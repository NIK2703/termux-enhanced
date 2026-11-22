package com.termux.app.terminal;

import android.app.Activity;
import android.content.Context;
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
import org.robolectric.shadows.ShadowLooper;

/** Behaviour of the card's horizontal swipe-to-close gesture. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SessionOverviewCardViewTest {

    private static final int WIDTH = 1000;
    private static final int HEIGHT = 600;

    @Test
    public void tapSelectsTheCard() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TestCard card = newCard(activity);

        send(card, MotionEvent.ACTION_DOWN, 500f);
        send(card, MotionEvent.ACTION_UP, 500f);
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        Assert.assertEquals(1, card.clicks);
    }

    @Test
    public void verticalDragDoesNotSelectTheCard() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TestCard card = newCard(activity);

        send(card, MotionEvent.ACTION_DOWN, 500f);
        send(card, MotionEvent.ACTION_MOVE, 500f, 420f);
        send(card, MotionEvent.ACTION_UP, 500f, 420f);
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        Assert.assertEquals(0, card.clicks);
    }

    @Test
    public void crossingTheDismissDistanceTicksOncePerEntry() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TestCard card = newCard(activity);

        send(card, MotionEvent.ACTION_DOWN, 500f);
        send(card, MotionEvent.ACTION_MOVE, 600f); // dx = 100, below 28% of 1000.
        Assert.assertEquals(0, card.haptics);

        send(card, MotionEvent.ACTION_MOVE, 700f); // translation 100 -> dx = 300.
        Assert.assertEquals(1, card.haptics);

        send(card, MotionEvent.ACTION_MOVE, 600f); // translation 300 -> dx = 400.
        Assert.assertEquals(1, card.haptics);

        send(card, MotionEvent.ACTION_MOVE, 200f); // translation 400 -> dx = 100.
        Assert.assertEquals(1, card.haptics);

        send(card, MotionEvent.ACTION_MOVE, 700f); // translation 100 -> dx = 300 again.
        Assert.assertEquals(2, card.haptics);
    }

    private static TestCard newCard(Activity activity) {
        final FrameLayout parent = new FrameLayout(activity);
        activity.setContentView(parent);
        final TestCard card = new TestCard(activity);
        parent.addView(card, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        card.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        card.layout(0, 0, WIDTH, HEIGHT);
        return card;
    }

    private static void send(TestCard card, int action, float x) {
        send(card, action, x, 300f);
    }

    private static void send(TestCard card, int action, float x, float y) {
        final long now = SystemClock.uptimeMillis();
        final MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        card.onTouchEvent(event);
        event.recycle();
    }

    /** Counts clicks and haptic ticks without needing a real window or vibrator. */
    private static final class TestCard extends SessionOverviewCardView {
        int clicks;
        int haptics;

        TestCard(Context context) {
            super(context);
            setClickable(true);
        }

        @Override
        public boolean performClick() {
            clicks++;
            return super.performClick();
        }

        @Override
        public boolean performHapticFeedback(int feedbackConstant) {
            haptics++;
            return true;
        }
    }
}
