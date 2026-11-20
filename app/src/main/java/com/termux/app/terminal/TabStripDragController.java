package com.termux.app.terminal;

import android.animation.LayoutTransition;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/**
 * Long-press drag-reorder for the session tab strip.
 *
 * <p>A press becomes a candidate at the long-press timeout: the tab scales up. Horizontal movement
 * past the touch slop starts the drag, while vertical movement or a completed drag cancels it.
 * Releasing a lifted tab without movement keeps long-press rename.
 *
 * <p>Only the dragged tab moves during the gesture. Child order and the session model change on
 * drop, so every index used by the controller and by the pager stays valid until then.
 */
final class TabStripDragController {

    interface Host {
        int getTabCount();

        @Nullable
        View getTabAt(int index);

        /** True when this tab can take the gesture (live session, not closing). */
        boolean canStartDrag(@Nullable View tab);

        /** The drag started: stop strip animations and park pager input. */
        void onDragStarted();

        /** Release pager input after a drop or cancel. */
        void onDragEnded();

        /** A lifted tab was released without movement. */
        void onRenameTab(@Nullable View tab);

        /** Commit the already-applied view move to the session model and pager. */
        void onTabReordered(int fromIndex, int toIndex);
    }

    private static final int STATE_IDLE = 0;
    private static final int STATE_PRESSED = 1;
    private static final int STATE_GRABBED = 2;
    private static final int STATE_DRAGGING = 3;
    /** Vertical movement cancelled the hold; keep consuming until the finger lifts. */
    private static final int STATE_CANCELLED = 4;

    private static final long LIFT_DURATION_MS = 120L;
    private static final long GAP_DURATION_MS = 130L;
    private static final long SETTLE_DURATION_MS = 160L;
    private static final float LIFT_SCALE = 1.1f;
    private static final float LIFT_Y_DP = -1.5f;
    private static final float LIFT_Z_DP = 6f;
    private static final float AUTO_SCROLL_EDGE_DP = 48f;
    private static final float AUTO_SCROLL_MAX_STEP_DP = 10f;
    private static final DecelerateInterpolator DECELERATE = new DecelerateInterpolator();

    private final LinearLayout mContainer;
    private final HorizontalScrollView mScroll;
    private final Host mHost;

    private final int mTouchSlop;
    private final int mLongPressTimeout;
    private final float mLiftY;
    private final float mLiftZ;
    private final int mAutoScrollEdge;
    private final int mAutoScrollMaxStep;

    private final int[] mScrollLocation = new int[2];
    private int[] mLefts = new int[0];
    private int[] mWidths = new int[0];
    private int[] mMarginStarts = new int[0];
    private int[] mMarginEnds = new int[0];

    private int mState = STATE_IDLE;
    @Nullable
    private View mTab;
    private int mFromIndex = -1;
    private int mTargetIndex = -1;
    private float mDownRawX;
    private float mDownRawY;
    private float mLastRawX;
    private float mDragTranslationX;
    private float mDragWidth;
    private int mDraggedMarginStart;
    private int mDraggedMarginEnd;
    private boolean mAutoScrollPosted;
    private int mAutoScrollStep;
    private boolean mPagerInputReserved;

    TabStripDragController(LinearLayout container, HorizontalScrollView scroll, Host host) {
        mContainer = container;
        mScroll = scroll;
        mHost = host;

        final ViewConfiguration configuration = ViewConfiguration.get(container.getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
        mLongPressTimeout = ViewConfiguration.getLongPressTimeout();
        final float density = container.getResources().getDisplayMetrics().density;
        mLiftY = LIFT_Y_DP * density;
        mLiftZ = LIFT_Z_DP * density;
        mAutoScrollEdge = Math.max(1, Math.round(AUTO_SCROLL_EDGE_DP * density));
        mAutoScrollMaxStep = Math.max(1, Math.round(AUTO_SCROLL_MAX_STEP_DP * density));
    }

    /** @return true while the strip is grabbed or being dragged. */
    boolean isActive() {
        return mState == STATE_GRABBED || mState == STATE_DRAGGING;
    }

    /** Abort any gesture in flight without renaming or reordering. Used on pause / settings change. */
    void cancel() {
        if (mState == STATE_IDLE) return;
        stopAutoScroll();
        final View tab = mTab;
        if (mState == STATE_DRAGGING) {
            clearNeighbourTranslations(true);
        }
        if (tab != null) {
            animateDown(tab);
            disallowIntercept(tab, false);
        }
        finishPagerReservation();
        resetGesture();
    }

    /** Abort before a structural update; the following pager sync re-arms pager input. */
    void cancelForModelChange() {
        if (mState == STATE_IDLE) return;
        stopAutoScroll();
        final View tab = mTab;
        if (mState == STATE_DRAGGING) {
            clearNeighbourTranslations(false);
        }
        if (tab != null) {
            animateDown(tab);
            disallowIntercept(tab, false);
        }
        mPagerInputReserved = false;
        resetGesture();
    }

    boolean onTouch(View tab, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return onDown(tab, event);
            case MotionEvent.ACTION_MOVE:
                return onMove(tab, event);
            case MotionEvent.ACTION_UP:
                return onUp(tab);
            case MotionEvent.ACTION_CANCEL:
                return onCancel();
            default:
                return mState != STATE_IDLE;
        }
    }

    private boolean onDown(View tab, MotionEvent event) {
        if (mState != STATE_IDLE) return false;
        if (!mHost.canStartDrag(tab)) return false;

        mTab = tab;
        mState = STATE_PRESSED;
        mDownRawX = event.getRawX();
        mDownRawY = event.getRawY();
        mLastRawX = mDownRawX;
        tab.postDelayed(mGrabRunnable, mLongPressTimeout);
        return false;
    }

    private boolean onMove(View tab, MotionEvent event) {
        if (mTab != tab) return false;

        final float dx = event.getRawX() - mDownRawX;
        final float dy = event.getRawY() - mDownRawY;

        if (mState == STATE_PRESSED) {
            if (Math.hypot(dx, dy) > mTouchSlop) {
                // Normal click or scroll started before the long press.
                removeGrabCallback(tab);
                resetGesture();
            }
            return false;
        }

        if (mState == STATE_GRABBED) {
            if (mHost.getTabCount() < 2) {
                // Nothing to reorder: movement cancels rename, a still press keeps it.
                if (Math.hypot(dx, dy) > mTouchSlop) {
                    mState = STATE_CANCELLED;
                    animateDown(tab);
                }
                return true;
            }
            if (Math.abs(dx) > mTouchSlop && Math.abs(dx) >= Math.abs(dy)) {
                beginDrag(event.getRawX());
                return true;
            }
            if (Math.abs(dy) > mTouchSlop && Math.abs(dy) > Math.abs(dx)) {
                // Vertical intent: cancel rather than reorder.
                mState = STATE_CANCELLED;
                animateDown(tab);
                return true;
            }
            return true;
        }

        if (mState == STATE_DRAGGING) {
            dragTo(event.getRawX());
            return true;
        }

        // CANCELLED: swallow the rest so no click fires.
        return true;
    }

    private boolean onUp(View tab) {
        removeGrabCallback(tab);

        switch (mState) {
            case STATE_PRESSED:
                resetGesture();
                return false; // let the View deliver the click
            case STATE_GRABBED:
                // No drag started, so keep long-press rename.
                animateDown(tab);
                resetGesture();
                mHost.onRenameTab(tab);
                return true;
            case STATE_DRAGGING:
                drop(tab);
                return true;
            case STATE_CANCELLED:
                resetGesture();
                return true;
            default:
                return false;
        }
    }

    private boolean onCancel() {
        final boolean consumed = mState != STATE_IDLE;
        if (mState == STATE_GRABBED || mState == STATE_DRAGGING) {
            stopAutoScroll();
            if (mState == STATE_DRAGGING) {
                clearNeighbourTranslations(true);
            }
            if (mTab != null) animateDown(mTab);
            finishPagerReservation();
        }
        resetGesture();
        return consumed;
    }

    private final Runnable mGrabRunnable = this::onGrab;

    private void onGrab() {
        final View tab = mTab;
        if (mState != STATE_PRESSED || tab == null || !tab.isAttachedToWindow()
                || !mHost.canStartDrag(tab)) {
            resetGesture();
            return;
        }

        final int from = indexOfTab(tab);
        if (from < 0) {
            resetGesture();
            return;
        }

        mState = STATE_GRABBED;
        mFromIndex = from;
        mTargetIndex = from;
        mDragTranslationX = 0f;

        disallowIntercept(tab, true);
        tab.setPressed(false);
        tab.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);

        tab.animate()
                .translationY(mLiftY)
                .translationZ(mLiftZ)
                .scaleX(LIFT_SCALE)
                .scaleY(LIFT_SCALE)
                .setDuration(LIFT_DURATION_MS)
                .setInterpolator(DECELERATE)
                .start();
    }

    @SuppressWarnings("SuspiciousNameCombination")
    private void beginDrag(float rawX) {
        final View tab = mTab;
        if (tab == null || mHost.getTabCount() < 2) return;
        mState = STATE_DRAGGING;
        mPagerInputReserved = true;
        mHost.onDragStarted();
        mLastRawX = mDownRawX;
        dragTo(rawX);
    }

    private void dragTo(float rawX) {
        final View tab = mTab;
        if (tab == null) return;

        mDragTranslationX += rawX - mLastRawX;
        mLastRawX = rawX;
        tab.setTranslationX(mDragTranslationX);

        updateTarget();
        updateAutoScroll(rawX);
    }

    /** Recompute the insertion slot from live tab geometry. */
    private void updateTarget() {
        final View tab = mTab;
        final int count = mHost.getTabCount();
        if (tab == null || count < 2 || mFromIndex < 0 || mFromIndex >= count) return;
        if (mLefts.length != count) allocateArrays(count);

        for (int i = 0; i < count; i++) {
            final View view = mHost.getTabAt(i);
            if (view == null) return;
            mLefts[i] = view.getLeft();
            mWidths[i] = view.getWidth();
            mMarginStarts[i] = marginStart(view);
            mMarginEnds[i] = marginEnd(view);
        }
        mDragWidth = occupiedWidth(tab);

        final float center = mLefts[mFromIndex] + mWidths[mFromIndex] / 2f + mDragTranslationX;
        final int target = TabStripDragMath.resolveTargetIndex(mFromIndex, mTargetIndex, center,
                mLefts, mWidths, mMarginStarts, mMarginEnds,
                mDragWidth, mDraggedMarginStart, mDraggedMarginEnd);

        if (target != mTargetIndex) {
            mTargetIndex = target;
            applyNeighbourShifts(true);
            tab.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    /**
     * Shift the other tabs to open a gap at {@link #mTargetIndex}. The dragged tab keeps its base
     * position and follows the finger.
     */
    private void applyNeighbourShifts(boolean animate) {
        final int count = mHost.getTabCount();
        for (int i = 0; i < count; i++) {
            if (i == mFromIndex) continue;
            final View view = mHost.getTabAt(i);
            if (view == null) continue;
            final float shift = TabStripDragMath.neighborShift(i, mFromIndex, mTargetIndex, mDragWidth);
            if (animate) {
                view.animate()
                        .translationX(shift)
                        .setDuration(GAP_DURATION_MS)
                        .setInterpolator(DECELERATE)
                        .start();
            } else {
                view.animate().cancel();
                view.setTranslationX(shift);
            }
        }
    }

    /** Return every non-dragged tab to its base position. */
    private void clearNeighbourTranslations(boolean animate) {
        final int count = mHost.getTabCount();
        for (int i = 0; i < count; i++) {
            if (i == mFromIndex) continue;
            final View view = mHost.getTabAt(i);
            if (view == null) continue;
            if (animate) {
                view.animate()
                        .translationX(0f)
                        .setDuration(GAP_DURATION_MS)
                        .setInterpolator(DECELERATE)
                        .start();
            } else {
                view.animate().cancel();
                view.setTranslationX(0f);
            }
        }
    }

    private void updateAutoScroll(float rawX) {
        if (mState != STATE_DRAGGING) return;

        mScroll.getLocationOnScreen(mScrollLocation);
        final int pointerX = Math.round(rawX - mScrollLocation[0]);
        final boolean rtl = mScroll.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        mAutoScrollStep = TabStripDragMath.autoScrollStep(pointerX, mScroll.getWidth(),
                mAutoScrollEdge, mAutoScrollMaxStep, rtl);

        if (mAutoScrollStep != 0) {
            if (!mAutoScrollPosted) {
                mAutoScrollPosted = true;
                mScroll.postOnAnimation(mAutoScrollRunnable);
            }
        }
    }

    private final Runnable mAutoScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (mState != STATE_DRAGGING || mTab == null || mAutoScrollStep == 0) {
                mAutoScrollPosted = false;
                return;
            }

            final int maxScroll = Math.max(0, mContainer.getWidth() - mScroll.getWidth());
            final int scrollX = mScroll.getScrollX();
            int step = mAutoScrollStep;
            if (step < 0) {
                step = Math.max(step, -scrollX);
            } else {
                step = Math.min(step, maxScroll - scrollX);
            }
            if (step == 0) {
                // Already at the edge in that direction. A later MOVE restarts the scroller.
                mAutoScrollStep = 0;
                mAutoScrollPosted = false;
                return;
            }

            final int before = scrollX;
            mScroll.scrollBy(step, 0);
            final int moved = mScroll.getScrollX() - before;
            if (moved != 0) {
                // Keep the floated tab under the stationary finger while the strip moves below it.
                mDragTranslationX += moved;
                mTab.setTranslationX(mDragTranslationX);
                updateTarget();
            }
            mScroll.postOnAnimation(this);
        }
    };

    private void stopAutoScroll() {
        mScroll.removeCallbacks(mAutoScrollRunnable);
        mAutoScrollPosted = false;
        mAutoScrollStep = 0;
    }

    /** Reorder the views, hand the model move to the host, then settle. */
    private void drop(View tab) {
        stopAutoScroll();
        final int from = mFromIndex;
        final int target = mTargetIndex;
        final int count = mHost.getTabCount();
        if (tab == null || from < 0 || target < 0 || from >= count || target >= count) {
            animateDown(tab);
            finishPagerReservation();
            resetGesture();
            return;
        }

        // Let the host run updateTabs() without treating it as a live drag.
        mState = STATE_IDLE;

        if (from == target) {
            animateDown(tab);
            finishPagerReservation();
            resetGesture();
            return;
        }

        // After the child swap, each neighbour's base position moves by exactly its gap shift.
        // Snapping the shift now and clearing it after the swap keeps the strip visually still.
        for (int i = 0; i < count; i++) {
            if (i == from) continue;
            final View view = mHost.getTabAt(i);
            if (view == null) continue;
            view.animate().cancel();
            view.setTranslationX(TabStripDragMath.neighborShift(i, from, target, mDragWidth));
        }

        final float oldLeft = tab.getLeft();
        LayoutTransition savedTransition = mContainer.getLayoutTransition();
        mContainer.setLayoutTransition(null);

        final ViewGroup.LayoutParams lp = tab.getLayoutParams();
        if (lp != null) {
            mContainer.removeView(tab);
            mContainer.addView(tab, target, lp);
        }

        for (int i = 0; i < count; i++) {
            if (i == target) continue;
            final View view = mHost.getTabAt(i);
            if (view != null) view.setTranslationX(0f);
        }

        // Restore the transition after the reorder layout, not during it. A child swap does not
        // change the container's own bounds, so the global layout is the reliable hook.
        mContainer.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        mContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        if (mContainer.getLayoutTransition() == null) {
                            mContainer.setLayoutTransition(savedTransition);
                        }
                    }
                });

        // The dragged tab's base left moves by (newLeft - oldLeft). Cancel that in the layout pass,
        // then animate the remainder into the slot.
        final float oldBaseLeft = oldLeft;
        tab.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldL, int oldT, int oldR, int oldB) {
                tab.removeOnLayoutChangeListener(this);
                final float moved = left - oldBaseLeft;
                tab.setTranslationX(tab.getTranslationX() - moved);
                animateSettle(tab);
            }
        });

        mHost.onTabReordered(from, target);
        finishPagerReservation();
        resetGesture();
    }

    private void animateSettle(View tab) {
        tab.animate()
                .translationX(0f)
                .translationY(0f)
                .translationZ(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(SETTLE_DURATION_MS)
                .setInterpolator(DECELERATE)
                .start();
    }

    private void animateDown(@Nullable View tab) {
        if (tab == null) return;
        tab.animate()
                .translationX(0f)
                .translationY(0f)
                .translationZ(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(LIFT_DURATION_MS)
                .setInterpolator(DECELERATE)
                .start();
    }

    private void finishPagerReservation() {
        if (!mPagerInputReserved) return;
        mPagerInputReserved = false;
        mHost.onDragEnded();
    }

    private void resetGesture() {
        stopAutoScroll();
        removeGrabCallback(mTab);
        if (mTab != null) disallowIntercept(mTab, false);
        mTab = null;
        mFromIndex = -1;
        mTargetIndex = -1;
        mDragTranslationX = 0f;
        mDragWidth = 0f;
        mDraggedMarginStart = 0;
        mDraggedMarginEnd = 0;
        mState = STATE_IDLE;
    }

    private void removeGrabCallback(@Nullable View tab) {
        if (tab != null) tab.removeCallbacks(mGrabRunnable);
    }

    private void allocateArrays(int count) {
        if (mLefts.length == count) return;
        mLefts = new int[count];
        mWidths = new int[count];
        mMarginStarts = new int[count];
        mMarginEnds = new int[count];
    }

    private int indexOfTab(View tab) {
        for (int i = 0, count = mHost.getTabCount(); i < count; i++) {
            if (mHost.getTabAt(i) == tab) return i;
        }
        return -1;
    }

    /** Only a parent can stop the ScrollView from intercepting; the tab view has no such API. */
    private static void disallowIntercept(View view, boolean disallow) {
        ViewParent parent = view.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(disallow);
    }

    private static int marginStart(View view) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        return (lp instanceof ViewGroup.MarginLayoutParams)
                ? ((ViewGroup.MarginLayoutParams) lp).getMarginStart() : 0;
    }

    private static int marginEnd(View view) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        return (lp instanceof ViewGroup.MarginLayoutParams)
                ? ((ViewGroup.MarginLayoutParams) lp).getMarginEnd() : 0;
    }

    private static float occupiedWidth(View view) {
        return view.getWidth() + marginStart(view) + marginEnd(view);
    }
}
