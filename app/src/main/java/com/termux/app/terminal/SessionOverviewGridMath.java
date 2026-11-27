package com.termux.app.terminal;

/**
 * Pure geometry for the all-sessions overview.
 *
 * <p>The architecture is deliberately not expressed in rows. A layout is a list of card slots plus
 * the union of the occupied slots ({@code contentBounds}). Centring, scrolling and structural
 * animations consume only those rectangles; the number of rows is a derived detail used only by
 * the compatibility fields below and by tests. Card sizing remains a separate policy: the current
 * policy uses two columns and a two-row page, but callers never branch on a row count.</p>
 *
 * <p>Everything is in pixels. No Android classes are referenced so the geometry can be property
 * tested for any session count, column count, card aspect and window shape.</p>
 */
public final class SessionOverviewGridMath {

    private SessionOverviewGridMath() {}

    /** Column count chosen by the current card-sizing policy. */
    public static final int COLUMNS = 2;

    /** Visible rows used by the current card-sizing policy. */
    public static final int VISIBLE_ROWS = 2;

    /** One card's rectangle in content coordinates, before the view adds its padding. */
    public static final class Slot {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        Slot(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public int width() {
            return Math.max(0, right - left);
        }

        public int height() {
            return Math.max(0, bottom - top);
        }

        public float centerX() {
            return (left + right) / 2f;
        }

        public float centerY() {
            return (top + bottom) / 2f;
        }

        Slot translated(int dx, int dy) {
            return new Slot(left + dx, top + dy, right + dx, bottom + dy);
        }
    }

    /**
     * Immutable result of one layout pass. Rows and columns are published as derived metadata;
     * consumers that centre or animate cards must use {@link #slot(int)} / {@link #contentHeight}.
     */
    public static final class Layout {
        public final int sessionCount;
        public final int columns;
        /** Derived: ceil(sessionCount / columns). Not used for centring or transitions. */
        public final int rows;
        public final int gap;
        /** Larger breathing space above the first row and below the last row, in pixels. */
        public final int outerGap;
        public final int availableWidth;
        /** Keyboard-stable height used to size cards. */
        public final int availableHeight;
        /** Height actually visible right now; centring uses this, not {@link #availableHeight}. */
        public final int visibleHeight;
        public final int cardWidth;
        public final int cardHeight;
        /** Width of the union of occupied slots. */
        public final int contentWidth;
        /** Height of the union of occupied slots, without outer gutters. */
        public final int contentHeight;
        public final int gridWidth;
        /** Scrollable content height, including both outer gutters when it overflows. */
        public final int gridHeight;
        /** Left edge of the occupied bounds in content coordinates. */
        public final int left;
        /** Top edge of the occupied bounds in content coordinates. */
        public final int top;
        public final int emptySlots;

        private final Slot[] slots;

        Layout(int sessionCount, int columns, int rows, int gap, int outerGap,
               int availableWidth, int availableHeight, int visibleHeight,
               int contentWidth, int contentHeight,
               int cardWidth, int cardHeight,
               int gridWidth, int gridHeight, int left, int top, Slot[] slots) {
            this.sessionCount = sessionCount;
            this.columns = columns;
            this.rows = rows;
            this.gap = gap;
            this.outerGap = outerGap;
            this.availableWidth = availableWidth;
            this.availableHeight = availableHeight;
            this.visibleHeight = visibleHeight;
            this.contentWidth = contentWidth;
            this.contentHeight = contentHeight;
            this.cardWidth = cardWidth;
            this.cardHeight = cardHeight;
            this.gridWidth = gridWidth;
            this.gridHeight = gridHeight;
            this.left = left;
            this.top = top;
            this.slots = slots;
            this.emptySlots = Math.max(0, columns * rows - sessionCount);
        }

        public boolean hasCards() {
            if (sessionCount <= 0 || cardWidth <= 0 || cardHeight <= 0
                    || slots.length != sessionCount) {
                return false;
            }
            for (Slot slot : slots) {
                if (slot == null) return false;
            }
            return true;
        }

        /** True when the occupied content plus its outer gutters needs scrolling. */
        public boolean isScrollable() {
            return gridHeight > visibleHeight;
        }

        /** Logical slot for a card index, before RTL mirroring. */
        public Slot slot(int index) {
            if (index < 0 || index >= slots.length) return null;
            return slots[index];
        }

        /**
         * Visual left edge of a slot. RTL mirrors each rectangle inside the available width, so a
         * single centred card stays exactly where it was.
         */
        public int visualLeft(int index, boolean rtl) {
            final Slot slot = slot(index);
            if (slot == null) return 0;
            return rtl ? availableWidth - slot.right : slot.left;
        }

        public float centerX(int index, boolean rtl) {
            final Slot slot = slot(index);
            if (slot == null) return 0f;
            return rtl ? availableWidth - slot.centerX() : slot.centerX();
        }

        public float centerY(int index) {
            final Slot slot = slot(index);
            return slot == null ? 0f : slot.centerY();
        }

        public int slotCount() {
            return slots.length;
        }
    }

    /**
     * Place already-sized cards. Both public overloads end here so the gap rules cannot drift
     * apart. The only row arithmetic is local to slot generation; nothing downstream reads it.
     */
    static Layout buildLayout(int sessionCount, int columns, int gap, int outerGap,
                              int availableWidth, int availableHeight,
                              int cardWidth, int cardHeight) {
        final int safeWidth = Math.max(0, availableWidth);
        final int safeHeight = Math.max(0, availableHeight);
        final Slot[] slots = new Slot[Math.max(0, sessionCount)];
        if (sessionCount <= 0 || columns <= 0 || cardWidth <= 0 || cardHeight <= 0) {
            final int derivedColumns = sessionCount > 0 ? Math.max(0, columns) : 0;
            final int derivedRows = sessionCount > 0 && derivedColumns > 0
                    ? (sessionCount + derivedColumns - 1) / derivedColumns : 0;
            return new Layout(sessionCount, derivedColumns, derivedRows, gap, outerGap,
                    safeWidth, safeHeight, safeHeight, 0, 0,
                    Math.max(0, cardWidth), Math.max(0, cardHeight),
                    0, 0, 0, 0, new Slot[0]);
        }

        int contentWidth = 0;
        int contentHeight = 0;
        for (int i = 0; i < sessionCount; i++) {
            final int row = i / columns;
            final int column = i % columns;
            final int leadingLeft = column * (cardWidth + gap);
            final int leadingTop = row * (cardHeight + gap);
            final Slot slot = new Slot(leadingLeft, leadingTop,
                    leadingLeft + cardWidth, leadingTop + cardHeight);
            slots[i] = slot;
            contentWidth = Math.max(contentWidth, slot.right);
            contentHeight = Math.max(contentHeight, slot.bottom);
        }

        // Centre the union of occupied slots, not a reserved full grid. A single card therefore
        // lands in the middle of the viewport on both axes without a special case.
        // A policy that supplies cards wider than the viewport must not centre them into a
        // negative left edge; keep the first column reachable instead.
        final int dx = Math.max(0, (safeWidth - contentWidth) / 2);
        final int top;
        final int gridHeight;
        if (contentHeight + outerGap * 2 <= safeHeight) {
            top = Math.max(0, (safeHeight - contentHeight) / 2);
            gridHeight = safeHeight;
        } else {
            top = outerGap;
            gridHeight = contentHeight + outerGap * 2;
        }
        for (int i = 0; i < slots.length; i++) {
            slots[i] = slots[i].translated(dx, top);
        }

        final int derivedRows = (sessionCount + columns - 1) / columns;
        return new Layout(sessionCount, columns, derivedRows, gap, outerGap,
                safeWidth, safeHeight, safeHeight,
                contentWidth, contentHeight, cardWidth, cardHeight,
                contentWidth, gridHeight, dx, top, slots);
    }

    /** Recompute only the vertical placement for the viewport that is actually visible. */
    public static Layout recenter(Layout layout, int visibleHeightPx) {
        if (!layout.hasCards()) return layout;
        final int visibleHeight = Math.max(0, visibleHeightPx);
        final int top;
        final int gridHeight;
        if (layout.contentHeight + layout.outerGap * 2 <= visibleHeight) {
            top = Math.max(0, (visibleHeight - layout.contentHeight) / 2);
            gridHeight = visibleHeight;
        } else {
            top = layout.outerGap;
            gridHeight = layout.contentHeight + layout.outerGap * 2;
        }
        if (layout.top == top && layout.gridHeight == gridHeight
                && layout.visibleHeight == visibleHeight) {
            return layout;
        }
        final int dy = top - layout.top;
        final Slot[] slots = new Slot[layout.slots.length];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = layout.slots[i].translated(0, dy);
        }
        return new Layout(layout.sessionCount, layout.columns, layout.rows,
                layout.gap, layout.outerGap,
                layout.availableWidth, layout.availableHeight, visibleHeight,
                layout.contentWidth, layout.contentHeight,
                layout.cardWidth, layout.cardHeight,
                layout.gridWidth, gridHeight, layout.left, top, slots);
    }

    /** Place an already-known exact card size; no aspect arithmetic or rounding happens. */
    public static Layout compute(int sessionCount, int availableWidth, int availableHeight,
                                 int gap, int cardWidth, int cardHeight) {
        return compute(sessionCount, availableWidth, availableHeight,
                gap, gap, cardWidth, cardHeight);
    }

    public static Layout compute(int sessionCount, int availableWidth, int availableHeight,
                                 int gap, int outerGap, int cardWidth, int cardHeight) {
        final int safeWidth = Math.max(0, availableWidth);
        final int safeHeight = Math.max(0, availableHeight);
        final int effectiveGap = Math.max(1, gap);
        final int effectiveOuter = Math.max(0, outerGap);
        final int columns = COLUMNS;
        final int minVisibleHeight =
                effectiveGap * (VISIBLE_ROWS - 1) + effectiveOuter * 2;
        if (sessionCount <= 0 || safeWidth <= effectiveGap * (columns + 1)
                || safeHeight <= minVisibleHeight || cardWidth <= 0 || cardHeight <= 0) {
            return buildLayout(sessionCount, columns, effectiveGap, effectiveOuter,
                    safeWidth, safeHeight, 0, 0);
        }
        // Trim an oversized exact card instead of shrinking the gutters. Both axes shrink by the
        // same factor so the preview keeps its aspect.
        final int maxCardWidth = (safeWidth - effectiveGap * (columns + 1)) / columns;
        if (maxCardWidth < 1) {
            return buildLayout(sessionCount, columns, effectiveGap, effectiveOuter,
                    safeWidth, safeHeight, 0, 0);
        }
        int width = cardWidth;
        int height = cardHeight;
        if (width > maxCardWidth) {
            final float trim = (float) maxCardWidth / width;
            height = Math.max(1, (int) Math.floor(height * trim));
            width = maxCardWidth;
        }
        return buildLayout(sessionCount, columns, effectiveGap, effectiveOuter,
                safeWidth, safeHeight, width, height);
    }

    /** Compute card size and placement from a content-area aspect ratio. */
    public static Layout compute(int sessionCount, int availableWidth, int availableHeight, int gap) {
        return compute(sessionCount, availableWidth, availableHeight, gap, gap, 0d);
    }

    public static Layout compute(int sessionCount, int availableWidth, int availableHeight,
                                 int gap, double cardAspect) {
        return compute(sessionCount, availableWidth, availableHeight, gap, gap, cardAspect);
    }

    public static Layout compute(int sessionCount, int availableWidth, int availableHeight,
                                 int gap, int outerGap, double cardAspect) {
        final int safeWidth = Math.max(0, availableWidth);
        final int safeHeight = Math.max(0, availableHeight);
        final int effectiveGap = Math.max(1, gap);
        final int effectiveOuter = Math.max(0, outerGap);
        final int columns = COLUMNS;
        if (sessionCount <= 0 || safeWidth <= 0 || safeHeight <= 0) {
            return buildLayout(sessionCount, columns, effectiveGap, effectiveOuter,
                    safeWidth, safeHeight, 0, 0);
        }
        final double aspect = (cardAspect > 0
                && !Double.isNaN(cardAspect) && !Double.isInfinite(cardAspect))
                ? cardAspect
                : (double) safeWidth / safeHeight;
        final int totalGapX = effectiveGap * (columns + 1);
        final int totalGapYVisible =
                effectiveGap * (VISIBLE_ROWS - 1) + effectiveOuter * 2;
        if (safeWidth <= totalGapX || safeHeight <= totalGapYVisible) {
            return buildLayout(sessionCount, columns, effectiveGap, effectiveOuter,
                    safeWidth, safeHeight, 0, 0);
        }
        final double maxCardWidthByWidth = (safeWidth - totalGapX) / (double) columns;
        final double maxCardWidthByHeight =
                ((safeHeight - totalGapYVisible) / (double) VISIBLE_ROWS) * aspect;
        int cardWidth = Math.max(0,
                (int) Math.floor(Math.min(maxCardWidthByWidth, maxCardWidthByHeight)));
        int cardHeight = (int) Math.floor(cardWidth / aspect);
        if (cardWidth > 0 && cardHeight == 0) {
            cardHeight = 1;
            cardWidth = Math.min(cardWidth, Math.max(1, (int) Math.floor(aspect)));
        }
        if (safeWidth - columns * cardWidth < totalGapX) {
            cardWidth = Math.max(1, (safeWidth - totalGapX) / columns);
            cardHeight = Math.max(1, (int) Math.floor(cardWidth / aspect));
        }
        return buildLayout(sessionCount, columns, effectiveGap, effectiveOuter,
                safeWidth, safeHeight, cardWidth, cardHeight);
    }
}
