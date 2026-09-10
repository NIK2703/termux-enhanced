package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Regression tests for the dirty-row tracking rework (E1b / E2 / E4 in glyph-scroll-optimization-2.md). */
public class DirtyStateTest extends TerminalTestCase {

    @Test
    public void testFullScreenScroll_isNotAllDirty_andOnlyRevealedRowDirty() {
        // 10 cols, 5 screen rows, 100-row transcript. Drive one line of output to force a scroll.
        mTerminal = new TerminalEmulator(mOutput, 10, 5, INITIAL_CELL_WIDTH_PIXELS, INITIAL_CELL_HEIGHT_PIXELS, 100, null);
        TerminalBuffer screen = mTerminal.getScreen();

        // Establish a clean baseline (the renderer would have called clearDirtyState() first).
        screen.clearDirtyState();
        assertFalse(screen.isAllDirty());
        assertFalse(screen.isRowDirty(0));

        enterString("0123456789\r\n");            // first line, no scroll yet
        assertFalse("writing a line must not mark all dirty", screen.isAllDirty());
        assertTrue(screen.isRowDirty(0));
        screen.clearDirtyState();
        assertFalse(screen.isRowDirty(0));

        enterString("abcdefghij\r\n");            // second line, still no scroll
        screen.clearDirtyState();

        // Now scroll: enough newlines to push the first line out.
        for (int i = 0; i < 5; i++) enterString("row" + i + "xy\r\n");

        // E1: a full-screen scroll must NOT mark the whole buffer dirty — only the ring moved.
        assertFalse("scrollDownOneLine must not markAllDirty()", screen.isAllDirty());
        // Exactly one freshly revealed row (the new bottom line) plus the row the program just wrote
        // should be dirty; the history rows (visible while scrolled up) must be untouched.
        int dirtyCount = 0;
        for (int r = -screen.getActiveTranscriptRows(); r < 5; r++) {
            if (screen.isRowDirty(r)) dirtyCount++;
        }
        // The buffer has ~105 rows; a regression that re-adds markAllDirty() on every scroll would
        // mark all of them. Six is "a handful" and proves the scroll only touched a few rows.
        assertTrue("only a handful of rows should be dirty after a scroll, got " + dirtyCount, dirtyCount <= 10);
    }

    @Test
    public void testRowDirtyBitset_setClearAndAllDirty() {
        mTerminal = new TerminalEmulator(mOutput, 10, 5, INITIAL_CELL_WIDTH_PIXELS, INITIAL_CELL_HEIGHT_PIXELS, 100, null);
        TerminalBuffer screen = mTerminal.getScreen();

        // The renderer would have cleared the initial mAllDirty=true before the first frame.
        screen.clearDirtyState();
        assertFalse(screen.isRowDirty(0));
        screen.markRowDirty(0);
        assertTrue(screen.isRowDirty(0));
        assertFalse(screen.isRowDirty(1));

        screen.clearRowDirty(0);
        assertFalse(screen.isRowDirty(0));

        screen.markAllDirty();
        for (int r = 0; r < 5; r++) assertTrue(screen.isRowDirty(r));
        screen.clearDirtyState();
        for (int r = 0; r < 5; r++) assertFalse(screen.isRowDirty(r));
    }

    @Test
    public void testBlankAndUniform_flag_tracksContent() {
        final long style = TextStyle.NORMAL;
        TerminalRow row = new TerminalRow(10, style);
        // E4: a cleared row is exactly the shape the renderer's blank fast path wants.
        assertTrue(row.isBlankAndUniform());

        // A non-space char breaks it.
        row.setChar(0, 'X', style);
        assertFalse(row.isBlankAndUniform());

        // A space under the uniform style keeps it.
        TerminalRow row2 = new TerminalRow(10, style);
        row2.setChar(0, ' ', style);
        assertTrue(row2.isBlankAndUniform());

        // A space under a *different* style breaks it (it would paint its own background).
        TerminalRow row3 = new TerminalRow(10, style);
        row3.setChar(0, ' ', TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, 1, 0));
        assertFalse(row3.isBlankAndUniform());
    }
}
