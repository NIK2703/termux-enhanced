package com.termux.app.terminal;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

import androidx.annotation.Nullable;

import com.termux.view.ElasticOverdrag;

/**
 * Scroll view for the overview grid.
 *
 * <p>A normal {@link ScrollView} measures its child with an unspecified height. The grid still
 * needs to know how tall the visible viewport is so it can size a 2x2 page of cards and then
 * append extra rows. This view passes that height down before measuring.</p>
 *
 * <p>The vertical over-drag is the same elastic ("rubber band") model as the session tab strip
 * and the terminal pager: {@link ElasticHorizontalScrollView} is the horizontal adapter and this
 * is the vertical one. They share {@link ElasticOverdrag} for the pull curve, cap, spring and
 * fling impulse; only the gesture plumbing and the axis differ.</p>
 */
public class SessionOverviewScrollView extends ScrollView {

    /** The first (top) end: the content is pulled DOWN, so translationY > 0. */
    private static final int DIRECTION_TOP = -1;

    /** The last (bottom) end: the content is pulled UP, so translationY < 0. */
    private static final int DIRECTION_BOTTOM = 1;

    /**
     * How long the scroller may be silent before later frames are no longer assumed to belong to
     * this view's fling. Mirrors {@link ElasticHorizontalScrollView#FLING_IDLE_MS}.
     */
    private static final long FLING_IDLE_MS = 96L;

    /** Accumulated (undamped) finger travel past the top / bottom end, in px. Never negative. */
    private float mTopRawPx;
    private float mBottomRawPx;

    /** The damped displacement currently applied as the content's {@code translationY}, in px. */
    private float mTranslationPx;

    @Nullable
    private ValueAnimator mSpring;

    /** The fly-out of a fling impact (the band being stretched); null unless one is running. */
    @Nullable
    private ValueAnimator mImpact;

    // ── fling bookkeeping ──────────────────────────────────────────────────────────────────
    // One flag per hole in "a fling ran into the end of the list":
    //   mFlingFrameSeen   — a fling frame arrived since the last settle check (still travelling),
    //   mFlingImpactSpent — the one bounce per fling has already been played,
    //   mDragPullAbsorbed — the release that launched this fling already held a pull, so the
    //                       impulse must not be stacked on top of it.
    private boolean mFlingFrameSeen;
    private boolean mFlingImpactSpent;
    private boolean mDragPullAbsorbed;

    /**
     * True while the scroller frames arriving are a fling this view launched. Armed by
     * {@link #fling(int)} and disarmed after {@link #FLING_IDLE_MS} of silence, so a programmatic
     * scroll finishing on an end is not mistaken for an impact.
     */
    private boolean mFlingArmed;

    /** Timestamp of the previous fling frame, for {@link #onFlingFrame}'s speed measurement. */
    private long mLastFlingFrameNanos;

    /**
     * The speed the fling is carrying, in px/s — refreshed by every frame the list absorbed and
     * left alone by the frames it did not, so it still holds the last speed the fling had when a
     * frame leaves the grid pinned to an end.
     */
    private float mFlingArrivalPxPerSec;

    /** The velocity the fling was launched with, as handed to {@link #fling(int)}. */
    private float mFlingLaunchVelocityPx;

    /** Deferred {@link #settleNow()} — see {@link #ensureSettled()}. */
    private final Runnable mSettleRunnable = this::settleNow;

    /** {@link #mFlingArmed}'s expiry. */
    private final Runnable mFlingDisarm = () -> mFlingArmed = false;

    public SessionOverviewScrollView(Context context) {
        this(context, null);
    }

    public SessionOverviewScrollView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SessionOverviewScrollView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public SessionOverviewScrollView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
                                     int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        // The platform's edge glow / stretch would stack on top of the elastic displacement.
        // OVER_SCROLL_NEVER also pins View#overScrollBy's clamp to exactly [0, scrollRange],
        // which makes the unconsumed-delta arithmetic in overScrollBy() exact.
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        // The overview is a full-screen surface with no need for a scroll affordance; the card
        // grid itself is the content. Removing the bar also avoids a strip appearing over the
        // right-most card while the grid is pulled.
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        setVerticalFadingEdgeEnabled(false);
        setHorizontalFadingEdgeEnabled(false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final View child = getChildAt(0);
        if (child instanceof SessionOverviewGridView) {
            ((SessionOverviewGridView) child).setViewportHeight(
                    MeasureSpec.getSize(heightMeasureSpec));
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    // ── gesture input ──────────────────────────────────────────────────────────────────────

    /**
     * The single hook into the grid's own scrolling. Called with the delta the widget is about to
     * apply, before it is clamped — the only moment the unconsumed part is still visible.
     * {@code maxOverScrollY} is ignored: with {@link #OVER_SCROLL_NEVER} the framework forces it
     * to 0, so honouring a non-zero margin here would let the framework scroll past the end and
     * count it as finger travel as well.
     *
     * <p>A grid with four or fewer cards fits the viewport, so {@code scrollRangeY} is 0 and the
     * elastic layer stays off: there is no end to pull away from. Runtime rather than one-off at
     * attach time because the range changes as sessions come and go.</p>
     */
    @Override
    protected boolean overScrollBy(int deltaX, int deltaY, int scrollX, int scrollY,
                                   int scrollRangeX, int scrollRangeY,
                                   int maxOverScrollX, int maxOverScrollY, boolean isTouchEvent) {
        if (deltaY != 0 && scrollRangeY > 0) {
            final int wanted = scrollY + deltaY;
            final int clamped = Math.max(0, Math.min(scrollRangeY, wanted));
            // Where the grid will actually stop. The difference is finger travel it had nowhere
            // to put — the over-pull — and the rest is travel it absorbed, i.e. real scroll.
            final int unconsumedPx = Math.abs(wanted - clamped);
            final int consumedPx = Math.abs(deltaY) - unconsumedPx;
            // Direction from the delta (direction of travel), not from where the frame ends up:
            // a clamped fling finishes with `wanted` exactly on an end, and a sign test on
            // `wanted` would read a fling into the top as a bottom-end pull.
            final int direction = deltaY < 0 ? DIRECTION_TOP : DIRECTION_BOTTOM;
            if (isTouchEvent) {
                if (unconsumedPx > 0) onDragDelta(direction, consumedPx, unconsumedPx);
                else bleedIntoScroll(consumedPx);
            } else {
                onFlingFrame(direction, Math.abs(deltaY), consumedPx, clamped, scrollRangeY);
            }
        }
        return super.overScrollBy(deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY,
                maxOverScrollX, maxOverScrollY, isTouchEvent);
    }

    /**
     * One drag frame that ran past an end. A frame can do both at once — the last of the scroll
     * and the first of the pull — when the finger crosses the boundary mid-frame. The scroll half
     * is handed back first, so the pull the frame leaves behind is measured from the boundary
     * rather than from the frame's start.
     */
    private void onDragDelta(int direction, int consumedPx, int unconsumedPx) {
        if (consumedPx > 0) bleedIntoScroll(consumedPx);
        if (unconsumedPx > 0) onEdgePull(direction, unconsumedPx);
    }

    /**
     * One fling frame: either still travelling (bleed + record speed) or left pinned to an end —
     * the impulse {@link #onEdgeAbsorb} wants.
     *
     * <p>A {@link ScrollView} keeps its {@code OverScroller} private, so the speed is measured:
     * refreshed from {@code |deltaY| / dt} only on frames the grid absorbed (for a clamped fling
     * that last in-range frame is the arrival speed), falling back to the launch velocity when a
     * flick started on an end and never had an in-range frame. Measuring the pinned frame's own
     * delta would read the scroller's leftover as a frame step and saturate the band on every
     * bounce.</p>
     */
    private void onFlingFrame(int direction, int requestedPx, int consumedPx,
                              int clampedPosition, int scrollRangeY) {
        mFlingFrameSeen = true;
        armFlingLifetime();

        final long now = System.nanoTime();
        final float dtSeconds = mLastFlingFrameNanos == 0L
                ? 0f : (now - mLastFlingFrameNanos) / 1_000_000_000f;
        mLastFlingFrameNanos = now;

        if (consumedPx > 0) {
            // In range: one frame's travel over that frame's own duration is a real speed.
            if (dtSeconds > 0f && isFinite(dtSeconds)) {
                mFlingArrivalPxPerSec = requestedPx / dtSeconds;
            }
            bleedIntoScroll(consumedPx);
            // It is a fling giving the pull back — nothing else will notice if this one stops
            // before the displacement reaches 0.
            ensureSettled();
        }

        if (mFlingImpactSpent || mDragPullAbsorbed || !mFlingArmed) return;
        // "At an end": the position is pinned to one of the two ends — landed exactly on one or
        // was clamped back onto one. Range 0 never arrives here: overScrollBy() gates on it.
        if (clampedPosition != 0 && clampedPosition != scrollRangeY) return;

        final float velocity = mFlingArrivalPxPerSec > 0f
                ? mFlingArrivalPxPerSec
                : Math.abs(mFlingLaunchVelocityPx);
        mFlingImpactSpent = true;
        onEdgeAbsorb(direction, velocity);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Handled here rather than in {@link #onTouchEvent} because this is the only place that
     * sees the whole gesture: a tap on a card never reaches {@code onTouchEvent}, so a release
     * hooked there would leave the previous pull hanging. The rest runs after {@code super}, so
     * the fling launched on ACTION_UP is registered before the spring is armed and the held pull
     * is recorded before {@link #onEdgeRelease} consumes it.</p>
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        final int action = ev.getActionMasked();
        final boolean handled = super.dispatchTouchEvent(ev);
        if (action == MotionEvent.ACTION_DOWN) {
            mDragPullAbsorbed = false;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_CANCEL) {
                reset();
            } else {
                // Read before the release zeroes the accumulators: this release is already being
                // answered by the pull's own spring, so the fling it launched must not add an
                // impulse on top.
                mDragPullAbsorbed = mTranslationPx != 0f;
                onEdgeRelease();
            }
        }
        return handled;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Records the start of a fling: its launch velocity, the state that makes the bounce happen
     * at most once per fling, and the lifetime of {@link #mFlingArmed}.</p>
     */
    @Override
    public void fling(int velocityY) {
        mFlingLaunchVelocityPx = velocityY;
        mFlingArrivalPxPerSec = 0f;
        mFlingImpactSpent = false;
        mFlingFrameSeen = false;
        mFlingArmed = true;
        mLastFlingFrameNanos = 0L;
        armFlingLifetime();
        super.fling(velocityY);
    }

    /**
     * Keep {@link #mFlingArmed} true while a scroller produces frames and let it lapse
     * {@link #FLING_IDLE_MS} after the last one.
     */
    private void armFlingLifetime() {
        removeCallbacks(mFlingDisarm);
        postOnAnimationDelayed(mFlingDisarm, FLING_IDLE_MS);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A programmatic scroll repositions the grid for a reason unrelated to the finger
     * ({@code SessionOverviewController} drives {@code scrollTo} to reveal the active card), so a
     * held pull is released into its spring rather than left hanging. Gated on the position
     * actually moving, because {@code ScrollView#onLayout} ends by calling this with the current
     * values — a no-op that must not be read as a reposition.</p>
     */
    @Override
    public void scrollTo(int x, int y) {
        final int before = getScrollY();
        super.scrollTo(x, y);
        if (getScrollY() != before && mTranslationPx != 0f) {
            mTopRawPx = 0f;
            mBottomRawPx = 0f;
            startSpring(mTranslationPx);
        }
    }

    // ── lifecycle ──────────────────────────────────────────────────────────────────────────

    @Override
    protected void onDetachedFromWindow() {
        // A view pulled out of the window can no longer be sprung back, and whatever displacement
        // it was holding must not survive into the next attachment.
        reset();
        super.onDetachedFromWindow();
    }

    // ── gesture input, physics side ────────────────────────────────────────────────────────

    /**
     * The finger travelled {@code deltaPx} further past one end. Always positive — an increasing
     * pull; {@code direction} is {@link #DIRECTION_TOP} (content moves down) or
     * {@link #DIRECTION_BOTTOM} (content moves up).
     */
    private void onEdgePull(int direction, float deltaPx) {
        if (!(deltaPx > 0f) || !isFinite(deltaPx)) return;
        cancelAnimators();
        // A new pull first pays off whatever the opposite end still holds, so "at most one end
        // is ever displaced" holds by construction.
        if (direction == DIRECTION_TOP) {
            float returned = Math.min(mBottomRawPx, deltaPx);
            mBottomRawPx = clampRaw(mBottomRawPx - returned);
            mTopRawPx = clampRaw(mTopRawPx + deltaPx - returned);
        } else {
            float returned = Math.min(mTopRawPx, deltaPx);
            mTopRawPx = clampRaw(mTopRawPx - returned);
            mBottomRawPx = clampRaw(mBottomRawPx + deltaPx - returned);
        }
        apply();
    }

    /** Finger lifted (or the gesture was cancelled): spring back to the end. */
    private void onEdgeRelease() {
        if (mTranslationPx == 0f) {
            mTopRawPx = 0f;
            mBottomRawPx = 0f;
            return;
        }
        mTopRawPx = 0f;
        mBottomRawPx = 0f;
        startSpring(mTranslationPx);
    }

    /**
     * A fling ran into the end: convert a slice of its arrival speed into extra pull on the end
     * that was actually hit, then spring back. Same {@link ElasticOverdrag#impact} call as the
     * tab strip and pager, so a given gesture thresholds and caps identically on every surface.
     */
    private void onEdgeAbsorb(int direction, float velocityPxPerSec) {
        if (!(velocityPxPerSec > 0f) || !isFinite(velocityPxPerSec)) {
            onEdgeRelease();
            return;
        }
        cancelAnimators();
        final ElasticOverdrag.Impact impact =
                ElasticOverdrag.impact(velocityPxPerSec, extentPx(), 0f, density());
        final float base = (direction == DIRECTION_TOP) ? mTopRawPx : mBottomRawPx;
        startImpact(direction, base, impact);
    }

    /**
     * Play the fly-out half of an impact: the band being stretched by the impulse. Samples are
     * raw travel integrated in displacement, so the content follows the band being loaded and
     * starts at the impulse's own speed. The last sample hands the stretch to
     * {@link #onEdgeRelease()}.
     */
    private void startImpact(int direction, float baseRaw,
                             @Nullable ElasticOverdrag.Impact impact) {
        cancelAnimators();
        if (impact == null || impact.durationMs <= 0L || !(impact.peakTravelPx() > 0f)) {
            onEdgeRelease();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(impact.durationMs);
        // Linear: the samples ARE the timing. Shared instance — allocating one per fling for an
        // interpolator that is, by definition, stateless is pointless.
        animator.setInterpolator(ElasticOverdrag.LINEAR);
        animator.addUpdateListener(animation -> {
            float progress = (Float) animation.getAnimatedValue();
            if (!isFinite(progress)) return;
            float raw = clampRaw(baseRaw + impact.travelAt(progress));
            if (direction == DIRECTION_TOP) mTopRawPx = raw;
            else mBottomRawPx = raw;
            apply();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mImpact = null;
                onEdgeRelease();
            }
        });
        mImpact = animator;
        animator.start();
    }

    /**
     * The grid scrolled for real by {@code giveBackPx}: hand the held pull back one pixel of
     * finger travel per pixel of scroll, so a pull-then-return gesture stays continuous. Payback
     * goes to the raw accumulator, never the damped value — the pull curve is concave, so
     * subtracting from the displacement would pay back faster than the finger bought it.
     */
    private void bleedIntoScroll(float giveBackPx) {
        if (mTranslationPx == 0f) return;
        cancelAnimators();
        // Only the side that currently holds the displacement can be giving anything back.
        if (mTranslationPx > 0f) mTopRawPx = clampRaw(mTopRawPx - giveBackPx);
        else mBottomRawPx = clampRaw(mBottomRawPx - giveBackPx);
        // The damped value follows from the raw, so it is re-derived rather than decremented.
        apply();
    }

    // ── displacement ───────────────────────────────────────────────────────────────────────

    private void apply() {
        // One height read for the whole frame: damp() would otherwise read extentPx() twice and
        // setTranslation() a third time.
        final float h = extentPx();
        setTranslation(damp(mTopRawPx, h) - damp(mBottomRawPx, h), h);
    }

    /** {@link #setTranslation(float, float)} with the height read fresh. */
    private void setTranslation(float px) {
        setTranslation(px, extentPx());
    }

    /**
     * The single writer of the content's {@code translationY}: must always be a finite, bounded
     * number — anything else is coerced to 0 rather than allowed to poison the view's transform.
     */
    private void setTranslation(float px, float heightPx) {
        px = PagerOverscrollController.coerceTranslation(px, heightPx);
        // An unchanged displacement must not dirty the RenderNode. Every settle/reset ends here,
        // and most of them are writing a 0 that is already there.
        if (px == mTranslationPx) return;
        mTranslationPx = px;
        View content = getChildAt(0);
        if (content != null) content.setTranslationY(px);
    }

    /**
     * The grid's extent along the drag axis — the viewport height; every scale of the effect is a
     * fraction of it. Read on demand, not cached, so rotation / split-screen / density changes
     * reflow the physics instead of keeping a stale pixel value.
     */
    private float extentPx() {
        return Math.max(1f, getHeight());
    }

    private float maxPullPx() {
        return ElasticOverdrag.maxPull(extentPx());
    }

    /** The display density, read on demand so a move to another display is picked up. */
    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    /** {@link #damp(float)} against an extent the caller already has — see {@link #apply()}. */
    private float damp(float rawPx, float heightPx) {
        return PagerOverscrollController.dampStatic(rawPx, heightPx);
    }

    /** Inverse of {@link #damp(float)}: the raw travel that produces {@code dampedPx}. */
    private float inverseDamp(float dampedPx) {
        return PagerOverscrollController.inverseDampStatic(dampedPx, extentPx());
    }

    /** Keep the raw accumulator finite and bounded: positive, never NaN/Infinity, never > cap. */
    private float clampRaw(float rawPx) {
        return PagerOverscrollController.clampRawStatic(rawPx, extentPx());
    }

    private static boolean isFinite(float v) {
        return PagerOverscrollController.isFinite(v);
    }

    // ── the "never displaced while idle" invariant ─────────────────────────────────────────

    /**
     * Guarantee the grid is never left displaced once the gesture is over.
     *
     * <p>{@link #bleedIntoScroll} strips animation listeners before cancelling (so the content
     * does not snap mid-gesture), which also removes the only guaranteed writer of a clean 0; a
     * fling can keep the bleed running long after the finger is gone. So after every fling frame
     * that gives the pull back we post a check for leftover displacement, re-posting while frames
     * keep arriving ({@link #mFlingFrameSeen}). The check <em>animates</em> what it finds rather
     * than zeroing it — a clean 0 would be a visible snap of up to the full cap.
     */
    private void ensureSettled() {
        removeCallbacks(mSettleRunnable);
        postOnAnimation(mSettleRunnable);
    }

    private void settleNow() {
        final boolean flingStillRunning = mFlingFrameSeen;
        mFlingFrameSeen = false;
        if (mSpring != null || mImpact != null) return;
        if (flingStillRunning) {
            // A fling frame arrived since the last check, so the scroll is still in flight and
            // may still be holding the displacement. Look again next frame instead of deciding.
            ensureSettled();
            return;
        }
        if (mTranslationPx != 0f) {
            // Displaced with no animator: the return was cancelled mid-flight. Hand the leftover
            // to the spring instead of zeroing it — an instant reset would snap up to the cap.
            mTopRawPx = 0f;
            mBottomRawPx = 0f;
            startSpring(mTranslationPx);
            return;
        }
        // A raw accumulator on its own can never be on screen (a non-zero raw always damps to a
        // non-zero displacement), so there is nothing to animate — just drop it.
        if (mTopRawPx != 0f || mBottomRawPx != 0f) reset();
    }

    // ── spring back ────────────────────────────────────────────────────────────────────────

    private void startSpring(float from) {
        cancelAnimators();
        if (!isFinite(from) || from == 0f) {
            // Nothing sane to animate from: drop the displacement instead of animating NaN.
            mTopRawPx = 0f;
            mBottomRawPx = 0f;
            setTranslation(0f);
            return;
        }
        float max = maxPullPx();
        from = Math.max(-max, Math.min(max, from));
        ValueAnimator animator = ValueAnimator.ofFloat(from, 0f);
        animator.setDuration(ElasticOverdrag.SPRING_DURATION_MS);
        animator.setInterpolator(ElasticOverdrag.SPRING);
        animator.addUpdateListener(animation ->
                setTranslation((Float) animation.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mSpring = null;
                mTopRawPx = 0f;
                mBottomRawPx = 0f;
                setTranslation(0f);
            }
        });
        mSpring = animator;
        animator.start();
    }

    /** Drop any held displacement right now, without animating. */
    private void reset() {
        cancelAnimators();
        removeCallbacks(mSettleRunnable);
        removeCallbacks(mFlingDisarm);
        mFlingFrameSeen = false;
        mTopRawPx = 0f;
        mBottomRawPx = 0f;
        setTranslation(0f);
    }

    /** Stop both the fly-out and the return, leaving the displacement exactly where it is. */
    private void cancelAnimators() {
        cancelImpact();
        cancelSpring();
    }

    private void cancelImpact() {
        ValueAnimator impact = mImpact;
        mImpact = null;
        if (impact == null) return;
        // Same reasoning as the spring: cancel() would deliver onAnimationEnd, which releases
        // into the spring and would fight whatever is taking over.
        impact.removeAllUpdateListeners();
        impact.removeAllListeners();
        impact.cancel();
    }

    private void cancelSpring() {
        ValueAnimator spring = mSpring;
        mSpring = null;
        if (spring == null) return;
        // Unhook before cancelling: cancel() fires onAnimationEnd too, which would snap the
        // content to the boundary mid-gesture.
        spring.removeAllUpdateListeners();
        spring.removeAllListeners();
        spring.cancel();
        // The spring drove translationY directly, so the accumulators are stale — re-seed them
        // so a pull that interrupts the bounce continues from the position on screen.
        if (mTopRawPx == 0f && mBottomRawPx == 0f && mTranslationPx != 0f) {
            if (mTranslationPx > 0f) mTopRawPx = inverseDamp(mTranslationPx);
            else mBottomRawPx = inverseDamp(-mTranslationPx);
        }
    }
}
