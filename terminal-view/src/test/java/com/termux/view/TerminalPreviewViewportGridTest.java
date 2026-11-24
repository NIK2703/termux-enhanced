package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the terminal grid the overview publishes to sessions.
 *
 * <p>The arithmetic is exercised through the metric seam so it stays independent of an Android
 * Paint. These are the exact floor operations {@code TerminalView.updateSize()} uses for the
 * columns, rows and pty cell size; a drift between the two is what leaves an overview thumbnail
 * rendered at a smaller grid than its card.</p>
 */
public class TerminalPreviewViewportGridTest {

    @Test
    public void mirrorsTerminalViewFloorFormula() {
        final TerminalPreviewRenderer.ViewportGrid grid =
                TerminalPreviewRenderer.fromViewport(815, 925, 10f, 20, 25);
        assertNotNull(grid);
        assertEquals(81, grid.columns);
        assertEquals(45, grid.rows);
        assertEquals(10, grid.cellWidthPx);
        assertEquals(20, grid.cellHeightPx);
        assertEquals(810d / 900d, grid.aspect(), 0.0000001d);
    }

    @Test
    public void fractionalCellWidthIsTruncatedOnlyForThePty() {
        final TerminalPreviewRenderer.ViewportGrid grid =
                TerminalPreviewRenderer.fromViewport(100, 100, 8.4f, 20, 25);
        assertNotNull(grid);
        assertEquals(11, grid.columns);
        assertEquals(4, grid.rows); // three rows fit, but the four-row floor applies
        // The renderer paints with 8.4 px cells, so the aspect must keep the fraction...
        assertEquals(11 * 8.4d / (4 * 20d), grid.aspect(), 0.0000001d);
        // ...while the pty is told the same integer width TerminalView.updateSize() reports.
        assertEquals(8, grid.cellWidthPx);
    }

    @Test
    public void clampsToFourCellsForATinyViewport() {
        final TerminalPreviewRenderer.ViewportGrid grid =
                TerminalPreviewRenderer.fromViewport(3, 3, 10f, 20, 25);
        assertNotNull(grid);
        assertEquals(4, grid.columns);
        assertEquals(4, grid.rows);
    }

    @Test
    public void compressedHeightHasFewerRowsAndAWiderAspect() {
        final TerminalPreviewRenderer.ViewportGrid normal =
                TerminalPreviewRenderer.fromViewport(800, 900, 10f, 20, 25);
        final TerminalPreviewRenderer.ViewportGrid ime =
                TerminalPreviewRenderer.fromViewport(800, 600, 10f, 20, 25);
        assertNotNull(normal);
        assertNotNull(ime);
        assertEquals(normal.columns, ime.columns);
        assertTrue(ime.rows < normal.rows);
        assertTrue(ime.aspect() > normal.aspect());
    }

    @Test
    public void carriesThePtyCellSizeAlongsideColumnsAndRows() {
        final TerminalPreviewRenderer.ViewportGrid grid =
                TerminalPreviewRenderer.fromViewport(800, 900, 10f, 20, 25);
        assertNotNull(grid);
        assertEquals(80, grid.columns);
        assertEquals(43, grid.rows);
        assertEquals(10, grid.cellWidthPx);
        assertEquals(20, grid.cellHeightPx);
    }

    @Test
    public void unusableMetricsAreRejected() {
        assertNull(TerminalPreviewRenderer.fromViewport(0, 900, 10f, 20, 25));
        assertNull(TerminalPreviewRenderer.fromViewport(800, 0, 10f, 20, 25));
        assertNull(TerminalPreviewRenderer.fromViewport(800, 900, 0f, 20, 25));
        assertNull(TerminalPreviewRenderer.fromViewport(800, 900, 10f, 0, 25));
        assertNull(TerminalPreviewRenderer.fromViewport(800, 900, Float.NaN, 20, 25));
    }
}
