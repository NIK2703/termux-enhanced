package com.termux.app.terminal;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Long-press drag-reorder for the overview grid.
 *
 * <p>A press becomes a candidate at the long-press timeout. The lifted card shrinks, fades and
 * follows the finger, while every other card stays in its slot. On release the lifted card is
 * removed from its original index and inserted at the slot nearest the finger. Only then is the
 * final order handed to the host, which applies one remove-and-insert move to the session model so
 * the pager and the tab strip change together with the grid.</p>
 */
final class SessionOverviewDragController {

    interface Host {
        /** True when this card can start a reorder gesture right now. */
        boolean canStartDrag(@NonNull SessionOverviewCardView card);

        /**
         * Commit a completed drop.
         *
         * @param fromIndex    original grid/session index of the dragged card.
         * @param toIndex      slot index the card was dropped on.
         * @param orderedCards the cards in their final slot order.
         */
        void onReorderCommitted(int fromIndex, int toIndex,
                                @NonNull List<SessionOverviewCardView> orderedCards);
    }

    private static final int STATE_IDLE = 0;
    private static final int STATE_PRESSED = 1;
    private static final int STATE_DRAGGING = 2;

    private static final long LIFT_DURATION_MS = 120L;
    private static final long SETTLE_DURATION_MS = 160L;
    private static final float LIFT_SCALE = 0.92f;
    private static final float LIFT_ALPHA = 0.82f;
    private static final float LIFT_Z_DP = 8f;
    private static final float AUTO_SCROLL_EDGE_DP = 48f;
    private static final float AUTO_SCROLL_MAX_STEP_DP = 10f;
    private static final int MAX_SETTLE_LAYOUT_ATTEMPTS = 20;
    private static final DecelerateInterpolator DECELERATE = new DecelerateInterpolator();

    private final SessionOverviewGridView mGrid;
    @Nullable
    private final SessionOverviewScrollView mScroll;
    private final Host mHost;

    private final int mTouchSlop;
    private final int mLongPressTimeout;
    private final float mLiftZ;
    private final int mAutoScrollEdge;
    private final int mAutoScrollMaxStep;

    private final int[] mGridLocation = new int[2];
    private final int[] mScrollLocation = new int[2];

    private int mState = STATE_IDLE;
    @Nullable
    private SessionOverviewCardView mCard;
    private int mDragChildIndex = -1;
    private float[] mBaseCenterXs = new float[0];
    private float[] mBaseCenterYs = new float[0];
    private float mDownRawX;
    private float mDownRawY;
    private float mLastRawX;
    private float mLastRawY;
    /** Pointer and card translation at the instant the long press lifted the card. */
    private float mGrabGridX;
    private float mGrabGridY;
    private float mGrabTranslationX;
    private float mGrabTranslationY;

    private boolean mAutoScrollPosted;
    private int mAutoScrollStep;

    /**
     * A drop has reordered the children but the grid has not laid them out yet. Non-null only
     * between the drop and the first layout pass, when the settle animations can be seeded.
     */
    @Nullable
    private List<SessionOverviewCardView> mSettleCards;
    private float[] mSettleFromCenterXs = new float[0];
    private float[] mSettleFromCenterYs = new float[0];
    private float[] mSettleTargetCenterXs = new float[0];
    private float[] mSettleTargetCenterYs = new float[0];
    private int mSettleDraggedIndex = -1;
    private int mSettleAttempts;

    private final Runnable mLongPressRunnable = this::grab;
    private final Runnable mAutoScrollRunnable = this::autoScrollFrame;
    private final Runnable mSettleRunnable = this::settleWhenLaidOut;
    private final View.OnLayoutChangeListener mCardLayoutListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (mState == STATE_DRAGGING && view == mCard) {
                    updateCardPosition();
                }
            };

    SessionOverviewDragController(@NonNull SessionOverviewGridView grid,
                                  @Nullable SessionOverviewScrollView scroll,
                                  @NonNull Host host) {
        mGrid = grid;
        mScroll = scroll;
        mHost = host;

        final ViewConfiguration configuration = ViewConfiguration.get(grid.getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
        mLongPressTimeout = ViewConfiguration.getLongPressTimeout();
        final float density = grid.getResources().getDisplayMetrics().density;
        mLiftZ = LIFT_Z_DP * density;
        mAutoScrollEdge = Math.max(1, Math.round(AUTO_SCROLL_EDGE_DP * density));
        mAutoScrollMaxStep = Math.max(1, Math.round(AUTO_SCROLL_MAX_STEP_DP * density));
    }

    /** @return true while a press or drag is being tracked. */
    boolean isActive() {
        return mState != STATE_IDLE;
    }

    boolean onTouch(@NonNull SessionOverviewCardView card, @NonNull MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return onDown(card, event);
            case MotionEvent.ACTION_MOVE:
                return onMove(card, event);
            case MotionEvent.ACTION_UP:
                return onUp(card);
            case MotionEvent.ACTION_CANCEL:
                return onCancel(card);
            case MotionEvent.ACTION_POINTER_DOWN:
                if (mState == STATE_PRESSED) {
                    // A second finger turns this into an ordinary multi-touch gesture.
                    card.removeCallbacks(mLongPressRunnable);
                    resetGesture();
                    return false;
                }
                return mState != STATE_IDLE;
            default:
                return mState != STATE_IDLE;
        }
    }

    private boolean onDown(@NonNull SessionOverviewCardView card, @NonNull MotionEvent event) {
        if (mState != STATE_IDLE || mSettleCards != null) return false;
        if (!mHost.canStartDrag(card)) return false;

        mCard = card;
        mState = STATE_PRESSED;
        mDownRawX = mLastRawX = event.getRawX();
        mDownRawY = mLastRawY = event.getRawY();
        card.postDelayed(mLongPressRunnable, mLongPressTimeout);
        return false;
    }

    private boolean onMove(@NonNull SessionOverviewCardView card, @NonNull MotionEvent event) {
        if (mCard != card) return false;

        if (mState == STATE_PRESSED) {
            mLastRawX = event.getRawX();
            mLastRawY = event.getRawY();
            final float dx = mLastRawX - mDownRawX;
            final float dy = mLastRawY - mDownRawY;
            if (Math.hypot(dx, dy) > mTouchSlop) {
                // A normal tap, swipe or scroll won the race against the long press.
                card.removeCallbacks(mLongPressRunnable);
                resetGesture();
            }
            return false;
        }

        if (mState == STATE_DRAGGING) {
            mLastRawX = event.getRawX();
            mLastRawY = event.getRawY();
            updateCardPosition();
            updateAutoScroll(mLastRawY);
            return true;
        }
        return false;
    }

    private boolean onUp(@NonNull SessionOverviewCardView card) {
        card.removeCallbacks(mLongPressRunnable);
        if (mState == STATE_PRESSED) {
            resetGesture();
            return false; // let the View deliver the click
        }
        if (mState == STATE_DRAGGING) {
            drop();
            return true;
        }
        return false;
    }

    private boolean onCancel(@NonNull SessionOverviewCardView card) {
        card.removeCallbacks(mLongPressRunnable);
        if (mState == STATE_DRAGGING) {
            cancelDrag();
            return true;
        }
        resetGesture();
        return false;
    }

    /** Long-press timeout: lift the card and start following the finger. */
    private void grab() {
        final SessionOverviewCardView card = mCard;
        if (mState != STATE_PRESSED || card == null) return;
        if (!card.isAttachedToWindow() || !mHost.canStartDrag(card)) {
            resetGesture();
            return;
        }
        if (mSettleCards != null || !captureLayout(card)) {
            resetGesture();
            return;
        }

        mState = STATE_DRAGGING;
        mDragChildIndex = indexOfChild(card);
        if (mDragChildIndex < 0) {
            resetGesture();
            return;
        }

        // Follow the finger from the lift point. Snapping the card's centre to the finger here
        // made a long press look like the card jumped out from under the user's hand.
        mGrid.getLocationOnScreen(mGridLocation);
        mGrabGridX = mLastRawX - mGridLocation[0];
        mGrabGridY = mLastRawY - mGridLocation[1];
        mGrabTranslationX = card.getTranslationX();
        mGrabTranslationY = card.getTranslationY();

        card.cancelSwipeGesture();
        card.setPressed(false);
        card.cancelLongPress();
        card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        disallowIntercept(card, true);
        card.addOnLayoutChangeListener(mCardLayoutListener);
        card.animate().cancel();
        card.animate()
                .scaleX(LIFT_SCALE)
                .scaleY(LIFT_SCALE)
                .alpha(LIFT_ALPHA)
                .translationZ(mLiftZ)
                .setDuration(LIFT_DURATION_MS)
                .setInterpolator(DECELERATE)
                .start();
        updateCardPosition();
    }

    /** Remember every slot centre in grid coordinates. Only the dragged card will leave its slot. */
    private boolean captureLayout(@NonNull SessionOverviewCardView card) {
        final int count = mGrid.getChildCount();
        if (count < 2) return false;
        final float[] centerXs = new float[count];
        final float[] centerYs = new float[count];
        for (int i = 0; i < count; i++) {
            final View child = mGrid.getChildAt(i);
            if (child.getWidth() <= 0 || child.getHeight() <= 0) return false;
            centerXs[i] = child.getLeft() + child.getWidth() / 2f;
            centerYs[i] = child.getTop() + child.getHeight() / 2f;
        }
        if (indexOfChild(card) < 0) return false;
        mBaseCenterXs = centerXs;
        mBaseCenterYs = centerYs;
        return true;
    }

    private int indexOfChild(@NonNull View child) {
        for (int i = 0; i < mGrid.getChildCount(); i++) {
            if (mGrid.getChildAt(i) == child) return i;
        }
        return -1;
    }

    /**
     * Move the card by the same delta as the finger since the lift. The card's initial offset
     * under the finger is preserved, including while auto-scroll moves the grid underneath it.
     */
    private void updateCardPosition() {
        final SessionOverviewCardView card = mCard;
        if (mState != STATE_DRAGGING || card == null || !card.isAttachedToWindow()) return;
        if (card.getWidth() <= 0 || card.getHeight() <= 0) return;

        mGrid.getLocationOnScreen(mGridLocation);
        final float gridX = mLastRawX - mGridLocation[0];
        final float gridY = mLastRawY - mGridLocation[1];
        card.setTranslationX(SessionOverviewReorderMath.dragTranslation(
                mGrabTranslationX, mGrabGridX, gridX));
        card.setTranslationY(SessionOverviewReorderMath.dragTranslation(
                mGrabTranslationY, mGrabGridY, gridY));
    }

    /** @return the slot nearest the finger at release, or -1 when the layout is unusable. */
    private int resolveDropTarget() {
        final int count = mGrid.getChildCount();
        if (mState != STATE_DRAGGING || mDragChildIndex < 0 || count < 2
                || mBaseCenterXs.length != count || mBaseCenterYs.length != count) {
            return -1;
        }
        mGrid.getLocationOnScreen(mGridLocation);
        final float x = mLastRawX - mGridLocation[0];
        final float y = mLastRawY - mGridLocation[1];
        return SessionOverviewReorderMath.resolveTargetIndex(
                x, y, mBaseCenterXs, mBaseCenterYs, mDragChildIndex);
    }

    private void updateAutoScroll(float rawY) {
        if (mScroll == null || mState != STATE_DRAGGING) {
            stopAutoScroll();
            return;
        }
        mScroll.getLocationOnScreen(mScrollLocation);
        final float pointerY = rawY - mScrollLocation[1];
        final int step = SessionOverviewReorderMath.autoScrollStep(
                pointerY, mScroll.getHeight(), mAutoScrollEdge, mAutoScrollMaxStep);
        mAutoScrollStep = step;
        if (step == 0) {
            stopAutoScroll();
            return;
        }
        if (!mAutoScrollPosted) {
            mAutoScrollPosted = true;
            mScroll.postOnAnimation(mAutoScrollRunnable);
        }
    }

    private void stopAutoScroll() {
        mAutoScrollStep = 0;
        mAutoScrollPosted = false;
        if (mScroll != null) mScroll.removeCallbacks(mAutoScrollRunnable);
    }

    private void autoScrollFrame() {
        mAutoScrollPosted = false;
        if (mState != STATE_DRAGGING || mScroll == null || mAutoScrollStep == 0) return;

        final int before = mScroll.getScrollY();
        mScroll.scrollBy(0, mAutoScrollStep);
        if (mScroll.getScrollY() == before) {
            // The grid fits or is already at the end. Wait for the next MOVE to re-evaluate.
            mAutoScrollStep = 0;
            return;
        }

        // The grid moved under the finger, so the card's screen-relative translation has to be
        // refreshed even though the finger itself did not move.
        updateCardPosition();
        if (mState == STATE_DRAGGING && mAutoScrollStep != 0) {
            mAutoScrollPosted = true;
            mScroll.postOnAnimation(mAutoScrollRunnable);
        }
    }

    private void drop() {
        final SessionOverviewCardView card = mCard;
        final int from = mDragChildIndex;
        if (card == null || from < 0 || from >= mGrid.getChildCount()
                || mGrid.getChildAt(from) != card) {
            resetGesture();
            return;
        }
        stopAutoScroll();
        card.removeOnLayoutChangeListener(mCardLayoutListener);

        final int to = resolveDropTarget();
        if (to < 0 || to == from) {
            // Dropped back where it started: no visual or model reorder happened during the drag.
            settleInPlace(card);
            disallowIntercept(card, false);
            resetGesture();
            return;
        }

        final int count = mGrid.getChildCount();
        final int[] order = SessionOverviewReorderMath.orderAfterMove(count, from, to);
        if (order == null || mBaseCenterXs.length != count || mBaseCenterYs.length != count) {
            cancelDrag();
            return;
        }

        final List<SessionOverviewCardView> orderedCards = new ArrayList<>(count);
        final ViewGroup.LayoutParams[] params = new ViewGroup.LayoutParams[count];
        final float[] fromCenterXs = new float[count];
        final float[] fromCenterYs = new float[count];
        for (int newIndex = 0; newIndex < count; newIndex++) {
            final int oldIndex = order[newIndex];
            final View child = mGrid.getChildAt(oldIndex);
            if (!(child instanceof SessionOverviewCardView)) {
                cancelDrag();
                return;
            }
            orderedCards.add((SessionOverviewCardView) child);
            params[newIndex] = child.getLayoutParams();
            fromCenterXs[newIndex] = child.getLeft() + child.getWidth() / 2f
                    + child.getTranslationX();
            fromCenterYs[newIndex] = child.getTop() + child.getHeight() / 2f
                    + child.getTranslationY();
        }

        final float[] targetCenterXs = mBaseCenterXs.clone();
        final float[] targetCenterYs = mBaseCenterYs.clone();

        // The touch has ended, so changing the child order now cannot cut the gesture short.
        // Re-adding the children also clears the swipe/lift transforms that detaching resets.
        mGrid.removeAllViews();
        for (int newIndex = 0; newIndex < count; newIndex++) {
            mGrid.addView(orderedCards.get(newIndex), newIndex, params[newIndex]);
        }
        mGrid.requestLayout();

        beginSettle(orderedCards, fromCenterXs, fromCenterYs,
                targetCenterXs, targetCenterYs, to);

        resetGesture();
        disallowIntercept(card, false);
        mHost.onReorderCommitted(from, to, orderedCards);
    }

    /** Animate the lifted card back into its own slot without changing any order. */
    private void settleInPlace(@NonNull SessionOverviewCardView card) {
        if (!card.isAttachedToWindow()) return;
        card.animate().cancel();
        card.animate()
                .translationX(0f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .translationZ(0f)
                .setDuration(SETTLE_DURATION_MS)
                .setInterpolator(DECELERATE)
                .start();
    }

    /**
     * Remember where every card was and where its new slot will be, then wait for the grid to lay
     * the re-added children out before starting the drop animation.
     */
    private void beginSettle(@NonNull List<SessionOverviewCardView> cards,
                             @NonNull float[] fromCenterXs, @NonNull float[] fromCenterYs,
                             @NonNull float[] targetCenterXs, @NonNull float[] targetCenterYs,
                             int draggedIndex) {
        cancelPendingSettle();
        mSettleCards = cards;
        mSettleFromCenterXs = fromCenterXs;
        mSettleFromCenterYs = fromCenterYs;
        mSettleTargetCenterXs = targetCenterXs;
        mSettleTargetCenterYs = targetCenterYs;
        mSettleDraggedIndex = draggedIndex;
        mSettleAttempts = 0;
        mGrid.post(mSettleRunnable);
    }

    /**
     * Animate cards from their previous slot centres to the layout produced by a model change.
     *
     * <p>Deleting a session removes its card and lets the survivors slide into the gap. The new
     * cards may not have been laid out yet, so the target centres come from the grid's geometry;
     * {@link #settleWhenLaidOut()} waits for the real layout before starting the flight.</p>
     */
    void animateLayoutChange(@NonNull List<SessionOverviewCardView> cards,
                             @NonNull float[] fromCenterXs, @NonNull float[] fromCenterYs,
                             @NonNull float[] targetCenterXs, @NonNull float[] targetCenterYs) {
        if (mState != STATE_IDLE || mSettleCards != null) return;
        final int count = cards.size();
        if (count == 0 || fromCenterXs.length != count || fromCenterYs.length != count
                || targetCenterXs.length != count || targetCenterYs.length != count) {
            return;
        }
        beginSettle(cards, fromCenterXs, fromCenterYs,
                targetCenterXs, targetCenterYs, -1);
    }

    /**
     * Wait until the grid has laid the re-added children out, then animate every card from its
     * old visual centre to its new slot. The dragged card keeps its lift transform for the flight
     * so it stays above the cards that shift underneath it.
     */
    private void settleWhenLaidOut() {
        final List<SessionOverviewCardView> cards = mSettleCards;
        if (cards == null) return;
        if (cards.size() != mGrid.getChildCount()) {
            cancelPendingSettle();
            return;
        }

        boolean laidOut = true;
        for (int i = 0; i < cards.size(); i++) {
            final SessionOverviewCardView child = cards.get(i);
            if (mGrid.getChildAt(i) != child || !child.isAttachedToWindow()
                    || child.getWidth() <= 0 || child.getHeight() <= 0) {
                cancelPendingSettle();
                return;
            }
            final float baseX = child.getLeft() + child.getWidth() / 2f;
            final float baseY = child.getTop() + child.getHeight() / 2f;
            if (Math.abs(baseX - mSettleTargetCenterXs[i]) > 1f
                    || Math.abs(baseY - mSettleTargetCenterYs[i]) > 1f) {
                laidOut = false;
            }
        }
        if (!laidOut) {
            if (++mSettleAttempts < MAX_SETTLE_LAYOUT_ATTEMPTS) {
                mGrid.postOnAnimation(mSettleRunnable);
            } else {
                cancelPendingSettle();
            }
            return;
        }

        for (int i = 0; i < cards.size(); i++) {
            final SessionOverviewCardView child = cards.get(i);
            if (!child.isAttachedToWindow()) continue;
            child.animate().cancel();
            final float baseX = child.getLeft() + child.getWidth() / 2f;
            final float baseY = child.getTop() + child.getHeight() / 2f;
            child.setTranslationX(mSettleFromCenterXs[i] - baseX);
            child.setTranslationY(mSettleFromCenterYs[i] - baseY);
            if (i == mSettleDraggedIndex) {
                child.setScaleX(LIFT_SCALE);
                child.setScaleY(LIFT_SCALE);
                child.setAlpha(LIFT_ALPHA);
                child.setTranslationZ(mLiftZ);
            } else {
                child.setScaleX(1f);
                child.setScaleY(1f);
                child.setAlpha(1f);
                child.setTranslationZ(0f);
            }
            child.animate()
                    .translationX(0f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .translationZ(0f)
                    .setDuration(SETTLE_DURATION_MS)
                    .setInterpolator(DECELERATE)
                    .start();
        }

        // The animations run independently from here on. A new long-press needs at least the
        // long-press timeout, which is much longer than this settle, so no generation token is
        // necessary for the normal case.
        clearSettleState();
    }

    private void cancelPendingSettle() {
        mGrid.removeCallbacks(mSettleRunnable);
        clearSettleState();
    }

    private void clearSettleState() {
        mSettleCards = null;
        mSettleFromCenterXs = new float[0];
        mSettleFromCenterYs = new float[0];
        mSettleTargetCenterXs = new float[0];
        mSettleTargetCenterYs = new float[0];
        mSettleDraggedIndex = -1;
        mSettleAttempts = 0;
    }

    /** Release the finger without a drop: the lifted card returns to its own slot. */
    private void cancelDrag() {
        final SessionOverviewCardView card = mCard;
        stopAutoScroll();
        if (card != null) {
            card.removeOnLayoutChangeListener(mCardLayoutListener);
            settleInPlace(card);
            disallowIntercept(card, false);
        }
        resetGesture();
    }

    /**
     * Abort before a structural update or a hide. The controller is about to rebuild or release
     * the cards, so reset everything immediately instead of animating.
     */
    void cancelForModelChange() {
        final SessionOverviewCardView card = mCard;
        if (card != null) card.removeCallbacks(mLongPressRunnable);
        stopAutoScroll();
        cancelPendingSettle();
        if (card != null) card.removeOnLayoutChangeListener(mCardLayoutListener);

        for (int i = 0; i < mGrid.getChildCount(); i++) {
            final View child = mGrid.getChildAt(i);
            child.animate().cancel();
            child.setTranslationX(0f);
            child.setTranslationY(0f);
            child.setScaleX(1f);
            child.setScaleY(1f);
            child.setAlpha(1f);
            child.setTranslationZ(0f);
        }
        if (card != null) disallowIntercept(card, false);
        resetGesture();
    }

    private void resetGesture() {
        stopAutoScroll();
        if (mCard != null) mCard.removeCallbacks(mLongPressRunnable);
        mState = STATE_IDLE;
        mCard = null;
        mDragChildIndex = -1;
        mBaseCenterXs = new float[0];
        mBaseCenterYs = new float[0];
    }

    private static void disallowIntercept(@Nullable View view, boolean disallow) {
        if (view == null) return;
        final ViewParent parent = view.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(disallow);
    }
}
