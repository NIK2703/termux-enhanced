package com.termux.app.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void disabledOnlyAtZero() {
        assertFalse(SessionOverviewPreviewThrottle.isEnabled(0));
        assertTrue(SessionOverviewPreviewThrottle.isEnabled(1));
        assertTrue(SessionOverviewPreviewThrottle.isEnabled(30));
    }

    @Test
    public void defaultIsOff() {
        final int defaultFps =
            TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_SESSION_OVERVIEW_PREVIEW_FPS;
        assertEquals(0, defaultFps);
        assertFalse(SessionOverviewPreviewThrottle.isEnabled(defaultFps));
        assertEquals(0, SessionOverviewPreviewThrottle.sliderPositionForFps(defaultFps));
    }

    @Test
    public void sliderStartsWithOffThenCountsDownFrom30() {
        assertEquals(0, SessionOverviewPreviewThrottle.sliderPositionForFps(0));
        assertEquals(1, SessionOverviewPreviewThrottle.sliderPositionForFps(30));
        assertEquals(2, SessionOverviewPreviewThrottle.sliderPositionForFps(29));
        assertEquals(30, SessionOverviewPreviewThrottle.sliderPositionForFps(1));
        assertEquals(0, SessionOverviewPreviewThrottle.sliderPositionForFps(-1));
        assertEquals(1, SessionOverviewPreviewThrottle.sliderPositionForFps(31));
    }

    @Test
    public void sliderPositionsMapBackToFps() {
        assertEquals(0, SessionOverviewPreviewThrottle.fpsForSliderPosition(0));
        assertEquals(30, SessionOverviewPreviewThrottle.fpsForSliderPosition(1));
        assertEquals(29, SessionOverviewPreviewThrottle.fpsForSliderPosition(2));
        assertEquals(1, SessionOverviewPreviewThrottle.fpsForSliderPosition(30));
        assertEquals(0, SessionOverviewPreviewThrottle.fpsForSliderPosition(-1));
        assertEquals(1, SessionOverviewPreviewThrottle.fpsForSliderPosition(31));
    }

    @Test
    public void sliderMappingRoundTripsEverySupportedFps() {
        for (int fps = 0; fps <= 30; fps++) {
            assertEquals(fps, SessionOverviewPreviewThrottle.fpsForSliderPosition(
                SessionOverviewPreviewThrottle.sliderPositionForFps(fps)));
        }
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
