package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

/** Timing rules for the overview opening and closing transitions. */
public class SessionOverviewTransitionMathTest {

    private static final float EPS = 0.0001f;

    @Test
    public void progressIsClampedToTheUnitInterval() {
        Assert.assertEquals(0f, SessionOverviewTransitionMath.clamp01(-3f), EPS);
        Assert.assertEquals(0f, SessionOverviewTransitionMath.clamp01(0f), EPS);
        Assert.assertEquals(0.5f, SessionOverviewTransitionMath.clamp01(0.5f), EPS);
        Assert.assertEquals(1f, SessionOverviewTransitionMath.clamp01(2f), EPS);
        Assert.assertEquals(0f, SessionOverviewTransitionMath.clamp01(Float.NaN), EPS);
    }

    @Test
    public void previewStaysHiddenUntilTheSnapshotIsNearlyHome() {
        final float start = SessionOverviewTransitionMath.CROSSFADE_START;
        Assert.assertEquals(0f, SessionOverviewTransitionMath.previewAlpha(0f), EPS);
        Assert.assertEquals(0f, SessionOverviewTransitionMath.previewAlpha(start - 0.01f), EPS);
        Assert.assertEquals(0f, SessionOverviewTransitionMath.previewAlpha(start), EPS);
        Assert.assertEquals(0.5f,
                SessionOverviewTransitionMath.previewAlpha((start + 1f) * 0.5f), EPS);
        Assert.assertEquals(1f, SessionOverviewTransitionMath.previewAlpha(1f), EPS);
    }

    @Test
    public void ghostIsTheExactInverseOfThePreview() {
        for (float progress = 0f; progress <= 1f; progress += 0.05f) {
            Assert.assertEquals(1f,
                    SessionOverviewTransitionMath.ghostAlpha(progress)
                            + SessionOverviewTransitionMath.previewAlpha(progress), EPS);
        }
        Assert.assertEquals(1f, SessionOverviewTransitionMath.ghostAlpha(0f), EPS);
        Assert.assertEquals(0f, SessionOverviewTransitionMath.ghostAlpha(1f), EPS);
    }

    @Test
    public void closingFadeIsTheOpeningCrossfadePlayedBackwards() {
        for (float progress = 0f; progress <= 1f; progress += 0.05f) {
            Assert.assertEquals(
                    SessionOverviewTransitionMath.ghostAlpha(1f - progress),
                    SessionOverviewTransitionMath.closingGhostAlpha(progress), EPS);
            Assert.assertEquals(1f,
                    SessionOverviewTransitionMath.closingGhostAlpha(progress)
                            + SessionOverviewTransitionMath.closingPreviewAlpha(progress), EPS);
        }
        Assert.assertEquals(0f, SessionOverviewTransitionMath.closingGhostAlpha(0f), EPS);
        Assert.assertEquals(1f, SessionOverviewTransitionMath.closingGhostAlpha(1f), EPS);
        Assert.assertEquals(1f, SessionOverviewTransitionMath.closingPreviewAlpha(0f), EPS);
        Assert.assertEquals(0f, SessionOverviewTransitionMath.closingPreviewAlpha(1f), EPS);
    }

    @Test
    public void fadeInHandlesDegenerateStarts() {
        Assert.assertEquals(0.5f, SessionOverviewTransitionMath.fadeIn(0.5f, -1f), EPS);
        Assert.assertEquals(0f, SessionOverviewTransitionMath.fadeIn(0.5f, 1f), EPS);
        Assert.assertEquals(1f, SessionOverviewTransitionMath.fadeIn(1f, 1f), EPS);
        Assert.assertEquals(0.5f, SessionOverviewTransitionMath.fadeIn(0.5f, 0f), EPS);
    }

    @Test
    public void tabPanelFadesOutOnOpenAndBackInOnClose() {
        for (int step = 0; step <= 100; step++) {
            final float progress = step / 100f;
            Assert.assertEquals(1f - progress,
                    SessionOverviewTransitionMath.openingTabPanelAlpha(progress), EPS);
            Assert.assertEquals(progress,
                    SessionOverviewTransitionMath.closingTabPanelAlpha(progress), EPS);
        }
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.openingTabPanelAlpha(0f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.openingTabPanelAlpha(1f), EPS);
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.openingTabPanelAlpha(-1f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.openingTabPanelAlpha(2f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.closingTabPanelAlpha(0f), EPS);
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.closingTabPanelAlpha(1f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.closingTabPanelAlpha(-1f), EPS);
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.closingTabPanelAlpha(2f), EPS);
    }

    @Test
    public void appBackgroundFadesInWithTheTabPanel() {
        for (int step = 0; step <= 100; step++) {
            final float progress = step / 100f;
            Assert.assertEquals(progress,
                    SessionOverviewTransitionMath.closingAppBackgroundAlpha(progress), EPS);
            Assert.assertEquals(
                    SessionOverviewTransitionMath.closingTabPanelAlpha(progress),
                    SessionOverviewTransitionMath.closingAppBackgroundAlpha(progress), EPS);
        }
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.closingAppBackgroundAlpha(-1f), EPS);
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.closingAppBackgroundAlpha(2f), EPS);
    }

    @Test
    public void integerInterpolationRoundsAndClamps() {
        Assert.assertEquals(10, SessionOverviewTransitionMath.lerp(10, 20, 0f));
        Assert.assertEquals(20, SessionOverviewTransitionMath.lerp(10, 20, 1f));
        Assert.assertEquals(15, SessionOverviewTransitionMath.lerp(10, 20, 0.5f));
        Assert.assertEquals(10, SessionOverviewTransitionMath.lerp(10, 20, -1f));
        Assert.assertEquals(20, SessionOverviewTransitionMath.lerp(10, 20, 2f));
        Assert.assertEquals(4, SessionOverviewTransitionMath.lerp(3, 4, 0.5f));
    }

    @Test
    public void colourInterpolationKeepsAlphaAndEndpoints() {
        final int start = 0x80112233;
        final int end = 0xFFAABBCC;
        Assert.assertEquals(start, SessionOverviewTransitionMath.blendColor(start, end, 0f));
        Assert.assertEquals(end, SessionOverviewTransitionMath.blendColor(start, end, 1f));
        final int half = SessionOverviewTransitionMath.blendColor(start, end, 0.5f);
        Assert.assertEquals(0xC0, (half >>> 24) & 0xFF);
        Assert.assertEquals(0x5E, (half >>> 16) & 0xFF);
        Assert.assertEquals(0x6F, (half >>> 8) & 0xFF);
        Assert.assertEquals(0x80, half & 0xFF);
    }

    @Test
    public void previewFadeIsMonotonic() {
        float previous = -1f;
        for (int step = 0; step <= 100; step++) {
            final float preview = SessionOverviewTransitionMath.previewAlpha(step / 100f);
            Assert.assertTrue(preview >= previous);
            previous = preview;
        }
    }
}
