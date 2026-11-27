package com.termux.app.terminal;

/**
 * Pure decisions for the session-overview preview scheduler.
 *
 * <p>The grid renders only cards that are both dirty and inside the actual viewport. There is no
 * offscreen preload work: the overview is a scroll surface, so a card outside the viewport is not
 * worth a full terminal repaint. Keeping the arithmetic here makes it testable without a Canvas,
 * a View or a device.</p>
 */
final class SessionOverviewPreviewPolicy {

    private SessionOverviewPreviewPolicy() {}

    /**
     * Whether a card intersects the visible scroll range.
     *
     * @param cardTop        card top in scroll-content coordinates
     * @param cardBottom     card bottom in scroll-content coordinates
     * @param scrollY        current scroll offset
     * @param viewportHeight visible height of the scroll view
     * @return {@code true} when the card should be allowed to render; an unlaid-out viewport
     *         returns {@code true} so the first frame is never left blank
     */
    static boolean isVisible(int cardTop, int cardBottom, int scrollY, int viewportHeight) {
        if (viewportHeight <= 0) return true;
        if (cardBottom <= cardTop) return false;
        return cardBottom > scrollY && cardTop < scrollY + viewportHeight;
    }
}
