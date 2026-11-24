package com.termux.app.terminal;

import android.app.Activity;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

/**
 * Pins the tab-strip gesture: only a vertical-dominant swipe toward the terminal opens the
 * overview, and a single gesture opens it at most once. The {@code +} button's own gesture is
 * protected by its {@code requestDisallowInterceptTouchEvent} call, which is Android's job to
 * honour; here we cover the decision this class owns.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TabPanelLayoutTest {

    private TabPanelLayout mPanel;
    private FakeListener mListener;

    @Before
    public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        mPanel = new TabPanelLayout(activity);
        mListener = new FakeListener();
        mPanel.setListener(mListener);
    }

    @Test
    public void topPanelOpensOnDownwardSwipeExactlyOnce() {
        Assert.assertFalse(down(100, 100));
        Assert.assertTrue(move(100, 220));
        Assert.assertEquals(1, mListener.opened);

        // The rest of a consumed gesture must not reopen or do extra work.
        Assert.assertFalse(move(100, 320));
        Assert.assertFalse(move(100, 420));
        Assert.assertEquals(1, mListener.opened);
    }

    @Test
    public void bottomPanelOpensOnUpwardSwipe() {
        mListener.opensOnSwipeDown = false;

        Assert.assertFalse(down(100, 500));
        Assert.assertTrue(move(100, 380));
        Assert.assertEquals(1, mListener.opened);
    }

    @Test
    public void wrongDirectionDoesNothing() {
        Assert.assertFalse(down(100, 500));
        Assert.assertFalse(move(100, 380));
        Assert.assertEquals(0, mListener.opened);
    }

    @Test
    public void horizontalDragBelongsToTheTabStrip() {
        Assert.assertFalse(down(100, 100));
        // dy is past the slop but dx dominates: this is a tab-strip scroll, not an overview swipe.
        Assert.assertFalse(move(240, 130));
        Assert.assertEquals(0, mListener.opened);
    }

    @Test
    public void slopMustBeExceeded() {
        Assert.assertFalse(down(100, 100));
        Assert.assertFalse(move(100, 101));
        Assert.assertEquals(0, mListener.opened);
    }

    @Test
    public void noSessionsMeansNoInterception() {
        mListener.canOpen = false;
        Assert.assertFalse(down(100, 100));
        Assert.assertFalse(move(100, 220));
        Assert.assertEquals(0, mListener.opened);
    }

    @Test
    public void childDisallowFlagWins() {
        // The (+) button does exactly this on ACTION_DOWN so its directory-history swipe is not
        // stolen by the overview gesture. Android's disallow flag is what protects it.
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TabPanelLayout panel = new TabPanelLayout(activity);
        FakeListener listener = new FakeListener();
        panel.setListener(listener);
        View child = new DisallowChild(activity);
        panel.addView(child, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        panel.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
        panel.layout(0, 0, 500, 100);

        Assert.assertTrue(panel.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100, 20)));
        Assert.assertTrue(panel.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 100, 80)));
        Assert.assertEquals(0, listener.opened);
    }

    @Test
    public void releaseAfterOpeningReportsTheFingerPosition() {
        Assert.assertFalse(down(100, 100));
        Assert.assertTrue(move(100, 220));
        Assert.assertTrue(consumedUp(240, 300));
        Assert.assertEquals(1, mListener.released);
        Assert.assertEquals(240f, mListener.releaseX, 0f);
        Assert.assertEquals(300f, mListener.releaseY, 0f);
    }

    @Test
    public void cancelAfterOpeningNeverSelects() {
        Assert.assertFalse(down(100, 100));
        Assert.assertTrue(move(100, 220));
        MotionEvent cancel = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 240, 300, 0);
        try {
            Assert.assertTrue(mPanel.onTouchEvent(cancel));
        } finally {
            cancel.recycle();
        }
        Assert.assertEquals(0, mListener.released);
    }

    @Test
    public void aNewGestureCanOpenAfterTheOldOneEnds() {
        Assert.assertFalse(down(100, 100));
        Assert.assertTrue(move(100, 220));
        // After interception the rest of the stream is delivered to onTouchEvent.
        Assert.assertTrue(consumedUp(100, 220));

        Assert.assertFalse(down(100, 100));
        Assert.assertTrue(move(100, 220));
        Assert.assertEquals(2, mListener.opened);
    }

    private boolean down(float x, float y) {
        return send(MotionEvent.ACTION_DOWN, x, y);
    }

    private boolean move(float x, float y) {
        return send(MotionEvent.ACTION_MOVE, x, y);
    }

    private boolean consumedUp(float x, float y) {
        MotionEvent event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, x, y, 0);
        try {
            return mPanel.onTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private boolean send(int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0L, 0L, action, x, y, 0);
        try {
            return mPanel.onInterceptTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static MotionEvent event(int action, float x, float y) {
        return MotionEvent.obtain(0L, 0L, action, x, y, 0);
    }

    private static final class DisallowChild extends View {
        DisallowChild(Context context) {
            super(context);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            return true;
        }
    }

    private static final class FakeListener implements TabPanelLayout.Listener {
        boolean canOpen = true;
        boolean opensOnSwipeDown = true;
        int opened;
        int released;
        float releaseX;
        float releaseY;

        @Override
        public boolean canOpenOverview() {
            return canOpen;
        }

        @Override
        public boolean opensOnSwipeDown() {
            return opensOnSwipeDown;
        }

        @Override
        public void onOverviewRequested() {
            opened++;
        }

        @Override
        public void onOverviewGestureReleased(float rawX, float rawY) {
            released++;
            releaseX = rawX;
            releaseY = rawY;
        }
    }
}
