package com.termux.terminal;

/**
 * Thin JNI bridge to the native terminal core. The default backend is foot (terminal-emulator/src/main/foot); the Rust core exposes the same entry points when built with -PtermuxTerminalBackend=rust.
 *
 * <p>The native side owns the VT parser, the screen/history grid, the palette mirror, selection
 * extraction and render planning. Instances are represented by an opaque handle; every call is
 * synchronised on the native side, but the Java terminal facade is single-threaded (main thread),
 * like the rest of the emulation path.</p>
 */
final class TerminalNative {

    static {
        System.loadLibrary("termux_terminal");
    }

    private TerminalNative() {
    }

    static native long nativeCreate(int columns, int rows, int transcriptRows,
                                    int cellWidth, int cellHeight, int[] palette);

    static native void nativeDestroy(long handle);

    /** Feed raw PTY bytes; returns the visible rows whose content changed. */
    static native int[] nativeAppend(long handle, byte[] data, int length);

    static native void nativeReset(long handle);

    static native void nativeResize(long handle, int columns, int rows, int cellWidth, int cellHeight);

    /** Newline-joined event records, or an empty string. */
    static native String nativeDrainEvents(long handle);

    static native int[] nativePalette(long handle);

    /**
     * Update the native colour mirror.
     *
     * @param palette  current dynamic colours (259 entries)
     * @param defaults colours OSC 104/110-112 reset to (259 entries)
     * @return true when the current palette changed
     */
    static native boolean nativeSetPalette(long handle, int[] palette, int[] defaults);

    static native int nativeHistorySize(long handle);

    static native int nativeRows(long handle);

    static native int nativeColumns(long handle);

    static native int nativeCursorRow(long handle);

    static native int nativeCursorCol(long handle);

    /** Bit mask mirroring the old Java DECSET bits. */
    static native int nativeModeBits(long handle);

    static native int nativeScrollCounter(long handle);

    static native void nativeClearScrollCounter(long handle);

    /** Legacy per-row anchor query; retained for the fixed 27-method JNI ABI. */
    static native int nativeAnchorRow(long handle, int externalRow);

    /** Absolute (unclamped) scroll count used as the base of content anchors. */
    static native long nativeAbsoluteScrollCount(long handle);

    static native boolean nativeIsAlternate(long handle);

    /** Cursor shape using the Termux TERMINAL_CURSOR_STYLE_* constants. */
    static native int nativeCursorStyle(long handle);

    /** Override the cursor shape (used for the app's configured default). */
    static native void nativeSetCursorStyle(long handle, int shape);

    /** Packed row snapshot: spaceUsed, lineWrap, charCount, UTF-16 units, column starts. */
    static native int[] nativeRow(long handle, int externalRow);

    static native String nativeSelectedText(long handle, int x1, int y1, int x2, int y2,
                                            boolean joinBackLines, boolean joinFullLines);

    /** mode: 0 = join wrapped lines, 1 = keep line breaks, 2 = also join full-width lines. */
    static native String nativeTranscriptText(long handle, int mode);

    static native String nativeWordAtLocation(long handle, int x, int y);

    /**
     * Packed render payload consumed by {@code TerminalRenderer}: commands terminated by
     * {@code OP_END}, followed by the UTF-16 text length and the text units.
     */
    static native int[] nativeRender(long handle, int topRow, int selectionY1, int selectionY2,
                                     int selectionX1, int selectionX2, boolean cursorVisible,
                                     int cursorShape, int backgroundAlpha, float fontWidth,
                                     int fontLineSpacing, int fontAscent, int columns, int rows);

    /** Legacy separate UTF-16 payload; kept for the fixed 27-method JNI ABI. */
    static native int[] nativeRenderText(long handle);
}
