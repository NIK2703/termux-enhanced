package com.termux.terminal;

import junit.framework.AssertionFailedError;

/**
 * Shadow-canvas regression tests for the glyph-scroll repaint optimizations (E1/E2/E3/F2 in
 * glyph-scroll-optimization-2.md, F0-F7/G1-G7 in the later audits).
 *
 * <h2>What is being tested</h2>
 *
 * The optimizations replaced "every output chunk forces a full frame" with a sparse, content-based
 * decision: {@code TerminalBuffer} keeps one dirty bit per ring row, and
 * {@code TerminalView.repaintAfterUpdate()} decides between a full repaint and a partial one from
 * (a) the <em>content anchor</em> (the internal ring row under the top visible line) and (b) the
 * dirty rows that are currently visible. {@code TerminalRenderer.render()} then skips any row that
 * is neither dirty nor inside the damage rectangle.
 *
 * <p>That is exactly the machinery that can go wrong in the two situations this suite pins down:
 * <ul>
 *   <li>the view is scrolled up N lines from the live bottom (so {@code mTopRow &lt; 0} and output
 *       arriving at the bottom is compensated away by the follow-text shift);</li>
 *   <li>a program changes data at the <em>top</em> of its screen (status line, progress bar,
 *       {@code tput cup 0 0} rewrite) without scrolling anything.</li>
 * </ul>
 *
 * <h2>Method: a shadow canvas</h2>
 *
 * A unit test cannot instantiate {@code TerminalView} (it is an Android {@code View}), so the view
 * half of the pipeline is mirrored here — the same technique {@link ScrollFollowTextTest} and
 * {@link DirtyStateTest} already use. What makes this suite stronger than a pure state assertion is
 * that it keeps a <b>shadow canvas</b>: a per-visible-slot copy of the pixels that the renderer has
 * actually drawn. After every simulated frame the canvas is compared against the true content of
 * the buffer. A row whose content changed but was never repainted shows up as a stale canvas slot —
 * i.e. exactly the user-visible bug ("the terminal did not update").
 *
 * <p>Mirrored from {@code TerminalView.onScreenUpdated()}, {@code repaintAfterUpdate()},
 * {@code invalidateScrollbarBand()}, {@code invalidateRowRange()} and
 * {@code TerminalRenderer.render()}. The scrollbar is modelled in row units; its real thumb has a
 * fixed pixel height, so the exact row count is a modelling choice and does not change any
 * assertion. The cursor is pinned visible (a single frame has no blink timer).</p>
 */
public class ScrolledUpdateRepaintTest extends TerminalTestCase {

    private static final int COLS = 20;
    private static final int ROWS = 10;
    /**
     * Total buffer rows. Must be >= TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MIN (100) or the
     * constructor silently substitutes the 2000-row default — and then "the transcript is full"
     * tests would need 1991 lines to get there.
     */
    private static final int TOTAL_ROWS = 100;
    private static final int MAX_TRANSCRIPT = TOTAL_ROWS - ROWS;
    private static final int NO_THUMB = Integer.MIN_VALUE;
    /** Fixed thumb height in row units (the real thumb has a fixed pixel height). */
    private static final int THUMB_ROWS = 3;

    // ── Mirror of the TerminalView fields that drive the repaint decision ────────────────────
    private int mTopRow;
    private int mLastAnchorRow = Integer.MIN_VALUE;
    private int mLastCursorRow = Integer.MIN_VALUE;
    private int mLastCursorCol = -1;
    private boolean mPixelsValid = false;
    private boolean mAutoScrollDisabled = false;
    private boolean mSelecting = false;
    private boolean mScrollbarDragging = false;
    private boolean mFlingActive = false;
    private int mLastThumbTop = NO_THUMB;
    private int mLastThumbBottom = NO_THUMB;

    // ── The shadow canvas and the damage of the frame currently being simulated ──────────────
    /** Drawn pixels per visible slot; slot 0 is the top visible line. {@code null} = never drawn. */
    private String[] mCanvas;
    private boolean mDamageFull;
    private int mDamageRowTop = Integer.MAX_VALUE;
    private int mDamageRowBottom = Integer.MIN_VALUE;
    /** Rows damaged by the scrollbar band in the current frame, if it damaged anything at all. */
    private boolean mScrollbarDamaged;
    private int mScrollbarDamageTop;
    private int mScrollbarDamageBottom;
    /** External rows the renderer drew in the last frame (the E2 skip is what makes this small). */
    private final java.util.TreeSet<Integer> mDrawnRows = new java.util.TreeSet<>();
    private int mFullFrames;
    private int mPartialFrames;
    /** Test-harness self-check: when true the partial path damages only the first dirty row. */
    private boolean mSimulateMissedRepaint = false;
    /** Diagnostic snapshot of the last partial-path decision. */
    private String mDiag = "";

    // ─────────────────────────────────────────────────────────────────────────────────────────

    private void newTerminal() {
        mTerminal = new TerminalEmulator(mOutput, COLS, ROWS, INITIAL_CELL_WIDTH_PIXELS,
            INITIAL_CELL_HEIGHT_PIXELS, TOTAL_ROWS, null);
        mCanvas = new String[ROWS];
        mTopRow = 0;
        mLastAnchorRow = Integer.MIN_VALUE;
        mLastCursorRow = Integer.MIN_VALUE;
        mLastCursorCol = -1;
        mPixelsValid = false;
        mAutoScrollDisabled = false;
        mSelecting = false;
        mScrollbarDragging = false;
        mFlingActive = false;
        mLastThumbTop = NO_THUMB;
        mLastThumbBottom = NO_THUMB;
        mDrawnRows.clear();
        mFullFrames = 0;
        mPartialFrames = 0;
        mSimulateMissedRepaint = false;
    }

    private void output(String s) {
        enterString(s);
    }

    /** Write {@code n} numbered lines so every row has identifiable content. */
    private void fillLines(String prefix, int from, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < from + count; i++) sb.append(prefix).append(i).append("\r\n");
        output(sb.toString());
    }

    // ── User scrolling: mirror of the scrollUp()/doScroll() path ─────────────────────────────
    // mTopRow is set, follow-to-bottom is armed/disarmed, and invalidate() is called — but
    // mLastAnchorRow is deliberately NOT refreshed, exactly as in the real view, so the following
    // onScreenUpdated() sees a changed anchor and does one more full frame.
    private void scrollTo(int topRow) {
        int min = -mTerminal.getScreen().getActiveTranscriptRows();
        mTopRow = Math.max(min, Math.min(0, topRow));
        mAutoScrollDisabled = (mTopRow != 0);
        fullRepaintFrame();
    }

    // ── The pixel signature of a row as the renderer would draw it ───────────────────────────
    private String pixelsOf(int externalRow) {
        TerminalBuffer screen = mTerminal.getScreen();
        TerminalRow line = screen.getLineOrBlank(externalRow);
        StringBuilder sb = new StringBuilder(COLS * 12);
        for (int c = 0; c < COLS; c++) {
            sb.append(line.mText[c]).append('~').append(Long.toHexString(line.mStyle[c])).append(' ');
        }
        if (externalRow == mTerminal.getCursorRow()) {
            sb.append("|cursor@").append(mTerminal.getCursorCol());
        }
        return sb.toString();
    }

    private void drawRow(int externalRow) {
        mCanvas[externalRow - mTopRow] = pixelsOf(externalRow);
        mDrawnRows.add(externalRow);
    }

    private void damageRows(int topExternal, int bottomExternal) {
        if (topExternal < mDamageRowTop) mDamageRowTop = topExternal;
        if (bottomExternal > mDamageRowBottom) mDamageRowBottom = bottomExternal;
    }

    private void resetDamage() {
        mDamageFull = false;
        mDamageRowTop = Integer.MAX_VALUE;
        mDamageRowBottom = Integer.MIN_VALUE;
        mScrollbarDamaged = false;
        mScrollbarDamageTop = 0;
        mScrollbarDamageBottom = 0;
    }

    // ── Mirror of TerminalView.invalidateRowRange(first, last, markRows=false) ───────────────
    private void invalidateRowRange(int first, int last) {
        damageRows(first, last);
    }

    // ── Mirror of TerminalView.invalidateScrollbarBand() ─────────────────────────────────────
    private int[] thumbSlots() {
        int range = mTerminal.getScreen().getActiveTranscriptRows();
        if (range <= 0) return null;
        float scrollFraction = (range + mTopRow) / (float) range; // 1 at bottom, 0 at top
        float maxOffset = ROWS - THUMB_ROWS;
        float top = scrollFraction * maxOffset;
        int t = (int) Math.floor(top);
        int b = (int) Math.ceil(top + THUMB_ROWS);
        if (t < 0) t = 0;
        if (b > ROWS) b = ROWS;
        if (t >= b) return null;
        return new int[]{t, b};
    }

    private void invalidateScrollbarBand() {
        if (mTerminal.getScreen().getActiveTranscriptRows() <= 0) return;
        int[] now = thumbSlots();
        if (now == null) return;
        int top = now[0], bottom = now[1];
        if (mLastThumbTop != NO_THUMB) {
            if (mLastThumbTop == top && mLastThumbBottom == bottom) return;
            top = Math.min(top, mLastThumbTop);
            bottom = Math.max(bottom, mLastThumbBottom);
        } else {
            top = 0;
            bottom = ROWS;
        }
        if (top >= bottom) return;
        // markRowsIntersectingDirty(top, bottom)
        final TerminalBuffer screen = mTerminal.getScreen();
        int first = mTopRow + top;
        int last = mTopRow + bottom;
        int minRow = Math.max(mTopRow, -screen.getActiveTranscriptRows());
        int maxRow = mTopRow + ROWS - 1;
        if (first < minRow) first = minRow;
        if (last > maxRow) last = maxRow;
        for (int row = first; row <= last; row++) screen.markRowDirty(row);
        damageRows(mTopRow + top, mTopRow + bottom - 1);
        mScrollbarDamaged = true;
        mScrollbarDamageTop = mTopRow + top;
        mScrollbarDamageBottom = mTopRow + bottom - 1;
    }

    // ── A frame that is a full repaint with no emulator update (user scroll, attach, resize) ──
    private void fullRepaintFrame() {
        resetDamage();
        mDamageFull = true;
        render();
    }

    // ── Mirror of TerminalView.onScreenUpdated(autoScrollDisabled) + repaintAfterUpdate() ────
    private void frame() {
        final TerminalBuffer screen = mTerminal.getScreen();
        resetDamage();

        // ---- onScreenUpdated(skipScrolling) ----
        int rowsInHistory = screen.getActiveTranscriptRows();
        if (mTopRow < -rowsInHistory) mTopRow = -rowsInHistory;

        boolean skipScrolling = false;
        if (mTopRow == 0) {
            skipScrolling = true;
        } else if (mScrollbarDragging) {
            skipScrolling = true;
            // The finger position is not modelled; the drag tests drive mTopRow directly.
        } else if (mFlingActive) {
            skipScrolling = true;
        } else if (mSelecting || mAutoScrollDisabled) {
            int rowShift = mTerminal.getScrollCounter();
            if (-mTopRow + rowShift > rowsInHistory) {
                if (mSelecting) mSelecting = false; // stopTextSelectionMode()
                if (mAutoScrollDisabled) {
                    mTopRow = -rowsInHistory;
                    skipScrolling = true;
                }
            } else {
                skipScrolling = true;
                mTopRow -= rowShift;
            }
        }
        if (!skipScrolling && mTopRow != 0) mTopRow = 0;
        mTerminal.clearScrollCounter();

        // ---- repaintAfterUpdate() ----
        int cursorExtRow = mTerminal.getCursorRow();
        int cursorCol = mTerminal.getCursorCol();
        boolean cursorMoved = (cursorExtRow != mLastCursorRow) || (cursorCol != mLastCursorCol);
        int prevCursorExtRow = mLastCursorRow;
        mLastCursorRow = cursorExtRow;
        mLastCursorCol = cursorCol;

        final int activeTranscript = screen.getActiveTranscriptRows();
        boolean anchorChanged;
        if (mTopRow < -activeTranscript) {
            anchorChanged = true;
        } else {
            final int anchor = screen.externalToInternalRow(Math.max(mTopRow, -activeTranscript));
            anchorChanged = (anchor != mLastAnchorRow);
            mLastAnchorRow = anchor;
        }

        if (anchorChanged || mSelecting || mScrollbarDragging || screen.isAllDirty()) {
            mDamageFull = true;
            render();
            return;
        }

        if (cursorMoved) {
            if (prevCursorExtRow != Integer.MIN_VALUE) screen.markRowDirty(prevCursorExtRow);
            screen.markRowDirty(cursorExtRow);
        }

        int visTop = Math.max(mTopRow, -activeTranscript);
        int visBottom = Math.min(mTopRow + mTerminal.mRows - 1, mTerminal.mRows - 1);
        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
        StringBuilder dirtySeen = new StringBuilder();
        StringBuilder allDirty = new StringBuilder();
        for (int row = -activeTranscript; row < mTerminal.mRows; row++) {
            if (screen.isRowDirty(row)) allDirty.append(row).append(' ');
            if (row >= visTop && row <= visBottom && screen.isRowDirty(row)) {
                if (row < first) first = row;
                last = row;
                dirtySeen.append(row).append(' ');
            }
        }
        mDiag = "mTopRow=" + mTopRow + " vis=[" + visTop + "," + visBottom + "] dirtyVisible=["
            + dirtySeen.toString().trim() + "] first=" + first + " last=" + last
            + " scrFirstInt=" + screen.externalToInternalRow(0)
            + " int(1)=" + screen.externalToInternalRow(1)
            + " ALLDIRTY=[" + allDirty.toString().trim() + "]";

        if (first > last) {
            // Nothing visible changed (the ordinary "scrolled up, output keeps arriving" case).
            invalidateScrollbarBand();
            render();
            return;
        }
        invalidateScrollbarBand();
        if (mSimulateMissedRepaint) {
            invalidateRowRange(first, first);
        } else {
            invalidateRowRange(first, last);
        }
        render();
    }

    // ── Mirror of TerminalView.onDraw() + TerminalRenderer.render() ──────────────────────────
    private void render() {
        final TerminalBuffer screen = mTerminal.getScreen();
        mDrawnRows.clear();

        int visTop = Math.max(mTopRow, -screen.getActiveTranscriptRows());
        int visBottom = Math.min(mTopRow + ROWS - 1, ROWS - 1);

        if (mDamageFull) {
            for (int row = visTop; row <= visBottom; row++) drawRow(row);
            mPixelsValid = true;
            screen.clearDirtyState();
            mFullFrames++;
        } else if (!mPixelsValid) {
            // The view passes dirtyRect = null: every visible row is drawn, and the dirty set is
            // deliberately NOT cleared (the surface outside the clip is still unknown).
            for (int row = visTop; row <= visBottom; row++) drawRow(row);
            mPartialFrames++;
        } else {
            int renderStart = Math.max(mDamageRowTop, visTop);
            int renderEnd = Math.min(mDamageRowBottom, visBottom);
            boolean anyDirty = false;
            for (int row = renderStart; row <= renderEnd; row++) {
                if (screen.isRowDirty(row)) {
                    anyDirty = true;
                    break;
                }
            }
            boolean forceDraw = !anyDirty;
            for (int row = renderStart; row <= renderEnd; row++) {
                if (forceDraw || screen.isRowDirty(row)) {
                    drawRow(row);
                    screen.clearRowDirty(row);
                }
            }
            mPartialFrames++;
        }

        // drawScrollbar() records the thumb extent that is now on the canvas.
        int[] thumb = mTerminal.getScreen().getActiveTranscriptRows() <= 0 ? null : thumbSlots();
        if (thumb == null) {
            mLastThumbTop = NO_THUMB;
            mLastThumbBottom = NO_THUMB;
        } else {
            mLastThumbTop = thumb[0];
            mLastThumbBottom = thumb[1];
        }
    }

    // ── The oracle ───────────────────────────────────────────────────────────────────────────
    /** Fails if any visible row on the canvas does not match the buffer's current content. */
    private void assertCanvasMatchesScreen(String msg) {
        for (int slot = 0; slot < ROWS; slot++) {
            int externalRow = mTopRow + slot;
            String expected = pixelsOf(externalRow);
            if (!expected.equals(mCanvas[slot])) {
                fail(msg + ": visible row " + slot + " (external " + externalRow + ") is STALE —"
                    + "\n  on screen: " + describe(mCanvas[slot])
                    + "\n  in buffer: " + describe(expected)
                    + "\n  mTopRow=" + mTopRow + " activeTranscript="
                    + mTerminal.getScreen().getActiveTranscriptRows());
            }
        }
    }

    private static String describe(String pixels) {
        StringBuilder sb = new StringBuilder();
        for (String cell : pixels.split(" ")) {
            if (cell.isEmpty()) continue;
            int tilde = cell.indexOf('~');
            if (tilde > 0) sb.append(cell, 0, tilde);
        }
        return sb.toString();
    }

    /** The visible text, top to bottom, as the user would read it. */
    private String[] visibleText() {
        String[] out = new String[ROWS];
        for (int slot = 0; slot < ROWS; slot++) {
            out[slot] = describe(pixelsOf(mTopRow + slot));
        }
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // Scenario 1 — the script changes data at the TOP, at the live bottom.
    // ═════════════════════════════════════════════════════════════════════════════════════════

    public void testTopLineRewriteAtLiveBottomIsRepainted() {
        newTerminal();
        fillLines("row-", 0, ROWS);
        frame(); // first frame: full repaint
        assertCanvasMatchesScreen("baseline");

        // A status line: move to the top-left corner and overwrite row 0 in place.
        output("\033[HSTATUS-1   \r");
        frame();

        assertCanvasMatchesScreen("after top-line rewrite");
        assertTrue("row 0 must show the new status line, was '" + visibleText()[0] + "'",
            visibleText()[0].startsWith("STATUS-1"));
        assertEquals("mTopRow must not move", 0, mTopRow);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // Scenario 2 — the terminal is scrolled up N lines and the script changes data at the top.
    // ═════════════════════════════════════════════════════════════════════════════════════════

    public void testTopLineRewriteWhileScrolledUpIsRepainted() {
        newTerminal();
        fillLines("row-", 0, 30); // 10 screen rows + 20 transcript rows
        frame();
        scrollTo(-3);
        frame();
        assertCanvasMatchesScreen("after scrolling up");

        String[] before = visibleText();

        // The program rewrites its own first screen row. While scrolled up by 3, screen row 0 is
        // the 4th visible line (slot 3).
        output("\033[HTOP-CHANGED \r");
        frame();

        assertCanvasMatchesScreen("after top-line rewrite while scrolled up");
        String[] after = visibleText();
        assertTrue("visible slot 3 (screen row 0) must show the new text, was '" + after[3] + "'",
            after[3].startsWith("TOP-CHANGED"));
        assertEquals("follow-text must keep the viewport in place", -3, mTopRow);
        for (int slot = 0; slot < ROWS; slot++) {
            if (slot == 3) continue;
            assertEquals("slot " + slot + " must not change", before[slot], after[slot]);
        }
    }

    /**
     * The clamp at the top of history: the transcript is full, the view sits on the oldest line,
     * and new output evicts that line. The visible text therefore *does* change, so a repaint is
     * mandatory even though the view is scrolled away from the bottom — this is the case where the
     * anchor comparison must not be fooled by the follow-text compensation.
     */
    public void testScrollClampAtTopOfHistoryRepaints() {
        newTerminal();
        fillLines("row-", 0, TOTAL_ROWS + 1); // overfill so the transcript is certainly full
        frame();
        assertEquals("transcript must be full", MAX_TRANSCRIPT,
            mTerminal.getScreen().getActiveTranscriptRows());

        scrollTo(-MAX_TRANSCRIPT);
        frame();
        assertCanvasMatchesScreen("at the top of a full transcript");

        String firstBefore = visibleText()[0];

        output("evict-me\r\n");
        frame();

        assertCanvasMatchesScreen("after the oldest line was evicted");
        assertEquals("the view must stay pinned to the top of history",
            -MAX_TRANSCRIPT, mTopRow);
        assertFalse("the oldest line was evicted, so the top visible line must change",
            firstBefore.equals(visibleText()[0]));
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // Scenario 3 — scrolled up while output keeps arriving at the (off-screen) bottom.
    // ═════════════════════════════════════════════════════════════════════════════════════════

    public void testStreamingOutputWhileScrolledUpKeepsVisibleTextAndCanvasConsistent() {
        newTerminal();
        fillLines("row-", 0, 30);
        frame();
        scrollTo(-4);
        frame();
        assertCanvasMatchesScreen("after scrolling up");

        String[] before = visibleText();

        for (int chunk = 0; chunk < 12; chunk++) {
            output("stream-" + chunk + "\r\n");
            frame();
            // The visible content must not move: follow-text compensates the ring shift.
            assertCanvasMatchesScreen("streaming chunk " + chunk);
            String[] now = visibleText();
            for (int slot = 0; slot < ROWS; slot++) {
                assertEquals("chunk " + chunk + ": visible slot " + slot + " must not move",
                    before[slot], now[slot]);
            }
            assertEquals("mTopRow must track the transcript, not the bottom", -4 - chunk - 1, mTopRow);

            // The optimization's whole point: nothing visible changed, so the repaint must stay
            // local to the scrollbar thumb — an overlay whose rows have to be re-rendered so the
            // glyphs underneath it come back (F2/E3). The newly revealed bottom row is dirty but
            // off-screen and must stay untouched until the user scrolls back down.
            //
            // The bound is THUMB_ROWS + 3 rather than the exact thumb band because
            // markRowsIntersectingDirty() marks one row past the end of the damage rectangle it
            // accompanies (mark range [mTopRow+top, mTopRow+bottom] vs rect rows [.., bottom-1]).
            // That extra row stays dirty, is not drawn (it is outside the clip), and is then drawn
            // on some later frame — legitimate deferred scrollbar damage, not a content repaint.
            // A regression to a full-frame repaint would draw ROWS rows and fail here.
            assertTrue("chunk " + chunk + ": the repaint must stay local to the scrollbar, but rows "
                + mDrawnRows + " were drawn [" + mDiag + "]",
                mDrawnRows.size() <= THUMB_ROWS + 3);
            for (int row : mDrawnRows) {
                assertTrue("chunk " + chunk + ": row " + row + " is far from the scrollbar band ["
                        + mScrollbarDamageTop + "," + mScrollbarDamageBottom + "] [" + mDiag + "]",
                    Math.abs(row - mScrollbarDamageBottom) <= THUMB_ROWS + 3
                        || Math.abs(row - mScrollbarDamageTop) <= THUMB_ROWS + 3);
            }
        }
    }

    /** New output must still be there when the user scrolls back down. */
    public void testScrollingBackToBottomShowsTheNewOutput() {
        newTerminal();
        fillLines("row-", 0, 30);
        frame();
        scrollTo(-4);
        frame();

        output("brand-new-line\r\n");
        frame();
        assertCanvasMatchesScreen("while scrolled up");

        scrollTo(0);
        frame();
        assertCanvasMatchesScreen("back at the bottom");

        boolean found = false;
        for (String row : visibleText()) if (row.startsWith("brand-new-line")) found = true;
        assertTrue("the newest line must be visible after scrolling back down", found);
    }

    /** A row that was written while off-screen must be repainted when it scrolls into view. */
    public void testOffscreenDirtyRowIsRepaintedWhenItScrollsIntoView() {
        newTerminal();
        fillLines("row-", 0, 30);
        frame();
        scrollTo(-4);
        frame();
        assertCanvasMatchesScreen("scrolled up");

        // The program writes to the bottom screen row, which is below the visible band while the
        // view is scrolled up by 4.
        output("\033[" + ROWS + ";1HBOTTOM-MARK\r");
        frame();
        assertCanvasMatchesScreen("bottom row written off-screen");

        // Scroll back down so the row becomes visible.
        scrollTo(0);
        frame();
        assertCanvasMatchesScreen("bottom row revealed");

        assertTrue("the off-screen write must appear once revealed, was '" + visibleText()[ROWS - 1] + "'",
            visibleText()[ROWS - 1].startsWith("BOTTOM-MARK"));
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // Scenario 4 — the sparse dirty set (E2) and non-content damage.
    // ═════════════════════════════════════════════════════════════════════════════════════════

    public void testSparseDirtySetRepaintsOnlyTheTouchedRows() {
        newTerminal();
        fillLines("row-", 0, 30);
        frame();
        scrollTo(0);
        frame();

        // Touch the first and the last screen row only — the shape every full-screen TUI has.
        output("\033[1;1HT\033[" + ROWS + ";1HB\033[1;1H");
        frame();

        assertCanvasMatchesScreen("sparse update");
        assertTrue("row 0 must be repainted", mDrawnRows.contains(0));
        assertTrue("row " + (ROWS - 1) + " must be repainted", mDrawnRows.contains(ROWS - 1));
        for (int row = 1; row < ROWS - 1; row++) {
            assertFalse("row " + row + " must NOT be repainted (nothing in it changed)",
                mDrawnRows.contains(row));
        }
    }

    public void testStyleOnlyChangeOnAVisibleRowIsRepainted() {
        newTerminal();
        fillLines("row-", 0, 30);
        frame();
        scrollTo(-3);
        frame();
        assertCanvasMatchesScreen("scrolled up");

        // Reverse video on the top screen row (visible as slot 3) — no character changes at all.
        output("\033[H\033[7m" + "row-20" + "\033[0m");
        frame();

        assertCanvasMatchesScreen("style-only change while scrolled up");
    }

    public void testClearScreenWhileScrolledUpRepaintsTheVisibleScreenRows() {
        newTerminal();
        fillLines("row-", 0, 30);
        frame();
        scrollTo(-3);
        frame();
        assertCanvasMatchesScreen("scrolled up");

        String[] before = visibleText();
        output("\033[2J");
        frame();

        assertCanvasMatchesScreen("after clear");
        String[] after = visibleText();
        // Slots 0..2 are history rows: the program cannot reach them, so they must survive.
        for (int slot = 0; slot < 3; slot++) {
            assertEquals("history slot " + slot + " must survive a clear", before[slot], after[slot]);
        }
        for (int slot = 3; slot < ROWS; slot++) {
            assertTrue("screen slot " + slot + " must be blank after clear, was '" + after[slot] + "'",
                after[slot].trim().isEmpty());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // Scenario 5 — the hard combination: scrolled up + top rewrite + streaming output.
    // ═════════════════════════════════════════════════════════════════════════════════════════

    public void testTopRewriteAndStreamingOutputWhileScrolledUp() {
        newTerminal();
        fillLines("row-", 0, 30);
        frame();
        scrollTo(-3);
        frame();
        assertCanvasMatchesScreen("scrolled up");

        for (int tick = 0; tick < 5; tick++) {
            // One new line of output at the bottom (which scrolls the screen) ...
            output("stream-" + tick + "\r\n");
            // ... plus, in the same frame, a counter rewritten in place on the top screen row.
            output("\033[HSTATUS-" + tick + "  \r");
            output("\033[" + ROWS + ";1H");
            frame();
            assertCanvasMatchesScreen("tick " + tick);

            // Screen row 0 is visible as long as the view is scrolled up by fewer than ROWS lines
            // (follow-text keeps the viewport pinned while the program's screen slides past it).
            int statusSlot = -mTopRow;
            assertTrue("tick " + tick + ": screen row 0 must still be visible (slot " + statusSlot + ")",
                statusSlot >= 0 && statusSlot < ROWS);
            String top = visibleText()[statusSlot];
            assertTrue("tick " + tick + ": the status line must be current, was '" + top + "'",
                top.startsWith("STATUS-" + tick));
        }

        // And the newest streamed line must be present once scrolled back down.
        scrollTo(0);
        frame();
        assertCanvasMatchesScreen("back at the bottom after the combined run");
        boolean found = false;
        for (String row : visibleText()) if (row.startsWith("stream-4")) found = true;
        assertTrue("the last streamed line must be visible after scrolling back down", found);
    }

    /** A partial scroll region (htop/vi) forces a full repaint — the rows physically move. */
    public void testScrollRegionWhileScrolledUpIsRepainted() {
        newTerminal();
        fillLines("row-", 0, 30);
        frame();
        scrollTo(-3);
        frame();
        assertCanvasMatchesScreen("scrolled up");

        // Set a scroll region on the top 4 screen rows and scroll it.
        output("\033[1;4r\033[4;1H\n\033[r");
        frame();
        assertCanvasMatchesScreen("after a scroll-region scroll");
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // Harness self-check: prove the shadow canvas actually catches a missed repaint.
    // ═════════════════════════════════════════════════════════════════════════════════════════

    public void testHarnessDetectsAMissedRepaint() {
        newTerminal();
        fillLines("row-", 0, 30);
        frame();
        scrollTo(0);
        frame();

        mSimulateMissedRepaint = true;
        output("\033[1;1HT\033[" + ROWS + ";1HB\033[1;1H");
        try {
            frame();
            assertCanvasMatchesScreen("deliberately broken repaint");
            fail("the shadow canvas failed to notice a skipped repaint — the test has no teeth");
        } catch (AssertionFailedError expected) {
            assertTrue("unexpected failure: " + expected.getMessage(),
                expected.getMessage().contains("STALE"));
        } finally {
            mSimulateMissedRepaint = false;
        }
    }
}
