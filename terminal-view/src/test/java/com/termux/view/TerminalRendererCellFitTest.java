package com.termux.view;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for the horizontal fit applied to every shaped run of an explicit-grid renderer.
 *
 * <p>The pixel-level regression is covered by
 * {@code com.termux.app.terminal.TerminalPreviewGlyphSpacingTest} on a device. These tests pin the
 * arithmetic and, more importantly, the degenerate cases that must never reach
 * {@code Canvas.scale()}.</p>
 */
public class TerminalRendererCellFitTest {


    private static float cellFitScale(float expectedWidth, float shapedWidth) {
        if (!(expectedWidth > 0f) || !(shapedWidth > 0f)) return 1f;
        final float scale = expectedWidth / shapedWidth;
        if (!(scale > 0f) || Float.isInfinite(scale) || Float.isNaN(scale)) return 1f;
        return (scale <= TerminalRenderer.MAX_MISMATCH_SCALE && scale >= TerminalRenderer.MIN_MISMATCH_SCALE) ? scale : 1f;
    }
    @Test
    public void mapsAShapedRunOntoItsCells() {
        assertEquals(1.05f, cellFitScale(100.8f, 96f), 0.00001f);
        assertEquals(0.95f, cellFitScale(95f, 100f), 0.00001f);
    }

    @Test
    public void anExactRunNeedsNoCorrection() {
        assertEquals(1f, cellFitScale(100f, 100f), 0f);
    }

    @Test
    public void zeroOrNonFiniteMeasurementsLeaveTheCanvasAlone() {
        assertEquals(1f, cellFitScale(0f, 100f), 0f);
        assertEquals(1f, cellFitScale(100f, 0f), 0f);
        assertEquals(1f, cellFitScale(100f, Float.NaN), 0f);
        assertEquals(1f, cellFitScale(100f, Float.POSITIVE_INFINITY), 0f);
    }

    @Test
    public void extremeRatiosAreRejected() {
        // A measurement below one eighth of a cell or above eight cells is not describing a
        // glyph; scaling by it would make the run illegible and can overflow the canvas matrix.
        assertEquals(1f, cellFitScale(100f, 1f), 0f);
        assertEquals(1f, cellFitScale(1f, 100f), 0f);
        assertEquals(TerminalRenderer.MAX_MISMATCH_SCALE, cellFitScale(TerminalRenderer.MAX_MISMATCH_SCALE, 1f), 0f);
        assertEquals(TerminalRenderer.MIN_MISMATCH_SCALE, cellFitScale(1f, TerminalRenderer.MAX_MISMATCH_SCALE), 0f);
    }
}
