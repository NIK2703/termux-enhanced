package com.termux.app.terminal;

import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants;

/**
 * Timing math for the session-overview preview throttle.
 *
 * <p>The user-facing setting is a maximum pass rate in frames per second. {@code 0} disables
 * throttling; {@code 1..30} spaces preview passes by {@code 1000 / fps} milliseconds. The slider
 * has off at position 0 and then 30 down to 1 FPS, so positions are mapped explicitly instead of
 * showing the raw slider number.</p>
 */
public final class SessionOverviewPreviewThrottle {

    private SessionOverviewPreviewThrottle() {
    }

    /** Clamp a stored or user-provided rate to the supported {@code 0..30} FPS range. */
    public static int clampFps(int fps) {
        return Math.max(TermuxPreferenceConstants.TERMUX_APP.MIN_SESSION_OVERVIEW_PREVIEW_FPS,
            Math.min(TermuxPreferenceConstants.TERMUX_APP.MAX_SESSION_OVERVIEW_PREVIEW_FPS,
                fps));
    }

    /** Whether throttling is enabled; {@code false} means passes are not rate-limited. */
    public static boolean isEnabled(int fps) {
        return clampFps(fps) > 0;
    }

    /** Slider position for a rate: position 0 is off, then 30 down to 1 FPS. */
    public static int sliderPositionForFps(int fps) {
        final int clamped = clampFps(fps);
        final int maxFps = TermuxPreferenceConstants.TERMUX_APP.MAX_SESSION_OVERVIEW_PREVIEW_FPS;
        return clamped == 0 ? 0 : maxFps + 1 - clamped;
    }

    /** Rate for a slider position: position 0 is off, then 30 down to 1 FPS. */
    public static int fpsForSliderPosition(int position) {
        final int maxFps = TermuxPreferenceConstants.TERMUX_APP.MAX_SESSION_OVERVIEW_PREVIEW_FPS;
        final int clamped = Math.max(0, Math.min(maxFps, position));
        return clamped == 0 ? 0 : maxFps + 1 - clamped;
    }

    /**
     * Interval between two preview passes, in milliseconds.
     *
     * @param fps maximum passes per second; {@code 0} disables throttling.
     * @return {@code 0} when throttling is off, otherwise a positive interval in milliseconds.
     */
    public static long intervalMs(int fps) {
        final int clamped = clampFps(fps);
        return clamped == 0 ? 0L : Math.max(1L, Math.round(1000.0 / clamped));
    }

    /**
     * Remaining delay before the next preview pass may start.
     *
     * @param lastRenderStartMs start of the last pass that actually rendered a card;
     *                          {@code 0} means no pass has rendered yet.
     * @param nowMs             current {@code SystemClock.uptimeMillis()}.
     * @param intervalMs        interval from {@link #intervalMs(int)}; {@code 0} disables the gate.
     * @return {@code 0} when the next pass may start immediately, otherwise the remaining delay.
     */
    public static long remainingDelayMs(long lastRenderStartMs, long nowMs, long intervalMs) {
        if (intervalMs <= 0L) return 0L;
        final long nextRenderMs = lastRenderStartMs + intervalMs;
        return nextRenderMs > nowMs ? nextRenderMs - nowMs : 0L;
    }
}
