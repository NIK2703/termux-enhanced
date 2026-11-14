package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

/**
 * Pins the truth table that used to live implicitly across four call sites in the activity.
 */
public class ExtraKeysVisibilityPolicyTest {

    private static boolean visible(boolean toolbar, boolean hideWithKeyboard,
                                   boolean keyboard, boolean panel, boolean panelOverTerminal) {
        return ExtraKeysVisibilityPolicy.isExtraKeysVisible(
            toolbar, hideWithKeyboard, keyboard, panel, panelOverTerminal);
    }

    @Test
    public void disabledToolbarHidesThePanelInEveryMode() {
        Assert.assertFalse(visible(false, false, true, false, false));
        Assert.assertFalse(visible(false, false, false, false, false));
        Assert.assertFalse(visible(false, true, true, false, false));
        Assert.assertFalse(visible(false, true, false, false, false));
    }

    @Test
    public void alwaysModeDoesNotDependOnTheKeyboard() {
        Assert.assertTrue(visible(true, false, true, false, false));
        Assert.assertTrue(visible(true, false, false, false, false));
    }

    @Test
    public void keyboardModeTracksTheKeyboard() {
        Assert.assertTrue(visible(true, true, true, false, false));
        Assert.assertFalse(visible(true, true, false, false, false));
    }

    @Test
    public void inputPanelInTheKeysPlaceOwnsTheStrip() {
        Assert.assertFalse(visible(true, false, true, true, false));
        Assert.assertFalse(visible(true, true, true, true, false));
        Assert.assertFalse(visible(true, false, false, true, false));
    }

    @Test
    public void inputPanelOverTheTerminalLeavesTheKeysAlone() {
        Assert.assertTrue(visible(true, true, true, true, true));
        Assert.assertFalse(visible(true, true, false, true, true));
        Assert.assertTrue(visible(true, false, false, true, true));
    }

    @Test
    public void hiddenInputPanelNeverBlocksTheKeys() {
        Assert.assertTrue(visible(true, true, true, false, true));
        Assert.assertTrue(visible(true, true, true, false, false));
    }
}
