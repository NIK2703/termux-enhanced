package com.termux.app.terminal;

import com.termux.app.TermuxAppConstants;

import android.animation.ValueAnimator;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewPropertyAnimator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Long-press drag-reorder for the overview grid.
 *
 * <p>A press becomes a candidate at the long-press timeout. The lifted card grows slightly, fades
 * and follows the finger. As the finger crosses another slot, the other cards shift into the order
 * the drop would produce, so the new order is visible before the finger is released. On release the
 * lifted card is removed from its original index and inserted at the slot nearest the finger; the
 * host then applies one remove-and-insert move to the session model so the pager and the tab strip
 * change together with the grid.</p>
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


    private static final long SHIFT_DURATION_MS = 180L;
    /** The held card grows slightly instead of shrinking, so the lifted item reads as picked up. */
    private static final int MAX_SETTLE_LAYOUT_ATTEMPTS = 20;

    private final SessionOverviewGridView mGrid;
    @Nullable
    private final SessionOverviewScrollView mScroll;
    private final Host mHost;

    private final int mTouchSlop;
    private final int mLongPressTimeout;
    private final float mLiftZ;
    private final int mAutoScrollBand;
    private final int mAutoScrollMaxStep;

    private final int[] mGridLocation = new int[2];
    private final int[] mScrollLocation = new int[2];

    private int mState = TermuxAppConstants.Overview.STATE_IDLE;
    @Nullable
    private SessionOverviewCardView mCard;
    private int mDragChildIndex = -1;
    /** Slot the dragged card currently previews over; neighbours shift while this changes. */
    private int mPreviewTargetIndex = -1;
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

    /** Drop flight: children stay in their old order until the model reorder is committed. */
    @Nullable
    private ValueAnimator mDropAnimator;
    private boolean mAutoScrollPosted;
    private long mAutoScrollLastTimeMs;

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
                if (mState == TermuxAppConstants.Overview.STATE_DRAGGING && view == mCard) {
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
        mLiftZ = FloatingShadow.overviewCardElevationPx(grid.getResources());
        mAutoScrollBand = Math.max(1, Math.round(
                SessionOverviewReorderMath.AUTO_SCROLL_BAND_DP * density));
        mAutoScrollMaxStep = Math.max(1, Math.round(
                SessionOverviewReorderMath.AUTO_SCROLL_MAX_STEP_DP * density));
    }

    /** @return true while a press or drag is being tracked. */
    boolean isActive() {
        return mState != TermuxAppConstants.Overview.STATE_IDLE;
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
                if (mState == TermuxAppConstants.Overview.STATE_PRESSED) {
                    // A second finger turns this into an ordinary multi-touch gesture.
                    card.removeCallbacks(mLongPressRunnable);
                    resetGesture();
                    return false;
                }
                return mState != TermuxAppConstants.Overview.STATE_IDLE;
            default:
                return mState != TermuxAppConstants.Overview.STATE_IDLE;
        }
    }

    private boolean onDown(@NonNull SessionOverviewCardView card, @NonNull MotionEvent event) {
        if (mDropAnimator != null) cancelDropFlight(false);
        if (mState != TermuxAppConstants.Overview.STATE_IDLE || mSettleCards != null) return false;
        if (!mHost.canStartDrag(card)) return false;

        mCard = card;
        mState = TermuxAppConstants.Overview.STATE_PRESSED;
        mDownRawX = mLastRawX = event.getRawX();
        mDownRawY = mLastRawY = event.getRawY();
        card.postDelayed(mLongPressRunnable, mLongPressTimeout);
        return false;
    }

    private boolean onMove(@NonNull SessionOverviewCardView card, @NonNull MotionEvent event) {
        if (mCard != card) return false;

        if (mState == TermuxAppConstants.Overview.STATE_PRESSED) {
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

        if (mState == TermuxAppConstants.Overview.STATE_DRAGGING) {
            mLastRawX = event.getRawX();
            mLastRawY = event.getRawY();
            updateCardPosition();
            updateAutoScroll(mLastRawY);
            updatePreviewOrder();
            return true;
        }
        return false;
    }

    private boolean onUp(@NonNull SessionOverviewCardView card) {
        card.removeCallbacks(mLongPressRunnable);
        if (mState == TermuxAppConstants.Overview.STATE_PRESSED) {
            resetGesture();
            return false; // let the View deliver the click
        }
        if (mState == TermuxAppConstants.Overview.STATE_DRAGGING) {
            drop();
            return true;
        }
        return false;
    }

    private boolean onCancel(@NonNull SessionOverviewCardView card) {
        card.removeCallbacks(mLongPressRunnable);
        if (mState == TermuxAppConstants.Overview.STATE_DRAGGING) {
            cancelDrag();
            return true;
        }
        resetGesture();
        return false;
    }

    /** Long-press timeout: lift the card and start following the finger. */
    private void grab() {
        final SessionOverviewCardView card = mCard;
        if (mState != TermuxAppConstants.Overview.STATE_PRESSED || card == null) return;
        if (!card.isAttachedToWindow() || !mHost.canStartDrag(card)) {
            resetGesture();
            return;
        }
        if (mSettleCards != null || !captureLayout(card)) {
            resetGesture();
            return;
        }

        mState = TermuxAppConstants.Overview.STATE_DRAGGING;
        mDragChildIndex = indexOfChild(card);
        if (mDragChildIndex < 0) {
            resetGesture();
            return;
        }
        mPreviewTargetIndex = mDragChildIndex;

        // Follow the finger from the lift point. Snapping the card's centre to the finger here
        // made a long press look like the card jumped out from under the user's hand.
        mGrid.getLocationOnScreen(mGridLocation);
        mGrabGridX = mLastRawX - mGridLocation[0];
        mGrabGridY = mLastRawY - mGridLocation[1];
        mGrabTranslationX = card.getTranslationX();
        mGrabTranslationY = card.getTranslationY();

        card.cancelSwipeGesture();
        card.cancelHoverLift();
        card.setPressed(false);
        card.cancelLongPress();
        card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        disallowIntercept(card, true);
        card.addOnLayoutChangeListener(mCardLayoutListener);
        card.animate().cancel();
        card.restoreShadowColor();
        card.animate()
                .scaleX(FloatingShadow.OVERVIEW_CARD_LIFT_SCALE)
                .scaleY(FloatingShadow.OVERVIEW_CARD_LIFT_SCALE)
                .alpha(FloatingShadow.OVERVIEW_CARD_LIFT_ALPHA)
                .translationZ(mLiftZ)
                .setDuration(FloatingShadow.OVERVIEW_SHADOW_ANIM_MS)
                .setInterpolator(SessionOverviewCardView.DECELERATE)
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
        if (mState != TermuxAppConstants.Overview.STATE_DRAGGING || card == null || !card.isAttachedToWindow()) return;
        if (card.getWidth() <= 0 || card.getHeight() <= 0) return;

        mGrid.getLocationOnScreen(mGridLocation);
        final float gridX = mLastRawX - mGridLocation[0];
        final float gridY = mLastRawY - mGridLocation[1];
        card.setTranslationX(SessionOverviewReorderMath.dragTranslation(
                mGrabTranslationX, mGrabGridX, gridX));
        card.setTranslationY(SessionOverviewReorderMath.dragTranslation(
                mGrabTranslationY, mGrabGridY, gridY));
    }

    /**
     * Shift the other cards into the slots they would occupy if the drag were dropped now. The
     * child order is left alone — only translations change — so the touch target and the finger
     * following maths stay untouched until the release actually commits the reorder.
     */
    private void updatePreviewOrder() {
        if (mState != TermuxAppConstants.Overview.STATE_DRAGGING || mDragChildIndex < 0) return;
        final int count = mGrid.getChildCount();
        if (count < 2 || mBaseCenterXs.length != count || mBaseCenterYs.length != count) return;

        final int to = resolveDropTarget();
        if (to < 0 || to == mPreviewTargetIndex) return;
        mPreviewTargetIndex = to;

        final int[] order = SessionOverviewReorderMath.orderAfterMove(count, mDragChildIndex, to);
        if (order == null) return;
        for (int newIndex = 0; newIndex < count; newIndex++) {
            final int oldIndex = order[newIndex];
            if (oldIndex == mDragChildIndex) continue;
            final View child = mGrid.getChildAt(oldIndex);
            if (child == null) continue;
            child.animate().cancel();
            child.animate()
                    .translationX(mBaseCenterXs[newIndex] - mBaseCenterXs[oldIndex])
                    .translationY(mBaseCenterYs[newIndex] - mBaseCenterYs[oldIndex])
                    .setDuration(SHIFT_DURATION_MS)
                    .setInterpolator(SessionOverviewCardView.DECELERATE)
                    .start();
        }
    }

    /** Return the previewed neighbours to their own slots, with or without an animation. */
    private void clearPreviewOrder(boolean animate) {
        final int count = mGrid.getChildCount();
        for (int i = 0; i < count; i++) {
            if (i == mDragChildIndex) continue;
            final View child = mGrid.getChildAt(i);
            if (child == null) continue;
            child.animate().cancel();
            if (animate && child.isAttachedToWindow()) {
                child.animate()
                        .translationX(0f)
                        .translationY(0f)
                        .setDuration(SHIFT_DURATION_MS)
                        .setInterpolator(SessionOverviewCardView.DECELERATE)
                        .start();
            } else {
                child.setTranslationX(0f);
                child.setTranslationY(0f);
            }
        }
        mPreviewTargetIndex = -1;
    }

    /** @return the slot nearest the finger at release, or -1 when the layout is unusable. */
    private int resolveDropTarget() {
        final int count = mGrid.getChildCount();
        if (mState != TermuxAppConstants.Overview.STATE_DRAGGING || mDragChildIndex < 0 || count < 2
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
        if (mScroll == null || mState != TermuxAppConstants.Overview.STATE_DRAGGING) {
            stopAutoScroll();
            return;
        }
        // Idempotent: the running loop re-evaluates the finger position on every frame, exactly
        // like the post-opening hover auto-scroll.
        if (mAutoScrollPosted) return;
        mAutoScrollPosted = true;
        mAutoScrollLastTimeMs = SystemClock.uptimeMillis();
        mScroll.postOnAnimation(mAutoScrollRunnable);
    }

    private void stopAutoScroll() {
        mAutoScrollPosted = false;
        mAutoScrollLastTimeMs = 0L;
        if (mScroll != null) mScroll.removeCallbacks(mAutoScrollRunnable);
    }

    private void autoScrollFrame() {
        mAutoScrollPosted = false;
        if (mState != TermuxAppConstants.Overview.STATE_DRAGGING || mScroll == null) return;

        mScroll.getLocationOnScreen(mScrollLocation);
        final float pointerY = mLastRawY - mScrollLocation[1];

        final long now = SystemClock.uptimeMillis();
        final float frameRatio = SessionOverviewReorderMath.frameRatio(
                now, mAutoScrollLastTimeMs);
        mAutoScrollLastTimeMs = now;

        final int step = SessionOverviewReorderMath.autoScrollStep(
                pointerY, mScroll.getHeight(), mAutoScrollBand, mAutoScrollMaxStep, frameRatio);
        if (step == 0) {
            stopAutoScroll();
            return;
        }

        final int before = mScroll.getScrollY();
        mScroll.scrollBy(0, step);
        if (mScroll.getScrollY() == before) {
            // At the end of the content; the next MOVE will start the loop again if needed.
            stopAutoScroll();
            return;
        }

        // The grid moved under the finger, so the card's screen-relative translation has to be
        // refreshed even though the finger itself did not move.
        updateCardPosition();
        updatePreviewOrder();
        mAutoScrollPosted = true;
        mScroll.postOnAnimation(mAutoScrollRunnable);
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
        // Freeze the live preview at its current visual positions; the settle below animates from
        // exactly these centres, and no leftover animator may move a re-added child afterwards.
        for (int i = 0; i < mGrid.getChildCount(); i++) {
            final View child = mGrid.getChildAt(i);
            if (child != null) child.animate().cancel();
        }

        final int to = resolveDropTarget();
        // Snapshot the slot geometry now. A layout/model callback during the reorder below can
        // reset the controller's fields mid-drop; the settle must use the geometry captured while
        // the gesture was still active, not whatever the fields hold a few lines later.
        final float[] slotCenterXs = mBaseCenterXs.clone();
        final float[] slotCenterYs = mBaseCenterYs.clone();
        if (to < 0 || to == from) {
            // Dropped back where it started: no visual or model reorder happened during the drag.
            clearPreviewOrder(true);
            settleInPlace(card);
            disallowIntercept(card, false);
            resetGesture();
            return;
        }

        final int count = mGrid.getChildCount();
        final int[] order = SessionOverviewReorderMath.orderAfterMove(count, from, to);
        if (order == null || slotCenterXs.length != count || slotCenterYs.length != count) {
            cancelDrag();
            return;
        }

        // Keep the child order untouched during the flight. Re-adding the views first is what made
        // the dragged card appear in its new slot for a frame: detach reset its transforms, and the
        // seed was computed against slots the children did not occupy yet. Instead animate every
        // card from its current offset to the offset that will place it in its new slot, and only
        // reorder the children when the flight ends.
        final float[] startTx = new float[count];
        final float[] startTy = new float[count];
        final float[] endTx = new float[count];
        final float[] endTy = new float[count];
        final View[] children = new View[count];
        for (int newIndex = 0; newIndex < count; newIndex++) {
            final int oldIndex = order[newIndex];
            final View child = mGrid.getChildAt(oldIndex);
            if (!(child instanceof SessionOverviewCardView)) {
                cancelDrag();
                return;
            }
            children[oldIndex] = child;
            startTx[oldIndex] = child.getTranslationX();
            startTy[oldIndex] = child.getTranslationY();
            endTx[oldIndex] = slotCenterXs[newIndex]
                    - (child.getLeft() + child.getWidth() / 2f);
            endTy[oldIndex] = slotCenterYs[newIndex]
                    - (child.getTop() + child.getHeight() / 2f);
        }

        final float fromScale = card.getScaleX();
        final float fromAlpha = card.getAlpha();
        final float fromZ = card.getTranslationZ();
        final float fromShadow = card.getShadowAlphaFraction();
        cancelDropFlight(false);
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(TermuxAppConstants.Overview.SETTLE_DURATION_MS);
        animator.setInterpolator(SessionOverviewCardView.DECELERATE);
        animator.addUpdateListener(animation -> {
            final float progress = (float) animation.getAnimatedValue();
            for (int oldIndex = 0; oldIndex < count; oldIndex++) {
                final View child = children[oldIndex];
                child.setTranslationX(startTx[oldIndex]
                        + (endTx[oldIndex] - startTx[oldIndex]) * progress);
                child.setTranslationY(startTy[oldIndex]
                        + (endTy[oldIndex] - startTy[oldIndex]) * progress);
            }
            final float inverse = 1f - progress;
            card.setScaleX(1f + (fromScale - 1f) * inverse);
            card.setScaleY(1f + (fromScale - 1f) * inverse);
            card.setAlpha(1f + (fromAlpha - 1f) * inverse);
            card.setTranslationZ(fromZ * inverse);
            card.setShadowAlphaFraction(fromShadow * inverse);
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                mDropAnimator = null;
                finishDrop(count, order);
            }
        });
        mDropAnimator = animator;
        animator.start();
    }

    /** Reorder the children after the drop flight and hand the final order to the controller. */
    private void finishDrop(int count, int[] order) {
        final int from = mDragChildIndex;
        final List<SessionOverviewCardView> orderedCards = new ArrayList<>(count);
        final ViewGroup.LayoutParams[] params = new ViewGroup.LayoutParams[count];
        for (int newIndex = 0; newIndex < count; newIndex++) {
            final View child = mGrid.getChildAt(order[newIndex]);
            if (!(child instanceof SessionOverviewCardView)) {
                cancelDropFlight(false);
                return;
            }
            orderedCards.add((SessionOverviewCardView) child);
            params[newIndex] = child.getLayoutParams();
        }
        mGrid.removeAllViews();
        for (int newIndex = 0; newIndex < count; newIndex++) {
            mGrid.addView(orderedCards.get(newIndex), newIndex, params[newIndex]);
        }
        for (View child : orderedCards) {
            SessionOverviewCardView.resetVisual(child);
        }
        final int to = orderFromIndex(order, from);
        resetGesture();
        if (to >= 0) disallowIntercept(orderedCards.get(to), false);
        if (to >= 0 && from >= 0) mHost.onReorderCommitted(from, to, orderedCards);
    }

    private static int orderFromIndex(int[] order, int oldIndex) {
        for (int i = 0; i < order.length; i++) {
            if (order[i] == oldIndex) return i;
        }
        return -1;
    }

    /** Abort a drop flight without committing a model move, restoring the resting transforms. */
    private void cancelDropFlight(boolean commit) {
        if (mDropAnimator == null) return;
        mDropAnimator.removeAllUpdateListeners();
        mDropAnimator.removeAllListeners();
        mDropAnimator.cancel();
        mDropAnimator = null;
        if (!commit) {
            for (int i = 0; i < mGrid.getChildCount(); i++) {
                SessionOverviewCardView.resetVisual(mGrid.getChildAt(i));
            }
        }
    }

    /** Animate the lifted card back into its own slot without changing any order. */
    private void settleInPlace(@NonNull SessionOverviewCardView card) {
        if (!card.isAttachedToWindow()) return;
        card.animate().cancel();
        final float fromZ = card.getTranslationZ();
        final float fromShadow = card.getShadowAlphaFraction();
        final ViewPropertyAnimator animator = card.animate()
                .translationX(0f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .translationZ(0f)
                .setDuration(TermuxAppConstants.Overview.SETTLE_DURATION_MS)
                .setInterpolator(SessionOverviewCardView.DECELERATE);
        if (fromZ > 0f) {
            animator.setUpdateListener(animation -> card.setShadowAlphaFraction(
                    fromShadow * Math.max(0f,
                            Math.min(1f, card.getTranslationZ() / fromZ))));
            animator.withEndAction(() -> {
                card.setTranslationZ(0f);
                card.restoreShadowColor();
            });
        }
        animator.start();
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
        if (mState != TermuxAppConstants.Overview.STATE_IDLE || mSettleCards != null) return;
        final int count = cards.size();
        if (count == 0 || fromCenterXs.length != count || fromCenterYs.length != count
                || targetCenterXs.length != count || targetCenterYs.length != count) {
            return;
        }
        beginSettle(cards, fromCenterXs, fromCenterYs,
                targetCenterXs, targetCenterYs, -1);
        // Seed the old visual centres before the next layout. Otherwise the new grid can be
        // painted once in its destination slots and then jump back when settleWhenLaidOut()
        // applies the same translation after the layout pass.
        seedLayoutChangeTranslations(cards, fromCenterXs, fromCenterYs,
                targetCenterXs, targetCenterYs);
    }

    /**
     * Keep every card at its previous visual centre while the grid has not laid out the new
     * slots yet. Translation is relative to the laid-out bounds, so once layout moves a card to
     * its target slot the visual centre is again the old one and the settle animation can run
     * from there without an intermediate flash.
     */
    private static void seedLayoutChangeTranslations(
            @NonNull List<SessionOverviewCardView> cards,
            @NonNull float[] fromCenterXs, @NonNull float[] fromCenterYs,
            @NonNull float[] targetCenterXs, @NonNull float[] targetCenterYs) {
        if (cards.size() != fromCenterXs.length || cards.size() != targetCenterXs.length
                || cards.size() != fromCenterYs.length || cards.size() != targetCenterYs.length) {
            return;
        }
        for (int i = 0; i < cards.size(); i++) {
            cards.get(i).setTranslationX(fromCenterXs[i] - targetCenterXs[i]);
            cards.get(i).setTranslationY(fromCenterYs[i] - targetCenterYs[i]);
        }
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
                child.setScaleX(FloatingShadow.OVERVIEW_CARD_LIFT_SCALE);
                child.setScaleY(FloatingShadow.OVERVIEW_CARD_LIFT_SCALE);
                child.setAlpha(FloatingShadow.OVERVIEW_CARD_LIFT_ALPHA);
                child.setTranslationZ(mLiftZ);
            } else {
                child.setScaleX(1f);
                child.setScaleY(1f);
                child.setAlpha(1f);
                child.setTranslationZ(0f);
            }
            final float fromZ = child.getTranslationZ();
            final float fromShadow = child.getShadowAlphaFraction();
            final ViewPropertyAnimator animator = child.animate()
                    .translationX(0f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .translationZ(0f)
                    .setDuration(TermuxAppConstants.Overview.SETTLE_DURATION_MS)
                    .setInterpolator(SessionOverviewCardView.DECELERATE);
            if (fromZ > 0f) {
                animator.setUpdateListener(animation -> child.setShadowAlphaFraction(
                        fromShadow * Math.max(0f,
                                Math.min(1f, child.getTranslationZ() / fromZ))));
                animator.withEndAction(() -> {
                    child.setTranslationZ(0f);
                    child.restoreShadowColor();
                });
            }
            animator.start();
        }

        // The animations run independently from here on. A new long-press needs at least the
        // long-press timeout, which is much longer than this settle, so no generation token is
        // necessary for the normal case.
        clearSettleState();
    }

    private void cancelPendingSettle() {
        mGrid.removeCallbacks(mSettleRunnable);
        // The settle seeds translations before layout so the new slots cannot be painted for a
        // frame. If the settle is abandoned (another model change, a detached child or a layout
        // that never reaches the target), those seeds must not stay behind as a permanent offset.
        final List<SessionOverviewCardView> cards = mSettleCards;
        if (cards != null) {
            for (SessionOverviewCardView card : cards) {
                if (!card.isAttachedToWindow()) continue;
                card.animate().cancel();
                SessionOverviewCardView.resetVisual(card);
            }
        }
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
        cancelDropFlight(false);
        stopAutoScroll();
        if (card != null) {
            clearPreviewOrder(true);
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
        cancelDropFlight(false);
        if (card != null) card.removeCallbacks(mLongPressRunnable);
        stopAutoScroll();
        cancelPendingSettle();
        if (card != null) card.removeOnLayoutChangeListener(mCardLayoutListener);

        for (int i = 0; i < mGrid.getChildCount(); i++) {
            final View child = mGrid.getChildAt(i);
            child.animate().cancel();
            SessionOverviewCardView.resetVisual(child);
        }
        if (card != null) disallowIntercept(card, false);
        resetGesture();
    }

    private void resetGesture() {
        stopAutoScroll();
        if (mCard != null) mCard.removeCallbacks(mLongPressRunnable);
        mState = TermuxAppConstants.Overview.STATE_IDLE;
        mCard = null;
        mDragChildIndex = -1;
        mPreviewTargetIndex = -1;
        mBaseCenterXs = new float[0];
        mBaseCenterYs = new float[0];
    }

    private static void disallowIntercept(@Nullable View view, boolean disallow) {
        if (view == null) return;
        final ViewParent parent = view.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(disallow);
    }
}
