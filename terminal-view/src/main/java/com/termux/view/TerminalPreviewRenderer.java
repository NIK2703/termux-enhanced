package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;

import androidx.annotation.NonNull;

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

    private TerminalPreviewRenderer() {}

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
        if (emulator.mColumns <= 0 || emulator.mRows <= 0) return 0;
        final TerminalRenderer renderer = new TerminalRenderer(textSizePx, typeface);
        return gridAspect(emulator.mColumns, emulator.mRows, renderer);
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
        if (viewWidth <= 0 || viewHeight <= 0) return 0;
        final TerminalRenderer renderer = new TerminalRenderer(textSizePx, typeface);
        if (renderer.mFontWidth <= 0f || renderer.mFontLineSpacing <= 0f) return 0;
        final int columns = Math.max(4, (int) (viewWidth / renderer.mFontWidth));
        final int rows = Math.max(4,
                (int) ((viewHeight - renderer.mFontLineSpacingAndAscent)
                        / renderer.mFontLineSpacing));
        return gridAspect(columns, rows, renderer);
    }

    private static double gridAspect(int columns, int rows,
                                     @NonNull TerminalRenderer renderer) {
        final float gridWidth = columns * renderer.mFontWidth;
        final float gridHeight = rows * renderer.mFontLineSpacing;
        if (gridWidth <= 0f || gridHeight <= 0f) return 0;
        return (double) gridWidth / gridHeight;
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
