package com.termux.terminal;

/**
 * Read-only state needed by {@link com.termux.view.TerminalRenderer}.
 *
 * <p>The live renderer reads a {@link TerminalEmulator} directly. The overview rasterizer uses a
 * {@link TerminalRenderSnapshot} taken on the main thread, so the same renderer can paint a
 * stable frame on a background thread without touching mutable emulator state.</p>
 */
public interface TerminalRenderSource {

    int getColumns();

    int getRows();

    int getCursorCol();

    int getCursorRow();

    boolean shouldCursorBeVisible();

    int getCursorStyle();

    boolean isReverseVideo();

    /** Current indexed palette. Callers must not mutate the returned array. */
    int[] getPalette();

    int getActiveTranscriptRows();

    /**
     * A row in the external coordinate system, where {@code 0} is the top row of the visible
     * screen and negative values address transcript history.
     */
    TerminalRow getLineOrBlank(int externalRow);

    boolean isRowDirty(int externalRow);

    void clearRowDirty(int externalRow);
}
