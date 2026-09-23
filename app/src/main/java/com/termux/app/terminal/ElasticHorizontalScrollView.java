package com.termux.app.terminal;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;

import androidx.annotation.Nullable;

import com.termux.view.ElasticOverdrag;

/**
 * The session tab strip, with the same elastic ("rubber band") over-drag the terminal pager has.
 *
 * <p>While the tabs fit inside the window there is no end to pull away from and the strip is
 * deliberately inert (see {@link #overScrollBy}). Mirrors {@link PagerOverscrollController} (a
 * {@code ViewPager2} cannot be subclassed into one) — same {@link ElasticOverdrag} model, same
 * accumulators/bleed/spring/fly-out, so a pull feels identical on both surfaces; read that
 * class's doc for the shared reasoning. Only the genuinely different parts are documented here.
 *
 * <h2>1. Unconsumed finger travel</h2>
 * The pager reconstructs it from a spy {@code EdgeEffect}; a {@link HorizontalScrollView} has no
 * such hook, but {@code overScrollBy} hands over the delta, position and range directly, so the
 * unconsumed slice is plain arithmetic — no touch or velocity tracking needed.
 *
 * <h2>2. What gets displaced</h2>
 * The strip translates its content child, not the {@link HorizontalScrollView} itself. That is
 * load-bearing: a {@link android.ViewGroup} undoes a child's translation when dispatching touch,
 * so translating the scrolling container would shift every {@code MotionEvent} and corrupt its
 * gesture maths. Translating the content leaves {@code getScrollX()}, child-index arithmetic and
 * {@code PagerSnapHelper} reading the real layout while looking identical.
 *
 * <h2>3. When a fling has "run into the end"</h2>
 * A clamped {@code OverScroller} fling stops <b>precisely on</b> the end with no leftover delta,
 * so watching for unconsumed travel sees nothing. The rule is positional: <b>a fling frame that
 * leaves the scroll position pinned to either end of the range is an impact</b> — covering
 * landing on, clamping onto, and starting on the end.
 *
 * <h2>4. No diagnostics in this class</h2>
 * This ships from {@code src/main}: no counters, dumps or test toggles. The release build is
 * {@code -dontoptimize -dontobfuscate}, so R8 strips an uncalled method but keeps a field that
 * is only ever incremented. Observation now lives in {@code TermuxDebugCommandReceiver}
 * ({@code tabs} / {@code tabs watch}) via public {@link android.view.View} API.
 */
public class ElasticHorizontalScrollView extends HorizontalScrollView {

    /** The first (left) end of the list: the content is pulled to the RIGHT, so translationX > 0. */
    private static final int DIRECTION_LEFT = -1;

    /** The last (right) end of the list: the content is pulled to the LEFT, so translationX < 0. */
    private static final int DIRECTION_RIGHT = 1;

    /**
     * How long the scroller may be silent before later frames are no longer assumed to belong to
     * this view's fling — a live fling delivers a frame every animation frame; see
     * {@link #mFlingArmed}.
     */
    private static final long FLING_IDLE_MS = 96L;

    /** Accumulated (undamped) finger travel past the left / right end, in px. Never negative. */
    private float mLeftRawPx;
    private float mRightRawPx;

    /** The damped displacement currently applied as the content's {@code translationX}, in px. */
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
     * {@link #fling(int)}, disarmed by {@link #mFlingDisarm} after {@link #FLING_IDLE_MS} of
     * silence. Needed because {@code HorizontalScrollView} also starts the same private
     * {@code OverScroller} from {@code smoothScrollBy}/{@code smoothScrollTo} (both final), whose
     * frames are indistinguishable from a fling's — a programmatic scroll finishing on an end
     * must not read as an impact. Nothing in {@code TermuxSessionTabsController} uses those, but
     * the guard keeps that true by construction rather than by luck.
     */
    private boolean mFlingArmed;

    /** Timestamp of the previous fling frame, for {@link #onFlingFrame}'s speed measurement. */
    private long mLastFlingFrameNanos;

    /**
     * The speed the fling is carrying, in px/s — refreshed by every frame the list absorbed and
     * left alone by the frames it did not, so by the time a frame leaves the strip on an end it
     * still holds the last speed the fling genuinely had. See {@link #onFlingFrame}.
     */
    private float mFlingArrivalPxPerSec;

    /**
     * The velocity the fling was launched with, as handed to {@link #fling(int)} (signed, px/s).
     * The fallback for {@link #mFlingArrivalPxPerSec}: a fling that reaches the wall with no
     * in-range frame in front of it had nothing in between to slow it down, so it arrived at
     * exactly the speed it left with.
     */
    private float mFlingLaunchVelocityPx;

    /** Deferred {@link #settleNow()} — see {@link #ensureSettled()} for why it is posted. */
    private final Runnable mSettleRunnable = this::settleNow;

    /** {@link #mFlingArmed}'s expiry — see {@link #armFlingLifetime()}. */
    private final Runnable mFlingDisarm = () -> mFlingArmed = false;

    public ElasticHorizontalScrollView(Context context) {
        this(context, null);
    }

    public ElasticHorizontalScrollView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ElasticHorizontalScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public ElasticHorizontalScrollView(Context context, AttributeSet attrs, int defStyleAttr,
                                       int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        // OVER_SCROLL_NEVER for the same reason the pager's controller sets it: the platform's
        // edge glow / stretch would otherwise stack on top of the elastic displacement rather
        // than being replaced by it. It also pins View#overScrollBy's clamp to exactly
        // [0, scrollRange] (maxOverScrollX is zeroed in this mode), which is what makes the
        // unconsumed-delta arithmetic in overScrollBy() exact.
        setOverScrollMode(View.OVER_SCROLL_NEVER);
    }

    // ── gesture input ──────────────────────────────────────────────────────────────────────

    /**
     * The single hook into the strip's own scrolling (class doc §1). Called with the delta the
     * widget is <em>about</em> to apply, before it is clamped — the only moment the unconsumed
     * part is still visible. {@code maxOverScrollX} is deliberately ignored: with
     * {@link #OVER_SCROLL_NEVER} the framework forces it to 0, so honouring a non-zero margin
     * here would let the framework scroll past the end and count it as finger travel as well.
     *
     * <p><b>A strip with nothing to scroll has no end to pull away from.</b> {@code scrollRangeX}
     * is 0 while the tabs fit inside the window (the ordinary case for one or two of them); every
     * drag frame would then read as a full-width pull out of a gesture with nowhere to go, so the
     * {@code scrollRangeX > 0} gate keeps the whole elastic layer switched off. Runtime rather
     * than one-off at attach time because the range moves as tabs come and go; a pull already
     * held at such a moment is simply no longer fed, and the release still springs back to 0.
     */
    @Override
    protected boolean overScrollBy(int deltaX, int deltaY, int scrollX, int scrollY,
                                   int scrollRangeX, int scrollRangeY,
                                   int maxOverScrollX, int maxOverScrollY, boolean isTouchEvent) {
        if (deltaX != 0 && scrollRangeX > 0) {
            final int wanted = scrollX + deltaX;
            final int clamped = Math.max(0, Math.min(scrollRangeX, wanted));
            // Where the list will actually stop. The difference is finger travel it had nowhere
            // to put — the over-pull — and the rest is travel it absorbed, i.e. real scroll.
            final int unconsumedPx = Math.abs(wanted - clamped);
            final int consumedPx = Math.abs(deltaX) - unconsumedPx;
            // Direction from the delta (direction of travel), not from where the frame ends up:
            // a clamped fling finishes with `wanted` exactly on an end (class doc §3), and a
            // sign test on `wanted` would read a fling into the left end as a right-end pull.
            final int direction = deltaX < 0 ? DIRECTION_LEFT : DIRECTION_RIGHT;
            if (isTouchEvent) {
                if (unconsumedPx > 0) onDragDelta(direction, consumedPx, unconsumedPx);
                else bleedIntoScroll(consumedPx);
            } else {
                onFlingFrame(direction, Math.abs(deltaX), consumedPx, clamped, scrollRangeX);
            }
        }
        return super.overScrollBy(deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY,
                maxOverScrollX, maxOverScrollY, isTouchEvent);
    }

    /**
     * One drag frame that ran past an end.
     *
     * <p>A frame can do both at once — the last of the scroll and the first of the pull — when the
     * finger crosses the boundary mid-frame. The scroll half is handed back first, so the pull the
     * frame leaves behind is measured from the boundary rather than from the frame's start; doing
     * it the other way round would leave the pull holding travel the scroll had already spent.
     */
    private void onDragDelta(int direction, int consumedPx, int unconsumedPx) {
        if (consumedPx > 0) bleedIntoScroll(consumedPx);
        if (unconsumedPx > 0) onEdgePull(direction, unconsumedPx);
    }

    /**
     * One fling frame: either still travelling (bleed + record speed) or left pinned to an end —
     * the impulse {@link #onEdgeAbsorb} wants. See class doc §3 for why "at an end" is the test
     * rather than "had a leftover".
     *
     * <p><b>Residual velocity.</b> A {@code HorizontalScrollView} keeps its {@code OverScroller}
     * private (the pager reads {@code getCurrVelocity()} / {@code absorbGlows} directly), so the
     * speed is measured: refreshed from {@code |deltaX| / dt} only on frames the list absorbed
     * (for a clamped fling that last in-range frame <em>is</em> the arrival speed), falling back
     * to {@link #mFlingLaunchVelocityPx} when a flick started on an end and never had an
     * in-range frame. Measuring the pinned frame's own {@code deltaX} would read the scroller's
     * leftover as a frame step and saturate the band on every bounce.
     *
     * <p><b>Must not fire on a release already holding a pull.</b> The widget flings on
     * <em>every</em> release above {@code mMinimumVelocity}, including the one ending a drag past
     * an end, which is already answered by {@link #onEdgeRelease}'s spring — stacking the impulse
     * would kick the strip twice for one gesture. ({@code TerminalView} swallows that fling
     * outright; here only the impulse is dropped.) A flick that merely <em>ends</em> at an end
     * without holding a pull still bounces — that is the gesture this class exists for.
     *
     * @param direction       direction of travel ({@link #DIRECTION_LEFT}/{@link #DIRECTION_RIGHT}).
     * @param requestedPx     the frame's step, always positive.
     * @param consumedPx      how much of it the list absorbed as real scroll.
     * @param clampedPosition where the frame left the scroll position (after the clamp).
     * @param scrollRangeX    the range to test {@code clampedPosition} against — reading the view
     *                        here would give the <em>old</em> position, not the one this frame
     *                        is about to write.
     */
    private void onFlingFrame(int direction, int requestedPx, int consumedPx,
                              int clampedPosition, int scrollRangeX) {
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
        // was clamped back onto one (class doc §3). Range 0 never arrives here: overScrollBy()
        // gates the elastic layer on scrollRangeX > 0.
        if (clampedPosition != 0 && clampedPosition != scrollRangeX) return;

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
     * sees the whole gesture: a tap on a child never reaches {@code onTouchEvent}, so a release
     * hooked there would leave the previous pull hanging. Runs <em>after</em> {@code super}, so
     * the fling launched on ACTION_UP is registered before the spring is armed and the held pull
     * is recorded before {@link #onEdgeRelease} consumes it. The first scrolling fling frame
     * cancels the spring via {@link #bleedIntoScroll}, so pull-then-flick is one motion; a fling
     * with nowhere to go produces no scroll frames and the spring runs alone.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        final boolean handled = super.dispatchTouchEvent(ev);
        final int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mDragPullAbsorbed = false;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_CANCEL) {
                reset();
            } else {
                // Read before the release zeroes the accumulators: this release is already being
                // answered by the pull's own spring, so the fling it launched must not add an
                // impulse on top (see onFlingFrame).
                mDragPullAbsorbed = mTranslationPx != 0f;
                onEdgeRelease();
            }
        }
        return handled;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Records the start of a fling: its launch velocity (the fallback the impulse uses when the
     * fling reaches an end with no in-range frame in front of it) and the state that makes the
     * bounce happen at most once per fling. Also arms {@link #mFlingArmed} — see there for what
     * disarms it.
     */
    @Override
    public void fling(int velocityX) {
        mFlingLaunchVelocityPx = velocityX;
        mFlingArrivalPxPerSec = 0f;
        mFlingImpactSpent = false;
        mFlingFrameSeen = false;
        mFlingArmed = true;
        mLastFlingFrameNanos = 0L;
        armFlingLifetime();
        super.fling(velocityX);
    }

    /**
     * Keep {@link #mFlingArmed} true while a scroller produces frames and let it lapse
     * {@link #FLING_IDLE_MS} after the last one. Called on launch and every frame, so the timer
     * only fires for a scroller that has actually stopped.
     */
    private void armFlingLifetime() {
        removeCallbacks(mFlingDisarm);
        postOnAnimationDelayed(mFlingDisarm, FLING_IDLE_MS);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A programmatic scroll repositions the strip for a reason unrelated to the finger
     * ({@code TermuxSessionTabsController} drives {@code scrollTo} from its own animations), so a
     * held pull is released into its spring rather than left hanging. Gated on the position
     * actually moving, because {@code HorizontalScrollView#onLayout} ends by calling this with
     * the current values — a no-op that must not be read as a reposition, or every layout pass
     * during a pull would spring the strip back under the finger. The widget's own scrolling does
     * not come through here, so this cannot fire mid-drag.
     */
    @Override
    public void scrollTo(int x, int y) {
        final int before = getScrollX();
        super.scrollTo(x, y);
        if (getScrollX() != before && mTranslationPx != 0f) {
            mLeftRawPx = 0f;
            mRightRawPx = 0f;
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
     * pull; {@code direction} is {@link #DIRECTION_LEFT} (content moves right) or
     * {@link #DIRECTION_RIGHT} (content moves left).
     */
    private void onEdgePull(int direction, float deltaPx) {
        if (!(deltaPx > 0f) || !isFinite(deltaPx)) return;
        cancelAnimators();
        // A new pull first pays off whatever the opposite end still holds, so "at most one end
        // is ever displaced" holds by construction (the bleed below would get there anyway).
        if (direction == DIRECTION_LEFT) {
            float returned = Math.min(mRightRawPx, deltaPx);
            mRightRawPx = clampRaw(mRightRawPx - returned);
            mLeftRawPx = clampRaw(mLeftRawPx + deltaPx - returned);
        } else {
            float returned = Math.min(mLeftRawPx, deltaPx);
            mLeftRawPx = clampRaw(mLeftRawPx - returned);
            mRightRawPx = clampRaw(mRightRawPx + deltaPx - returned);
        }
        apply();
    }

    /** Finger lifted (or the gesture was cancelled): spring back to the end. */
    private void onEdgeRelease() {
        if (mTranslationPx == 0f) {
            mLeftRawPx = 0f;
            mRightRawPx = 0f;
            return;
        }
        mLeftRawPx = 0f;
        mRightRawPx = 0f;
        startSpring(mTranslationPx);
    }

    /**
     * A fling ran into the end: convert a slice of its arrival speed into extra pull on the end
     * that was actually hit, then spring back — so a hard flick slams the strip against its limit
     * instead of being swallowed. Same {@link ElasticOverdrag#impact} call as the pager, so both
     * surfaces threshold and cap a given gesture identically on any density. {@code direction}
     * must be passed in (the displacement is 0 here, so its sign says nothing about the impact);
     * {@code velocityPxPerSec} is always positive.
     */
    private void onEdgeAbsorb(int direction, float velocityPxPerSec) {
        if (!(velocityPxPerSec > 0f) || !isFinite(velocityPxPerSec)) {
            onEdgeRelease();
            return;
        }
        cancelAnimators();
        final ElasticOverdrag.Impact impact =
                ElasticOverdrag.impact(velocityPxPerSec, extentPx(), 0f, density());
        final float base = (direction == DIRECTION_LEFT) ? mLeftRawPx : mRightRawPx;
        startImpact(direction, base, impact);
    }

    /**
     * Play the fly-out half of an impact: the band being stretched by the impulse. Samples are
     * raw travel integrated in displacement, so the content follows the band being loaded and
     * starts at the impulse's own speed (a harder flick flies out faster as well as further).
     * The last sample hands the stretch to {@link #onEdgeRelease()}.
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
            if (direction == DIRECTION_LEFT) mLeftRawPx = raw;
            else mRightRawPx = raw;
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
     * The strip scrolled for real by {@code giveBackPx}: hand the held pull back one pixel of
     * finger travel per pixel of scroll, so a pull-then-return gesture stays continuous. Payback
     * goes to the raw accumulator, never the damped value — the pull curve is concave, so
     * subtracting from the displacement would pay back at ~2.4x the rate the finger bought it at
     * and a single large scroll frame could zero the whole pull in one frame. See
     * {@code PagerOverscrollController#bleedIntoScroll} for the arithmetic.
     */
    private void bleedIntoScroll(float giveBackPx) {
        if (mTranslationPx == 0f) return;
        cancelAnimators();
        // Only the side that currently holds the displacement can be giving anything back: the
        // scroll moved the content away from that end and toward the other, and the accumulator
        // on the other end is necessarily 0 (a pull on one end pays the other off, and only the
        // held end is ever bled).
        if (mTranslationPx > 0f) mLeftRawPx = clampRaw(mLeftRawPx - giveBackPx);
        else mRightRawPx = clampRaw(mRightRawPx - giveBackPx);
        // The damped value follows from the raw, so it is re-derived rather than decremented: the
        // raw accumulator stays the single source of truth, and the displacement can never be
        // driven past the cap.
        apply();
    }

    // ── displacement ───────────────────────────────────────────────────────────────────────

    private void apply() {
        // One width read for the whole frame: damp() would otherwise call extentPx() twice and
        // setTranslation() a third time.
        final float w = extentPx();
        setTranslation(damp(mLeftRawPx, w) - damp(mRightRawPx, w), w);
    }

    /** {@link #setTranslation(float, float)} with the width read fresh. */
    private void setTranslation(float px) {
        setTranslation(px, extentPx());
    }

    /**
     * The single writer of the content's {@code translationX}: must always be a finite, bounded
     * number — anything else is coerced to 0 rather than allowed to poison the view's transform
     * (an invalid RenderNode transform stops the whole subtree being drawn, and hit-testing
     * inverts a NaN matrix, turning every touch coordinate into NaN).
     */
    private void setTranslation(float px, float widthPx) {
        px = PagerOverscrollController.coerceTranslation(px, widthPx);
        // An unchanged displacement must not dirty the RenderNode. Every settle/reset ends here,
        // and most of them are writing a 0 that is already there.
        if (px == mTranslationPx) return;
        mTranslationPx = px;
        View content = getChildAt(0);
        if (content != null) content.setTranslationX(px);
    }

    /**
     * The strip's extent along the drag axis — the viewport width; every scale of the effect is
     * a fraction of it. Read on demand, not cached, so rotation / split-screen / tab-height
     * changes reflow the physics. The strip has no quantum to snap to (unlike the transcript's
     * glyph rows), so plain {@link ElasticOverdrag} entry points are used.
     */
    private float extentPx() {
        return Math.max(1f, getWidth());
    }

    private float maxPullPx() {
        return ElasticOverdrag.maxPull(extentPx());
    }

    /**
     * The display density, read on demand so a move to another display is picked up. Only the
     * impulse needs it — the band itself is scale-free.
     */
    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    /** {@link #damp(float)} against an extent the caller already has — see {@link #apply()}. */
    private float damp(float rawPx, float widthPx) {
        return PagerOverscrollController.dampStatic(rawPx, widthPx);
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
     * Guarantee the strip is never left displaced once the gesture is over.
     *
     * <p>{@link #bleedIntoScroll} strips animation listeners before cancelling (so the content
     * does not snap mid-gesture), which also removes the only guaranteed writer of a clean 0;
     * a fling can keep the bleed running long after the finger is gone. So after every fling
     * frame that gives the pull back we post a check for leftover displacement, re-posting while
     * frames keep arriving ({@link #mFlingFrameSeen}). The check <em>animates</em> what it finds
     * rather than zeroing it — a clean 0 would be a visible snap of up to the full cap. Worst
     * case stays a missing animation, never a broken strip.
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
            // may still be holding the displacement. Look again next frame instead of deciding now.
            ensureSettled();
            return;
        }
        if (mTranslationPx != 0f) {
            // Displaced with no animator: the return was cancelled mid-flight. Hand the leftover
            // to the spring instead of zeroing it — an instant reset here is a visible snap of up
            // to the full cap. The invariant is still absolute (startSpring() coerces anything
            // non-finite to 0, clamps to the cap, and its onAnimationEnd writes a clean 0), it
            // just gets there by animating.
            mLeftRawPx = 0f;
            mRightRawPx = 0f;
            startSpring(mTranslationPx);
            return;
        }
        // A raw accumulator on its own can never be on screen (a non-zero raw always damps to a
        // non-zero displacement), so there is nothing to animate — just drop it.
        if (mLeftRawPx != 0f || mRightRawPx != 0f) reset();
    }

    // ── spring back ────────────────────────────────────────────────────────────────────────

    private void startSpring(float from) {
        cancelAnimators();
        if (!isFinite(from) || from == 0f) {
            // Nothing sane to animate from: drop the displacement instead of animating NaN.
            mLeftRawPx = 0f;
            mRightRawPx = 0f;
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
                mLeftRawPx = 0f;
                mRightRawPx = 0f;
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
        mLeftRawPx = 0f;
        mRightRawPx = 0f;
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
        // The spring drove translationX directly, so the accumulators are stale — re-seed them so
        // a pull that interrupts the bounce continues from the position on screen.
        if (mLeftRawPx == 0f && mRightRawPx == 0f && mTranslationPx != 0f) {
            if (mTranslationPx > 0f) mLeftRawPx = inverseDamp(mTranslationPx);
            else mRightRawPx = inverseDamp(-mTranslationPx);
        }
    }
}
