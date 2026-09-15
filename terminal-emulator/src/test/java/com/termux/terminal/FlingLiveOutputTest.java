package com.termux.terminal;

import java.util.Arrays;

/**
 * Why a fling lands short of the end it was heading for when the program prints during the glide.
 *
 * <h2>The mechanism</h2>
 *
 * {@code mTopRow} is an <em>address</em>, not a piece of content: 0 is the live bottom and every
 * printed line renumbers each old row one row deeper, because
 * {@code TerminalBuffer.scrollDownOneLine()} grows the transcript
 * ({@code mActiveTranscriptRows++}). Two consequences fall out of the same fact:
 * <ol>
 *   <li>the top of the history is <em>content</em>-anchored — the oldest line stays the oldest
 *       line, its address just gets deeper by one per printed line;</li>
 *   <li>a viewport whose address is frozen therefore drifts one row towards the newer text per
 *       printed line.</li>
 * </ol>
 *
 * The fling is a single absolute {@code OverScroller} whose bounds are snapshotted at gesture
 * start ({@code TerminalView.startFling()}: {@code minPx = -transcriptRows * rowHeight}), and
 * {@code onScreenUpdated()} deliberately skips the follow-text compensation while it runs. So the
 * fling flies over addresses that the output renumbered underneath it: it lands on the address
 * that was the top of the history when the gesture started, which by then is exactly
 * {@code rowShift} rows short of the real one.
 *
 * <p>The fix must move <em>both</em> sides by the same amount ({@code mTopRow} and the scroller's
 * row target, i.e. {@code mFlingAnchorShiftPx}), so {@code diff = newRow - mTopRow} — the only
 * thing the fling applies — stays untouched. And it must do that only while the fling heads into
 * history: the other end, the live bottom, is anchored to "now" and must not recede, otherwise a
 * fling towards the bottom starts falling short by the very same N.</p>
 *
 * <h2>Method</h2>
 *
 * Pure JVM: a unit test cannot instantiate {@code TerminalView} (an Android {@code View}) nor an
 * {@code OverScroller}, so only the positional half is mirrored — the same technique
 * {@link ScrollFollowTextTest} uses. The emulator, the buffer and the scroll counter are real.
 */
public class FlingLiveOutputTest extends TerminalTestCase {

    private static final int ROWS = 4;
    private static final int TOTAL_ROWS = 100;
    private static final int MAX_TRANSCRIPT = TOTAL_ROWS - ROWS;

    // ── Mirrored TerminalView state ────────────────────────────────────────────────────────
    private int mTopRow;
    private boolean mFlingActive;
    /** > 0 == heading into history (see the sign convention on TerminalView.startFling()). */
    private int mFlingRawVelocity;
    private int mFlingStartRow;
    private int mFlingMinRow;
    private int mFlingFinalRow;
    private int mFlingTravelled;
    /** Mirror of {@code mFlingAnchorShiftPx}, in rows. */
    private int mFlingAnchorShiftRows;
    /** When false the mirror reproduces the pre-fix behaviour (no anchoring at all). */
    private boolean mFixEnabled;

    private void newEmulator() {
        mTerminal = new TerminalEmulator(mOutput, 10, ROWS, INITIAL_CELL_WIDTH_PIXELS,
            INITIAL_CELL_HEIGHT_PIXELS, TOTAL_ROWS, null);
        mTopRow = 0;
        mFlingActive = false;
        mFlingRawVelocity = 0;
        mFlingStartRow = 0;
        mFlingMinRow = 0;
        mFlingFinalRow = 0;
        mFlingTravelled = 0;
        mFlingAnchorShiftRows = 0;
    }

    private void output(String s) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mTerminal.append(bytes, bytes.length);
    }

    private void scrollUp(int lines) {
        int range = mTerminal.getScreen().getActiveTranscriptRows();
        mTopRow = Math.min(0, Math.max(-range, mTopRow - lines));
    }

    /** Text under {@code topRow .. topRow+ROWS-1}, trimmed. */
    private String[] textAt(int topRow) {
        String[] out = new String[ROWS];
        for (int r = 0; r < ROWS; r++) {
            TerminalRow line = mTerminal.getScreen()
                .allocateFullLineIfNecessary(mTerminal.getScreen().externalToInternalRow(topRow + r));
            out[r] = new String(line.mText, 0, line.getSpaceUsed()).trim();
        }
        return out;
    }

    private String[] visibleText() {
        return textAt(mTopRow);
    }

    /**
     * Mirror of {@code TerminalView.onScreenUpdated(boolean)}: the follow-text compensation, the
     * snap-to-bottom and the fling branch. {@code autoScrollDisabled} is the state
     * {@code doScroll()} keeps in sync with {@code mTopRow != 0}.
     */
    private void onScreenUpdated(boolean autoScrollDisabled) {
        int rowsInHistory = mTerminal.getScreen().getActiveTranscriptRows();
        if (mTopRow < -rowsInHistory) mTopRow = -rowsInHistory;

        if (mTopRow == 0) {
            // live bottom: nothing to compensate, the view follows the output
        } else if (mFlingActive) {
            int rowShift = mTerminal.getScrollCounter();
            if (rowShift != 0) anchorFlingToContent(rowShift);
        } else if (autoScrollDisabled) {
            int rowShift = mTerminal.getScrollCounter();
            if (-mTopRow + rowShift > rowsInHistory) {
                mTopRow = -rowsInHistory;
            } else {
                mTopRow -= rowShift;
            }
        } else {
            mTopRow = 0;
        }
        mTerminal.clearScrollCounter();
    }

    /** Mirror of {@code TerminalView.anchorFlingToContent(int)}. */
    private void anchorFlingToContent(int rowShift) {
        if (!mFixEnabled) return;
        if (mFlingRawVelocity <= 0) return; // heading for the live bottom: it must not recede
        int rowsInHistory = mTerminal.getScreen().getActiveTranscriptRows();
        int newTop = Math.max(-rowsInHistory, mTopRow - rowShift);
        int applied = mTopRow - newTop;
        if (applied <= 0) return;
        mTopRow = newTop;
        mFlingAnchorShiftRows -= applied;
    }

    /**
     * Mirror of {@code startFling()}: the bounds are a snapshot, the final row is clamped to them
     * exactly like {@code SplineOverScroller} clamps {@code mFinal}.
     */
    private void startFling(int distanceRows, boolean intoHistory) {
        mFlingActive = true;
        mFlingRawVelocity = intoHistory ? 1 : -1;
        mFlingStartRow = mTopRow;
        mFlingMinRow = -mTerminal.getScreen().getActiveTranscriptRows();
        mFlingTravelled = 0;
        mFlingAnchorShiftRows = 0;
        int natural = mTopRow + (intoHistory ? -distanceRows : distanceRows);
        mFlingFinalRow = Math.min(0, Math.max(mFlingMinRow, natural));
    }

    /** Mirror of the per-frame {@code doScroll(diff)} of {@code runFlingFrame()}. */
    private void flingFrame(int travelled) {
        mFlingTravelled = travelled;
        int target = mFlingStartRow
            + (mFlingRawVelocity > 0 ? -mFlingTravelled : mFlingTravelled)
            + mFlingAnchorShiftRows;
        int rowsInHistory = mTerminal.getScreen().getActiveTranscriptRows();
        mTopRow = Math.min(0, Math.max(-rowsInHistory, target));
    }

    private void endFling() {
        flingFrame(Math.abs(mFlingFinalRow - mFlingStartRow));
        mFlingActive = false;
    }

    /** Fill {@code lines} rows of output, one {@code onScreenUpdated()} per line. */
    private void printDuring(int lines, boolean autoScrollDisabled) {
        for (int i = 0; i < lines; i++) {
            output("L" + (1000 + i) + "\r\n");
            onScreenUpdated(autoScrollDisabled);
        }
    }

    // ───────────────────────────────────────────────────────────────────────────────────────

    /** Premise: printing while scrolled back grows the transcript and renumbers every old row. */
    public void testOutputRenumberstHistory() {
        newEmulator();
        printDuring(60, true);
        int transcript = mTerminal.getScreen().getActiveTranscriptRows();
        assertTrue("transcript must be non-empty", transcript > ROWS);
        output("X\r\n");
        onScreenUpdated(true);
        assertEquals("exactly one transcript row per scrolled line", transcript + 1,
            mTerminal.getScreen().getActiveTranscriptRows());
        transcript = mTerminal.getScreen().getActiveTranscriptRows();

        scrollUp(20);
        assertEquals(-20, mTopRow);
        String[] before = visibleText();

        printDuring(7, true);
        assertEquals("transcript grew by the 7 new lines", transcript + 7,
            mTerminal.getScreen().getActiveTranscriptRows());

        // With the follow-text compensation the same text is still under the viewport...
        assertEquals(-27, mTopRow);
        assertEquals("compensation keeps the text", Arrays.asList(before), Arrays.asList(visibleText()));
        // ...while the address it used to sit on now shows text 7 rows newer: the renumbering.
        assertFalse("a frozen address must drift",
            Arrays.asList(before).equals(Arrays.asList(textAt(-20))));
    }

    /** The reported bug, reproduced: into history, N lines printed during the glide. */
    public void testFlingIntoHistoryFallsShortByPrintedLines() {
        mFixEnabled = false;
        newEmulator();
        printDuring(60, true);
        final int transcript = mTerminal.getScreen().getActiveTranscriptRows();

        scrollUp(20);
        startFling(1000, true);           // hard flick: wants the very top of history
        printDuring(7, true);             // 7 lines arrive mid-flight
        endFling();

        final int liveTop = -mTerminal.getScreen().getActiveTranscriptRows();
        assertEquals("transcript grew", transcript + 7, -liveTop);
        assertEquals("lands on the snapshot bound", -transcript, mTopRow);
        assertEquals("shortfall is exactly the number of lines printed during the fling",
            7, mTopRow - liveTop);
    }

    /** The fix: both sides move together, the fling reaches the real top of history. */
    public void testAnchoredFlingIntoHistoryReachesTheTop() {
        mFixEnabled = true;
        newEmulator();
        printDuring(60, true);
        final int transcript = mTerminal.getScreen().getActiveTranscriptRows();

        scrollUp(20);
        String[] before = visibleText();
        startFling(1000, true);
        printDuring(7, true);             // the viewport is re-anchored, the text does not drift
        assertEquals("text stays put while output arrives", Arrays.asList(before),
            Arrays.asList(visibleText()));
        endFling();

        final int liveTop = -mTerminal.getScreen().getActiveTranscriptRows();
        assertEquals("no shortfall", 0, mTopRow - liveTop);
    }

    /** A weak flick must cover exactly as many content rows as it would in a silent terminal. */
    public void testAnchoredFlingCoversTheSameDistance() {
        mFixEnabled = true;
        newEmulator();
        printDuring(60, true);
        final int transcript = mTerminal.getScreen().getActiveTranscriptRows();

        scrollUp(20);
        startFling(10, true);             // only 10 rows of travel: cannot reach the top
        printDuring(7, true);
        endFling();

        // A silent terminal would have ended at -30 with the top of history at -transcript, i.e.
        // transcript-30 rows from it. Output must not change that distance.
        final int liveTop = -mTerminal.getScreen().getActiveTranscriptRows();
        assertEquals(transcript - 30, mTopRow - liveTop);
    }

    /** The other end: the live bottom is anchored to "now" and must not recede. */
    public void testFlingTowardsBottomStillReachesIt() {
        newEmulator();
        printDuring(60, true);
        scrollUp(20);

        for (boolean fix : new boolean[] {false, true}) {
            mFixEnabled = fix;
            mTopRow = -20;
            mFlingActive = false;
            printDuring(0, true);
            startFling(1000, false);      // flick back down to the live output
            printDuring(7, true);
            endFling();
            assertEquals("bottom reached with fix=" + fix, 0, mTopRow);
        }
    }

    /** Degenerate case: the transcript is at its limit, so the top of history cannot recede. */
    public void testFullTranscriptHasNoShortfall() {
        mFixEnabled = false;
        newEmulator();
        printDuring(MAX_TRANSCRIPT + ROWS, true);
        assertEquals(MAX_TRANSCRIPT, mTerminal.getScreen().getActiveTranscriptRows());

        scrollUp(200);
        assertEquals(-MAX_TRANSCRIPT, mTopRow);
        startFling(1000, true);
        printDuring(7, true);
        endFling();
        // Nothing to fall short of: the transcript cannot grow, rows are evicted instead.
        assertEquals(-MAX_TRANSCRIPT, mTopRow);
    }
}
