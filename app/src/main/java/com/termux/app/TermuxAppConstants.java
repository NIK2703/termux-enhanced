package com.termux.app;

/**
 * Constants shared by more than one component of the Termux app.
 *
 * <p>Each nested group owns one domain, so a constant keeps a short, unambiguous name without a
 * redundant prefix. Values used by a single class stay private to that class: a central file must
 * not become a dumping ground for class-local tags, handlers or layout details.</p>
 */
public final class TermuxAppConstants {

    private TermuxAppConstants() {}

    /** Maximum number of simultaneously live terminal sessions. */
    public static final int MAX_SESSIONS = 10;

    /** Item ids of the terminal's long-press context menu. */
    public static final class ContextMenu {
        public static final int SELECT_URL = 0;
        public static final int SHARE_TRANSCRIPT = 1;
        public static final int AUTOFILL_PASSWORD = 2;
        public static final int RESET_TERMINAL = 3;
        public static final int KILL_PROCESS = 4;
        public static final int STYLING = 5;
        public static final int TOGGLE_KEEP_SCREEN_ON = 6;
        public static final int HELP = 7;
        public static final int SETTINGS = 8;
        public static final int REPORT = 9;
        public static final int SHARE_SELECTED_TEXT = 10;
        public static final int AUTOFILL_USERNAME = 11;
        public static final int FONT = 12;

        private ContextMenu() {}
    }

    /** Session-overview hover/drag and transition constants. */
    public static final class Overview {
        public static final long FLING_IDLE_MS = 96L;
        public static final long SETTLE_DURATION_MS = 160L;

        /** Shared states of an overview/tab drag gesture. */
        public static final int STATE_IDLE = 0;
        public static final int STATE_PRESSED = 1;
        /** The overview grid uses 2; the tab strip uses a third state of its own. */
        public static final int STATE_DRAGGING = 2;

        private Overview() {}
    }

    /** Tab-strip gesture constants that differ from the ones in {@link Overview}. */
    public static final class TabStrip {
        public static final int STATE_DRAGGING = 3;

        private TabStrip() {}
    }

    /** History size defaults shared by the activity, the controllers and the settings screen. */
    public static final class History {
        public static final int MESSAGE_MAX_DEFAULT = 20;
        public static final int MESSAGE_POPUP_LIMIT = 100;
        public static final int DIRECTORY_MAX_DEFAULT = 20;

        private History() {}
    }

    /** Keys and file name of the app's own SharedPreferences store. */
    public static final class Preferences {
        public static final String FILE_NAME = "termux_prefs";

        public static final String TEXT_INPUT_ENABLED = "text_input_enabled";
        public static final String MESSAGE_HISTORY_MAX = "message_history_max";
        public static final String PER_DIRECTORY_MESSAGE_HISTORY = "per_directory_message_history";
        public static final String AUTOCOMPLETE_SENTENCES_ENABLED = "autocomplete_sentences_enabled";
        public static final String DIRECTORY_HISTORY_MAX = "directory_history_max";
        public static final String TAB_HEIGHT_MODE = "tab_height_mode";
        public static final String TAB_HEIGHT_MODE_SINGLE = "single";
        public static final String TAB_HEIGHT_MODE_DOUBLE = "double";
        /** Two lines in portrait, one line in landscape. */
        public static final String TAB_HEIGHT_MODE_DOUBLE_LANDSCAPE_SINGLE = "double_landscape_single";
        public static final String TAB_PANEL_POSITION = "tab_panel_position";
        /** Bottom in portrait, top in landscape. */
        public static final String TAB_PANEL_POSITION_BOTTOM_LANDSCAPE_TOP = "bottom_landscape_top";
        public static final String SCREEN_ORIENTATION = "screen_orientation";
        public static final String SWIPE_RIGHTMOST_NEW_TAB = "swipe_rightmost_new_tab";
        public static final String NEW_TAB_IN_ACTIVE_SESSION_DIRECTORY = "new_tab_in_active_session_directory";
        public static final String GESTURE_DIRECTORY_HISTORY_ENABLED = "gesture_directory_history_enabled";

        private Preferences() {}
    }

    /** MIME types used by the file pickers and backup/restore intents. */
    public static final class MimeTypes {
        public static final String ALL = "*/*";
        public static final String ZIP = "application/zip";

        private MimeTypes() {}
    }

    /** Broadcast actions used between the settings screens and a live activity. */
    public static final class Broadcasts {
        public static final String TEXT_INPUT_VISIBILITY_CHANGED = "com.termux.TEXT_INPUT_VISIBILITY_CHANGED";
        public static final String TEXT_INPUT_ENABLED_CHANGED = "com.termux.TEXT_INPUT_ENABLED_CHANGED";
        public static final String TAB_PANEL_POSITION_CHANGED = "com.termux.TAB_PANEL_POSITION_CHANGED";
        public static final String TAB_HEIGHT_MODE_CHANGED = "com.termux.TAB_HEIGHT_MODE_CHANGED";
        public static final String TEXT_INPUT_OVER_TERMINAL_CHANGED = "com.termux.TEXT_INPUT_OVER_TERMINAL_CHANGED";
        public static final String INPUT_PANEL_QUICK_ACTIONS_CHANGED = "com.termux.INPUT_PANEL_QUICK_ACTIONS_CHANGED";
        public static final String AUTOCOMPLETE_SETTINGS_CHANGED = "com.termux.AUTOCOMPLETE_SETTINGS_CHANGED";
        public static final String FONT_SIZE_CHANGED = "com.termux.FONT_SIZE_CHANGED";

        private Broadcasts() {}
    }

    /** Values shared by the input-history and suggestion swipe-to-delete gestures. */
    public static final class SwipeDelete {
        public static final int BACKGROUND = 0xFFB3261E;
        public static final int ANIM_MS = 140;
        public static final int RESTORE_ANIM_MS = 160;
        public static final float THRESHOLD_WIDTH_FRACTION = 0.30f;

        private SwipeDelete() {}
    }

    /** Geometry and styling shared by the two history context popups. */
    public static final class HistoryPopup {
        public static final float SHADOW_ALPHA = 0.65f;
        public static final float CONTENT_ALPHA = 0.9f;
        public static final long FRAME_CAP_MS = 48L;
        public static final float FRAME_REFERENCE_MS = 16f;

        private HistoryPopup() {}
    }

    /** Message-history autocomplete tuning shared by the controller and the popup. */
    public static final class Autocomplete {
        public static final int POPUP_SLIDE_DURATION_MS = 140;

        private Autocomplete() {}
    }
}
