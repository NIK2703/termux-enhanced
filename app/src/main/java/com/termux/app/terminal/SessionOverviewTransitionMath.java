package com.termux.app.terminal;

/**
 * Pure timing and interpolation math for the overview opening and closing transitions.
 *
 * <p>The controller feeds the animator's 0..1 progress through these functions so the visual
 * rules (when the snapshot crossfades into the preview, and the exact reverse on the way back)
 * are testable without a running view hierarchy.</p>
 */
public final class SessionOverviewTransitionMath {

    private SessionOverviewTransitionMath() {}

    /** Total flight time. Short enough to feel immediate, long enough to read as motion. */
    public static final long DURATION_MS = 300L;

    /** Progress at which the terminal snapshot starts dissolving into the live preview. */
    public static final float CROSSFADE_START = 0.78f;

    public static float clamp01(float value) {
        if (Float.isNaN(value)) return 0f;
        if (value <= 0f) return 0f;
        if (value >= 1f) return 1f;
        return value;
    }

    /**
     * A fade that stays at zero until {@code start}, then rises linearly to one at the end of
     * the transition.
     */
    public static float fadeIn(float progress, float start) {
        final float p = clamp01(progress);
        final float from = clamp01(start);
        if (p >= 1f) return 1f;
        if (p <= from) return 0f;
        if (from >= 1f) return 0f;
        return clamp01((p - from) / (1f - from));
    }

    /** Final terminal preview opacity; zero until the snapshot is nearly in place. */
    public static float previewAlpha(float progress) {
        return fadeIn(progress, CROSSFADE_START);
    }

    /** Flying terminal snapshot opacity, the exact inverse of {@link #previewAlpha(float)}. */
    public static float ghostAlpha(float progress) {
        return 1f - previewAlpha(progress);
    }

    /**
     * Expanding terminal snapshot opacity for the closing flight.
     *
     * <p>This is the opening crossfade played backwards: at progress zero the card preview is
     * fully visible and the snapshot is transparent; by {@code 1 - CROSSFADE_START} the snapshot
     * is opaque and the preview has disappeared. After that the snapshot stays opaque until the
     * overlay hands the screen back to the live terminal.</p>
     */
    public static float closingGhostAlpha(float progress) {
        final float p = clamp01(progress);
        final float end = 1f - CROSSFADE_START;
        if (end <= 0f) return 1f;
        return clamp01(p / end);
    }

    /** Card preview opacity during the closing flight; the reverse of the opening crossfade. */
    public static float closingPreviewAlpha(float progress) {
        return 1f - closingGhostAlpha(progress);
    }

    /**
     * Strip snapshot opacity while the terminal and its tab fly into the grid.
     *
     * <p>The strip is behind the overview's opaque scrim. A snapshot of it is drawn above the
     * scrim and follows this fade, so the panel does not simply vanish on the first frame.</p>
     */
    public static float openingTabPanelAlpha(float progress) {
        return 1f - clamp01(progress);
    }

    /**
     * Strip snapshot opacity while the selected terminal expands back out.
     *
     * <p>This is the reverse of {@link #openingTabPanelAlpha(float)}: the final strip, already
     * scrolled and with the target tab selected, fades in behind the flying tab.</p>
     */
    public static float closingTabPanelAlpha(float progress) {
        return clamp01(progress);
    }

    /**
     * Application-background opacity while the selected terminal expands back out.
     *
     * <p>The overview scrim is opaque, so without this layer the window background would only
     * appear when the overlay is removed at the end of the flight. Fading it in at the same rate
     * as the tab panel makes the whole surface return together instead of popping in.</p>
     */
    public static float closingAppBackgroundAlpha(float progress) {
        return clamp01(progress);
    }

    public static int lerp(int start, int end, float progress) {
        final float p = clamp01(progress);
        return Math.round(start + (end - start) * p);
    }

    public static float lerp(float start, float end, float progress) {
        final float p = clamp01(progress);
        return start + (end - start) * p;
    }

    /** Linear ARGB interpolation, including alpha. */
    public static int blendColor(int start, int end, float progress) {
        final float p = clamp01(progress);
        final int a = lerp((start >>> 24) & 0xFF, (end >>> 24) & 0xFF, p);
        final int r = lerp((start >>> 16) & 0xFF, (end >>> 16) & 0xFF, p);
        final int g = lerp((start >>> 8) & 0xFF, (end >>> 8) & 0xFF, p);
        final int b = lerp(start & 0xFF, end & 0xFF, p);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
