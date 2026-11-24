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

    /**
     * Progress by which the copy of a flying tab at its source has fully faded out. The fade
     * covers the first third of the flight, so the tab disappears after travelling 33% of its
     * path and crosses the remaining sixth of the line — one third of the source half —
     * invisibly.
     */
    public static final float TAB_FADE_OUT_END = 1f / 3f;

    /**
     * Progress at which the copy of a flying tab at its target starts fading in. The fade covers
     * the last third of the flight, so the target half is traversed invisibly until 67% of the
     * path and only then reappears.
     */
    public static final float TAB_FADE_IN_START = 2f / 3f;

    /** Fraction of the path at which the source copy hands over to the target copy. */
    public static final float TAB_MOTION_SPLIT = 0.5f;

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
     * Opacity of the copy of a flying tab that is still at its source size and title.
     *
     * <p>The tab leaves immediately: it fades out over the first third of the flight while
     * moving along the first third of the source-to-target line at the size and title it had
     * in the strip or on the card. Only its position is interpolated; no text is re-laid out.</p>
     */
    public static float tabSourceAlpha(float progress) {
        final float p = clamp01(progress);
        if (p >= TAB_FADE_OUT_END) return 0f;
        return 1f - p / TAB_FADE_OUT_END;
    }

    /**
     * Opacity of the copy of a flying tab that is already at its target size and title.
     *
     * <p>The target copy stays invisible until 67% of the path, then fades in over the last
     * third while moving the rest of the way. The source and target fades never overlap, so
     * the tab is never drawn twice.</p>
     */
    public static float tabTargetAlpha(float progress) {
        return fadeIn(progress, TAB_FADE_IN_START);
    }

    /**
     * Fraction of the source-to-target centre line covered by the source copy of a flying tab.
     * It advances linearly from 0 to {@link #TAB_MOTION_SPLIT} over the first half of the flight.
     * The fade ends at one third of that line; the remaining sixth of the path — one third of
     * the source half — is traversed invisibly before the hand-off.
     */
    public static float tabSourcePathFraction(float progress) {
        final float p = clamp01(progress);
        if (p >= TAB_MOTION_SPLIT || TAB_MOTION_SPLIT <= 0f) return TAB_MOTION_SPLIT;
        return TAB_MOTION_SPLIT * (p / TAB_MOTION_SPLIT);
    }

    /**
     * Fraction of the source-to-target centre line covered by the target copy of a flying tab.
     * It advances linearly from {@link #TAB_MOTION_SPLIT} to 1 over the second half of the
     * flight. The target stays invisible for the first third of that segment and fades in from
     * the 67% point of the line.
     */
    public static float tabTargetPathFraction(float progress) {
        final float p = clamp01(progress);
        if (p <= TAB_MOTION_SPLIT) return TAB_MOTION_SPLIT;
        if (TAB_MOTION_SPLIT >= 1f) return 1f;
        return TAB_MOTION_SPLIT + (1f - TAB_MOTION_SPLIT)
                * ((p - TAB_MOTION_SPLIT) / (1f - TAB_MOTION_SPLIT));
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
     * Opacity of every non-active opening card. All cards use the flying tab's target fade:
     * invisible until 67% of the path, then visible over the last third. A card without a
     * visible tab does not move, but its appearance must still coincide with the cards that do.
     * The active card is represented by the terminal snapshot and stays out of this fade.
     */
    public static float openingCardAlpha(float progress) {
        return tabTargetAlpha(progress);
    }

    /**
     * Opacity of every unselected closing card. All cards use the flying tab's source fade:
     * visible at the start, gone by 33% of the path. A card without a visible tab does not move,
     * but its disappearance must still coincide with the cards that do. The selected card is
     * represented by the expanding terminal snapshot and stays out of this fade.
     */
    public static float closingCardAlpha(float progress) {
        return tabSourceAlpha(progress);
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
     * Translation Z for a flying tab that is travelling above the terminal snapshot. Child Z
     * order wins over insertion order, so a tab with the default Z could disappear under a
     * snapshot carrying elevation even when it is added later. Keeping the tab one pixel above
     * the snapshot's maximum elevation makes it stay on top for the whole flight. Overview
     * snapshots pass zero because they cast no shadow.
     */
    public static float tabGhostZ(float terminalElevation) {
        final float elevation = Float.isNaN(terminalElevation) ? 0f : terminalElevation;
        return Math.max(0f, elevation) + 1f;
    }

    /**
     * Translation Z for a flying tab whose card thumbnail is visible and moving with it.
     *
     * <p>That pair belongs to the grid, not to the terminal that is shrinking or expanding
     * above it. The flight terminal has zero Z and the tab ghosts are appended after it, so a
     * zero Z would still paint the tab on top by insertion order. A negative Z puts the tab
     * and its close cross strictly below the terminal. A tab moving alone, with no thumbnail on
     * screen yet or any more, keeps the old "above the terminal" Z.</p>
     */
    public static float tabGhostZ(boolean thumbnailVisible, float terminalElevation) {
        return thumbnailVisible ? -1f : tabGhostZ(terminalElevation);
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
