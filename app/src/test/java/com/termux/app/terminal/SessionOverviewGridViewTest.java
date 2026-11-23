package com.termux.app.terminal;

import android.app.Activity;
import android.view.View;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

/**
 * End-to-end layout of the real grid view: every session gets a card, cards use the size from
 * the pure geometry class, the viewport is 2x2, extra rows are scrollable, and RTL mirrors the
 * visual order without changing the model order.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SessionOverviewGridViewTest {

    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;

    @Test
    public void everySessionGetsARealCardAndExtraRowsAreScrollable() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        for (int count = 1; count <= 8; count++) {
            SessionOverviewGridView grid = newGrid(activity, count);
            grid.setViewportHeight(HEIGHT);
            grid.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            final int measuredHeight = grid.getMeasuredHeight();
            grid.layout(0, 0, WIDTH, measuredHeight);

            SessionOverviewGridMath.Layout layout = grid.getGridLayout();
            Assert.assertNotNull(layout);
            Assert.assertEquals(count, grid.getChildCount());
            Assert.assertEquals(count, layout.sessionCount);
            Assert.assertEquals(2, layout.columns);
            Assert.assertEquals(Math.max(2, (count + 1) / 2), layout.rows);
            Assert.assertEquals(layout.columns * layout.rows - count, layout.emptySlots);
            Assert.assertTrue(layout.hasCards());

            if (count <= 4) {
                Assert.assertEquals(HEIGHT, measuredHeight);
                Assert.assertFalse(layout.isScrollable());
            } else {
                Assert.assertTrue(measuredHeight > HEIGHT);
                Assert.assertTrue(layout.isScrollable());
            }

            double contentAspect = (double) WIDTH / HEIGHT;
            for (int i = 0; i < count; i++) {
                View card = grid.getChildAt(i);
                Assert.assertEquals(layout.cardWidth, card.getWidth());
                Assert.assertEquals(layout.cardHeight, card.getHeight());
                Assert.assertTrue(card.getLeft() >= 0);
                Assert.assertTrue(card.getTop() >= 0);
                Assert.assertTrue(card.getRight() <= WIDTH);
                Assert.assertTrue(card.getBottom() <= measuredHeight);
                Assert.assertEquals(contentAspect,
                        card.getWidth() / (double) card.getHeight(), 0.05);
            }
        }
    }

    @Test
    public void shortGridSticksToTheTopBelowItsPadding() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SessionOverviewGridView grid = newGrid(activity, 1);
        final int statusBarInset = 96;
        grid.setPadding(0, statusBarInset, 0, 0);
        grid.setViewportHeight(HEIGHT);
        grid.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        grid.layout(0, 0, WIDTH, grid.getMeasuredHeight());

        SessionOverviewGridMath.Layout layout = grid.getGridLayout();
        Assert.assertNotNull(layout);
        Assert.assertEquals(0, layout.top);
        // The grid padding is the status-bar strip the overlay extends into; cards start directly
        // below it instead of being centered in the remaining window.
        Assert.assertEquals(statusBarInset, grid.getChildAt(0).getTop());
    }

    @Test
    public void outerSideGapsMatchTheGapBetweenCards() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SessionOverviewGridView grid = newGrid(activity, 4);
        grid.setViewportHeight(HEIGHT);
        // A square terminal grid is width-limited on this portrait viewport, so the side
        // gutters must be the same size as the gap between the two columns.
        grid.setCardAspect(1.0);
        grid.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        grid.layout(0, 0, WIDTH, grid.getMeasuredHeight());

        final View leftCard = grid.getChildAt(0);
        final View rightCard = grid.getChildAt(1);
        final int innerGap = rightCard.getLeft() - leftCard.getRight();
        final int leftGap = leftCard.getLeft();
        final int rightGap = WIDTH - rightCard.getRight();
        Assert.assertTrue(innerGap > 0);
        Assert.assertEquals(innerGap, leftGap, 1);
        Assert.assertEquals(innerGap, rightGap, 1);
    }

    @Test
    public void cardAspectCanMatchTheTerminalGrid() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SessionOverviewGridView grid = newGrid(activity, 4);
        grid.setViewportHeight(HEIGHT);
        grid.setCardAspect(0.5);
        grid.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        grid.layout(0, 0, WIDTH, grid.getMeasuredHeight());

        SessionOverviewGridMath.Layout layout = grid.getGridLayout();
        Assert.assertNotNull(layout);
        Assert.assertEquals(0.5, layout.cardWidth / (double) layout.cardHeight, 0.01);
        for (int i = 0; i < grid.getChildCount(); i++) {
            View card = grid.getChildAt(i);
            Assert.assertEquals(0.5, card.getWidth() / (double) card.getHeight(), 0.01);
        }
        Assert.assertTrue(layout.gridHeight <= HEIGHT);
        Assert.assertEquals(0, layout.top);
    }

    @Test
    public void rtlMirrorsTheVisualOrderButKeepsSessionOrder() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        // Robolectric's pinned SDK does not resolve an explicit layout direction on a detached
        // view, so supply the one thing the layout code reads. The mirroring below is real.
        SessionOverviewGridView grid = new SessionOverviewGridView(activity) {
            @Override
            public int getLayoutDirection() {
                return View.LAYOUT_DIRECTION_RTL;
            }
        };
        for (int i = 0; i < 4; i++) grid.addView(new View(activity));
        grid.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        grid.layout(0, 0, WIDTH, HEIGHT);

        SessionOverviewGridMath.Layout layout = grid.getGridLayout();
        Assert.assertEquals(2, layout.columns);
        // Logical session 0 is the first child and must sit in the right-most visual column.
        Assert.assertTrue(grid.getChildAt(0).getLeft() > grid.getChildAt(1).getLeft());
        Assert.assertTrue(grid.getChildAt(0).getRight() > grid.getChildAt(1).getRight());
        Assert.assertEquals(grid.getChildAt(0).getTop(), grid.getChildAt(1).getTop());
    }

    @Test
    public void keyboardSizedViewportDoesNotShrinkTheCards() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SessionOverviewGridView grid = newGrid(activity, 4);
        grid.setViewportHeight(HEIGHT);
        grid.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        grid.layout(0, 0, WIDTH, grid.getMeasuredHeight());

        final int cardWidth = grid.getCardWidth();
        final int cardHeight = grid.getCardHeight();

        // The keyboard shrinks the scroll viewport without changing the width. Cards must keep
        // the keyboard-hidden size; the grid may become scrollable until the IME is gone.
        grid.setViewportHeight(HEIGHT / 2);
        grid.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        grid.layout(0, 0, WIDTH, grid.getMeasuredHeight());

        Assert.assertEquals(cardWidth, grid.getCardWidth());
        Assert.assertEquals(cardHeight, grid.getCardHeight());
        Assert.assertTrue(grid.getMeasuredHeight() >= HEIGHT);

        // A width change is a new window shape and is allowed to recompute the cards.
        grid.setViewportHeight(HEIGHT / 2);
        grid.measure(View.MeasureSpec.makeMeasureSpec(WIDTH / 2, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        Assert.assertNotEquals(cardWidth, grid.getCardWidth());
    }

    @Test
    public void slotCentersMatchTheLaidOutCards() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        for (int count = 1; count <= 8; count++) {
            SessionOverviewGridView grid = newGrid(activity, count);
            grid.setViewportHeight(HEIGHT);
            grid.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            grid.layout(0, 0, WIDTH, grid.getMeasuredHeight());

            float[] centers = grid.getSlotCenters(count);
            Assert.assertNotNull(centers);
            Assert.assertEquals(count * 2, centers.length);
            for (int i = 0; i < count; i++) {
                View card = grid.getChildAt(i);
                Assert.assertEquals(card.getLeft() + card.getWidth() / 2f,
                        centers[i * 2], 0.001f);
                Assert.assertEquals(card.getTop() + card.getHeight() / 2f,
                        centers[i * 2 + 1], 0.001f);
            }
        }
    }

    @Test
    public void slotCentersFollowRtlAndRejectUnusableGeometry() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SessionOverviewGridView grid = new SessionOverviewGridView(activity) {
            @Override
            public int getLayoutDirection() {
                return View.LAYOUT_DIRECTION_RTL;
            }
        };
        for (int i = 0; i < 4; i++) grid.addView(new View(activity));
        grid.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        grid.layout(0, 0, WIDTH, HEIGHT);

        float[] centers = grid.getSlotCenters(4);
        Assert.assertNotNull(centers);
        Assert.assertTrue(centers[0] > centers[2]);
        Assert.assertEquals(centers[1], centers[3], 0.001f);
        Assert.assertNull(grid.getSlotCenters(0));

        SessionOverviewGridView empty = new SessionOverviewGridView(activity);
        Assert.assertNull(empty.getSlotCenters(3));
    }

    @Test
    public void listenerReportsTheSizeThatWasLaidOut() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SessionOverviewGridView grid = newGrid(activity, 5);
        final int[] reported = {-1, -1, -1, -1};
        grid.setOnGridLayoutListener((w, h, columns, rows) -> {
            reported[0] = w;
            reported[1] = h;
            reported[2] = columns;
            reported[3] = rows;
        });

        grid.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        grid.layout(0, 0, WIDTH, HEIGHT);

        SessionOverviewGridMath.Layout layout = grid.getGridLayout();
        Assert.assertEquals(layout.cardWidth, reported[0]);
        Assert.assertEquals(layout.cardHeight, reported[1]);
        Assert.assertEquals(2, reported[2]);
        Assert.assertEquals(3, reported[3]);
    }

    @Test
    public void scrollViewMakesTheExtraRowsReachable() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SessionOverviewScrollView scroll = new SessionOverviewScrollView(activity);
        SessionOverviewGridView grid = newGrid(activity, 5);
        scroll.addView(grid);

        scroll.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        scroll.layout(0, 0, WIDTH, HEIGHT);

        Assert.assertEquals(HEIGHT, scroll.getHeight());
        Assert.assertTrue(grid.getMeasuredHeight() > HEIGHT);
        Assert.assertTrue(scroll.canScrollVertically(1));

        final int maxScroll = grid.getMeasuredHeight() - HEIGHT;
        scroll.scrollTo(0, maxScroll);
        Assert.assertEquals(maxScroll, scroll.getScrollY());
    }

    private static SessionOverviewGridView newGrid(Activity activity, int count) {
        SessionOverviewGridView grid = new SessionOverviewGridView(activity);
        for (int i = 0; i < count; i++) grid.addView(new View(activity));
        return grid;
    }
}
