package com.termux.shared.termux.settings.properties;

import com.google.common.collect.ImmutableBiMap;
import com.termux.shared.termux.shell.am.TermuxAmSocketServer;
import com.termux.shared.theme.NightMode;
import com.termux.shared.settings.properties.SharedProperties;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalEmulator;
import com.termux.view.TerminalView;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/*
 * Version: v0.18.0
 * SPDX-License-Identifier: MIT
 *
 * Changelog
 *
 * - 0.1.0 (2021-03-11)
 *      - Initial Release.
 *
 * - 0.2.0 (2021-03-11)
 *      - Renamed `HOME_PATH` to `TERMUX_HOME_DIR_PATH`.
 *      - Renamed `TERMUX_PROPERTIES_PRIMARY_PATH` to `TERMUX_PROPERTIES_PRIMARY_FILE_PATH`.
 *      - Renamed `TERMUX_PROPERTIES_SECONDARY_FILE_PATH` to `TERMUX_PROPERTIES_SECONDARY_FILE_PATH`.
 *
 * - 0.3.0 (2021-03-16)
 *      - Add `*TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR*`.
 *
 * - 0.4.0 (2021-03-16)
 *      - Removed `MAP_GENERIC_BOOLEAN` and `MAP_GENERIC_INVERTED_BOOLEAN`.
 *
 * - 0.5.0 (2021-03-25)
 *      - Add `KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP`.
 *
 * - 0.6.0 (2021-04-07)
 *      - Updated javadocs.
 *
 * - 0.7.0 (2021-05-09)
 *      - Add `*SOFT_KEYBOARD_TOGGLE_BEHAVIOUR*`.
 *
 * - 0.8.0 (2021-05-10)
 *      - Change the `KEY_USE_BACK_KEY_AS_ESCAPE_KEY` and `KEY_VIRTUAL_VOLUME_KEYS_DISABLED` booleans
 *          to `KEY_BACK_KEY_BEHAVIOUR` and `KEY_VOLUME_KEYS_BEHAVIOUR` String internal values.
 *      - Renamed `SOFT_KEYBOARD_TOGGLE_BEHAVIOUR` to `KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR`.
 *
 * - 0.9.0 (2021-05-14)
 *      - Add `*KEY_TERMINAL_CURSOR_BLINK_RATE*`.
 *
 * - 0.10.0 (2021-05-15)
 *      - Add `MAP_BACK_KEY_BEHAVIOUR`, `MAP_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR`, `MAP_VOLUME_KEYS_BEHAVIOUR`.
 *
 * - 0.11.0 (2021-06-10)
 *      - Add `*KEY_TERMINAL_TRANSCRIPT_ROWS*`.
 *
 * - 0.12.0 (2021-06-10)
 *      - Add `*KEY_TERMINAL_CURSOR_STYLE*`.
 *
 * - 0.13.0 (2021-08-25)
 *      - Add `*KEY_TERMINAL_MARGIN_HORIZONTAL*` and `*KEY_TERMINAL_MARGIN_VERTICAL*`.
 *
 * - 0.14.0 (2021-09-02)
 *      - Add `getTermuxFloatPropertiesFile()`.
 *
 * - 0.15.0 (2021-09-05)
 *      - Add `KEY_EXTRA_KEYS_TEXT_ALL_CAPS`.
 *
 * - 0.16.0 (2021-10-21)
 *      - Add `KEY_NIGHT_MODE`.
 *
 * - 0.17.0 (2022-03-17)
 *      - Add `KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT`.
 *
 * - 0.18.0 (2022-06-13)
 *      - Add `KEY_DISABLE_FILE_SHARE_RECEIVER` and `KEY_DISABLE_FILE_VIEW_RECEIVER`.
 */

/**
 * A class that defines shared constants of the SharedProperties used by Termux app and its plugins.
 * This class will be hosted by termux-shared lib and should be imported by other termux plugin
 * apps as is instead of copying constants to random classes. The 3rd party apps can also import
 * it for interacting with termux apps. If changes are made to this file, increment the version number
 * and add an entry in the Changelog section above.
 *
 * The properties are loaded from the first file found at
 * {@link TermuxConstants#TERMUX_PROPERTIES_PRIMARY_FILE_PATH} or
 * {@link TermuxConstants#TERMUX_PROPERTIES_SECONDARY_FILE_PATH}
 */
public final class TermuxPropertyConstants {

    private static final String LOG_TAG = "TermuxPropertyConstants";

    /* boolean */

    /** Defines the key for whether file share receiver of the app is enabled. */
    public static final String KEY_DISABLE_FILE_SHARE_RECEIVER =  "disable-file-share-receiver"; // Default: "disable-file-share-receiver"

    /** Defines the key for whether file view receiver of the app is enabled. */
    public static final String KEY_DISABLE_FILE_VIEW_RECEIVER =  "disable-file-view-receiver"; // Default: "disable-file-view-receiver"



    /** Defines the key for whether hardware keyboard shortcuts are enabled. */
    public static final String KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS =  "disable-hardware-keyboard-shortcuts"; // Default: "disable-hardware-keyboard-shortcuts"



    /** Defines the key for whether a toast will be shown when user changes the terminal session */
    public static final String KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST =  "disable-terminal-session-change-toast"; // Default: "disable-terminal-session-change-toast"



    /** Defines the key for whether to enforce character based input to fix the issue where for some devices like Samsung, the letters might not appear until enter is pressed */
    public static final String KEY_ENFORCE_CHAR_BASED_INPUT =  "enforce-char-based-input"; // Default: "enforce-char-based-input"



    /** Defines the key for whether text for the extra keys buttons should be all capitalized automatically */
    public static final String KEY_EXTRA_KEYS_TEXT_ALL_CAPS =  "extra-keys-text-all-caps"; // Default: "extra-keys-text-all-caps"



    /** Defines the key for whether to hide soft keyboard when termux app is started */
    public static final String KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP =  "hide-soft-keyboard-on-startup"; // Default: "hide-soft-keyboard-on-startup"



    /** Defines the key for whether the {@link TermuxAmSocketServer} should be run at app startup */
    public static final String KEY_RUN_TERMUX_AM_SOCKET_SERVER =  "run-termux-am-socket-server"; // Default: "run-termux-am-socket-server"



    /** Defines the key for whether url links in terminal transcript will automatically open on click or on tap */
    public static final String KEY_TERMINAL_ONCLICK_URL_OPEN =  "terminal-onclick-url-open"; // Default: "terminal-onclick-url-open"



    /** Defines the key for whether to use black UI */
    @Deprecated
    public static final String KEY_USE_BLACK_UI =  "use-black-ui"; // Default: "use-black-ui"



    /** Defines the key for whether to use ctrl space workaround to fix the issue where ctrl+space does not work on some ROMs */
    public static final String KEY_USE_CTRL_SPACE_WORKAROUND =  "ctrl-space-workaround"; // Default: "ctrl-space-workaround"



    /** Defines the key for whether to use fullscreen */
    public static final String KEY_USE_FULLSCREEN =  "fullscreen"; // Default: "fullscreen"



    /** Defines the key for whether to use fullscreen workaround */
    public static final String KEY_USE_FULLSCREEN_WORKAROUND =  "use-fullscreen-workaround"; // Default: "use-fullscreen-workaround"





    /* int */

    /** Defines the key for the bell behaviour */
    public static final String KEY_BELL_BEHAVIOUR =  "bell-character"; // Default: "bell-character"

    public static final String VALUE_BELL_BEHAVIOUR_VIBRATE = "vibrate";
    public static final String VALUE_BELL_BEHAVIOUR_BEEP = "beep";
    public static final String VALUE_BELL_BEHAVIOUR_IGNORE = "ignore";
    public static final String DEFAULT_VALUE_BELL_BEHAVIOUR = VALUE_BELL_BEHAVIOUR_VIBRATE;

    public static final int IVALUE_BELL_BEHAVIOUR_VIBRATE = 1;
    public static final int IVALUE_BELL_BEHAVIOUR_BEEP = 2;
    public static final int IVALUE_BELL_BEHAVIOUR_IGNORE = 3;
    public static final int DEFAULT_IVALUE_BELL_BEHAVIOUR = IVALUE_BELL_BEHAVIOUR_VIBRATE;

    /** Defines the bidirectional map for bell behaviour values and their internal values */
    public static final ImmutableBiMap<String, Integer> MAP_BELL_BEHAVIOUR =
        new ImmutableBiMap.Builder<String, Integer>()
            .put(VALUE_BELL_BEHAVIOUR_VIBRATE, IVALUE_BELL_BEHAVIOUR_VIBRATE)
            .put(VALUE_BELL_BEHAVIOUR_BEEP, IVALUE_BELL_BEHAVIOUR_BEEP)
            .put(VALUE_BELL_BEHAVIOUR_IGNORE, IVALUE_BELL_BEHAVIOUR_IGNORE)
            .build();

    /** Defines the key for extra keys haptic feedback mode */
    public static final String KEY_EXTRA_KEYS_HAPTIC = "extra-keys-haptic";

    public static final String VALUE_EXTRA_KEYS_HAPTIC_ALL = "all";
    public static final String VALUE_EXTRA_KEYS_HAPTIC_GESTURES = "gestures";
    public static final String VALUE_EXTRA_KEYS_HAPTIC_OFF = "off";
    public static final String DEFAULT_VALUE_EXTRA_KEYS_HAPTIC = VALUE_EXTRA_KEYS_HAPTIC_ALL;

    public static final int IVALUE_EXTRA_KEYS_HAPTIC_ALL = 0;
    public static final int IVALUE_EXTRA_KEYS_HAPTIC_GESTURES = 1;
    public static final int IVALUE_EXTRA_KEYS_HAPTIC_OFF = 2;
    public static final int DEFAULT_IVALUE_EXTRA_KEYS_HAPTIC = IVALUE_EXTRA_KEYS_HAPTIC_ALL;

    /** Defines the bidirectional map for extra keys haptic values and their internal values */
    public static final ImmutableBiMap<String, Integer> MAP_EXTRA_KEYS_HAPTIC =
        new ImmutableBiMap.Builder<String, Integer>()
            .put(VALUE_EXTRA_KEYS_HAPTIC_ALL, IVALUE_EXTRA_KEYS_HAPTIC_ALL)
            .put(VALUE_EXTRA_KEYS_HAPTIC_GESTURES, IVALUE_EXTRA_KEYS_HAPTIC_GESTURES)
            .put(VALUE_EXTRA_KEYS_HAPTIC_OFF, IVALUE_EXTRA_KEYS_HAPTIC_OFF)
            .build();


    /** Defines the key for the terminal cursor blink rate */
    public static final String KEY_TERMINAL_CURSOR_BLINK_RATE =  "terminal-cursor-blink-rate"; // Default: "terminal-cursor-blink-rate"
    public static final int IVALUE_TERMINAL_CURSOR_BLINK_RATE_MIN = TerminalView.TERMINAL_CURSOR_BLINK_RATE_MIN;
    public static final int IVALUE_TERMINAL_CURSOR_BLINK_RATE_MAX = TerminalView.TERMINAL_CURSOR_BLINK_RATE_MAX;
    public static final int DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE = 0;

    /** Defines the key for the terminal cursor blink enabled state */
    public static final String KEY_TERMINAL_CURSOR_BLINK_ENABLED = "terminal-cursor-blink-enabled";
    public static final boolean DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_ENABLED = false;




    /** Defines the key for the terminal cursor style */
    public static final String KEY_TERMINAL_CURSOR_STYLE =  "terminal-cursor-style"; // Default: "terminal-cursor-style"

    public static final String VALUE_TERMINAL_CURSOR_STYLE_BLOCK = "block";
    public static final String VALUE_TERMINAL_CURSOR_STYLE_UNDERLINE = "underline";
    public static final String VALUE_TERMINAL_CURSOR_STYLE_BAR = "bar";

    public static final int IVALUE_TERMINAL_CURSOR_STYLE_BLOCK = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
    public static final int IVALUE_TERMINAL_CURSOR_STYLE_UNDERLINE = TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE;
    public static final int IVALUE_TERMINAL_CURSOR_STYLE_BAR = TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR;
    public static final int DEFAULT_IVALUE_TERMINAL_CURSOR_STYLE = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE;

    /** Defines the bidirectional map for terminal cursor styles and their internal values */
    public static final ImmutableBiMap<String, Integer> MAP_TERMINAL_CURSOR_STYLE =
        new ImmutableBiMap.Builder<String, Integer>()
            .put(VALUE_TERMINAL_CURSOR_STYLE_BLOCK, IVALUE_TERMINAL_CURSOR_STYLE_BLOCK)
            .put(VALUE_TERMINAL_CURSOR_STYLE_UNDERLINE, IVALUE_TERMINAL_CURSOR_STYLE_UNDERLINE)
            .put(VALUE_TERMINAL_CURSOR_STYLE_BAR, IVALUE_TERMINAL_CURSOR_STYLE_BAR)
            .build();




    /**
     * Defines the key for how many days old the access time should be of files that should be
     * deleted from $TMPDIR on termux exit.
     * `-1` for none, `0` for all and `> 0` for x days.
     */
    public static final String KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT =  "delete-tmpdir-files-older-than-x-days-on-exit"; // Default: "delete-tmpdir-files-older-than-x-days-on-exit"
    public static final int IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MIN = -1;
    public static final int IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MAX = 100000;
    public static final int DEFAULT_IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT = 3;



    /** Defines the key for the terminal margin on the left in dp units */
    public static final String KEY_TERMINAL_MARGIN_LEFT =  "terminal-margin-left"; // Default: "terminal-margin-left"
    public static final int IVALUE_TERMINAL_MARGIN_LEFT_MIN = 0;
    public static final int IVALUE_TERMINAL_MARGIN_LEFT_MAX = 100;
    public static final int DEFAULT_IVALUE_TERMINAL_MARGIN_LEFT = 2;

    /** Defines the key for the terminal margin on the top in dp units */
    public static final String KEY_TERMINAL_MARGIN_TOP =  "terminal-margin-top"; // Default: "terminal-margin-top"
    public static final int IVALUE_TERMINAL_MARGIN_TOP_MIN = 0;
    public static final int IVALUE_TERMINAL_MARGIN_TOP_MAX = 100;
    public static final int DEFAULT_IVALUE_TERMINAL_MARGIN_TOP = 2;

    /** Defines the key for the terminal margin on the right in dp units */
    public static final String KEY_TERMINAL_MARGIN_RIGHT =  "terminal-margin-right"; // Default: "terminal-margin-right"
    public static final int IVALUE_TERMINAL_MARGIN_RIGHT_MIN = 0;
    public static final int IVALUE_TERMINAL_MARGIN_RIGHT_MAX = 100;
    public static final int DEFAULT_IVALUE_TERMINAL_MARGIN_RIGHT = 2;

    /** Defines the key for the terminal margin on the bottom in dp units */
    public static final String KEY_TERMINAL_MARGIN_BOTTOM =  "terminal-margin-bottom"; // Default: "terminal-margin-bottom"
    public static final int IVALUE_TERMINAL_MARGIN_BOTTOM_MIN = 0;
    public static final int IVALUE_TERMINAL_MARGIN_BOTTOM_MAX = 100;
    public static final int DEFAULT_IVALUE_TERMINAL_MARGIN_BOTTOM = 0;



    /** Defines the key for the terminal transcript rows */
    public static final String KEY_TERMINAL_TRANSCRIPT_ROWS =  "terminal-transcript-rows"; // Default: "terminal-transcript-rows"
    public static final int IVALUE_TERMINAL_TRANSCRIPT_ROWS_MIN = TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MIN;
    public static final int IVALUE_TERMINAL_TRANSCRIPT_ROWS_MAX = TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MAX;
    public static final int DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS = TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS;





    /* float */

    /** Defines the key for the terminal toolbar height */
    public static final String KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR =  "terminal-toolbar-height"; // Default: "terminal-toolbar-height"
    public static final float IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MIN = 0.4f;
    public static final float IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MAX = 3;
    public static final float DEFAULT_IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR = 1;





    /* int */

    /** Defines the key for the extra keys button corner radius in dp */
    public static final String KEY_EXTRA_KEYS_CORNER_RADIUS = "extra-keys-corner-radius";
    public static final int IVALUE_EXTRA_KEYS_CORNER_RADIUS_MIN = 0;
    public static final int IVALUE_EXTRA_KEYS_CORNER_RADIUS_MAX = 24;
    public static final int DEFAULT_IVALUE_EXTRA_KEYS_CORNER_RADIUS = 12;



    /* Integer */

    /** Defines the key for create session shortcut */
    public static final String KEY_SHORTCUT_CREATE_SESSION =  "shortcut.create-session"; // Default: "shortcut.create-session"
    /** Defines the key for next session shortcut */
    public static final String KEY_SHORTCUT_NEXT_SESSION =  "shortcut.next-session"; // Default: "shortcut.next-session"
    /** Defines the key for previous session shortcut */
    public static final String KEY_SHORTCUT_PREVIOUS_SESSION =  "shortcut.previous-session"; // Default: "shortcut.previous-session"
    /** Defines the key for rename session shortcut */
    public static final String KEY_SHORTCUT_RENAME_SESSION =  "shortcut.rename-session"; // Default: "shortcut.rename-session"

    public static final int ACTION_SHORTCUT_CREATE_SESSION = 1;
    public static final int ACTION_SHORTCUT_NEXT_SESSION = 2;
    public static final int ACTION_SHORTCUT_PREVIOUS_SESSION = 3;
    public static final int ACTION_SHORTCUT_RENAME_SESSION = 4;

    /** Defines the bidirectional map for session shortcut values and their internal actions */
    public static final ImmutableBiMap<String, Integer> MAP_SESSION_SHORTCUTS =
        new ImmutableBiMap.Builder<String, Integer>()
            .put(KEY_SHORTCUT_CREATE_SESSION, ACTION_SHORTCUT_CREATE_SESSION)
            .put(KEY_SHORTCUT_NEXT_SESSION, ACTION_SHORTCUT_NEXT_SESSION)
            .put(KEY_SHORTCUT_PREVIOUS_SESSION, ACTION_SHORTCUT_PREVIOUS_SESSION)
            .put(KEY_SHORTCUT_RENAME_SESSION, ACTION_SHORTCUT_RENAME_SESSION)
            .build();





    /* String */

    /** Defines the key for whether back key will behave as escape key or literal back key */
    public static final String KEY_BACK_KEY_BEHAVIOUR =  "back-key"; // Default: "back-key"

    public static final String IVALUE_BACK_KEY_BEHAVIOUR_BACK = "back";
    public static final String IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE = "escape";
    public static final String DEFAULT_IVALUE_BACK_KEY_BEHAVIOUR = IVALUE_BACK_KEY_BEHAVIOUR_BACK;

    /** Defines the bidirectional map for back key behaviour values and their internal values */
    public static final ImmutableBiMap<String, String> MAP_BACK_KEY_BEHAVIOUR =
        new ImmutableBiMap.Builder<String, String>()
            .put(IVALUE_BACK_KEY_BEHAVIOUR_BACK, IVALUE_BACK_KEY_BEHAVIOUR_BACK)
            .put(IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE, IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE)
            .build();



    /** Defines the key for the default working directory */
    public static final String KEY_DEFAULT_WORKING_DIRECTORY =  "default-working-directory"; // Default: "default-working-directory"
    /** Defines the default working directory */
    public static final String DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY = TermuxConstants.TERMUX_HOME_DIR_PATH;



    /** Defines the key for extra keys */
    public static final String KEY_EXTRA_KEYS =  "extra-keys"; // Default: "extra-keys"
    //public static final String DEFAULT_IVALUE_EXTRA_KEYS = "[[ESC, TAB, CTRL, ALT, {key: '-', popup: '|'}, DOWN, UP]]"; // Single row
    public static final String DEFAULT_IVALUE_EXTRA_KEYS = "[['ESC','/',{key: '-', popup: '|'},'HOME','UP','END','PGUP'], ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]"; // Double row

    /** Defines the key for extra keys style */
    public static final String KEY_EXTRA_KEYS_STYLE =  "extra-keys-style"; // Default: "extra-keys-style"
    public static final String DEFAULT_IVALUE_EXTRA_KEYS_STYLE = "default";

    /** Defines the key for the special buttons (CTRL, ALT, SHIFT, FN) behaviour mode */
    public static final String KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE =  "extra-keys-special-button-mode"; // Default: "extra-keys-special-button-mode"
    public static final String IVALUE_EXTRA_KEYS_SPECIAL_BUTTON_MODE_STICKY = "sticky";
    public static final String IVALUE_EXTRA_KEYS_SPECIAL_BUTTON_MODE_HOLD = "hold";
    public static final String DEFAULT_IVALUE_EXTRA_KEYS_SPECIAL_BUTTON_MODE = IVALUE_EXTRA_KEYS_SPECIAL_BUTTON_MODE_STICKY;

    /** Defines the bidirectional map for special buttons mode values and their internal values */
    public static final ImmutableBiMap<String, String> MAP_EXTRA_KEYS_SPECIAL_BUTTON_MODE =
        new ImmutableBiMap.Builder<String, String>()
            .put(IVALUE_EXTRA_KEYS_SPECIAL_BUTTON_MODE_STICKY, IVALUE_EXTRA_KEYS_SPECIAL_BUTTON_MODE_STICKY)
            .put(IVALUE_EXTRA_KEYS_SPECIAL_BUTTON_MODE_HOLD, IVALUE_EXTRA_KEYS_SPECIAL_BUTTON_MODE_HOLD)
            .build();



    /** Defines the key for session-name based extra-keys layout switching. */
    public static final String KEY_EXTRA_KEYS_SESSION = "extra-keys-session"; // Default: "extra-keys-session"
    /** Default empty value (session switching disabled). */
    public static final String DEFAULT_IVALUE_EXTRA_KEYS_SESSION = "";


    /** Defines the key for {@link NightMode}. */
    public static final String KEY_NIGHT_MODE = "night-mode"; // Default: "night-mode"

    public static final String IVALUE_NIGHT_MODE_TRUE = NightMode.TRUE.getName();
    public static final String IVALUE_NIGHT_MODE_FALSE = NightMode.FALSE.getName();
    public static final String IVALUE_NIGHT_MODE_SYSTEM = NightMode.SYSTEM.getName();
    public static final String DEFAULT_IVALUE_NIGHT_MODE = IVALUE_NIGHT_MODE_SYSTEM;

    /** Defines the bidirectional map for {@link NightMode} values and their internal values */
    public static final ImmutableBiMap<String, String> MAP_NIGHT_MODE =
        new ImmutableBiMap.Builder<String, String>()
            .put(IVALUE_NIGHT_MODE_TRUE, IVALUE_NIGHT_MODE_TRUE)
            .put(IVALUE_NIGHT_MODE_FALSE, IVALUE_NIGHT_MODE_FALSE)
            .put(IVALUE_NIGHT_MODE_SYSTEM, IVALUE_NIGHT_MODE_SYSTEM)
            .build();



    /** Defines the key for the per-theme terminal color scheme for the light app theme. */
    public static final String KEY_COLOR_SCHEME_LIGHT = "color-scheme-light"; // Default: "color-scheme-light"
    /** Defines the key for the per-theme terminal color scheme for the dark app theme. */
    public static final String KEY_COLOR_SCHEME_DARK = "color-scheme-dark"; // Default: "color-scheme-dark"

    /**
     * Default value for {@link #KEY_COLOR_SCHEME_LIGHT} / {@link #KEY_COLOR_SCHEME_DARK}: no
     * scheme selected, i.e. the built-in (non-Material) light/dark terminal scheme.
     */
    public static final String DEFAULT_IVALUE_COLOR_SCHEME = "Default";

    /**
     * Defines the key for the "color scheme type" of the wallpaper-derived (Monet) terminal
     * scheme. Accepts {@code system} plus the nine kde-material-you-colors variant names and their
     * indices (0..8). Only meaningful on Android 12+ and only while
     * {@link #KEY_COLOR_SCHEME_LIGHT} / {@link #KEY_COLOR_SCHEME_DARK} is set to {@code Monet}.
     */
    public static final String KEY_MONET_VARIANT = "monet-variant"; // Default: "monet-variant"

    /** Defines the key for how deep in the surface stack the Monet background sits. */
    public static final String KEY_MONET_BACKGROUND = "monet-background"; // Default: "monet-background"

    /** Defines the key for where the seven ANSI accent candidates come from. */
    public static final String KEY_MONET_ACCENT_SOURCE = "monet-accent-source"; // Default: "monet-accent-source"

    /** Defines the key for the minimum contrast of every ANSI accent against the background. */
    public static final String KEY_MONET_ACCENT_CONTRAST = "monet-accent-contrast"; // Default: "monet-accent-contrast"

    /** Defines the key for the chroma multiplier applied to the ANSI accents. */
    public static final String KEY_MONET_CHROMA = "monet-chroma"; // Default: "monet-chroma"

    /** Defines the key for the tone multiplier applied to background roles. */
    public static final String KEY_MONET_TONE = "monet-tone"; // Default: "monet-tone"

    /** Defines the key for what goes into ANSI slot 0 ({@code bg} or a dimmed {@code dim}). */
    public static final String KEY_MONET_COLOR0 = "monet-color0"; // Default: "monet-color0"

    /** Defines the key for whether toggle soft keyboard request will show/hide or enable/disable keyboard */
    public static final String KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR =  "soft-keyboard-toggle-behaviour"; // Default: "soft-keyboard-toggle-behaviour"

    public static final String IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE = "show/hide";
    public static final String IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE = "enable/disable";
    public static final String DEFAULT_IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR = IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE;

    /** Defines the bidirectional map for toggle soft keyboard behaviour values and their internal values */
    public static final ImmutableBiMap<String, String> MAP_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR =
        new ImmutableBiMap.Builder<String, String>()
            .put(IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE, IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE)
            .put(IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE, IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE)
            .build();



    /** Defines the key for whether volume keys will behave as virtual or literal volume keys */
    public static final String KEY_VOLUME_KEYS_BEHAVIOUR =  "volume-keys"; // Default: "volume-keys"

    public static final String IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL = "virtual";
    public static final String IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME = "volume";
    public static final String DEFAULT_IVALUE_VOLUME_KEYS_BEHAVIOUR = IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL;

    /** Defines the bidirectional map for volume keys behaviour values and their internal values */
    public static final ImmutableBiMap<String, String> MAP_VOLUME_KEYS_BEHAVIOUR =
        new ImmutableBiMap.Builder<String, String>()
            .put(IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL, IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL)
            .put(IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME, IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME)
            .build();





    /** Defines the set for keys loaded by termux
     * Setting this to {@code null} will make {@link SharedProperties} throw an exception.
     * */
    public static final Set<String> TERMUX_APP_PROPERTIES_LIST = new HashSet<>(Arrays.asList(
        /* boolean */
        KEY_DISABLE_FILE_SHARE_RECEIVER,
        KEY_DISABLE_FILE_VIEW_RECEIVER,
        KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS,
        KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST,
        KEY_ENFORCE_CHAR_BASED_INPUT,
        KEY_EXTRA_KEYS_TEXT_ALL_CAPS,
        KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP,
        KEY_RUN_TERMUX_AM_SOCKET_SERVER,
        KEY_TERMINAL_ONCLICK_URL_OPEN,
        KEY_USE_CTRL_SPACE_WORKAROUND,
        KEY_USE_FULLSCREEN,
        KEY_USE_FULLSCREEN_WORKAROUND,
        TermuxConstants.PROP_ALLOW_EXTERNAL_APPS,

        /* int */
        KEY_BELL_BEHAVIOUR,
        KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT,
        KEY_TERMINAL_CURSOR_BLINK_RATE,
        KEY_TERMINAL_CURSOR_STYLE,
        KEY_TERMINAL_MARGIN_LEFT,
        KEY_TERMINAL_MARGIN_TOP,
        KEY_TERMINAL_MARGIN_RIGHT,
        KEY_TERMINAL_MARGIN_BOTTOM,
        KEY_TERMINAL_TRANSCRIPT_ROWS,

        /* float */
        KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR,

        /* Integer (session shortcuts are stored as raw "Ctrl+KEY" strings, see String section) */

        /* String */
        KEY_BACK_KEY_BEHAVIOUR,
        KEY_SHORTCUT_CREATE_SESSION,
        KEY_SHORTCUT_NEXT_SESSION,
        KEY_SHORTCUT_PREVIOUS_SESSION,
        KEY_SHORTCUT_RENAME_SESSION,
        KEY_COLOR_SCHEME_DARK,
        KEY_COLOR_SCHEME_LIGHT,
        KEY_MONET_VARIANT,
        KEY_MONET_BACKGROUND,
        KEY_MONET_ACCENT_SOURCE,
        KEY_MONET_ACCENT_CONTRAST,
        KEY_MONET_CHROMA,
        KEY_MONET_TONE,
        KEY_MONET_COLOR0,
        KEY_DEFAULT_WORKING_DIRECTORY,
        KEY_EXTRA_KEYS,
        KEY_EXTRA_KEYS_STYLE,
        KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE,
        KEY_EXTRA_KEYS_SESSION,
        KEY_NIGHT_MODE,
        KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR,
        KEY_VOLUME_KEYS_BEHAVIOUR
    ));

    /** Defines the set for keys loaded by termux that have default boolean behaviour with false as default.
     * "true" -> true
     * "false" -> false
     * default: false
     */
    public static final Set<String> TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST = new HashSet<>(Arrays.asList(
        KEY_DISABLE_FILE_SHARE_RECEIVER,
        KEY_DISABLE_FILE_VIEW_RECEIVER,
        KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS,
        KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST,
        KEY_ENFORCE_CHAR_BASED_INPUT,
        KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP,
        KEY_TERMINAL_ONCLICK_URL_OPEN,
        KEY_USE_CTRL_SPACE_WORKAROUND,
        KEY_USE_FULLSCREEN,
        KEY_USE_FULLSCREEN_WORKAROUND,
        TermuxConstants.PROP_ALLOW_EXTERNAL_APPS
    ));

    /** Defines the set for keys loaded by termux that have default boolean behaviour with true as default.
     * "true" -> true
     * "false" -> false
     * default: true
     */
    public static final Set<String> TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST = new HashSet<>(Arrays.asList(
        KEY_EXTRA_KEYS_TEXT_ALL_CAPS,
        KEY_RUN_TERMUX_AM_SOCKET_SERVER
    ));

    /** Defines the set for keys loaded by termux that have default inverted boolean behaviour with false as default.
     * "false" -> true
     * "true" -> false
     * default: false
     */
    public static final Set<String> TERMUX_DEFAULT_INVERETED_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST = new HashSet<>(Arrays.asList(
    ));

    /** Defines the set for keys loaded by termux that have default inverted boolean behaviour with true as default.
     * "false" -> true
     * "true" -> false
     * default: true
     */
    public static final Set<String> TERMUX_DEFAULT_INVERETED_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST = new HashSet<>(Arrays.asList(
    ));


    /** The boolean keys (both default-false and default-true behaviour). */
    public static final Set<String> TERMUX_APP_PROPERTIES_BOOLEAN_KEYS = Collections.unmodifiableSet(
        new HashSet<String>() {{
            addAll(TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST);
            addAll(TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST);
        }});

    /** The int keys. */
    public static final Set<String> TERMUX_APP_PROPERTIES_INT_KEYS = new HashSet<>(Arrays.asList(
        KEY_BELL_BEHAVIOUR,
        KEY_EXTRA_KEYS_HAPTIC,
        KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT,
        KEY_TERMINAL_CURSOR_BLINK_RATE,
        KEY_TERMINAL_CURSOR_STYLE,
        KEY_TERMINAL_MARGIN_LEFT,
        KEY_TERMINAL_MARGIN_TOP,
        KEY_TERMINAL_MARGIN_RIGHT,
        KEY_TERMINAL_MARGIN_BOTTOM,
        KEY_TERMINAL_TRANSCRIPT_ROWS
    ));

    /** The float keys. */
    public static final Set<String> TERMUX_APP_PROPERTIES_FLOAT_KEYS = new HashSet<>(Arrays.asList(
        KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR
    ));

    /** The String keys. */
    public static final Set<String> TERMUX_APP_PROPERTIES_STRING_KEYS = new HashSet<>(Arrays.asList(
        KEY_BACK_KEY_BEHAVIOUR,
        KEY_SHORTCUT_CREATE_SESSION,
        KEY_SHORTCUT_NEXT_SESSION,
        KEY_SHORTCUT_PREVIOUS_SESSION,
        KEY_SHORTCUT_RENAME_SESSION,
        KEY_COLOR_SCHEME_DARK,
        KEY_COLOR_SCHEME_LIGHT,
        KEY_MONET_VARIANT,
        KEY_MONET_BACKGROUND,
        KEY_MONET_ACCENT_SOURCE,
        KEY_MONET_ACCENT_CONTRAST,
        KEY_MONET_CHROMA,
        KEY_MONET_TONE,
        KEY_MONET_COLOR0,
        KEY_DEFAULT_WORKING_DIRECTORY,
        KEY_EXTRA_KEYS,
        KEY_EXTRA_KEYS_STYLE,
        KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE,
        KEY_EXTRA_KEYS_SESSION,
        KEY_NIGHT_MODE,
        KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR,
        KEY_VOLUME_KEYS_BEHAVIOUR
    ));


    public static boolean isBooleanKey(String key) {
        return TERMUX_APP_PROPERTIES_BOOLEAN_KEYS.contains(key);
    }

    public static boolean isIntKey(String key) {
        return TERMUX_APP_PROPERTIES_INT_KEYS.contains(key);
    }

    public static boolean isFloatKey(String key) {
        return TERMUX_APP_PROPERTIES_FLOAT_KEYS.contains(key);
    }

}
