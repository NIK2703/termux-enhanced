package com.termux.app.terminal;

/**
 * Scroll-position maths for the overview grid.
 *
 * <p>Reopening the overview starts from the position the user left last time. That position is
 * only a preference: the active card must stay fully visible, with its top clear of the
 * status-bar strip and its bottom clear of the viewport edge. This class chooses the scroll offset
 * closest to the remembered one that satisfies both conditions, without leaving the scroll range.
 * </p>
 *
 * <p>All values are pixels. {@code cardTop} and {@code cardBottom} are in the grid's own content
 * coordinates, so they include the grid's top padding.</p>
 */
final class SessionOverviewScrollMath {

    private SessionOverviewScrollMath() {}

    /**
     * Pick the scroll offset for the active card.
     *
     * @param preferredScroll last remembered offset, or another position to stay near
     * @param viewportHeight  visible height of the scroll view
     * @param topInset        grid top padding that must stay above the card (status-bar strip)
     * @param maxScroll       maximum scroll offset of the grid
     * @param cardTop         active card's top in grid content coordinates
     * @param cardBottom      active card's bottom in grid content coordinates
     * @return a clamped scroll offset that keeps the card fully visible whenever the usable
     *         viewport is tall enough
     */
    static int scrollForActiveCard(int preferredScroll, int viewportHeight, int topInset,
                                   int maxScroll, int cardTop, int cardBottom) {
        final int safeMax = Math.max(0, maxScroll);
        final int preferred = clamp(preferredScroll, 0, safeMax);
        if (viewportHeight <= 0 || cardBottom <= cardTop) return preferred;

        final int inset = clamp(topInset, 0, viewportHeight);
        // The card's bottom must not be below the viewport; its top must not be above the line
        // where the status-bar strip ends. The active card is fully visible exactly between these
        // two scroll offsets.
        final int lowest = cardBottom - viewportHeight;
        final int highest = cardTop - inset;

        if (lowest > highest) {
            // The card is taller than the usable viewport. Nothing can show it whole; keep its
            // top edge at the first usable line rather than cutting it off at both ends.
            return clamp(highest, 0, safeMax);
        }
        if (preferred < lowest) return clamp(lowest, 0, safeMax);
        if (preferred > highest) return clamp(highest, 0, safeMax);
        return preferred;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * How far the content must slide after a layout clamps the scroll to a shorter range.
     *
     * <p>Removing cards can make the grid shorter than the current scroll offset. The platform
     * clamps {@code scrollY} immediately; the difference between the old and the clamped position
     * is the distance the content has to travel to reach the new end smoothly. A layout that did
     * not move the position produces zero.</p>
     *
     * @param scrollBefore position before the layout pass
     * @param scrollAfter  position after the pass clamped it
     * @return non-negative settle distance in pixels
     */
    static int shrinkSettleDistance(int scrollBefore, int scrollAfter) {
        return Math.max(0, scrollBefore - scrollAfter);
    }
}
