package com.termux.app.terminal;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.widget.EdgeEffect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.termux.view.ElasticOverdrag;

/**
 * Elastic ("rubber band") over-drag for the horizontal session pager.
 *
 * <p>On the first/last page the drag dies at the boundary; this controller turns that dead
 * zone into resistance: the edge page follows the finger with a damped response and springs
 * back on release, so it can be pulled a little way off the edge and snaps back when let go.
 *
 * <h2>How the pull is measured</h2>
 * A RecyclerView never scrolls past its bounds, but it funnels every <em>unconsumed</em>
 * scroll delta into {@code pullGlows()} / {@code onPullDistance(glow, unconsumedPx/width, ...)},
 * i.e. the framework already computes the "pixels dragged past the boundary". So a
 * {@link RecyclerView.EdgeEffectFactory} of pure-spy {@link EdgeEffect}s is installed: they
 * draw nothing, never call {@code super.onPull} (internal distance stays 0 forever, which also
 * suppresses every stock visual) and only forward the delta via {@code onPull/onPullDistance}
 * (drag), {@code onAbsorb} (fling into the wall) and {@code onRelease} (spring back).
 *
 * <h2>Where the displacement is applied</h2>
 * On the pager's inner RecyclerView itself ({@code translationX}), not on a page: the pager
 * clips its children, so translating the RecyclerView slides the edge page and reveals the
 * background. The scroll offset stays untouched, so PagerSnapHelper / {@code onPageScrolled} /
 * {@code getCurrentItem()} never see the over-drag and cannot fight it.
 *
 * <h2>Giving the pull back (the bleed)</h2>
 * When the finger travels back inward the RecyclerView scrolls for real; {@code onScrolled}
 * therefore bleeds the held pull back one pixel of <em>finger travel</em> per pixel of
 * consumed scroll ({@link #bleedIntoScroll}). Paying in the finger-travel domain (rather than
 * the damped on-screen value) keeps the motion continuous: in the damped domain the concave
 * curve cancels ~2.4x the pull and a single large scroll frame can zero the displacement at
 * once. With a single page the return arrives as an unconsumed delta on the <em>opposite</em>
 * edge ({@link #onEdgePull} spends a new pull cancelling the opposite accumulator first).
 *
 * <h2>Why the maths is clamped everywhere</h2>
 * The value is written to the view hosting <em>every</em> page, so a bad value takes out all
 * sessions at once and is unrecoverable without recreating the activity: a NaN translation
 * makes the whole subtree stop being drawn <em>and</em> inverts hit-testing (the reported
 * "output gone for all sessions, restart only"). Three invariants are enforced at every entry
 * point: (1) finite only, no non-finite value ever reaches {@code setTranslationX}; (2) the
 * raw accumulator is capped at the saturation travel, so {@link #damp(float)} cannot overflow;
 * (3) never displaced while idle, any leftover is <em>animating</em> to 0
 * ({@link #ensureSettled()}) so enforcing it is never itself the jerk.
 */
public final class PagerOverscrollController {

    // The absorb cap and fling threshold live in the shared model (ElasticOverdrag, dp/s) РІР‚вЂќ a px/s
    // value would retune the bounce per display density. Same numbers on both surfaces, every device.

    private static final int DIRECTION_LEFT = RecyclerView.EdgeEffectFactory.DIRECTION_LEFT;
    private static final int DIRECTION_RIGHT = RecyclerView.EdgeEffectFactory.DIRECTION_RIGHT;

    /** The pager's inner RecyclerView РІР‚вЂќ the view that actually gets displaced. */
    private final RecyclerView mRecyclerView;

    /** Accumulated (undamped) finger travel past the left / right boundary, in px. Never negative. */
    private float mLeftRawPx;
    private float mRightRawPx;

    /** The damped displacement currently applied as {@code translationX}, in px.
     *  Positive = the page sits to the right of the boundary (first page pulled right). */
    private float mTranslationPx;

    @Nullable
    private ValueAnimator mSpring;

    /** The fly-out of a fling impact (the band being stretched); null unless one is running. */
    @Nullable
    private ValueAnimator mImpact;

    private boolean mEnabled = true;

    private final RecyclerView.OnScrollListener mScrollListener;
    private final View.OnAttachStateChangeListener mAttachListener;

    /** Deferred {@link #settleNow()} РІР‚вЂќ see {@link #ensureSettled()} for why it is posted. */
    private final Runnable mSettleRunnable = this::settleNow;

    private PagerOverscrollController(@NonNull RecyclerView recyclerView) {
        mRecyclerView = recyclerView;

        // OVER_SCROLL_NEVER short-circuits pullGlows()/absorbGlows(), so the edge must be
        // scrollable again for the spy to receive anything; its zero distance keeps it invisible.
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

        // A detached view cannot be sprung back (the animator would write to a dead RenderNode),
        // and any held displacement must not survive into the next attachment.
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
        cancelAnimators();
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
        // displacement, stock over-scroll off again.
        mRecyclerView.setEdgeEffectFactory(new RecyclerView.EdgeEffectFactory());
        mRecyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
    }

    // ---- gesture input ----

    /**
     * The finger travelled {@code deltaPx} further past one boundary.
     *
     * @param direction {@link #DIRECTION_LEFT} (first page, displacement is to the right) or
     *                  {@link #DIRECTION_RIGHT} (last page, displacement is to the left).
     * @param deltaPx   always positive РІР‚вЂќ an increasing pull.
     */
    private void onEdgePull(int direction, float deltaPx) {
        if (!mEnabled) return;
        // Rejects NaN as well: NaN > 0f is false.
        if (!(deltaPx > 0f) || !isFinite(deltaPx)) return;
        cancelAnimators();
        // The finger is only on one side at a time, so a new pull pays off whatever the opposite
        // side still holds. This is what makes the single-page case work: the return drag arrives
        // as an unconsumed delta on the OPPOSITE edge, i.e. as a pull on the other side.
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
     * A fling ran into the wall: convert a slice of the unused velocity into extra pull on the
     * edge that was hit, then spring back РІР‚вЂќ a hard flick visibly slams the page into its limit
     * instead of being swallowed.
     *
     * @param direction the edge that absorbed the fling. Must be passed in: the current
     *                  displacement is 0 here, so its sign says nothing, and guessing from it
     *                  bounced the last page the wrong way.
     * @param velocity  always positive РІР‚вЂќ RecyclerView negates it for the left edge.
     */
    private void onEdgeAbsorb(int direction, int velocity) {
        if (!mEnabled) return;
        if (velocity <= 0) {
            onEdgeRelease();
            return;
        }
        cancelAnimators();
        // Same conversion as the terminal's РІР‚вЂќ both surfaces threshold/cap a gesture identically on
        // any density РІР‚вЂќ and it plays out in TIME: the impulse stretches the band sample by sample at
        // the speed it arrived with, rather than teleporting the page to the peak.
        final ElasticOverdrag.Impact impact =
                ElasticOverdrag.impact(velocity, extentPx(), 0f, density());
        final float base = (direction == DIRECTION_LEFT) ? mLeftRawPx : mRightRawPx;
        startImpact(direction, base, impact);
    }

    /**
     * Play the fly-out half of an impact (the band being stretched). The samples are raw travel
     * integrated in displacement and converted back, so a harder flick flies out faster as well as
     * further; the last sample hands the stretch to {@link #onEdgeRelease()} so the return starts
     * from wherever the fly-out got to.
     */
    private void startImpact(int direction, float baseRaw, @Nullable ElasticOverdrag.Impact impact) {
        cancelAnimators();
        if (impact == null || impact.durationMs <= 0L || !(impact.peakTravelPx() > 0f)) {
            onEdgeRelease();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(impact.durationMs);
        // Linear: the samples ARE the timing. Shared instance РІР‚вЂќ a stateless interpolator is not
        // worth allocating per fling.
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
     * The pager scrolled for real by {@code dx}. Hand the held pull back one pixel of finger
     * travel per pixel of scroll so a pull-then-return stays continuous; without this the page
     * would move by the scroll <em>and</em> keep the over-drag. The payback is applied to the raw
     * accumulator РІР‚вЂќ see below, the domain is the difference between a continuous return and a snap.
     */
    private void bleedIntoScroll(float dx) {
        if (mTranslationPx == 0f) return;
        cancelAnimators();
        // Only the MAGNITUDE of the scroll matters: the bleed can only pull the displacement
        // back toward the boundary, never push it further out.
        //
        // Payback is in the RAW (finger-travel) domain, not the damped on-screen value. With a
        // concave damp curve, subtracting from the damped value pays back at ~2.4x the rate
        // near the boundary: the page moved ~2x the finger's speed on the return, and one
        // large |dx| frame could snap the full cap (20% of width) back with no animation.
        // In the raw domain payback is 1:1: continuous, no jump, no velocity step at bottom-out.
        final float giveBack = Math.abs(dx);
        if (mTranslationPx > 0f) {
            mLeftRawPx = clampRaw(mLeftRawPx - giveBack);
        } else {
            mRightRawPx = clampRaw(mRightRawPx - giveBack);
        }
        // The damped value follows from the raw, so it is re-derived, not decremented: the
        // accumulator stays the single source of truth, and the displacement cannot pass the cap.
        apply();
    }

    // ---- displacement ----

    private void apply() {
        // One width read per frame: damp() would read extentPx() twice, setTranslation() a third.
        final float w = extentPx();
        setTranslation(damp(mLeftRawPx, w) - damp(mRightRawPx, w), w);
    }

    /** {@link #setTranslation(float, float)} with the width read fresh. */
    private void setTranslation(float px) {
        setTranslation(px, extentPx());
    }

    /**
     * The single writer of {@code translationX} РІР‚вЂќ last line of defence. The value is a property of
     * the view hosting every page, so it must always be finite and bounded; anything else is
     * coerced to 0 rather than allowed to poison the transform.
     */
    private void setTranslation(float px, float widthPx) {
        px = coerceTranslation(px, widthPx);
        // Same guard TerminalView.setOverdragRaw() has always had: an unchanged displacement must
        // not dirty the RenderNode (most settles here write a 0 that is already there).
        if (px == mTranslationPx) return;
        mTranslationPx = px;
        mRecyclerView.setTranslationX(px);
    }

    /**
     * The pager's extent along the drag axis РІР‚вЂќ the inner RecyclerView's width. Every scale of the
     * effect is a fraction of it (the cap is 20 %). Read on demand, not cached, so rotation or a
     * split-screen resize reflows the physics instead of keeping a stale pixel value.
     *
     * <p>The pager has no quantum to snap to (unlike the transcript, made of glyph rows), so all
     * of these use the plain {@link ElasticOverdrag} entry points.</p>
     */
    private float extentPx() {
        return Math.max(1f, mRecyclerView.getWidth());
    }

    private float maxPullPx() {
        return ElasticOverdrag.maxPull(extentPx());
    }

    /**
     * The display density, read on demand so a move to another display is picked up. Only the
     * impulse needs it ({@code ElasticOverdrag} Р’В§0b) РІР‚вЂќ the band itself is scale-free.
     */
    private float density() {
        return mRecyclerView.getResources().getDisplayMetrics().density;
    }

    /**
     * The rubber-band curve, straight from the shared model: {@code f(x) = MР’В·sin((РџР‚/2)Р’В·x/L)} with
     * {@code M} = 20 % of the width. {@code f'(0) = 0.42} РІР‚вЂќ a firm band right at the boundary РІР‚вЂќ
     * and {@code f(L)=M, f'(L)=0}: the cap is arrived at rather than chased, so no knee, no wall,
     * and the pull can never exceed it. Exactly the curve the terminal transcript uses, so both
     * surfaces feel like the same material; see {@link ElasticOverdrag}.
     */
    private float damp(float rawPx) {
        return damp(rawPx, extentPx());
    }

    /**
     * {@link #damp(float)} against an extent the caller already has. {@link #apply()} needs the
     * width three times per frame РІР‚вЂќ reading it once keeps {@link #extentPx()} (and the
     * {@code getWidth()} behind it) off the hot path.
     *
     * @param widthPx the pager's extent, read once per frame by {@link #apply()}.
     */
    private float damp(float rawPx, float widthPx) {
        return dampStatic(rawPx, widthPx);
    }

    /** Inverse of {@link #damp(float)}: the raw travel that produces {@code dampedPx}. */
    private float inverseDamp(float dampedPx) {
        return inverseDampStatic(dampedPx, extentPx());
    }

    /** Keep the raw accumulator finite and bounded: positive, never NaN/Infinity, never > cap. */
    private float clampRaw(float rawPx) {
        return clampRawStatic(rawPx, extentPx());
    }

    /** Pure rubber-band curve shared with {@link ElasticHorizontalScrollView}. */
    static float dampStatic(float rawPx, float widthPx) {
        // Rejects NaN as well.
        if (!(rawPx > 0f)) return 0f;
        return ElasticOverdrag.damp(rawPx, widthPx);
    }

    /** Inverse of {@link #dampStatic(float, float)} against {@code extentPx}. */
    static float inverseDampStatic(float dampedPx, float extentPx) {
        if (!(dampedPx > 0f)) return 0f;
        return clampRawStatic(ElasticOverdrag.undamp(dampedPx, extentPx), extentPx);
    }

    /** Positive, finite raw accumulator, capped at the saturation travel. */
    static float clampRawStatic(float rawPx, float extentPx) {
        if (!(rawPx > 0f) || !isFinite(rawPx)) return 0f;
        return ElasticOverdrag.clampRaw(rawPx, extentPx);
    }

    /** Finite check shared with {@link ElasticHorizontalScrollView}. */
    static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    /** Clamp a raw displacement into the finite [-maxPull, +maxPull] band for {@code widthPx}. */
    static float coerceDisplacement(float px, float widthPx) {
        if (!isFinite(px)) px = 0f;
        float max = ElasticOverdrag.maxPull(widthPx);
        return Math.max(-max, Math.min(max, px));
    }

    /** Clamp a signed translation into the finite band (same rule as {@link #coerceDisplacement}). */
    static float coerceTranslation(float px, float widthPx) {
        return coerceDisplacement(px, widthPx);
    }

    // ---- the "never displaced while idle" invariant ----

    /**
     * Guarantee the pager is not left displaced once the gesture is over.
     *
     * <p>The check is <em>posted</em>, not run inline: RecyclerView reaches IDLE while
     * dispatching {@code ACTION_UP}, i.e. before it hands the edge effects {@code onRelease()},
     * so inline would kill the spring-back before it starts. One frame later the spring (if any)
     * is registered and the check stands down. Interrupting the spring or the bleed can cancel
     * animators without writing the final 0, so this is the only safety net.
     *
     * <p>The leftover is <em>animated</em>, not zeroed: writing a clean 0 would snap up to the
     * full cap, the very thing this class must not do. {@link #startSpring(float)} keeps the
     * invariant absolute while arriving smoothly: worst case a missing animation, never a
     * broken pager.
     */
    private void ensureSettled() {
        mRecyclerView.removeCallbacks(mSettleRunnable);
        mRecyclerView.post(mSettleRunnable);
    }

    private void settleNow() {
        if (!mEnabled || mSpring != null || mImpact != null) return;
        if (mTranslationPx != 0f) {
            // Displaced with no animator: the return was cancelled mid-flight (the bleed cancels
            // animators on every scroll frame). Hand the leftover to the spring instead of zeroing
            // it: an instant reset would snap up to the full cap. startSpring() ends at a clean 0.
            mLeftRawPx = 0f;
            mRightRawPx = 0f;
            startSpring(mTranslationPx);
            return;
        }
        // A raw accumulator alone can never be on screen (non-zero raw always damps non-zero).
        if (mLeftRawPx != 0f || mRightRawPx != 0f) reset();
    }

    // ---- spring back ----

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

    /** Stop both the fly-out and the return, leaving the displacement exactly where it is. */
    private void cancelAnimators() {
        cancelImpact();
        cancelSpring();
    }

    private void cancelImpact() {
        ValueAnimator impact = mImpact;
        mImpact = null;
        if (impact == null) return;
        // Unhook before cancelling: cancel() fires onAnimationEnd too, which would release into
        // the spring and fight whatever is taking over.
        impact.removeAllUpdateListeners();
        impact.removeAllListeners();
        impact.cancel();
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
        // The spring drove translationX directly, so the accumulators are stale РІР‚вЂќ re-seed them so
        // a pull interrupting the bounce continues from the position on screen.
        if (mLeftRawPx == 0f && mRightRawPx == 0f && mTranslationPx != 0f) {
            if (mTranslationPx > 0f) mLeftRawPx = inverseDamp(mTranslationPx);
            else mRightRawPx = inverseDamp(-mTranslationPx);
        }
    }

    // ---- the edge-effect spy ----

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
            // RecyclerView normalises the unconsumed delta by the view width; both edges get a
            // POSITIVE deltaDistance for an increasing pull. De-normalise with that width РІР‚вЂќ and
            // bail on a zero-width view, where the framework's own division yields Infinity/NaN.
            int width = mRecyclerView.getWidth();
            if (width <= 0) return;
            onEdgePull(mDirection, deltaDistance * width);
        }

        /** API 31+ path РІР‚вЂќ {@code EdgeEffectCompat} calls this instead of {@link #onPull(float, float)}.
         *  Returning 0 = none of the pull consumed: it goes to the page, not to a stretch. */
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
