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
     * @param context context used to resolve the {@link TermuxConstants#TERMUX_PACKAGE_NAME} package context.
     * @return the preferences, or {@code null} if an exception is raised.
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
     * @param context context used to resolve the {@link TermuxConstants#TERMUX_PACKAGE_NAME} package context.
     * @param exitAppOnError if {@code true} and the package context cannot be obtained, show a
     *                       dialog whose dismissal exits the app.
     * @return the preferences, or {@code null} if an exception is raised.
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

    private int getInt(String key, int def) {
        return SharedPreferenceUtils.getInt(mSharedPreferences, key, def);
    }

    private String getString(String key, String def) {
        return SharedPreferenceUtils.getString(mSharedPreferences, key, def, true);
    }

    private void setIntClamped(String key, int value, int min, int max) {
        SharedPreferenceUtils.setInt(mSharedPreferences, key, DataUtils.clamp(value, min, max), false);
    }

    private void setFloatClamped(String key, float value, float min, float max) {
        SharedPreferenceUtils.setFloat(mSharedPreferences, key, clampFloat(value, min, max), false);
    }

    private static float clampFloat(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static boolean isTablet(Context context) {
        return context.getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    public boolean shouldShowTerminalToolbar() {
        return getBooleanByKey(TERMUX_APP.KEY_SHOW_TERMINAL_TOOLBAR, TERMUX_APP.DEFAULT_VALUE_SHOW_TERMINAL_TOOLBAR);
    }

    public void setShowTerminalToolbar(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_SHOW_TERMINAL_TOOLBAR, value);
    }

    public boolean toogleShowTerminalToolbar() {
        boolean currentValue = shouldShowTerminalToolbar();
        setShowTerminalToolbar(!currentValue);
        return !currentValue;
    }

    /**
     * Get whether extra keys should be hidden when the soft keyboard is hidden.
     */
    public boolean shouldHideExtraKeysWithKeyboard() {
        if (!SharedPreferenceUtils.isKeyPresent(mSharedPreferences, TERMUX_APP.KEY_HIDE_EXTRA_KEYS_WITH_KEYBOARD)) {
            setHideExtraKeysWithKeyboard(TERMUX_APP.DEFAULT_VALUE_HIDE_EXTRA_KEYS_WITH_KEYBOARD);
        }
        return getBooleanByKey(TERMUX_APP.KEY_HIDE_EXTRA_KEYS_WITH_KEYBOARD, TERMUX_APP.DEFAULT_VALUE_HIDE_EXTRA_KEYS_WITH_KEYBOARD);
    }

    public void setHideExtraKeysWithKeyboard(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_HIDE_EXTRA_KEYS_WITH_KEYBOARD, value);
    }

    public boolean shouldTextInputAppendEnter() {
        return getBooleanByKey(TERMUX_APP.KEY_TEXT_INPUT_APPEND_ENTER, TERMUX_APP.DEFAULT_VALUE_TEXT_INPUT_APPEND_ENTER);
    }

    public void setTextInputAppendEnter(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_TEXT_INPUT_APPEND_ENTER, value);
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
        setGenericString(TERMUX_APP.KEY_TEXT_INPUT_ACTION_ON_SEND, value);
    }

    public boolean shouldInsertAtCursorOnHistoryPick() {
        return getBooleanByKey(TERMUX_APP.KEY_TEXT_INPUT_INSERT_AT_CURSOR, TERMUX_APP.DEFAULT_VALUE_TEXT_INPUT_INSERT_AT_CURSOR);
    }

    public void setInsertAtCursorOnHistoryPick(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_TEXT_INPUT_INSERT_AT_CURSOR, value);
    }

    /**
     * Get the maximum number of auto-complete suggestions to show in the text input popup.
     *
     * @return Returns the max suggestions count (clamped to 1-10, default 4).
     */
    public int getSuggestionsMaxCount() {
        return getInt(TERMUX_APP.KEY_SUGGESTIONS_MAX_COUNT, TERMUX_APP.DEFAULT_VALUE_SUGGESTIONS_MAX_COUNT);
    }

    public void setSuggestionsMaxCount(int value) {
        setIntClamped(TERMUX_APP.KEY_SUGGESTIONS_MAX_COUNT, value, TERMUX_APP.SUGGESTIONS_MAX_COUNT_MIN, TERMUX_APP.SUGGESTIONS_MAX_COUNT_MAX);
    }

    public boolean isSoftKeyboardEnabled() {
        return getBooleanByKey(TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED, TERMUX_APP.DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED);
    }

    public void setSoftKeyboardEnabled(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED, value);
    }

    public boolean isKeyboardStateFollowTabSwitch() {
        return getBooleanByKey(TERMUX_APP.KEY_KEYBOARD_STATE_FOLLOW_TAB_SWITCH, TERMUX_APP.DEFAULT_VALUE_KEYBOARD_STATE_FOLLOW_TAB_SWITCH);
    }

    public void setKeyboardStateFollowTabSwitch(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_KEYBOARD_STATE_FOLLOW_TAB_SWITCH, value);
    }

    public boolean isSoftKeyboardEnabledOnlyIfNoHardware() {
        return getBooleanByKey(TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE, TERMUX_APP.DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE);
    }

    public void setSoftKeyboardEnabledOnlyIfNoHardware(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE, value);
    }

    public boolean shouldKeepScreenOn() {
        return getBooleanByKey(TERMUX_APP.KEY_KEEP_SCREEN_ON, TERMUX_APP.DEFAULT_VALUE_KEEP_SCREEN_ON);
    }

    public void setKeepScreenOn(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_KEEP_SCREEN_ON, value);
    }

    public static int[] getDefaultFontSizes(Context context) {
        float dipInPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, context.getResources().getDisplayMetrics());

        int[] sizes = new int[3];

        // Absolute rather than density-derived (the original 4dp floor was ~10px and made the lower
        // half of the slider travel point at sizes no one wants). 20px keeps a useful floor.
        sizes[1] = 20; // min

        // http://www.google.com/design/spec/style/typography.html#typography-line-height
        int defaultFontSize = Math.round(12 * dipInPixels);
        // Make it divisible by 2 since that is the minimal adjustment step:
        if (defaultFontSize % 2 == 1) defaultFontSize--;

        sizes[0] = defaultFontSize; // default

        // Absolute rather than density-derived (the original cap was 256px, which made the Display
        // settings slider useless over most of its travel). 40px keeps the whole range usable — the
        // density-derived default (12dp) sits comfortably inside it — and because the pinch gesture,
        // the fontsize preference and the slider all clamp to this same bound, the slider still
        // cannot reach a size the gesture cannot.
        sizes[2] = 40; // max

        // Guards: on a very high-density display the density-derived default can exceed the absolute
        // max, and on a low-density one it can fall below the absolute min. Either way the slider must
        // be able to represent the current size, so clamp the default into [min, max].
        if (sizes[0] > sizes[2]) sizes[0] = sizes[2];
        if (sizes[0] < sizes[1]) sizes[0] = sizes[1];

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

    /**
     * Store the terminal font size, in pixels.
     *
     * <p>Clamped to the same bounds {@link #getFontSize()} uses, so a value from outside the app
     * (old preferences file, external UI) can never be persisted out of range; the read-side
     * clamp stays as the second line of defence.
     */
    public void setFontSize(int value) {
        value = DataUtils.clamp(value, MIN_FONTSIZE, MAX_FONTSIZE);
        SharedPreferenceUtils.setIntStoredAsString(mSharedPreferences, TERMUX_APP.KEY_FONTSIZE, value, false);
    }

    /**
     * The smallest terminal font size, in pixels — the lower bound the pinch gesture, the
     * {@code fontsize} preference and the Display settings slider all share.
     */
    public int getMinFontSize() {
        return MIN_FONTSIZE;
    }

    /**
     * The largest terminal font size, in pixels — the upper bound the pinch gesture, the
     * {@code fontsize} preference and the Display settings slider all share.
     */
    public int getMaxFontSize() {
        return MAX_FONTSIZE;
    }

    /**
     * The adjustment step of the terminal font size, in pixels — how much the pinch gesture moves
     * the size per step, and the granularity the Display settings slider snaps to.
     */
    public static final int FONT_SIZE_STEP = 2;

    public void changeFontSize(boolean increase) {
        int fontSize = getFontSize();

        fontSize += (increase ? 1 : -1) * FONT_SIZE_STEP;

        setFontSize(fontSize);
    }

    public String getCurrentSession() {
        return getStringByKey(TERMUX_APP.KEY_CURRENT_SESSION);
    }

    public void setCurrentSession(String value) {
        setGenericString(TERMUX_APP.KEY_CURRENT_SESSION, value);
    }

    public int getLogLevel() {
        return getInt(TERMUX_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL);
    }

    public void setLogLevel(Context context, int logLevel) {
        logLevel = Logger.setLogLevel(context, logLevel);
        setGenericInt(TERMUX_APP.KEY_LOG_LEVEL, logLevel);
    }

    public int getLastNotificationId() {
        return getInt(TERMUX_APP.KEY_LAST_NOTIFICATION_ID, TERMUX_APP.DEFAULT_VALUE_KEY_LAST_NOTIFICATION_ID);
    }

    public void setLastNotificationId(int notificationId) {
        setGenericInt(TERMUX_APP.KEY_LAST_NOTIFICATION_ID, notificationId);
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

    public boolean arePluginErrorNotificationsEnabled(boolean readFromFile) {
        if (readFromFile)
            return SharedPreferenceUtils.getBoolean(mMultiProcessSharedPreferences, TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_PLUGIN_ERROR_NOTIFICATIONS_ENABLED);
        else
            return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_PLUGIN_ERROR_NOTIFICATIONS_ENABLED);
    }

    public void setPluginErrorNotificationsEnabled(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED, value);
    }

    public int getButtonBgInactiveAlpha() {
        return getInt(TERMUX_APP.KEY_BUTTON_BG_INACTIVE_ALPHA, TERMUX_APP.DEFAULT_BUTTON_BG_INACTIVE_ALPHA);
    }

    public void setButtonBgInactiveAlpha(int value) {
        setGenericInt(TERMUX_APP.KEY_BUTTON_BG_INACTIVE_ALPHA, value);
    }

    public int getButtonBgActiveAlpha() {
        return getInt(TERMUX_APP.KEY_BUTTON_BG_ACTIVE_ALPHA, TERMUX_APP.DEFAULT_BUTTON_BG_ACTIVE_ALPHA);
    }

    public void setButtonBgActiveAlpha(int value) {
        setGenericInt(TERMUX_APP.KEY_BUTTON_BG_ACTIVE_ALPHA, value);
    }

    public boolean areCrashReportNotificationsEnabled(boolean readFromFile) {
        if (readFromFile)
            return SharedPreferenceUtils.getBoolean(mMultiProcessSharedPreferences, TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_CRASH_REPORT_NOTIFICATIONS_ENABLED);
       else
            return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_CRASH_REPORT_NOTIFICATIONS_ENABLED);
    }

    public void setCrashReportNotificationsEnabled(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED, value);
    }

    /* Settings migrated from ~/.termux/termux.properties — keys intentionally match the old
     * termux.properties keys so that values can be migrated on first launch. */

    /* boolean */

    public boolean shouldAllowExternalApps() {
        return getBooleanByKey(TERMUX_APP.KEY_ALLOW_EXTERNAL_APPS, TERMUX_APP.DEFAULT_VALUE_ALLOW_EXTERNAL_APPS);
    }

    public void setAllowExternalApps(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_ALLOW_EXTERNAL_APPS, value);
    }

    public boolean isFileShareReceiverDisabled() {
        return getBooleanByKey(TERMUX_APP.KEY_DISABLE_FILE_SHARE_RECEIVER, TERMUX_APP.DEFAULT_VALUE_DISABLE_FILE_SHARE_RECEIVER);
    }

    public void setFileShareReceiverDisabled(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_DISABLE_FILE_SHARE_RECEIVER, value);
    }

    public boolean isFileViewReceiverDisabled() {
        return getBooleanByKey(TERMUX_APP.KEY_DISABLE_FILE_VIEW_RECEIVER, TERMUX_APP.DEFAULT_VALUE_DISABLE_FILE_VIEW_RECEIVER);
    }

    public void setFileViewReceiverDisabled(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_DISABLE_FILE_VIEW_RECEIVER, value);
    }

    public boolean areHardwareKeyboardShortcutsDisabled() {
        return getBooleanByKey(TERMUX_APP.KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS, TERMUX_APP.DEFAULT_VALUE_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS);
    }

    public void setHardwareKeyboardShortcutsDisabled(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS, value);
    }

    public boolean areTerminalSessionChangeToastsDisabled() {
        return getBooleanByKey(TERMUX_APP.KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST, TERMUX_APP.DEFAULT_VALUE_DISABLE_TERMINAL_SESSION_CHANGE_TOAST);
    }

    public void setTerminalSessionChangeToastsDisabled(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST, value);
    }

    public boolean isEnforcingCharBasedInput() {
        return getBooleanByKey(TERMUX_APP.KEY_ENFORCE_CHAR_BASED_INPUT, TERMUX_APP.DEFAULT_VALUE_ENFORCE_CHAR_BASED_INPUT);
    }

    public void setEnforceCharBasedInput(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_ENFORCE_CHAR_BASED_INPUT, value);
    }

    public boolean shouldExtraKeysTextBeAllCaps() {
        return getBooleanByKey(TERMUX_APP.KEY_EXTRA_KEYS_TEXT_ALL_CAPS, TERMUX_APP.DEFAULT_VALUE_EXTRA_KEYS_TEXT_ALL_CAPS);
    }

    public void setExtraKeysTextAllCaps(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_EXTRA_KEYS_TEXT_ALL_CAPS, value);
    }

    public boolean isExtraKeysDynamicFontSizeEnabled(Context context) {
        boolean tablet = isTablet(context);
        return getBooleanByKey(TERMUX_APP.KEY_EXTRA_KEYS_DYNAMIC_FONT_SIZE, !tablet);
    }

    public void setExtraKeysDynamicFontSize(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_EXTRA_KEYS_DYNAMIC_FONT_SIZE, value);
    }

    public boolean isExtraKeysEdgeIndicatorsEnabled() {
        return getBooleanByKey(TERMUX_APP.KEY_EXTRA_KEYS_EDGE_INDICATORS, TERMUX_APP.DEFAULT_VALUE_EXTRA_KEYS_EDGE_INDICATORS);
    }

    public void setExtraKeysEdgeIndicatorsEnabled(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_EXTRA_KEYS_EDGE_INDICATORS, value);
    }

    /**
     * Whether the extra keys panel may fold its rows into one or two when the window is wider than
     * it is tall. The fold itself is a derived layout — see
     * {@link com.termux.shared.termux.extrakeys.ExtraKeysCompaction} — so this is a UI preference,
     * not part of the stored {@code extra-keys} layout.
     *
     * @param context used for the device-dependent default (on for phones, off for tablets), so it
     *                must be a {@code Context} whose resources describe the device.
     */
    public boolean isExtraKeysCompactLandscapeEnabled(Context context) {
        // Phone/tablet default (see javadoc); stored value wins once the user touches the switch.
        boolean tablet = isTablet(context);
        return getBooleanByKey(TERMUX_APP.KEY_EXTRA_KEYS_COMPACT_LANDSCAPE, !tablet);
    }

    public void setExtraKeysCompactLandscapeEnabled(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_EXTRA_KEYS_COMPACT_LANDSCAPE, value);
    }

    /**
     * Order of keys inside a folded row, as a {@link com.termux.shared.termux.extrakeys.ExtraKeysCompaction.Mode}
     * preference value ({@code "rows"} or {@code "columns"}). Read it through
     * {@code ExtraKeysCompaction.modeFromPreferenceValue(...)} so an unknown value cannot break the panel.
     */
    public String getExtraKeysCompactMode() {
        return getString(TERMUX_APP.KEY_EXTRA_KEYS_COMPACT_MODE, TERMUX_APP.DEFAULT_VALUE_EXTRA_KEYS_COMPACT_MODE);
    }

    public void setExtraKeysCompactMode(String value) {
        setGenericString(TERMUX_APP.KEY_EXTRA_KEYS_COMPACT_MODE, value);
    }

    public boolean isScrollOnNewOutputEnabled() {
        return getBooleanByKey(TERMUX_APP.KEY_SCROLL_ON_NEW_OUTPUT, TERMUX_APP.DEFAULT_VALUE_SCROLL_ON_NEW_OUTPUT);
    }

    public void setScrollOnNewOutputEnabled(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_SCROLL_ON_NEW_OUTPUT, value);
    }

    public boolean shouldSoftKeyboardBeHiddenOnStartup() {
        return getBooleanByKey(TERMUX_APP.KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP, TERMUX_APP.DEFAULT_VALUE_HIDE_SOFT_KEYBOARD_ON_STARTUP);
    }

    public void setSoftKeyboardHiddenOnStartup(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP, value);
    }

    public boolean shouldRunTermuxAmSocketServer() {
        return getBooleanByKey(TERMUX_APP.KEY_RUN_TERMUX_AM_SOCKET_SERVER, TERMUX_APP.DEFAULT_VALUE_RUN_TERMUX_AM_SOCKET_SERVER);
    }

    public void setRunTermuxAmSocketServer(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_RUN_TERMUX_AM_SOCKET_SERVER, value);
    }

    public boolean shouldOpenTerminalTranscriptURLOnClick() {
        return getBooleanByKey(TERMUX_APP.KEY_TERMINAL_ONCLICK_URL_OPEN, TERMUX_APP.DEFAULT_VALUE_TERMINAL_ONCLICK_URL_OPEN);
    }

    public void setOpenTerminalTranscriptURLOnClick(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_TERMINAL_ONCLICK_URL_OPEN, value);
    }

    public boolean isUsingCtrlSpaceWorkaround() {
        return getBooleanByKey(TERMUX_APP.KEY_USE_CTRL_SPACE_WORKAROUND, TERMUX_APP.DEFAULT_VALUE_USE_CTRL_SPACE_WORKAROUND);
    }

    public void setCtrlSpaceWorkaround(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_USE_CTRL_SPACE_WORKAROUND, value);
    }

    public boolean isUsingFullScreen() {
        return getBooleanByKey(TERMUX_APP.KEY_USE_FULLSCREEN, TERMUX_APP.DEFAULT_VALUE_USE_FULLSCREEN);
    }

    public void setFullScreen(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_USE_FULLSCREEN, value);
    }

    public boolean isBubbleOnBackgroundEnabled() {
        return getBooleanByKey(TERMUX_APP.KEY_BUBBLE_ON_BACKGROUND, TERMUX_APP.DEFAULT_VALUE_BUBBLE_ON_BACKGROUND);
    }

    public void setBubbleOnBackgroundEnabled(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_BUBBLE_ON_BACKGROUND, value);
    }

    /* int */

    public int getBellBehaviour() {
        return getInt(TERMUX_APP.KEY_BELL_BEHAVIOUR, TERMUX_APP.DEFAULT_VALUE_BELL_BEHAVIOUR);
    }

    public void setBellBehaviour(int value) {
        setIntClamped(TERMUX_APP.KEY_BELL_BEHAVIOUR, value, TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_VIBRATE, TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_IGNORE);
    }

    public int getExtraKeysHaptic() {
        return getInt(TERMUX_APP.KEY_EXTRA_KEYS_HAPTIC, TERMUX_APP.DEFAULT_VALUE_EXTRA_KEYS_HAPTIC);
    }

    public void setExtraKeysHaptic(int value) {
        setIntClamped(TERMUX_APP.KEY_EXTRA_KEYS_HAPTIC, value, TermuxPropertyConstants.IVALUE_EXTRA_KEYS_HAPTIC_ALL, TermuxPropertyConstants.IVALUE_EXTRA_KEYS_HAPTIC_OFF);
    }

    public int getDeleteTMPDIRFilesOlderThanXDaysOnExit() {
        return getInt(TERMUX_APP.KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT, TERMUX_APP.DEFAULT_VALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT);
    }

    public void setDeleteTMPDIRFilesOlderThanXDaysOnExit(int value) {
        setIntClamped(TERMUX_APP.KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT, value, TERMUX_APP.MIN_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT, TERMUX_APP.MAX_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT);
    }

    public int getTerminalCursorBlinkRate() {
        return getInt(TERMUX_APP.KEY_TERMINAL_CURSOR_BLINK_RATE, TERMUX_APP.DEFAULT_VALUE_TERMINAL_CURSOR_BLINK_RATE);
    }

    public void setTerminalCursorBlinkRate(int value) {
        if (value != 0 && value < TERMUX_APP.MIN_TERMINAL_CURSOR_BLINK_RATE) value = TERMUX_APP.MIN_TERMINAL_CURSOR_BLINK_RATE;
        if (value > TERMUX_APP.MAX_TERMINAL_CURSOR_BLINK_RATE) value = TERMUX_APP.MAX_TERMINAL_CURSOR_BLINK_RATE;
        setGenericInt(TERMUX_APP.KEY_TERMINAL_CURSOR_BLINK_RATE, value);
    }

    public boolean getTerminalCursorBlinkEnabled() {
        return getBooleanByKey(TERMUX_APP.KEY_TERMINAL_CURSOR_BLINK_ENABLED, TERMUX_APP.DEFAULT_VALUE_TERMINAL_CURSOR_BLINK_ENABLED);
    }

    public void setTerminalCursorBlinkEnabled(boolean value) {
        setGenericBoolean(TERMUX_APP.KEY_TERMINAL_CURSOR_BLINK_ENABLED, value);
    }

    public int getTerminalCursorStyle() {
        return getInt(TERMUX_APP.KEY_TERMINAL_CURSOR_STYLE, TERMUX_APP.DEFAULT_VALUE_TERMINAL_CURSOR_STYLE);
    }

    public void setTerminalCursorStyle(int value) {
        setIntClamped(TERMUX_APP.KEY_TERMINAL_CURSOR_STYLE, value, TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_STYLE_BLOCK, TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_STYLE_BAR);
    }

    /**
     * Get the terminal margin for one side. For a left/right side, if the new per-side
     * key is unset, falls back to the legacy "terminal-margin-horizontal" value; for a
     * top/bottom side, to the legacy "terminal-margin-vertical" value.
     */
    private int getTerminalMarginSide(String key, String legacyKey, int defaultValue) {
        if (mSharedPreferences.contains(key)) {
            return getInt(key, defaultValue);
        }
        int legacy = getInt(legacyKey, defaultValue);
        setGenericInt(key, legacy);
        return legacy;
    }

    public int getTerminalMarginLeft() {
        return getTerminalMarginSide(TERMUX_APP.KEY_TERMINAL_MARGIN_LEFT,
                "terminal-margin-horizontal", TERMUX_APP.DEFAULT_VALUE_TERMINAL_MARGIN_LEFT);
    }

    public void setTerminalMarginLeft(int value) {
        setIntClamped(TERMUX_APP.KEY_TERMINAL_MARGIN_LEFT, value, TERMUX_APP.MIN_TERMINAL_MARGIN_LEFT, TERMUX_APP.MAX_TERMINAL_MARGIN_LEFT);
    }

    public int getTerminalMarginTop() {
        return getTerminalMarginSide(TERMUX_APP.KEY_TERMINAL_MARGIN_TOP,
                "terminal-margin-vertical", TERMUX_APP.DEFAULT_VALUE_TERMINAL_MARGIN_TOP);
    }

    public void setTerminalMarginTop(int value) {
        setIntClamped(TERMUX_APP.KEY_TERMINAL_MARGIN_TOP, value, TERMUX_APP.MIN_TERMINAL_MARGIN_TOP, TERMUX_APP.MAX_TERMINAL_MARGIN_TOP);
    }

    public int getTerminalMarginRight() {
        return getTerminalMarginSide(TERMUX_APP.KEY_TERMINAL_MARGIN_RIGHT,
                "terminal-margin-horizontal", TERMUX_APP.DEFAULT_VALUE_TERMINAL_MARGIN_RIGHT);
    }

    public void setTerminalMarginRight(int value) {
        setIntClamped(TERMUX_APP.KEY_TERMINAL_MARGIN_RIGHT, value, TERMUX_APP.MIN_TERMINAL_MARGIN_RIGHT, TERMUX_APP.MAX_TERMINAL_MARGIN_RIGHT);
    }

    public int getTerminalMarginBottom() {
        return getTerminalMarginSide(TERMUX_APP.KEY_TERMINAL_MARGIN_BOTTOM,
                "terminal-margin-vertical", TERMUX_APP.DEFAULT_VALUE_TERMINAL_MARGIN_BOTTOM);
    }

    public void setTerminalMarginBottom(int value) {
        setIntClamped(TERMUX_APP.KEY_TERMINAL_MARGIN_BOTTOM, value, TERMUX_APP.MIN_TERMINAL_MARGIN_BOTTOM, TERMUX_APP.MAX_TERMINAL_MARGIN_BOTTOM);
    }

    /**
     * Get the terminal background transparency in percent.
     *
     * @return 0 when the wallpaper feature is off (opaque background — the default and the
     * historical behaviour), up to {@link TermuxPreferenceConstants.TERMUX_APP#MAX_TERMINAL_BACKGROUND_TRANSPARENCY}.
     */
    public int getTerminalBackgroundTransparency() {
        return getInt(TERMUX_APP.KEY_TERMINAL_BACKGROUND_TRANSPARENCY, TERMUX_APP.DEFAULT_VALUE_TERMINAL_BACKGROUND_TRANSPARENCY);
    }

    public void setTerminalBackgroundTransparency(int value) {
        setIntClamped(TERMUX_APP.KEY_TERMINAL_BACKGROUND_TRANSPARENCY, value, TERMUX_APP.MIN_TERMINAL_BACKGROUND_TRANSPARENCY, TERMUX_APP.MAX_TERMINAL_BACKGROUND_TRANSPARENCY);
    }

    /**
     * The wallpaper blur radius behind the terminal, in pixels. Larger values blur more; 0 is
     * a sharp wallpaper. Clamped to the documented range so a stale or out-of-range value from
     * an older build can never reach {@code WindowManager.LayoutParams#setBlurBehindRadius}.
     */
    public int getTerminalBackgroundBlurRadius() {
        return getInt(TERMUX_APP.KEY_TERMINAL_BACKGROUND_BLUR_RADIUS, TERMUX_APP.DEFAULT_VALUE_TERMINAL_BACKGROUND_BLUR_RADIUS);
    }

    public void setTerminalBackgroundBlurRadius(int value) {
        setIntClamped(TERMUX_APP.KEY_TERMINAL_BACKGROUND_BLUR_RADIUS, value, TERMUX_APP.MIN_TERMINAL_BACKGROUND_BLUR_RADIUS, TERMUX_APP.MAX_TERMINAL_BACKGROUND_BLUR_RADIUS);
    }

    public int getTerminalTranscriptRows() {
        return getInt(TERMUX_APP.KEY_TERMINAL_TRANSCRIPT_ROWS, TERMUX_APP.DEFAULT_VALUE_TERMINAL_TRANSCRIPT_ROWS);
    }

    public void setTerminalTranscriptRows(int value) {
        setIntClamped(TERMUX_APP.KEY_TERMINAL_TRANSCRIPT_ROWS, value, TERMUX_APP.MIN_TERMINAL_TRANSCRIPT_ROWS, TERMUX_APP.MAX_TERMINAL_TRANSCRIPT_ROWS);
    }

    /* float */

    public float getTerminalToolbarHeightScaleFactor() {
        return SharedPreferenceUtils.getFloat(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR, TERMUX_APP.DEFAULT_VALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR);
    }

    public void setTerminalToolbarHeightScaleFactor(float value) {
        setFloatClamped(TERMUX_APP.KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR, value, TERMUX_APP.MIN_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR, TERMUX_APP.MAX_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR);
    }

    /* int (extra keys corner radius) */

    public int getExtraKeysCornerRadius() {
        return getInt(TERMUX_APP.KEY_EXTRA_KEYS_CORNER_RADIUS, TERMUX_APP.DEFAULT_VALUE_EXTRA_KEYS_CORNER_RADIUS);
    }

    public void setExtraKeysCornerRadius(int value) {
        setIntClamped(TERMUX_APP.KEY_EXTRA_KEYS_CORNER_RADIUS, value, TERMUX_APP.MIN_EXTRA_KEYS_CORNER_RADIUS, TERMUX_APP.MAX_EXTRA_KEYS_CORNER_RADIUS);
    }

    /* float (extra keys button margin in dp) - stored as int (value × 10) */

    public float getExtraKeysButtonMargin() {
        int stored = getInt(TERMUX_APP.KEY_EXTRA_KEYS_BUTTON_MARGIN, Math.round(TERMUX_APP.DEFAULT_VALUE_EXTRA_KEYS_BUTTON_MARGIN * 10f));
        return stored / 10f;
    }

    public void setExtraKeysButtonMargin(float value) {
        setGenericInt(TERMUX_APP.KEY_EXTRA_KEYS_BUTTON_MARGIN, Math.round(clampFloat(value, TERMUX_APP.MIN_EXTRA_KEYS_BUTTON_MARGIN, TERMUX_APP.MAX_EXTRA_KEYS_BUTTON_MARGIN) * 10f));
    }

    /* int (extra keys base font size in sp) */

    public int getExtraKeysFontSize() {
        return getInt(TERMUX_APP.KEY_EXTRA_KEYS_FONT_SIZE, TERMUX_APP.DEFAULT_VALUE_EXTRA_KEYS_FONT_SIZE);
    }

    public void setExtraKeysFontSize(int value) {
        setIntClamped(TERMUX_APP.KEY_EXTRA_KEYS_FONT_SIZE, value, TERMUX_APP.MIN_EXTRA_KEYS_FONT_SIZE, TERMUX_APP.MAX_EXTRA_KEYS_FONT_SIZE);
    }

    /* String (session shortcuts, raw "Ctrl+KEY" form) */

    public String getShortcutString(String key) {
        return getStringByKey(key);
    }

    public void setShortcutString(String key, String value) {
        setGenericString(key, value);
    }

    /* String */

    public boolean isBackKeyTheEscapeKey() {
        return TermuxPropertyConstants.IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE.equals(getBackKeyBehaviour());
    }

    public String getBackKeyBehaviour() {
        return getString(TERMUX_APP.KEY_BACK_KEY_BEHAVIOUR, TERMUX_APP.DEFAULT_VALUE_BACK_KEY_BEHAVIOUR);
    }

    public void setBackKeyBehaviour(String value) {
        setGenericString(TERMUX_APP.KEY_BACK_KEY_BEHAVIOUR, value);
    }

    public String getDefaultWorkingDirectory() {
        String value = getString(TERMUX_APP.KEY_DEFAULT_WORKING_DIRECTORY, TERMUX_APP.DEFAULT_VALUE_DEFAULT_WORKING_DIRECTORY);
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
        setGenericString(TERMUX_APP.KEY_DEFAULT_WORKING_DIRECTORY, value);
    }

    public String getExtraKeys() {
        return getString(TERMUX_APP.KEY_EXTRA_KEYS, TERMUX_APP.DEFAULT_VALUE_EXTRA_KEYS);
    }

    public void setExtraKeys(String value) {
        setGenericString(TERMUX_APP.KEY_EXTRA_KEYS, value);
    }

    /**
     * Get the {@code extra-keys-session} property: named extra-keys profiles, each with session
     * name prefixes that auto-activate its layout. Returns {@code null} when unset or empty.
     */
    public String getExtraKeysSession() {
        String value = getStringByKey(TERMUX_APP.KEY_EXTRA_KEYS_SESSION);
        return (value == null || value.isEmpty()) ? null : value;
    }

    public void setExtraKeysSession(String value) {
        setGenericString(TERMUX_APP.KEY_EXTRA_KEYS_SESSION, value);
    }

    public String getExtraKeysStyle() {
        return getString(TERMUX_APP.KEY_EXTRA_KEYS_STYLE, TERMUX_APP.DEFAULT_VALUE_EXTRA_KEYS_STYLE);
    }

    public void setExtraKeysStyle(String value) {
        setGenericString(TERMUX_APP.KEY_EXTRA_KEYS_STYLE, value);
    }

    public String getExtraKeysSpecialButtonMode() {
        return getString(TERMUX_APP.KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE, TERMUX_APP.DEFAULT_VALUE_EXTRA_KEYS_SPECIAL_BUTTON_MODE);
    }

    public void setExtraKeysSpecialButtonMode(String value) {
        setGenericString(TERMUX_APP.KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE, value);
    }

    public String getNightMode() {
        return getString(TERMUX_APP.KEY_NIGHT_MODE, TERMUX_APP.DEFAULT_VALUE_NIGHT_MODE);
    }

    public void setNightMode(String value) {
        setGenericString(TERMUX_APP.KEY_NIGHT_MODE, value);
    }

    public boolean shouldEnableDisableSoftKeyboardOnToggle() {
        return TermuxPropertyConstants.IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE.equals(getSoftKeyboardToggleBehaviour());
    }

    public String getSoftKeyboardToggleBehaviour() {
        return getString(TERMUX_APP.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, TERMUX_APP.DEFAULT_VALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR);
    }

    public void setSoftKeyboardToggleBehaviour(String value) {
        setGenericString(TERMUX_APP.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, value);
    }

    public boolean areVirtualVolumeKeysDisabled() {
        return TermuxPropertyConstants.IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME.equals(getVolumeKeysBehaviour());
    }

    public String getVolumeKeysBehaviour() {
        return getString(TERMUX_APP.KEY_VOLUME_KEYS_BEHAVIOUR, TERMUX_APP.DEFAULT_VALUE_VOLUME_KEYS_BEHAVIOUR);
    }

    public void setVolumeKeysBehaviour(String value) {
        setGenericString(TERMUX_APP.KEY_VOLUME_KEYS_BEHAVIOUR, value);
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
