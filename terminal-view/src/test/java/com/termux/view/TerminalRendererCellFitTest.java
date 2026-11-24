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

    @Test
    public void mapsAShapedRunOntoItsCells() {
        assertEquals(1.05f, TerminalRenderer.cellFitScale(100.8f, 96f), 0.00001f);
        assertEquals(0.95f, TerminalRenderer.cellFitScale(95f, 100f), 0.00001f);
    }

    @Test
    public void anExactRunNeedsNoCorrection() {
        assertEquals(1f, TerminalRenderer.cellFitScale(100f, 100f), 0f);
    }

    @Test
    public void zeroOrNonFiniteMeasurementsLeaveTheCanvasAlone() {
        assertEquals(1f, TerminalRenderer.cellFitScale(0f, 100f), 0f);
        assertEquals(1f, TerminalRenderer.cellFitScale(100f, 0f), 0f);
        assertEquals(1f, TerminalRenderer.cellFitScale(100f, Float.NaN), 0f);
        assertEquals(1f, TerminalRenderer.cellFitScale(100f, Float.POSITIVE_INFINITY), 0f);
    }

    @Test
    public void extremeRatiosAreRejected() {
        // A measurement below one eighth of a cell or above eight cells is not describing a
        // glyph; scaling by it would make the run illegible and can overflow the canvas matrix.
        assertEquals(1f, TerminalRenderer.cellFitScale(100f, 1f), 0f);
        assertEquals(1f, TerminalRenderer.cellFitScale(1f, 100f), 0f);
        assertEquals(8f, TerminalRenderer.cellFitScale(8f, 1f), 0f);
        assertEquals(0.125f, TerminalRenderer.cellFitScale(1f, 8f), 0f);
    }
}
