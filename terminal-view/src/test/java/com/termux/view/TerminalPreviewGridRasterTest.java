package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The preview raster uses the font's natural metrics and an exact ceil-sized bitmap.
 *
 * <p>The fit is a direct estimate plus a constant number of proportional corrections. These tests
 * use a metrics seam so they can prove the invariants without a device: the bitmap fits the slot,
 * the stored pitch is the natural measured advance rather than a floored cell, and the number of
 * metric probes stays bounded.</p>
 */
public class TerminalPreviewGridRasterTest {

    @Test
    public void rasterUsesNaturalAdvanceAndTheLiveGridRowPitch() {
        final LinearMetrics source = new LinearMetrics();
        final TerminalPreviewRenderer.GridRaster raster =
                TerminalPreviewRenderer.fitGridRaster(80, 40, 32, null, 1000, 1000,
                        source, 32f * LinearMetrics.ADVANCE_RATIO,
                        (int) Math.ceil(32f * LinearMetrics.LINE_RATIO));
        assertNotNull(raster);
        assertTrue(raster.widthFor(80) <= 1000);
        assertTrue(raster.heightFor(40) <= 1000);
        // The cell width is the measured natural advance at the fitted size, not a rounded cell.
        assertEquals(raster.textSizePx * LinearMetrics.ADVANCE_RATIO,
                raster.cellWidth, 0.0001f);
        // The row pitch follows the live renderer's ceil(getFontSpacing()), not the raw spacing.
        assertEquals(raster.cellWidth * (39f / 19.2f), raster.lineHeight, 0.0001f);
        assertEquals((int) Math.ceil(80 * (double) raster.cellWidth), raster.widthFor(80));
        assertEquals((int) Math.ceil(40 * (double) raster.lineHeight), raster.heightFor(40));
    }

    @Test
    public void cardAspectMatchesTheLiveGlyphGrid() {
        final LinearMetrics source = new LinearMetrics();
        final TerminalPreviewRenderer.GridRaster raster =
                TerminalPreviewRenderer.fitGridRaster(80, 40, 32, null, 1000, 1000,
                        source, 32f * LinearMetrics.ADVANCE_RATIO,
                        (int) Math.ceil(32f * LinearMetrics.LINE_RATIO));
        assertNotNull(raster);
        // The live grid is 80 * 19.2 wide by 40 * 39 high. The card bitmap must describe the
        // same ratio, otherwise the width-filled flight snapshot is clipped at the hand-off.
        final double liveAspect = (80d * 19.2d) / (40d * 39d);
        final double cardAspect = (double) raster.widthFor(80) / raster.heightFor(40);
        assertEquals(liveAspect, cardAspect, 0.001d);
    }

    @Test
    public void fitUsesABoundedNumberOfMetricProbes() {
        final LinearMetrics source = new LinearMetrics();
        final TerminalPreviewRenderer.GridRaster raster =
                TerminalPreviewRenderer.fitGridRaster(80, 40, 32, null, 1000, 1000,
                        source, 32f * LinearMetrics.ADVANCE_RATIO,
                        (int) Math.ceil(32f * LinearMetrics.LINE_RATIO));
        assertNotNull(raster);
        // One probe at the preferred size, one at the estimate, and at most a few corrections.
        assertTrue("unexpected search: " + source.calls, source.calls <= 4);
    }

    @Test
    public void hintingCorrectionStillFitsAndKeepsTheLiveCellAspect() {
        final HintedMetrics source = new HintedMetrics();
        final float referenceAdvance = source.advanceAt(32f);
        final int referenceLineSpacing = (int) Math.ceil(32f * 1.2f);
        final TerminalPreviewRenderer.GridRaster raster =
                TerminalPreviewRenderer.fitGridRaster(80, 40, 32, null, 1000, 1000,
                        source, referenceAdvance, referenceLineSpacing);
        assertNotNull(raster);
        assertTrue(raster.widthFor(80) <= 1000);
        assertTrue(raster.heightFor(40) <= 1000);
        assertEquals(source.advanceAt(raster.textSizePx), raster.cellWidth, 0.0001f);
        assertEquals(raster.cellWidth * referenceLineSpacing / referenceAdvance,
                raster.lineHeight, 0.0001f);
        assertTrue("hinting correction should need another probe", source.calls >= 3);
        assertTrue("correction must stay bounded: " + source.calls, source.calls <= 5);
    }

    @Test
    public void widthForCeilsTheNaturalGrid() {
        final TerminalPreviewRenderer.GridRaster raster =
                new TerminalPreviewRenderer.GridRaster(null, 10f, 7.31f, 15.71f, -8f);
        assertEquals(74, raster.widthFor(10));   // ceil(73.1)
        assertEquals(158, raster.heightFor(10)); // ceil(157.1)
        assertEquals(0, raster.widthFor(0));
        assertEquals(0, raster.heightFor(-1));
    }

    @Test
    public void unusableInputsReturnNull() {
        assertNull(TerminalPreviewRenderer.fitGridRaster(0, 40, 32, null, 100, 100,
                new LinearMetrics(), 19.2f, 39));
        assertNull(TerminalPreviewRenderer.fitGridRaster(80, 40, 32, null, 0, 100,
                new LinearMetrics(), 19.2f, 39));
        assertNull(TerminalPreviewRenderer.fitGridRaster(80, 40, 32, null, 100, 100,
                new BrokenMetrics(), 19.2f, 39));
        assertNull(TerminalPreviewRenderer.fitGridRaster(80, 40, 32, null, 100, 100,
                new LinearMetrics(), 0f, 39));
        assertNull(TerminalPreviewRenderer.fitGridRaster(80, 40, 32, null, 100, 100,
                new LinearMetrics(), 19.2f, 0));
    }

    private static final class LinearMetrics implements TerminalPreviewRenderer.MetricsSource {
        static final float ADVANCE_RATIO = 0.6f;
        static final float LINE_RATIO = 1.2f;
        int calls;

        @Override
        public TerminalPreviewRenderer.RasterMetrics measure(float textSizePx) {
            calls++;
            return new TerminalPreviewRenderer.RasterMetrics(
                    textSizePx * ADVANCE_RATIO,
                    textSizePx * LINE_RATIO,
                    -textSizePx * 0.9f);
        }
    }

    private static final class HintedMetrics implements TerminalPreviewRenderer.MetricsSource {
        int calls;

        float advanceAt(float textSizePx) {
            // A step at 18 px simulates hinting: the advance is not perfectly linear in size.
            return textSizePx * 0.6f + (textSizePx > 18f ? 1.5f : 0f);
        }

        @Override
        public TerminalPreviewRenderer.RasterMetrics measure(float textSizePx) {
            calls++;
            return new TerminalPreviewRenderer.RasterMetrics(
                    advanceAt(textSizePx), textSizePx * 1.2f, -textSizePx * 0.9f);
        }
    }

    private static final class BrokenMetrics implements TerminalPreviewRenderer.MetricsSource {
        @Override
        public TerminalPreviewRenderer.RasterMetrics measure(float textSizePx) {
            return new TerminalPreviewRenderer.RasterMetrics(0f, 0f, 0f);
        }
    }
}
