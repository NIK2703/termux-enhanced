package com.termux.app.terminal;

/**
 * Pure timing and interpolation math for the overview opening and closing transitions.
 *
 * <p>The controller feeds the animator's 0..1 progress through these functions so the visual
 * rules (the opening snapshot stays opaque until the final frame, and the closing snapshot is
 * opaque from the first frame) are testable without a running view hierarchy.</p>
 */
public final class SessionOverviewTransitionMath {

    private SessionOverviewTransitionMath() {}

    /** Total flight time. Short enough to feel immediate, long enough to read as motion. */
    public static final long DURATION_MS = 300L;

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

    /**
     * Final terminal preview opacity. The opening flight keeps the card preview hidden until
     * the snapshot has landed, so the flying terminal disappears in one step at the very end
     * instead of dissolving into the thumbnail.
     */
    public static float previewAlpha(float progress) {
        return fadeIn(progress, 1f);
    }

    /** Flying terminal snapshot opacity, the exact inverse of {@link #previewAlpha(float)}. */
    public static float ghostAlpha(float progress) {
        return 1f - previewAlpha(progress);
    }

    /**
     * Expanding terminal snapshot opacity for the closing flight.
     *
     * <p>The snapshot is fully opaque on the first frame: the selected thumbnail starts growing
     * immediately instead of fading in over the card preview. The preview is hidden for the
     * whole flight, so the expansion reads as one continuous resize from the card to the
     * terminal.</p>
     */
    public static float closingGhostAlpha(float progress) {
        return 1f;
    }

    /** Card preview opacity during the closing flight; hidden from the first frame. */
    public static float closingPreviewAlpha(float progress) {
        return 0f;
    }

    /**
     * Strip snapshot opacity while the terminal and its tab fly into the grid.
     *
     * <p>The strip is behind the overview backdrop. A snapshot of it is drawn above the
     * backdrop and follows this fade, so the panel does not simply vanish on the first frame.</p>
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
     * Opacity of every grid card except the active one while the terminal flies into the grid.
     * The active card is represented by the terminal snapshot until the final frame; the rest
     * of the grid fades in around that flight instead of appearing at full strength immediately.
     */
    public static float openingGridCardsAlpha(float progress) {
        return clamp01(progress);
    }

    /** Opacity of the grid cards that stay behind while the selected terminal expands back out. */
    public static float closingGridCardsAlpha(float progress) {
        return 1f - clamp01(progress);
    }

    public static int lerp(int start, int end, float progress) {
        final float p = clamp01(progress);
        return Math.round(start + (end - start) * p);
    }

    public static float lerp(float start, float end, float progress) {
        final float p = clamp01(progress);
        return start + (end - start) * p;
    }

    /**
     * Translation Z for a flying tab. The terminal snapshot's elevation is animated up to
     * {@code terminalElevation}, and child Z order wins over insertion order, so a tab with
     * the default Z would disappear under the terminal even when it is added later. Keeping
     * every tab one pixel above the snapshot's maximum elevation makes the strip stay on top
     * for the whole flight.
     */
    public static float tabGhostZ(float terminalElevation) {
        final float elevation = Float.isNaN(terminalElevation) ? 0f : terminalElevation;
        return Math.max(0f, elevation) + 1f;
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
