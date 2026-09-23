package com.termux.app.terminal;

/**
 * Pure geometry for the directory-picker overlay: turns the trailing placeholder page into a
 * vertical "open a new session in this directory" menu. No Android types, no state — a single
 * static function over numbers, so it is unit-testable on the JVM.
 *
 * <p>The dragging finger latches a vertical anchor {@code yA}: nothing is drawn at {@code yA}
 * itself, the directory list opens <em>upwards</em> ending one gap above the finger, and the
 * "+ new session" hint sits above the list. There is deliberately no downward placement — the
 * menu lives in the space the finger is not covering, and the newest entry always sits nearest
 * the finger (see {@link Result#itemIndexAt(int)}), which makes the gesture predictable.
 *
 * <p>{@code rowHeight} is constant; only the entry count adapts:
 * {@code rows = min(n, floor(availUp / rowHeight))}. Entries are newest-first, so a tight
 * placement drops the oldest. When not even one row fits, the list is dropped entirely
 * ({@link Mode#NONE}) and the hint falls back to the historic centred position — nothing is
 * clamped into a position it does not fit.
 */
public final class DirectoryPickerLayout {

    /** Which of the two states the page is in. */
    public enum Mode {
        /** No list to show — the "+ new session" hint keeps its historic centred position. */
        NONE,
        /** The list sits entirely above the finger, ending at {@code anchorY - gap}. */
        UP
    }

    /** The computed placement. All coordinates are page-local pixels, y growing downwards. */
    public static final class Result {
        public final Mode mode;
        /** Height of one row, in px — the constant that was passed in. 0 when NONE. */
        public final float rowHeight;
        /** How many entries to show — possibly fewer than the caller offered. 0 when NONE. */
        public final int rows;
        /** Top edge of the first row. 0 when NONE. */
        public final float listTop;
        /** Bottom edge of the last row. 0 when NONE. */
        public final float listBottom;
        /** Top edge of the "+ new session" hint block. */
        public final float hintTop;
        /** Height of the hint block, in px. */
        public final float hintHeight;

        Result(Mode mode, float rowHeight, int rows, float listTop, float listBottom,
               float hintTop, float hintHeight) {
            this.mode = mode;
            this.rowHeight = rowHeight;
            this.rows = rows;
            this.listTop = listTop;
            this.listBottom = listBottom;
            this.hintTop = hintTop;
            this.hintHeight = hintHeight;
        }

        /** @return true when a directory list is actually drawn. */
        public boolean hasList() {
            return mode == Mode.UP && rows > 0;
        }

        /**
         * The row containing {@code pageY}, or -1 when not on the list (anchor zone, gap,
         * hint band, empty space). Lives here so drawing and hit-testing share the same
         * {@link #listTop}/{@link #rowHeight}; truncated entries lie outside {@link #rows}.
         */
        public int indexAt(float pageY) {
            if (!hasList() || rowHeight <= 0f) return -1;
            final float relative = pageY - listTop;
            if (relative < 0f) return -1;
            final int index = (int) (relative / rowHeight);
            return (index >= 0 && index < rows) ? index : -1;
        }

        /**
         * Which offered entry is drawn on {@code row}: the list is newest-first but drawn
         * upward, so the row nearest the finger (last) holds the newest entry — order is
         * reversed relative to the data. Painter and hit-test both go through here.
         *
         * @return index into the item list, or -1 when {@code row} is not a row of this list.
         */
        public int itemIndexAt(int row) {
            if (row < 0 || row >= rows) return -1;
            return rows - 1 - row;
        }
    }

    private DirectoryPickerLayout() { }

    /**
     * Lay the picker out on a page of {@code pageHeight} pixels for an anchor at {@code anchorY}.
     *
     * @param pageHeight  height of the placeholder page (= the pager's height), px.
     * @param pad         safe inset from the top and bottom edges, px.
     * @param gap         vertical gap between the finger and the list, and between the hint and the
     *                    list, px.
     * @param hintPad     padding kept above and below the hint inside its band, px.
     * @param anchorY     the latched finger position, page-local px.
     * @param itemCount   how many directories the caller can offer (0 → nothing to lay out).
     * @param hintHeight  measured height of the "+ new session" block, px.
     * @param rowHeight   fixed height of one row, px — never scaled by this function.
     */
    public static Result compute(float pageHeight, float pad, float gap, float hintPad,
                                 float anchorY, int itemCount, float hintHeight,
                                 float rowHeight) {
        // The plain placeholder's historic position — also the fallback whenever no list is shown.
        final float centred = (pageHeight - hintHeight) / 2f;
        if (itemCount <= 0 || pageHeight <= 0f || rowHeight <= 0f) {
            return new Result(Mode.NONE, 0f, 0, 0f, 0f, centred, hintHeight);
        }

        final float hintBlock = hintHeight + 2f * hintPad;
        // Space above the finger, already net of the hint's band and of both gaps.
        final float availUp = (anchorY - gap) - pad - gap - hintBlock;
        final int rows = Math.min(itemCount, (int) (availUp / rowHeight));
        if (rows < 1) {
            // No room above the finger: fall back to the plain centred placeholder (see class doc).
            return new Result(Mode.NONE, 0f, 0, 0f, 0f, centred, hintHeight);
        }

        final float listBottom = anchorY - gap;
        final float listTop = listBottom - rows * rowHeight;
        // The reservation in availUp guarantees the band above the list can hold the hint, so this is
        // always a genuine centred placement, never a clamped one.
        final float band = listTop - pad;
        final float hintTop = pad + (band - hintHeight) / 2f;
        return new Result(Mode.UP, rowHeight, rows, listTop, listBottom, hintTop, hintHeight);
    }
}
