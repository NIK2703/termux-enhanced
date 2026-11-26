package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

/** Phase math for releasing over the overview "+" button and opening a terminal. */
public class SessionOverviewAddButtonTerminalMathTest {

    private static final float EPS = 0.0001f;

    @Test
    public void sourceFadesOutAtHandoffEnd() {
        Assert.assertEquals(1f,
                SessionOverviewAddButtonTerminalMath.sourceAlpha(1f, 0f), EPS);
        Assert.assertEquals(0.5f,
                SessionOverviewAddButtonTerminalMath.sourceAlpha(1f,
                        SessionOverviewAddButtonTerminalMath.HANDOFF_END / 2f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewAddButtonTerminalMath.sourceAlpha(1f,
                        SessionOverviewAddButtonTerminalMath.HANDOFF_END), EPS);
        Assert.assertEquals(0f,
                SessionOverviewAddButtonTerminalMath.sourceAlpha(1f, 1f), EPS);
        Assert.assertEquals(0.4f,
                SessionOverviewAddButtonTerminalMath.sourceAlpha(0.4f, 0f), EPS);
    }

    @Test
    public void plusFadesBeforeTheButtonBody() {
        Assert.assertEquals(1f,
                SessionOverviewAddButtonTerminalMath.sourcePlusAlpha(1f, 0f), EPS);
        Assert.assertEquals(0.5f,
                SessionOverviewAddButtonTerminalMath.sourcePlusAlpha(1f,
                        SessionOverviewAddButtonTerminalMath.PLUS_FADE_END / 2f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewAddButtonTerminalMath.sourcePlusAlpha(1f,
                        SessionOverviewAddButtonTerminalMath.PLUS_FADE_END), EPS);
        Assert.assertTrue(SessionOverviewAddButtonTerminalMath.PLUS_FADE_END
                < SessionOverviewAddButtonTerminalMath.HANDOFF_END);
    }

    @Test
    public void terminalStartsTransparentAndIsOpaqueAtHandoff() {
        Assert.assertEquals(0f,
                SessionOverviewAddButtonTerminalMath.terminalContentAlpha(0f), EPS);
        Assert.assertEquals(0.5f,
                SessionOverviewAddButtonTerminalMath.terminalContentAlpha(
                        SessionOverviewAddButtonTerminalMath.HANDOFF_END / 2f), EPS);
        Assert.assertEquals(1f,
                SessionOverviewAddButtonTerminalMath.terminalContentAlpha(
                        SessionOverviewAddButtonTerminalMath.HANDOFF_END), EPS);
        Assert.assertEquals(1f,
                SessionOverviewAddButtonTerminalMath.terminalContentAlpha(1f), EPS);
    }

    @Test
    public void radiusMorphsOverTheWholeFlight() {
        final float start = 40f;
        Assert.assertEquals(start,
                SessionOverviewAddButtonTerminalMath.radius(0f, start), EPS);
        Assert.assertEquals(start * 0.75f,
                SessionOverviewAddButtonTerminalMath.radius(0.25f, start), EPS);
        Assert.assertEquals(0f,
                SessionOverviewAddButtonTerminalMath.radius(1f, start), EPS);
    }

    @Test
    public void strokeReachesCardWidthAtHandoff() {
        Assert.assertEquals(2f,
                SessionOverviewAddButtonTerminalMath.strokeWidth(0f, 2f, 6f), EPS);
        Assert.assertEquals(4f,
                SessionOverviewAddButtonTerminalMath.strokeWidth(0.125f, 2f, 6f), EPS);
        Assert.assertEquals(6f,
                SessionOverviewAddButtonTerminalMath.strokeWidth(0.25f, 2f, 6f), EPS);
        Assert.assertEquals(6f,
                SessionOverviewAddButtonTerminalMath.strokeWidth(1f, 2f, 6f), EPS);
    }

    @Test
    public void borderAndShadowReachZeroHalfway() {
        Assert.assertEquals(0f,
                SessionOverviewAddButtonTerminalMath.terminalBorderAlpha(0f), EPS);
        Assert.assertEquals(0.5f,
                SessionOverviewAddButtonTerminalMath.terminalBorderAlpha(0.25f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewAddButtonTerminalMath.terminalBorderAlpha(0.5f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewAddButtonTerminalMath.terminalShadowAlpha(0.5f), EPS);
        Assert.assertEquals(1f,
                SessionOverviewAddButtonTerminalMath.terminalShadowAlpha(0f), EPS);
    }

    @Test
    public void visualRectUsesThePivotAndScale() {
        final float[] out = new float[4];
        SessionOverviewAddButtonTerminalMath.visualRect(out,
                100f, 200f, 40f, 40f, 20f, 20f, 1.05f, 1.05f);
        Assert.assertEquals(99f, out[0], EPS);
        Assert.assertEquals(199f, out[1], EPS);
        Assert.assertEquals(141f, out[2], EPS);
        Assert.assertEquals(241f, out[3], EPS);

        SessionOverviewAddButtonTerminalMath.visualRect(out,
                100f, 200f, 40f, 40f, 0f, 0f, 0.5f, 2f);
        Assert.assertEquals(100f, out[0], EPS);
        Assert.assertEquals(200f, out[1], EPS);
        Assert.assertEquals(120f, out[2], EPS);
        Assert.assertEquals(280f, out[3], EPS);
    }

    @Test
    public void visualRectNormalizesNegativeScale() {
        final float[] out = new float[4];
        SessionOverviewAddButtonTerminalMath.visualRect(out,
                10f, 20f, 30f, 40f, 0f, 0f, -1f, -2f);
        Assert.assertEquals(-20f, out[0], EPS);
        Assert.assertEquals(-60f, out[1], EPS);
        Assert.assertEquals(10f, out[2], EPS);
        Assert.assertEquals(20f, out[3], EPS);
    }

    @Test
    public void sourceRadiusAndScaledContentAreSafe() {
        Assert.assertEquals(10f,
                SessionOverviewAddButtonTerminalMath.sourceRadius(20f, 40f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewAddButtonTerminalMath.sourceRadius(0f, 40f), EPS);
        Assert.assertEquals(30f,
                SessionOverviewAddButtonTerminalMath.scaledContentSize(
                        46f, 46f, 16f, 1f), EPS);
        Assert.assertEquals(31.5f,
                SessionOverviewAddButtonTerminalMath.scaledContentSize(
                        46f, 46f, 16f, 1.05f), EPS);
        Assert.assertEquals(1f,
                SessionOverviewAddButtonTerminalMath.scaledContentSize(
                        10f, 10f, 16f, 1f), EPS);
    }
}
