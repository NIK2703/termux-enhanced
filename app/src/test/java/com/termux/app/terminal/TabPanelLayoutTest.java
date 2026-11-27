package com.termux.app.terminal;

import android.app.Activity;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

/**
 * Coordinates reported by the swipe that opens the overview.
 *
 * <p>The overview hit-tests cards with {@link View#getGlobalVisibleRect}, i.e. in the window's
 * coordinate space. The tab panel must not hand it display-space raw coordinates: in a bubble
 * the window starts away from the display origin and every hit-test point would be shifted.</p>
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TabPanelLayoutTest {

    private static final int PARENT_WIDTH = 1000;
    private static final int PARENT_HEIGHT = 600;
    private static final int PANEL_LEFT = 100;
    private static final int PANEL_TOP = 50;

    @Test
    public void overviewGestureReportsWindowCoordinates() {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final FrameLayout parent = new FrameLayout(activity);
        activity.setContentView(parent);

        final TabPanelLayout panel = new TabPanelLayout(activity);
        final FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(400, 100);
        params.leftMargin = PANEL_LEFT;
        params.topMargin = PANEL_TOP;
        parent.addView(panel, params);
        parent.measure(View.MeasureSpec.makeMeasureSpec(PARENT_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(PARENT_HEIGHT, View.MeasureSpec.EXACTLY));
        parent.layout(0, 0, PARENT_WIDTH, PARENT_HEIGHT);

        final int[] panelOrigin = new int[2];
        panel.getLocationInWindow(panelOrigin);
        Assert.assertTrue("the panel must not sit at the window origin",
                panelOrigin[0] >= PANEL_LEFT && panelOrigin[1] >= PANEL_TOP);

        final Recorder recorder = new Recorder();
        panel.setListener(recorder);

        final long now = SystemClock.uptimeMillis();
        intercept(panel, MotionEvent.ACTION_DOWN, 50f, 20f, now);
        intercept(panel, MotionEvent.ACTION_MOVE, 50f, 80f, now);
        touch(panel, MotionEvent.ACTION_MOVE, 60f, 90f, now);
        touch(panel, MotionEvent.ACTION_UP, 70f, 100f, now);

        Assert.assertTrue(recorder.requested);
        Assert.assertEquals(panelOrigin[0] + 60f, recorder.hoverX, 0.001f);
        Assert.assertEquals(panelOrigin[1] + 90f, recorder.hoverY, 0.001f);
        Assert.assertEquals(panelOrigin[0] + 70f, recorder.releaseX, 0.001f);
        Assert.assertEquals(panelOrigin[1] + 100f, recorder.releaseY, 0.001f);
    }

    private static void intercept(TabPanelLayout panel, int action, float x, float y, long now) {
        final MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try {
            panel.onInterceptTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static void touch(TabPanelLayout panel, int action, float x, float y, long now) {
        final MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try {
            panel.onTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static final class Recorder implements TabPanelLayout.Listener {
        boolean requested;
        float hoverX;
        float hoverY;
        float releaseX;
        float releaseY;

        @Override
        public boolean canOpenOverview() {
            return true;
        }

        @Override
        public boolean opensOnSwipeDown() {
            return true;
        }

        @Override
        public void onOverviewRequested() {
            requested = true;
        }

        @Override
        public void onOverviewGestureReleased(float windowX, float windowY) {
            releaseX = windowX;
            releaseY = windowY;
        }

        @Override
        public void onOverviewGestureHover(float windowX, float windowY) {
            hoverX = windowX;
            hoverY = windowY;
        }

        @Override
        public void onOverviewGestureCancelled() {
        }
    }
}
