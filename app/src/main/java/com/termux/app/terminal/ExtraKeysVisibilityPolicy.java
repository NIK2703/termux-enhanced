package com.termux.app.terminal;

/**
 * Decides whether the extra-keys view belongs on screen. Its four inputs used to be applied by
 * separate call sites, and a path that missed one left the panel visible without a keyboard.
 * Keeping the decision here gives every caller the same answer and makes it testable.
 */
public final class ExtraKeysVisibilityPolicy {

    private ExtraKeysVisibilityPolicy() {}

    /**
     * @param toolbarEnabled          the "show extra buttons panel" preference; {@code false} means
     *                                the whole strip is off ("Never")
     * @param hideWithKeyboard        {@code true} for the "Only when the keyboard is open" mode
     * @param keyboardVisible         whether the soft keyboard is on screen right now
     * @param inputPanelVisible       whether the text input panel is on screen
     * @param inputPanelOverTerminal  whether that panel floats over the terminal instead of taking
     *                                the extra keys' place in the strip
     * @return {@code true} when the extra-keys view should be {@code VISIBLE}
     */
    public static boolean isExtraKeysVisible(boolean toolbarEnabled,
                                             boolean hideWithKeyboard,
                                             boolean keyboardVisible,
                                             boolean inputPanelVisible,
                                             boolean inputPanelOverTerminal) {
        if (!toolbarEnabled) return false;

        // The panel in the keys' place owns the strip; showing both would stack two panels of
        // the same height. Over the terminal the panel is an overlay, so the keys stay.
        if (inputPanelVisible && !inputPanelOverTerminal) return false;

        return !hideWithKeyboard || keyboardVisible;
    }
}
