package com.termux.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression tests for batched {@code OP_GLYPH} runs.
 *
 * <p>Stage-5 batching merges adjacent upstream cells into one text run. A block-art run can mix
 * full blocks, half blocks and spaces, and Android may pick fallback glyphs whose advance differs
 * from both the cell width and the space advance. Scaling the whole run as one unit then moves
 * each block away from its cell background, producing a seam at every cell boundary. The run has
 * to fall back to per-code-point clusters whenever its measured width does not match its cells.</p>
 */
public class TerminalRendererGlyphRunClusterTest {

    @Test
    public void mixedBlockAndSpaceRunUsesPerCellClusters() {
        // 8 block glyphs at an 18 px fallback advance plus one space at the 16 px cell advance.
        assertTrue(TerminalRenderer.runNeedsClusterFallback(
            9, 9 * 16f, 8 * 18f + 16f, 16f));
    }

    @Test
    public void blockOnlyRunAlsoFallsBackWhenWiderThanItsCells() {
        assertTrue(TerminalRenderer.runNeedsClusterFallback(
            8, 8 * 16f, 8 * 18f, 16f));
    }

    @Test
    public void exactRunKeepsTheFastWholeRunPath() {
        assertFalse(TerminalRenderer.runNeedsClusterFallback(
            8, 8 * 16f, 8 * 16f, 16f));
    }

    @Test
    public void unusableScaleDoesNotEnterTheClusterPath() {
        // 160 px measured for one 16 px cell is far past MAX_MISMATCH_SCALE.
        assertFalse(TerminalRenderer.runNeedsClusterFallback(
            1, 16f, 160f, 16f));
    }

    @Test
    public void degenerateInputsAreRejected() {
        assertFalse(TerminalRenderer.runNeedsClusterFallback(0, 0f, 16f, 16f));
        assertFalse(TerminalRenderer.runNeedsClusterFallback(1, 16f, 18f, 0f));
    }
}
