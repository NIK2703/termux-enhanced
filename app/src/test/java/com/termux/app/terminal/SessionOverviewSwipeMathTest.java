package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

/** Thresholds for the overview's horizontal swipe-to-close gesture. */
public class SessionOverviewSwipeMathTest {

    private static final int WIDTH = 1000;
    private static final int MIN_FLING = 50;
    private static final float EPS = 0.0001f;

    @Test
    public void shortSlowDragSpringsBack() {
        Assert.assertFalse(SessionOverviewSwipeMath.shouldDismiss(
                100f, 0f, WIDTH, MIN_FLING));
    }

    @Test
    public void distancePastTheFractionDismissesInEitherDirection() {
        final float threshold = WIDTH * SessionOverviewSwipeMath.DISMISS_FRACTION;
        Assert.assertFalse(SessionOverviewSwipeMath.shouldDismiss(
                threshold - 1f, 0f, WIDTH, MIN_FLING));
        Assert.assertTrue(SessionOverviewSwipeMath.shouldDismiss(
                threshold, 0f, WIDTH, MIN_FLING));
        Assert.assertTrue(SessionOverviewSwipeMath.shouldDismiss(
                -threshold, 0f, WIDTH, MIN_FLING));
    }

    @Test
    public void dismissDistanceThresholdIsInclusiveAndSymmetric() {
        final float threshold = WIDTH * SessionOverviewSwipeMath.DISMISS_FRACTION;
        Assert.assertFalse(SessionOverviewSwipeMath.isPastDismissThreshold(
                threshold - 1f, WIDTH));
        Assert.assertTrue(SessionOverviewSwipeMath.isPastDismissThreshold(
                threshold, WIDTH));
        Assert.assertTrue(SessionOverviewSwipeMath.isPastDismissThreshold(
                -threshold, WIDTH));
        Assert.assertFalse(SessionOverviewSwipeMath.isPastDismissThreshold(
                threshold, 0));
        Assert.assertFalse(SessionOverviewSwipeMath.isPastDismissThreshold(
                Float.NaN, WIDTH));
    }

    @Test
    public void aFlingDismissesEvenBeforeTheDistanceThreshold() {
        Assert.assertTrue(SessionOverviewSwipeMath.shouldDismiss(
                40f, 500f, WIDTH, MIN_FLING));
        Assert.assertTrue(SessionOverviewSwipeMath.shouldDismiss(
                -40f, -500f, WIDTH, MIN_FLING));
    }

    @Test
    public void aFlingBackTowardTheCentreDoesNotDismiss() {
        Assert.assertFalse(SessionOverviewSwipeMath.shouldDismiss(
                40f, -500f, WIDTH, MIN_FLING));
        Assert.assertFalse(SessionOverviewSwipeMath.shouldDismiss(
                -40f, 500f, WIDTH, MIN_FLING));
    }

    @Test
    public void aZeroDragNeverDismissesOnVelocityAlone() {
        Assert.assertFalse(SessionOverviewSwipeMath.shouldDismiss(
                0f, 500f, WIDTH, MIN_FLING));
    }

    @Test
    public void invalidGeometryOrValuesNeverDismiss() {
        Assert.assertFalse(SessionOverviewSwipeMath.shouldDismiss(
                500f, 500f, 0, MIN_FLING));
        Assert.assertFalse(SessionOverviewSwipeMath.shouldDismiss(
                Float.NaN, 500f, WIDTH, MIN_FLING));
        Assert.assertFalse(SessionOverviewSwipeMath.shouldDismiss(
                500f, Float.NaN, WIDTH, MIN_FLING));
    }

    @Test
    public void dragAlphaStaysReadableAndEndsAtTheSameValueOnBothSides() {
        Assert.assertEquals(1f, SessionOverviewSwipeMath.dragAlpha(0f, WIDTH), EPS);
        Assert.assertEquals(1f - SessionOverviewSwipeMath.MAX_DRAG_ALPHA,
                SessionOverviewSwipeMath.dragAlpha(WIDTH, WIDTH), EPS);
        Assert.assertEquals(SessionOverviewSwipeMath.dragAlpha(-WIDTH, WIDTH),
                SessionOverviewSwipeMath.dragAlpha(WIDTH, WIDTH), EPS);
        Assert.assertEquals(1f - SessionOverviewSwipeMath.MAX_DRAG_ALPHA,
                SessionOverviewSwipeMath.dragAlpha(WIDTH * 3f, WIDTH), EPS);
        Assert.assertEquals(1f, SessionOverviewSwipeMath.dragAlpha(100f, 0), EPS);
    }

    @Test
    public void dragAlphaFadesMonotonicallyWithDistanceFromTheSlot() {
        float previous = 1f;
        for (int step = 0; step <= 100; step++) {
            final float alpha = SessionOverviewSwipeMath.dragAlpha(
                    WIDTH * step / 100f, WIDTH);
            Assert.assertTrue(alpha <= previous);
            Assert.assertTrue(alpha >= 0f && alpha <= 1f);
            previous = alpha;
        }
        Assert.assertEquals(0f, SessionOverviewSwipeMath.dragAlpha(WIDTH, WIDTH), EPS);
        Assert.assertEquals(1f, SessionOverviewSwipeMath.dragAlpha(0f, WIDTH), EPS);
    }

    @Test
    public void directionFollowsTheDragSign() {
        Assert.assertEquals(-1, SessionOverviewSwipeMath.dismissDirection(-1f));
        Assert.assertEquals(1, SessionOverviewSwipeMath.dismissDirection(0f));
        Assert.assertEquals(1, SessionOverviewSwipeMath.dismissDirection(1f));
    }
}
