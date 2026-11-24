package com.termux.app.terminal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit tests for the visibility and dirty decisions used by the overview preview scheduler. */
public class SessionOverviewPreviewPolicyTest {

    @Test
    public void cardStrictlyAboveViewportIsNotVisible() {
        assertFalse(SessionOverviewPreviewPolicy.isVisible(0, 100, 200, 500));
    }

    @Test
    public void cardStrictlyBelowViewportIsNotVisible() {
        assertFalse(SessionOverviewPreviewPolicy.isVisible(800, 900, 200, 500));
    }

    @Test
    public void cardOverlappingTheViewportEdgeIsVisible() {
        assertTrue(SessionOverviewPreviewPolicy.isVisible(0, 101, 100, 500));
        assertTrue(SessionOverviewPreviewPolicy.isVisible(599, 699, 100, 500));
    }

    @Test
    public void unlaidOutViewportIsTreatedAsVisible() {
        assertTrue(SessionOverviewPreviewPolicy.isVisible(0, 0, 0, 0));
    }

    @Test
    public void zeroHeightCardIsNotVisibleInALaidOutViewport() {
        assertFalse(SessionOverviewPreviewPolicy.isVisible(100, 100, 0, 500));
    }

    @Test
    public void cursorBlinkChangeMarksOnlyThatStateAsDifferent() {
        assertTrue(SessionOverviewPreviewPolicy.previewStateChanged(
                3, 10, true, 0, 3, 10, false, 0));
        assertFalse(SessionOverviewPreviewPolicy.previewStateChanged(
                3, 10, true, 0, 3, 10, true, 0));
    }

    @Test
    public void cursorMoveAndScrollChangeAreDetected() {
        assertTrue(SessionOverviewPreviewPolicy.previewStateChanged(
                3, 10, true, 0, 4, 10, true, 0));
        assertTrue(SessionOverviewPreviewPolicy.previewStateChanged(
                3, 10, true, 0, 3, 10, true, -2));
    }
}
