package com.termux.app.terminal;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Trace;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One session card in the overview, with horizontal swipe-to-close.
 *
 * <p>The card follows the finger and fades as it moves away from its slot, then lifts above its
 * neighbours. Releasing past the distance or fling threshold flies it out and asks the controller
 * to close the session; otherwise it springs back. A vertical gesture is left to the surrounding
 * {@link SessionOverviewScrollView}, and touches that start on the close button are left alone
 * because that child consumes them before they reach this view.</p>
 */
public class SessionOverviewCardView extends FrameLayout {

    public interface OnSwipeDismissListener {
        /**
         * Called after the card has flown off screen.
         *
         * @return true when the session was handed off for closing; false to spring the card back
         *         (for example when the overview is already leaving).
         */
        boolean onSwipeDismiss(@NonNull SessionOverviewCardView card, int direction);
    }

    private static final long DISMISS_DURATION_MS = 160L;
    private static final long RETURN_DURATION_MS = 200L;
    /** Stateless interpolator shared by every card, reorder and settle animation. */
    static final DecelerateInterpolator DECELERATE = new DecelerateInterpolator();

    private final int mTouchSlop;
    private final int mMinFlingVelocity;
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** System elevation used only while this card is the drop target of the opening gesture. */
    private float mHoverElevationPx;
    /** Full-opacity hover shadow colour; only its alpha is animated while the card lands. */
    private int mHoverShadowColor;
    /** Current fraction of {@link #mHoverShadowColor}, 1 at rest and 0 when fully faded. */
    private float mShadowAlphaFraction = 1f;
    private boolean mHoverHighlighted;
    @Nullable
    private ValueAnimator mHoverAnimator;
    private float mBorderWidth;
    private float mBorderRadius;
    private int mBorderColor;
    private float mBorderAlpha = 1f;
    private boolean mBorderSuppressed;

    @Nullable
    private OnSwipeDismissListener mSwipeDismissListener;
    @Nullable
    private ValueAnimator mSettleAnimator;
    @Nullable
    private VelocityTracker mVelocityTracker;

    private float mDownX;
    private float mDownY;
    private boolean mTracking;
    private boolean mDragging;
    private boolean mSettling;
    /** True while the finger is beyond the distance that dismisses on release. */
    private boolean mDismissThresholdPassed;
    /** True once this gesture has moved too far to still count as a tap. */
    private boolean mMovedPastTapSlop;
    private int mActivePointerId = MotionEvent.INVALID_POINTER_ID;

    public SessionOverviewCardView(Context context) {
        this(context, null);
    }

    public SessionOverviewCardView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SessionOverviewCardView(Context context, @Nullable AttributeSet attrs,
                                   int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        final ViewConfiguration configuration = ViewConfiguration.get(context);
        mTouchSlop = configuration.getScaledTouchSlop();
        mMinFlingVelocity = configuration.getScaledMinimumFlingVelocity();
        mBorderPaint.setStyle(Paint.Style.STROKE);
    }

    /**
     * Restore the six visual properties every card gesture mutates. Shared by swipe, hover,
     * reorder drop and transition cleanup so none of them can leave a stale lift behind.
     */
    static void resetVisual(@NonNull View view) {
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setAlpha(1f);
        view.setTranslationZ(0f);
        if (view instanceof SessionOverviewCardView) {
            // Z is already zero, so restoring the colour cannot make a shadow visible. It just
            // makes the next lift start from the full hover shadow instead of a faded one.
            ((SessionOverviewCardView) view).restoreShadowColor();
        }
    }

    /**
     * Set the outline drawn inside the card's rounded corners. The controller supplies the
     * overview card stroke width and the floating controls' colour.
     */
    public void setBorder(int widthPx, int color, float radiusPx) {
        mBorderWidth = Math.max(0, widthPx);
        mBorderRadius = Math.max(0f, radiusPx);
        mBorderColor = color;
        mBorderPaint.setStrokeWidth(mBorderWidth);
        updateBorderPaintColor();
        invalidate();
    }

    /** Continue alpha of the outline, driven by the overview opening/closing flights. */
    public void setBorderAlpha(float alpha) {
        alpha = Math.max(0f, Math.min(1f, alpha));
        if (mBorderAlpha == alpha) return;
        mBorderAlpha = alpha;
        updateBorderPaintColor();
        invalidate();
    }

    private void updateBorderPaintColor() {
        mBorderPaint.setColor((Math.round(Color.alpha(mBorderColor) * mBorderAlpha) << 24)
                | (mBorderColor & 0x00FFFFFF));
    }

    /** Hide the outline while a terminal snapshot owns the card's visual during a flight. */
    public void setBorderSuppressed(boolean suppressed) {
        if (mBorderSuppressed == suppressed) return;
        mBorderSuppressed = suppressed;
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        Trace.beginSection("overview.card.draw");
        super.dispatchDraw(canvas);
        if (mBorderSuppressed || mBorderWidth <= 0f
                || Color.alpha(mBorderPaint.getColor()) == 0
                || getWidth() <= mBorderWidth || getHeight() <= mBorderWidth) {
            Trace.endSection();
            return;
        }
        // Keep the stroke fully inside the rounded outline. The root is clipped to that outline,
        // so a stroke centred on the edge would lose half of its width.
        final float inset = mBorderWidth / 2f;
        final float radius = Math.max(0f, mBorderRadius - inset);
        canvas.drawRoundRect(inset, inset, getWidth() - inset, getHeight() - inset,
                radius, radius, mBorderPaint);
        Trace.endSection();
    }

    /**
     * Configure the system shadow used for hover. It mirrors the floating controls' shadow, but
     * is larger and less pronounced so the drop target reads as a soft lift, not a new layer.
     */
    public void setHoverShadow(int elevationPx, int color) {
        mHoverElevationPx = elevationPx;
        mHoverShadowColor = color;
        mShadowAlphaFraction = 1f;
        setShadowColor(color);
    }

    /** Change only the shadow colour, e.g. to fade a lift during a flight. */
    void setShadowColor(int color) {
        FloatingShadow.setShadowColors(this, color);
    }

    /**
     * Fade the elevation shadow without touching the card's own alpha or the terminal preview.
     * On API 28+ the shadow colours carry an independent alpha; below that the platform ignores
     * the colour and the card still relies on the Z animation alone.
     */
    void setShadowAlphaFraction(float fraction) {
        final float clamped = Math.max(0f, Math.min(1f, fraction));
        if (mShadowAlphaFraction == clamped) return;
        mShadowAlphaFraction = clamped;
        if (mHoverElevationPx <= 0f) return;
        setShadowColor(FloatingShadow.withAlphaFraction(mHoverShadowColor, clamped));
    }

    /** Current shadow-alpha fraction, used by the reorder controller to continue a fade. */
    float getShadowAlphaFraction() {
        return mShadowAlphaFraction;
    }

    /**
     * Put the full hover colour back. Call only when Z is already zero; otherwise the shadow
     * would pop back at full strength.
     */
    void restoreShadowColor() {
        mShadowAlphaFraction = 1f;
        if (mHoverElevationPx > 0f) setShadowColor(mHoverShadowColor);
    }

    public boolean isHoverHighlighted() {
        return mHoverHighlighted;
    }

    /** Let a drag take over the lift without animating the hover values back to rest first. */
    void cancelHoverLift() {
        if (mHoverAnimator != null) {
            mHoverAnimator.cancel();
            mHoverAnimator = null;
        }
        mHoverHighlighted = false;
    }

    /**
     * Animate the selection lift: scale, translation Z and shadow fade, with a single 254/255
     * alpha step for transparency. Horizontal swipe and long-press reorder keep the full 0.82
     * lift dimming; hover-to-select is effectively opaque so the card that opens a terminal
     * starts the flight without a visible fade.
     */
    public void setHoverHighlight(boolean highlighted) {
        if (mHoverHighlighted == highlighted) return;
        mHoverHighlighted = highlighted;
        if (mHoverAnimator != null) {
            mHoverAnimator.cancel();
            mHoverAnimator = null;
        }
        final float targetZ = highlighted ? mHoverElevationPx : 0f;
        final float targetScale = highlighted
                ? FloatingShadow.OVERVIEW_CARD_LIFT_SCALE : 1f;
        final float targetAlpha = highlighted
                ? FloatingShadow.OVERVIEW_CARD_HOVER_ALPHA : 1f;
        final float targetShadow = highlighted ? 1f : 0f;
        if (highlighted) restoreShadowColor();
        final float fromZ = getTranslationZ();
        final float fromScale = getScaleX();
        final float fromAlpha = getAlpha();
        final float fromShadow = mShadowAlphaFraction;
        mHoverAnimator = ValueAnimator.ofFloat(0f, 1f);
        mHoverAnimator.setDuration(FloatingShadow.OVERVIEW_SHADOW_ANIM_MS);
        mHoverAnimator.setInterpolator(DECELERATE);
        mHoverAnimator.addUpdateListener(animation -> {
            final float fraction = (float) animation.getAnimatedValue();
            setTranslationZ(fromZ + (targetZ - fromZ) * fraction);
            final float scale = fromScale + (targetScale - fromScale) * fraction;
            setScaleX(scale);
            setScaleY(scale);
            setAlpha(fromAlpha + (targetAlpha - fromAlpha) * fraction);
            setShadowAlphaFraction(
                    fromShadow + (targetShadow - fromShadow) * fraction);
        });
        mHoverAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mHoverAnimator = null;
                setTranslationZ(targetZ);
                setScaleX(targetScale);
                setScaleY(targetScale);
                setAlpha(targetAlpha);
                // Z is zero on the way out, so restoring the full colour here cannot pop the
                // shadow back into view; it only prepares the next lift.
                restoreShadowColor();
            }
        });
        mHoverAnimator.start();
    }

    public void setOnSwipeDismissListener(@Nullable OnSwipeDismissListener listener) {
        mSwipeDismissListener = listener;
    }

    /** Cancel any drag or settle animation and put the card back in its resting state. */
    public void resetSwipeState() {
        clearSettleAnimator();
        mTracking = false;
        mDragging = false;
        mSettling = false;
        mDismissThresholdPassed = false;
        mMovedPastTapSlop = false;
        mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        releaseVelocityTracker();
        setPressed(false);
        resetVisual(this);
        final android.view.ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
    }

    /**
     * Drop any in-flight swipe tracking when a long-press reorder takes over the touch stream.
     * The visual properties are deliberately left alone: the reorder controller sets the lift
     * transform immediately afterwards.
     */
    public void cancelSwipeGesture() {
        mTracking = false;
        mDragging = false;
        mDismissThresholdPassed = false;
        mMovedPastTapSlop = false;
        mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        releaseVelocityTracker();
        setPressed(false);
    }

    /** @return true while the card is flying out or springing back from a swipe. */
    public boolean isSettling() {
        return mSettling;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mSettling) return true;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // MotionEvent coordinates are transformed into this view's local space. Because
                // the card itself moves with setTranslationX(), using getX() directly would feed
                // the card's own translation back into the next dx (and make it oscillate between
                // the finger and its slot). Add the current translation so the delta is measured
                // in the parent's coordinate space and follows the finger exactly.
                mDownX = event.getX() + getTranslationX();
                mDownY = event.getY() + getTranslationY();
                mActivePointerId = event.getPointerId(0);
                mTracking = true;
                mDragging = false;
                mDismissThresholdPassed = false;
                mMovedPastTapSlop = false;
                obtainVelocityTracker();
                if (mVelocityTracker != null) mVelocityTracker.addMovement(event);
                // Let the normal click handling arm itself. A drag cancels it below.
                return super.onTouchEvent(event);
            }
            case MotionEvent.ACTION_MOVE: {
                if (!mTracking) return super.onTouchEvent(event);
                final int index = event.findPointerIndex(mActivePointerId);
                if (index < 0) return true;
                final float x = event.getX(index) + getTranslationX();
                final float y = event.getY(index) + getTranslationY();
                final float dx = x - mDownX;
                final float dy = y - mDownY;
                if (!mMovedPastTapSlop && Math.hypot(dx, dy) > mTouchSlop) {
                    // A tap selects a session; any real drag must not. This matters most when
                    // the grid fits without scrolling, because then the scroll view never
                    // intercepts and the card would otherwise receive the matching UP and
                    // perform its click.
                    mMovedPastTapSlop = true;
                    setPressed(false);
                    cancelLongPress();
                }
                if (!mDragging) {
                    if (Math.abs(dx) > mTouchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                        startDrag();
                    } else {
                        if (mVelocityTracker != null) mVelocityTracker.addMovement(event);
                        return super.onTouchEvent(event);
                    }
                }
                if (mVelocityTracker != null) mVelocityTracker.addMovement(event);
                updateDrag(x - mDownX);
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                onSecondaryPointerUp(event);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (mVelocityTracker != null) mVelocityTracker.addMovement(event);
                if (mDragging) {
                    finishDrag();
                } else {
                    if (mMovedPastTapSlop) {
                        setPressed(false);
                    } else {
                        super.onTouchEvent(event);
                    }
                    resetTracking();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (mVelocityTracker != null) mVelocityTracker.addMovement(event);
                if (mDragging) {
                    animateBack();
                } else {
                    super.onTouchEvent(event);
                    resetTracking();
                }
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    private void startDrag() {
        mDragging = true;
        setPressed(false);
        cancelLongPress();
        // A hover from the opening gesture may still own the lift animator. Release it so the
        // drag below is the single owner of scale, alpha and Z.
        cancelHoverLift();
        final float liftZ = mHoverElevationPx > 0f
                ? mHoverElevationPx
                : FloatingShadow.overviewCardElevationPx(getResources());
        animate().cancel();
        // The drag owns alpha from the first movement: updateDrag() maps horizontal distance
        // to opacity. Animating alpha here as well would keep overwriting that value until the
        // lift finished, so the card only started fading after the full lift.
        setAlpha(FloatingShadow.OVERVIEW_CARD_LIFT_ALPHA);
        restoreShadowColor();
        animate()
                .scaleX(FloatingShadow.OVERVIEW_CARD_LIFT_SCALE)
                .scaleY(FloatingShadow.OVERVIEW_CARD_LIFT_SCALE)
                .translationZ(liftZ)
                .setDuration(FloatingShadow.OVERVIEW_SHADOW_ANIM_MS)
                .setInterpolator(DECELERATE)
                .start();
        final android.view.ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
    }

    private void updateDrag(float dx) {
        setTranslationX(dx);
        // Stay at the shared lift alpha while the card is back in its slot: a fully opaque card
        // would hide the shadow behind it and show a hard rectangle around the translucent preview.
        setAlpha(Math.min(FloatingShadow.OVERVIEW_CARD_LIFT_ALPHA,
                SessionOverviewSwipeMath.dragAlpha(dx, getWidth())));

        final boolean passed = SessionOverviewSwipeMath.isPastDismissThreshold(dx, getWidth());
        if (passed != mDismissThresholdPassed) {
            mDismissThresholdPassed = passed;
            if (passed) {
                // One tick as the card enters the dismiss zone, matching the reorder and
                // tab-strip threshold feedback. Returning under the threshold and crossing
                // again ticks again.
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
        }
    }

    private void finishDrag() {
        final float dx = getTranslationX();
        float velocityX = 0f;
        if (mVelocityTracker != null) {
            mVelocityTracker.computeCurrentVelocity(1000);
            velocityX = mVelocityTracker.getXVelocity(mActivePointerId);
        }
        final boolean dismiss = SessionOverviewSwipeMath.shouldDismiss(
                dx, velocityX, getWidth(), mMinFlingVelocity);
        resetTracking();
        if (dismiss) {
            animateOut(SessionOverviewSwipeMath.dismissDirection(dx));
        } else {
            animateBack();
        }
    }

    private void animateOut(int direction) {
        final float fromTranslation = getTranslationX();
        // Same 32dp fly-out distance as before, now derived from the shared shadow size.
        final float toTranslation = direction
                * (getWidth() + FloatingShadow.overviewCardElevationPx(getResources()) * 2f);
        final float fromAlpha = getAlpha();
        final float fromShadow = mShadowAlphaFraction;
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DISMISS_DURATION_MS);
        animator.setInterpolator(DECELERATE);
        animator.addUpdateListener(animation -> {
            final float progress = (float) animation.getAnimatedValue();
            setTranslationX(fromTranslation + (toTranslation - fromTranslation) * progress);
            setAlpha(fromAlpha + (0f - fromAlpha) * progress);
            // The card leaves instead of landing, but its shadow must not stay behind at full
            // strength while the card itself becomes transparent.
            setShadowAlphaFraction(fromShadow * (1f - progress));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mSettleAnimator = null;
                if (mSwipeDismissListener != null
                        && mSwipeDismissListener.onSwipeDismiss(
                        SessionOverviewCardView.this, direction)) {
                    return;
                }
                if (isAttachedToWindow()) {
                    animateBack();
                } else {
                    resetSwipeState();
                }
            }
        });
        mSettleAnimator = animator;
        mSettling = true;
        animator.start();
    }

    private void animateBack() {
        final float fromTranslation = getTranslationX();
        final float fromAlpha = getAlpha();
        final float fromZ = getTranslationZ();
        final float fromScale = getScaleX();
        final float fromShadow = mShadowAlphaFraction;
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(RETURN_DURATION_MS);
        animator.setInterpolator(DECELERATE);
        animator.addUpdateListener(animation -> {
            final float progress = (float) animation.getAnimatedValue();
            setTranslationX(fromTranslation * (1f - progress));
            setAlpha(fromAlpha + (1f - fromAlpha) * progress);
            // Fade the lift out with the same curve as the hover highlight, not in one frame.
            setTranslationZ(fromZ * (1f - progress));
            setShadowAlphaFraction(fromShadow * (1f - progress));
            final float scale = 1f + (fromScale - 1f) * (1f - progress);
            setScaleX(scale);
            setScaleY(scale);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mSettleAnimator = null;
                mSettling = false;
                resetVisual(SessionOverviewCardView.this);
                final android.view.ViewParent parent = getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
            }
        });
        mSettleAnimator = animator;
        mSettling = true;
        animator.start();
    }

    private void clearSettleAnimator() {
        if (mSettleAnimator != null) {
            final ValueAnimator animator = mSettleAnimator;
            mSettleAnimator = null;
            animator.removeAllListeners();
            animator.cancel();
        }
    }

    private void resetTracking() {
        mTracking = false;
        mDragging = false;
        mMovedPastTapSlop = false;
        mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        releaseVelocityTracker();
    }

    private void obtainVelocityTracker() {
        releaseVelocityTracker();
        mVelocityTracker = VelocityTracker.obtain();
    }

    private void releaseVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    private void onSecondaryPointerUp(MotionEvent event) {
        final int pointerIndex = event.getActionIndex();
        if (event.getPointerId(pointerIndex) != mActivePointerId) return;
        final int newIndex = pointerIndex == 0 ? 1 : 0;
        mActivePointerId = event.getPointerId(newIndex);
        // Both pointers share the same view translation; use parent-space coordinates for the
        // rebase so a subsequent move remains continuous.
        final float oldX = event.getX(pointerIndex) + getTranslationX();
        final float oldY = event.getY(pointerIndex) + getTranslationY();
        final float newX = event.getX(newIndex) + getTranslationX();
        final float newY = event.getY(newIndex) + getTranslationY();
        mDownX += newX - oldX;
        mDownY += newY - oldY;
    }

    @Override
    protected void onDetachedFromWindow() {
        resetSwipeState();
        super.onDetachedFromWindow();
    }
}
