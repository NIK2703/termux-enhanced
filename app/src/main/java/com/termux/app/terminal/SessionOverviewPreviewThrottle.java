package com.termux.app.terminal;

import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants;

/**
 * Timing math for the session-overview preview throttle.
 *
 * <p>The user-facing setting is a maximum pass rate in frames per second. {@code 0} disables
 * throttling; {@code 1..20} spaces preview passes by {@code 1000 / fps} milliseconds. The slider
 * uses the same numbers: 0 is off, 1 is 1 FPS and 20 is 20 FPS.</p>
 */
public final class SessionOverviewPreviewThrottle {

    private SessionOverviewPreviewThrottle() {
    }

    /** Clamp a stored, user-provided or slider value to the supported {@code 0..20} range. */
    public static int clampFps(int fps) {
        return Math.max(TermuxPreferenceConstants.TERMUX_APP.MIN_SESSION_OVERVIEW_PREVIEW_FPS,
            Math.min(TermuxPreferenceConstants.TERMUX_APP.MAX_SESSION_OVERVIEW_PREVIEW_FPS,
                fps));
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
