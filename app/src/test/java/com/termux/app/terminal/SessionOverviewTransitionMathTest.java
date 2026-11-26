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
    public void openingPreviewAppearsOnlyOnTheFinalFrame() {
        Assert.assertEquals(0f, SessionOverviewTransitionMath.previewAlpha(0f), EPS);
        Assert.assertEquals(0f, SessionOverviewTransitionMath.previewAlpha(0.5f), EPS);
        Assert.assertEquals(0f, SessionOverviewTransitionMath.previewAlpha(0.78f), EPS);
        Assert.assertEquals(0f, SessionOverviewTransitionMath.previewAlpha(0.999f), EPS);
        Assert.assertEquals(1f, SessionOverviewTransitionMath.previewAlpha(1f), EPS);
    }

    @Test
    public void openingTerminalDoesNotDissolveBeforeTheEnd() {
        for (int step = 0; step < 100; step++) {
            final float progress = step / 100f;
            Assert.assertEquals(1f,
                    SessionOverviewTransitionMath.ghostAlpha(progress), EPS);
            Assert.assertEquals(0f,
                    SessionOverviewTransitionMath.previewAlpha(progress), EPS);
        }
        Assert.assertEquals(0f, SessionOverviewTransitionMath.ghostAlpha(1f), EPS);
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
    public void closingSnapshotStartsOpaqueWithoutAnUnfade() {
        for (int step = 0; step <= 100; step++) {
            final float progress = step / 100f;
            Assert.assertEquals(1f,
                    SessionOverviewTransitionMath.closingGhostAlpha(progress), EPS);
            Assert.assertEquals(0f,
                    SessionOverviewTransitionMath.closingPreviewAlpha(progress), EPS);
        }
        Assert.assertEquals(1f, SessionOverviewTransitionMath.closingGhostAlpha(-1f), EPS);
        Assert.assertEquals(0f, SessionOverviewTransitionMath.closingPreviewAlpha(-1f), EPS);
        Assert.assertEquals(1f, SessionOverviewTransitionMath.closingGhostAlpha(2f), EPS);
        Assert.assertEquals(0f, SessionOverviewTransitionMath.closingPreviewAlpha(2f), EPS);
    }

    @Test
    public void closingHoverAlphaRampsFromTheFrozenAlphaToOpaque() {
        Assert.assertEquals(0.82f,
                SessionOverviewTransitionMath.closingHoverAlpha(0.82f, 0f), EPS);
        Assert.assertEquals(0.91f,
                SessionOverviewTransitionMath.closingHoverAlpha(0.82f, 0.125f), EPS);
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.closingHoverAlpha(0.82f,
                        SessionOverviewTransitionMath.CLOSING_HOVER_HANDOFF_END), EPS);
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.closingHoverAlpha(0.82f, 1f), EPS);
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.closingHoverAlpha(1f, 0.25f), EPS);
        Assert.assertEquals(0.82f,
                SessionOverviewTransitionMath.closingHoverAlpha(0.82f, Float.NaN), EPS);
    }

    @Test
    public void closingShadowAlphaIsFullyTransparentAtHalfway() {
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.closingShadowAlpha(0f), EPS);
        Assert.assertEquals(0.5f,
                SessionOverviewTransitionMath.closingShadowAlpha(0.25f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.closingShadowAlpha(
                        SessionOverviewTransitionMath.CLOSING_SHADOW_FADE_END), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.closingShadowAlpha(1f), EPS);
    }

    @Test
    public void fadeInHandlesDegenerateStarts() {
        Assert.assertEquals(0.5f, SessionOverviewTransitionMath.fadeIn(0.5f, -1f), EPS);
        Assert.assertEquals(0f, SessionOverviewTransitionMath.fadeIn(0.5f, 1f), EPS);
        Assert.assertEquals(1f, SessionOverviewTransitionMath.fadeIn(1f, 1f), EPS);
        Assert.assertEquals(0.5f, SessionOverviewTransitionMath.fadeIn(0.5f, 0f), EPS);
    }

    @Test
    public void tabsFadeOutAtTheSourceAndInAtTheTarget() {
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.tabSourceAlpha(0f), EPS);
        Assert.assertEquals(0.5f,
                SessionOverviewTransitionMath.tabSourceAlpha(
                        SessionOverviewTransitionMath.TAB_FADE_OUT_END / 2f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.tabSourceAlpha(
                        SessionOverviewTransitionMath.TAB_FADE_OUT_END), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.tabSourceAlpha(0.5f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.tabTargetAlpha(0f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.tabTargetAlpha(
                        SessionOverviewTransitionMath.TAB_FADE_IN_START), EPS);
        Assert.assertEquals(0.5f,
                SessionOverviewTransitionMath.tabTargetAlpha(
                        SessionOverviewTransitionMath.TAB_FADE_IN_START
                                + (1f - SessionOverviewTransitionMath.TAB_FADE_IN_START) / 2f),
                EPS);
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.tabTargetAlpha(1f), EPS);

        float previousSource = 1.1f;
        float previousTarget = -0.1f;
        for (int step = 0; step <= 100; step++) {
            final float progress = step / 100f;
            final float source = SessionOverviewTransitionMath.tabSourceAlpha(progress);
            final float target = SessionOverviewTransitionMath.tabTargetAlpha(progress);
            Assert.assertTrue(source >= 0f && source <= 1f);
            Assert.assertTrue(target >= 0f && target <= 1f);
            Assert.assertTrue(source <= previousSource);
            Assert.assertTrue(target >= previousTarget);
            Assert.assertTrue(source == 0f || target == 0f);
            Assert.assertTrue(source + target <= 1f + EPS);
            previousSource = source;
            previousTarget = target;
        }

        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.tabSourceAlpha(-1f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.tabSourceAlpha(2f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.tabTargetAlpha(-1f), EPS);
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.tabTargetAlpha(2f), EPS);
    }

    @Test
    public void tabsFadeAtOneThirdAndTwoThirdsPointsOfThePath() {
        // The tab disappears only after travelling one third of the line, crosses the middle
        // third invisibly while its size/style changes, and reappears for the last third. The
        // path itself stays a single continuous run; only the alpha is absent in the middle.
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.tabSourcePathFraction(0f), EPS);
        Assert.assertEquals(SessionOverviewTransitionMath.TAB_FADE_OUT_END,
                SessionOverviewTransitionMath.tabSourcePathFraction(
                        SessionOverviewTransitionMath.TAB_FADE_OUT_END), EPS);
        Assert.assertEquals(SessionOverviewTransitionMath.TAB_MOTION_SPLIT,
                SessionOverviewTransitionMath.tabSourcePathFraction(0.5f), EPS);
        Assert.assertEquals(SessionOverviewTransitionMath.TAB_MOTION_SPLIT,
                SessionOverviewTransitionMath.tabTargetPathFraction(0f), EPS);
        Assert.assertEquals(SessionOverviewTransitionMath.TAB_FADE_IN_START,
                SessionOverviewTransitionMath.tabTargetPathFraction(
                        SessionOverviewTransitionMath.TAB_FADE_IN_START), EPS);
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.tabTargetPathFraction(1f), EPS);

        // The fade boundaries sit on the same fraction of the path as on the timeline.
        Assert.assertEquals(SessionOverviewTransitionMath.TAB_FADE_OUT_END,
                SessionOverviewTransitionMath.tabSourcePathFraction(
                        SessionOverviewTransitionMath.TAB_FADE_OUT_END), EPS);
        Assert.assertEquals(SessionOverviewTransitionMath.TAB_FADE_IN_START,
                SessionOverviewTransitionMath.tabTargetPathFraction(
                        SessionOverviewTransitionMath.TAB_FADE_IN_START), EPS);

        float previousSource = -0.1f;
        float previousTarget = -0.1f;
        for (int step = 0; step <= 100; step++) {
            final float progress = step / 100f;
            final float source =
                    SessionOverviewTransitionMath.tabSourcePathFraction(progress);
            final float target =
                    SessionOverviewTransitionMath.tabTargetPathFraction(progress);
            Assert.assertTrue(source >= 0f && source <= 1f);
            Assert.assertTrue(target >= 0f && target <= 1f);
            Assert.assertTrue(source >= previousSource);
            Assert.assertTrue(target >= previousTarget);
            previousSource = source;
            previousTarget = target;
        }

        // The invisible hand-off happens at the same point on the line.
        Assert.assertEquals(
                SessionOverviewTransitionMath.tabSourcePathFraction(
                        SessionOverviewTransitionMath.TAB_MOTION_SPLIT),
                SessionOverviewTransitionMath.tabTargetPathFraction(
                        SessionOverviewTransitionMath.TAB_MOTION_SPLIT), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.tabSourcePathFraction(-1f), EPS);
        Assert.assertEquals(SessionOverviewTransitionMath.TAB_MOTION_SPLIT,
                SessionOverviewTransitionMath.tabSourcePathFraction(2f), EPS);
        Assert.assertEquals(SessionOverviewTransitionMath.TAB_MOTION_SPLIT,
                SessionOverviewTransitionMath.tabTargetPathFraction(-1f), EPS);
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.tabTargetPathFraction(2f), EPS);
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
    public void flyingTabsStayAboveTheTerminalSnapshot() {
        Assert.assertTrue(SessionOverviewTransitionMath.tabGhostZ(0f) > 0f);
        Assert.assertTrue(SessionOverviewTransitionMath.tabGhostZ(8f) > 8f);
        Assert.assertTrue(SessionOverviewTransitionMath.tabGhostZ(24f) > 24f);
        Assert.assertEquals(1f, SessionOverviewTransitionMath.tabGhostZ(Float.NaN), EPS);
    }

    @Test
    public void tabsWithVisibleThumbnailsGoBelowTheTerminalSnapshot() {
        // The pair is drawn below every positive terminal elevation, and also below a
        // zero-elevation snapshot because the ghosts are appended after it.
        Assert.assertEquals(-1f,
                SessionOverviewTransitionMath.tabGhostZ(true, 24f), EPS);
        Assert.assertEquals(-1f,
                SessionOverviewTransitionMath.tabGhostZ(true, 0f), EPS);
        // A tab moving without its thumbnail keeps the old above-the-terminal order.
        Assert.assertTrue(SessionOverviewTransitionMath.tabGhostZ(false, 24f) > 24f);
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.tabGhostZ(false, Float.NaN), EPS);
    }

    @Test
    public void offscreenCardTabsFlyWithoutCarryingTheirThumbnail() {
        // Every tab flies, even when its card is outside the viewport. Only a visible card
        // follows its tab; an off-screen card stays in its slot so the flight cannot drag an
        // unseen thumbnail into view.
        Assert.assertFalse(SessionOverviewTransitionMath.tabCarriesCard(false, false));
        Assert.assertFalse(SessionOverviewTransitionMath.tabCarriesCard(false, true));
        Assert.assertTrue(SessionOverviewTransitionMath.tabCarriesCard(true, false));
        // The card that owns the terminal snapshot is animated by that snapshot instead.
        Assert.assertFalse(SessionOverviewTransitionMath.tabCarriesCard(true, true));
    }

    @Test
    public void overviewCardsShareTheTabFadeSchedule() {
        // Every non-active opening card shares the tab's target fade. This includes cards
        // whose tab was not visible in the strip: they do not move, but they must not appear
        // earlier than the cards that fly in with a tab.
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.openingCardAlpha(-1f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.openingCardAlpha(0f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.openingCardAlpha(0.5f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.openingCardAlpha(
                        SessionOverviewTransitionMath.TAB_FADE_IN_START), EPS);
        Assert.assertEquals(0.5f,
                SessionOverviewTransitionMath.openingCardAlpha(
                        SessionOverviewTransitionMath.TAB_FADE_IN_START
                                + (1f - SessionOverviewTransitionMath.TAB_FADE_IN_START) / 2f),
                EPS);
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.openingCardAlpha(1f), EPS);
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.openingCardAlpha(2f), EPS);

        // Every unselected closing card shares the tab's source fade. This includes cards
        // that stay put; their disappearance must coincide with the flying cards.
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.closingCardAlpha(-1f), EPS);
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.closingCardAlpha(0f), EPS);
        Assert.assertEquals(0.5f,
                SessionOverviewTransitionMath.closingCardAlpha(
                        SessionOverviewTransitionMath.TAB_FADE_OUT_END / 2f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.closingCardAlpha(
                        SessionOverviewTransitionMath.TAB_FADE_OUT_END), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.closingCardAlpha(0.75f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.closingCardAlpha(1f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.closingCardAlpha(2f), EPS);

        for (int step = 0; step <= 100; step++) {
            final float progress = step / 100f;
            Assert.assertEquals(
                    SessionOverviewTransitionMath.tabTargetAlpha(progress),
                    SessionOverviewTransitionMath.openingCardAlpha(progress), EPS);
            Assert.assertEquals(
                    SessionOverviewTransitionMath.tabSourceAlpha(progress),
                    SessionOverviewTransitionMath.closingCardAlpha(progress), EPS);
        }
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

    @Test
    public void scrollbarThumbOpeningSlidesRightAndFadesOut() {
        final float shift = 16f;
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.openingScrollbarThumbTranslation(0f, shift), EPS);
        Assert.assertEquals(shift,
                SessionOverviewTransitionMath.openingScrollbarThumbTranslation(1f, shift), EPS);
        Assert.assertEquals(shift / 2f,
                SessionOverviewTransitionMath.openingScrollbarThumbTranslation(0.5f, shift), EPS);
        // Out-of-range progress is clamped.
        Assert.assertEquals(shift,
                SessionOverviewTransitionMath.openingScrollbarThumbTranslation(2f, shift), EPS);

        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.openingScrollbarThumbAlpha(0f), EPS);
        Assert.assertEquals(0.5f,
                SessionOverviewTransitionMath.openingScrollbarThumbAlpha(0.5f), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.openingScrollbarThumbAlpha(1f), EPS);
    }

    @Test
    public void scrollbarThumbClosingIsTheExactReverseOfOpening() {
        final float shift = 16f;
        for (int step = 0; step <= 100; step++) {
            final float progress = step / 100f;
            final float p = 1f - progress;
            // Opening at (1-p) lands on the same shifted/faded pose as closing at p.
            Assert.assertEquals(
                    SessionOverviewTransitionMath.openingScrollbarThumbTranslation(p, shift),
                    SessionOverviewTransitionMath.closingScrollbarThumbTranslation(progress, shift),
                    EPS);
            Assert.assertEquals(
                    SessionOverviewTransitionMath.openingScrollbarThumbAlpha(p),
                    SessionOverviewTransitionMath.closingScrollbarThumbAlpha(progress),
                    EPS);
        }
    }

    @Test
    public void scrollbarThumbClosingSlidesBackLeftAndFadesIn() {
        final float shift = 16f;
        Assert.assertEquals(shift,
                SessionOverviewTransitionMath.closingScrollbarThumbTranslation(0f, shift), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.closingScrollbarThumbTranslation(1f, shift), EPS);
        Assert.assertEquals(0f,
                SessionOverviewTransitionMath.closingScrollbarThumbAlpha(0f), EPS);
        Assert.assertEquals(1f,
                SessionOverviewTransitionMath.closingScrollbarThumbAlpha(1f), EPS);
    }
}
