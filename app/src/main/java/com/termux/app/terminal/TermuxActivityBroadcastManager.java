package com.termux.app.terminal;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.annotation.NonNull;

import com.termux.app.TermuxActivity;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.shared.termux.crash.TermuxCrashUtils;

import java.lang.ref.WeakReference;

public class TermuxActivityBroadcastManager {

    private static final String LOG_TAG = "TermuxActivity";

    private static final String ACTION_TEXT_INPUT_VISIBILITY_CHANGED = "com.termux.TEXT_INPUT_VISIBILITY_CHANGED";
    private static final String ACTION_TEXT_INPUT_ENABLED_CHANGED = "com.termux.TEXT_INPUT_ENABLED_CHANGED";
    private static final String ACTION_TAB_PANEL_POSITION_CHANGED = "com.termux.TAB_PANEL_POSITION_CHANGED";
    private static final String ACTION_TAB_HEIGHT_MODE_CHANGED = "com.termux.TAB_HEIGHT_MODE_CHANGED";
    /**
     * The terminal font size was changed outside the activity (the Display settings slider).
     *
     * <p>A dedicated action rather than a styling reload: {@code ACTION_RELOAD_STYLE} would run a
     * full property reload, an extra-keys rebuild and a colour-scheme re-apply just to carry one
     * number across, and the terminal only has to re-apply the size to the pages it keeps bound.
     */
    private static final String ACTION_FONT_SIZE_CHANGED = "com.termux.FONT_SIZE_CHANGED";

    private final Activity mActivity;
    private final BroadcastReceiver mReceiver;

    public TermuxActivityBroadcastManager(Activity activity) {
        mActivity = activity;
        mReceiver = new TermuxActivityBroadcastReceiver(activity);
    }

    public void register() {
        mActivity.registerReceiver(mReceiver, createIntentFilter());
    }

    public void unregister() {
        mActivity.unregisterReceiver(mReceiver);
    }

    /**
     * Ask a live {@link TermuxActivity} to re-apply the configured terminal font size to every page
     * its pager keeps bound.
     *
     * <p>Sent by the Display settings slider. Loss-free by design: if no activity is alive the
     * broadcast is simply not received, and the pages pick the size up from the preferences the
     * next time they are bound — which is also what happens after an activity recreate.
     */
    public static void notifyFontSizeChanged(@NonNull Context context) {
        Intent intent = new Intent(ACTION_FONT_SIZE_CHANGED);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    public IntentFilter createIntentFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        intentFilter.addAction(ACTION_TEXT_INPUT_VISIBILITY_CHANGED);
        intentFilter.addAction(ACTION_TEXT_INPUT_ENABLED_CHANGED);
        intentFilter.addAction(ACTION_TAB_PANEL_POSITION_CHANGED);
        intentFilter.addAction(ACTION_TAB_HEIGHT_MODE_CHANGED);
        intentFilter.addAction(ACTION_FONT_SIZE_CHANGED);
        return intentFilter;
    }

    private static void fixTermuxActivityBroadcastReceiverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    private static class TermuxActivityBroadcastReceiver extends BroadcastReceiver {

        private final WeakReference<Activity> mActivityRef;

        TermuxActivityBroadcastReceiver(Activity activity) {
            mActivityRef = new WeakReference<>(activity);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            Activity activity = mActivityRef.get();
            if (!(activity instanceof TermuxActivity)) return;
            TermuxActivity termuxActivity = (TermuxActivity) activity;

            String action = intent.getAction();

            if (ACTION_TEXT_INPUT_VISIBILITY_CHANGED.equals(action)) {
                boolean visible = intent.getBooleanExtra("visible", true);
                termuxActivity.setTextInputVisible(visible);
                return;
            }

            if (ACTION_TEXT_INPUT_ENABLED_CHANGED.equals(action)) {
                termuxActivity.updateToggleTextInputButtonVisibility();
                return;
            }

            if (ACTION_TAB_PANEL_POSITION_CHANGED.equals(action)) {
                termuxActivity.applyTabPanelPosition();
                return;
            }

            if (ACTION_TAB_HEIGHT_MODE_CHANGED.equals(action)) {
                termuxActivity.applyTabHeightMode();
                return;
            }

            if (ACTION_FONT_SIZE_CHANGED.equals(action)) {
                termuxActivity.applyTerminalFontSize();
                return;
            }

            fixTermuxActivityBroadcastReceiverIntent(intent);
            action = intent.getAction();

            if (TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS.equals(action)) {
                termuxActivity.requestStoragePermission(false);
                return;
            }

            if (TERMUX_ACTIVITY.ACTION_RELOAD_STYLE.equals(action)) {
                termuxActivity.reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, false));
                return;
            }

            switch (action) {
                case TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH:
                    TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG);
                    return;
                default:
            }
        }
    }
}
