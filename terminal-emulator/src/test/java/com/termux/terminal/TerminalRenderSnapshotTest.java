package com.termux.terminal;

/**
 * Snapshot semantics: a captured frame must stay pixel-stable while the live emulator keeps
 * changing on the main thread. This is what lets the overview rasterizer paint on a background
 * thread without ever touching mutable terminal state.
 */
public class TerminalRenderSnapshotTest extends TerminalTestCase {

    public void testSnapshotRowsAreIndependentFromLaterWrites() {
        withTerminalSized(8, 2).enterString("ABCD\nEFGH");
        final TerminalRenderSnapshot snapshot = TerminalRenderSnapshot.capture(mTerminal, 0);

        assertEquals('A', snapshot.getLineOrBlank(0).mText[0]);
        assertEquals('D', snapshot.getLineOrBlank(0).mText[3]);
        assertEquals('E', snapshot.getLineOrBlank(1).mText[0]);
        assertEquals('H', snapshot.getLineOrBlank(1).mText[3]);

        // Clear the live screen. The snapshot must still show the old frame.
        enterString("\033[2J");
        assertEquals(' ', mTerminal.getLineOrBlank(0).mText[0]);
        assertEquals('A', snapshot.getLineOrBlank(0).mText[0]);
        assertEquals('D', snapshot.getLineOrBlank(0).mText[3]);
        assertEquals('E', snapshot.getLineOrBlank(1).mText[0]);
        assertEquals('H', snapshot.getLineOrBlank(1).mText[3]);
    }

    public void testSnapshotPaletteIsACopy() {
        withTerminalSized(8, 2);
        final int original = mTerminal.getPalette()[1];
        final TerminalRenderSnapshot snapshot = TerminalRenderSnapshot.capture(mTerminal, 0);

        mTerminal.mColors.tryParseColor(1, "#ff0000");

        assertEquals(original, snapshot.getPalette()[1]);
        assertEquals(0xFFFF0000, mTerminal.getPalette()[1]);
        assertNotSame(snapshot.getPalette(), mTerminal.getPalette());
    }

    public void testSnapshotCapturesTheClampedTopRow() {
        withTerminalSized(8, 2).enterString("a\nb\nc\nd\ne\nf\ng\nh");
        final int active = mTerminal.getActiveTranscriptRows();
        assertTrue(active > 0);

        final TerminalRenderSnapshot snapshot = TerminalRenderSnapshot.capture(mTerminal, -1000);

        assertEquals(-active, snapshot.getTopRow());
        assertEquals(mTerminal.getLineOrBlank(-active).mText[0],
                snapshot.getLineOrBlank(-active).mText[0]);
    }

    public void testSnapshotGeometrySurvivesResize() {
        withTerminalSized(8, 2).enterString("ABCDEFGH");
        final TerminalRenderSnapshot snapshot = TerminalRenderSnapshot.capture(mTerminal, 0);

        resize(4, 1);

        assertEquals(8, snapshot.getColumns());
        assertEquals(2, snapshot.getRows());
        assertEquals('A', snapshot.getLineOrBlank(0).mText[0]);
        assertEquals(4, mTerminal.getColumns());
    }

    public void testSnapshotReportsNoDirtyRows() {
        withTerminalSized(8, 2).enterString("ABCD");
        final TerminalRenderSnapshot snapshot = TerminalRenderSnapshot.capture(mTerminal, 0);
        assertFalse(snapshot.isRowDirty(0));
        snapshot.clearRowDirty(0);
        assertFalse(snapshot.isRowDirty(0));
    }
}
