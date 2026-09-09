package com.termux.shared.termux.settings.preferences;

/*
 * Version: v0.16.0
 *
 * Changelog
 *
 * - 0.1.0 (2021-03-12)
 *      - Initial Release.
 *
 * - 0.2.0 (2021-03-13)
 *      - Added `KEY_LOG_LEVEL` and `KEY_TERMINAL_VIEW_LOGGING_ENABLED`.
 *
 * - 0.3.0 (2021-03-16)
 *      - Changed to per app scoping of variables so that the same file can store all constants of
 *          Termux app and its plugins. This will allow {@link com.termux.app.TermuxSettings} to
 *          manage preferences of plugins as well if they don't have launcher activity themselves
 *          and also allow plugin apps to make changes to preferences from background.
 *      - Added following to `TERMUX_TASKER_APP`:
 *           `KEY_LOG_LEVEL`.
 *
 * - 0.4.0 (2021-03-13)
 *      - Added following to `TERMUX_APP`:
 *          `KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED` and `DEFAULT_VALUE_PLUGIN_ERROR_NOTIFICATIONS_ENABLED`.
 *
 * - 0.5.0 (2021-03-24)
 *      - Added following to `TERMUX_APP`:
 *          `KEY_LAST_NOTIFICATION_ID` and `DEFAULT_VALUE_KEY_LAST_NOTIFICATION_ID`.
 *
 * - 0.6.0 (2021-03-24)
 *      - Change `DEFAULT_VALUE_KEEP_SCREEN_ON` value to `false` in `TERMUX_APP`.
 *
 * - 0.7.0 (2021-03-27)
 *      - Added following to `TERMUX_APP`:
 *          `KEY_SOFT_KEYBOARD_ENABLED` and `DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED`.
 *
 * - 0.8.0 (2021-04-06)
 *      - Added following to `TERMUX_APP`:
 *          `KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED` and `DEFAULT_VALUE_CRASH_REPORT_NOTIFICATIONS_ENABLED`.
 *
 * - 0.9.0 (2021-04-07)
 *      - Updated javadocs.
 *
 * - 0.10.0 (2021-05-12)
 *      - Added following to `TERMUX_APP`:
 *          `KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE` and `DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE`.
 *
 * - 0.11.0 (2021-07-08)
 *      - Added following to `TERMUX_APP`:
 *          `KEY_DISABLE_TERMINAL_MARGIN_ADJUSTMENT`.
 *
 * - 0.12.0 (2021-08-27)
 *      - Added `TERMUX_API_APP.KEY_LOG_LEVEL`, `TERMUX_BOOT_APP.KEY_LOG_LEVEL`,
 *          `TERMUX_FLOAT_APP.KEY_LOG_LEVEL`, `TERMUX_STYLING_APP.KEY_LOG_LEVEL`,
 *          `TERMUX_Widget_APP.KEY_LOG_LEVEL`.
 *
 * - 0.13.0 (2021-09-02)
 *      - Added following to `TERMUX_FLOAT_APP`:
 *          `KEY_WINDOW_X`, `KEY_WINDOW_Y`, `KEY_WINDOW_WIDTH`, `KEY_WINDOW_HEIGHT`, `KEY_FONTSIZE`,
 *          `KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED`.
 *
 * - 0.14.0 (2021-09-04)
 *      - Added `TERMUX_WIDGET_APP.KEY_TOKEN`.
 *
 * - 0.15.0 (2021-09-05)
 *      - Added following to `TERMUX_TASKER_APP`:
 *          `KEY_LAST_PENDING_INTENT_REQUEST_CODE` and `DEFAULT_VALUE_KEY_LAST_PENDING_INTENT_REQUEST_CODE`.
 *
 * - 0.16.0 (2022-06-11)
 *      - Added following to `TERMUX_APP`:
 *          `KEY_APP_SHELL_NUMBER_SINCE_BOOT` and `KEY_TERMINAL_SESSION_NUMBER_SINCE_BOOT`.
 */

import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

/**
 * A class that defines shared constants of the SharedPreferences used by Termux app and its plugins.
 * This class will be hosted by termux-shared lib and should be imported by other termux plugin
 * apps as is instead of copying constants to random classes. The 3rd party apps can also import
 * it for interacting with termux apps. If changes are made to this file, increment the version number
 * and add an entry in the Changelog section above.
 */
public final class TermuxPreferenceConstants {

    /**
     * Termux app constants.
     */
    public static final class TERMUX_APP {

        /**
         * Defines the key for whether terminal view margin adjustment that is done to prevent soft
         * keyboard from covering bottom part of terminal view on some devices is enabled or not.
         * Margin adjustment may cause screen flickering on some devices and so should be disabled.
         */
        public static final String KEY_TERMINAL_MARGIN_ADJUSTMENT =  "terminal_margin_adjustment";
        public static final boolean DEFAULT_TERMINAL_MARGIN_ADJUSTMENT = true;


        /**
         * Defines the key for whether to show terminal toolbar containing extra keys and text input field.
         */
        public static final String KEY_SHOW_TERMINAL_TOOLBAR = "show_extra_keys";
        public static final boolean DEFAULT_VALUE_SHOW_TERMINAL_TOOLBAR = true;

        /**
         * Defines the key for whether sending text from the text input field also appends a
         * carriage return ("Enter") to submit the line, instead of writing the raw text only.
         */
        public static final String KEY_TEXT_INPUT_APPEND_ENTER = "text_input_append_enter";
        public static final boolean DEFAULT_VALUE_TEXT_INPUT_APPEND_ENTER = true;

        /**
         * Defines the key for what should happen after sending a message from the text
         * input field (e.g. by pressing Enter on the keyboard). It is a single choice
         * that replaces the previously separate "hide input panel" and "hide keyboard"
         * toggles.
         * Allowed values are the {@code TEXT_INPUT_ACTION_ON_SEND_*} constants:
         * <ul>
         *   <li>{@code "none"} — do nothing, keep the panel and keyboard as-is.</li>
         *   <li>{@code "hide_panel"} — hide the input panel and move focus to the terminal.</li>
         *   <li>{@code "hide_keyboard"} — hide the soft keyboard; the input panel is
         *       hidden automatically as part of this.</li>
         * </ul>
         */
        public static final String KEY_TEXT_INPUT_ACTION_ON_SEND = "text_input_action_on_send";
        public static final String DEFAULT_VALUE_TEXT_INPUT_ACTION_ON_SEND = TERMUX_APP.TEXT_INPUT_ACTION_ON_SEND_HIDE_KEYBOARD;

        public static final String TEXT_INPUT_ACTION_ON_SEND_NONE = "none";
        public static final String TEXT_INPUT_ACTION_ON_SEND_HIDE_PANEL = "hide_panel";
        public static final String TEXT_INPUT_ACTION_ON_SEND_HIDE_KEYBOARD = "hide_keyboard";

        /**
         * Defines the key for whether picking a message from the history popup should
         * insert the message at the cursor position in the input field (true), or
         * replace the entire field content (false, legacy behaviour).
         * When insertion mode is active, the inserted text is also automatically
         * selected (highlighted) so the user sees exactly what was placed.
         */
        public static final String KEY_TEXT_INPUT_INSERT_AT_CURSOR = "text_input_insert_at_cursor";
        public static final boolean DEFAULT_VALUE_TEXT_INPUT_INSERT_AT_CURSOR = false;

        /**
         * Defines the key for whether the soft keyboard will be enabled, for cases where users want
         * to use a hardware keyboard instead.
         */
        public static final String KEY_SOFT_KEYBOARD_ENABLED = "soft_keyboard_enabled";
        public static final boolean DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED = true;

        /**
         * Defines the key for whether switching tabs changes the soft keyboard state to match
         * the per-session remembered state of the target session (true), or leaves the
         * keyboard exactly as it currently is (false). When false, switching to a tab whose
         * input panel was open while the keyboard is hidden closes that panel instead of
         * opening it without a keyboard.
         */
        public static final String KEY_KEYBOARD_STATE_FOLLOW_TAB_SWITCH = "keyboard_state_follow_tab_switch";
        public static final boolean DEFAULT_VALUE_KEYBOARD_STATE_FOLLOW_TAB_SWITCH = true;

        /**
         * Defines the key for whether the soft keyboard will be enabled only if no hardware keyboard
         * attached, for cases where users want to use a hardware keyboard instead.
         */
        public static final String KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE = "soft_keyboard_enabled_only_if_no_hardware";
        public static final boolean DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE = false;


        /**
         * Defines the key for whether extra keys should be hidden when the soft keyboard is hidden
         * and shown when the soft keyboard is opened.
         */
        public static final String KEY_HIDE_EXTRA_KEYS_WITH_KEYBOARD = "hide_extra_keys_with_keyboard";
        public static final boolean DEFAULT_VALUE_HIDE_EXTRA_KEYS_WITH_KEYBOARD = true;


        /**
         * Defines the key for the maximum number of auto-complete suggestions to show
         * in the text input popup, based on message history. Range 1-10, default 4.
         */
        public static final String KEY_SUGGESTIONS_MAX_COUNT = "suggestions_max_count";
        public static final int DEFAULT_VALUE_SUGGESTIONS_MAX_COUNT = 4;
        public static final int SUGGESTIONS_MAX_COUNT_MIN = 0;
        public static final int SUGGESTIONS_MAX_COUNT_MAX = 10;


        /**
         * Defines the key for whether to always keep screen on.
         */
        public static final String KEY_KEEP_SCREEN_ON = "screen_always_on";
        public static final boolean DEFAULT_VALUE_KEEP_SCREEN_ON = false;


        /**
         * Defines the key for font size of termux terminal view.
         */
        public static final String KEY_FONTSIZE = "fontsize";


        /**
         * Defines the key for current termux terminal session.
         */
        public static final String KEY_CURRENT_SESSION = "current_session";


        /**
         * Defines the key for current log level.
         */
        public static final String KEY_LOG_LEVEL = "log_level";


        /**
         * Defines the key for last used notification id.
         */
        public static final String KEY_LAST_NOTIFICATION_ID = "last_notification_id";
        public static final int DEFAULT_VALUE_KEY_LAST_NOTIFICATION_ID = 0;

        /**
         * The {@link ExecutionCommand.Runner#APP_SHELL} number after termux app process since boot.
         */
        public static final String KEY_APP_SHELL_NUMBER_SINCE_BOOT = "app_shell_number_since_boot";
        public static final int DEFAULT_VALUE_APP_SHELL_NUMBER_SINCE_BOOT = 0;

        /**
         * The {@link ExecutionCommand.Runner#TERMINAL_SESSION} number after termux app process since boot.
         */
        public static final String KEY_TERMINAL_SESSION_NUMBER_SINCE_BOOT = "terminal_session_number_since_boot";
        public static final int DEFAULT_VALUE_TERMINAL_SESSION_NUMBER_SINCE_BOOT = 0;


        /**
         * Defines the key for whether termux terminal view key logging is enabled or not
         */
        public static final String KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED = "terminal_view_key_logging_enabled";
        public static final boolean DEFAULT_VALUE_TERMINAL_VIEW_KEY_LOGGING_ENABLED = false;

        /**
         * Defines the key for whether flashes and notifications for plugin errors are enabled or not.
         */
        public static final String KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED = "plugin_error_notifications_enabled";
        public static final boolean DEFAULT_VALUE_PLUGIN_ERROR_NOTIFICATIONS_ENABLED = true;

        /**
         * Defines the key for whether notifications for crash reports are enabled or not.
         */
        public static final String KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED = "crash_report_notifications_enabled";
        public static final boolean DEFAULT_VALUE_CRASH_REPORT_NOTIFICATIONS_ENABLED = true;


        /**
         * Defines the key for the background transparency (alpha) of inactive panel elements
         * (bottom buttons, scrollbar thumb). Stored as an integer percentage 0–10, where 5 means
         * ~5% alpha (0x0D = ~13/255). Applied ONCE when the setting changes.
         */
        public static final String KEY_BUTTON_BG_INACTIVE_ALPHA = "button_bg_inactive_alpha";
        public static final int DEFAULT_BUTTON_BG_INACTIVE_ALPHA = 5;

        /**
         * Defines the key for the background transparency (alpha) of active / pressed panel
         * elements. Stored as an integer percentage 10–20, where 12 means ~12% alpha
         * (0x1F = ~31/255). Applied ONCE when the setting changes.
         */
        public static final String KEY_BUTTON_BG_ACTIVE_ALPHA = "button_bg_active_alpha";
        public static final int DEFAULT_BUTTON_BG_ACTIVE_ALPHA = 12;

        /**



        /* ###################################################################
         * Keys migrated from the ~/.termux/termux.properties file.
         * The string values intentionally match the legacy termux.properties keys
         * (see {@link TermuxPropertyConstants}) so that values can be migrated on
         * first launch.
         * ################################################################### */

        /* boolean */

        /** @see TermuxPropertyConstants#KEY_ALLOW_EXTERNAL_APPS legacy "allow-external-apps" */
        public static final String KEY_ALLOW_EXTERNAL_APPS = "allow-external-apps";
        public static final boolean DEFAULT_VALUE_ALLOW_EXTERNAL_APPS = false;

        public static final String KEY_DISABLE_FILE_SHARE_RECEIVER = "disable-file-share-receiver";
        public static final boolean DEFAULT_VALUE_DISABLE_FILE_SHARE_RECEIVER = false;

        public static final String KEY_DISABLE_FILE_VIEW_RECEIVER = "disable-file-view-receiver";
        public static final boolean DEFAULT_VALUE_DISABLE_FILE_VIEW_RECEIVER = false;

        public static final String KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS = "disable-hardware-keyboard-shortcuts";
        public static final boolean DEFAULT_VALUE_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS = false;

        public static final String KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST = "disable-terminal-session-change-toast";
        public static final boolean DEFAULT_VALUE_DISABLE_TERMINAL_SESSION_CHANGE_TOAST = false;

        public static final String KEY_ENFORCE_CHAR_BASED_INPUT = "enforce-char-based-input";
        public static final boolean DEFAULT_VALUE_ENFORCE_CHAR_BASED_INPUT = false;

        public static final String KEY_EXTRA_KEYS_TEXT_ALL_CAPS = "extra-keys-text-all-caps";
        public static final boolean DEFAULT_VALUE_EXTRA_KEYS_TEXT_ALL_CAPS = true;

        // Whether dynamic font size (based on column count and macro bind count) is enabled
        public static final String KEY_EXTRA_KEYS_DYNAMIC_FONT_SIZE = "extra-keys-dynamic-font-size";
        public static final boolean DEFAULT_VALUE_EXTRA_KEYS_DYNAMIC_FONT_SIZE = true;

        // Whether swipe-direction edge indicators are shown on extra keys
        public static final String KEY_EXTRA_KEYS_EDGE_INDICATORS = "extra-keys-edge-indicators";
        public static final boolean DEFAULT_VALUE_EXTRA_KEYS_EDGE_INDICATORS = true;

        public static final String KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP = "hide-soft-keyboard-on-startup";
        public static final boolean DEFAULT_VALUE_HIDE_SOFT_KEYBOARD_ON_STARTUP = false;

        public static final String KEY_RUN_TERMUX_AM_SOCKET_SERVER = "run-termux-am-socket-server";
        public static final boolean DEFAULT_VALUE_RUN_TERMUX_AM_SOCKET_SERVER = true;

        public static final String KEY_TERMINAL_ONCLICK_URL_OPEN = "terminal-onclick-url-open";
        public static final boolean DEFAULT_VALUE_TERMINAL_ONCLICK_URL_OPEN = false;

        public static final String KEY_USE_CTRL_SPACE_WORKAROUND = "ctrl-space-workaround";
        public static final boolean DEFAULT_VALUE_USE_CTRL_SPACE_WORKAROUND = false;

        public static final String KEY_USE_FULLSCREEN = "fullscreen";
        public static final boolean DEFAULT_VALUE_USE_FULLSCREEN = false;

        public static final String KEY_USE_FULLSCREEN_WORKAROUND = "use-fullscreen-workaround";
        public static final boolean DEFAULT_VALUE_USE_FULLSCREEN_WORKAROUND = false;


        /* int */

        public static final String KEY_BELL_BEHAVIOUR = "bell-character";
        public static final int DEFAULT_VALUE_BELL_BEHAVIOUR = TermuxPropertyConstants.DEFAULT_IVALUE_BELL_BEHAVIOUR;

        public static final String KEY_EXTRA_KEYS_HAPTIC = "extra-keys-haptic";
        public static final int DEFAULT_VALUE_EXTRA_KEYS_HAPTIC = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_HAPTIC;

        public static final String KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT = "delete-tmpdir-files-older-than-x-days-on-exit";
        public static final int DEFAULT_VALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT = TermuxPropertyConstants.DEFAULT_IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT;
        public static final int MIN_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT = TermuxPropertyConstants.IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MIN;
        public static final int MAX_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT = TermuxPropertyConstants.IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MAX;

        public static final String KEY_TERMINAL_CURSOR_BLINK_RATE = "terminal-cursor-blink-rate";
        public static final int DEFAULT_VALUE_TERMINAL_CURSOR_BLINK_RATE = TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE;
        public static final int MIN_TERMINAL_CURSOR_BLINK_RATE = TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MIN;
        public static final int MAX_TERMINAL_CURSOR_BLINK_RATE = TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MAX;

        public static final String KEY_TERMINAL_CURSOR_BLINK_ENABLED = "terminal-cursor-blink-enabled";
        public static final boolean DEFAULT_VALUE_TERMINAL_CURSOR_BLINK_ENABLED = TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_ENABLED;


        public static final String KEY_SCROLL_ON_NEW_OUTPUT = "scroll-on-new-output";
        public static final boolean DEFAULT_VALUE_SCROLL_ON_NEW_OUTPUT = false;


        public static final String KEY_TERMINAL_CURSOR_STYLE = "terminal-cursor-style";
        public static final int DEFAULT_VALUE_TERMINAL_CURSOR_STYLE = TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_STYLE;

        public static final String KEY_TERMINAL_MARGIN_LEFT = "terminal-margin-left";
        public static final int DEFAULT_VALUE_TERMINAL_MARGIN_LEFT = TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_MARGIN_LEFT;
        public static final int MIN_TERMINAL_MARGIN_LEFT = TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_LEFT_MIN;
        public static final int MAX_TERMINAL_MARGIN_LEFT = TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_LEFT_MAX;

        public static final String KEY_TERMINAL_MARGIN_TOP = "terminal-margin-top";
        public static final int DEFAULT_VALUE_TERMINAL_MARGIN_TOP = TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_MARGIN_TOP;
        public static final int MIN_TERMINAL_MARGIN_TOP = TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_TOP_MIN;
        public static final int MAX_TERMINAL_MARGIN_TOP = TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_TOP_MAX;

        public static final String KEY_TERMINAL_MARGIN_RIGHT = "terminal-margin-right";
        public static final int DEFAULT_VALUE_TERMINAL_MARGIN_RIGHT = TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_MARGIN_RIGHT;
        public static final int MIN_TERMINAL_MARGIN_RIGHT = TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_RIGHT_MIN;
        public static final int MAX_TERMINAL_MARGIN_RIGHT = TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_RIGHT_MAX;

        public static final String KEY_TERMINAL_MARGIN_BOTTOM = "terminal-margin-bottom";
        public static final int DEFAULT_VALUE_TERMINAL_MARGIN_BOTTOM = TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_MARGIN_BOTTOM;
        public static final int MIN_TERMINAL_MARGIN_BOTTOM = TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_BOTTOM_MIN;
        public static final int MAX_TERMINAL_MARGIN_BOTTOM = TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_BOTTOM_MAX;

        public static final String KEY_TERMINAL_TRANSCRIPT_ROWS = "terminal-transcript-rows";
        public static final int DEFAULT_VALUE_TERMINAL_TRANSCRIPT_ROWS = TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS;
        public static final int MIN_TERMINAL_TRANSCRIPT_ROWS = TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MIN;
        public static final int MAX_TERMINAL_TRANSCRIPT_ROWS = TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MAX;

        /**
         * How transparent the terminal background is, in percent. 0 disables the feature
         * entirely (opaque background, no wallpaper behind the terminal — the historical
         * behaviour); 50 is the maximum, i.e. a background alpha of 128.
         *
         * The value lives only in SharedPreferences (there is no termux.properties
         * counterpart), so MIN/MAX are declared right here.
         */
        public static final String KEY_TERMINAL_BACKGROUND_TRANSPARENCY = "terminal-background-transparency";
        public static final int DEFAULT_VALUE_TERMINAL_BACKGROUND_TRANSPARENCY = 0;
        public static final int MIN_TERMINAL_BACKGROUND_TRANSPARENCY = 0;
        public static final int MAX_TERMINAL_BACKGROUND_TRANSPARENCY = 50;


        /* float */

        public static final String KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR = "terminal-toolbar-height";
        public static final float DEFAULT_VALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR = TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR;
        public static final float MIN_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR = TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MIN;
        public static final float MAX_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR = TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MAX;


        /* int (extra keys corner radius) */

        public static final String KEY_EXTRA_KEYS_CORNER_RADIUS = "extra-keys-corner-radius";
        public static final int DEFAULT_VALUE_EXTRA_KEYS_CORNER_RADIUS = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_CORNER_RADIUS;
        public static final int MIN_EXTRA_KEYS_CORNER_RADIUS = TermuxPropertyConstants.IVALUE_EXTRA_KEYS_CORNER_RADIUS_MIN;
        public static final int MAX_EXTRA_KEYS_CORNER_RADIUS = TermuxPropertyConstants.IVALUE_EXTRA_KEYS_CORNER_RADIUS_MAX;


        /* float (extra keys button margin in dp) */

        public static final String KEY_EXTRA_KEYS_BUTTON_MARGIN = "extra-keys-button-margin";
        public static final float DEFAULT_VALUE_EXTRA_KEYS_BUTTON_MARGIN = 2.0f;
        public static final float MIN_EXTRA_KEYS_BUTTON_MARGIN = 0.1f;
        public static final float MAX_EXTRA_KEYS_BUTTON_MARGIN = 4.0f;


        /* int (extra keys base font size in sp) */

        public static final String KEY_EXTRA_KEYS_FONT_SIZE = "extra-keys-font-size";
        public static final int DEFAULT_VALUE_EXTRA_KEYS_FONT_SIZE = 14;
        public static final int MIN_EXTRA_KEYS_FONT_SIZE = 12;
        public static final int MAX_EXTRA_KEYS_FONT_SIZE = 16;


        /* Integer (session shortcuts, may be null/0) */

        public static final String KEY_SHORTCUT_CREATE_SESSION = "shortcut.create-session";
        public static final String KEY_SHORTCUT_NEXT_SESSION = "shortcut.next-session";
        public static final String KEY_SHORTCUT_PREVIOUS_SESSION = "shortcut.previous-session";
        public static final String KEY_SHORTCUT_RENAME_SESSION = "shortcut.rename-session";


        /* String */

        public static final String KEY_BACK_KEY_BEHAVIOUR = "back-key";
        public static final String DEFAULT_VALUE_BACK_KEY_BEHAVIOUR = TermuxPropertyConstants.DEFAULT_IVALUE_BACK_KEY_BEHAVIOUR;

        public static final String KEY_DEFAULT_WORKING_DIRECTORY = "default-working-directory";
        public static final String DEFAULT_VALUE_DEFAULT_WORKING_DIRECTORY = TermuxPropertyConstants.DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY;

        public static final String KEY_EXTRA_KEYS = "extra-keys";
        public static final String DEFAULT_VALUE_EXTRA_KEYS = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS;

        public static final String KEY_EXTRA_KEYS_SESSION = "extra-keys-session";
        public static final String DEFAULT_VALUE_EXTRA_KEYS_SESSION =
                TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_SESSION; // ""

        public static final String KEY_EXTRA_KEYS_STYLE = "extra-keys-style";
        public static final String DEFAULT_VALUE_EXTRA_KEYS_STYLE = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE;

        public static final String KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE = "extra-keys-special-button-mode";
        public static final String DEFAULT_VALUE_EXTRA_KEYS_SPECIAL_BUTTON_MODE = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_SPECIAL_BUTTON_MODE;

        public static final String KEY_NIGHT_MODE = "night-mode";
        public static final String DEFAULT_VALUE_NIGHT_MODE = TermuxPropertyConstants.DEFAULT_IVALUE_NIGHT_MODE;

        public static final String KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR = "soft-keyboard-toggle-behaviour";
        public static final String DEFAULT_VALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR = TermuxPropertyConstants.DEFAULT_IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR;

        public static final String KEY_VOLUME_KEYS_BEHAVIOUR = "volume-keys";
        public static final String DEFAULT_VALUE_VOLUME_KEYS_BEHAVIOUR = TermuxPropertyConstants.DEFAULT_IVALUE_VOLUME_KEYS_BEHAVIOUR;


    }



    /**
     * Termux:API app constants.
     */
    public static final class TERMUX_API_APP {

        /**
         * Defines the key for current log level.
         */
        public static final String KEY_LOG_LEVEL = "log_level";


        /**
         * Defines the key for last used PendingIntent request code.
         */
        public static final String KEY_LAST_PENDING_INTENT_REQUEST_CODE = "last_pending_intent_request_code";
        public static final int DEFAULT_VALUE_KEY_LAST_PENDING_INTENT_REQUEST_CODE = 0;

    }



    /**
     * Termux:Boot app constants.
     */
    public static final class TERMUX_BOOT_APP {

        /**
         * Defines the key for current log level.
         */
        public static final String KEY_LOG_LEVEL = "log_level";

    }



    /**
     * Termux:Float app constants.
     */
    public static final class TERMUX_FLOAT_APP {

        /**
         * The float window x coordinate.
         */
        public static final String KEY_WINDOW_X = "window_x";

        /**
         * The float window y coordinate.
         */
        public static final String KEY_WINDOW_Y = "window_y";

        /**
         * The float window width.
         */
        public static final String KEY_WINDOW_WIDTH = "window_width";

        /**
         * The float window height.
         */
        public static final String KEY_WINDOW_HEIGHT = "window_height";

        /**
         * Defines the key for font size of termux terminal view.
         */
        public static final String KEY_FONTSIZE = "fontsize";

        /**
         * Defines the key for current log level.
         */
        public static final String KEY_LOG_LEVEL = "log_level";

        /**
         * Defines the key for whether termux terminal view key logging is enabled or not
         */
        public static final String KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED = "terminal_view_key_logging_enabled";
        public static final boolean DEFAULT_VALUE_TERMINAL_VIEW_KEY_LOGGING_ENABLED = false;

    }



    /**
     * Termux:Styling app constants.
     */
    public static final class TERMUX_STYLING_APP {

        /**
         * Defines the key for current log level.
         */
        public static final String KEY_LOG_LEVEL = "log_level";

    }



    /**
     * Termux:Tasker app constants.
     */
    public static final class TERMUX_TASKER_APP {

        /**
         * Defines the key for current log level.
         */
        public static final String KEY_LOG_LEVEL = "log_level";


        /**
         * Defines the key for last used PendingIntent request code.
         */
        public static final String KEY_LAST_PENDING_INTENT_REQUEST_CODE = "last_pending_intent_request_code";
        public static final int DEFAULT_VALUE_KEY_LAST_PENDING_INTENT_REQUEST_CODE = 0;

    }



    /**
     * Termux:Widget app constants.
     */
    public static final class TERMUX_WIDGET_APP {

        /**
         * Defines the key for current log level.
         */
        public static final String KEY_LOG_LEVEL = "log_level";

        /**
         * Defines the key for current token for shortcuts.
         */
        public static final String KEY_TOKEN = "token";

    }

}
