package com.termux.app;

import android.app.Application;
import android.content.Context;

import com.termux.BuildConfig;
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

    public void onCreate() {
        super.onCreate();

        Context context = getApplicationContext();

        // Set crash handler for the app
        TermuxCrashUtils.setDefaultCrashHandler(this);

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

}
