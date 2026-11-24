package com.termux.view;

import android.graphics.Rect;

/**
 * Pixel geometry of the glyph grid painted inside a {@link TerminalView}.
 *
 * <p>A terminal view is usually larger than its glyph grid: the leftover width is split into
 * equal side margins and the leftover height stays below the bottom row. Those bands are filled
 * with the terminal background, but they are not part of the terminal itself. Anything that
 * animates the terminal into or out of a thumbnail must use this grid rectangle, otherwise the
 * thumbnail and the flying snapshot disagree about their aspect ratio and the terminal jumps when
 * one replaces the other.</p>
 */
public final class TerminalGridGeometry {

    /** Smallest terminal grid the view and its previews may use. */
    public static final int MIN_COLUMNS = 4;
    public static final int MIN_ROWS = 4;

    private TerminalGridGeometry() {}

    /**
     * Compute the visible part of the glyph grid in view coordinates.
     *
     * <p>The grid is top-pinned and horizontally centered. {@code gridTop} includes any elastic
     * over-drag, so the result is clipped to the view just like the pixels the view can actually
     * paint.</p>
     *
     * @param viewWidth   terminal view width in pixels
     * @param viewHeight  terminal view height in pixels
     * @param columns     emulator column count
     * @param rows        emulator row count
     * @param fontWidth   glyph cell width in pixels
     * @param lineSpacing row pitch in pixels
     * @param gridLeft    left edge of the grid in view coordinates
     * @param gridTop     top edge of the grid in view coordinates
     * @param out         receives the bounds; set empty when there is no drawable grid
     * @return true when {@code out} contains a non-empty grid
     */
    public static boolean visibleBounds(int viewWidth, int viewHeight,
                                        int columns, int rows,
                                        float fontWidth, int lineSpacing,
                                        float gridLeft, float gridTop,
                                        Rect out) {
        out.setEmpty();
        if (viewWidth <= 0 || viewHeight <= 0 || columns <= 0 || rows <= 0
                || fontWidth <= 0f || lineSpacing <= 0) {
            return false;
        }

        final int left = Math.round(gridLeft);
        final int top = Math.round(gridTop);
        final int right = Math.round(gridLeft + columns * fontWidth);
        final int bottom = top + rows * lineSpacing;
        out.set(left, top, right, bottom);
        return out.intersect(0, 0, viewWidth, viewHeight);
    }
}
