package com.termux.terminal;

/**
 * Immutable copy of the emulator state needed for one full-frame render.
 *
 * <p>The copy is made on the main thread while the emulator is quiescent. A background thread can
 * then paint it with {@code TerminalRenderer} while the emulator keeps processing output. Rows are
 * copied into their own {@link TerminalRow} instances; the snapshot never exposes a live row.</p>
 */
public final class TerminalRenderSnapshot implements TerminalRenderSource {

    private final int columns;
    private final int rows;
    private final int cursorCol;
    private final int cursorRow;
    private final int cursorStyle;
    private final int activeTranscriptRows;
    private final boolean cursorVisible;
    private final boolean reverseVideo;
    private final int[] palette;
    private final int firstExternalRow;
    private final TerminalRow[] lines;

    private TerminalRenderSnapshot(int columns, int rows, int cursorCol, int cursorRow,
                                   int cursorStyle, int activeTranscriptRows,
                                   boolean cursorVisible, boolean reverseVideo,
                                   int[] palette, int firstExternalRow, TerminalRow[] lines) {
        this.columns = columns;
        this.rows = rows;
        this.cursorCol = cursorCol;
        this.cursorRow = cursorRow;
        this.cursorStyle = cursorStyle;
        this.activeTranscriptRows = activeTranscriptRows;
        this.cursorVisible = cursorVisible;
        this.reverseVideo = reverseVideo;
        this.palette = palette;
        this.firstExternalRow = firstExternalRow;
        this.lines = lines;
    }

    /**
     * Capture the visible window that starts at {@code topRow}.
     *
     * <p>{@code topRow} is clamped exactly like the live renderer clamps it, so the returned
     * snapshot's {@link #getTopRow()} can be passed back to the renderer unchanged.</p>
     */
    public static TerminalRenderSnapshot capture(TerminalRenderSource source, int topRow) {
        final int columns = source.getColumns();
        final int rows = source.getRows();
        if (columns <= 0 || rows <= 0) {
            throw new IllegalArgumentException("Invalid terminal size: " + columns + "x" + rows);
        }
        final int activeTranscriptRows = source.getActiveTranscriptRows();
        final int firstRow = Math.min(0, Math.max(topRow, -activeTranscriptRows));
        final TerminalRow[] lines = new TerminalRow[rows];
        for (int i = 0; i < rows; i++) {
            lines[i] = new TerminalRow(source.getLineOrBlank(firstRow + i));
        }
        return new TerminalRenderSnapshot(columns, rows, source.getCursorCol(), source.getCursorRow(),
                source.getCursorStyle(), activeTranscriptRows, source.shouldCursorBeVisible(),
                source.isReverseVideo(), source.getPalette().clone(), firstRow, lines);
    }

    /** First external row captured by this snapshot; pass it to the renderer as {@code topRow}. */
    public int getTopRow() {
        return firstExternalRow;
    }

    @Override
    public int getColumns() {
        return columns;
    }

    @Override
    public int getRows() {
        return rows;
    }

    @Override
    public int getCursorCol() {
        return cursorCol;
    }

    @Override
    public int getCursorRow() {
        return cursorRow;
    }

    @Override
    public boolean shouldCursorBeVisible() {
        return cursorVisible;
    }

    @Override
    public int getCursorStyle() {
        return cursorStyle;
    }

    @Override
    public boolean isReverseVideo() {
        return reverseVideo;
    }

    @Override
    public int[] getPalette() {
        return palette;
    }

    @Override
    public int getActiveTranscriptRows() {
        return activeTranscriptRows;
    }

    @Override
    public TerminalRow getLineOrBlank(int externalRow) {
        final int index = externalRow - firstExternalRow;
        if (index < 0 || index >= lines.length) {
            throw new IllegalArgumentException("Row " + externalRow + " is outside the snapshot ["
                    + firstExternalRow + ", " + (firstExternalRow + lines.length) + ")");
        }
        return lines[index];
    }

    @Override
    public boolean isRowDirty(int externalRow) {
        // The snapshot is always painted as a full frame.
        return false;
    }

    @Override
    public void clearRowDirty(int externalRow) {
        // No-op: a snapshot owns no dirty state.
    }
}
