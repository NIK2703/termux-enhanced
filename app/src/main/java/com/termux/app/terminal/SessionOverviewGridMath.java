package com.termux.app.terminal;

/**
 * Geometry for the all-sessions overview grid.
 *
 * <p>The viewport always shows two columns and two rows. Cards keep the aspect ratio of the
 * content area they are laid out in. When there are more than four sessions, the additional rows
 * are laid out below the viewport and the surrounding scroll view makes them reachable.</p>
 *
 * <p>Everything is in pixels; the caller converts dp gaps before calling. The math is deliberately
 * independent of Android so it can be property-tested for every session count and for arbitrary
 * window shapes (rotation, split-screen, desktop windows).</p>
 */
public final class SessionOverviewGridMath {

    private SessionOverviewGridMath() {}

    /** Fixed number of columns in the overview. */
    public static final int COLUMNS = 2;

    /** Number of rows visible without scrolling. */
    public static final int VISIBLE_ROWS = 2;

    public static final class Layout {
        public final int sessionCount;
        public final int columns;
        /** Total rows, including rows below the viewport. */
        public final int rows;
        public final int gap;
        public final int availableWidth;
        /** Height of the visible viewport, not of the whole scrollable content. */
        public final int availableHeight;
        public final int cardWidth;
        public final int cardHeight;
        public final int gridWidth;
        /** Total content height; may be larger than {@link #availableHeight}. */
        public final int gridHeight;
        public final int left;
        public final int top;
        public final int emptySlots;

        Layout(int sessionCount, int columns, int rows, int gap,
               int availableWidth, int availableHeight,
               int cardWidth, int cardHeight,
               int gridWidth, int gridHeight, int left, int top) {
            this.sessionCount = sessionCount;
            this.columns = columns;
            this.rows = rows;
            this.gap = gap;
            this.availableWidth = availableWidth;
            this.availableHeight = availableHeight;
            this.cardWidth = cardWidth;
            this.cardHeight = cardHeight;
            this.gridWidth = gridWidth;
            this.gridHeight = gridHeight;
            this.left = left;
            this.top = top;
            this.emptySlots = columns * rows - sessionCount;
        }

        public boolean hasCards() {
            return columns > 0 && rows > 0 && cardWidth > 0 && cardHeight > 0;
        }

        /** True when the extra rows need a scroll view. */
        public boolean isScrollable() {
            return gridHeight > availableHeight;
        }
    }

    /**
     * Compute the card size and the centered grid position.
     *
     * <p>The card size is based on the two visible rows, not on the total row count. Adding a
     * fifth session therefore adds a scrollable row instead of shrinking every card.</p>
     *
     * @param sessionCount number of live sessions; values below 1 produce an empty layout
     * @param availableWidth measured content width, excluding padding
     * @param availableHeight measured viewport height, excluding padding
     * @param gap gap between cards in pixels
     */
    public static Layout compute(int sessionCount, int availableWidth, int availableHeight, int gap) {
        final int safeWidth = Math.max(0, availableWidth);
        final int safeHeight = Math.max(0, availableHeight);
        final int safeGap = Math.max(0, gap);

        if (sessionCount <= 0) {
            return new Layout(0, 0, 0, safeGap, safeWidth, safeHeight,
                    0, 0, 0, 0, 0, 0);
        }

        final int columns = COLUMNS;
        final int visibleRows = VISIBLE_ROWS;
        final int totalRows = Math.max(visibleRows,
                (sessionCount + columns - 1) / columns);
        final int totalGapX = safeGap * (columns - 1);
        final int totalGapYVisible = safeGap * (visibleRows - 1);

        if (safeWidth <= 0 || safeHeight <= 0
                || safeWidth <= totalGapX || safeHeight <= totalGapYVisible) {
            return new Layout(sessionCount, columns, totalRows, safeGap,
                    safeWidth, safeHeight, 0, 0, 0, 0, 0, 0);
        }

        final double aspect = (double) safeWidth / safeHeight;
        final double maxCardWidthByWidth = (safeWidth - totalGapX) / (double) columns;
        final double maxCardWidthByHeight =
                ((safeHeight - totalGapYVisible) / (double) visibleRows) * aspect;
        int cardWidth = Math.max(0,
                (int) Math.floor(Math.min(maxCardWidthByWidth, maxCardWidthByHeight)));

        // Floor, not round: a rounded-up height could push the last visible row outside the
        // viewport by one pixel on an awkward aspect ratio. The resulting aspect error is below
        // one pixel.
        int cardHeight = (int) Math.floor(cardWidth / aspect);
        if (cardWidth > 0 && cardHeight == 0) {
            // Extremely wide, short window: keep one pixel of height and shrink the width so the
            // card still fits both axes.
            cardHeight = 1;
            cardWidth = Math.min(cardWidth, Math.max(1, (int) Math.floor(aspect)));
        }

        final int gridWidth = columns * cardWidth + totalGapX;
        final int gridHeight = totalRows * cardHeight + safeGap * (totalRows - 1);
        final int left = Math.max(0, (safeWidth - gridWidth) / 2);
        final int top = Math.max(0, (safeHeight - gridHeight) / 2);

        return new Layout(sessionCount, columns, totalRows, safeGap,
                safeWidth, safeHeight, cardWidth, cardHeight,
                gridWidth, gridHeight, left, top);
    }
}
