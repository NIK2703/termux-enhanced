package com.termux.app.terminal;

import android.content.Context;
import android.os.Build;
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

    /**
     * Softest tick available on this device, shared by the opening gesture and the card-hover
     * feedback so both feel identical. API 34 has a dedicated very soft segment tick; API 27+ has
     * the text-handle tick, which is much lighter than a clock/key click; older devices fall back
     * to a virtual key tap.
     */
    public static final int OVERVIEW_GESTURE_FEEDBACK =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    ? HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                    : Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
                            ? HapticFeedbackConstants.TEXT_HANDLE_MOVE
                            : HapticFeedbackConstants.VIRTUAL_KEY;

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

        /** Called while the finger moves over the open overview, for hover highlighting. */
        void onOverviewGestureHover(float rawX, float rawY);

        /** Called when the opened gesture is cancelled before release. */
        void onOverviewGestureCancelled();
    }

    private final int mTouchSlop;
    private float mDownX;
    private float mDownY;
    private boolean mTracking;
    private boolean mGestureConsumed;
    /** Primary pointer of the gesture that opened the overview. */
    private int mOverviewPointerId = -1;
    /** True once the swipe has opened the overview, for hover/release/cancel routing. */
    private boolean mOverviewOpened;
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
                // The previous gesture can end with its UP redirected elsewhere (the overview
                // closes and focus changes inside the release callback). Start from a known
                // state instead of trusting that onTouchEvent saw that UP.
                resetOverviewGesture();
                mDownX = event.getX();
                mDownY = event.getY();
                mTracking = true;
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
                mOverviewOpened = true;
                mOverviewPointerId = event.getPointerId(0);
                performHapticFeedback(OVERVIEW_GESTURE_FEEDBACK);
                listener.onOverviewRequested();
                return true;
            }
            case MotionEvent.ACTION_UP:
                resetOverviewGesture();
                break;
            case MotionEvent.ACTION_CANCEL:
                if (mOverviewOpened && mListener != null) mListener.onOverviewGestureCancelled();
                resetOverviewGesture();
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN && mGestureConsumed) {
            // A new finger with the old state still set: reset and let normal dispatch (and the
            // next MOVE interception) decide, instead of keeping the stale target.
            resetOverviewGesture();
            return false;
        }
        if (mGestureConsumed) {
            if (action == MotionEvent.ACTION_MOVE) {
                final Listener listener = mListener;
                if (mOverviewOpened && listener != null && event.getPointerCount() == 1
                        && event.getPointerId(0) == mOverviewPointerId) {
                    listener.onOverviewGestureHover(event.getRawX(), event.getRawY());
                }
            } else if (action == MotionEvent.ACTION_UP) {
                final Listener listener = mListener;
                if (mOverviewOpened && listener != null && event.getPointerCount() == 1
                        && event.getPointerId(0) == mOverviewPointerId) {
                    // The finger never left the screen: let the controller select whatever card
                    // is under it now. The card itself never received ACTION_DOWN, so its own
                    // click handler cannot do this.
                    listener.onOverviewGestureReleased(event.getRawX(), event.getRawY());
                }
                resetOverviewGesture();
            } else if (action == MotionEvent.ACTION_CANCEL) {
                if (mOverviewOpened && mListener != null) mListener.onOverviewGestureCancelled();
                resetOverviewGesture();
            }
            return true;
        }
        return super.onTouchEvent(event);
    }


    private void resetOverviewGesture() {
        mOverviewOpened = false;
        mGestureConsumed = false;
        mTracking = false;
        mOverviewPointerId = -1;
    }

    @Override
    protected void onDetachedFromWindow() {
        resetOverviewGesture();
        super.onDetachedFromWindow();
    }
}
