package com.termux.app.terminal;

/**
 * Pure arithmetic for the one structural layout transition.
 *
 * <p>The animator owns card translations and a scroll offset with a single progress value. These
 * helpers contain no Android state so timing and clamp composition can be tested directly.</p>
 */
public final class SessionOverviewLayoutTransitionMath {

    /** Cards only: same duration and curve as the existing 160 ms card appearance. */
    public static final long CARD_ONLY_DURATION_MS = 160L;
    /** Any movement that also scrolls: one shared 250 ms curve for cards and scroll. */
    public static final long SCROLL_DURATION_MS = 250L;

    private SessionOverviewLayoutTransitionMath() {}

    public static long durationMs(boolean hasScroll) {
        return hasScroll ? SCROLL_DURATION_MS : CARD_ONLY_DURATION_MS;
    }

    /** Value remaining when the transition is at {@code progress} (0 = start, 1 = end). */
    public static float remaining(float start, float progress) {
        final float p = progress <= 0f ? 0f : progress >= 1f ? 1f : progress;
        return start * (1f - p);
    }

    /**
     * Fold a layout clamp into the visual offset. The real scroll already moved from
     * {@code scrollBefore} to {@code scrollAfter}; the offset keeps the first frame unchanged.
     */
    public static int offsetAfterClamp(int previousOffset, int scrollBefore, int scrollAfter) {
        return previousOffset + scrollAfter - scrollBefore;
    }

    public static int clamp(int value, int min, int max) {
        if (max < min) return min;
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    /** True when any positional channel still has motion at progress 0. */
    public static boolean hasMotion(float[] translations, float scrollOffset) {
        if (scrollOffset != 0f) return true;
        if (translations == null) return false;
        for (float value : translations) {
            if (value != 0f) return true;
        }
        return false;
    }
}
