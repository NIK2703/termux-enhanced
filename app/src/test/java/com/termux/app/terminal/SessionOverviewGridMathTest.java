package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

/**
 * Property tests for the pure flow geometry. Row counts are derived metadata here; centring is
 * tested against the union of occupied slots for every session count and window shape.
 */
public class SessionOverviewGridMathTest {

    private static final int[] WIDTHS = {320, 360, 411, 600, 720, 900, 1280, 1600, 2560};
    private static final int[] HEIGHTS = {240, 320, 480, 640, 720, 900, 1080, 1600, 2560};

    @Test
    public void derivedRowCountMatchesTheFlowForEverySessionCount() {
        for (int count = 1; count <= 8; count++) {
            SessionOverviewGridMath.Layout layout =
                    SessionOverviewGridMath.compute(count, 1080, 1920, 24);
            Assert.assertEquals(2, layout.columns);
            Assert.assertEquals((count + 1) / 2, layout.rows);
            Assert.assertEquals(layout.columns * layout.rows - count, layout.emptySlots);
            Assert.assertEquals(count, layout.slotCount());
        }
    }

    @Test
    public void oneCardIsCentredOnBothAxes() {
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                SessionOverviewGridMath.Layout layout =
                        SessionOverviewGridMath.compute(1, width, height, 12, 0.6);
                if (!layout.hasCards()) continue;
                final SessionOverviewGridMath.Slot slot = layout.slot(0);
                Assert.assertEquals("horizontal centre", (double) width / 2, slot.centerX(), 1.5);
                Assert.assertEquals("vertical centre", (double) height / 2, slot.centerY(), 1.5);
                Assert.assertFalse(layout.isScrollable());
            }
        }
    }

    @Test
    public void occupiedBoundsAreCentredNotTheReservedTwoRowBlock() {
        SessionOverviewGridMath.Layout one =
                SessionOverviewGridMath.compute(1, 1080, 1920, 24);
        SessionOverviewGridMath.Layout two =
                SessionOverviewGridMath.compute(2, 1080, 1920, 24);

        Assert.assertEquals(one.cardWidth, one.contentWidth);
        Assert.assertEquals(one.cardHeight, one.contentHeight);
        Assert.assertEquals((1920 - one.contentHeight) / 2, one.top);
        Assert.assertEquals((1080 - one.contentWidth) / 2, one.left);

        Assert.assertTrue(two.contentWidth > one.contentWidth);
        Assert.assertEquals(one.contentHeight, two.contentHeight);
        Assert.assertEquals((1920 - two.contentHeight) / 2, two.top);
        Assert.assertEquals(one.cardWidth, two.cardWidth);
        Assert.assertEquals(one.cardHeight, two.cardHeight);
    }

    @Test
    public void outerGuttersAreIndependentOfTheCardGap() {
        SessionOverviewGridMath.Layout layout =
                SessionOverviewGridMath.compute(6, 1080, 1920, 4, 16, 0d);
        Assert.assertEquals(4, layout.gap);
        Assert.assertEquals(16, layout.outerGap);
        Assert.assertEquals(3, layout.rows);
        Assert.assertEquals(16, layout.top);
        Assert.assertEquals(layout.contentHeight + 2 * layout.outerGap, layout.gridHeight);
        Assert.assertFalse(layout.gridHeight <= layout.visibleHeight);
    }

    @Test
    public void shortContentIsCentredAndOverflowKeepsTheOuterGutter() {
        for (int count = 1; count <= 8; count++) {
            for (int width : WIDTHS) {
                for (int height : HEIGHTS) {
                    SessionOverviewGridMath.Layout layout =
                            SessionOverviewGridMath.compute(count, width, height, 16);
                    if (!layout.hasCards()) continue;
                    Assert.assertTrue(layout.contentWidth <= width);
                    Assert.assertTrue(layout.left >= 0);
                    Assert.assertTrue(layout.left + layout.contentWidth <= width);

                    if (layout.isScrollable()) {
                        Assert.assertEquals(layout.outerGap, layout.top);
                        Assert.assertEquals(layout.contentHeight + 2 * layout.outerGap,
                                layout.gridHeight);
                    } else {
                        Assert.assertEquals(layout.gridHeight, layout.visibleHeight);
                        Assert.assertEquals((height - layout.contentHeight) / 2, layout.top);
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

                final int innerGap = layout.contentWidth - 2 * layout.cardWidth;
                final int leftGutter = layout.left;
                final int rightGutter =
                        layout.availableWidth - layout.left - layout.contentWidth;
                Assert.assertEquals(layout.gap, innerGap);
                Assert.assertTrue(leftGutter >= layout.gap);
                Assert.assertTrue(rightGutter >= layout.gap);
                Assert.assertTrue(Math.abs(leftGutter - rightGutter) <= 1);
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
        Assert.assertEquals(five.outerGap, five.top);
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
                    if (!layout.hasCards()) continue;
                    double expectedAspect = (double) width / height;
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
                        if (!layout.hasCards()) continue;
                        Assert.assertTrue(layout.contentWidth <= width);
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
    public void slotsNeverOverlapAndStayInsideTheOccupiedBounds() {
        for (int count = 1; count <= 8; count++) {
            SessionOverviewGridMath.Layout layout =
                    SessionOverviewGridMath.compute(count, 1280, 800, 20);
            if (!layout.hasCards()) continue;
            for (int i = 0; i < count; i++) {
                final SessionOverviewGridMath.Slot slot = layout.slot(i);
                Assert.assertTrue(slot.left >= layout.left);
                Assert.assertTrue(slot.top >= layout.top);
                Assert.assertTrue(slot.right <= layout.left + layout.contentWidth);
                Assert.assertTrue(slot.bottom <= layout.top + layout.contentHeight);
                for (int j = i + 1; j < count; j++) {
                    final SessionOverviewGridMath.Slot other = layout.slot(j);
                    final boolean overlap = slot.left < other.right && other.left < slot.right
                            && slot.top < other.bottom && other.top < slot.bottom;
                    Assert.assertFalse("slots " + i + " and " + j + " overlap", overlap);
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
            if (!base.hasCards() || !scaled.hasCards()) continue;

            Assert.assertEquals(base.columns, scaled.columns);
            Assert.assertEquals(base.rows, scaled.rows);
            Assert.assertTrue(Math.abs(scaled.cardWidth / 3.0 - base.cardWidth) <= 2.0);
            Assert.assertTrue(Math.abs(scaled.cardHeight / 3.0 - base.cardHeight) <= 2.0);
        }
    }

    @Test
    public void swappingOrientationKeepsTheSameGridShape() {
        for (int count = 1; count <= 8; count++) {
            SessionOverviewGridMath.Layout portrait =
                    SessionOverviewGridMath.compute(count, 720, 1280, 16);
            SessionOverviewGridMath.Layout landscape =
                    SessionOverviewGridMath.compute(count, 1280, 720, 16);
            if (!portrait.hasCards() || !landscape.hasCards()) continue;

            Assert.assertEquals(portrait.columns, landscape.columns);
            Assert.assertEquals(portrait.rows, landscape.rows);
            Assert.assertEquals(portrait.emptySlots, landscape.emptySlots);
            Assert.assertTrue(landscape.contentWidth <= 1280);
        }
    }

    @Test
    public void oneCardIsCentredOnBothAxesForEveryViewport() {
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                final SessionOverviewGridMath.Layout layout =
                        SessionOverviewGridMath.compute(1, width, height, 16, 0.5);
                if (!layout.hasCards()) continue;
                final SessionOverviewGridMath.Slot slot = layout.slot(0);
                Assert.assertNotNull(slot);
                Assert.assertTrue(Math.abs(layout.centerX(0, false) - width / 2f) <= 1f);
                Assert.assertTrue(Math.abs(layout.centerY(0) - height / 2f) <= 1f);
                Assert.assertTrue(Math.abs(slot.centerX() - width / 2f) <= 1f);
                Assert.assertTrue(Math.abs(slot.centerY() - height / 2f) <= 1f);
                Assert.assertEquals(0, layout.gridHeight - height);
                Assert.assertFalse(layout.isScrollable());
            }
        }
    }

    @Test
    public void arbitraryColumnPoliciesUseOnlySlotGeometry() {
        for (int count = 0; count <= 100; count++) {
            for (int columns = 1; columns <= 8; columns++) {
                for (int width : new int[]{400, 800, 1600}) {
                    for (int height : new int[]{600, 1200}) {
                        final SessionOverviewGridMath.Layout layout =
                                SessionOverviewGridMath.buildLayout(count, columns, 4, 8,
                                        width, height, 20, 10);
                        if (!layout.hasCards()) {
                            Assert.assertEquals(0, layout.slotCount());
                            Assert.assertEquals(0, layout.gridWidth);
                            Assert.assertEquals(0, layout.gridHeight);
                            continue;
                        }

                        int minLeft = Integer.MAX_VALUE;
                        int minTop = Integer.MAX_VALUE;
                        int maxRight = Integer.MIN_VALUE;
                        int maxBottom = Integer.MIN_VALUE;
                        for (int i = 0; i < count; i++) {
                            final SessionOverviewGridMath.Slot slot = layout.slot(i);
                            Assert.assertNotNull(slot);
                            minLeft = Math.min(minLeft, slot.left);
                            minTop = Math.min(minTop, slot.top);
                            maxRight = Math.max(maxRight, slot.right);
                            maxBottom = Math.max(maxBottom, slot.bottom);
                            Assert.assertEquals(layout.cardWidth, slot.width());
                            Assert.assertEquals(layout.cardHeight, slot.height());
                            Assert.assertEquals(slot.centerX(), layout.centerX(i, false), 0f);
                            Assert.assertEquals(slot.centerY(), layout.centerY(i), 0f);
                        }

                        Assert.assertEquals(layout.left, minLeft);
                        Assert.assertEquals(layout.top, minTop);
                        Assert.assertEquals(layout.contentWidth, maxRight - minLeft);
                        Assert.assertEquals(layout.contentHeight, maxBottom - minTop);
                        Assert.assertEquals(width, layout.availableWidth);
                        Assert.assertEquals(height, layout.availableHeight);
                        Assert.assertEquals(columns, layout.columns);
                        Assert.assertEquals((count + columns - 1) / columns, layout.rows);

                        if (layout.isScrollable()) {
                            Assert.assertEquals(layout.outerGap, layout.top);
                            Assert.assertEquals(layout.contentHeight + 2 * layout.outerGap,
                                    layout.gridHeight);
                        } else {
                            Assert.assertTrue(Math.abs(layout.left * 2 + layout.contentWidth
                                    - width) <= 1);
                            Assert.assertTrue(Math.abs(layout.top * 2 + layout.contentHeight
                                    - height) <= 1);
                            Assert.assertEquals(height, layout.gridHeight);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void rtlMirrorsVisualPositionsWithoutMovingACentredCard() {
        for (int count = 1; count <= 20; count++) {
            final SessionOverviewGridMath.Layout layout =
                    SessionOverviewGridMath.buildLayout(count, 3, 8, 16,
                            800, 1200, 100, 80);
            if (!layout.hasCards()) continue;
            for (int i = 0; i < count; i++) {
                final SessionOverviewGridMath.Slot slot = layout.slot(i);
                Assert.assertNotNull(slot);
                Assert.assertEquals(800 - slot.right, layout.visualLeft(i, true));
                Assert.assertEquals(800 - slot.centerX(), layout.centerX(i, true), 0f);
            }
        }

        final SessionOverviewGridMath.Layout single =
                SessionOverviewGridMath.buildLayout(1, 1, 8, 16, 800, 1200, 100, 80);
        Assert.assertTrue(single.hasCards());
        Assert.assertEquals(single.centerX(0, false), single.centerX(0, true), 0f);
        Assert.assertEquals(single.visualLeft(0, false), single.visualLeft(0, true));
    }

    @Test
    public void noSessionsProducesAnEmptyLayout() {
        SessionOverviewGridMath.Layout layout =
                SessionOverviewGridMath.compute(0, 1080, 1920, 16);
        Assert.assertEquals(0, layout.columns);
        Assert.assertEquals(0, layout.rows);
        Assert.assertEquals(0, layout.emptySlots);
        Assert.assertEquals(0, layout.slotCount());
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

        if (visible.contentHeight + 2 * visible.outerGap <= 1000) {
            Assert.assertEquals((1000 - visible.contentHeight) / 2, visible.top);
            Assert.assertEquals(1000, visible.gridHeight);
        } else {
            Assert.assertEquals(visible.outerGap, visible.top);
            Assert.assertEquals(visible.contentHeight + 2 * visible.outerGap,
                    visible.gridHeight);
        }
        for (int i = 0; i < visible.slotCount(); i++) {
            Assert.assertEquals(sized.slot(i).left, visible.slot(i).left);
            Assert.assertEquals(sized.slot(i).top + visible.top - sized.top,
                    visible.slot(i).top);
        }

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
        Assert.assertEquals(0, layout.contentWidth);
        Assert.assertEquals(0, layout.contentHeight);
        Assert.assertFalse(layout.hasCards());
        Assert.assertTrue(layout.left >= 0);
        Assert.assertTrue(layout.top >= 0);
    }

    @Test
    public void exactCardSizeNeverOverflowsTheViewport() {
        for (int availableWidth = 0; availableWidth <= 200; availableWidth++) {
            final SessionOverviewGridMath.Layout layout =
                    SessionOverviewGridMath.compute(4, availableWidth, 800, 8, 12, 40);
            if (!layout.hasCards()) continue;
            Assert.assertTrue("width=" + availableWidth + " left=" + layout.left,
                    layout.left >= 0);
            Assert.assertTrue("width=" + availableWidth + " right="
                            + layout.left + layout.contentWidth,
                    layout.left + layout.contentWidth <= availableWidth);
            Assert.assertEquals("width=" + availableWidth + " published gap",
                    layout.gap, layout.contentWidth - 2 * layout.cardWidth);
        }
    }
}
