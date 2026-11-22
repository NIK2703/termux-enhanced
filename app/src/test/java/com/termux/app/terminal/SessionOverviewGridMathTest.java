package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

/**
 * Property tests for the overview grid. The viewport is always 2x2; a fifth session must add a
 * scrollable row instead of shrinking the cards.
 */
public class SessionOverviewGridMathTest {

    private static final int[] WIDTHS = {320, 360, 411, 600, 720, 900, 1280, 1600, 2560};
    private static final int[] HEIGHTS = {240, 320, 480, 640, 720, 900, 1080, 1600, 2560};

    @Test
    public void fixedTwoColumnGridForEverySessionCount() {
        for (int count = 1; count <= 8; count++) {
            SessionOverviewGridMath.Layout layout =
                    SessionOverviewGridMath.compute(count, 1080, 1920, 24);
            Assert.assertEquals(2, layout.columns);
            Assert.assertEquals(Math.max(2, (count + 1) / 2), layout.rows);
            Assert.assertEquals(layout.columns * layout.rows - count, layout.emptySlots);
        }
    }

    @Test
    public void firstTwoRowsFitTheViewportAndExtraRowsScroll() {
        for (int count = 1; count <= 8; count++) {
            for (int width : WIDTHS) {
                for (int height : HEIGHTS) {
                    SessionOverviewGridMath.Layout layout =
                            SessionOverviewGridMath.compute(count, width, height, 16);
                    Assert.assertTrue("cards must be positive for " + width + "x" + height,
                            layout.cardWidth > 0 && layout.cardHeight > 0);
                    Assert.assertTrue(layout.gridWidth <= width);
                    Assert.assertTrue(layout.left >= 0);
                    Assert.assertTrue(layout.left + layout.gridWidth <= width);

                    if (layout.rows <= SessionOverviewGridMath.VISIBLE_ROWS) {
                        Assert.assertFalse(layout.isScrollable());
                        Assert.assertTrue(layout.gridHeight <= height);
                        Assert.assertTrue(layout.top >= 0);
                        Assert.assertTrue(layout.top + layout.gridHeight <= height);
                    } else {
                        Assert.assertTrue(layout.isScrollable());
                        Assert.assertTrue(layout.gridHeight > height);
                        Assert.assertEquals(0, layout.top);
                    }
                }
            }
        }
    }

    @Test
    public void moreThanFourSessionsAddARowWithoutShrinkingCards() {
        SessionOverviewGridMath.Layout four =
                SessionOverviewGridMath.compute(4, 1080, 1920, 24);
        SessionOverviewGridMath.Layout five =
                SessionOverviewGridMath.compute(5, 1080, 1920, 24);

        Assert.assertEquals(four.cardWidth, five.cardWidth);
        Assert.assertEquals(four.cardHeight, five.cardHeight);
        Assert.assertEquals(2, five.columns);
        Assert.assertEquals(3, five.rows);
        Assert.assertTrue(five.gridHeight > five.availableHeight);
        Assert.assertEquals(0, five.top);
        Assert.assertFalse(four.isScrollable());
        Assert.assertTrue(five.isScrollable());
    }

    @Test
    public void cardsKeepTheContentAreaAspectRatio() {
        for (int count = 1; count <= 8; count++) {
            for (int width : WIDTHS) {
                for (int height : HEIGHTS) {
                    SessionOverviewGridMath.Layout layout =
                            SessionOverviewGridMath.compute(count, width, height, 12);
                    double expectedAspect = (double) width / height;
                    // The height is floored, so the width error is bounded by one row of aspect
                    // units. This is less than a pixel of visual difference at real card sizes.
                    Assert.assertTrue(
                            "aspect error too large for " + count + " at " + width + "x" + height,
                            Math.abs(layout.cardWidth - layout.cardHeight * expectedAspect)
                                    < expectedAspect);
                }
            }
        }
    }

    @Test
    public void cardsDoNotOverlapAndStayInsideTheirSlots() {
        for (int count = 1; count <= 8; count++) {
            SessionOverviewGridMath.Layout layout =
                    SessionOverviewGridMath.compute(count, 1280, 800, 20);
            for (int i = 0; i < count; i++) {
                int row = i / layout.columns;
                int column = i % layout.columns;
                int left = layout.left + column * (layout.cardWidth + layout.gap);
                int top = layout.top + row * (layout.cardHeight + layout.gap);
                Assert.assertTrue(left >= layout.left);
                Assert.assertTrue(top >= layout.top);
                Assert.assertTrue(left + layout.cardWidth <= layout.left + layout.gridWidth);
                Assert.assertTrue(top + layout.cardHeight <= layout.top + layout.gridHeight);

                if (column + 1 < layout.columns) {
                    int nextLeft = left + layout.cardWidth + layout.gap;
                    Assert.assertTrue(nextLeft >= left + layout.cardWidth);
                }
                if (row + 1 < layout.rows) {
                    int nextTop = top + layout.cardHeight + layout.gap;
                    Assert.assertTrue(nextTop >= top + layout.cardHeight);
                }
            }
        }
    }

    @Test
    public void densityScalingKeepsTheGridShape() {
        for (int count = 1; count <= 8; count++) {
            SessionOverviewGridMath.Layout base =
                    SessionOverviewGridMath.compute(count, 411, 731, 8);
            SessionOverviewGridMath.Layout scaled =
                    SessionOverviewGridMath.compute(count, 411 * 3, 731 * 3, 8 * 3);

            Assert.assertEquals(base.columns, scaled.columns);
            Assert.assertEquals(base.rows, scaled.rows);
            // Compare in dp: pixel floors may differ by a few pixels, but the layout must be the
            // same physical size at 3x density.
            Assert.assertTrue(Math.abs(scaled.cardWidth / 3.0 - base.cardWidth) <= 2.0);
            Assert.assertTrue(Math.abs(scaled.cardHeight / 3.0 - base.cardHeight) <= 2.0);
            // Centering slack can shift by a few pixels after flooring; only the card size is
            // expected to stay stable in dp.
        }
    }

    @Test
    public void swappingOrientationKeepsTheSameGridShape() {
        for (int count = 1; count <= 8; count++) {
            SessionOverviewGridMath.Layout portrait =
                    SessionOverviewGridMath.compute(count, 720, 1280, 16);
            SessionOverviewGridMath.Layout landscape =
                    SessionOverviewGridMath.compute(count, 1280, 720, 16);

            Assert.assertEquals(portrait.columns, landscape.columns);
            Assert.assertEquals(portrait.rows, landscape.rows);
            Assert.assertEquals(portrait.emptySlots, landscape.emptySlots);
            Assert.assertTrue(landscape.gridWidth <= 1280);
            if (landscape.rows <= SessionOverviewGridMath.VISIBLE_ROWS) {
                Assert.assertTrue(landscape.gridHeight <= 720);
            } else {
                Assert.assertTrue(landscape.gridHeight > 720);
            }
        }
    }

    @Test
    public void noSessionsProducesAnEmptyLayout() {
        SessionOverviewGridMath.Layout layout =
                SessionOverviewGridMath.compute(0, 1080, 1920, 16);
        Assert.assertEquals(0, layout.columns);
        Assert.assertEquals(0, layout.rows);
        Assert.assertEquals(0, layout.emptySlots);
        Assert.assertFalse(layout.hasCards());
    }

    @Test
    public void tooSmallContentAreaDoesNotProduceNegativeGeometry() {
        SessionOverviewGridMath.Layout layout =
                SessionOverviewGridMath.compute(8, 10, 10, 16);
        Assert.assertEquals(2, layout.columns);
        Assert.assertEquals(4, layout.rows);
        Assert.assertEquals(0, layout.cardWidth);
        Assert.assertEquals(0, layout.cardHeight);
        Assert.assertEquals(0, layout.gridWidth);
        Assert.assertEquals(0, layout.gridHeight);
    }
}
