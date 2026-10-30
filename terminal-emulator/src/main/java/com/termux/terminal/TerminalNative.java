package com.termux.terminal;

import android.graphics.Bitmap;

/**
 * Thin JNI bridge to the foot terminal core in terminal-emulator/src/main/foot.
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

    /**
     * Header size of the packed state array returned by {@link #nativeAppend}.
     * Layout: dirty count, mode bits, cursor style, cursor row, cursor column,
     * absolute scroll count, history size, flags, viewport top, followed by the
     * dirty visible rows (external addresses: negative values are transcript rows).
     */
    static final int APPEND_STATE_HEADER_SIZE = 9;

    /** The append contained a scroll that did not cover the whole screen. */
    static final int APPEND_FLAG_PARTIAL_SCROLL = 1;

    /** Native full-viewport damage is still pending until a full frame commits. */
    static final int APPEND_FLAG_FULL_DAMAGE = 1 << 1;

    /**
     * Feed raw PTY bytes. Returns the packed state described by
     * {@link #APPEND_STATE_HEADER_SIZE}; dirty rows are external viewport
     * coordinates (negative values are transcript rows). The array is returned
     * even when no row is dirty, so the caller can update its cached
     * mode/cursor/scroll state without extra JNI calls.
     */
    static native int[] nativeAppend(long handle, byte[] data, int length);

    /**
     * Fill {@code out} with the packed append state and return its length, or
     * {@code -required} when {@code out} cannot hold the header plus one entry per visible
     * row. An undersized call does not consume {@code data}. Reusing the caller's array
     * avoids a fresh JNI allocation on every PTY chunk.
     */
    static native int nativeAppendInto(long handle, byte[] data, int length, int[] out);

    static native void nativeReset(long handle);

    static native void nativeResize(long handle, int columns, int rows, int cellWidth, int cellHeight);

    /**
     * Synchronize foot's grid viewport with the UI address. {@code topRow} uses
     * the same external coordinates as the render commands: 0 is the live
     * screen top, negative values are transcript rows above it.
     */
    /** Set the viewport; returns the effective (history-clamped) top row. */
    static native int nativeSetView(long handle, int topRow);

    /** Consume the retained-surface viewport shift accumulated by the last setView. */
    static native int nativeConsumeViewportShift(long handle);

    /** Current foot viewport top in external coordinates (0 = live screen top). */
    static native int nativeViewTop(long handle);

    /** Retire native dirty bits for visible rows that were actually painted. */
    static native void nativeCommitRows(long handle, int[] rows, int count);

    /** Mark rows dirty for Android overlays (for example the scrollbar band). */
    static native void nativeDamageRows(long handle, int[] rows, int count);

    /**
     * Move a terminal scroll region on an ARGB_8888 retained surface. The
     * native core derives the pixel band from the terminal's cell height and
     * replays foot's grid_render_scroll()/grid_render_scroll_reverse().
     * {@code start}/{@code end} are viewport-relative rows and {@code lines}
     * is the number of whole rows to move. Returns false when the bitmap bridge
     * is unavailable or the geometry is invalid, so the caller can fall back to
     * a full row repaint.
     */
    static native boolean nativeScrollSurfaceRows(long handle, Bitmap bitmap,
                                                  int start, int end, int lines,
                                                  boolean reverse);

    /** Retire every native dirty bit after a full-clip frame. */
    static native void nativeCommitAllRows(long handle);

    /** Newline-joined event records, or null when no events are pending. */
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

    /** Legacy per-row anchor query; retained for the JNI ABI. */
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

    /**
     * Fill {@code out} with the packed render payload and return its length, or
     * {@code -required} when {@code out} is too small. Reusing the caller's array avoids a
     * fresh JNI allocation on every frame.
     */
    static native int nativeRenderInto(long handle, int topRow, int selectionY1, int selectionY2,
                                       int selectionX1, int selectionX2, boolean cursorVisible,
                                       int cursorShape, int backgroundAlpha, float fontWidth,
                                       int fontLineSpacing, int fontAscent, int columns, int rows,
                                       boolean forceAllRows, int[] out);

    /** Legacy separate UTF-16 payload; kept for the fixed JNI ABI. */
    static native int[] nativeRenderText(long handle);

    /**
     * Copy one visible sixel surface into a caller-owned bitmap. {@code imageId}
     * and {@code revision} come from an OP_IMAGE render command. Returns null
     * when the image has scrolled away or was modified before the copy.
     */
    static native int[] nativeSixelPixels(long handle, long imageId, int revision,
                                          int width, int height);
}
