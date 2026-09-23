package com.termux.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import com.termux.BuildConfig;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxBootstrap;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.termux.shell.am.TermuxAmSocketServer;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.theme.TermuxThemeUtils;

public class TermuxApplication extends Application {

    private static final String LOG_TAG = "TermuxApplication";

    /**
     * How many of this app's activities are currently in the started state (i.e. at least visible).
     *
     * <p>This exists to answer one question that no single activity can answer on its own: when the
     * terminal window goes to {@code onStop()}, did the user leave the app, or did the app simply put
     * another of its own screens (Settings, Help, the bootstrap selector) in front of it? Only the
     * second case has {@code count > 0} once our own stop has been accounted for.
     *
     * <p>The bubble's own window is a second instance of the same activity class, so it is counted
     * here as well; the auto-open code never consults this from inside the bubble window.
     *
     * <p>Written and read on the main thread only (the activity lifecycle callbacks are dispatched
     * there), so no synchronization is needed.
     */
    private static int sStartedActivityCount;

    /** @return the number of this app's activities currently started. Main thread only. */
    public static int getStartedActivityCount() {
        return sStartedActivityCount;
    }

    public void onCreate() {
        super.onCreate();

        Context context = getApplicationContext();

        registerActivityLifecycleCallbacks(new StartedActivityCounter());

        // Set crash handler for the app
        TermuxCrashUtils.setDefaultCrashHandler(this);

        // Add the container/settings backup buttons to the app crash report screen. The host is a
        // process-wide static of the shared ReportActivity, and ReportActivity instances always live
        // in this process, so registering it here covers every way the crash report can be opened
        // (crash notification, recents, ...).
        ReportActivity.setReportActionHost(new TermuxReportActionHost());

        // Set log config for the app
        setLogConfig(context);

        Logger.logDebug("Starting Application");

        // Set TermuxBootstrap.TERMUX_APP_PACKAGE_MANAGER and TermuxBootstrap.TERMUX_APP_PACKAGE_VARIANT.
        // Use runtime variant if bootstrap was downloaded; fall back to BuildConfig default.
        TermuxBootstrap.initializeFromRuntime(this, BuildConfig.TERMUX_PACKAGE_VARIANT);

        // Migrate any legacy ~/.termux/termux.properties configuration into SharedPreferences.
        TermuxAppSharedProperties.migrateLegacyTermuxProperties(context);

        // Init app wide SharedProperties loaded from SharedPreferences
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.init(context);

        // Init app wide shell manager
        TermuxShellManager shellManager = TermuxShellManager.init(context);

        // Set NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(properties.getNightMode());

        // Apply the app display language (per-app locale) at startup so the chosen
        // language (e.g. Russian) is used everywhere without re-selecting it.
        TermuxLocaleUtils.applyLocale(TermuxLocaleUtils.getLocaleOverride());

        // Notify about a crash of a previous run as early as possible — deliberately here, and not
        // in TermuxActivity, because that only runs after the whole terminal UI (view, sessions,
        // pager, IME state) has been created: a crash during start-up — exactly the case where a
        // crash report is most needed — would then never produce a notification at all. Reading the
        // crash log and posting the notification happen on a background thread, so the app start is
        // not delayed. Called after the locale is applied so the notification is in the user's
        // language, and before the files directory checks below, which can return early.
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        // Init TermuxShellEnvironment constants and caches BEFORE any early return,
        // so that getDefaultWorkingDirectoryPath() always returns runtime-resolved paths
        // (not compile-time /data/data/com.termux/...) even when other setup fails.
        TermuxShellEnvironment.init(this);

        // Check and create termux files directory. If failed to access it like in case of secondary
        // user or external sd card installation, then don't run files directory related code
        Error error = TermuxFileUtils.isTermuxFilesDirectoryAccessible(this, true, true);
        boolean isTermuxFilesDirectoryAccessible = error == null;
        if (isTermuxFilesDirectoryAccessible) {
            Logger.logInfo(LOG_TAG, "Termux files directory is accessible");

            try {
                TermuxInstaller.ensureCompatSymlinks(this);
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to ensure Termux compat symlinks: " + e.getMessage());
            }

            error = TermuxFileUtils.isAppsTermuxAppDirectoryAccessible(true, true);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Create apps/termux-app directory failed\n" + error);
                return;
            }

            // Setup termux-am-socket server
            TermuxAmSocketServer.setupTermuxAmSocketServer(context);

            // Off the main thread: several PackageManager/ActivityManager Binder calls plus two
            // file writes that need not block cold start. The first run (no env file yet) stays
            // synchronous inside writeEnvironmentToFileAsync so a plugin cannot see a missing env.
            TermuxShellEnvironment.writeEnvironmentToFileAsync(this);
        } else {
            Logger.logErrorExtended(LOG_TAG, "Termux files directory is not accessible\n" + error);
        }
    }

    public static void setLogConfig(Context context) {
        Logger.setDefaultLogTag(TermuxConstants.TERMUX_APP_NAME);

        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context);
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
    }

    /**
     * Keeps {@link #sStartedActivityCount} in step with the app's activities.
     *
     * <p>Registered for every activity of the app, including ones that have nothing to do with the
     * terminal, because that is the whole point: the terminal window has to be able to tell whether
     * one of them is on screen when it is itself stopped.
     *
     * <p>The pair {@code onActivityStarted}/{@code onActivityStopped} is always balanced, so the
     * counter cannot drift; the clamp below only guards against a callback arriving for an activity
     * that was started before this application object registered its callbacks (which cannot happen
     * in practice, since registration happens in {@code onCreate()}).
     */
    private static final class StartedActivityCounter implements ActivityLifecycleCallbacks {

        @Override
        public void onActivityStarted(Activity activity) {
            sStartedActivityCount++;
            Logger.logVerbose(LOG_TAG, "Activity started: " + activity.getClass().getSimpleName()
                    + ", started count: " + sStartedActivityCount);
        }

        @Override
        public void onActivityStopped(Activity activity) {
            if (sStartedActivityCount > 0) sStartedActivityCount--;
            Logger.logVerbose(LOG_TAG, "Activity stopped: " + activity.getClass().getSimpleName()
                    + ", started count: " + sStartedActivityCount);
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

        @Override
        public void onActivityResumed(Activity activity) {}

        @Override
        public void onActivityPaused(Activity activity) {}

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

        @Override
        public void onActivityDestroyed(Activity activity) {}
    }

}
