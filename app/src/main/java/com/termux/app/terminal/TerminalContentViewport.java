package com.termux.app.terminal;

import androidx.annotation.NonNull;

/**
 * Resolves the terminal content viewport used by the session overview.
 *
 * <p>The pager is full-bleed, while {@code terminal-margin-*} are layout margins on each page's
 * {@code TerminalView}. The glyph grid is therefore laid out for the pager size minus those
 * margins, not for the pager itself. The overview must use the same rectangle or its card
 * thumbnails get a different column/row count and aspect than the live terminal.</p>
 *
 * <p>Kept as a pure helper so the arithmetic can be tested without an Android view tree.</p>
 */
public final class TerminalContentViewport {

    private TerminalContentViewport() {}

    /**
     * Content viewport for a full-bleed pager with per-page terminal margins.
     *
     * @param pagerWidth       pager width in pixels.
     * @param pagerHeight      pager height in pixels.
     * @param leftMarginPx     left layout margin of the page's TerminalView.
     * @param topMarginPx      top layout margin of the page's TerminalView.
     * @param rightMarginPx    right layout margin of the page's TerminalView.
     * @param bottomMarginPx   bottom layout margin of the page's TerminalView.
     * @param imeBottomPx      keyboard inset that the pager no longer includes, or zero.
     * @return packed {@code {width, height}}, or {@code {0, 0}} when no usable size remains.
     */
    @NonNull
    public static int[] fromPager(int pagerWidth, int pagerHeight,
                                  int leftMarginPx, int topMarginPx,
                                  int rightMarginPx, int bottomMarginPx,
                                  int imeBottomPx) {
        if (pagerWidth <= 0 || pagerHeight <= 0) return new int[]{0, 0};

        final int width = pagerWidth - leftMarginPx - rightMarginPx;
        final int height = pagerHeight - topMarginPx - bottomMarginPx
                + Math.max(0, imeBottomPx);
        if (width <= 0 || height <= 0) return new int[]{0, 0};
        return new int[]{width, height};
    }

    /**
     * Content viewport measured directly on an already-laid-out TerminalView.
     *
     * <p>Its size already excludes the layout margins. The IME inset is added back because the
     * view may have been compressed before the overview hid the keyboard.</p>
     *
     * @param viewWidth   TerminalView width in pixels.
     * @param viewHeight  TerminalView height in pixels.
     * @param imeBottomPx keyboard inset that the view no longer includes, or zero.
     * @return packed {@code {width, height}}, or {@code {0, 0}} when no usable size exists.
     */
    @NonNull
    public static int[] fromView(int viewWidth, int viewHeight, int imeBottomPx) {
        if (viewWidth <= 0 || viewHeight <= 0) return new int[]{0, 0};
        return new int[]{viewWidth, viewHeight + Math.max(0, imeBottomPx)};
    }
}
