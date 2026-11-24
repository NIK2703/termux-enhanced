package com.termux.view;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The preview card is sized from integer cell metrics, so this arithmetic must stay exact and
 * constant-time: no search, no retry and no rounding of the card size after the fact.
 */
public class TerminalPreviewCellSizingTest {

    @Test
    public void cellsAreIntegersAndTheGridFitsTheSlot() {
        for (int columns : new int[]{24, 40, 80, 120}) {
            for (int rows : new int[]{2, 20, 45, 60}) {
                for (int width : new int[]{320, 523, 1080, 1440}) {
                    for (int height : new int[]{200, 460, 940, 1600}) {
                        final int[] cells = TerminalPreviewRenderer.computeCells(
                                columns, rows, width, height, 13.7f, 29.4f);
                        if (cells == null) continue; // a MIN_CELL guard, covered separately
                        assertTrue("cell width must be a whole pixel", cells[0] > 0);
                        assertTrue("cell height must be a whole pixel", cells[1] > 0);
                        assertTrue("grid wider than the slot",
                                columns * cells[0] <= width);
                        assertTrue("grid taller than the slot",
                                rows * cells[1] <= height);
                    }
                }
            }
        }
    }

    @Test
    public void bothCellsComeFromOneScaleSoTheFontKeepsItsProportions() {
        final int[] cells = TerminalPreviewRenderer.computeCells(
                80, 40, 1000, 1000, 10f, 20f);
        assertArrayEquals(new int[]{12, 25}, cells);
        assertTrue("cell aspect must stay close to the font aspect",
                Math.abs((float) cells[0] / cells[1] - 0.5f) < 0.04f);
    }

    @Test
    public void tinySlotIsRejectedInsteadOfProducingSubPixelCells() {
        assertNull(TerminalPreviewRenderer.computeCells(80, 40, 8, 8, 10f, 20f));
        assertNull(TerminalPreviewRenderer.computeCells(0, 40, 100, 100, 10f, 20f));
        assertNull(TerminalPreviewRenderer.computeCells(80, 40, 100, 100, 0f, 20f));
    }
}
