package com.termux.app.terminal;

/**
 * Geometry for the all-sessions overview grid.
 *
 * <p>The viewport always shows two columns and two rows. Cards normally use the aspect ratio of
 * the terminal grid they preview, so the thumbnail has no background bands above or below the
 * glyphs. Callers without terminal geometry can use the content-area ratio instead. When there
 * are more than four sessions, the additional rows are laid out below the viewport and the
 * surrounding scroll view makes them reachable.</p>
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
        /**
         * Total content height, including the gap below the last row; may be larger than
         * {@link #availableHeight}.
         */
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
     * Compute the grid position for an already known, exact card size.
     *
     * <p>The overview derives {@code cardWidth/cardHeight} from the preview's integer cell pitch;
     * this overload only places those cards, so no aspect arithmetic or rounding is involved.</p>
     */
    public static Layout compute(int sessionCount, int availableWidth, int availableHeight,
                                 int gap, int cardWidth, int cardHeight) {
        final int safeWidth = Math.max(0, availableWidth);
        final int safeHeight = Math.max(0, availableHeight);
        final int safeGap = Math.max(0, gap);
        if (sessionCount <= 0) {
            return new Layout(0, 0, 0, safeGap, safeWidth, safeHeight,
                    0, 0, 0, 0, 0, 0);
        }
        final int columns = COLUMNS;
        final int visibleRows = VISIBLE_ROWS;
        final int totalRows = Math.max(visibleRows, (sessionCount + columns - 1) / columns);
        if (safeWidth <= safeGap * (columns + 1)
                || safeHeight <= safeGap * visibleRows
                || cardWidth <= 0 || cardHeight <= 0) {
            return new Layout(sessionCount, columns, totalRows, safeGap,
                    safeWidth, safeHeight, 0, 0, 0, 0, 0, 0);
        }
        final int gridWidth = columns * cardWidth + safeGap * (columns - 1);
        final int gridHeight = totalRows * cardHeight + safeGap * totalRows;
        final int left = Math.max(safeGap, (safeWidth - gridWidth) / 2);
        return new Layout(sessionCount, columns, totalRows, safeGap,
                safeWidth, safeHeight, cardWidth, cardHeight,
                gridWidth, gridHeight, left, 0);
    }

    /**
     * Compute the card size and the grid position using the content-area aspect ratio.
     *
     * <p>Kept for callers that have no terminal geometry yet; the overview passes the terminal
     * grid's own ratio to {@link #compute(int, int, int, int, double)} so a preview is never
     * letterboxed inside its card.</p>
     */
    public static Layout compute(int sessionCount, int availableWidth, int availableHeight, int gap) {
        return compute(sessionCount, availableWidth, availableHeight, gap, 0);
    }

    /**
     * Compute the card size and the grid position.
     *
     * <p>The card size is based on the two visible rows, not on the total row count. Adding a
     * fifth session therefore adds a scrollable row instead of shrinking every card. The block is
     * horizontally centered but pinned to the top of the viewport: a short grid starts at the
     * first available pixel below the status bar instead of floating in the middle of the
     * window. The last row keeps one gap below it, the same size as the gap between cards, so a
     * height-limited grid never ends flush with the bottom edge.</p>
     *
     * @param sessionCount number of live sessions; values below 1 produce an empty layout
     * @param availableWidth measured content width, excluding padding
     * @param availableHeight measured viewport height, excluding padding
     * @param gap gap between cards in pixels
     * @param cardAspect width/height of the terminal grid the card shows; values at or below
     *                   zero (or non-finite values) fall back to the content-area aspect
     */
    public static Layout compute(int sessionCount, int availableWidth, int availableHeight,
                                 int gap, double cardAspect) {
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
        // Reserve one gap at each screen edge as well as the gaps between cards. When width is
        // the limiting axis this makes the outer gutters exactly the same size as the gap
        // between two thumbnails; when height caps the cards, the remaining width is split
        // evenly so both gutters stay equal and at least one gap wide.
        final int totalGapX = safeGap * (columns + 1);
        // Reserve the gap between the two visible rows and one gap below the second row. The
        // bottom gap matches the inter-card gap and keeps the last row off the screen edge when
        // height limits the cards; the top edge stays pinned below the status bar.
        final int totalGapYVisible = safeGap * visibleRows;

        if (safeWidth <= 0 || safeHeight <= 0
                || safeWidth <= totalGapX || safeHeight <= totalGapYVisible) {
            return new Layout(sessionCount, columns, totalRows, safeGap,
                    safeWidth, safeHeight, 0, 0, 0, 0, 0, 0);
        }

        final double aspect = (cardAspect > 0
                && !Double.isNaN(cardAspect) && !Double.isInfinite(cardAspect))
                ? cardAspect
                : (double) safeWidth / safeHeight;
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

        // gridWidth spans the outer card edges; the two edge gaps are represented by left and
        // the matching right-hand slack, not included in this span.
        final int gridWidth = columns * cardWidth + safeGap * (columns - 1);
        // Every row is followed by one gap, including the bottom row. The top edge has no outer
        // gap because the grid is pinned directly below the status bar.
        final int gridHeight = totalRows * cardHeight + safeGap * totalRows;
        final int left = Math.max(safeGap, (safeWidth - gridWidth) / 2);
        // The grid belongs against the top edge of the app content, below the status bar. Only
        // horizontal slack is distributed; vertical slack stays below the cards.
        final int top = 0;

        return new Layout(sessionCount, columns, totalRows, safeGap,
                safeWidth, safeHeight, cardWidth, cardHeight,
                gridWidth, gridHeight, left, top);
    }
}
