package com.termux.app.terminal.io.autocomplete;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AutoCompletePopupManagerTest {

    private static final int MIN_WIDTH = 200;
    private static final int MARGIN = 16;
    private static final float FRACTION = 0.70f;

    @Test
    public void splitScreenCapsAtTheWindowWidthNotTheDisplayWidth() {
        // A 600px-wide window on a 1200px display. The old display-based cap allowed 840px,
        // which never engaged, so the popup grew to the full input field (600px).
        assertEquals(420, AutoCompletePopupManager.computePopupWidth(
                600, 600, MIN_WIDTH, MARGIN, FRACTION));
    }

    @Test
    public void aNarrowFieldKeepsThePopupAtTheFieldWidth() {
        // The window is wide enough for the fraction cap, but the field itself is not.
        assertEquals(300, AutoCompletePopupManager.computePopupWidth(
                300, 1200, MIN_WIDTH, MARGIN, FRACTION));
    }

    @Test
    public void theMinimumWidthIsUsedWhenTheFieldIsNarrower() {
        assertEquals(200, AutoCompletePopupManager.computePopupWidth(
                100, 300, MIN_WIDTH, MARGIN, FRACTION));
    }

    @Test
    public void theWindowMarginCapsThePopupBeforeTheFraction() {
        assertEquals(468, AutoCompletePopupManager.computePopupWidth(
                500, 500, MIN_WIDTH, MARGIN, 1.0f));
    }

    @Test
    public void aVeryNarrowWindowDoesNotOverflow() {
        assertEquals(126, AutoCompletePopupManager.computePopupWidth(
                0, 180, MIN_WIDTH, MARGIN, FRACTION));
    }
}
