package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalEmulator;

/**
 * Renders a terminal emulator into a bitmap for the session overview.
 *
 * <p>This deliberately does not create a {@link TerminalView}: a second view would attach to the
 * session, change its size and take part in focus/IME handling. The renderer is stateless apart
 * from the reusable font measurements, so it can paint any emulator without touching the PTY.</p>
 *
 * <p>The terminal grid is scaled to fit the bitmap with its aspect ratio preserved. The remaining
 * margins are filled with the emulator's background colour, which is what makes cards of a
 * different aspect ratio look like a terminal rather than a stretched screenshot.</p>
 */
public final class TerminalPreviewRenderer {

    /** Same floor as {@link TerminalView#updateSize()} uses for degenerate viewports. */
    private static final int MIN_COLUMNS = 4;
    private static final int MIN_ROWS = 4;

    private TerminalPreviewRenderer() {}

    /**
     * The exact glyph grid a terminal session should carry for a given viewport.
     *
     * <p>One value object is the single source of truth for the overview's sizing: the same
     * columns and rows are used to size a card, to recognize a stale emulator, and to republish
     * the normal window grid to it. The cell dimensions are the integers
     * {@link TerminalView#updateSize()} reports to the pty; {@link #aspect()} uses the same
     * fractional font metrics {@link #render} paints with, so a card sized from it has no bands.</p>
     */
    public static final class ViewportGrid {
        public final int columns;
        public final int rows;
        /** Integer cell width reported to the pty, matching {@code TerminalView.updateSize()}. */
        public final int cellWidthPx;
        /** Line spacing reported to the pty, matching {@code TerminalView.updateSize()}. */
        public final int cellHeightPx;
        private final double aspect;

        private ViewportGrid(int columns, int rows, int cellWidthPx, int cellHeightPx,
                             double aspect) {
            this.columns = columns;
            this.rows = rows;
            this.cellWidthPx = cellWidthPx;
            this.cellHeightPx = cellHeightPx;
            this.aspect = aspect;
        }

        /** Width/height ratio of the glyph grid; always positive for a constructed grid. */
        public double aspect() {
            return aspect;
        }

        /** True when the emulator already carries exactly this grid, pty cell size included. */
        public boolean matches(@NonNull TerminalEmulator emulator) {
            return emulator.hasSize(columns, rows, cellWidthPx, cellHeightPx);
        }

        /** Pure form of {@link #matches(TerminalEmulator)}, usable without an emulator. */
        public boolean matches(int columns, int rows, int cellWidthPx, int cellHeightPx) {
            return this.columns == columns && this.rows == rows
                    && this.cellWidthPx == cellWidthPx && this.cellHeightPx == cellHeightPx;
        }
    }

    /**
     * Width-to-height ratio of the exact glyph grid {@link #render} paints for this emulator.
     *
     * <p>A preview target with this aspect ratio has no background margins around the terminal:
     * the scale factor is the same on both axes. The overview uses it instead of the window
     * aspect so the thumbnail is the terminal, not a letterboxed copy of it.</p>
     *
     * @return a positive ratio, or 0 when the emulator or font metrics are not usable yet
     */
    public static double aspectRatio(@NonNull TerminalEmulator emulator,
                                     int textSizePx, @NonNull Typeface typeface) {
        final ViewportGrid grid = emulatorGrid(emulator, textSizePx, typeface);
        return grid != null ? grid.aspect() : 0;
    }

    /**
     * Width-to-height ratio of the largest glyph grid that fits a terminal viewport.
     *
     * <p>This mirrors {@link com.termux.view.TerminalView#updateSize()}'s column and row
     * calculation, but takes the viewport height explicitly. The overview passes the height the
     * terminal has with the IME hidden, so every card is sized for a normal terminal instead of
     * inheriting the compressed rows of whichever session happens to be active when the keyboard
     * is up.</p>
     *
     * @return a positive ratio, or 0 when the viewport or font metrics are not usable yet
     */
    public static double aspectRatioForViewport(int viewWidth, int viewHeight,
                                                int textSizePx, @NonNull Typeface typeface) {
        final ViewportGrid grid = viewportGrid(viewWidth, viewHeight, textSizePx, typeface);
        return grid != null ? grid.aspect() : 0;
    }

    /**
     * Largest glyph grid that fits a terminal viewport, with the cell size a {@link TerminalView}
     * would report to the pty. Null when the viewport or font metrics are not usable yet.
     */
    @Nullable
    public static ViewportGrid viewportGrid(int viewWidth, int viewHeight,
                                            int textSizePx, @NonNull Typeface typeface) {
        if (viewWidth <= 0 || viewHeight <= 0) return null;
        final TerminalRenderer renderer = new TerminalRenderer(textSizePx, typeface);
        return fromViewport(viewWidth, viewHeight, renderer.mFontWidth,
                renderer.mFontLineSpacing, renderer.mFontLineSpacingAndAscent);
    }

    /** Glyph grid an existing emulator carries, with metrics of the preview font. */
    @Nullable
    public static ViewportGrid emulatorGrid(@NonNull TerminalEmulator emulator,
                                            int textSizePx, @NonNull Typeface typeface) {
        if (emulator.mColumns <= 0 || emulator.mRows <= 0) return null;
        final TerminalRenderer renderer = new TerminalRenderer(textSizePx, typeface);
        return fromGrid(emulator.mColumns, emulator.mRows,
                renderer.mFontWidth, renderer.mFontLineSpacing);
    }

    /**
     * Pure column/row calculation shared by the viewport and emulator entry points. Takes the
     * font metrics directly so it can be tested without an Android {@code Paint}.
     */
    @Nullable
    static ViewportGrid fromViewport(int viewWidth, int viewHeight,
                                     float fontWidth, int fontLineSpacing,
                                     int fontLineSpacingAndAscent) {
        if (viewWidth <= 0 || viewHeight <= 0 || !(fontWidth > 0f)
                || !Float.isFinite(fontWidth) || fontLineSpacing <= 0) {
            return null;
        }
        final int columns = Math.max(MIN_COLUMNS, (int) (viewWidth / fontWidth));
        final int rows = Math.max(MIN_ROWS,
                (int) ((viewHeight - fontLineSpacingAndAscent) / fontLineSpacing));
        return fromGrid(columns, rows, fontWidth, fontLineSpacing);
    }

    @Nullable
    private static ViewportGrid fromGrid(int columns, int rows,
                                         float fontWidth, int fontLineSpacing) {
        if (columns <= 0 || rows <= 0 || !(fontWidth > 0f)
                || !Float.isFinite(fontWidth) || fontLineSpacing <= 0) return null;
        final float gridWidth = columns * fontWidth;
        final float gridHeight = rows * fontLineSpacing;
        if (gridWidth <= 0f || gridHeight <= 0f) return null;
        return new ViewportGrid(columns, rows, (int) fontWidth, fontLineSpacing,
                (double) gridWidth / gridHeight);
    }

    /**
     * Paint {@code emulator} into an opaque card thumbnail.
     *
     * @param target     bitmap to draw into; its size is the card size in pixels
     * @param textSizePx terminal font size in pixels
     * @param typeface   terminal typeface
     * @param topRow     scroll position, 0 or negative; clamped to the live transcript
     */
    public static void render(@NonNull TerminalEmulator emulator, @NonNull Bitmap target,
                              int textSizePx, @NonNull Typeface typeface, int topRow) {
        render(emulator, target, textSizePx, typeface, topRow, 0);
    }

    /**
     * Paint {@code emulator} into {@code target} with an optional transparent background.
     *
     * <p>The overview cards stay opaque. The closing flight uses the terminal's configured
     * transparency so the window background and its blur remain visible while a background
     * session expands back into its live terminal.</p>
     *
     * @param backgroundTransparencyPercent 0 = opaque, 50 = maximum terminal transparency
     */
    public static void render(@NonNull TerminalEmulator emulator, @NonNull Bitmap target,
                              int textSizePx, @NonNull Typeface typeface, int topRow,
                              int backgroundTransparencyPercent) {
        if (target.isRecycled() || target.getWidth() <= 0 || target.getHeight() <= 0) return;
        if (emulator.mColumns <= 0 || emulator.mRows <= 0) return;

        final TerminalRenderer renderer = new TerminalRenderer(textSizePx, typeface);
        renderer.setBackgroundTransparencyPercent(backgroundTransparencyPercent);

        final float gridWidth = emulator.mColumns * renderer.mFontWidth;
        final float gridHeight = emulator.mRows * renderer.mFontLineSpacing;
        if (gridWidth <= 0f || gridHeight <= 0f) return;

        final float scale = Math.min(target.getWidth() / gridWidth,
                target.getHeight() / gridHeight);
        if (scale <= 0f) return;

        final int clampedTopRow = Math.min(0, Math.max(topRow,
                -emulator.getScreen().getActiveTranscriptRows()));

        final Canvas canvas = new Canvas(target);
        // The renderer clears the whole canvas before applying its own translation, so margins
        // left by the fit are already terminal background. Scale first, then give the renderer
        // offsets in unscaled grid coordinates so the grid lands centered in the bitmap.
        canvas.scale(scale, scale);
        final float xOffset = (target.getWidth() / scale - gridWidth) / 2f;
        final float yOffset = (target.getHeight() / scale - gridHeight) / 2f
                - renderer.mFontLineSpacingAndAscent;
        renderer.render(emulator, canvas, clampedTopRow,
                -1, -1, -1, -1,
                xOffset, yOffset, Math.max(1, (int) Math.ceil(target.getWidth() / scale)),
                null, false);
    }
}
