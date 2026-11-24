package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

/** Pure arithmetic for overview scroll restore and the post-removal settle. */
public class SessionOverviewScrollMathTest {

    @Test
    public void rememberedPositionIsKeptWhenTheActiveCardIsFullyVisible() {
        // viewport 1000, 100 px status strip, card 250..600; scroll 120 shows all of it.
        Assert.assertEquals(120, SessionOverviewScrollMath.scrollForActiveCard(
                120, 1000, 100, 500, 250, 600));
    }

    @Test
    public void scrollsUpOnlyEnoughToKeepTheCardOutOfTheStatusBar() {
        // At scroll 200 the card top (150) would be 50 px above the 100 px usable line.
        Assert.assertEquals(50, SessionOverviewScrollMath.scrollForActiveCard(
                200, 1000, 100, 500, 150, 500));
    }

    @Test
    public void scrollsDownOnlyEnoughToRevealTheCardBottom() {
        Assert.assertEquals(200, SessionOverviewScrollMath.scrollForActiveCard(
                0, 1000, 100, 500, 900, 1200));
    }

    @Test
    public void activeCardVisibilityWinsOverTheRememberedPositionAtBothEdges() {
        Assert.assertEquals(0, SessionOverviewScrollMath.scrollForActiveCard(
                0, 1000, 100, 500, 300, 650));
        Assert.assertEquals(200, SessionOverviewScrollMath.scrollForActiveCard(
                500, 1000, 100, 500, 300, 650));
    }

    @Test
    public void preferredPositionIsClampedToTheScrollRangeWhenThereIsNoCard() {
        Assert.assertEquals(0, SessionOverviewScrollMath.scrollForActiveCard(
                -20, 1000, 100, 500, 0, 0));
        Assert.assertEquals(500, SessionOverviewScrollMath.scrollForActiveCard(
                900, 1000, 100, 500, 0, 0));
    }

    @Test
    public void cardTallerThanTheUsableViewportKeepsItsTopAtTheUsableLine() {
        // Usable height is 900, the card is 1300; both ends cannot fit. Put the top at y=100.
        Assert.assertEquals(200, SessionOverviewScrollMath.scrollForActiveCard(
                0, 1000, 100, 500, 300, 1600));
    }

    @Test
    public void invalidGeometryLeavesTheClampedPreferenceAlone() {
        Assert.assertEquals(75, SessionOverviewScrollMath.scrollForActiveCard(
                75, 0, 100, 200, 0, 0));
        Assert.assertEquals(75, SessionOverviewScrollMath.scrollForActiveCard(
                75, 1000, 100, 200, 400, 400));
    }

    @Test
    public void topInsetIsIgnoredWhenItIsLargerThanTheViewport() {
        // The usable viewport is empty; keep the preferred position rather than forcing a jump.
        Assert.assertEquals(0, SessionOverviewScrollMath.scrollForActiveCard(
                0, 100, 200, 0, 10, 20));
    }

    @Test
    public void clampCausedByAShorterGridProducesTheSettleDistance() {
        // Removing the last row takes the maximum from 900 to 600; the content must slide 300 px.
        Assert.assertEquals(300, SessionOverviewScrollMath.shrinkSettleDistance(900, 600));
    }

    @Test
    public void layoutThatDidNotMoveTheScrollNeedsNoSettle() {
        Assert.assertEquals(0, SessionOverviewScrollMath.shrinkSettleDistance(600, 600));
        Assert.assertEquals(0, SessionOverviewScrollMath.shrinkSettleDistance(300, 600));
    }

    @Test
    public void stackedClampsAddUpToTheTotalDistance() {
        // A second removal while the first settle is still running clamps again. The offsets
        // stack, so their sum must still equal the distance from the original position.
        final int first = SessionOverviewScrollMath.shrinkSettleDistance(900, 600);
        final int second = SessionOverviewScrollMath.shrinkSettleDistance(600, 500);
        Assert.assertEquals(900 - 500, first + second);
    }
}
