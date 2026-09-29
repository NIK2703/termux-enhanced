package com.termux.app.terminal;

/**
 * Which view the bottom edge of the terminal and of the tab panel is anchored to.
 *
 * <p>A value, not a side effect, and deliberately free of any Android type: the terminal window is
 * a {@code RelativeLayout} whose two topmost children are the tab panel and the terminal pager, the
 * pager declared <em>after</em> the panel and therefore painting on top of it. Anchoring both to
 * the same bottom strip is therefore not a redundancy but a defect — the terminal covers the tab
 * panel outright — and it is invisible in the default "tabs at top" arrangement, so only the "tabs
 * at bottom" setting exposes it.
 *
 * <p>Kept out of the activity so it can be exercised by a plain JVM test: the whole point is that
 * this decision has one owner and can be pinned without inflating anything.
 */
public final class BottomStripAnchors {

    /** Returned for a view that claims no bottom edge, i.e. the tab panel at the top. */
    public static final int NO_BOTTOM_EDGE = 0;

    private BottomStripAnchors() {}

    /**
     * The bottom edges of the terminal and of the tab panel, as {@code [pager, tabs]}.
     *
     * <p>With the tab panel at the bottom the stack from the top down is tab panel, terminal, bottom
     * strip, so the terminal stops at the tabs and the tabs at the strip. With the tab panel at the
     * top it owns the top edge instead, and the terminal stops at the bottom strip directly.
     *
     * @param tabsAtBottom whether the tab panel is configured to sit at the bottom.
     * @param tabStripId id of the tab panel view.
     * @param bottomStripId id of the view currently forming the bottom strip — the extra-keys panel,
     *                      or the input panel while it has taken the extra keys' place.
     * @return {@code [pager, tabs]}, with {@link #NO_BOTTOM_EDGE} for a view that has no bottom edge.
     */
    public static int[] pagerAndTabs(boolean tabsAtBottom, int tabStripId, int bottomStripId) {
        return tabsAtBottom
            ? new int[]{tabStripId, bottomStripId}
            : new int[]{bottomStripId, NO_BOTTOM_EDGE};
    }
}
