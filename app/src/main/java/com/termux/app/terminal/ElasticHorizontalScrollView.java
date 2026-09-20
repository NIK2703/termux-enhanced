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
 * <p>{@link PagerOverscrollController} is a separate controller class because a
 * {@code ViewPager2} cannot be subclassed into one; the tab strip is a plain
 * {@link HorizontalScrollView}, so here the same physics is installed by subclassing. Everything
 * below the input layer is a deliberate mirror of that class — the two raw accumulators, the
 * bleed, the spring, the fly-out and the "never displaced while idle" invariant are the same
 * arithmetic against the same {@link ElasticOverdrag} model, so a pull feels identical on both
 * surfaces. Read {@code PagerOverscrollController}'s class doc for the reasoning behind each
 * piece; only the two parts that are genuinely different are documented at length here.
 *
 * <h2>1. Where the unconsumed finger travel comes from</h2>
 * The pager has to reconstruct it, because a {@code RecyclerView} can never scroll past its own
 * bounds: the class installs a spy {@link android.widget.EdgeEffect} factory and reads the
 * <em>unconsumed</em> scroll delta out of {@code pullGlows()}. A {@link HorizontalScrollView} has
 * no such plumbing to borrow — its edge effects are private and it has no factory hook — but it
 * does not need one either, because it hands the quantity over directly:
 *
 * <pre>
 *   // HorizontalScrollView#onTouchEvent, ACTION_MOVE
 *   overScrollBy(deltaX, 0, mScrollX, 0, range, 0, mOverscrollDistance, 0, true);
 * </pre>
 *
 * <p>{@code overScrollBy} is <em>not</em> declared by {@code HorizontalScrollView} — it is
 * {@code View}'s, and only ever <em>called</em> by the widget — so overriding it puts this class
 * directly in the path of every drag frame with the three numbers that matter: the delta about to
 * be applied, the position it is about to be applied at, and the scrollable range. The slice the
 * list cannot absorb is then plain arithmetic ({@code wanted - clamped}), and the slice it can is
 * the same quantity the pager bleeds its held pull back by. No touch tracking, no touch-slop
 * replication, no velocity tracker: the widget has already done all of that before it calls here.
 *
 * <p>The same hook covers the fling: {@code HorizontalScrollView#computeScroll} calls
 * {@code overScrollBy(..., false)} once per frame with the fling's step, which is the moment
 * {@code RecyclerView#absorbGlows} hands the pager its leftover velocity. What counts as "the
 * fling ran into the end" is <em>not</em> the same on the two surfaces, though, and the
 * difference is the whole of §3 below.
 *
 * <h2>2. What gets displaced</h2>
 * The pager translates the view that hosts every page. The tab strip translates the opposite
 * thing — its content child ({@code session_tabs}), not the {@link HorizontalScrollView} itself —
 * and that difference is load-bearing rather than cosmetic. A {@link android.view.ViewGroup}
 * transforms the touch events it hands a translated child by that child's inverse matrix
 * ({@code dispatchTransformedTouchEvent}), so a scrolling container that is itself being
 * translated sees every {@code MotionEvent} shifted by the displacement it is accumulating, and
 * its own {@code deltaX} picks up a {@code Δtranslation} term per frame. Translating the content
 * instead leaves the scrolling container's geometry — and therefore its gesture maths — exactly
 * where it was, and the visual result is the same: the strip slides and the strip it vacates
 * shows the background behind it.
 *
 * <p>Nothing in {@code TermuxSessionTabsController} sees the displacement, and that is
 * deliberate: {@code translationX} is a draw-time transform, so {@code getScrollX()},
 * {@code getLeft()} and the child index arithmetic all keep reading the real layout, exactly as
 * {@code PagerSnapHelper} and {@code onPageScrolled} keep reading the pager's.
 *
 * <h2>3. When a fling has "run into the end"</h2>
 * The pager is told: a {@code RecyclerView} cannot scroll past its bounds, so its edge effect
 * receives the leftover step and {@code absorbGlows} reports it. A {@link HorizontalScrollView}
 * is told nothing of the sort, because its {@code OverScroller} is built to <em>not</em> overshoot
 * in the ordinary case:
 *
 * <pre>
 *   // OverScroller.SplineOverScroller#fling
 *   mSplineDistance = (int) (totalDistance * Math.signum(velocity));
 *   mFinal = start + mSplineDistance;
 *   if (mFinal &gt; max) { adjustDuration(mStart, mFinal, max); mFinal = max; }
 * </pre>
 *
 * <p>{@code adjustDuration} shortens the animation to exactly the time the spline needs to cover
 * the <em>clamped</em> distance, so a flick whose natural reach is several screens long stops
 * <b>precisely on the end</b>, with {@code mCurrVelocity} left at whatever speed it had there.
 * That last frame is the impact — but it is a frame the list <em>absorbed</em> completely
 * ({@code wanted == clamped}, i.e. no leftover), so a rule that watches for an unconsumed delta
 * sees nothing at all. The leftover only appears afterwards, if the scroller takes its
 * {@code BALLISTIC} continuation past the end — and whether it does depends on the fling's
 * speed against the overfling allowance, which is not something to build a gesture on.
 *
 * <p>So the rule here is stated in terms of where the strip <em>is</em> rather than what was left
 * over: <b>a fling frame that leaves the scroll position pinned to either end of the range is an
 * impact.</b> That covers all three ways a fling can meet a wall — landing exactly on it, being
 * clamped back onto it, and starting on it — with one condition, and it cannot fire for a fling
 * that still has somewhere to go, because such a frame ends strictly between the two ends.
 */
public class ElasticHorizontalScrollView extends HorizontalScrollView {

    /** The first (left) end of the list: the content is pulled to the RIGHT, so translationX > 0. */
    private static final int DIRECTION_LEFT = -1;

    /** The last (right) end of the list: the content is pulled to the LEFT, so translationX < 0. */
    private static final int DIRECTION_RIGHT = 1;

    /**
     * How long the scroller may be silent before the frames that follow are no longer assumed to
     * belong to the fling this view launched. A fling delivers a frame every animation frame, so
     * anything past a couple of frames means it has stopped and whatever runs next was started by
     * somebody else — see {@link #mFlingArmed}.
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

    private boolean mElasticEnabled = true;

    // ── fling bookkeeping ──────────────────────────────────────────────────────────────────
    //
    // One flag per hole in "a fling ran into the end of the list":
    //   mFlingFrameSeen     — a fling frame arrived since the last settle check (still travelling),
    //   mFlingImpactSpent   — the one bounce per fling has already been played,
    //   mDragPullAbsorbed   — the release that launched this fling was already holding a pull, so
    //                         the impulse must not be stacked on top of it,
    //   mFlingArmed         — the frames arriving really are this view's fling (see its own doc).
    private boolean mFlingFrameSeen;
    private boolean mFlingImpactSpent;
    private boolean mDragPullAbsorbed;

    /**
     * True while the scroller frames arriving are a fling this view launched from a gesture.
     *
     * <p>Armed by {@link #fling(int)}, disarmed by {@link #mFlingDisarm} once the scroller has been
     * silent for {@link #FLING_IDLE_MS}. The flag exists because {@code HorizontalScrollView} also
     * starts the same private {@code OverScroller} from {@code smoothScrollBy} /
     * {@code smoothScrollTo} — both {@code final}, so they cannot be intercepted — and those frames
     * are indistinguishable from a fling's; a programmatic scroll that happens to finish on an end
     * must not be read as an impact. (Nothing in {@code TermuxSessionTabsController} uses them: it
     * drives {@code scrollTo} from its own animators, which never reach {@code overScrollBy} at
     * all. The guard is here so that stays true by construction rather than by luck.)</p>
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

    // ── diagnostics ────────────────────────────────────────────────────────────────────────
    // Counters behind dumpOverscrollState(), for the debug command receiver. They are four ints
    // and a handful of increments, which is nothing next to the animators this class already
    // drives; in exchange the fling path stops being a black box on a device you cannot attach a
    // debugger to. (endFrames is the one that matters: it counts the frames the impulse rule
    // actually fired on, so "the bounce did not happen" and "the bounce fired with no velocity"
    // are distinguishable.)
    private int mFlingFrameCount;
    private int mFlingClampedCount;
    private int mFlingEndFrameCount;
    private int mAbsorbCount;

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
        // OVER_SCROLL_NEVER for the same reason the pager's controller sets it: the platform's own
        // over-scroll visual (the edge glow, and the 12+ stretch that HorizontalScrollView gained
        // along with consumeFlingInStretch) would otherwise stack on top of the elastic
        // displacement rather than being replaced by it. It also pins View#overScrollBy's clamp to
        // exactly [0, scrollRange] — it zeroes maxOverScrollX whenever the mode is NEVER — which is
        // what makes the unconsumed-delta arithmetic in overScrollBy() exact instead of approximate.
        setOverScrollMode(View.OVER_SCROLL_NEVER);
    }

    // ── gesture input ──────────────────────────────────────────────────────────────────────

    /**
     * The single hook into the strip's own scrolling — see the class doc §1 for why this is the
     * whole input layer.
     *
     * <p>Called with the delta the widget is <em>about</em> to apply, before it is clamped, which
     * is the only moment the unconsumed part is still visible: afterwards the scroll position has
     * simply stopped and says nothing about how hard it was pushed.
     *
     * <p>{@code maxOverScrollX} is deliberately not used. The mode set in the constructor is
     * {@link #OVER_SCROLL_NEVER}, so {@code View#overScrollBy} forces it to 0 and the boundary is
     * where the list genuinely stops; honouring a non-zero margin here would let the framework
     * scroll past the end on its own and then count that as finger travel as well.
     */
    @Override
    protected boolean overScrollBy(int deltaX, int deltaY, int scrollX, int scrollY,
                                   int scrollRangeX, int scrollRangeY,
                                   int maxOverScrollX, int maxOverScrollY, boolean isTouchEvent) {
        if (deltaX != 0 && mElasticEnabled) {
            final int wanted = scrollX + deltaX;
            final int clamped = Math.max(0, Math.min(scrollRangeX, wanted));
            // Where the list will actually stop. The difference is finger travel it had nowhere to
            // put — the over-pull — and the rest is travel it absorbed, i.e. real scroll.
            final int unconsumedPx = Math.abs(wanted - clamped);
            final int consumedPx = Math.abs(deltaX) - unconsumedPx;
            // The direction is taken from the delta, i.e. from the direction of travel, and not
            // from where the frame ends up. A frame can end *exactly* on an end — which is how a
            // clamped fling finishes (see the class doc §3) — and then `wanted` is 0 or
            // scrollRangeX, neither of which is outside the range; a sign test on `wanted` would
            // read a fling into the left end as a pull on the right one.
            final int direction = deltaX < 0 ? DIRECTION_LEFT : DIRECTION_RIGHT;
            if (isTouchEvent) {
                if (unconsumedPx > 0) onDragDelta(direction, consumedPx, unconsumedPx);
                else bleedIntoScroll(consumedPx);
            } else {
                onFlingFrame(direction, Math.abs(deltaX), consumedPx, unconsumedPx,
                        clamped, scrollRangeX);
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
     * One fling frame. Either the fling is still travelling — in which case the frame only bleeds
     * and the frame's speed is recorded — or it has left the strip pinned to an end, which is the
     * impulse {@link #onEdgeAbsorb} wants.
     *
     * <p><b>What counts as the impact.</b> Not "the frame had a leftover" — see the class doc §3
     * for why a clamped fling produces no leftover at all. It is "the strip is at an end": either
     * the frame landed exactly on one, or it was clamped back onto one. One condition, three ways
     * to arrive (land on it, be clamped onto it, start on it), and it cannot fire while the fling
     * still has room to travel.
     *
     * <p><b>Where the residual velocity comes from.</b> {@code TerminalView} reads it straight off
     * its own {@code OverScroller} ({@code mScroller.getCurrVelocity()}), and
     * {@code RecyclerView#absorbGlows} hands the pager the same quantity. A
     * {@code HorizontalScrollView} keeps its {@code OverScroller} private and gives the leftover
     * velocity to the edge glow alone — and the glow is switched off here — so it has to be
     * measured instead. Two sources, in this order:
     * <ol>
     *   <li><b>The frames the list absorbed.</b> For those, {@code |deltaX| / dt} is exactly the
     *       speed the content is moving at, because {@code deltaX} really is one frame's travel
     *       and {@code dt} really is that frame's duration. The value is refreshed only there, so
     *       by the time a frame arrives at an end it still holds the last speed the fling genuinely
     *       had. For a clamped fling that is the arrival speed itself, because the frame that
     *       reaches the end <em>is</em> a frame the list absorbed: {@code adjustDuration} lands the
     *       spline on the end exactly, so the last in-range step is the one that gets there.</li>
     *   <li><b>The velocity the fling was launched with</b> ({@link #mFlingLaunchVelocityPx}), when
     *       there was no in-range frame at all. That is a flick started <em>on</em> an end: the
     *       strip has nowhere to go, so the very first frame is already pinned, and the launch
     *       velocity <em>is</em> the arrival velocity — nothing in between could have slowed it
     *       down. (Measuring that frame's own {@code deltaX} instead would read the scroller's
     *       leftover as a frame step and saturate the band on every bounce.)</li>
     * </ol>
     *
     * <p><b>Why this must not fire on a release that was already holding a pull.</b> The widget
     * launches a fling on <em>every</em> release above {@code mMinimumVelocity}, including the one
     * that ends a drag past an end — and that release is already being answered by the spring
     * {@link #onEdgeRelease} armed. Letting the impulse fire as well would kick the strip a second
     * time for one gesture. {@code TerminalView} closes the same hole by swallowing the fling
     * outright ({@code mOverdragEngaged}); here the fling is left to run (it has nowhere to go and
     * produces no scroll) and only the impulse is dropped, which is the same outcome with less
     * interference. Note what is <em>not</em> suppressed: a flick that merely <em>ends</em> at an
     * end — dragged up to the wall and released while still moving, so that no pull is being held —
     * is ordinary scrolling, and it bounces. That is the gesture this class exists for.
     *
     * @param direction        the direction of travel ({@link #DIRECTION_LEFT} / {@link
     *                         #DIRECTION_RIGHT}), which is also the end the fling is pushing
     *                         against.
     * @param requestedPx      the frame's step, always positive.
     * @param consumedPx       how much of it the list absorbed as real scroll.
     * @param unconsumedPx     how much of it the list had nowhere to put.
     * @param clampedPosition  where the frame left the scroll position, i.e. after the clamp.
     * @param scrollRangeX     the scrollable range, so {@code clampedPosition} can be tested
     *                         against the ends without reading the view (which would be the
     *                         <em>old</em> position, not the one this frame is about to write).
     */
    private void onFlingFrame(int direction, int requestedPx, int consumedPx, int unconsumedPx,
                              int clampedPosition, int scrollRangeX) {
        mFlingFrameSeen = true;
        mFlingFrameCount++;
        if (unconsumedPx > 0) mFlingClampedCount++;
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
            // The pull has just been partly given back, and it is a fling that is giving it back:
            // nothing else will notice if this one stops before the displacement reaches 0.
            ensureSettled();
        }

        if (mFlingImpactSpent || mDragPullAbsorbed || !mFlingArmed) return;
        // "Reached an end": the position is pinned to one of the two ends of the range. The two
        // tests are one condition, not two cases — with a range of 0 both ends are the same
        // position, and a flick on a strip that cannot scroll at all must still bounce.
        if (clampedPosition != 0 && clampedPosition != scrollRangeX) return;

        mFlingEndFrameCount++;
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
     * sees the whole gesture: when the finger goes down on a tab, the child owns the stream and
     * {@code onTouchEvent} is not called at all until the strip has intercepted — and if the
     * gesture turns out to be a tap it never is, so a release hooked on {@code onTouchEvent}
     * would leave the previous pull hanging.
     *
     * <p>Run <em>after</em> {@code super}, so the fling the widget has just launched on ACTION_UP
     * is already registered by the time the spring is armed, and so the pull still held at this
     * instant can be recorded before {@link #onEdgeRelease} consumes it. The two animations
     * coexist exactly as they do in the pager: the first fling frame that scrolls calls
     * {@link #bleedIntoScroll}, which cancels the spring and takes the pull over — so
     * pull-then-flick-inward is one continuous motion rather than a spring fighting a scroll. If
     * the fling has nowhere to go (the common case at an end) it produces no scroll frames at all,
     * and the spring is left to do its job alone.
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
        mFlingFrameCount = 0;
        mFlingClampedCount = 0;
        mFlingEndFrameCount = 0;
        mLastFlingFrameNanos = 0L;
        armFlingLifetime();
        super.fling(velocityX);
    }

    /**
     * Keep {@link #mFlingArmed} true while a scroller is producing frames and let it lapse
     * {@link #FLING_IDLE_MS} after the last one. Called on the launch and again on every frame, so
     * the deadline is always one idle period past the newest frame — the timer only ever fires for
     * a scroller that has actually stopped, which is the only moment another one could take over.
     */
    private void armFlingLifetime() {
        removeCallbacks(mFlingDisarm);
        postOnAnimationDelayed(mFlingDisarm, FLING_IDLE_MS);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A programmatic scroll means the strip is being repositioned for a reason that has nothing
     * to do with the finger — {@code TermuxSessionTabsController} drives {@code scrollTo} directly
     * from its own animations — so a held pull is released into its spring rather than left
     * hanging relative to the new position. The widget's own scrolling does not come through here
     * ({@code HorizontalScrollView#onOverScrolled} calls {@code View.scrollTo} explicitly), so
     * this cannot fire mid-drag and cannot fight the gesture.
     *
     * <p>Gated on the position actually moving, because {@code HorizontalScrollView#onLayout} ends
     * by calling this with the current values "to re-claim them" — a no-op that must not be read
     * as a reposition, or every layout pass during a pull would spring the strip back under the
     * finger.
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

    // ── configuration / diagnostics ────────────────────────────────────────────────────────

    /** Enable/disable the elastic over-drag (a disable also drops any displacement held). */
    public void setElasticOverscrollEnabled(boolean enabled) {
        mElasticEnabled = enabled;
        if (!enabled) reset();
    }

    /** The displacement currently applied as the content's {@code translationX}, in px. */
    public float getOverscrollDisplacementPx() {
        return mTranslationPx;
    }

    /**
     * A one-line snapshot of the whole over-drag state, for the debug command receiver. Exists
     * because the fling half of this class is otherwise unobservable on a device: whether an
     * impulse was ever absorbed, and with what velocity, is decided by frames the caller never
     * sees.
     */
    public String dumpOverscrollState() {
        return "disp=" + mTranslationPx
                + " leftRaw=" + mLeftRawPx
                + " rightRaw=" + mRightRawPx
                + " spring=" + (mSpring != null)
                + " impact=" + (mImpact != null)
                + " flingFrames=" + mFlingFrameCount
                + " clampedFrames=" + mFlingClampedCount
                + " endFrames=" + mFlingEndFrameCount
                + " absorbs=" + mAbsorbCount
                + " spent=" + mFlingImpactSpent
                + " dragAbsorbed=" + mDragPullAbsorbed
                + " launchV=" + Math.round(mFlingLaunchVelocityPx)
                + " arrivalV=" + Math.round(mFlingArrivalPxPerSec);
    }

    @Override
    protected void onDetachedFromWindow() {
        // A view pulled out of the window can no longer be sprung back, and whatever displacement
        // it was holding must not survive into the next attachment.
        reset();
        super.onDetachedFromWindow();
    }

    // ── gesture input, physics side ────────────────────────────────────────────────────────

    /**
     * The finger travelled {@code deltaPx} further past one end.
     *
     * @param direction {@link #DIRECTION_LEFT} (the content moves right) or
     *                  {@link #DIRECTION_RIGHT} (it moves left).
     * @param deltaPx   always positive — an increasing pull.
     */
    private void onEdgePull(int direction, float deltaPx) {
        if (!(deltaPx > 0f) || !isFinite(deltaPx)) return;
        cancelAnimators();
        // The finger can only be on one end at a time, so a new pull first pays off whatever the
        // opposite end still holds. With a scrollable strip that is a belt-and-braces path — the
        // bleed below pays the held pull back as the list scrolls away from the end — but the
        // strip need not be scrollable at all (two tabs fit on any screen), and then both ends
        // are the same position and a drag can change which one it is pulling without any scroll
        // in between.
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
     * that was actually hit, then spring back, so a hard flick visibly slams the strip against
     * its limit instead of being swallowed. Same conversion as the pager's — literally the same
     * {@link ElasticOverdrag#impact} call, so both surfaces threshold and cap a given gesture
     * identically on any density.
     *
     * @param direction         which end absorbed the fling. It has to be passed in: the current
     *                          displacement is 0 at this point, so its sign says nothing about
     *                          the direction of the impact.
     * @param velocityPxPerSec  always positive — the arrival speed measured in {@link #onFlingFrame}.
     */
    private void onEdgeAbsorb(int direction, float velocityPxPerSec) {
        mAbsorbCount++;
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
     * Play the fly-out half of an impact: the band being stretched by the impulse that arrived.
     * The samples are raw travel, but they were integrated in displacement and converted back, so
     * the content follows the band being loaded — and it starts at the speed the impulse actually
     * arrived with, i.e. a harder flick flies out faster as well as further. On the last sample
     * the stretch is handed to {@link #onEdgeRelease()}, so the return starts from wherever the
     * fly-out actually got to.
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
     * The strip scrolled for real by {@code giveBackPx}. Hand the held pull back one pixel of
     * finger travel per pixel of scroll so a pull-then-return gesture stays continuous; without
     * this the content would move by the scroll <em>and</em> keep the over-drag, i.e. by both at
     * once.
     *
     * <p>The payback is applied to the raw accumulator, never to the damped value on screen — the
     * pull curve is concave, so subtracting from the displacement would pay the pull back at
     * ~2.4x the rate the finger bought it at, and a single large scroll frame could zero the
     * whole displacement in one frame instead of animating it home. See
     * {@code PagerOverscrollController#bleedIntoScroll}, which spells the arithmetic out.
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
     * The single writer of the content's {@code translationX}. This is the last line of defence:
     * the value is a property of the view holding every tab, so it must always be a finite,
     * bounded number — anything else is coerced to 0 rather than allowed to poison the view's
     * transform. (An invalid RenderNode transform does not just go invisible: the whole subtree
     * stops being drawn, and {@code ViewGroup} hit-testing inverts a NaN matrix, so every touch
     * coordinate handed to the strip becomes NaN.)
     */
    private void setTranslation(float px, float widthPx) {
        if (!isFinite(px)) px = 0f;
        float max = ElasticOverdrag.maxPull(widthPx);
        px = Math.max(-max, Math.min(max, px));
        // An unchanged displacement must not dirty the RenderNode. Every settle/reset ends here,
        // and most of them are writing a 0 that is already there.
        if (px == mTranslationPx) return;
        mTranslationPx = px;
        View content = getChildAt(0);
        if (content != null) content.setTranslationX(px);
    }

    /**
     * The strip's extent along the drag axis — the viewport width. Every scale of the effect is a
     * fraction of it: the cap is 20 % of it, and the finger travel that reaches the cap is derived
     * from it. Read on demand rather than cached, so a rotation, a split-screen resize or a
     * change of the tab-height mode reflows the physics instead of keeping a stale pixel value.
     *
     * <p>The strip has no quantum to snap to (unlike the transcript, which is made of glyph rows),
     * so all of these use the plain {@link ElasticOverdrag} entry points.
     */
    private float extentPx() {
        return Math.max(1f, getWidth());
    }

    private float maxPullPx() {
        return ElasticOverdrag.maxPull(extentPx());
    }

    /**
     * The display density, read on demand so a move to another display is picked up. Only the
     * impulse needs it: the band itself is scale-free, so nothing else in the effect is
     * density-dependent.
     */
    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    /** {@link #damp(float)} against an extent the caller already has — see {@link #apply()}. */
    private float damp(float rawPx, float widthPx) {
        // Rejects NaN as well.
        if (!(rawPx > 0f)) return 0f;
        return ElasticOverdrag.damp(rawPx, widthPx);
    }

    /** Inverse of {@link #damp(float)}: the raw travel that produces {@code dampedPx}. */
    private float inverseDamp(float dampedPx) {
        if (!(dampedPx > 0f)) return 0f;
        return clampRaw(ElasticOverdrag.undamp(dampedPx, extentPx()));
    }

    /** Keep the raw accumulator finite and bounded: positive, never NaN/Infinity, never > cap. */
    private float clampRaw(float rawPx) {
        if (!(rawPx > 0f) || !isFinite(rawPx)) return 0f;
        return ElasticOverdrag.clampRaw(rawPx, extentPx());
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    // ── the "never displaced while idle" invariant ─────────────────────────────────────────

    /**
     * Guarantee that the strip is not left displaced once the gesture is over.
     *
     * <p>Normally the spring-back's {@code onAnimationEnd} writes the final 0 — but
     * {@link #bleedIntoScroll} strips the listeners before cancelling so the content does not
     * snap mid-gesture, and that very same listener removal removes the only guaranteed writer of
     * a clean 0. The bleed can run long after the finger has gone, because a fling keeps
     * scrolling; if the bleed does not finish the job in that frame, nothing ever would again.
     *
     * <p>So after every fling frame that gives the pull back we post a check for leftover
     * displacement, and it re-posts itself for as long as fling frames keep arriving
     * ({@link #mFlingFrameSeen}). The check <em>animates</em> what it finds rather than zeroing
     * it: the leftover is a displacement the user can see, so writing a clean 0 is a snap of up
     * to the full cap, which is the very thing this class must not do. That also keeps the
     * fail-safe promise — the worst case stays a <em>missing</em> animation, never a broken
     * strip — because a spring can only ever end at 0.
     */
    private void ensureSettled() {
        removeCallbacks(mSettleRunnable);
        postOnAnimation(mSettleRunnable);
    }

    private void settleNow() {
        final boolean flingStillRunning = mFlingFrameSeen;
        mFlingFrameSeen = false;
        if (!mElasticEnabled) return;
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
