package com.termux.terminal;

import java.util.HashSet;
import java.util.Set;

/**
 * Read-only view of the native terminal grid plus the dirty-row bookkeeping used by the view.
 *
 * <p>The emulator state itself (screen, transcript, alternate buffer, reflow) lives in the native terminal core (foot by default, with the Rust core available as a rollback backend).
 * This class is the small Java facade the existing selection and rendering code was written
 * against: it exposes external row coordinates ({@code -history..screenRows-1}), row snapshots
 * for the text selection UI, and a dirty set addressed by <em>content anchors</em> so a dirty
 * row keeps its identity when output scrolls the transcript.</p>
 */
public final class TerminalBuffer {

    /**
     * The native grid handle, or 0 for the detached compatibility constructor used by
     * standalone buffer tests.
     */
    private final long mNativeHandle;

    /** The number of columns visible on the screen. */
    int mColumns;
    /** The total number of rows kept by the terminal (screen + transcript). */
    int mTotalRows;
    /** The number of rows visible on the screen. */
    int mScreenRows;

    private boolean mAllDirty = true;
    private final Set<Integer> mDirtyRows = new HashSet<>();
    private int mCachedHistory = -1;

    private TerminalRow mBlankRow;
    private int mBlankRowColumns = -1;

    TerminalBuffer(long nativeHandle, int columns, int totalRows, int screenRows) {
        mNativeHandle = nativeHandle;
        mColumns = columns;
        mTotalRows = totalRows;
        mScreenRows = screenRows;
    }

    /**
     * Detached, empty buffer. Kept so code which only needs the coordinate helpers can be
     * instantiated without a terminal; all content comes back blank.
     */
    public TerminalBuffer(int columns, int totalRows, int screenRows) {
        this(0, columns, totalRows, screenRows);
    }

    void updateSize(int columns, int totalRows, int screenRows) {
        mColumns = columns;
        mTotalRows = totalRows;
        mScreenRows = screenRows;
        mBlankRow = null;
        mBlankRowColumns = -1;
        mCachedHistory = -1;
        markAllDirty();
    }

    void invalidateHistoryCache() {
        mCachedHistory = -1;
    }

    public void markRowDirty(int externalRow) {
        if (mAllDirty) return;
        final int history = getActiveTranscriptRows();
        if (externalRow < -history || externalRow > mScreenRows) return;
        mDirtyRows.add(anchorFor(externalRow));
    }

    /**
     * Compatibility entry point for code that addresses ring slots. Native rows are identified
     * by their content anchor, and {@link #externalToInternalRow(int)} returns exactly that.
     */
    public void markInternalRowDirty(int internalRow) {
        if (mAllDirty) return;
        mDirtyRows.add(internalRow);
    }

    public boolean isRowDirty(int externalRow) {
        if (mAllDirty) return true;
        if (mDirtyRows.isEmpty()) return false;
        final int history = getActiveTranscriptRows();
        if (externalRow < -history || externalRow > mScreenRows) return false;
        return mDirtyRows.contains(anchorFor(externalRow));
    }

    public void clearRowDirty(int externalRow) {
        if (mAllDirty) return;
        final int history = getActiveTranscriptRows();
        if (externalRow < -history || externalRow > mScreenRows) return;
        mDirtyRows.remove(anchorFor(externalRow));
    }

    public void markAllDirty() {
        mAllDirty = true;
        mDirtyRows.clear();
    }

    public boolean isAllDirty() {
        return mAllDirty;
    }

    public void clearDirtyState() {
        mAllDirty = false;
        mDirtyRows.clear();
    }

    private int anchorFor(int externalRow) {
        if (mNativeHandle == 0) return externalRow;
        return TerminalNative.nativeAnchorRow(mNativeHandle, externalRow);
    }

    public int getActiveTranscriptRows() {
        if (mNativeHandle == 0) return 0;
        if (mCachedHistory < 0) {
            mCachedHistory = TerminalNative.nativeHistorySize(mNativeHandle);
        }
        return mCachedHistory;
    }

    public int getActiveRows() {
        return getActiveTranscriptRows() + mScreenRows;
    }

    /**
     * Content-addressed identity of an external row. It changes when the row's content moves
     * (live output scrolling the screen), and stays the same when the viewport follows the
     * content, which is exactly what {@code TerminalView.repaintAfterUpdate()} compares.
     */
    public int externalToInternalRow(int externalRow) {
        if (mNativeHandle == 0) return externalRow;
        return TerminalNative.nativeAnchorRow(mNativeHandle, externalRow);
    }

    public String getTranscriptText() {
        return getTranscriptText(0);
    }

    public String getTranscriptTextWithoutJoinedLines() {
        return getTranscriptText(1);
    }

    public String getTranscriptTextWithFullLinesJoined() {
        return getTranscriptText(2);
    }

    private String getTranscriptText(int mode) {
        if (mNativeHandle == 0) return "";
        return TerminalNative.nativeTranscriptText(mNativeHandle, mode);
    }

    public String getSelectedText(int selX1, int selY1, int selX2, int selY2) {
        return getSelectedText(selX1, selY1, selX2, selY2, true);
    }

    public String getSelectedText(int selX1, int selY1, int selX2, int selY2, boolean joinBackLines) {
        return getSelectedText(selX1, selY1, selX2, selY2, joinBackLines, false);
    }

    public String getSelectedText(int selX1, int selY1, int selX2, int selY2, boolean joinBackLines, boolean joinFullLines) {
        if (mNativeHandle == 0) return "";
        return TerminalNative.nativeSelectedText(mNativeHandle, selX1, selY1, selX2, selY2, joinBackLines, joinFullLines);
    }

    public String getWordAtLocation(int x, int y) {
        if (mNativeHandle == 0) return "";
        return TerminalNative.nativeWordAtLocation(mNativeHandle, x, y);
    }

    public boolean getLineWrap(int row) {
        return getLineOrBlank(row).mLineWrap;
    }

    public void setLineWrap(int row) {
        // Line wrapping is owned by the native grid and set while text is written.
    }

    public void clearLineWrap(int row) {
        // Line wrapping is owned by the native grid.
    }

    /**
     * Materialize one row as a {@link TerminalRow} snapshot. The returned object is immutable
     * and safe to read from the renderer / selection controller.
     */
    public TerminalRow getLineOrBlank(int externalRow) {
        final int history = getActiveTranscriptRows();
        if (externalRow < -history || externalRow > mScreenRows) {
            throw new IllegalArgumentException("extRow=" + externalRow + ", mScreenRows=" + mScreenRows
                + ", mActiveTranscriptRows=" + history);
        }
        if (mNativeHandle == 0) return blankRow();
        final int[] encoded = TerminalNative.nativeRow(mNativeHandle, externalRow);
        if (encoded == null || encoded.length < 3) return blankRow();
        final int spaceUsed = encoded[0];
        final boolean lineWrap = encoded[1] != 0;
        final int charCount = encoded[2];
        if (charCount < 0 || 3 + charCount > encoded.length) return blankRow();
        char[] text = new char[charCount];
        for (int i = 0; i < charCount; i++) text[i] = (char) encoded[3 + i];
        final int startsOffset = 3 + charCount;
        final int startsLength = Math.min(mColumns + 1, encoded.length - startsOffset);
        int[] starts = new int[mColumns + 1];
        for (int i = 0; i < startsLength; i++) starts[i] = encoded[startsOffset + i];
        for (int i = startsLength; i <= mColumns; i++) starts[i] = charCount;
        long[] styles = null;
        final int stylesOffset = startsOffset + mColumns + 1;
        if (encoded.length >= stylesOffset + mColumns * 2) {
            styles = new long[mColumns];
            for (int i = 0; i < mColumns; i++) {
                final long low = encoded[stylesOffset + i * 2] & 0xffffffffL;
                final long high = encoded[stylesOffset + i * 2 + 1] & 0xffffffffL;
                styles[i] = (high << 32) | low;
            }
        }
        return new TerminalRow(mColumns, text, starts, styles, spaceUsed, lineWrap);
    }

    private TerminalRow blankRow() {
        if (mBlankRow == null || mBlankRowColumns != mColumns) {
            char[] text = new char[mColumns];
            java.util.Arrays.fill(text, ' ');
            int[] starts = new int[mColumns + 1];
            for (int i = 0; i <= mColumns; i++) starts[i] = i;
            mBlankRow = new TerminalRow(mColumns, text, starts, mColumns, false);
            mBlankRowColumns = mColumns;
        }
        return mBlankRow;
    }

    /**
     * Legacy helper: accepts a content anchor (as returned by
     * {@link #externalToInternalRow(int)}) and materializes the corresponding row.
     */
    public TerminalRow allocateFullLineIfNecessary(int internalRow) {
        if (mNativeHandle == 0) return blankRow();
        long scroll = TerminalNative.nativeAbsoluteScrollCount(mNativeHandle);
        int externalRow = (int) (internalRow - scroll);
        return getLineOrBlank(externalRow);
    }

    /** Style of one cell, as encoded by {@link TextStyle}. */
    public long getStyleAt(int externalRow, int column) {
        TerminalRow row = getLineOrBlank(externalRow);
        if (column < 0 || column >= mColumns) return TextStyle.NORMAL;
        return row.getStyle(column);
    }
}
