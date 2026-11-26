package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

/** Phase math for the newly added tab's target-slot reveal. */
public class SessionOverviewNewTabArrivalMathTest {

    private static final float EPS = 0.0001f;

    @Test
    public void revealScaleStartsSubtleAndEndsExactlyAtTarget() {
        Assert.assertEquals(SessionOverviewTransitionMath.NEW_TAB_REVEAL_SCALE,
                SessionOverviewTransitionMath.newTabRevealScale(0f), EPS);
        Assert.assertEquals(SessionOverviewTransitionMath.NEW_TAB_REVEAL_SCALE,
                SessionOverviewTransitionMath.newTabRevealScale(
                        SessionOverviewTransitionMath.TAB_FADE_IN_START), EPS);
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.newTabRevealScale(1f), EPS);
    }

    @Test
    public void revealScaleIsMonotonic() {
        float previous = SessionOverviewTransitionMath.newTabRevealScale(0f);
        for (float p = 0.05f; p <= 1f; p += 0.05f) {
            final float current = SessionOverviewTransitionMath.newTabRevealScale(p);
            Assert.assertTrue(current >= previous);
            previous = current;
        }
    }

    @Test
    public void revealScaleIsClampedAndNanSafe() {
        Assert.assertEquals(SessionOverviewTransitionMath.NEW_TAB_REVEAL_SCALE,
                SessionOverviewTransitionMath.newTabRevealScale(-1f), EPS);
        Assert.assertEquals(1f, SessionOverviewTransitionMath.newTabRevealScale(2f), EPS);
        Assert.assertEquals(SessionOverviewTransitionMath.NEW_TAB_REVEAL_SCALE,
                SessionOverviewTransitionMath.newTabRevealScale(Float.NaN), EPS);
    }
}
