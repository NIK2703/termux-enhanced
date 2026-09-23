package com.termux.shared.view;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.RequiresApi;
import androidx.core.view.WindowInsetsCompat;

import com.termux.shared.logger.Logger;

public class KeyboardUtils {

    private static final String LOG_TAG = "KeyboardUtils";

    private static InputMethodManager getInputMethodManager(final Context context) {
        return (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    private static boolean hasWindow(final Activity activity) {
        return activity != null && activity.getWindow() != null;
    }

    /**
     * Toggle the soft keyboard. The {@link InputMethodManager#SHOW_FORCED} is passed as
     * {@code showFlags} so that keyboard is forcefully shown if it needs to be enabled.
     *
     * This is also important for soft keyboard to be shown when a hardware keyboard is connected, and
     * user has disabled the {@code Show on-screen keyboard while hardware keyboard is connected} toggle
     * in Android "Language and Input" settings but the current soft keyboard app overrides the
     * default implementation of {@link InputMethodService#onEvaluateInputViewShown()} and returns
     * {@code true}.
     */
    public static void toggleSoftKeyboard(final Context context) {
        if (context == null) return;
        InputMethodManager inputMethodManager = getInputMethodManager(context);
        if (inputMethodManager != null)
            inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
    }

    /**
     * Show the soft keyboard. The {@code 0} value is passed as {@code flags} so that keyboard is
     * forcefully shown.
     *
     * This is also important for soft keyboard to be shown on app startup when a hardware keyboard
     * is connected, and user has disabled the {@code Show on-screen keyboard while hardware keyboard
     * is connected} toggle in Android "Language and Input" settings but the current soft keyboard app
     * overrides the default implementation of {@link InputMethodService#onEvaluateInputViewShown()}
     * and returns {@code true}.
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:frameworks/base/core/java/android/inputmethodservice/InputMethodService.java;l=1751
     *
     * Also check {@link InputMethodService#onShowInputRequested(int, boolean)} which must return
     * {@code true}, which can be done by failing its {@code ((flags&InputMethod.SHOW_EXPLICIT) == 0)}
     * check by passing {@code 0} as {@code flags}.
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:frameworks/base/core/java/android/inputmethodservice/InputMethodService.java;l=2022
     */
    public static void showSoftKeyboard(final Context context, final View view) {
        if (context == null || view == null) return;
        InputMethodManager inputMethodManager = getInputMethodManager(context);
        if (inputMethodManager != null)
            inputMethodManager.showSoftInput(view, 0);
    }

    public static void hideSoftKeyboard(final Context context, final View view) {
        if (context == null || view == null) return;
        InputMethodManager inputMethodManager = getInputMethodManager(context);
        if (inputMethodManager != null)
            inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    public static void disableSoftKeyboard(final Activity activity, final View view) {
        if (activity == null || view == null) return;
        hideSoftKeyboard(activity, view);
        setDisableSoftKeyboardFlags(activity);
    }

    public static void setDisableSoftKeyboardFlags(final Activity activity) {
        if (hasWindow(activity))
            activity.getWindow().setFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM, WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
    }

    public static void clearDisableSoftKeyboardFlags(final Activity activity) {
        if (hasWindow(activity))
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
    }

    public static boolean areDisableSoftKeyboardFlagsSet(final Activity activity) {
        if (!hasWindow(activity)) return false;
        return (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) != 0;
    }

    public static void setSoftKeyboardAlwaysHiddenFlags(final Activity activity) {
        setSoftInputMode(activity, WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    /**
     * Keep the IME from being shown by the platform when this window gains focus, while still
     * letting the window resize once a keyboard does appear.
     *
     * <p>Both halves matter and they are different bits of the same field, so neither of the two
     * single-purpose setters above can be used: {@link #setSoftKeyboardAlwaysHiddenFlags} replaces
     * the whole mode with {@code STATE_ALWAYS_HIDDEN}, dropping {@code ADJUST_RESIZE} (the keyboard
     * then overlays the content instead of resizing it), and {@link #setSoftInputModeAdjustResize}
     * does the opposite. Use this when the window must not auto-show the IME but is expected to
     * resize if the user opens it explicitly.
     */
    public static void setSoftKeyboardAlwaysHiddenAndAdjustResize(final Activity activity) {
        setSoftInputMode(activity, WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    public static void setSoftInputModeAdjustResize(final Activity activity) {
        // TODO: The flag is deprecated for API 30 and WindowInset API should be used
        // https://developer.android.com/reference/android/view/WindowManager.LayoutParams#SOFT_INPUT_ADJUST_RESIZE
        // https://medium.com/androiddevelopers/animating-your-keyboard-fb776a8fb66d
        // https://stackoverflow.com/a/65194077/14686958
        setSoftInputMode(activity, WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    /**
     * Set the window's {@code softInputMode}, skipping the call when it already holds that value.
     * <p>
     * {@code Window.setSoftInputMode()} unconditionally dispatches the (possibly unchanged)
     * attributes to the window, which ends in a {@code relayoutWindow()} binder call to the
     * window manager. The resume path alone asks for the very same mode three to five times
     * (onResume intent pre-emption, setSoftKeyboardState, runKeyboardRestore, the IME visibility
     * handler, showing the input panel), so the short-circuit removes several IPCs per cycle.
     * Reading {@code getAttributes()} is a plain field read — no IPC — so the check is free.
     */
    private static void setSoftInputMode(final Activity activity, final int mode) {
        if (!hasWindow(activity)) return;
        if (activity.getWindow().getAttributes().softInputMode == mode) return;
        activity.getWindow().setSoftInputMode(mode);
    }

    /**
     * Check if soft keyboard is visible.
     * Does not work on android 7 but does on android 11 avd.
     *
     * @param activity The Activity of the root view for which the visibility should be checked.
     * @return Returns {@code true} if soft keyboard is visible, otherwise {@code false}.
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    public static boolean isSoftKeyboardVisible(final Activity activity) {
        if (hasWindow(activity)) {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) {
                WindowInsetsCompat insetsCompat = WindowInsetsCompat.toWindowInsetsCompat(insets);
                if (insetsCompat.isVisible(WindowInsetsCompat.Type.ime())) {
                    Logger.logVerbose(LOG_TAG, "Soft keyboard visible");
                    return true;
                }
            }
        }

        Logger.logVerbose(LOG_TAG, "Soft keyboard not visible");
        return false;
    }

    /**
     * Check if hardware keyboard is connected.
     * Based on default implementation of {@link InputMethodService#onEvaluateInputViewShown()}.
     *
     * https://developer.android.com/guide/topics/resources/providing-resources#ImeQualifier
     *
     * @param context The Context for operations.
     * @return Returns {@code true} if device has hardware keys for text input or an external hardware
     * keyboard is connected, otherwise {@code false}.
     */
    public static boolean isHardKeyboardConnected(final Context context) {
        if (context == null) return false;

        Configuration config = context.getResources().getConfiguration();
        return config.keyboard != Configuration.KEYBOARD_NOKEYS
            || config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;
    }

    /**
     * Check if soft keyboard should be disabled based on user configuration.
     *
     * @param context The Context for operations.
     * @return Returns {@code true} if device has soft keyboard should be disabled, otherwise {@code false}.
     */
    public static boolean shouldSoftKeyboardBeDisabled(final Context context, final boolean isSoftKeyboardEnabled, final boolean isSoftKeyboardEnabledOnlyIfNoHardware) {
        // If soft keyboard is disabled by user regardless of hardware keyboard
        if (!isSoftKeyboardEnabled) {
            return true;
        } else {
            /*
             * When only-enabled-if-no-hardware is set, soft keyboard is disabled on app startup
             * and on return from another app; toggle buttons re-enable it until the next switch.
             * Also works around a Lineage OS bug: with "Show soft keyboard" disabled and
             * SOFT_INPUT_ADJUST_RESIZE, rotating landscape→portrait can leave a blank space
             * where the keyboard should be (never opens because it is disabled, but the window
             * still resizes). https://github.com/termux/termux-app/issues/1995#issuecomment-837080079
             */
            // If soft keyboard is disabled by user only if hardware keyboard is connected
            if(isSoftKeyboardEnabledOnlyIfNoHardware) {
                boolean isHardKeyboardConnected = KeyboardUtils.isHardKeyboardConnected(context);
                Logger.logVerbose(LOG_TAG, "Hardware keyboard connected=" + isHardKeyboardConnected);
                return isHardKeyboardConnected;
            } else {
                return false;
            }
        }
    }

}
