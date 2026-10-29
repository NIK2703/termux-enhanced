package com.termux.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression tests for ancestor-clipped row damage in {@link TerminalRenderer}.
 *
 * <p>Row damage is requested across the full view width. The session pager can translate a page
 * while a swipe settles (and its elastic spring can leave it a few pixels off-screen), so the
 * framework may hand {@code onDraw} a horizontally clipped canvas. That frame paints only the
 * visible part of the dirty rows; clearing their dirty bits and treating the rest of the surface
 * as valid leaves the clipped-off strips showing the previous frame indefinitely.</p>
 */
public class TerminalRendererClippedDamageTest {

    private static final int WIDTH = 1080;

    @Test
    public void fullWidthRowDamageNeedsNoRecovery() {
        assertFalse(TerminalRenderer.fullWidthDamageWasClipped(
            false, true, 0, WIDTH, WIDTH));
    }

    @Test
    public void fullClipNeedsNoRecovery() {
        assertFalse(TerminalRenderer.fullWidthDamageWasClipped(
            true, true, 0, WIDTH, WIDTH));
    }

    @Test
    public void horizontallyClippedRowDamageNeedsRecovery() {
        assertTrue("pager translated the page left",
            TerminalRenderer.fullWidthDamageWasClipped(false, true, 30, WIDTH, WIDTH));
        assertTrue("pager translated the page right",
            TerminalRenderer.fullWidthDamageWasClipped(false, true, 0, WIDTH - 30, WIDTH));
    }

    @Test
    public void overlayOnlyDamageDoesNotNeedRecovery() {
        // The scrollbar band and the cursor cell are intentionally narrower than the view; they
        // change no row content, so keeping the per-row skip for them is correct.
        assertFalse(TerminalRenderer.fullWidthDamageWasClipped(
            false, false, WIDTH - 30, WIDTH, WIDTH));
        assertFalse(TerminalRenderer.fullWidthDamageWasClipped(
            false, false, 100, 130, WIDTH));
    }
}
