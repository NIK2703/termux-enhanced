package com.termux.shared.termux.settings.properties;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.logger.Logger;
import com.termux.shared.data.DataUtils;
import com.termux.shared.settings.properties.SharedProperties;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

import java.io.File;
import java.util.Properties;

/**
 * A facade over {@link TermuxAppSharedPreferences} that exposes the settings that used to be
 * read from the {@code ~/.termux/termux.properties} file. The file based configuration has been
 * removed; all the values are now stored in the app {@link android.content.SharedPreferences}.
 *
 * The package-private {@code getInternalPropertyValue} / {@code getPropertyValue} helpers are kept
 * for backwards compatibility with the few callers that read raw keys, but they now resolve the
 * values from {@link TermuxAppSharedPreferences} instead of an on-disk properties file.
 */
public class TermuxAppSharedProperties {

    private static final String LOG_TAG = "TermuxAppSharedProperties";

    private static TermuxAppSharedProperties properties;

    private final TermuxAppSharedPreferences mPreferences;


    private TermuxAppSharedProperties(@NonNull Context context) {
        mPreferences = TermuxAppSharedPreferences.build(context, true);
    }

    /**
     * Initialize the {@link #properties} and load properties.
     *
     * @param context The {@link Context} for operations.
     * @return Returns the {@link TermuxAppSharedProperties}.
     */
    public static TermuxAppSharedProperties init(@NonNull Context context) {
        if (properties == null)
            properties = new TermuxAppSharedProperties(context);

        return properties;
    }

    /**
     * Get the {@link #properties}.
     *
     * @return Returns the {@link TermuxAppSharedProperties}.
     */
    public static TermuxAppSharedProperties getProperties() {
        return properties;
    }

    private TermuxAppSharedPreferences prefs() {
        return mPreferences;
    }

    /**
     * No-op kept for API compatibility with callers that used to trigger a reload from disk.
     * Values now come from {@link android.content.SharedPreferences} which are always up to date.
     */
    public void loadTermuxPropertiesFromDisk() {
        // Intentionally empty. Configuration is now backed by SharedPreferences.
    }

    /**
     * Migrate an existing {@code ~/.termux/termux.properties} file (and the secondary
     * {@code ~/.config/termux/termux.properties}) into the app {@link android.content.SharedPreferences}.
     *
     * This is a one time operation: it only copies values for keys that are not already present in
     * the SharedPreferences, then renames the migrated file to {@code termux.properties.migrated}
     * so it is not processed again. The legacy file based configuration has been removed, so this
     * keeps existing user configuration working after an upgrade.
     *
     * @param context The {@link Context} for operations.
     */
    public static void migrateLegacyTermuxProperties(@NonNull Context context) {
        TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(context, false);
        if (prefs == null) return;

        File file = SharedProperties.getPropertiesFileFromList(TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST, LOG_TAG);
        if (file == null) return;

        Properties properties = SharedProperties.getPropertiesFromFile(context, file, null);
        if (properties == null || properties.isEmpty()) {
            // Nothing to migrate, just mark the (empty) file as processed.
            renameMigratedFile(file);
            return;
        }

        Logger.logInfo(LOG_TAG, "Migrating legacy termux.properties from " + file.getAbsolutePath());

        for (String key : TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST) {
            if (!properties.containsKey(key)) continue;
            if (prefs.isKeyPresentByKey(key)) continue;

            String value = properties.getProperty(key);
            if (value == null) continue;

            if (TermuxPropertyConstants.isBooleanKey(key)) {
                prefs.setGenericBoolean(key, SharedProperties.getBooleanValueForStringValue(key, value, false, false, LOG_TAG));
            } else if (TermuxPropertyConstants.KEY_BELL_BEHAVIOUR.equals(key)) {
                // Stored as int, but the legacy file value is a string enum ("vibrate"/"beep"/"ignore").
                Integer mapped = TermuxPropertyConstants.MAP_BELL_BEHAVIOUR.get(value);
                prefs.setGenericInt(key, mapped != null ? mapped : TermuxPropertyConstants.DEFAULT_IVALUE_BELL_BEHAVIOUR);
            } else if (TermuxPropertyConstants.KEY_EXTRA_KEYS_HAPTIC.equals(key)) {
                // Stored as int, but the legacy file value is a string enum ("all"/"gestures"/"off").
                Integer mapped = TermuxPropertyConstants.MAP_EXTRA_KEYS_HAPTIC.get(value);
                prefs.setGenericInt(key, mapped != null ? mapped : TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_HAPTIC);
            } else if (TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE.equals(key)) {
                // Same as above: legacy value is a string enum ("block"/"underline"/"bar").
                Integer mapped = TermuxPropertyConstants.MAP_TERMINAL_CURSOR_STYLE.get(value);
                prefs.setGenericInt(key, mapped != null ? mapped : TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_STYLE);
            } else if (TermuxPropertyConstants.isIntKey(key)) {
                prefs.setGenericInt(key, DataUtils.getIntFromString(value, 0));
            } else if (TermuxPropertyConstants.isFloatKey(key)) {
                prefs.setGenericFloat(key, DataUtils.getFloatFromString(value, 0f));
            } else {
                prefs.setGenericString(key, value);
            }
        }

        renameMigratedFile(file);
    }

    private static void renameMigratedFile(@NonNull File file) {
        File migrated = new File(file.getAbsolutePath() + ".migrated");
        if (!file.renameTo(migrated)) {
            // Best effort: try to delete so it is not reprocessed on next launch.
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }


    /* boolean */

    public boolean shouldAllowExternalApps() {
        return prefs().shouldAllowExternalApps();
    }

    public boolean isFileShareReceiverDisabled() {
        return prefs().isFileShareReceiverDisabled();
    }

    public boolean isFileViewReceiverDisabled() {
        return prefs().isFileViewReceiverDisabled();
    }

    public boolean areHardwareKeyboardShortcutsDisabled() {
        return prefs().areHardwareKeyboardShortcutsDisabled();
    }

    public boolean areTerminalSessionChangeToastsDisabled() {
        return prefs().areTerminalSessionChangeToastsDisabled();
    }

    public boolean isEnforcingCharBasedInput() {
        return prefs().isEnforcingCharBasedInput();
    }

    public boolean shouldExtraKeysTextBeAllCaps() {
        return prefs().shouldExtraKeysTextBeAllCaps();
    }

    public boolean shouldSoftKeyboardBeHiddenOnStartup() {
        return prefs().shouldSoftKeyboardBeHiddenOnStartup();
    }

    public boolean shouldRunTermuxAmSocketServer() {
        return prefs().shouldRunTermuxAmSocketServer();
    }

    public boolean shouldOpenTerminalTranscriptURLOnClick() {
        return prefs().shouldOpenTerminalTranscriptURLOnClick();
    }

    public boolean isUsingCtrlSpaceWorkaround() {
        return prefs().isUsingCtrlSpaceWorkaround();
    }

    public boolean isUsingFullScreen() {
        return prefs().isUsingFullScreen();
    }

    public boolean isUsingFullScreenWorkAround() {
        return prefs().isUsingFullScreenWorkAround();
    }


    /* int */

    public int getBellBehaviour() {
        return prefs().getBellBehaviour();
    }

    public int getExtraKeysHaptic() {
        return prefs().getExtraKeysHaptic();
    }

    public int getDeleteTMPDIRFilesOlderThanXDaysOnExit() {
        return prefs().getDeleteTMPDIRFilesOlderThanXDaysOnExit();
    }

    public int getTerminalCursorBlinkRate() {
        return prefs().getTerminalCursorBlinkRate();
    }

    public boolean getTerminalCursorBlinkEnabled() {
        return prefs().getTerminalCursorBlinkEnabled();
    }


    public int getTerminalCursorStyle() {
        return prefs().getTerminalCursorStyle();
    }

    public int getTerminalMarginLeft() {
        return prefs().getTerminalMarginLeft();
    }

    public int getTerminalMarginTop() {
        return prefs().getTerminalMarginTop();
    }

    public int getTerminalMarginRight() {
        return prefs().getTerminalMarginRight();
    }

    public int getTerminalMarginBottom() {
        return prefs().getTerminalMarginBottom();
    }

    public int getTerminalTranscriptRows() {
        return prefs().getTerminalTranscriptRows();
    }

    /**
     * Terminal background transparency in percent: 0 = opaque (the device wallpaper is not
     * shown behind the terminal at all), 50 = maximum transparency (background alpha 128).
     */
    public int getTerminalBackgroundTransparency() {
        return prefs().getTerminalBackgroundTransparency();
    }


    /* float */

    public float getTerminalToolbarHeightScaleFactor() {
        return prefs().getTerminalToolbarHeightScaleFactor();
    }


    /* int */

    public int getExtraKeysCornerRadius() {
        return prefs().getExtraKeysCornerRadius();
    }


    /* float */

    public float getExtraKeysButtonMargin() {
        return prefs().getExtraKeysButtonMargin();
    }


    /* int */

    public int getExtraKeysFontSize() {
        return prefs().getExtraKeysFontSize();
    }


    /* String */

    public boolean isBackKeyTheEscapeKey() {
        return prefs().isBackKeyTheEscapeKey();
    }

    public String getDefaultWorkingDirectory() {
        return prefs().getDefaultWorkingDirectory();
    }

    public String getNightMode() {
        return prefs().getNightMode();
    }

    /** Get the {@link TermuxPropertyConstants#KEY_NIGHT_MODE} value from SharedPreferences. */
    public static String getNightMode(Context context) {
        TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(context, false);
        if (prefs == null) return TermuxPropertyConstants.DEFAULT_IVALUE_NIGHT_MODE;
        return prefs.getNightMode();
    }

    public boolean shouldEnableDisableSoftKeyboardOnToggle() {
        return prefs().shouldEnableDisableSoftKeyboardOnToggle();
    }

    public boolean areVirtualVolumeKeysDisabled() {
        return prefs().areVirtualVolumeKeysDisabled();
    }


    /**
     * Resolve a raw key to its internal value from {@link TermuxAppSharedPreferences}.
     * This mirrors the old {@code TermuxSharedProperties#getInternalPropertyValue} behaviour but
     * reads the stored SharedPreferences value instead of an on-disk properties file.
     *
     * The mapped values for bell behaviour, cursor style, night mode, etc. are applied via the
     * {@code MAP_*} tables in {@link TermuxPropertyConstants} so that the stored SharedPreferences
     * String values are converted consistently with the legacy file based loader.
     */
    public Object getInternalPropertyValue(String key, boolean cached) {
        TermuxAppSharedPreferences prefs = prefs();
        if (key == null) return null;

        switch (key) {
            /* int */
            case TermuxPropertyConstants.KEY_BELL_BEHAVIOUR:
                return getBellBehaviour();
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_HAPTIC:
                return getExtraKeysHaptic();
            case TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_ENABLED:
                return getTerminalCursorBlinkEnabled();
            case TermuxPropertyConstants.KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT:
                return getDeleteTMPDIRFilesOlderThanXDaysOnExit();
            case TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE:
                return getTerminalCursorBlinkRate();
            case TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE:
                return getTerminalCursorStyle();
            case TermuxPropertyConstants.KEY_TERMINAL_MARGIN_LEFT:
                return getTerminalMarginLeft();
            case TermuxPropertyConstants.KEY_TERMINAL_MARGIN_TOP:
                return getTerminalMarginTop();
            case TermuxPropertyConstants.KEY_TERMINAL_MARGIN_RIGHT:
                return getTerminalMarginRight();
            case TermuxPropertyConstants.KEY_TERMINAL_MARGIN_BOTTOM:
                return getTerminalMarginBottom();
            case TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS:
                return getTerminalTranscriptRows();

            /* float */
            case TermuxPropertyConstants.KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR:
                return getTerminalToolbarHeightScaleFactor();
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_CORNER_RADIUS:
                return getExtraKeysCornerRadius();

            /* Integer (code point, may be null) */
            case TermuxPropertyConstants.KEY_SHORTCUT_CREATE_SESSION:
            case TermuxPropertyConstants.KEY_SHORTCUT_NEXT_SESSION:
            case TermuxPropertyConstants.KEY_SHORTCUT_PREVIOUS_SESSION:
            case TermuxPropertyConstants.KEY_SHORTCUT_RENAME_SESSION:
                return getShortcutCodePoint(key);

            /* String (may be null) */
            case TermuxPropertyConstants.KEY_BACK_KEY_BEHAVIOUR:
                return prefs.getBackKeyBehaviour();
            case TermuxPropertyConstants.KEY_DEFAULT_WORKING_DIRECTORY:
                return getDefaultWorkingDirectory();
            case TermuxPropertyConstants.KEY_EXTRA_KEYS:
                return prefs.getExtraKeys();
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE:
                return prefs.getExtraKeysStyle();
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE:
                return prefs.getExtraKeysSpecialButtonMode();
            case TermuxPropertyConstants.KEY_NIGHT_MODE:
                return getNightMode();
            case TermuxPropertyConstants.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR:
                return prefs.getSoftKeyboardToggleBehaviour();
            case TermuxPropertyConstants.KEY_VOLUME_KEYS_BEHAVIOUR:
                return prefs.getVolumeKeysBehaviour();

            default:
                // legacy boolean behaviours
                if (TermuxPropertyConstants.TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key))
                    return prefs().getBooleanByKey(key, false);
                if (TermuxPropertyConstants.TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key))
                    return prefs().getBooleanByKey(key, true);
                else
                    return prefs().getStringByKey(key);
        }
    }

    /**
     * Parse a stored "Ctrl+&lt;key&gt;" shortcut String into its code point, or {@code null} if the
     * value is null/empty or not a valid Ctrl+&lt;something&gt; shortcut. This mirrors the legacy
     * {@code TermuxSharedProperties.getCodePointForSessionShortcuts} behaviour.
     */
    private Integer getShortcutCodePoint(String key) {
        String value = prefs().getShortcutString(key);
        if (value == null) return null;
        return parseShortcutCodePoint(key, value);
    }

    private static Integer parseShortcutCodePoint(String key, String value) {
        if (value == null) return null;
        String[] parts = value.toLowerCase().trim().split("\\+");
        String input = parts.length == 2 ? parts[1].trim() : null;
        if (!(parts.length == 2 && parts[0].trim().equals("ctrl")) || input.isEmpty() || input.length() > 2) {
            Logger.logError(LOG_TAG, "Keyboard shortcut '" + key + "' is not Ctrl+<something>");
            return null;
        }

        char c = input.charAt(0);
        int codePoint = c;
        if (Character.isLowSurrogate(c)) {
            if (input.length() != 2 || Character.isHighSurrogate(input.charAt(1))) {
                Logger.logError(LOG_TAG, "Keyboard shortcut '" + key + "' is not Ctrl+<something>");
                return null;
            } else {
                codePoint = Character.toCodePoint(input.charAt(1), c);
            }
        }

        return codePoint;
    }
}
