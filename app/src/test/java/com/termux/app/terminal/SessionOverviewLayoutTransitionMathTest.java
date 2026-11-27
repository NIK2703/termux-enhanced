package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

/** Pure timing, offset composition and p=0/p=1 invariants for the structural transition. */
public class SessionOverviewLayoutTransitionMathTest {

    @Test
    public void cardOnlyMovementUsesTheAppearanceDuration() {
        Assert.assertEquals(160L, SessionOverviewLayoutTransitionMath.durationMs(false));
    }

    @Test
    public void anyScrollParticipationUsesTheScrollDuration() {
        Assert.assertEquals(250L, SessionOverviewLayoutTransitionMath.durationMs(true));
    }

    @Test
    public void remainingIsExactAtBothEndsAndClampedOutside() {
        Assert.assertEquals(42f, SessionOverviewLayoutTransitionMath.remaining(42f, 0f), 0f);
        Assert.assertEquals(0f, SessionOverviewLayoutTransitionMath.remaining(42f, 1f), 0f);
        Assert.assertEquals(42f, SessionOverviewLayoutTransitionMath.remaining(42f, -1f), 0f);
        Assert.assertEquals(0f, SessionOverviewLayoutTransitionMath.remaining(42f, 2f), 0f);
        Assert.assertEquals(21f, SessionOverviewLayoutTransitionMath.remaining(42f, 0.5f), 0f);
    }

    @Test
    public void offsetAfterClampComposesSeveralClamps() {
        int offset = 0;
        offset = SessionOverviewLayoutTransitionMath.offsetAfterClamp(offset, 900, 700);
        offset = SessionOverviewLayoutTransitionMath.offsetAfterClamp(offset, 700, 400);
        Assert.assertEquals(-500, offset);
        Assert.assertEquals(0, SessionOverviewLayoutTransitionMath.offsetAfterClamp(0, 50, 50));
    }

    @Test
    public void clampHandlesEmptyAndInvertedRanges() {
        Assert.assertEquals(5, SessionOverviewLayoutTransitionMath.clamp(5, 0, 10));
        Assert.assertEquals(0, SessionOverviewLayoutTransitionMath.clamp(-1, 0, 10));
        Assert.assertEquals(10, SessionOverviewLayoutTransitionMath.clamp(11, 0, 10));
        Assert.assertEquals(0, SessionOverviewLayoutTransitionMath.clamp(5, 0, -1));
    }

    @Test
    public void motionIsDetectedFromCardsOrScroll() {
        Assert.assertFalse(SessionOverviewLayoutTransitionMath.hasMotion(null, 0f));
        Assert.assertFalse(SessionOverviewLayoutTransitionMath.hasMotion(new float[]{0f, 0f}, 0f));
        Assert.assertTrue(SessionOverviewLayoutTransitionMath.hasMotion(new float[]{0f, 1f}, 0f));
        Assert.assertTrue(SessionOverviewLayoutTransitionMath.hasMotion(new float[0], -1f));
    }

    @Test
    public void arbitraryGridChangeMatchesTheOldFrameAtZeroAndTheNewSlotAtOne() {
        final float[] fromX = {10f, 120f, 250f, 400f, 640f};
        final float[] fromY = {20f, 40f, 300f, 500f, 700f};
        final float[] toX = {30f, 90f, 300f, 350f, 700f};
        final float[] toY = {80f, 100f, 200f, 600f, 650f};
        final int oldScroll = 180;
        final int targetScroll = 420;
        final int previousOffset = -35;
        final int offset = SessionOverviewLayoutTransitionMath.offsetAfterClamp(
                previousOffset, oldScroll, targetScroll);

        for (int i = 0; i < fromX.length; i++) {
            final float translationX = fromX[i] - toX[i];
            final float translationY = fromY[i] - toY[i];
            final float screenXAtZero = toX[i] + translationX - targetScroll + offset;
            final float screenYAtZero = toY[i] + translationY - targetScroll + offset;
            Assert.assertEquals(fromX[i] - oldScroll + previousOffset, screenXAtZero, 0.001f);
            Assert.assertEquals(fromY[i] - oldScroll + previousOffset, screenYAtZero, 0.001f);

            final float screenXAtOne = toX[i] + SessionOverviewLayoutTransitionMath.remaining(
                    translationX, 1f) - targetScroll;
            final float screenYAtOne = toY[i] + SessionOverviewLayoutTransitionMath.remaining(
                    translationY, 1f) - targetScroll;
            Assert.assertEquals(toX[i] - targetScroll, screenXAtOne, 0.001f);
            Assert.assertEquals(toY[i] - targetScroll, screenYAtOne, 0.001f);
        }
    }
}
