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
         * Total content height, including the gaps above the first row and below the last row;
         * may be larger than {@link #availableHeight}.
         */
        public final int gridHeight;
        public final int left;
        /** Offset of the first row from the content top; equal to the outer gutter. */
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
        final int effectiveGap = Math.max(1, safeGap);
        if (safeWidth <= effectiveGap * (columns + 1)
                || safeHeight <= effectiveGap * (visibleRows + 1)
                || cardWidth <= 0 || cardHeight <= 0) {
            return new Layout(sessionCount, columns, totalRows, effectiveGap,
                    safeWidth, safeHeight, 0, 0, 0, 0, 0, 0);
        }
        // Three horizontal gutters — two edges and the inter-card gap — must fit at their
        // configured width. Trim an oversized exact card instead of shrinking the gutters.
        final int maxCardWidth = (safeWidth - effectiveGap * (columns + 1)) / columns;
        if (maxCardWidth < 1) {
            return new Layout(sessionCount, columns, totalRows, effectiveGap,
                    safeWidth, safeHeight, 0, 0, 0, 0, 0, 0);
        }
        cardWidth = Math.min(cardWidth, maxCardWidth);
        return buildLayout(sessionCount, columns, totalRows, effectiveGap,
                safeWidth, safeHeight, cardWidth, cardHeight);
    }

    /**
     * Place already-sized cards in the grid. Both public overloads end here so the gap rules
     * cannot drift apart.
     */
    private static Layout buildLayout(int sessionCount, int columns, int totalRows, int safeGap,
                                      int safeWidth, int safeHeight,
                                      int cardWidth, int cardHeight) {
        final int cardsWidth = columns * cardWidth;
        // Both callers trim cardWidth until three gutters of this size fit. The inter-card gap
        // stays fixed; only the leftover width is split between the two outer gutters. That keeps
        // the vertical gap identical to the horizontal one, so a height-limited grid can never
        // grow taller than the viewport by taking a larger gap from the width arithmetic.
        final int gap = safeGap;
        final int gridWidth = cardsWidth + gap * (columns - 1);
        // Equal gutters above the first row and below the last row. The top gutter is also the
        // offset of the first row; gridHeight includes both outer gutters so it remains the
        // full content height the scroll view needs.
        final int gridHeight = totalRows * cardHeight + gap * (totalRows + 1);
        final int slack = Math.max(0, safeWidth - gridWidth);
        final int left = slack / 2;
        return new Layout(sessionCount, columns, totalRows, gap,
                safeWidth, safeHeight, cardWidth, cardHeight,
                gridWidth, gridHeight, left, gap);
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
     * horizontally centered and keeps the same gap above the first row as below the last one.
     * Both outer gutters match the gap between cards, so a short grid neither starts flush with
     * the status bar nor ends flush with the bottom edge.</p>
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
        final int effectiveGap = Math.max(1, safeGap);
        // Reserve one gap at each screen edge as well as the gaps between cards. When width is
        // the limiting axis this makes the outer gutters exactly the same size as the gap
        // between two thumbnails; when height caps the cards, the remaining width is split
        // evenly so both gutters stay equal and at least one gap wide.
        final int totalGapX = effectiveGap * (columns + 1);
        // Reserve one gap above the first visible row, one between the rows and one below the
        // second row. All three match the inter-card gap, so the grid has the same breathing
        // room at the top and the bottom when height limits the cards.
        final int totalGapYVisible = effectiveGap * (visibleRows + 1);

        if (safeWidth <= 0 || safeHeight <= 0
                || safeWidth <= totalGapX || safeHeight <= totalGapYVisible) {
            return new Layout(sessionCount, columns, totalRows, effectiveGap,
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

        // Floor rounding can leave less than three gutters of width even though the sizing step
        // reserved them. Trim the card and recompute its height so the two edges and the
        // inter-card gap all keep the same size.
        if (safeWidth - columns * cardWidth < effectiveGap * (columns + 1)) {
            cardWidth = Math.max(1,
                    (safeWidth - effectiveGap * (columns + 1)) / columns);
            cardHeight = Math.max(1, (int) Math.floor(cardWidth / aspect));
        }

        // Both overloads now publish the same gap and place cards in the same way, so a measured
        // grid height always matches what onLayout draws.
        return buildLayout(sessionCount, columns, totalRows, effectiveGap,
                safeWidth, safeHeight, cardWidth, cardHeight);
    }
}
