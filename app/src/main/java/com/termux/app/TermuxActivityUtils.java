package com.termux.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;

import androidx.annotation.NonNull;

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
     * <p>The full-screen window obeys Settings → Screen orientation. The bubble's window must not:
     * it is a floating window drawn in the orientation the device is actually in, so a
     * fixed-orientation request cannot resize it — the platform letterboxes the content into a strip
     * inside it instead. The request is therefore cleared there, and only when it differs from the
     * current value, so repeated calls stay idempotent (see the MIUI/HyperOS hazard at the call site
     * in {@link TermuxActivity#onWindowFocusChanged(boolean)}).
     *
     * <p>Every screen of the app asks this, not just the terminal: a screen opened from the bubble is
     * drawn inside the bubble's window and is letterboxed the same way.
     *
     * <p>Called from onCreate and onWindowFocusChanged.
     */
    public static void applyScreenOrientation(@NonNull Activity activity) {
        if (isBubbleWindow(activity)) {
            if (activity.getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                Logger.logDebug(LOG_TAG, "Clearing screen orientation: bubble window");
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
            return;
        }

        final SharedPreferences prefs = activity.getSharedPreferences("termux_prefs", Activity.MODE_PRIVATE);
        final boolean isTablet = activity.getResources().getConfiguration().smallestScreenWidthDp >= 600;
        final String value = prefs.getString("screen_orientation",
                isTablet ? "sensor" : "portrait");
        final int orientation;
        switch (value) {
            case "portrait":
                orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                break;
            case "landscape":
                orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                break;
            case "portrait_follow_sensor":
                orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
                break;
            case "landscape_follow_sensor":
                orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                break;
            case "sensor":
                orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR;
                break;
            default:
                orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                break;
        }
        activity.setRequestedOrientation(orientation);
    }

    /**
     * Whether the given activity is drawn in the bubble's floating window rather than in the app's
     * own full-screen one.
     *
     * <p>The bubble is a second instance of {@link TermuxActivity} and answers for itself. Any other
     * screen in that window — Settings, opened from the bubble's context menu — is an ordinary
     * activity of the app's stack and cannot know from its class where it is drawn, so the answer is
     * handed to it at launch: the window that opens it says which window it is, see
     * {@link #newSettingsIntent(TermuxActivity)}.
     */
    private static boolean isBubbleWindow(@NonNull Activity activity) {
        if (activity instanceof TermuxActivity)
            return ((TermuxActivity) activity).isBubbleWindow();

        final Intent intent = activity.getIntent();
        return intent != null && intent.getBooleanExtra(EXTRA_IN_BUBBLE_WINDOW, false);
    }

    /**
     * Intent for the Settings screen, marked with the window it is being opened into.
     *
     * <p>Everything opened from the bubble is drawn inside the bubble's window, which follows the
     * device instead of Settings → Screen orientation; see
     * {@link #applyScreenOrientation(Activity)}. The launching window is the only one that knows
     * which of the two it is, so it is what passes the answer on.
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
