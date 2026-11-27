package com.termux.app.terminal;

import android.content.res.Configuration;

import com.termux.app.TermuxAppConstants;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The landscape-aware tab height mode must follow the window's aspect, not the display
 * orientation. Split-screen and freeform windows can be portrait-shaped while the activity's
 * {@link Configuration#orientation} still reports landscape.
 */
public class TabHeightWindowShapeTest {

    private static final String DOUBLE_LANDSCAPE_SINGLE =
        TermuxAppConstants.Preferences.TAB_HEIGHT_MODE_DOUBLE_LANDSCAPE_SINGLE;

    @Test
    public void portraitShapedWindowOnLandscapeDisplayUsesTwoLines() {
        assertFalse(TermuxSessionTabsController.isWindowLandscape(
            400, 800, Configuration.ORIENTATION_LANDSCAPE));
        assertFalse(TermuxSessionTabsController.isSingleLineMode(
            false, DOUBLE_LANDSCAPE_SINGLE));
    }

    @Test
    public void landscapeShapedWindowOnPortraitDisplayUsesOneLine() {
        assertTrue(TermuxSessionTabsController.isWindowLandscape(
            800, 400, Configuration.ORIENTATION_PORTRAIT));
        assertTrue(TermuxSessionTabsController.isSingleLineMode(
            true, DOUBLE_LANDSCAPE_SINGLE));
    }

    @Test
    public void normalLandscapeWindowUsesOneLine() {
        assertTrue(TermuxSessionTabsController.isWindowLandscape(
            800, 400, Configuration.ORIENTATION_LANDSCAPE));
        assertTrue(TermuxSessionTabsController.isSingleLineMode(
            true, DOUBLE_LANDSCAPE_SINGLE));
    }

    @Test
    public void normalPortraitWindowUsesTwoLines() {
        assertFalse(TermuxSessionTabsController.isWindowLandscape(
            400, 800, Configuration.ORIENTATION_PORTRAIT));
        assertFalse(TermuxSessionTabsController.isSingleLineMode(
            false, DOUBLE_LANDSCAPE_SINGLE));
    }

    @Test
    public void missingScreenDimensionsFallBackToOrientation() {
        assertTrue(TermuxSessionTabsController.isWindowLandscape(
            0, 0, Configuration.ORIENTATION_LANDSCAPE));
        assertFalse(TermuxSessionTabsController.isWindowLandscape(
            0, 0, Configuration.ORIENTATION_PORTRAIT));
    }

    @Test
    public void doubleModeAlwaysUsesTwoLines() {
        assertFalse(TermuxSessionTabsController.isSingleLineMode(
            true, TermuxAppConstants.Preferences.TAB_HEIGHT_MODE_DOUBLE));
        assertFalse(TermuxSessionTabsController.isSingleLineMode(
            false, TermuxAppConstants.Preferences.TAB_HEIGHT_MODE_DOUBLE));
    }

    @Test
    public void singleModeAlwaysUsesOneLine() {
        assertTrue(TermuxSessionTabsController.isSingleLineMode(
            true, TermuxAppConstants.Preferences.TAB_HEIGHT_MODE_SINGLE));
        assertTrue(TermuxSessionTabsController.isSingleLineMode(
            false, TermuxAppConstants.Preferences.TAB_HEIGHT_MODE_SINGLE));
    }
}
