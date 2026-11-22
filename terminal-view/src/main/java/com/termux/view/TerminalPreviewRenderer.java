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
     * Paint {@code emulator} into {@code target}.
     *
     * @param target     bitmap to draw into; its size is the card size in pixels
     * @param textSizePx terminal font size in pixels
     * @param typeface   terminal typeface
     * @param topRow     scroll position, 0 or negative; clamped to the live transcript
     */
    public static void render(@NonNull TerminalEmulator emulator, @NonNull Bitmap target,
                              int textSizePx, @NonNull Typeface typeface, int topRow) {
        if (target.isRecycled() || target.getWidth() <= 0 || target.getHeight() <= 0) return;
        if (emulator.mColumns <= 0 || emulator.mRows <= 0) return;

        final TerminalRenderer renderer = new TerminalRenderer(textSizePx, typeface);
        renderer.setBackgroundTransparencyPercent(0);

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
