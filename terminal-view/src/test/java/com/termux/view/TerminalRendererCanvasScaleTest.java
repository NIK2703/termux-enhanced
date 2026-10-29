package com.termux.view;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Regression tests for the native canvas grid to Java font grid mapping.
 *
 * <p>The upstream foot renderer receives an integer cell width, while
 * {@link TerminalRenderer} lays columns out at the measured (fractional) font width.
 * Pixel-space canvas commands must be scaled by {@code fontWidth / round(fontWidth)}
 * or the error accumulates with every column.</p>
 */
public class TerminalRendererCanvasScaleTest {

    @Test
    public void integerCellWidthNeedsNoScaling() {
        assertEquals(1f, TerminalRenderer.canvasScaleX(8f), 0f);
        assertEquals(1f, TerminalRenderer.canvasScaleX(16f), 0f);
    }

    @Test
    public void fractionalCellWidthMapsToWholePixelGrid() {
        assertEquals(8.4f / 8f, TerminalRenderer.canvasScaleX(8.4f), 0.0001f);
        assertEquals(8.6f / 9f, TerminalRenderer.canvasScaleX(8.6f), 0.0001f);
        assertEquals(14.5f / 15f, TerminalRenderer.canvasScaleX(14.5f), 0.0001f);
    }

    @Test
    public void subPixelFontStillUsesOnePixelCell() {
        assertEquals(0.4f, TerminalRenderer.canvasScaleX(0.4f), 0.0001f);
    }
}
