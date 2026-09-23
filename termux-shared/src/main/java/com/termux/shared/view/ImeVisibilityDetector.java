package com.termux.shared.view;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;

/**
 * Detects IME (soft keyboard) visibility changes using
 * {@link View#getWindowVisibleDisplayFrame(Rect)} — a method that works on
 * API 21–34 regardless of {@code SOFT_INPUT_ADJUST_RESIZE}, {@code WindowInsets},
 * or the activity's configuration (unlike {@code WindowInsetsCompat.Type.ime()}
 * which requires {@code ADJUST_RESIZE} on API < 30).
 *
 * <p>The detector registers a {@link ViewTreeObserver.OnGlobalLayoutListener}
 * on the decor view and compares the visible display frame height against the
 * total screen height. If the bottom-edge shrinkage exceeds a 15% threshold
 * (typical navigation bars are ~5–8 %, keyboards are 25–40 %), the IME is
 * considered visible.
 */
public final class ImeVisibilityDetector {

    public interface Listener {
        /**
         * Called when the computed IME visibility or height changes.
         *
         * @param visible     {@code true} if the IME is now considered visible.
         * @param imeHeightPx The estimated IME height in pixels (0 when hidden).
         */
        void onImeVisibilityChanged(boolean visible, int imeHeightPx);
    }

    private static final float KEYBOARD_THRESHOLD_FRACTION = 0.15f;

    private final View mDecorView;
    private final Listener mListener;
    private final Rect mVisibleFrame = new Rect();

    private boolean mLastVisible = false;
    private int mLastHeightPx = 0;
    private int mScreenHeightPx;
    private boolean mAttached = false;

    private final ViewTreeObserver.OnGlobalLayoutListener mLayoutListener =
            this::onGlobalLayout;

    /**
     * @param activity The activity whose decor view to observe.
     * @param listener Callback for IME visibility changes.
     */
    public ImeVisibilityDetector(@NonNull Activity activity, @NonNull Listener listener) {
        mDecorView = activity.getWindow().getDecorView();
        mListener = listener;
        mScreenHeightPx = activity.getResources().getDisplayMetrics().heightPixels;
    }

    /** Start listening. Idempotent. */
    public void attach() {
        if (mAttached) return;
        mDecorView.getViewTreeObserver().addOnGlobalLayoutListener(mLayoutListener);
        mAttached = true;
        // Seed the initial state so the first real change fires a callback.
        int h = computeKeyboardHeight();
        mLastVisible = h > 0;
        mLastHeightPx = h;
    }

    /** Stop listening. Idempotent. Call from onDestroy(). */
    public void detach() {
        if (!mAttached) return;
        mDecorView.getViewTreeObserver().removeOnGlobalLayoutListener(mLayoutListener);
        mAttached = false;
    }

    /** Force a one-shot refresh. Useful after activity recreate. */
    public void refresh() {
        int h = computeKeyboardHeight();
        boolean v = h > 0;
        if (v != mLastVisible || h != mLastHeightPx) {
            publishState(v, h);
        }
    }

    /** @return The last computed IME visibility (no re-calculation). */
    public boolean isImeVisible() {
        return mLastVisible;
    }

    // ---- internal ----

    private void onGlobalLayout() {
        int heightPx = computeKeyboardHeight();
        boolean visible = heightPx > 0;

        if (visible != mLastVisible
                || (visible && Math.abs(heightPx - mLastHeightPx) > 40)) {
            publishState(visible, heightPx);
        }
    }

    private void publishState(boolean visible, int heightPx) {
        mLastVisible = visible;
        mLastHeightPx = heightPx;
        mListener.onImeVisibilityChanged(visible, heightPx);
    }

    private int computeKeyboardHeight() {
        mDecorView.getWindowVisibleDisplayFrame(mVisibleFrame);

        int screenH = mDecorView.getHeight();
        if (screenH <= 0) screenH = mScreenHeightPx;

        int hidden = screenH - mVisibleFrame.bottom;
        if (hidden > screenH * KEYBOARD_THRESHOLD_FRACTION) {
            return hidden;
        }
        return 0;
    }
}
