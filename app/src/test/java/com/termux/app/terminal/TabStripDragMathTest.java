package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

/**
 * Drag geometry: slot resolution with unequal widths and tie stability, neighbour shifts and
 * auto-scroll.
 */
public class TabStripDragMathTest {

    private static final int[] NO_MARGINS = {0, 0, 0, 0};

    @Test
    public void resolvesEqualWidthSlotsAroundTheDraggedTab() {
        int[] lefts = {0, 120, 240};
        int[] widths = {120, 120, 120};

        // Resting position of the middle tab: its own slot.
        Assert.assertEquals(1, resolve(1, 1, 180, lefts, widths, 120));
        Assert.assertEquals(1, resolve(1, 1, 200, lefts, widths, 120));
        Assert.assertEquals(2, resolve(1, 1, 250, lefts, widths, 120));
        Assert.assertEquals(0, resolve(1, 1, 100, lefts, widths, 120));
    }

    @Test
    public void wideDraggedTabDoesNotJumpPastANarrowNeighbourAtRest() {
        // A 100px tab followed by a 20px tab. In the original layout the small tab's centre
        // is to the right of the dragged tab, but in the "dragged removed" layout it slides
        // under it. The no-move case must still resolve to the original slot.
        int[] lefts = {0, 100, 120};
        int[] widths = {100, 20, 80};

        Assert.assertEquals(0, resolve(0, 0, 50, lefts, widths, 100));
        // Slot centres are 50, 70, 150. Crossing 60 selects slot 1; 58 stays at slot 0.
        Assert.assertEquals(1, resolve(0, 0, 62, lefts, widths, 100));
        Assert.assertEquals(0, resolve(0, 1, 58, lefts, widths, 100));
        Assert.assertEquals(1, resolve(0, 1, 80, lefts, widths, 100));
        Assert.assertEquals(2, resolve(0, 1, 120, lefts, widths, 100));
    }

    @Test
    public void movesLeftFromTheLastSlot() {
        int[] lefts = {0, 80, 100};
        int[] widths = {80, 20, 100};

        // Slot centres when the last 100px tab is removed: 50, 130, 150.
        Assert.assertEquals(2, resolve(2, 2, 150, lefts, widths, 100));
        Assert.assertEquals(2, resolve(2, 2, 140, lefts, widths, 100)); // exact tie
        Assert.assertEquals(1, resolve(2, 2, 139, lefts, widths, 100));
        Assert.assertEquals(1, resolve(2, 1, 90, lefts, widths, 100));  // exact tie
        Assert.assertEquals(0, resolve(2, 1, 89, lefts, widths, 100));
    }

    @Test
    public void currentSlotWinsExactTies() {
        int[] lefts = {0, 120, 240};
        int[] widths = {120, 120, 120};

        // 120 is exactly between slot 0 (60) and slot 1 (180).
        Assert.assertEquals(0, resolve(1, 0, 120, lefts, widths, 120));
        Assert.assertEquals(1, resolve(1, 1, 120, lefts, widths, 120));
    }

    @Test
    public void neighbourShiftMovesTabsByTheDraggedOccupiedWidth() {
        Assert.assertEquals(0f, TabStripDragMath.neighborShift(0, 0, 2, 100), 0f);
        Assert.assertEquals(-100f, TabStripDragMath.neighborShift(1, 0, 2, 100), 0f);
        Assert.assertEquals(-100f, TabStripDragMath.neighborShift(2, 0, 2, 100), 0f);
        Assert.assertEquals(0f, TabStripDragMath.neighborShift(3, 0, 2, 100), 0f);

        Assert.assertEquals(100f, TabStripDragMath.neighborShift(0, 2, 0, 100), 0f);
        Assert.assertEquals(100f, TabStripDragMath.neighborShift(1, 2, 0, 100), 0f);
        Assert.assertEquals(0f, TabStripDragMath.neighborShift(2, 2, 0, 100), 0f);
        Assert.assertEquals(0f, TabStripDragMath.neighborShift(3, 2, 0, 100), 0f);

        Assert.assertEquals(0f, TabStripDragMath.neighborShift(2, 1, 1, 100), 0f);
        Assert.assertEquals(-100f, TabStripDragMath.neighborShift(2, 1, 3, 100), 0f);
        Assert.assertEquals(-100f, TabStripDragMath.neighborShift(3, 1, 3, 100), 0f);
    }

    @Test
    public void accountsForTheDraggedTabsEndMarginAtEverySlot() {
        // Real tab layout: 100px view + 4px end margin between neighbours.
        int[] lefts = {4, 108, 212};
        int[] widths = {100, 100, 100};
        int[] marginStarts = {0, 0, 0};
        int[] marginEnds = {4, 4, 4};

        // Slot centres for dragging the middle tab (occupied width 104) are 54, 158, 262.
        Assert.assertEquals(1, TabStripDragMath.resolveTargetIndex(1, 1, 158,
                lefts, widths, marginStarts, marginEnds, 104, 0, 4));
        Assert.assertEquals(1, TabStripDragMath.resolveTargetIndex(1, 1, 106,
                lefts, widths, marginStarts, marginEnds, 104, 0, 4)); // exact tie
        Assert.assertEquals(0, TabStripDragMath.resolveTargetIndex(1, 1, 105,
                lefts, widths, marginStarts, marginEnds, 104, 0, 4));
        Assert.assertEquals(1, TabStripDragMath.resolveTargetIndex(1, 1, 210,
                lefts, widths, marginStarts, marginEnds, 104, 0, 4)); // exact tie
        Assert.assertEquals(2, TabStripDragMath.resolveTargetIndex(1, 1, 211,
                lefts, widths, marginStarts, marginEnds, 104, 0, 4));
    }

    @Test
    public void autoScrollRampsTowardsTheEdges() {
        final int width = 1000;
        final int edge = 100;
        final int maxStep = 10;

        Assert.assertEquals(0, TabStripDragMath.autoScrollStep(500, width, edge, maxStep, false));
        Assert.assertEquals(-5, TabStripDragMath.autoScrollStep(50, width, edge, maxStep, false));
        Assert.assertEquals(-10, TabStripDragMath.autoScrollStep(0, width, edge, maxStep, false));
        Assert.assertEquals(5, TabStripDragMath.autoScrollStep(950, width, edge, maxStep, false));
        Assert.assertEquals(10, TabStripDragMath.autoScrollStep(1000, width, edge, maxStep, false));
    }

    @Test
    public void autoScrollIsInvertedForRtlAndClampedForNarrowViewports() {
        // RTL: the left visual edge needs a positive scroll step.
        Assert.assertEquals(5, TabStripDragMath.autoScrollStep(50, 1000, 100, 10, true));
        Assert.assertEquals(-10, TabStripDragMath.autoScrollStep(1000, 1000, 100, 10, true));

        // The edge zone never exceeds a quarter of the viewport (200 / 4 = 50).
        Assert.assertEquals(-8, TabStripDragMath.autoScrollStep(10, 200, 100, 10, false));

        // Fingers past the viewport clamp to the maximum step instead of returning 0.
        Assert.assertEquals(-10, TabStripDragMath.autoScrollStep(-50, 1000, 100, 10, false));
        Assert.assertEquals(10, TabStripDragMath.autoScrollStep(1200, 1000, 100, 10, false));
    }

    private static int resolve(int from, int currentTarget, float center,
                               int[] lefts, int[] widths, int draggedWidth) {
        return TabStripDragMath.resolveTargetIndex(from, currentTarget, center,
                lefts, widths, NO_MARGINS, NO_MARGINS, draggedWidth, 0, 0);
    }
}
