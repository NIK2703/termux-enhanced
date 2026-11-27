package com.termux.app.terminal;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

/**
 * Elastic over-drag for an overview grid that fits the viewport without scrolling.
 *
 * <p>A fitted grid has a zero platform scroll range, so the elastic layer must treat every touch
 * frame as travel past an end. It must still keep the range honest outside a gesture.</p>
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SessionOverviewScrollViewTest {

    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;

    @Test
    public void fittedGridPullsDownAtTheTop() {
        SessionOverviewScrollView scroll = newFittedScroll(4);
        Assert.assertFalse(scroll.canScrollVertically(1));

        scroll.overScrollBy(0, -60, 0, 0, 0, 0, 0, 0, true);

        Assert.assertTrue(scroll.getContentVisualOffsetPx() > 0f);
    }

    @Test
    public void fittedGridPullsUpAtTheBottom() {
        SessionOverviewScrollView scroll = newFittedScroll(4);

        scroll.overScrollBy(0, 60, 0, 0, 0, 0, 0, 0, true);

        Assert.assertTrue(scroll.getContentVisualOffsetPx() < 0f);
    }

    @Test
    public void fittedGridIgnoresNonTouchFrames() {
        SessionOverviewScrollView scroll = newFittedScroll(4);

        scroll.overScrollBy(0, 60, 0, 0, 0, 0, 0, 0, false);

        Assert.assertEquals(0f, scroll.getContentVisualOffsetPx(), 0f);
    }

    @Test
    public void verticalDragOnFittedGridPullsTheContent() {
        SessionOverviewScrollView scroll = newFittedScroll(4);
        final float centerX = WIDTH / 2f;
        final float centerY = HEIGHT / 2f;

        send(scroll, MotionEvent.ACTION_DOWN, centerX, centerY);
        send(scroll, MotionEvent.ACTION_MOVE, centerX, centerY - 100f);
        send(scroll, MotionEvent.ACTION_MOVE, centerX, centerY - 200f);

        Assert.assertTrue(scroll.getContentVisualOffsetPx() < 0f);

        send(scroll, MotionEvent.ACTION_UP, centerX, centerY - 200f);
    }

    @Test
    public void horizontalDragOnCardStaysWithTheCard() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TouchView card = new TouchView(activity);
        SessionOverviewScrollView scroll = newFittedScrollWith(activity, card);
        final float x = card.getLeft() + card.getWidth() / 2f;
        final float y = card.getTop() + card.getHeight() / 2f;

        send(scroll, MotionEvent.ACTION_DOWN, x, y);
        send(scroll, MotionEvent.ACTION_MOVE, x + 200f, y);
        send(scroll, MotionEvent.ACTION_MOVE, x + 300f, y);

        Assert.assertEquals(0f, scroll.getContentVisualOffsetPx(), 0f);
        Assert.assertFalse(card.cancelled);

        send(scroll, MotionEvent.ACTION_UP, x + 300f, y);
    }

    @Test
    public void verticalDragOnCardPullsTheGrid() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TouchView card = new TouchView(activity);
        SessionOverviewScrollView scroll = newFittedScrollWith(activity, card);
        final float x = card.getLeft() + card.getWidth() / 2f;
        final float y = card.getTop() + card.getHeight() / 2f;

        send(scroll, MotionEvent.ACTION_DOWN, x, y);
        send(scroll, MotionEvent.ACTION_MOVE, x, y - 100f);
        send(scroll, MotionEvent.ACTION_MOVE, x, y - 200f);

        Assert.assertTrue(scroll.getContentVisualOffsetPx() < 0f);

        send(scroll, MotionEvent.ACTION_UP, x, y - 200f);
    }

    @Test
    public void fittedGridReportsScrollableOnlyWhileTouching() {
        SessionOverviewScrollView scroll = newFittedScroll(4);
        Assert.assertFalse(scroll.canScrollVertically(1));

        send(scroll, MotionEvent.ACTION_DOWN, WIDTH / 2f, HEIGHT / 2f);
        Assert.assertTrue(scroll.canScrollVertically(1));

        send(scroll, MotionEvent.ACTION_UP, WIDTH / 2f, HEIGHT / 2f);
        Assert.assertFalse(scroll.canScrollVertically(1));
    }

    private static SessionOverviewScrollView newFittedScroll(int count) {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SessionOverviewScrollView scroll = new SessionOverviewScrollView(activity);
        SessionOverviewGridView grid = new SessionOverviewGridView(activity);
        for (int i = 0; i < count; i++) grid.addView(new View(activity));
        scroll.addView(grid);
        scroll.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        scroll.layout(0, 0, WIDTH, HEIGHT);
        Assert.assertEquals(HEIGHT, grid.getHeight());
        return scroll;
    }

    private static SessionOverviewScrollView newFittedScrollWith(Activity activity, View card) {
        SessionOverviewScrollView scroll = new SessionOverviewScrollView(activity);
        SessionOverviewGridView grid = new SessionOverviewGridView(activity);
        grid.addView(card);
        scroll.addView(grid);
        scroll.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        scroll.layout(0, 0, WIDTH, HEIGHT);
        Assert.assertEquals(HEIGHT, grid.getHeight());
        return scroll;
    }

    private static final class TouchView extends View {
        boolean cancelled;

        TouchView(Context context) {
            super(context);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) cancelled = true;
            return true;
        }
    }

    private static void send(SessionOverviewScrollView scroll, int action, float x, float y) {
        final long now = SystemClock.uptimeMillis();
        final MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try {
            scroll.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }
}
