package com.termux.shared.termux.settings.preferences;

import android.content.Context;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.android.PackageUtils;
import com.termux.shared.settings.preferences.AppSharedPreferences;
import com.termux.shared.settings.preferences.SharedPreferenceUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.logger.Logger;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP;

public class TermuxAppSharedPreferences extends AppSharedPreferences {

    private int MIN_FONTSIZE;
    private int MAX_FONTSIZE;
    private int DEFAULT_FONTSIZE;

    private static final String LOG_TAG = "TermuxAppSharedPreferences";

    private TermuxAppSharedPreferences(@NonNull Context context) {
        super(context,
            SharedPreferenceUtils.getPrivateSharedPreferences(context,
                TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION),
            SharedPreferenceUtils.getPrivateAndMultiProcessSharedPreferences(context,
                TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION));

        setFontVariables(context);
    }

    /**
     * Get {@link TermuxAppSharedPreferences}.
     *
     * @param context The {@link Context} to use to get the {@link Context} of the
     *                {@link TermuxConstants#TERMUX_PACKAGE_NAME}.
     * @return Returns the {@link TermuxAppSharedPreferences}. This will {@code null} if an exception is raised.
     */
    @Nullable
    public static TermuxAppSharedPreferences build(@NonNull final Context context) {
        Context termuxPackageContext;
        if (TermuxConstants.TERMUX_PACKAGE_NAME.equals(context.getPackageName())) {
            termuxPackageContext = PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_PACKAGE_NAME);
        } else {
            termuxPackageContext = context;
        }
        if (termuxPackageContext == null)
            return null;
        else
            return new TermuxAppSharedPreferences(termuxPackageContext);
    }

    /**
     * Get {@link TermuxAppSharedPreferences}.
     *
     * @param context The {@link Context} to use to get the {@link Context} of the
     *                {@link TermuxConstants#TERMUX_PACKAGE_NAME}.
     * @param exitAppOnError If {@code true} and failed to get package context, then a dialog will
     *                       be shown which when dismissed will exit the app.
     * @return Returns the {@link TermuxAppSharedPreferences}. This will {@code null} if an exception is raised.
     */
    public static TermuxAppSharedPreferences build(@NonNull final Context context, final boolean exitAppOnError) {
        Context termuxPackageContext;
        if (TermuxConstants.TERMUX_PACKAGE_NAME.equals(context.getPackageName())) {
            termuxPackageContext = TermuxUtils.getContextForPackageOrExitApp(context, TermuxConstants.TERMUX_PACKAGE_NAME, exitAppOnError);
        } else {
            termuxPackageContext = context;
        }
        if (termuxPackageContext == null)
            return null;
        else
            return new TermuxAppSharedPreferences(termuxPackageContext);
    }



    public boolean shouldShowTerminalToolbar() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_SHOW_TERMINAL_TOOLBAR, TERMUX_APP.DEFAULT_VALUE_SHOW_TERMINAL_TOOLBAR);
    }

    public void setShowTerminalToolbar(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_SHOW_TERMINAL_TOOLBAR, value, false);
    }

    public boolean toogleShowTerminalToolbar() {
        boolean currentValue = shouldShowTerminalToolbar();
        setShowTerminalToolbar(!currentValue);
        return !currentValue;
    }


    /**
     * Get whether extra keys should be hidden when the soft keyboard is hidden.
     *
     * @return Returns {@code true} if extra keys should be hidden with keyboard.
     */
    public boolean shouldHideExtraKeysWithKeyboard() {
        if (!SharedPreferenceUtils.isKeyPresent(mSharedPreferences, TERMUX_APP.KEY_HIDE_EXTRA_KEYS_WITH_KEYBOARD)) {
            setHideExtraKeysWithKeyboard(TERMUX_APP.DEFAULT_VALUE_HIDE_EXTRA_KEYS_WITH_KEYBOARD);
        }
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_HIDE_EXTRA_KEYS_WITH_KEYBOARD, TERMUX_APP.DEFAULT_VALUE_HIDE_EXTRA_KEYS_WITH_KEYBOARD);
    }

    public void setHideExtraKeysWithKeyboard(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_HIDE_EXTRA_KEYS_WITH_KEYBOARD, value, false);
    }


    public boolean shouldTextInputAppendEnter() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_TEXT_INPUT_APPEND_ENTER, TERMUX_APP.DEFAULT_VALUE_TEXT_INPUT_APPEND_ENTER);
    }

    public void setTextInputAppendEnter(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_TEXT_INPUT_APPEND_ENTER, value, false);
    }


    public boolean shouldTextInputHideOnSend() {
        String action = getTextInputActionOnSend();
        return action.equals(TERMUX_APP.TEXT_INPUT_ACTION_ON_SEND_HIDE_PANEL)
            || action.equals(TERMUX_APP.TEXT_INPUT_ACTION_ON_SEND_HIDE_KEYBOARD);
    }

    public boolean shouldTextInputHideKeyboardOnSend() {
        return getTextInputActionOnSend().equals(TERMUX_APP.TEXT_INPUT_ACTION_ON_SEND_HIDE_KEYBOARD);
    }

    @NonNull
    public String getTextInputActionOnSend() {
        String value = SharedPreferenceUtils.getString(mSharedPreferences, TERMUX_APP.KEY_TEXT_INPUT_ACTION_ON_SEND, TERMUX_APP.DEFAULT_VALUE_TEXT_INPUT_ACTION_ON_SEND, false);
        if (value == null) value = TERMUX_APP.DEFAULT_VALUE_TEXT_INPUT_ACTION_ON_SEND;
        switch (value) {
            case TERMUX_APP.TEXT_INPUT_ACTION_ON_SEND_NONE:
            case TERMUX_APP.TEXT_INPUT_ACTION_ON_SEND_HIDE_PANEL:
            case TERMUX_APP.TEXT_INPUT_ACTION_ON_SEND_HIDE_KEYBOARD:
                return value;
            default:
                return TERMUX_APP.DEFAULT_VALUE_TEXT_INPUT_ACTION_ON_SEND;
        }
    }

    public void setTextInputActionOnSend(@NonNull String value) {
        SharedPreferenceUtils.setString(mSharedPreferences, TERMUX_APP.KEY_TEXT_INPUT_ACTION_ON_SEND, value, false);
    }

    public boolean shouldInsertAtCursorOnHistoryPick() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_TEXT_INPUT_INSERT_AT_CURSOR, TERMUX_APP.DEFAULT_VALUE_TEXT_INPUT_INSERT_AT_CURSOR);
    }

    public void setInsertAtCursorOnHistoryPick(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_TEXT_INPUT_INSERT_AT_CURSOR, value, false);
    }

    /**
     * Get the maximum number of auto-complete suggestions to show in the text input popup.
     *
     * @return Returns the max suggestions count (clamped to 1-10, default 4).
     */
    public int getSuggestionsMaxCount() {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_APP.KEY_SUGGESTIONS_MAX_COUNT, TERMUX_APP.DEFAULT_VALUE_SUGGESTIONS_MAX_COUNT);
    }

    public void setSuggestionsMaxCount(int value) {
        if (value < TERMUX_APP.SUGGESTIONS_MAX_COUNT_MIN) value = TERMUX_APP.SUGGESTIONS_MAX_COUNT_MIN;
        if (value > TERMUX_APP.SUGGESTIONS_MAX_COUNT_MAX) value = TERMUX_APP.SUGGESTIONS_MAX_COUNT_MAX;
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_SUGGESTIONS_MAX_COUNT, value, false);
    }


    public boolean isTerminalMarginAdjustmentEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_MARGIN_ADJUSTMENT, TERMUX_APP.DEFAULT_TERMINAL_MARGIN_ADJUSTMENT);
    }

    public void setTerminalMarginAdjustment(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_MARGIN_ADJUSTMENT, value, false);
    }



    public boolean isSoftKeyboardEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED, TERMUX_APP.DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED);
    }

    public void setSoftKeyboardEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED, value, false);
    }

    public boolean isKeyboardStateFollowTabSwitch() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_KEYBOARD_STATE_FOLLOW_TAB_SWITCH, TERMUX_APP.DEFAULT_VALUE_KEYBOARD_STATE_FOLLOW_TAB_SWITCH);
    }

    public void setKeyboardStateFollowTabSwitch(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_KEYBOARD_STATE_FOLLOW_TAB_SWITCH, value, false);
    }

    public boolean isSoftKeyboardEnabledOnlyIfNoHardware() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE, TERMUX_APP.DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE);
    }

    public void setSoftKeyboardEnabledOnlyIfNoHardware(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE, value, false);
    }



    public boolean shouldKeepScreenOn() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_KEEP_SCREEN_ON, TERMUX_APP.DEFAULT_VALUE_KEEP_SCREEN_ON);
    }

    public void setKeepScreenOn(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_KEEP_SCREEN_ON, value, false);
    }



    public static int[] getDefaultFontSizes(Context context) {
        float dipInPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, context.getResources().getDisplayMetrics());

        int[] sizes = new int[3];

        // This is a bit arbitrary and sub-optimal. We want to give a sensible default for minimum font size
        // to prevent invisible text due to zoom be mistake:
        sizes[1] = (int) (4f * dipInPixels); // min

        // http://www.google.com/design/spec/style/typography.html#typography-line-height
        int defaultFontSize = Math.round(12 * dipInPixels);
        // Make it divisible by 2 since that is the minimal adjustment step:
        if (defaultFontSize % 2 == 1) defaultFontSize--;

        sizes[0] = defaultFontSize; // default

        sizes[2] = 256; // max

        return sizes;
    }

    public void setFontVariables(Context context) {
        int[] sizes = getDefaultFontSizes(context);

        DEFAULT_FONTSIZE = sizes[0];
        MIN_FONTSIZE = sizes[1];
        MAX_FONTSIZE = sizes[2];
    }

    public int getFontSize() {
        int fontSize = SharedPreferenceUtils.getIntStoredAsString(mSharedPreferences, TERMUX_APP.KEY_FONTSIZE, DEFAULT_FONTSIZE);
        return DataUtils.clamp(fontSize, MIN_FONTSIZE, MAX_FONTSIZE);
    }

    public void setFontSize(int value) {
        SharedPreferenceUtils.setIntStoredAsString(mSharedPreferences, TERMUX_APP.KEY_FONTSIZE, value, false);
    }

    public void changeFontSize(boolean increase) {
        int fontSize = getFontSize();

        fontSize += (increase ? 1 : -1) * 2;
        fontSize = Math.max(MIN_FONTSIZE, Math.min(fontSize, MAX_FONTSIZE));

        setFontSize(fontSize);
    }



    public String getCurrentSession() {
        return SharedPreferenceUtils.getString(mSharedPreferences, TERMUX_APP.KEY_CURRENT_SESSION, null, true);
    }

    public void setCurrentSession(String value) {
        SharedPreferenceUtils.setString(mSharedPreferences, TERMUX_APP.KEY_CURRENT_SESSION, value, false);
    }



    public int getLogLevel() {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL);
    }

    public void setLogLevel(Context context, int logLevel) {
        logLevel = Logger.setLogLevel(context, logLevel);
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_LOG_LEVEL, logLevel, false);
    }



    public int getLastNotificationId() {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_APP.KEY_LAST_NOTIFICATION_ID, TERMUX_APP.DEFAULT_VALUE_KEY_LAST_NOTIFICATION_ID);
    }

    public void setLastNotificationId(int notificationId) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_LAST_NOTIFICATION_ID, notificationId, false);
    }


    public synchronized int getAndIncrementAppShellNumberSinceBoot() {
        // Keep value at MAX_VALUE on integer overflow and not 0, since not first shell
        return SharedPreferenceUtils.getAndIncrementInt(mSharedPreferences, TERMUX_APP.KEY_APP_SHELL_NUMBER_SINCE_BOOT,
            TERMUX_APP.DEFAULT_VALUE_APP_SHELL_NUMBER_SINCE_BOOT, true, Integer.MAX_VALUE);
    }

    public synchronized void resetAppShellNumberSinceBoot() {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_APP_SHELL_NUMBER_SINCE_BOOT,
            TERMUX_APP.DEFAULT_VALUE_APP_SHELL_NUMBER_SINCE_BOOT, true);
    }

    public synchronized int getAndIncrementTerminalSessionNumberSinceBoot() {
        // Keep value at MAX_VALUE on integer overflow and not 0, since not first shell
        return SharedPreferenceUtils.getAndIncrementInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_SESSION_NUMBER_SINCE_BOOT,
            TERMUX_APP.DEFAULT_VALUE_TERMINAL_SESSION_NUMBER_SINCE_BOOT, true, Integer.MAX_VALUE);
    }

    public synchronized void resetTerminalSessionNumberSinceBoot() {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_SESSION_NUMBER_SINCE_BOOT,
            TERMUX_APP.DEFAULT_VALUE_TERMINAL_SESSION_NUMBER_SINCE_BOOT, true);
    }


    public boolean isTerminalViewKeyLoggingEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED, TERMUX_APP.DEFAULT_VALUE_TERMINAL_VIEW_KEY_LOGGING_ENABLED);
    }

    public void setTerminalViewKeyLoggingEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED, value, false);
    }



    public boolean arePluginErrorNotificationsEnabled(boolean readFromFile) {
        if (readFromFile)
            return SharedPreferenceUtils.getBoolean(mMultiProcessSharedPreferences, TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_PLUGIN_ERROR_NOTIFICATIONS_ENABLED);
        else
            return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_PLUGIN_ERROR_NOTIFICATIONS_ENABLED);
    }

    public void setPluginErrorNotificationsEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED, value, false);
    }





    public int getButtonBgInactiveAlpha() {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_APP.KEY_BUTTON_BG_INACTIVE_ALPHA, TERMUX_APP.DEFAULT_BUTTON_BG_INACTIVE_ALPHA);
    }

    public void setButtonBgInactiveAlpha(int value) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_BUTTON_BG_INACTIVE_ALPHA, value, false);
    }

    public int getButtonBgActiveAlpha() {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_APP.KEY_BUTTON_BG_ACTIVE_ALPHA, TERMUX_APP.DEFAULT_BUTTON_BG_ACTIVE_ALPHA);
    }

    public void setButtonBgActiveAlpha(int value) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_BUTTON_BG_ACTIVE_ALPHA, value, false);
    }


    public boolean areCrashReportNotificationsEnabled(boolean readFromFile) {
        if (readFromFile)
            return SharedPreferenceUtils.getBoolean(mMultiProcessSharedPreferences, TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_CRASH_REPORT_NOTIFICATIONS_ENABLED);
       else
            return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_CRASH_REPORT_NOTIFICATIONS_ENABLED);
    }

    public void setCrashReportNotificationsEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED, value, false);
    }


    /* #######################################################################
     * Settings migrated from the ~/.termux/termux.properties file.
     * Keys intentionally match the old termux.properties keys so that values
     * can be migrated on first launch.
     * ####################################################################### */

    /* boolean */

    public boolean shouldAllowExternalApps() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_ALLOW_EXTERNAL_APPS, TERMUX_APP.DEFAULT_VALUE_ALLOW_EXTERNAL_APPS);
    }

    public void setAllowExternalApps(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_ALLOW_EXTERNAL_APPS, value, false);
    }


    public boolean isFileShareReceiverDisabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_DISABLE_FILE_SHARE_RECEIVER, TERMUX_APP.DEFAULT_VALUE_DISABLE_FILE_SHARE_RECEIVER);
    }

    public void setFileShareReceiverDisabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_DISABLE_FILE_SHARE_RECEIVER, value, false);
    }


    public boolean isFileViewReceiverDisabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_DISABLE_FILE_VIEW_RECEIVER, TERMUX_APP.DEFAULT_VALUE_DISABLE_FILE_VIEW_RECEIVER);
    }

    public void setFileViewReceiverDisabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_DISABLE_FILE_VIEW_RECEIVER, value, false);
    }


    public boolean areHardwareKeyboardShortcutsDisabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS, TERMUX_APP.DEFAULT_VALUE_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS);
    }

    public void setHardwareKeyboardShortcutsDisabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS, value, false);
    }


    public boolean areTerminalSessionChangeToastsDisabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST, TERMUX_APP.DEFAULT_VALUE_DISABLE_TERMINAL_SESSION_CHANGE_TOAST);
    }

    public void setTerminalSessionChangeToastsDisabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST, value, false);
    }


    public boolean isEnforcingCharBasedInput() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_ENFORCE_CHAR_BASED_INPUT, TERMUX_APP.DEFAULT_VALUE_ENFORCE_CHAR_BASED_INPUT);
    }

    public void setEnforceCharBasedInput(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_ENFORCE_CHAR_BASED_INPUT, value, false);
    }


    public boolean shouldExtraKeysTextBeAllCaps() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_EXTRA_KEYS_TEXT_ALL_CAPS, TERMUX_APP.DEFAULT_VALUE_EXTRA_KEYS_TEXT_ALL_CAPS);
    }

    public void setExtraKeysTextAllCaps(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_EXTRA_KEYS_TEXT_ALL_CAPS, value, false);
    }

    public boolean isExtraKeysDynamicFontSizeEnabled(Context context) {
        boolean isTablet = context.getResources().getConfiguration().smallestScreenWidthDp >= 600;
        return SharedPreferenceUtils.getBoolean(mSharedPreferences,
            TERMUX_APP.KEY_EXTRA_KEYS_DYNAMIC_FONT_SIZE, !isTablet);
    }

    public void setExtraKeysDynamicFontSize(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences,
            TERMUX_APP.KEY_EXTRA_KEYS_DYNAMIC_FONT_SIZE, value, false);
    }


    public boolean isExtraKeysEdgeIndicatorsEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences,
            TERMUX_APP.KEY_EXTRA_KEYS_EDGE_INDICATORS, TERMUX_APP.DEFAULT_VALUE_EXTRA_KEYS_EDGE_INDICATORS);
    }

    public void setExtraKeysEdgeIndicatorsEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences,
            TERMUX_APP.KEY_EXTRA_KEYS_EDGE_INDICATORS, value, false);
    }


    public boolean isScrollOnNewOutputEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences,
            TERMUX_APP.KEY_SCROLL_ON_NEW_OUTPUT,
            TERMUX_APP.DEFAULT_VALUE_SCROLL_ON_NEW_OUTPUT);
    }

    public void setScrollOnNewOutputEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences,
            TERMUX_APP.KEY_SCROLL_ON_NEW_OUTPUT, value, false);
    }


    public boolean shouldSoftKeyboardBeHiddenOnStartup() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP, TERMUX_APP.DEFAULT_VALUE_HIDE_SOFT_KEYBOARD_ON_STARTUP);
    }

    public void setSoftKeyboardHiddenOnStartup(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP, value, false);
    }


    public boolean shouldRunTermuxAmSocketServer() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_RUN_TERMUX_AM_SOCKET_SERVER, TERMUX_APP.DEFAULT_VALUE_RUN_TERMUX_AM_SOCKET_SERVER);
    }

    public void setRunTermuxAmSocketServer(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_RUN_TERMUX_AM_SOCKET_SERVER, value, false);
    }


    public boolean shouldOpenTerminalTranscriptURLOnClick() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_ONCLICK_URL_OPEN, TERMUX_APP.DEFAULT_VALUE_TERMINAL_ONCLICK_URL_OPEN);
    }

    public void setOpenTerminalTranscriptURLOnClick(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_ONCLICK_URL_OPEN, value, false);
    }


    public boolean isUsingCtrlSpaceWorkaround() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_USE_CTRL_SPACE_WORKAROUND, TERMUX_APP.DEFAULT_VALUE_USE_CTRL_SPACE_WORKAROUND);
    }

    public void setCtrlSpaceWorkaround(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_USE_CTRL_SPACE_WORKAROUND, value, false);
    }


    public boolean isUsingFullScreen() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_USE_FULLSCREEN, TERMUX_APP.DEFAULT_VALUE_USE_FULLSCREEN);
    }

    public void setFullScreen(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_USE_FULLSCREEN, value, false);
    }


    public boolean isUsingFullScreenWorkAround() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_USE_FULLSCREEN_WORKAROUND, TERMUX_APP.DEFAULT_VALUE_USE_FULLSCREEN_WORKAROUND);
    }

    public void setFullScreenWorkAround(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_USE_FULLSCREEN_WORKAROUND, value, false);
    }


    /* int */

    public int getBellBehaviour() {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_APP.KEY_BELL_BEHAVIOUR, TERMUX_APP.DEFAULT_VALUE_BELL_BEHAVIOUR);
    }

    public void setBellBehaviour(int value) {
        if (value < TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_VIBRATE) value = TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_VIBRATE;
        if (value > TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_IGNORE) value = TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_IGNORE;
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_BELL_BEHAVIOUR, value, false);
    }


    public int getExtraKeysHaptic() {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_APP.KEY_EXTRA_KEYS_HAPTIC, TERMUX_APP.DEFAULT_VALUE_EXTRA_KEYS_HAPTIC);
    }

    public void setExtraKeysHaptic(int value) {
        if (value < TermuxPropertyConstants.IVALUE_EXTRA_KEYS_HAPTIC_ALL) value = TermuxPropertyConstants.IVALUE_EXTRA_KEYS_HAPTIC_ALL;
        if (value > TermuxPropertyConstants.IVALUE_EXTRA_KEYS_HAPTIC_OFF) value = TermuxPropertyConstants.IVALUE_EXTRA_KEYS_HAPTIC_OFF;
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_EXTRA_KEYS_HAPTIC, value, false);
    }


    public int getDeleteTMPDIRFilesOlderThanXDaysOnExit() {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_APP.KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT, TERMUX_APP.DEFAULT_VALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT);
    }

    public void setDeleteTMPDIRFilesOlderThanXDaysOnExit(int value) {
        if (value < TERMUX_APP.MIN_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT) value = TERMUX_APP.MIN_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT;
        if (value > TERMUX_APP.MAX_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT) value = TERMUX_APP.MAX_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT;
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT, value, false);
    }


    public int getTerminalCursorBlinkRate() {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_CURSOR_BLINK_RATE, TERMUX_APP.DEFAULT_VALUE_TERMINAL_CURSOR_BLINK_RATE);
    }

    public void setTerminalCursorBlinkRate(int value) {
        if (value != 0 && value < TERMUX_APP.MIN_TERMINAL_CURSOR_BLINK_RATE) value = TERMUX_APP.MIN_TERMINAL_CURSOR_BLINK_RATE;
        if (value > TERMUX_APP.MAX_TERMINAL_CURSOR_BLINK_RATE) value = TERMUX_APP.MAX_TERMINAL_CURSOR_BLINK_RATE;
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_CURSOR_BLINK_RATE, value, false);
    }

    public boolean getTerminalCursorBlinkEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_CURSOR_BLINK_ENABLED, TERMUX_APP.DEFAULT_VALUE_TERMINAL_CURSOR_BLINK_ENABLED);
    }

    public void setTerminalCursorBlinkEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_CURSOR_BLINK_ENABLED, value, false);
    }



    public int getTerminalCursorStyle() {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_CURSOR_STYLE, TERMUX_APP.DEFAULT_VALUE_TERMINAL_CURSOR_STYLE);
    }

    public void setTerminalCursorStyle(int value) {
        if (value < TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_STYLE_BLOCK) value = TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_STYLE_BLOCK;
        if (value > TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_STYLE_BAR) value = TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_STYLE_BAR;
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_CURSOR_STYLE, value, false);
    }


    /**
     * Get the terminal margin for one side. For a left/right side, if the new per-side
     * key is unset, falls back to the legacy "terminal-margin-horizontal" value; for a
     * top/bottom side, to the legacy "terminal-margin-vertical" value.
     */
    private int getTerminalMarginSide(String key, String legacyKey, int defaultValue) {
        if (mSharedPreferences.contains(key)) {
            return SharedPreferenceUtils.getInt(mSharedPreferences, key, defaultValue);
        }
        int legacy = SharedPreferenceUtils.getInt(mSharedPreferences, legacyKey, defaultValue);
        SharedPreferenceUtils.setInt(mSharedPreferences, key, legacy, false);
        return legacy;
    }

    public int getTerminalMarginLeft() {
        return getTerminalMarginSide(TERMUX_APP.KEY_TERMINAL_MARGIN_LEFT,
                "terminal-margin-horizontal", TERMUX_APP.DEFAULT_VALUE_TERMINAL_MARGIN_LEFT);
    }

    public void setTerminalMarginLeft(int value) {
        if (value < TERMUX_APP.MIN_TERMINAL_MARGIN_LEFT) value = TERMUX_APP.MIN_TERMINAL_MARGIN_LEFT;
        if (value > TERMUX_APP.MAX_TERMINAL_MARGIN_LEFT) value = TERMUX_APP.MAX_TERMINAL_MARGIN_LEFT;
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_MARGIN_LEFT, value, false);
    }

    public int getTerminalMarginTop() {
        return getTerminalMarginSide(TERMUX_APP.KEY_TERMINAL_MARGIN_TOP,
                "terminal-margin-vertical", TERMUX_APP.DEFAULT_VALUE_TERMINAL_MARGIN_TOP);
    }

    public void setTerminalMarginTop(int value) {
        if (value < TERMUX_APP.MIN_TERMINAL_MARGIN_TOP) value = TERMUX_APP.MIN_TERMINAL_MARGIN_TOP;
        if (value > TERMUX_APP.MAX_TERMINAL_MARGIN_TOP) value = TERMUX_APP.MAX_TERMINAL_MARGIN_TOP;
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_MARGIN_TOP, value, false);
    }

    public int getTerminalMarginRight() {
        return getTerminalMarginSide(TERMUX_APP.KEY_TERMINAL_MARGIN_RIGHT,
                "terminal-margin-horizontal", TERMUX_APP.DEFAULT_VALUE_TERMINAL_MARGIN_RIGHT);
    }

    public void setTerminalMarginRight(int value) {
        if (value < TERMUX_APP.MIN_TERMINAL_MARGIN_RIGHT) value = TERMUX_APP.MIN_TERMINAL_MARGIN_RIGHT;
        if (value > TERMUX_APP.MAX_TERMINAL_MARGIN_RIGHT) value = TERMUX_APP.MAX_TERMINAL_MARGIN_RIGHT;
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_MARGIN_RIGHT, value, false);
    }

    public int getTerminalMarginBottom() {
        return getTerminalMarginSide(TERMUX_APP.KEY_TERMINAL_MARGIN_BOTTOM,
                "terminal-margin-vertical", TERMUX_APP.DEFAULT_VALUE_TERMINAL_MARGIN_BOTTOM);
    }

    public void setTerminalMarginBottom(int value) {
        if (value < TERMUX_APP.MIN_TERMINAL_MARGIN_BOTTOM) value = TERMUX_APP.MIN_TERMINAL_MARGIN_BOTTOM;
        if (value > TERMUX_APP.MAX_TERMINAL_MARGIN_BOTTOM) value = TERMUX_APP.MAX_TERMINAL_MARGIN_BOTTOM;
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_MARGIN_BOTTOM, value, false);
    }


    /**
     * Get the terminal background transparency in percent.
     *
     * @return 0 when the wallpaper feature is off (opaque background — the default and the
     * historical behaviour), up to {@link TermuxPreferenceConstants.TERMUX_APP#MAX_TERMINAL_BACKGROUND_TRANSPARENCY}.
     */
    public int getTerminalBackgroundTransparency() {
        return SharedPreferenceUtils.getInt(mSharedPreferences,
            TERMUX_APP.KEY_TERMINAL_BACKGROUND_TRANSPARENCY,
            TERMUX_APP.DEFAULT_VALUE_TERMINAL_BACKGROUND_TRANSPARENCY);
    }

    public void setTerminalBackgroundTransparency(int value) {
        if (value < TERMUX_APP.MIN_TERMINAL_BACKGROUND_TRANSPARENCY) value = TERMUX_APP.MIN_TERMINAL_BACKGROUND_TRANSPARENCY;
        if (value > TERMUX_APP.MAX_TERMINAL_BACKGROUND_TRANSPARENCY) value = TERMUX_APP.MAX_TERMINAL_BACKGROUND_TRANSPARENCY;
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_BACKGROUND_TRANSPARENCY, value, false);
    }

    /**
     * Whether the device wallpaper behind the terminal should be blurred with the Android 12+
     * system blur ({@code FLAG_BLUR_BEHIND}).
     *
     * The stored value is returned as-is: the setting is inert below API 31 and whenever the
     * background transparency is 0, because then there is nothing behind the terminal to blur.
     */
    public boolean isTerminalBackgroundBlurEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences,
            TERMUX_APP.KEY_TERMINAL_BACKGROUND_BLUR, TERMUX_APP.DEFAULT_VALUE_TERMINAL_BACKGROUND_BLUR);
    }

    public void setTerminalBackgroundBlurEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_BACKGROUND_BLUR, value, false);
    }

    /**
     * The wallpaper blur radius behind the terminal, in pixels. Larger values blur more; 0 is
     * a sharp wallpaper. Clamped to the documented range so a stale or out-of-range value from
     * an older build can never reach {@code WindowManager.LayoutParams#setBlurBehindRadius}.
     */
    public int getTerminalBackgroundBlurRadius() {
        return SharedPreferenceUtils.getInt(mSharedPreferences,
            TERMUX_APP.KEY_TERMINAL_BACKGROUND_BLUR_RADIUS, TERMUX_APP.DEFAULT_VALUE_TERMINAL_BACKGROUND_BLUR_RADIUS);
    }

    public void setTerminalBackgroundBlurRadius(int value) {
        if (value < TERMUX_APP.MIN_TERMINAL_BACKGROUND_BLUR_RADIUS) value = TERMUX_APP.MIN_TERMINAL_BACKGROUND_BLUR_RADIUS;
        if (value > TERMUX_APP.MAX_TERMINAL_BACKGROUND_BLUR_RADIUS) value = TERMUX_APP.MAX_TERMINAL_BACKGROUND_BLUR_RADIUS;
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_BACKGROUND_BLUR_RADIUS, value, false);
    }

    public int getTerminalTranscriptRows() {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_TRANSCRIPT_ROWS, TERMUX_APP.DEFAULT_VALUE_TERMINAL_TRANSCRIPT_ROWS);
    }

    public void setTerminalTranscriptRows(int value) {
        if (value < TERMUX_APP.MIN_TERMINAL_TRANSCRIPT_ROWS) value = TERMUX_APP.MIN_TERMINAL_TRANSCRIPT_ROWS;
        if (value > TERMUX_APP.MAX_TERMINAL_TRANSCRIPT_ROWS) value = TERMUX_APP.MAX_TERMINAL_TRANSCRIPT_ROWS;
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_TRANSCRIPT_ROWS, value, false);
    }


    /* float */

    public float getTerminalToolbarHeightScaleFactor() {
        return SharedPreferenceUtils.getFloat(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR, TERMUX_APP.DEFAULT_VALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR);
    }

    public void setTerminalToolbarHeightScaleFactor(float value) {
        if (value < TERMUX_APP.MIN_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR) value = TERMUX_APP.MIN_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR;
        if (value > TERMUX_APP.MAX_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR) value = TERMUX_APP.MAX_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR;
        SharedPreferenceUtils.setFloat(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR, value, false);
    }


    /* int (extra keys corner radius) */

    public int getExtraKeysCornerRadius() {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_APP.KEY_EXTRA_KEYS_CORNER_RADIUS, TERMUX_APP.DEFAULT_VALUE_EXTRA_KEYS_CORNER_RADIUS);
    }

    public void setExtraKeysCornerRadius(int value) {
        if (value < TERMUX_APP.MIN_EXTRA_KEYS_CORNER_RADIUS) value = TERMUX_APP.MIN_EXTRA_KEYS_CORNER_RADIUS;
        if (value > TERMUX_APP.MAX_EXTRA_KEYS_CORNER_RADIUS) value = TERMUX_APP.MAX_EXTRA_KEYS_CORNER_RADIUS;
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_EXTRA_KEYS_CORNER_RADIUS, value, false);
    }


    /* float (extra keys button margin in dp) - stored as int (value × 10) */

    public float getExtraKeysButtonMargin() {
        int stored = SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_APP.KEY_EXTRA_KEYS_BUTTON_MARGIN, Math.round(TERMUX_APP.DEFAULT_VALUE_EXTRA_KEYS_BUTTON_MARGIN * 10f));
        return stored / 10f;
    }

    public void setExtraKeysButtonMargin(float value) {
        if (value < TERMUX_APP.MIN_EXTRA_KEYS_BUTTON_MARGIN) value = TERMUX_APP.MIN_EXTRA_KEYS_BUTTON_MARGIN;
        if (value > TERMUX_APP.MAX_EXTRA_KEYS_BUTTON_MARGIN) value = TERMUX_APP.MAX_EXTRA_KEYS_BUTTON_MARGIN;
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_EXTRA_KEYS_BUTTON_MARGIN, Math.round(value * 10f), false);
    }


    /* int (extra keys base font size in sp) */

    public int getExtraKeysFontSize() {
        return SharedPreferenceUtils.getInt(mSharedPreferences,
            TERMUX_APP.KEY_EXTRA_KEYS_FONT_SIZE,
            TERMUX_APP.DEFAULT_VALUE_EXTRA_KEYS_FONT_SIZE);
    }

    public void setExtraKeysFontSize(int value) {
        if (value < TERMUX_APP.MIN_EXTRA_KEYS_FONT_SIZE) value = TERMUX_APP.MIN_EXTRA_KEYS_FONT_SIZE;
        if (value > TERMUX_APP.MAX_EXTRA_KEYS_FONT_SIZE) value = TERMUX_APP.MAX_EXTRA_KEYS_FONT_SIZE;
        SharedPreferenceUtils.setInt(mSharedPreferences,
            TERMUX_APP.KEY_EXTRA_KEYS_FONT_SIZE, value, false);
    }


    /* String (session shortcuts, raw "Ctrl+KEY" form) */

    public String getShortcutString(String key) {
        return SharedPreferenceUtils.getString(mSharedPreferences, key, null, true);
    }

    public void setShortcutString(String key, String value) {
        SharedPreferenceUtils.setString(mSharedPreferences, key, value, false);
    }


    /* String */

    public boolean isBackKeyTheEscapeKey() {
        return TermuxPropertyConstants.IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE.equals(getBackKeyBehaviour());
    }

    public String getBackKeyBehaviour() {
        return SharedPreferenceUtils.getString(mSharedPreferences, TERMUX_APP.KEY_BACK_KEY_BEHAVIOUR, TERMUX_APP.DEFAULT_VALUE_BACK_KEY_BEHAVIOUR, true);
    }

    public void setBackKeyBehaviour(String value) {
        SharedPreferenceUtils.setString(mSharedPreferences, TERMUX_APP.KEY_BACK_KEY_BEHAVIOUR, value, false);
    }


    public String getDefaultWorkingDirectory() {
        String value = SharedPreferenceUtils.getString(mSharedPreferences, TERMUX_APP.KEY_DEFAULT_WORKING_DIRECTORY, TERMUX_APP.DEFAULT_VALUE_DEFAULT_WORKING_DIRECTORY, true);
        // The compile-time default TERMUX_HOME_DIR_PATH points to /data/data/com.termux/files/home.
        // If the actual package name differs (e.g. com.termux.debug), resolve to the runtime path.
        String compileTimeHome = TermuxConstants.TERMUX_HOME_DIR_PATH;
        if (value.equals(compileTimeHome)) {
            String runtimeHome = mContext.getFilesDir().getAbsolutePath() + "/home";
            if (!runtimeHome.equals(compileTimeHome)) {
                return runtimeHome;
            }
        }
        return value;
    }

    public void setDefaultWorkingDirectory(String value) {
        SharedPreferenceUtils.setString(mSharedPreferences, TERMUX_APP.KEY_DEFAULT_WORKING_DIRECTORY, value, false);
    }


    public String getExtraKeys() {
        return SharedPreferenceUtils.getString(mSharedPreferences, TERMUX_APP.KEY_EXTRA_KEYS, TERMUX_APP.DEFAULT_VALUE_EXTRA_KEYS, true);
    }

    public void setExtraKeys(String value) {
        SharedPreferenceUtils.setString(mSharedPreferences, TERMUX_APP.KEY_EXTRA_KEYS, value, false);
    }

    /**
     * Get the {@code extra-keys-session} property: named extra-keys profiles, each with session
     * name prefixes that auto-activate its layout. Returns {@code null} when unset or empty.
     */
    public String getExtraKeysSession() {
        String value = SharedPreferenceUtils.getString(mSharedPreferences,
            TERMUX_APP.KEY_EXTRA_KEYS_SESSION, null, true);
        return (value == null || value.isEmpty()) ? null : value;
    }

    public void setExtraKeysSession(String value) {
        SharedPreferenceUtils.setString(mSharedPreferences,
            TERMUX_APP.KEY_EXTRA_KEYS_SESSION, value, false);
    }





    public String getExtraKeysStyle() {
        return SharedPreferenceUtils.getString(mSharedPreferences, TERMUX_APP.KEY_EXTRA_KEYS_STYLE, TERMUX_APP.DEFAULT_VALUE_EXTRA_KEYS_STYLE, true);
    }

    public void setExtraKeysStyle(String value) {
        SharedPreferenceUtils.setString(mSharedPreferences, TERMUX_APP.KEY_EXTRA_KEYS_STYLE, value, false);
    }


    public String getExtraKeysSpecialButtonMode() {
        return SharedPreferenceUtils.getString(mSharedPreferences, TERMUX_APP.KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE, TERMUX_APP.DEFAULT_VALUE_EXTRA_KEYS_SPECIAL_BUTTON_MODE, true);
    }

    public void setExtraKeysSpecialButtonMode(String value) {
        SharedPreferenceUtils.setString(mSharedPreferences, TERMUX_APP.KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE, value, false);
    }


    public String getNightMode() {
        return SharedPreferenceUtils.getString(mSharedPreferences, TERMUX_APP.KEY_NIGHT_MODE, TERMUX_APP.DEFAULT_VALUE_NIGHT_MODE, true);
    }

    public void setNightMode(String value) {
        SharedPreferenceUtils.setString(mSharedPreferences, TERMUX_APP.KEY_NIGHT_MODE, value, false);
    }


    public boolean shouldEnableDisableSoftKeyboardOnToggle() {
        return TermuxPropertyConstants.IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE.equals(getSoftKeyboardToggleBehaviour());
    }

    public String getSoftKeyboardToggleBehaviour() {
        return SharedPreferenceUtils.getString(mSharedPreferences, TERMUX_APP.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, TERMUX_APP.DEFAULT_VALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, true);
    }

    public void setSoftKeyboardToggleBehaviour(String value) {
        SharedPreferenceUtils.setString(mSharedPreferences, TERMUX_APP.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, value, false);
    }


    public boolean areVirtualVolumeKeysDisabled() {
        return TermuxPropertyConstants.IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME.equals(getVolumeKeysBehaviour());
    }

    public String getVolumeKeysBehaviour() {
        return SharedPreferenceUtils.getString(mSharedPreferences, TERMUX_APP.KEY_VOLUME_KEYS_BEHAVIOUR, TERMUX_APP.DEFAULT_VALUE_VOLUME_KEYS_BEHAVIOUR, true);
    }

    public void setVolumeKeysBehaviour(String value) {
        SharedPreferenceUtils.setString(mSharedPreferences, TERMUX_APP.KEY_VOLUME_KEYS_BEHAVIOUR, value, false);
    }


    /* Generic key based accessors used by the properties facade. */

    public boolean getBooleanByKey(String key, boolean def) {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, key, def);
    }

    public String getStringByKey(String key) {
        return SharedPreferenceUtils.getString(mSharedPreferences, key, null, true);
    }

    public boolean isKeyPresentByKey(String key) {
        return SharedPreferenceUtils.isKeyPresent(mSharedPreferences, key);
    }

    public void setGenericBoolean(String key, boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, key, value, false);
    }

    public void setGenericInt(String key, int value) {
        SharedPreferenceUtils.setInt(mSharedPreferences, key, value, false);
    }

    public void setGenericFloat(String key, float value) {
        SharedPreferenceUtils.setFloat(mSharedPreferences, key, value, false);
    }

    public void setGenericString(String key, String value) {
        SharedPreferenceUtils.setString(mSharedPreferences, key, value, false);
    }

}
