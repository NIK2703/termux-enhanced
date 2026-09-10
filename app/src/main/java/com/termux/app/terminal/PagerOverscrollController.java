package com.termux.app.terminal;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.view.animation.Interpolator;
import android.widget.EdgeEffect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

/**
 * iOS-like elastic ("rubber band") over-drag for the horizontal session pager.
 *
 * <p>On the first/last page there is no neighbouring page to scroll to, so a horizontal drag
 * currently dies at the boundary: {@code LinearLayoutManager} consumes nothing and the page sits
 * perfectly still. This controller turns that dead zone into resistance — the edge page follows
 * the finger with a damped, asymptotic response and springs back on release, so the terminal
 * screen can be pulled a little way off the edge (revealing the wallpaper behind it) and snaps
 * back when let go.
 *
 * <h2>How the pull is measured</h2>
 * The displacement is <em>not</em> read from touch events and it is <em>not</em> a scroll offset.
 * A {@link ViewPager2} is a {@link RecyclerView} with a {@code PagerSnapHelper}, and a
 * RecyclerView can never scroll past its own bounds — so there is nothing to read. What it does
 * have is the edge-effect plumbing: {@code RecyclerView#scrollByInternal} funnels every
 * <em>unconsumed</em> scroll delta into {@code pullGlows()}, which calls
 * {@code EdgeEffectCompat.onPullDistance(glow, unconsumedPx / width, displacement)} — i.e. the
 * framework already computes exactly the "pixels dragged past the boundary" we need.
 *
 * <p>So the controller installs a {@link RecyclerView.EdgeEffectFactory} whose
 * {@link EdgeEffect}s are pure spies: they draw nothing, never call through to
 * {@code super.onPull} (so the internal edge-effect distance stays 0 forever) and only forward
 * the delta. Three callbacks cover the whole gesture lifecycle:
 * <ul>
 *   <li>{@code onPull / onPullDistance} — finger moved further past the edge (drag),</li>
 *   <li>{@code onAbsorb(velocity)} — the user flicked into the wall (only dispatched when
 *       {@code isFinished()} is true, which is why the spy always reports finished),</li>
 *   <li>{@code onRelease()} — finger lifted, spring back.</li>
 * </ul>
 *
 * <p>Because the effect always reports distance 0, every stock visual is suppressed as a side
 * effect: the pre-12 glow ({@code draw()} returns false), the 12+ stretch (all stretch paths —
 * {@code releaseHorizontalGlow}, {@code consumeFlingInStretch}, {@code shouldAbsorb} — key off a
 * non-zero {@code EdgeEffect.getDistance()}), and the extra composite pass that stretch added on
 * top of the translucent terminal surface. That is a feature, not a workaround: the elastic
 * displacement replaces the stretch rather than stacking on it.
 *
 * <h2>Where the displacement is applied</h2>
 * On the pager's inner RecyclerView itself ({@code translationX}), not on a page. The pager is
 * full-bleed and clips its children, so translating the RecyclerView slides the edge page by
 * exactly that amount and the strip it vacates shows the activity background / wallpaper — the
 * same reveal iOS produces. The RecyclerView's own scroll offset stays untouched, so
 * {@code PagerSnapHelper}, {@code onPageScrolled} and {@code getCurrentItem()} never see the
 * over-drag and cannot fight it.
 *
 * <h2>Giving the pull back (the bleed)</h2>
 * A RecyclerView does not know an over-drag exists, so the moment the finger travels back inward
 * it starts scrolling for real — which would move the page by both the scroll <em>and</em> the
 * still-held over-drag. {@code onScrolled} therefore bleeds the held displacement back into the
 * scroll one pixel per pixel of consumed scroll ({@link #bleedIntoScroll}), so pull-then-return
 * and pull-then-fling-inward read as one continuous motion instead of a jump.
 *
 * <p>With a single page the RecyclerView cannot scroll back at all, so the return arrives as an
 * unconsumed delta on the <em>opposite</em> edge. {@link #onEdgePull} handles that by spending a
 * new pull on cancelling the opposite side's accumulator before it starts its own, which keeps
 * the page's position continuous across the boundary crossing.
 *
 * <h2>Why the maths is clamped everywhere</h2>
 * The displacement is written to {@code View#setTranslationX} of the view that hosts <em>every</em>
 * page, so a bad value here takes out all sessions at once, and it is unrecoverable without
 * recreating the activity. Two things make that very easy to get wrong:
 * <ul>
 *   <li>{@code damp(x)} is {@code (C*M*x) / (C*x + M)} — for {@code x = +Infinity} that is
 *       {@code Inf/Inf}, i.e. {@code NaN}; for a merely huge {@code x} the numerator overflows to
 *       Infinity first and the result is NaN all the same. Infinity used to be reachable two ways:
 *       normalising the pull against a zero-width view, and an unbounded raw-travel accumulator fed
 *       by {@code inverseDamp()} (which divides by {@code MAX - damped}).</li>
 *   <li>A NaN translation is not just "invisible": the RenderNode transform is invalid, so the
 *       whole subtree — every page, every session — stops being drawn, <em>and</em>
 *       {@code ViewGroup} hit-testing inverts a NaN matrix, so the touch coordinates handed to the
 *       RecyclerView are NaN and its drag maths stops producing scroll. That is exactly the
 *       reported "output gone for all sessions and the pager no longer swipes, restart only".</li>
 * </ul>
 *
 * So this class maintains three invariants, enforced at every entry point rather than trusted:
 * <ol>
 *   <li><b>Finite only.</b> No non-finite value is ever stored or handed to
 *       {@code setTranslationX}.</li>
 *   <li><b>Bounded.</b> The raw accumulator is capped, which makes {@link #damp(float)} provably
 *       bounded by {@link #MAX_PULL_DP} — no overflow, no Infinity, no NaN.</li>
 *   <li><b>Never displaced while idle.</b> After every gesture the pager is guaranteed to sit at
 *       exactly 0 — see {@link #ensureSettled()}. This holds even when the spring-back was
 *       cancelled mid-flight (which used to be the one path that never wrote a clean 0 again),
 *       and it is what makes the whole feature fail-safe: the worst case is a missing animation,
 *       never a broken pager.</li>
 * </ol>
 */
public final class PagerOverscrollController {

    /**
     * Rubber-band stiffness — the same constant {@code UIScrollView} uses. It is the slope of the
     * response curve at zero: the page follows the finger at ~55% and bends away from there.
     */
    private static final float STIFFNESS = 0.55f;

    /** Hard cap on how far the edge page can be pulled off the boundary, in dp. */
    private static final float MAX_PULL_DP = 56f;

    /**
     * Cap on the accumulated raw finger travel, in px (~4 screen widths). Purely a safety bound:
     * {@link #damp(float)} is asymptotic, so past a few hundred px the curve is already within a
     * pixel or two of the cap and no finger can tell the difference — but without a bound the
     * accumulator is a float that only ever grows, and once it reaches the overflow threshold
     * {@code damp()} returns NaN (see the class javadoc).
     */
    private static final float MAX_RAW_TRAVEL_PX = 4096f;

    /** Spring-back duration, in ms. */
    private static final long SPRING_DURATION_MS = 320L;

    /**
     * Damped-oscillation constants for the spring-back curve
     * {@code f(t) = 1 - e^(-decay*t) * cos(freq*t)}: f(0)=0, f(1)~1. The cosine crosses zero at
     * t=0.17 (the page reaches the boundary) and bottoms out at t=0.35, so the page overshoots the
     * boundary by e^(-1.92) ~ 14% of the displacement and settles — one visible bounce, no ringing.
     */
    private static final float SPRING_DECAY = 5.5f;
    private static final float SPRING_FREQ = 9f;

    /** Fraction of a fling velocity (px/s) converted into extra pull when it hits the wall. */
    private static final float ABSORB_VELOCITY_SCALE = 0.02f;

    /** Sanity cap on an absorbed fling velocity (px/s) before it is converted to pull. */
    private static final int MAX_ABSORB_VELOCITY = 6000;

    private static final int DIRECTION_LEFT = RecyclerView.EdgeEffectFactory.DIRECTION_LEFT;
    private static final int DIRECTION_RIGHT = RecyclerView.EdgeEffectFactory.DIRECTION_RIGHT;

    /** The pager's inner RecyclerView — the view that actually gets displaced. */
    private final RecyclerView mRecyclerView;

    /** {@link #MAX_PULL_DP} in pixels, resolved once against the display density. */
    private final float mMaxPullPx;

    /** Accumulated (undamped) finger travel past the left / right boundary, in px. Never negative. */
    private float mLeftRawPx;
    private float mRightRawPx;

    /** The damped displacement currently applied as {@code translationX}, in px.
     *  Positive = the page sits to the right of the boundary (first page pulled right). */
    private float mTranslationPx;

    @Nullable
    private ValueAnimator mSpring;

    private boolean mEnabled = true;

    private final RecyclerView.OnScrollListener mScrollListener;
    private final View.OnAttachStateChangeListener mAttachListener;

    /** Deferred {@link #settleNow()} — see {@link #ensureSettled()} for why it is posted. */
    private final Runnable mSettleRunnable = this::settleNow;

    private PagerOverscrollController(@NonNull RecyclerView recyclerView) {
        mRecyclerView = recyclerView;
        mMaxPullPx = Math.max(1f, MAX_PULL_DP * recyclerView.getResources().getDisplayMetrics().density);

        // OVER_SCROLL_NEVER short-circuits pullGlows()/absorbGlows() entirely, so the edge has to
        // be scrollable again for the spy to receive anything. Nothing visible comes of it: the
        // spy reports a zero distance, which is what every stock visual keys off.
        recyclerView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        recyclerView.setEdgeEffectFactory(new SpyFactory(recyclerView.getContext()));

        mScrollListener = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dx != 0) bleedIntoScroll(dx);
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) ensureSettled();
            }
        };
        recyclerView.addOnScrollListener(mScrollListener);

        // A view pulled out of the window can no longer be sprung back (the animator would keep
        // writing to a detached RenderNode), and whatever displacement it was holding must not
        // survive into the next attachment.
        mAttachListener = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) { }

            @Override
            public void onViewDetachedFromWindow(View v) {
                reset();
            }
        };
        recyclerView.addOnAttachStateChangeListener(mAttachListener);
    }

    /**
     * Attach the elastic over-drag to a {@link ViewPager2}.
     *
     * @return the controller, or {@code null} if the pager's inner RecyclerView is not available
     *         yet (it is created in the ViewPager2 constructor, so in practice it always is).
     */
    @Nullable
    public static PagerOverscrollController install(@NonNull ViewPager2 pager) {
        View child = pager.getChildCount() > 0 ? pager.getChildAt(0) : null;
        if (!(child instanceof RecyclerView)) return null;
        return new PagerOverscrollController((RecyclerView) child);
    }

    /** Enable/disable the effect (a disable also drops any displacement currently held). */
    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
        if (!enabled) reset();
    }

    /** Drop any held displacement right now, without animating. */
    public void reset() {
        cancelSpring();
        mRecyclerView.removeCallbacks(mSettleRunnable);
        mLeftRawPx = 0f;
        mRightRawPx = 0f;
        setTranslation(0f);
    }

    /** Detach: cancels the spring-back and unhooks every listener so nothing outlives the activity. */
    public void destroy() {
        reset();
        mRecyclerView.removeOnScrollListener(mScrollListener);
        mRecyclerView.removeOnAttachStateChangeListener(mAttachListener);
        // Hand the pager back exactly as it was before install(): default edge effects, no
        // displacement, and the stock over-scroll switched off again (this is what the pager had
        // before the elastic over-drag existed).
        mRecyclerView.setEdgeEffectFactory(new RecyclerView.EdgeEffectFactory());
        mRecyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
    }

    // ── gesture input ──────────────────────────────────────────────────────────────────────

    /**
     * The finger travelled {@code deltaPx} further past one boundary.
     *
     * @param direction {@link #DIRECTION_LEFT} (first page, displacement is to the right) or
     *                  {@link #DIRECTION_RIGHT} (last page, displacement is to the left).
     * @param deltaPx   always positive — an increasing pull.
     */
    private void onEdgePull(int direction, float deltaPx) {
        if (!mEnabled) return;
        // Rejects NaN as well: NaN > 0f is false.
        if (!(deltaPx > 0f) || !isFinite(deltaPx)) return;
        cancelSpring();
        // The finger can only be on one side of the boundary at a time, so a new pull first pays
        // off whatever the opposite side still holds. This is what makes the (single-page) case
        // work: RecyclerView cannot scroll back, so a return-to-boundary drag is reported as an
        // unconsumed delta on the OPPOSITE edge, i.e. as a pull on the other side.
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

    /** Finger lifted (or the gesture was cancelled): spring back to the boundary. */
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
     * A fling ran into the wall: {@code RecyclerView#absorbGlows} hands over the velocity that
     * could not be turned into scroll. Convert a slice of it into extra pull on the edge that was
     * actually hit, then spring back, so a hard flick visibly slams the page against its limit
     * instead of being swallowed.
     *
     * @param direction {@link #DIRECTION_LEFT} or {@link #DIRECTION_RIGHT} — which edge absorbed
     *                  the fling. It has to be passed in: the current displacement is 0 at this
     *                  point, so its sign says nothing about the direction of the impact, and
     *                  guessing from it bounced the last page the wrong way.
     * @param velocity  always positive — RecyclerView negates it for the left edge.
     */
    private void onEdgeAbsorb(int direction, int velocity) {
        if (!mEnabled) return;
        if (velocity <= 0) {
            onEdgeRelease();
            return;
        }
        cancelSpring();
        float extra = clampRaw(Math.min(velocity, MAX_ABSORB_VELOCITY) * ABSORB_VELOCITY_SCALE);
        if (direction == DIRECTION_LEFT) mLeftRawPx = clampRaw(mLeftRawPx + extra);
        else mRightRawPx = clampRaw(mRightRawPx + extra);
        apply();
        onEdgeRelease();
    }

    /**
     * The pager scrolled for real by {@code dx} (positive = content moved left). Hand the held
     * displacement back one pixel per pixel of scroll so a pull-then-return gesture stays
     * continuous; without this the page would move by the scroll <em>and</em> keep the over-drag.
     */
    private void bleedIntoScroll(float dx) {
        if (mTranslationPx == 0f) return;
        cancelSpring();
        // Only the MAGNITUDE of the scroll matters, never its sign: the bleed can only ever pull
        // the displacement back toward the boundary, never push it further out. Subtracting the
        // signed dx (the obvious reading of "give one pixel back per pixel of scroll") is what
        // broke the reported case — the spring-back curve overshoots past 1, so after ~100 ms the
        // displacement has the OPPOSITE sign to the pull, and a real scroll then ADDED |dx| to it
        // on every single onScrolled frame. One page swipe piles ~1000 px of displacement onto the
        // pager and the RecyclerView parks a full screen width off, with every page (i.e. every
        // session) drawn outside the viewport and no path left that ever writes a clean 0.
        float magnitude = Math.max(0f, Math.abs(mTranslationPx) - Math.abs(dx));
        float target = mTranslationPx > 0f ? magnitude : -magnitude;
        setTranslation(target);
        // Re-seed the accumulators from the damped value so the next pull picks up from here.
        mLeftRawPx = inverseDamp(target);
        mRightRawPx = inverseDamp(-target);
    }

    // ── displacement ───────────────────────────────────────────────────────────────────────

    private void apply() {
        setTranslation(damp(mLeftRawPx) - damp(mRightRawPx));
    }

    /**
     * The single writer of {@code translationX}. This is the last line of defence: the value is a
     * property of the view hosting every page, so it must always be a finite, bounded number —
     * anything else is coerced to 0 rather than allowed to poison the view's transform.
     */
    private void setTranslation(float px) {
        if (!isFinite(px)) px = 0f;
        px = Math.max(-mMaxPullPx, Math.min(mMaxPullPx, px));
        mTranslationPx = px;
        mRecyclerView.setTranslationX(px);
    }

    /**
     * The rubber-band curve: {@code f(x) = C*MAX*x / (C*x + MAX)} — f(0)=0, f'(0)=C (the page
     * follows the finger at 55% near the boundary), f(inf)=MAX (it can never be pulled further
     * than the cap however hard the finger insists). Strictly below MAX for every finite x, so a
     * clamped accumulator can never produce an unbounded — or non-finite — displacement.
     */
    private float damp(float rawPx) {
        // Rejects NaN as well.
        if (!(rawPx > 0f)) return 0f;
        float x = Math.min(rawPx, MAX_RAW_TRAVEL_PX);
        return (STIFFNESS * mMaxPullPx * x) / (STIFFNESS * x + mMaxPullPx);
    }

    /** Inverse of {@link #damp(float)}: the raw travel that produces {@code dampedPx}. */
    private float inverseDamp(float dampedPx) {
        if (!(dampedPx > 0f)) return 0f;
        // Clamped well short of the asymptote: the denominator is (MAX - damped), which is what
        // makes the raw travel blow up (and eventually overflow) as the pull approaches the cap.
        float d = Math.min(dampedPx, mMaxPullPx * 0.98f);
        return clampRaw((d * mMaxPullPx) / (STIFFNESS * (mMaxPullPx - d)));
    }

    /** Keep the raw accumulator finite and bounded: positive, never NaN/Infinity, never > cap. */
    private static float clampRaw(float rawPx) {
        if (!(rawPx > 0f) || !isFinite(rawPx)) return 0f;
        return Math.min(rawPx, MAX_RAW_TRAVEL_PX);
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    // ── the "never displaced while idle" invariant ─────────────────────────────────────────

    /**
     * Guarantee that the pager is not left displaced once the gesture is over.
     *
     * <p>Normally the spring-back's {@code onAnimationEnd} writes the final 0 — but the moment the
     * user interrupts the spring (which is exactly what "quickly page to the neighbour while it is
     * still returning" does), {@link #cancelSpring()} strips the listeners before cancelling so the
     * page does not snap mid-gesture, and that very same listener removal removes the only
     * guaranteed writer of a clean 0. If the bleed did not finish the job in that frame, nothing
     * ever would again.
     *
     * <p>So on every {@code SCROLL_STATE_IDLE} we post a check that zeroes any leftover
     * displacement. It is posted rather than run inline because RecyclerView reaches IDLE while
     * dispatching {@code ACTION_UP}, i.e. <em>before</em> it hands the edge effects their
     * {@code onRelease()} — running inline would kill the spring-back before it starts. One frame
     * later the spring (if any) is already registered, and the check stands down.
     */
    private void ensureSettled() {
        mRecyclerView.removeCallbacks(mSettleRunnable);
        mRecyclerView.post(mSettleRunnable);
    }

    private void settleNow() {
        if (!mEnabled || mSpring != null) return;
        if (mTranslationPx != 0f || mLeftRawPx != 0f || mRightRawPx != 0f) reset();
    }

    // ── spring back ────────────────────────────────────────────────────────────────────────

    private void startSpring(float from) {
        cancelSpring();
        if (!isFinite(from) || from == 0f) {
            // Nothing sane to animate from: drop the displacement instead of animating NaN.
            mLeftRawPx = 0f;
            mRightRawPx = 0f;
            setTranslation(0f);
            return;
        }
        from = Math.max(-mMaxPullPx, Math.min(mMaxPullPx, from));
        ValueAnimator animator = ValueAnimator.ofFloat(from, 0f);
        animator.setDuration(SPRING_DURATION_MS);
        animator.setInterpolator(SPRING_INTERPOLATOR);
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

    private void cancelSpring() {
        ValueAnimator spring = mSpring;
        mSpring = null;
        if (spring == null) return;
        // Unhook before cancelling: cancel() fires onAnimationEnd too, which would snap the page
        // to the boundary mid-gesture.
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

    private static final Interpolator SPRING_INTERPOLATOR = input ->
            (float) (1f - Math.exp(-SPRING_DECAY * input) * Math.cos(SPRING_FREQ * input));

    // ── the edge-effect spy ────────────────────────────────────────────────────────────────

    private final class SpyFactory extends RecyclerView.EdgeEffectFactory {
        private final Context mContext;

        SpyFactory(Context context) {
            mContext = context;
        }

        @NonNull
        @Override
        protected EdgeEffect createEdgeEffect(@NonNull RecyclerView view, int direction) {
            return new SpyEdgeEffect(mContext, direction);
        }
    }

    /**
     * An {@link EdgeEffect} that produces no visuals of its own and instead reports the pull to
     * the controller. Deliberately never calls {@code super.onPull}: the internal distance stays 0,
     * which is what keeps the platform's stretch (and its extra composite pass over the translucent
     * terminal) switched off while the elastic displacement takes over.
     */
    private final class SpyEdgeEffect extends EdgeEffect {
        private final int mDirection;

        SpyEdgeEffect(Context context, int direction) {
            super(context);
            mDirection = direction;
        }

        @Override
        public void onPull(float deltaDistance) {
            onPull(deltaDistance, 0.5f);
        }

        @Override
        public void onPull(float deltaDistance, float displacement) {
            if (!isHorizontal()) return;
            // RecyclerView normalises the unconsumed delta by the view width before handing it
            // over; both edges receive a POSITIVE deltaDistance for an increasing pull. De-normalise
            // with that same width — and bail out on a zero-width view, where the framework's own
            // division would already have turned the delta into Infinity (Infinity in, NaN out).
            int width = mRecyclerView.getWidth();
            if (width <= 0) return;
            onEdgePull(mDirection, deltaDistance * width);
        }

        /** API 31+ path — {@code EdgeEffectCompat} calls this instead of {@link #onPull(float, float)}.
         *  Returning 0 tells RecyclerView that none of the pull was consumed by an edge effect,
         *  which is exactly right: the displacement goes to the page, not to a stretch. */
        @Override
        public float onPullDistance(float deltaDistance, float displacement) {
            onPull(deltaDistance, displacement);
            return 0f;
        }

        @Override
        public void onRelease() {
            if (isHorizontal()) onEdgeRelease();
        }

        @Override
        public void onAbsorb(int velocity) {
            if (isHorizontal()) onEdgeAbsorb(mDirection, velocity);
        }

        /** Zero distance = no stretch, ever (API 31+ keys every stretch path off this). */
        @Override
        public float getDistance() {
            return 0f;
        }

        /** RecyclerView only dispatches {@link #onAbsorb(int)} to a finished effect. */
        @Override
        public boolean isFinished() {
            return true;
        }

        @Override
        public boolean draw(Canvas canvas) {
            return false;
        }

        private boolean isHorizontal() {
            return mDirection == DIRECTION_LEFT || mDirection == DIRECTION_RIGHT;
        }
    }
}
