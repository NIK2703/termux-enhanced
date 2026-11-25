package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

/** Deterministic slot, auto-scroll and drop-order maths for the overview drag. */
public class SessionOverviewReorderMathTest {

    @Test
    public void nearestSlotWinsAndTiesKeepTheCurrentSlot() {
        float[] xs = {0f, 100f, 0f, 100f};
        float[] ys = {0f, 0f, 100f, 100f};

        Assert.assertEquals(0, SessionOverviewReorderMath.resolveTargetIndex(
                10f, 10f, xs, ys, 0));
        Assert.assertEquals(1, SessionOverviewReorderMath.resolveTargetIndex(
                90f, 10f, xs, ys, 0));
        Assert.assertEquals(2, SessionOverviewReorderMath.resolveTargetIndex(
                10f, 90f, xs, ys, 0));
        Assert.assertEquals(3, SessionOverviewReorderMath.resolveTargetIndex(
                90f, 90f, xs, ys, 0));

        // Exactly between slot 0 and slot 1: the previous target must not flicker.
        Assert.assertEquals(0, SessionOverviewReorderMath.resolveTargetIndex(
                50f, 0f, xs, ys, 0));
        Assert.assertEquals(1, SessionOverviewReorderMath.resolveTargetIndex(
                50f, 0f, xs, ys, 1));
    }

    @Test
    public void invalidGeometryReturnsMinusOneOrKeepsAValidCurrentSlot() {
        Assert.assertEquals(-1, SessionOverviewReorderMath.resolveTargetIndex(
                0f, 0f, new float[0], new float[0], 0));

        float[] xs = {10f};
        float[] ys = {20f};
        Assert.assertEquals(0, SessionOverviewReorderMath.resolveTargetIndex(
                100f, 100f, xs, ys, 99));
    }

    @Test
    public void dragKeepsTheInitialOffsetInsteadOfSnappingToTheFinger() {
        Assert.assertEquals(0f,
                SessionOverviewReorderMath.dragTranslation(0f, 100f, 100f), 0.001f);
        Assert.assertEquals(30f,
                SessionOverviewReorderMath.dragTranslation(0f, 100f, 130f), 0.001f);
        Assert.assertEquals(-10f,
                SessionOverviewReorderMath.dragTranslation(0f, 100f, 90f), 0.001f);
        Assert.assertEquals(27f,
                SessionOverviewReorderMath.dragTranslation(7f, 100f, 120f), 0.001f);
    }

    @Test
    public void autoScrollPullsTowardTheNearestEdge() {
        Assert.assertEquals(-10, SessionOverviewReorderMath.autoScrollStep(
                0f, 1000, 100, 10, 1f));
        Assert.assertEquals(-5, SessionOverviewReorderMath.autoScrollStep(
                50f, 1000, 100, 10, 1f));
        Assert.assertEquals(0, SessionOverviewReorderMath.autoScrollStep(
                500f, 1000, 100, 10, 1f));
        Assert.assertEquals(5, SessionOverviewReorderMath.autoScrollStep(
                950f, 1000, 100, 10, 1f));
        Assert.assertEquals(10, SessionOverviewReorderMath.autoScrollStep(
                1000f, 1000, 100, 10, 1f));

        // Overshooting the edge must saturate, not grow without bound.
        Assert.assertEquals(-10, SessionOverviewReorderMath.autoScrollStep(
                -100f, 1000, 100, 10, 1f));
        Assert.assertEquals(10, SessionOverviewReorderMath.autoScrollStep(
                1100f, 1000, 100, 10, 1f));
    }

    @Test
    public void autoScrollDisablesItselfForInvalidGeometry() {
        Assert.assertEquals(0, SessionOverviewReorderMath.autoScrollStep(
                0f, 0, 100, 10, 1f));
        Assert.assertEquals(0, SessionOverviewReorderMath.autoScrollStep(
                0f, 1000, 0, 10, 1f));
        Assert.assertEquals(0, SessionOverviewReorderMath.autoScrollStep(
                0f, 1000, 100, 0, 1f));
    }

    @Test
    public void dropRemovesTheDraggedChildAndInsertsItAtTheTarget() {
        Assert.assertArrayEquals(new int[]{1, 2, 0},
                SessionOverviewReorderMath.orderAfterMove(3, 0, 2));
        Assert.assertArrayEquals(new int[]{1, 2, 3, 0},
                SessionOverviewReorderMath.orderAfterMove(4, 0, 3));
        Assert.assertArrayEquals(new int[]{3, 0, 1, 2},
                SessionOverviewReorderMath.orderAfterMove(4, 3, 0));
        Assert.assertArrayEquals(new int[]{0, 2, 3, 1},
                SessionOverviewReorderMath.orderAfterMove(4, 1, 3));
        Assert.assertArrayEquals(new int[]{0, 1, 2, 3},
                SessionOverviewReorderMath.orderAfterMove(4, 2, 2));
    }

    @Test
    public void dropOrderMatchesOneModelMoveForEveryPair() {
        for (int count = 1; count <= 6; count++) {
            final int[] initialOrder = new int[count];
            for (int i = 0; i < count; i++) initialOrder[i] = i;
            for (int from = 0; from < count; from++) {
                for (int to = 0; to < count; to++) {
                    final int[] order = SessionOverviewReorderMath.orderAfterMove(
                            count, from, to);
                    Assert.assertNotNull("count=" + count + " from=" + from + " to=" + to, order);
                    Assert.assertArrayEquals(
                            "count=" + count + " from=" + from + " to=" + to,
                            applyMove(initialOrder, from, to), order);
                }
            }
        }
    }

    @Test
    public void dropOrderRejectsMissingOrOutOfRangeChildren() {
        Assert.assertNull(SessionOverviewReorderMath.orderAfterMove(0, 0, 0));
        Assert.assertNull(SessionOverviewReorderMath.orderAfterMove(3, -1, 1));
        Assert.assertNull(SessionOverviewReorderMath.orderAfterMove(3, 3, 1));
        Assert.assertNull(SessionOverviewReorderMath.orderAfterMove(3, 1, -1));
        Assert.assertNull(SessionOverviewReorderMath.orderAfterMove(3, 1, 3));
    }

    /**
     * Applies the model operation this order must represent: move the item at {@code from} to
     * {@code to}, then return the values in their new positions.
     */
    private static int[] applyMove(int[] initialOrder, int from, int to) {
        final int[] values = initialOrder.clone();
        if (from == to) return values;
        final int moved = values[from];
        if (from < to) {
            System.arraycopy(values, from + 1, values, from, to - from);
        } else {
            System.arraycopy(values, to, values, to + 1, from - to);
        }
        values[to] = moved;
        return values;
    }
}
