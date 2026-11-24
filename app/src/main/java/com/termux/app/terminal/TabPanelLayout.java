package com.termux.app.terminal;

import android.content.Context;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/**
 * The session tab strip container, with one extra gesture: a vertical swipe toward the terminal
 * opens the all-sessions overview.
 *
 * <p>Direction follows the panel's position, queried live through {@link Listener}, so moving the
 * tab strip from the top to the bottom of the window needs no separate wiring. Horizontal drags,
 * the tab strip's elastic scroll and the directory-history swipe on the {@code +} button are
 * untouched: only a vertical-dominant movement past the system touch slop is intercepted, and a
 * child that calls {@code requestDisallowInterceptTouchEvent} wins as usual.</p>
 */
public class TabPanelLayout extends LinearLayout {

    public interface Listener {
        /** Whether there is anything to show. */
        boolean canOpenOverview();

        /** True when the panel is at the top, i.e. a downward swipe points at the terminal. */
        boolean opensOnSwipeDown();

        /** Called once per gesture, after the movement has been recognized. */
        void onOverviewRequested();

        /**
         * Called on the release that ends the gesture which opened the overview.
         *
         * @param rawX screen x of the release, usable for hit-testing the card grid
         * @param rawY screen y of the release
         */
        void onOverviewGestureReleased(float rawX, float rawY);
    }

    private final int mTouchSlop;
    private float mDownX;
    private float mDownY;
    private boolean mTracking;
    private boolean mGestureConsumed;
    /** Primary pointer of the gesture that opened the overview. */
    private int mOverviewPointerId = -1;
    @Nullable
    private Listener mListener;

    public TabPanelLayout(Context context) {
        this(context, null);
    }

    public TabPanelLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getX();
                mDownY = event.getY();
                mTracking = true;
                mGestureConsumed = false;
                break;
            case MotionEvent.ACTION_MOVE: {
                if (!mTracking || mGestureConsumed) break;
                final float dx = event.getX() - mDownX;
                final float dy = event.getY() - mDownY;
                if (Math.abs(dy) <= mTouchSlop || Math.abs(dy) <= Math.abs(dx)) break;

                final Listener listener = mListener;
                if (listener == null || !listener.canOpenOverview()) break;
                final boolean towardTerminal = listener.opensOnSwipeDown() ? dy > 0 : dy < 0;
                if (!towardTerminal) break;

                mGestureConsumed = true;
                mOverviewPointerId = event.getPointerId(0);
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                listener.onOverviewRequested();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mTracking = false;
                mGestureConsumed = false;
                mOverviewPointerId = -1;
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mGestureConsumed) {
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP) {
                final Listener listener = mListener;
                if (listener != null && event.getPointerCount() == 1
                        && event.getPointerId(0) == mOverviewPointerId) {
                    // The finger never left the screen: let the controller select whatever card
                    // is under it now. The card itself never received ACTION_DOWN, so its own
                    // click handler cannot do this.
                    listener.onOverviewGestureReleased(event.getRawX(), event.getRawY());
                }
                mGestureConsumed = false;
                mTracking = false;
                mOverviewPointerId = -1;
            } else if (action == MotionEvent.ACTION_CANCEL) {
                mGestureConsumed = false;
                mTracking = false;
                mOverviewPointerId = -1;
            }
            return true;
        }
        return super.onTouchEvent(event);
    }
}
