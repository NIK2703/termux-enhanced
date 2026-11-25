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
                        final int cardsHeight = layout.rows * layout.cardHeight
                                + (layout.rows - 1) * layout.gap;
                        final int bottomSpace = height - layout.top - cardsHeight;
                        Assert.assertTrue("grid must be vertically centred",
                                Math.abs(layout.top - bottomSpace) <= 1);
                    } else {
                        Assert.assertTrue(layout.isScrollable());
                        Assert.assertTrue(layout.gridHeight > height);
                        Assert.assertEquals(layout.gap, layout.top);
                    }
                }
            }
        }
    }

    @Test
    public void sideGuttersMatchTheInterCardGapWhenWidthIsTheLimit() {
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                SessionOverviewGridMath.Layout layout =
                        SessionOverviewGridMath.compute(4, width, height, 16, 1.0);
                if (!layout.hasCards()) continue;

                final int innerGap = layout.gridWidth - 2 * layout.cardWidth;
                final int leftGutter = layout.left;
                final int rightGutter =
                        layout.availableWidth - layout.left - layout.gridWidth;
                Assert.assertEquals(layout.gap, innerGap);
                Assert.assertTrue(leftGutter >= layout.gap);
                Assert.assertTrue(rightGutter >= layout.gap);
                Assert.assertTrue(Math.abs(leftGutter - rightGutter) <= 1);

                final int widthLimitedCardWidth =
                        (layout.availableWidth - 3 * layout.gap) / 2;
                if (layout.cardWidth == widthLimitedCardWidth) {
                    Assert.assertTrue(Math.abs(leftGutter - layout.gap) <= 1);
                    Assert.assertTrue(Math.abs(rightGutter - layout.gap) <= 1);
                }
            }
        }
    }

    @Test
    public void shortGridIsCentredAndScrollingGridKeepsItsOuterGutters() {
        for (int count = 1; count <= 8; count++) {
            for (int width : WIDTHS) {
                for (int height : HEIGHTS) {
                    SessionOverviewGridMath.Layout layout =
                            SessionOverviewGridMath.compute(count, width, height, 16, 0.5);
                    if (!layout.hasCards()) continue;

                    final int lastRow = layout.rows - 1;
                    final int lastRowBottom = layout.top
                            + lastRow * (layout.cardHeight + layout.gap)
                            + layout.cardHeight;
                    Assert.assertEquals(layout.gap, layout.gridHeight - lastRowBottom);

                    if (layout.rows <= SessionOverviewGridMath.VISIBLE_ROWS) {
                        // The remaining height is split equally, so the empty strip above the
                        // first card matches the one below the last card.
                        final int cardsHeight = layout.rows * layout.cardHeight
                                + (layout.rows - 1) * layout.gap;
                        final int bottomSpace = layout.availableHeight - layout.top - cardsHeight;
                        Assert.assertTrue(Math.abs(layout.top - bottomSpace) <= 1);
                        Assert.assertTrue(layout.gridHeight <= layout.availableHeight);
                    } else {
                        Assert.assertEquals(layout.gap, layout.top);
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
        Assert.assertEquals(five.gap, five.top);
        Assert.assertTrue(four.top > four.gap);
        Assert.assertFalse(four.isScrollable());
        Assert.assertTrue(five.isScrollable());
    }

    @Test
    public void defaultCardsKeepTheContentAreaAspectRatio() {
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
    public void cardsCanUseTheTerminalGridAspectRatio() {
        final double[] aspects = {0.35, 0.5, 0.75, 1.0, 1.5, 2.0};
        for (int count = 1; count <= 8; count++) {
            for (int width : WIDTHS) {
                for (int height : HEIGHTS) {
                    for (double aspect : aspects) {
                        SessionOverviewGridMath.Layout layout =
                                SessionOverviewGridMath.compute(count, width, height, 12, aspect);
                        Assert.assertTrue("cards must be positive for " + width + "x" + height,
                                layout.cardWidth > 0 && layout.cardHeight > 0);
                        Assert.assertTrue(layout.gridWidth <= width);
                        if (layout.rows <= SessionOverviewGridMath.VISIBLE_ROWS) {
                            Assert.assertTrue(layout.gridHeight <= height);
                        }
                        // Flooring the height keeps the card at least as wide as the requested
                        // ratio; the error stays below one aspect unit.
                        Assert.assertTrue(
                                "aspect error too large for " + count + " at "
                                        + width + "x" + height + " ratio " + aspect,
                                Math.abs(layout.cardWidth - layout.cardHeight * aspect) < aspect);
                    }
                }
            }
        }
    }

    @Test
    public void invalidCardAspectFallsBackToTheContentArea() {
        SessionOverviewGridMath.Layout fallback =
                SessionOverviewGridMath.compute(4, 1080, 1920, 24, 0);
        SessionOverviewGridMath.Layout content =
                SessionOverviewGridMath.compute(4, 1080, 1920, 24);

        Assert.assertEquals(content.cardWidth, fallback.cardWidth);
        Assert.assertEquals(content.cardHeight, fallback.cardHeight);
        Assert.assertEquals(content.left, fallback.left);
        Assert.assertEquals(content.top, fallback.top);
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
            // Horizontal centering slack can shift by a few pixels after flooring; only the card
            // size is expected to stay stable in dp.
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
    public void recenteringUsesTheVisibleViewportNotTheStableSizingHeight() {
        SessionOverviewGridMath.Layout sized =
                SessionOverviewGridMath.compute(4, 1080, 1920, 24);
        SessionOverviewGridMath.Layout visible =
                SessionOverviewGridMath.recenter(sized, 1000);

        Assert.assertEquals(sized.cardWidth, visible.cardWidth);
        Assert.assertEquals(sized.cardHeight, visible.cardHeight);
        Assert.assertEquals(sized.rows, visible.rows);
        Assert.assertEquals(sized.gap, visible.gap);

        final int baseHeight = visible.rows * visible.cardHeight
                + visible.gap * (visible.rows + 1);
        final int expectedCentring = Math.max(0, 1000 - baseHeight) / 2;
        Assert.assertEquals(visible.gap + expectedCentring, visible.top);
        Assert.assertEquals(baseHeight + expectedCentring, visible.gridHeight);

        SessionOverviewGridMath.Layout restored =
                SessionOverviewGridMath.recenter(sized, 1920);
        Assert.assertEquals(sized.top, restored.top);
        Assert.assertEquals(sized.gridHeight, restored.gridHeight);
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

    @Test
    public void exactCardSizeNeverOverflowsTheViewport() {
        // Exercise the leftover-width 0..2 px cases where three min-width gutters do not fit.
        for (int availableWidth = 0; availableWidth <= 200; availableWidth++) {
            final SessionOverviewGridMath.Layout layout =
                    SessionOverviewGridMath.compute(4, availableWidth, 800, 8, 12, 40);
            if (!layout.hasCards()) continue;
            Assert.assertTrue("width=" + availableWidth + " left=" + layout.left,
                    layout.left >= 0);
            Assert.assertTrue("width=" + availableWidth + " right=" + layout.left + layout.gridWidth,
                    layout.left + layout.gridWidth <= availableWidth);
            Assert.assertTrue("width=" + availableWidth + " inner gap=" + layout.gap,
                    layout.gap >= 1);
            Assert.assertEquals("width=" + availableWidth + " published gap",
                    layout.gap, layout.gridWidth - 2 * layout.cardWidth);
        }
    }
}
