package com.termux.app.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants;

import org.junit.Test;

/** Unit tests for the FPS-based preview throttle math. */
public class SessionOverviewPreviewThrottleTest {

    @Test
    public void fpsIsClampedToSupportedRange() {
        assertEquals(0, SessionOverviewPreviewThrottle.clampFps(-1));
        assertEquals(0, SessionOverviewPreviewThrottle.clampFps(0));
        assertEquals(1, SessionOverviewPreviewThrottle.clampFps(1));
        assertEquals(30, SessionOverviewPreviewThrottle.clampFps(30));
        assertEquals(30, SessionOverviewPreviewThrottle.clampFps(31));
    }

    @Test
    public void onlyZeroDisablesTheGate() {
        assertEquals(0L, SessionOverviewPreviewThrottle.intervalMs(0));
        assertTrue(SessionOverviewPreviewThrottle.intervalMs(1) > 0L);
        assertTrue(SessionOverviewPreviewThrottle.intervalMs(30) > 0L);
    }

    @Test
    public void defaultIsOff() {
        final int defaultFps =
            TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_SESSION_OVERVIEW_PREVIEW_FPS;
        assertEquals(0, defaultFps);
        assertEquals(0L, SessionOverviewPreviewThrottle.intervalMs(defaultFps));
        assertEquals(0, SessionOverviewPreviewThrottle.clampFps(defaultFps));
    }

    @Test
    public void clampKeepsZeroAndBounds() {
        assertEquals(0, SessionOverviewPreviewThrottle.clampFps(0));
        assertEquals(1, SessionOverviewPreviewThrottle.clampFps(1));
        assertEquals(29, SessionOverviewPreviewThrottle.clampFps(29));
        assertEquals(30, SessionOverviewPreviewThrottle.clampFps(30));
        assertEquals(0, SessionOverviewPreviewThrottle.clampFps(-1));
        assertEquals(30, SessionOverviewPreviewThrottle.clampFps(31));
    }

    @Test
    public void intervalFollowsFps() {
        assertEquals(0L, SessionOverviewPreviewThrottle.intervalMs(0));
        assertEquals(1000L, SessionOverviewPreviewThrottle.intervalMs(1));
        assertEquals(500L, SessionOverviewPreviewThrottle.intervalMs(2));
        assertEquals(333L, SessionOverviewPreviewThrottle.intervalMs(3));
        assertEquals(100L, SessionOverviewPreviewThrottle.intervalMs(10));
        assertEquals(67L, SessionOverviewPreviewThrottle.intervalMs(15));
        assertEquals(33L, SessionOverviewPreviewThrottle.intervalMs(30));
    }

    @Test
    public void intervalUsesClampedFps() {
        assertEquals(0L, SessionOverviewPreviewThrottle.intervalMs(-1));
        assertEquals(33L, SessionOverviewPreviewThrottle.intervalMs(60));
    }

    @Test
    public void firstPassDoesNotWait() {
        assertEquals(0L, SessionOverviewPreviewThrottle.remainingDelayMs(0L, 1000L, 33L));
    }

    @Test
    public void remainingDelayWaitsForTheInterval() {
        assertEquals(33L, SessionOverviewPreviewThrottle.remainingDelayMs(1000L, 1000L, 33L));
        assertEquals(10L, SessionOverviewPreviewThrottle.remainingDelayMs(1000L, 1023L, 33L));
        assertEquals(0L, SessionOverviewPreviewThrottle.remainingDelayMs(1000L, 1033L, 33L));
        assertEquals(0L, SessionOverviewPreviewThrottle.remainingDelayMs(1000L, 1100L, 33L));
    }

    @Test
    public void offIntervalDoesNotDelay() {
        assertEquals(0L, SessionOverviewPreviewThrottle.remainingDelayMs(1000L, 1000L, 0L));
    }
}
