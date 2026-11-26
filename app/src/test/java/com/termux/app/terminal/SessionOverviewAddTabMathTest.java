package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

/** Phase math for the add-session flight. */
public class SessionOverviewAddTabMathTest {

    private static final float EPS = 0.0001f;

    @Test
    public void flightDurationMatchesOverviewTransitions() {
        Assert.assertEquals(SessionOverviewTransitionMath.DURATION_MS,
                SessionOverviewAddTabMath.FLIGHT_DURATION_MS);
    }

    @Test
    public void plusAlphaFadesOutEarlyAndIsMonotonic() {
        Assert.assertEquals(1f, SessionOverviewAddTabMath.plusAlpha(0f), EPS);
        Assert.assertEquals(1f, SessionOverviewAddTabMath.plusAlpha(-1f), EPS);
        Assert.assertEquals(0.5f, SessionOverviewAddTabMath.plusAlpha(
                SessionOverviewAddTabMath.PLUS_FADE_END / 2f), EPS);
        Assert.assertEquals(0f, SessionOverviewAddTabMath.plusAlpha(
                SessionOverviewAddTabMath.PLUS_FADE_END), EPS);
        Assert.assertEquals(0f, SessionOverviewAddTabMath.plusAlpha(1f), EPS);
        for (float p = 0f; p <= 1f; p += 0.05f) {
            Assert.assertTrue(SessionOverviewAddTabMath.plusAlpha(p)
                    >= SessionOverviewAddTabMath.plusAlpha(Math.min(1f, p + 0.05f)));
        }
    }

    @Test
    public void contentAlphaFadesInLateAndIsMonotonic() {
        Assert.assertEquals(0f, SessionOverviewAddTabMath.contentAlpha(0f), EPS);
        Assert.assertEquals(0f, SessionOverviewAddTabMath.contentAlpha(
                SessionOverviewAddTabMath.CONTENT_FADE_START), EPS);
        Assert.assertEquals(1f, SessionOverviewAddTabMath.contentAlpha(
                SessionOverviewAddTabMath.CONTENT_FADE_END), EPS);
        Assert.assertEquals(1f, SessionOverviewAddTabMath.contentAlpha(1f), EPS);
        final float mid = (SessionOverviewAddTabMath.CONTENT_FADE_START
                + SessionOverviewAddTabMath.CONTENT_FADE_END) / 2f;
        Assert.assertEquals(0.5f, SessionOverviewAddTabMath.contentAlpha(mid), EPS);
        for (float p = 0f; p <= 1f; p += 0.05f) {
            Assert.assertTrue(SessionOverviewAddTabMath.contentAlpha(p)
                    <= SessionOverviewAddTabMath.contentAlpha(Math.min(1f, p + 0.05f)));
        }
    }

    @Test
    public void plusIsOutBeforeContentStarts() {
        Assert.assertEquals(0f, SessionOverviewAddTabMath.plusAlpha(
                SessionOverviewAddTabMath.CONTENT_FADE_START), EPS);
        Assert.assertTrue(SessionOverviewAddTabMath.PLUS_FADE_END
                < SessionOverviewAddTabMath.CONTENT_FADE_START);
    }

    @Test
    public void cornerRadiusEndpointsAndClamping() {
        Assert.assertEquals(12f, SessionOverviewAddTabMath.cornerRadius(0f, 12f, 4f), EPS);
        Assert.assertEquals(4f, SessionOverviewAddTabMath.cornerRadius(1f, 12f, 4f), EPS);
        Assert.assertEquals(8f, SessionOverviewAddTabMath.cornerRadius(0.5f, 12f, 4f), EPS);
        Assert.assertEquals(12f, SessionOverviewAddTabMath.cornerRadius(-1f, 12f, 4f), EPS);
        Assert.assertEquals(4f, SessionOverviewAddTabMath.cornerRadius(2f, 12f, 4f), EPS);
        Assert.assertEquals(0f, SessionOverviewAddTabMath.cornerRadius(0f, -5f, -2f), EPS);
    }

    @Test
    public void sourceRadiusIsHalfMinSide() {
        Assert.assertEquals(23f, SessionOverviewAddTabMath.sourceRadius(46f, 46f), EPS);
        Assert.assertEquals(10f, SessionOverviewAddTabMath.sourceRadius(20f, 46f), EPS);
        Assert.assertEquals(0f, SessionOverviewAddTabMath.sourceRadius(0f, 46f), EPS);
        Assert.assertEquals(0f, SessionOverviewAddTabMath.sourceRadius(20f, -1f), EPS);
    }

    @Test
    public void shapeProgressIsClamped() {
        Assert.assertEquals(0f, SessionOverviewAddTabMath.shapeProgress(-1f), EPS);
        Assert.assertEquals(0.5f, SessionOverviewAddTabMath.shapeProgress(0.5f), EPS);
        Assert.assertEquals(1f, SessionOverviewAddTabMath.shapeProgress(2f), EPS);
    }

    @Test
    public void shadowStartsFullAndIsGoneByHalfway() {
        Assert.assertEquals(1f, SessionOverviewAddTabMath.shadowAlpha(0f), EPS);
        Assert.assertEquals(0.5f, SessionOverviewAddTabMath.shadowAlpha(0.25f), EPS);
        Assert.assertEquals(0f, SessionOverviewAddTabMath.shadowAlpha(
                SessionOverviewAddTabMath.SHADOW_FADE_END), EPS);
        Assert.assertEquals(0f, SessionOverviewAddTabMath.shadowAlpha(0.75f), EPS);
        Assert.assertEquals(0f, SessionOverviewAddTabMath.shadowAlpha(1f), EPS);
        Assert.assertEquals(1f, SessionOverviewAddTabMath.shadowAlpha(-1f), EPS);
        Assert.assertEquals(0f, SessionOverviewAddTabMath.shadowAlpha(2f), EPS);
    }

    @Test
    public void strokeMorphsFromButtonToCardWidth() {
        Assert.assertEquals(1f, SessionOverviewAddTabMath.strokeWidth(0f, 1f, 3f), EPS);
        Assert.assertEquals(2f, SessionOverviewAddTabMath.strokeWidth(0.5f, 1f, 3f), EPS);
        Assert.assertEquals(3f, SessionOverviewAddTabMath.strokeWidth(1f, 1f, 3f), EPS);
        Assert.assertEquals(0f, SessionOverviewAddTabMath.strokeWidth(0f, -1f, -2f), EPS);
    }

    @Test
    public void addButtonReturnsAtHalfway() {
        Assert.assertFalse(SessionOverviewAddTabMath.shouldRevealAddButton(0f));
        Assert.assertFalse(SessionOverviewAddTabMath.shouldRevealAddButton(0.49f));
        Assert.assertTrue(SessionOverviewAddTabMath.shouldRevealAddButton(0.5f));
        Assert.assertTrue(SessionOverviewAddTabMath.shouldRevealAddButton(1f));
        Assert.assertTrue(SessionOverviewAddTabMath.shouldRevealAddButton(2f));
        Assert.assertFalse(SessionOverviewAddTabMath.shouldRevealAddButton(-1f));
    }

    @Test
    public void nanProgressIsSafe() {
        Assert.assertEquals(1f, SessionOverviewAddTabMath.plusAlpha(Float.NaN), EPS);
        Assert.assertEquals(0f, SessionOverviewAddTabMath.contentAlpha(Float.NaN), EPS);
        Assert.assertEquals(0f, SessionOverviewAddTabMath.shapeProgress(Float.NaN), EPS);
        Assert.assertEquals(12f, SessionOverviewAddTabMath.cornerRadius(
                Float.NaN, 12f, 4f), EPS);
    }
}
