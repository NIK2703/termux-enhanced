package com.termux.app.terminal;

/**
 * Pure geometry for the directory-picker overlay that turns the trailing placeholder page into a
 * vertical "open a new session in this directory" menu.
 *
 * <p>No Android types, no state — a single static function over numbers, so the whole placement
 * model is unit-testable on the JVM.
 *
 * <h2>The model</h2>
 *
 * <p>The finger that drags the placeholder page in fixes a vertical anchor {@code yA} the moment the
 * page appears, and the menu is laid out around it:
 * <ul>
 *   <li><b>nothing</b> is drawn at {@code yA} itself — it is the neutral zone;</li>
 *   <li>the <b>directory list</b> always opens <em>upwards</em>, ending one gap above the finger;</li>
 *   <li>the <b>"+ new session" hint</b> is always above the list, centred in the band between the
 *       list and the top edge.</li>
 * </ul>
 *
 * <p>There is deliberately no downward placement. The finger is the thing doing the pointing, so the
 * menu lives in the space the finger is <em>not</em> covering; and with the list always on the same
 * side, the newest entry is always the one nearest the finger (see
 * {@link Result#itemIndexAt(int)}), which makes the gesture predictable.
 *
 * <h2>Fixed row height, adaptive entry count</h2>
 *
 * <p>{@code rowHeight} is a <em>constant</em> — rows never stretch to fill the space and never
 * compress to make more fit. The only thing that adapts is how many entries are offered:
 *
 * <pre>    rows = min(n, floor(availUp / rowHeight))</pre>
 *
 * <p>A row is therefore always exactly the same size and the list never leaves the page; a tight
 * placement simply shows fewer (newer) directories. Because the entries are ordered newest first,
 * cutting the tail drops the <em>oldest</em> ones.
 *
 * <h2>Zero entries is a valid outcome</h2>
 *
 * <p>When the finger sits so high that not even one row fits above it, the list is dropped entirely
 * — {@link Mode#NONE}, {@code rows == 0} — and the hint falls back to the centre of the page, which
 * is exactly where the plain placeholder has always drawn it. So the page degrades into the familiar
 * "+ new session" screen rather than into a cramped or overflowing list. Nothing is ever clamped
 * into a position it does not fit.
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
         * The row containing {@code pageY}.
         *
         * <p>Lives here, next to the placement, so the drawing and the hit-testing can never drift
         * apart: both read the same {@link #listTop} and {@link #rowHeight}. Entries dropped by the
         * truncation are outside {@link #rows} and therefore never selectable.
         *
         * @return the row index, or -1 when the position is not on the list — the neutral zone at
         *         the anchor, the gap below it, the hint band, and the empty space above all land
         *         here.
         */
        public int indexAt(float pageY) {
            if (!hasList() || rowHeight <= 0f) return -1;
            final float relative = pageY - listTop;
            if (relative < 0f) return -1;
            final int index = (int) (relative / rowHeight);
            return (index >= 0 && index < rows) ? index : -1;
        }

        /**
         * Which entry of the offered list is drawn on the row at {@code row}.
         *
         * <p>The list arrives ordered newest-first, but it is drawn above the finger, so the row
         * <em>nearest</em> the finger is the last one. The newest entry is put there: it is the one
         * the finger is already pointing at when the menu appears and the one a short drag reaches,
         * so the order is reversed relative to the data.
         *
         * <p>Both the painter and the hit-test go through here, so what is highlighted and what is
         * selected can never disagree about which entry a row holds.
         *
         * @return the index into the item list, or -1 when {@code row} is not a row of this list.
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
            // Not even one entry fits above the finger. Rather than shrink a row, push the list off
            // the page or flip it below the finger, the list is simply not shown and the page falls
            // back to the plain centred placeholder.
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
