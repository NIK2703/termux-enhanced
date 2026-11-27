package com.termux.app.terminal;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Trace;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One animator for every structural grid change.
 *
 * <p>The controller translates survivors from their old visual centres into the new slots and, when
 * the viewport also moves, compensates that scroll with the same progress. No second settle or
 * layout-driven scroll animation is started. Rows and session counts never appear here: only card
 * centres and scroll offsets.</p>
 */
final class SessionOverviewLayoutTransitionController {

    private static final Interpolator CARD_INTERPOLATOR = new DecelerateInterpolator();
    private static final Interpolator SCROLL_INTERPOLATOR =
            new PathInterpolator(0f, 0f, 0.2f, 1f);

    private final SessionOverviewGridView mGrid;
    @Nullable
    private final SessionOverviewScrollView mScrollView;

    private final List<View> mCards = new ArrayList<>();
    private float[] mFromX = new float[0];
    private float[] mFromY = new float[0];
    private float[] mStartX = new float[0];
    private float[] mStartY = new float[0];

    private boolean mSeeded;
    private boolean mUseClamp;
    private int mOldScrollY;
    private int mSeedVisualOffset;
    private int mTargetScrollY;
    private boolean mClamped;

    private int mGeneration;
    @Nullable
    private Runnable mBeginRunnable;
    @Nullable
    private ValueAnimator mAnimator;

    /**
     * Runs after a layout that was already requested when the plan was seeded. The post keeps the
     * begin pass behind the scroll view's own layout, where a removal clamp is reported.
     */
    @Nullable
    private View.OnLayoutChangeListener mLayoutListener;

    SessionOverviewLayoutTransitionController(@NonNull SessionOverviewGridView grid,
                                              @Nullable SessionOverviewScrollView scrollView) {
        mGrid = grid;
        mScrollView = scrollView;
        mLayoutListener = (view, left, top, right, bottom,
                           oldLeft, oldTop, oldRight, oldBottom) -> {
            if (!mSeeded || mBeginRunnable == null) return;
            view.removeOnLayoutChangeListener(mLayoutListener);
            mGrid.post(mBeginRunnable);
        };
    }

    boolean isActive() {
        return mSeeded || mAnimator != null;
    }

    /**
     * Seed translations before the model's layout pass so the new slots are never painted in their
     * final position for a frame. Call {@link #begin()} once the new layout has happened.
     *
     * @param fromXs/fromYs content centres captured before the change; the old structural offset
     *                     is carried separately
     * @param targetXs/targetYs predicted centres from the new geometry
     * @param targetScrollY explicit scroll target, ignored when {@code useClamp} is true
     * @param useClamp true for removals: the platform's layout clamp supplies the target
     */
    void seed(@NonNull List<View> cards,
              @NonNull float[] fromXs, @NonNull float[] fromYs,
              @NonNull float[] targetXs, @NonNull float[] targetYs,
              int targetScrollY, boolean useClamp) {
        cancel();
        final int count = Math.min(cards.size(),
                Math.min(Math.min(fromXs.length, fromYs.length),
                        Math.min(targetXs.length, targetYs.length)));
        if (count == 0) return;

        mCards.clear();
        mFromX = new float[count];
        mFromY = new float[count];
        mStartX = new float[count];
        mStartY = new float[count];
        for (int i = 0; i < count; i++) {
            final View view = cards.get(i);
            mCards.add(view);
            mFromX[i] = fromXs[i];
            mFromY[i] = fromYs[i];
            mStartX[i] = fromXs[i] - targetXs[i];
            mStartY[i] = fromYs[i] - targetYs[i];
            view.setTranslationX(mStartX[i]);
            view.setTranslationY(mStartY[i]);
        }

        mUseClamp = useClamp;
        mClamped = false;
        // Captured centres are content coordinates without any scroll displacement. The old
        // visual position is therefore from - oldScroll + oldSettleOffset; keeping oldScroll real
        // and carrying the settle offset separately avoids counting it twice on interruption.
        mOldScrollY = mScrollView == null ? 0 : mScrollView.getScrollY();
        mSeedVisualOffset = mScrollView == null
                ? 0 : Math.round(mScrollView.getSettleOffsetPx());
        if (mScrollView != null) {
            // A layout-driven scrollToActiveCard() can start its own settle before the post-layout
            // start callback reaches us. This transition owns scroll from here on, so stop that
            // settle now and keep the old structural offset until begin() recomputes it.
            mScrollView.finishContentTransition();
            mScrollView.setProgrammaticScrollOffsetPx(mSeedVisualOffset);
        }
        mTargetScrollY = targetScrollY;
        mSeeded = true;

        final int generation = ++mGeneration;
        mBeginRunnable = () -> {
            if (generation == mGeneration) begin();
        };
        if (mGrid.isLayoutRequested()) {
            // A removal seeds before the traversal. Waiting for the actual layout keeps the old
            // centres on screen and lets the scroll view report its clamp before the animation
            // starts. The listener only schedules begin; the post then runs after the parent
            // scroll view has finished its own layout pass.
            mGrid.addOnLayoutChangeListener(mLayoutListener);
        } else {
            mGrid.post(mBeginRunnable);
        }
    }

    /** Fold a real clamp that happened during layout into this transition instead of starting a new settle. */
    void onContentClamped(int before, int after) {
        if (!mSeeded) return;
        if (after == before) return;
        mClamped = true;
        if (mScrollView != null) {
            // The layout frame is about to draw. Apply the folded offset now so the clamp cannot
            // flash before the posted begin() pass.
            mScrollView.setProgrammaticScrollOffsetPx(
                    SessionOverviewLayoutTransitionMath.offsetAfterClamp(
                            mSeedVisualOffset, mOldScrollY, after));
        }
    }

    private void begin() {
        if (!mSeeded) return;
        Trace.beginSection("overview.grid.layoutTransition");
        if (mCards.isEmpty()) {
            finishNow();
            Trace.endSection();
            return;
        }
        final int count = mCards.size();
        final boolean[] ready = {true};
        for (int i = 0; i < count; i++) {
            final View view = mCards.get(i);
            if (!view.isAttachedToWindow() || view.getWidth() <= 0 || view.getHeight() <= 0) {
                ready[0] = false;
                break;
            }
            final float baseX = view.getLeft() + view.getWidth() / 2f;
            final float baseY = view.getTop() + view.getHeight() / 2f;
            mStartX[i] = mFromX[i] - baseX;
            mStartY[i] = mFromY[i] - baseY;
        }
        if (!ready[0]) {
            // The seed is posted only after the layout it depends on. A card that still has no
            // bounds cannot be animated safely; apply the final geometry instead of polling.
            finishNow();
            Trace.endSection();
            return;
        }
        mSeeded = false;
        mBeginRunnable = null;
        mGrid.removeOnLayoutChangeListener(mLayoutListener);

        int offset;
        if (mScrollView == null) {
            offset = 0;
        } else if (mUseClamp) {
            offset = SessionOverviewLayoutTransitionMath.offsetAfterClamp(
                    mSeedVisualOffset, mOldScrollY, mScrollView.getScrollY());
            mTargetScrollY = mScrollView.getScrollY();
        } else {
            final int maxScroll = Math.max(0, mGrid.getHeight() - mScrollView.getHeight());
            mTargetScrollY = SessionOverviewLayoutTransitionMath.clamp(
                    mTargetScrollY, 0, maxScroll);
            if (mScrollView.getScrollY() != mTargetScrollY) {
                mScrollView.scrollTo(0, mTargetScrollY);
            }
            offset = SessionOverviewLayoutTransitionMath.offsetAfterClamp(
                    mSeedVisualOffset, mOldScrollY, mScrollView.getScrollY());
        }

        if (mScrollView != null) mScrollView.setProgrammaticScrollOffsetPx(offset);

        final float[] motion = new float[count];
        boolean hasCardMotion = false;
        for (int i = 0; i < count; i++) {
            final View view = mCards.get(i);
            view.setTranslationX(mStartX[i]);
            view.setTranslationY(mStartY[i]);
            motion[i] = mStartX[i] != 0f || mStartY[i] != 0f ? 1f : 0f;
            if (motion[i] != 0f) hasCardMotion = true;
        }
        final boolean hasScroll = offset != 0 || mClamped;
        if (!hasCardMotion && !hasScroll) {
            clear();
            Trace.endSection();
            return;
        }

        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(SessionOverviewLayoutTransitionMath.durationMs(hasScroll));
        animator.setInterpolator(hasScroll ? SCROLL_INTERPOLATOR : CARD_INTERPOLATOR);
        animator.addUpdateListener(animation -> {
            if (mAnimator != animation) return;
            Trace.beginSection("overview.grid.layoutTransition.frame");
            final float progress = (float) animation.getAnimatedValue();
            for (int i = 0; i < mCards.size(); i++) {
                final View view = mCards.get(i);
                view.setTranslationX(SessionOverviewLayoutTransitionMath.remaining(
                        mStartX[i], progress));
                view.setTranslationY(SessionOverviewLayoutTransitionMath.remaining(
                        mStartY[i], progress));
            }
            if (mScrollView != null) {
                mScrollView.setProgrammaticScrollOffsetPx(
                        SessionOverviewLayoutTransitionMath.remaining(offset, progress));
            }
            Trace.endSection();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mAnimator != animation) return;
                clear();
            }
        });
        mAnimator = animator;
        animator.start();
        Trace.endSection();
    }

    /** Apply the final state now: no translation and no visual scroll offset. */
    void finishNow() {
        if (!isActive()) return;
        final List<View> views = new ArrayList<>(mCards);
        cancel();
        for (View view : views) {
            view.setTranslationX(0f);
            view.setTranslationY(0f);
        }
        if (mScrollView != null) mScrollView.setProgrammaticScrollOffsetPx(0f);
    }

    /** Drop state without animating; used when another plan is about to seed from live bounds. */
    void cancelForModelChange() {
        cancel();
    }

    private void cancel() {
        mGeneration++;
        mGrid.removeOnLayoutChangeListener(mLayoutListener);
        if (mBeginRunnable != null) {
            mGrid.removeCallbacks(mBeginRunnable);
            mBeginRunnable = null;
        }
        final ValueAnimator animator = mAnimator;
        mAnimator = null;
        if (animator != null) {
            animator.removeAllUpdateListeners();
            animator.removeAllListeners();
            animator.cancel();
        }
        mSeeded = false;
        mCards.clear();
        mFromX = new float[0];
        mFromY = new float[0];
        mStartX = new float[0];
        mStartY = new float[0];
    }

    private void clear() {
        for (View view : mCards) {
            view.setTranslationX(0f);
            view.setTranslationY(0f);
        }
        if (mScrollView != null) mScrollView.setProgrammaticScrollOffsetPx(0f);
        cancel();
    }
}
