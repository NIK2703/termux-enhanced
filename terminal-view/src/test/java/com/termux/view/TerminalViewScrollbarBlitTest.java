package com.termux.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Policy tests for retained-surface blits. A shortcut may only reuse pixels
 * that are already known-good; selection, elastic over-drag, a missing
 * renderer or a real partial clip must fall back to the normal row path.
 */
public class TerminalViewScrollbarBlitTest {

    @Test
    public void validRetainedPixelsAllowTheShortcut() {
        assertTrue(TerminalView.canBlitScrollbarBand(
            true, false, false, 0, false));
    }

    @Test
    public void invalidPixelsDisableTheShortcut() {
        assertFalse(TerminalView.canBlitScrollbarBand(
            false, false, false, 0, false));
    }

    @Test
    public void selectionDisablesTheShortcut() {
        assertFalse(TerminalView.canBlitScrollbarBand(
            true, true, false, 0, false));
    }

    @Test
    public void scrollbarDragDisablesTheShortcut() {
        assertFalse(TerminalView.canBlitScrollbarBand(
            true, false, true, 0, false));
    }

    @Test
    public void overdragDisablesTheShortcut() {
        assertFalse(TerminalView.canBlitScrollbarBand(
            true, false, false, 1, false));
        assertFalse(TerminalView.canBlitScrollbarBand(
            true, false, false, -1, false));
    }

    @Test
    public void pendingViewportShiftDisablesTheShortcut() {
        assertFalse(TerminalView.canBlitScrollbarBand(
            true, false, false, 0, true));
    }

    @Test
    public void validRetainedPixelsAllowViewportReuse() {
        assertTrue(TerminalView.canReuseViewportSurface(
            true, false, 0, true));
    }

    @Test
    public void invalidPixelsDisableViewportReuse() {
        assertFalse(TerminalView.canReuseViewportSurface(
            false, false, 0, true));
    }

    @Test
    public void selectionDisablesViewportReuse() {
        assertFalse(TerminalView.canReuseViewportSurface(
            true, true, 0, true));
    }

    @Test
    public void overdragDisablesViewportReuse() {
        assertFalse(TerminalView.canReuseViewportSurface(
            true, false, 1, true));
        assertFalse(TerminalView.canReuseViewportSurface(
            true, false, -1, true));
    }

    @Test
    public void missingRendererDisablesViewportReuse() {
        assertFalse(TerminalView.canReuseViewportSurface(
            true, false, 0, false));
    }

    @Test
    public void pendingShiftRequiresFullRepaintForARealPartialClip() {
        assertTrue(TerminalView.pendingViewportShiftRequiresFullRepaint(
            false, false, true));
    }

    @Test
    public void fullViewportCarrierMayConsumePendingShift() {
        assertFalse(TerminalView.pendingViewportShiftRequiresFullRepaint(
            false, true, true));
        assertFalse("ordinary full frames also consume the shift",
            TerminalView.pendingViewportShiftRequiresFullRepaint(
                true, false, true));
    }

    @Test
    public void noPendingShiftNeedsNoPromotion() {
        assertFalse(TerminalView.pendingViewportShiftRequiresFullRepaint(
            false, false, false));
    }
}
