package com.termux.terminal;

/**
 * Reproduces the scroll-follows-text behavior of TerminalView.onScreenUpdated():
 * when the scrollback buffer is full and new output arrives (with auto-scroll
 * disabled and the view scrolled up), the view position must be adjusted by
 * the emulator's scroll counter so the visible text does not move.
 */
public class ScrollFollowTextTest extends TerminalTestCase {

    private int mTopRow;

    /** Mirror of TerminalView.isFlingActive(): while true, the scroller owns mTopRow. */
    private boolean mFlingActive;

    private void newEmulator(int rows) {
        // Buffer total: 100 rows, of which 'rows' are the screen → transcript max = 100-rows.
        mTerminal = new TerminalEmulator(mOutput, 10, rows, INITIAL_CELL_WIDTH_PIXELS, INITIAL_CELL_HEIGHT_PIXELS, 100, null);
        mTopRow = 0;
        mFlingActive = false;
    }

    private void scrollUp(int lines) {
        int range = mTerminal.getScreen().getActiveTranscriptRows();
        mTopRow = Math.min(0, Math.max(-range, mTopRow - lines));
    }

    /** Mirror of TerminalView.onScreenUpdated(boolean) compensation logic. */
    private void onScreenUpdated(boolean autoScrollDisabled) {
        int rowsInHistory = mTerminal.getScreen().getActiveTranscriptRows();
        if (mTopRow < -rowsInHistory) mTopRow = -rowsInHistory;

        if (mTopRow == 0) {
            // at bottom: stay at bottom
        } else if (mFlingActive) {
            // Mirror of the fling branch: the OverScroller owns mTopRow during a fling —
            // no follow-text compensation and no snap-to-bottom; the counter is still consumed.
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

    private void output(String s) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mTerminal.append(bytes, bytes.length);
    }

    /** Text currently under the viewport (mTopRow..mTopRow+rows-1). */
    private String[] visibleText() {
        int rows = mTerminal.getScreen().mScreenRows;
        String[] out = new String[rows];
        for (int r = 0; r < rows; r++) {
            int row = mTopRow + r;
            TerminalRow line = mTerminal.getScreen().allocateFullLineIfNecessary(mTerminal.getScreen().externalToInternalRow(row));
            out[r] = new String(line.mText, 0, line.getSpaceUsed()).trim();
        }
        return out;
    }

    public void testFullBufferFollowsText() {
        // Screen 4 rows, transcript max 96 rows (buffer total 100).
        newEmulator(4);
        final int maxTranscript = 100 - 4;

        // Fill the transcript to the max: history lines + 4 screen lines.
        for (int i = 0; i < maxTranscript + 4; i++) {
            output("line-" + i + "\r\n");
        }
        onScreenUpdated(true);
        assertEquals("transcript should be full", maxTranscript, mTerminal.getScreen().getActiveTranscriptRows());
        assertTrue(mTopRow == 0);

        // User scrolls up by 3 rows.
        scrollUp(3);
        String[] before = visibleText();
        assertTrue("mTopRow=" + mTopRow, mTopRow == -3);

        // New output arrives, buffer is full: lines evict from history.
        for (int i = 0; i < 2; i++) {
            output("new-" + i + "\r\n");
        }
        onScreenUpdated(true);

        String[] after = visibleText();
        assertEquals("Scroll counter not consumed: " + mTerminal.getScrollCounter(), 0, mTerminal.getScrollCounter());
        assertEquals("Visible text must not move when buffer is full (mTopRow=" + mTopRow + ")", java.util.Arrays.asList(before), java.util.Arrays.asList(after));
    }

    public void testFullBufferFollowsTextAtVeryTop() {
        newEmulator(4);
        final int maxTranscript = 100 - 4;

        for (int i = 0; i < maxTranscript + 4; i++) {
            output("line-" + i + "\r\n");
        }
        onScreenUpdated(true);

        // Scroll to the very top of history: viewing the 4 oldest lines.
        scrollUp(maxTranscript);
        assertEquals(-maxTranscript, mTopRow);
        String[] before = visibleText();
        // First visible row is the oldest line in the transcript: line-1
        // (line-0 was evicted when the buffer filled up).
        assertEquals("line-1", before[0]);

        // One new line evicts the oldest line; view must stay on the same
        // text as much as possible (the first evicted line is gone).
        output("new-0\r\n");
        onScreenUpdated(true);

        String[] after = visibleText();
        assertEquals("Should snap to top of remaining history", -maxTranscript, mTopRow);
        assertEquals("line-2", after[0]);
    }

    public void testAutoScrollOnSnapsToBottom() {
        newEmulator(4);

        for (int i = 0; i < 100 + 4; i++) {
            output("line-" + i + "\r\n");
        }
        onScreenUpdated(true);

        scrollUp(3);
        assertEquals(-3, mTopRow);

        // With auto-scroll ENABLED the view must jump to the bottom.
        output("new-0\r\n");
        onScreenUpdated(false);
        assertEquals(0, mTopRow);
    }

    public void testFlingActiveSkipsFollowTextCompensation() {
        newEmulator(4);
        final int maxTranscript = 100 - 4;

        for (int i = 0; i < maxTranscript + 4; i++) {
            output("line-" + i + "\r\n");
        }
        onScreenUpdated(true);

        scrollUp(3);
        assertEquals(-3, mTopRow);

        // Fling in progress: new output must NOT shift mTopRow (the scroller owns the
        // position), and the scroll counter is still consumed.
        mFlingActive = true;
        output("new-0\r\nnew-1\r\n");
        onScreenUpdated(true);
        assertEquals("mTopRow must stay scroller-owned during fling", -3, mTopRow);
        assertEquals("Scroll counter must be consumed during fling", 0, mTerminal.getScrollCounter());

        // Even with auto-scroll enabled there is no snap-to-bottom while flinging.
        output("new-2\r\n");
        onScreenUpdated(false);
        assertEquals("No snap-to-bottom during fling", -3, mTopRow);

        // Fling finished: follow-text compensation applies again to newly arrived output.
        mFlingActive = false;
        output("new-3\r\n");
        onScreenUpdated(true);
        assertEquals("follow-text compensation resumes after fling", -4, mTopRow);
    }

    public void testFollowTextUnchangedWithoutFling() {
        // Guard against the fling branch leaking into normal behaviour: without an active
        // fling the compensation must work exactly as before.
        newEmulator(4);
        final int maxTranscript = 100 - 4;

        for (int i = 0; i < maxTranscript + 4; i++) {
            output("line-" + i + "\r\n");
        }
        onScreenUpdated(true);

        scrollUp(3);
        output("new-0\r\nnew-1\r\n");
        onScreenUpdated(true);
        assertEquals(-5, mTopRow);
    }
}
