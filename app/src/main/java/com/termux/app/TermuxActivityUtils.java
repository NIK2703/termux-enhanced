package com.termux.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.activities.SettingsActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;

public final class TermuxActivityUtils {

    private static final String LOG_TAG = "TermuxActivityUtils";

    /**
     * Set on a screen that is opened into the bubble's floating window instead of the app's own
     * full-screen one; see {@link #newSettingsIntent(TermuxActivity)}.
     */
    public static final String EXTRA_IN_BUBBLE_WINDOW = "com.termux.app.EXTRA_IN_BUBBLE_WINDOW";

    private TermuxActivityUtils() {}

    /**
     * Apply the rotation mode of the window the given activity is drawn in.
     *
     * <p>The full-screen window obeys Settings → Screen orientation, but never at the expense of
     * the system rotation lock: while the lock is on, the locked orientation wins over every app
     * mode, including the explicit Portrait/Landscape choices. See
     * {@link #resolveScreenOrientation(String, boolean, boolean)} for the mapping.</p>
     *
     * <p>The bubble's window must not take part in any of this: it is a floating window drawn in
     * the device's orientation, so a fixed-orientation request cannot resize it — the platform
     * letterboxes the content instead. The request is cleared there, only when it differs
     * (idempotent; see the MIUI/HyperOS hazard in
     * {@link TermuxActivity#onWindowFocusChanged(boolean)}). Every screen opened from the bubble
     * is letterboxed the same way, so all of them ask this — from onCreate and onWindowFocusChanged.
     */
    public static void applyScreenOrientation(@NonNull Activity activity) {
        if (isBubbleWindow(activity)) {
            if (activity.getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                Logger.logDebug(LOG_TAG, "Clearing screen orientation: bubble window");
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
            return;
        }

        final SharedPreferences prefs = activity.getSharedPreferences(TermuxAppConstants.Preferences.FILE_NAME, Activity.MODE_PRIVATE);
        final boolean isTablet = activity.getResources().getConfiguration().smallestScreenWidthDp >= 600;
        final String value = prefs.getString(TermuxAppConstants.Preferences.SCREEN_ORIENTATION,
                isTablet ? "sensor" : "portrait");
        final int orientation = resolveScreenOrientation(value, isTablet,
                isSystemAutoRotateEnabled(activity));
        // setRequestedOrientation() is not free on some ROMs (see the MIUI/HyperOS note in
        // onWindowFocusChanged()), and this runs on every focus. Only request a real change.
        if (activity.getRequestedOrientation() != orientation) {
            activity.setRequestedOrientation(orientation);
        }
    }

    /**
     * Resolve the value stored by Settings → Screen orientation to an {@link ActivityInfo}
     * orientation request.
     *
     * <p>The sensor-following modes use the {@code USER} family, not the {@code SENSOR} family.
     * {@code SENSOR}, {@code SENSOR_PORTRAIT} and {@code SENSOR_LANDSCAPE} explicitly ignore the
     * user's system rotation lock, so the app kept rotating with auto-rotate turned off.
     * {@code USER}, {@code USER_PORTRAIT} and {@code USER_LANDSCAPE} use the sensor only while
     * sensor-based rotation is enabled and otherwise keep the locked orientation.</p>
     *
     * <p>While the system lock is on, every mode resolves to {@link
     * ActivityInfo#SCREEN_ORIENTATION_USER}: the lock is the user's instruction for the whole
     * display, so it also outranks the app's explicit Portrait/Landscape choices. Once the lock is
     * turned off, the stored app mode applies again (the observer in {@link
     * SystemRotationLockObserver} re-applies it without waiting for a restart).</p>
     *
     * <p>Package-private and free of Android calls so the mapping is unit-testable.</p>
     */
    static int resolveScreenOrientation(@Nullable String value, boolean isTablet,
                                        boolean systemAutoRotateEnabled) {
        final String mode = value != null ? value : (isTablet ? "sensor" : "portrait");

        if (!systemAutoRotateEnabled) {
            return ActivityInfo.SCREEN_ORIENTATION_USER;
        }

        switch (mode) {
            case "portrait":
                return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            case "landscape":
                return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            case "portrait_follow_sensor":
                return ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
            case "landscape_follow_sensor":
                return ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
            case "sensor":
                return ActivityInfo.SCREEN_ORIENTATION_USER;
            default:
                return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
    }

    /**
     * Whether sensor-based rotation is enabled in system settings. The rotation lock is stored as
     * {@link Settings.System#ACCELEROMETER_ROTATION} == 0. If the setting cannot be read (some
     * vendor ROMs), assume rotation is enabled so the app's own mode still works.
     */
    static boolean isSystemAutoRotateEnabled(@NonNull Context context) {
        try {
            return Settings.System.getInt(context.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 1) == 1;
        } catch (SecurityException e) {
            Logger.logDebug(LOG_TAG, "Cannot read system auto-rotate setting: " + e.getMessage());
            return true;
        }
    }

    /**
     * Re-applies the screen orientation when the system rotation lock is toggled while the activity
     * is visible.
     *
     * <p>The platform re-evaluates {@code USER} requests by itself, but an app-level fixed request
     * (Portrait/Landscape) keeps forcing its orientation after the lock is turned on. The reverse
     * is true as well: a {@code USER} request made while locked starts following the sensor instead
     * of the stored app mode after the lock is turned off. Re-resolving on every change keeps the
     * two in step without waiting for a restart or a focus round-trip.</p>
     */
    public static final class SystemRotationLockObserver {
        private final Activity mActivity;
        private final ContentObserver mObserver;
        private boolean mStarted;

        public SystemRotationLockObserver(@NonNull Activity activity) {
            mActivity = activity;
            mObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    // A change dispatched just before stop() can still be queued on the main
                    // thread. Do not touch a window that is stopped or already going away.
                    if (mStarted && !mActivity.isFinishing() && !mActivity.isDestroyed())
                        applyScreenOrientation(mActivity);
                }
            };
        }

        public void start() {
            if (mStarted) return;
            mActivity.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
                    false, mObserver);
            mStarted = true;
        }

        public void stop() {
            if (!mStarted) return;
            mActivity.getContentResolver().unregisterContentObserver(mObserver);
            mStarted = false;
        }
    }

    /**
     * Whether the given activity is drawn in the bubble's floating window rather than in the app's
     * own full-screen one.
     *
     * <p>The bubble is a second {@link TermuxActivity} and answers for itself. Other screens in
     * that window (e.g. Settings) cannot know from their class where they are drawn, so the answer
     * is handed to them at launch via {@link #newSettingsIntent(TermuxActivity)}.
     */
    private static boolean isBubbleWindow(@NonNull Activity activity) {
        if (activity instanceof TermuxActivity)
            return ((TermuxActivity) activity).isBubbleWindow();

        final Intent intent = activity.getIntent();
        return intent != null && intent.getBooleanExtra(EXTRA_IN_BUBBLE_WINDOW, false);
    }

    /**
     * Intent for the Settings screen, marked with the window it is being opened into (see
     * {@link #applyScreenOrientation(Activity)}). The launching window is the only one that knows
     * which window it is, so it passes the answer on.
     */
    public static Intent newSettingsIntent(@NonNull TermuxActivity from) {
        final Intent intent = new Intent(from, SettingsActivity.class);
        intent.putExtra(EXTRA_IN_BUBBLE_WINDOW, from.isBubbleWindow());
        return intent;
    }

    /**
     * Send a broadcast to reload styling. Static so it can be called from dialogs that
     * don't hold an Activity reference.
     */
    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    /**
     * Start TermuxActivity with a fresh task.
     */
    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    /**
     * Create a new intent to launch TermuxActivity.
     */
    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /**
     * Start TermuxActivity and close all existing terminal sessions, opening a fresh one.
     * Used after a data restore so the user does not keep stale sessions.
     */
    public static void startTermuxActivityWithSessionReset(@NonNull final Context context) {
        Intent intent = newInstance(context);
        intent.putExtra(TERMUX_ACTIVITY.EXTRA_RESET_SESSIONS, true);
        ActivityUtils.startActivity(context, intent);
    }

    /**
     * Finish the activity preventing duplicate finish() calls.
     */
    public static void finishActivityIfNotFinishing(@NonNull Activity activity) {
        if (!activity.isFinishing()) {
            activity.finish();
        }
    }

    /**
     * Convert dp to pixels.
     */
    public static int dpToPx(@NonNull Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
