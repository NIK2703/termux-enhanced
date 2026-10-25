package com.termux.view;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

/** A combination of {@link GestureDetector} and {@link ScaleGestureDetector}. */
final class GestureAndScaleRecognizer {

    public interface Listener {
        boolean onSingleTapUp(MotionEvent e);

        boolean onDoubleTap(MotionEvent e);

        boolean onScroll(MotionEvent e2, float dx, float dy);

        boolean onFling(MotionEvent e, float velocityX, float velocityY);

        boolean onScale(float focusX, float focusY, float scale);

        boolean onDown(float x, float y);

        boolean onUp(MotionEvent e);

        void onLongPress(MotionEvent e);

        /** Called when the gesture is cancelled (e.g. parent View intercepts via ACTION_CANCEL). */
        void onCancel(MotionEvent e);
    }

    private final GestureDetector mGestureDetector;
    private final ScaleGestureDetector mScaleDetector;
    final Listener mListener;
    boolean isAfterLongPress;

    /**
     * Set when {@link GestureDetector} recognizes the second down of a double tap. The actual
     * callback is delayed until the second up, after {@link Listener#onUp} has delivered the tap's
     * regular action, so opening the keyboard cannot swallow that action.
     */
    private boolean mDoubleTapPending;
    private boolean mDoubleTapUp;

    public GestureAndScaleRecognizer(Context context, Listener listener) {
        mListener = listener;

        mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                // Dispatch immediately instead of waiting for onSingleTapConfirmed(): a tap must
                // not be delayed just to find out whether a second tap follows.
                return mListener.onSingleTapUp(e);
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                return mListener.onScroll(e2, dx, dy);
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                return mListener.onFling(e2, velocityX, velocityY);
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return mListener.onDown(e.getX(), e.getY());
            }

            @Override
            public void onLongPress(MotionEvent e) {
                mListener.onLongPress(e);
                isAfterLongPress = true;
            }
        }, null, true /* ignoreMultitouch */);

        mGestureDetector.setOnDoubleTapListener(new GestureDetector.OnDoubleTapListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                // Already dispatched by onSingleTapUp() above. This callback arrives after the
                // double-tap timeout; repeating the tap here would restore the delay.
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                mDoubleTapPending = true;
                return true;
            }

            @Override
            public boolean onDoubleTapEvent(MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_UP) mDoubleTapUp = true;
                return true;
            }
        });

        mScaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                return mListener.onScale(detector.getFocusX(), detector.getFocusY(), detector.getScaleFactor());
            }
        });
        mScaleDetector.setQuickScaleEnabled(false);
    }

    public void onTouchEvent(MotionEvent event) {
        mGestureDetector.onTouchEvent(event);
        mScaleDetector.onTouchEvent(event);
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isAfterLongPress = false;
                break;
            case MotionEvent.ACTION_UP:
                if (!isAfterLongPress) {
                    // This behaviour is desired when in e.g. vim with mouse events, where we do not
                    // want to move the cursor when lifting finger after a long press.
                    mListener.onUp(event);
                    if (mDoubleTapPending && mDoubleTapUp) {
                        // Delivered after onUp() so that the second tap's mouse click (if the
                        // terminal application reports mouse events) is sent before the keyboard
                        // opens and the window may resize.
                        mListener.onDoubleTap(event);
                    }
                }
                mDoubleTapPending = false;
                mDoubleTapUp = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                isAfterLongPress = false;
                mDoubleTapPending = false;
                mDoubleTapUp = false;
                mListener.onCancel(event);
                break;
        }
    }

    public boolean isInProgress() {
        return mScaleDetector.isInProgress();
    }

}
