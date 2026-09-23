package com.termux.view;

import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;

/**
 * The one and only definition of the elastic ("rubber band") over-drag physics.
 *
 * <p>Two surfaces use it — the vertical scroll of the terminal transcript ({@link TerminalView},
 * displacement in glyph space) and the horizontal session pager ({@code PagerOverscrollController},
 * displacement as {@code translationX}). They used to carry two separate copies of the same maths
 * and drifted apart; both call sites are now pure adapters deciding <em>where</em> the
 * displacement goes, never <em>how</em> it is computed, so a pull feels like the same material on
 * either surface.
 *
 * <h2>Units: the band is scale-free, the finger is not</h2>
 * The band has no intrinsic length: the cap is a fraction of the surface, and the return is a
 * function of time only — displacement needs no density handling. The one quantity that is not
 * scale-free is the finger's <b>speed</b>: a flick is a physical event ({@code v_px = v_dp ·
 * density}), so a px/s constant means something different on every screen. Convention:
 * <b>geometry in whatever unit the surface is measured in, velocity in dp/s</b>.
 * {@link #absorbVelocity(float, float)} is the one place that crosses that boundary; nothing else
 * mentions density.
 *
 * <h2>The model</h2>
 * <ul>
 *   <li><b>Cap:</b> {@code M = 0.2 · extent} ({@link #MAX_FRACTION}), scaled by the surface's own
 *       extent (not a dp constant), so a tablet and a phone pull by the same fraction. Optional
 *       {@code unitPx} quantum snaps the cap to whole glyph rows on the transcript.</li>
 *   <li><b>Pull curve:</b> quarter sine {@code f(x) = M·sin((π/2)·x/L)} — starts straight
 *       (slope {@link #BOUNDARY_SLOPE} at the boundary), arrives flat at the reachable cap (no
 *       knee), and is invertible in closed form ({@link #undamp}) for the bleed path. The raw
 *       accumulator is clamped to {@code L}, which is both the arithmetic guard against NaN and
 *       the reason the return starts moving on the very first pixel back.</li>
 *   <li><b>Return:</b> critically damped step response with the residual removed — provably
 *       monotone, no overshoot, lands exactly on 0 at t=1. A spring, not an ease-out: the content
 *       accelerates toward the boundary, then settles.</li>
 *   <li><b>Fling impulse:</b> momentum enters at its own speed damped by the boundary
 *       ({@code INITIAL_SLOPE·v} — the same proportion a finger meets there). The band is a
 *       pendulum in displacement with the same ω as the return: halving ω doubles the fly-out's
 *       duration <em>and</em> reach together. Simulated with symplectic Euler, peak pinned to
 *       closed form; samples are published as raw travel via {@link #undamp} so callers keep
 *       animating the accumulator.</li>
 * </ul>
 *
 * <p>Design history and rejected candidates: {@code docs/elastic-terminal-overdrag-design.md}.
 */
public final class ElasticOverdrag {

    // ══ TUNING ═══════════════════════════════════════════════════════════════════════════════
    // Every physics number in the system is written here and nowhere else; everything under the
    // DERIVED banner is computed from these. Units: dimensionless where the quantity is a ratio,
    // ms for time, dp/s for speed — nothing here is in px (see the class doc).

    /**
     * The cap as a fraction of the dragged surface's extent: the content can be pulled 20 % of
     * the screen height (transcript) or page width (pager) away from its boundary, however far
     * the finger travels.
     */
    public static final float MAX_FRACTION = 0.2f;

    /**
     * How much of the finger's movement the content follows right at the boundary, i.e.
     * {@code f'(0)} — the one figure chosen by taste; everything else follows from it. 0.42 is
     * firm enough that an over-drag reads as pulling against something, while the 20 % cap stays
     * reachable after ~0.75 of a surface of drag.
     */
    public static final float BOUNDARY_SLOPE = 0.42f;

    /** {@code L / M = π / (2·f'(0))} — follows from {@code f(x) = M·sin((π/2)·x/L)}. */
    private static final float SATURATION_RATIO = (float) (Math.PI / (2d * BOUNDARY_SLOPE));

    /**
     * Finger travel that reaches the cap, as a fraction of the same extent — derived from the
     * two numbers above: {@code L = M · π/(2·BOUNDARY_SLOPE) ≈ 0.748 · E}.
     */
    public static final float SATURATION_FRACTION = MAX_FRACTION * SATURATION_RATIO;

    /**
     * The actual boundary response, {@code f'(0) = π·M/(2L)}. Kept named (rather than inlined)
     * because it is the number to quote when comparing against other platforms; by construction
     * it equals {@link #BOUNDARY_SLOPE}.
     */
    public static final float INITIAL_SLOPE =
            (float) (Math.PI * MAX_FRACTION / (2f * SATURATION_FRACTION));

    /**
     * Spring-back duration, in ms — the one number the whole bounce hangs off (see
     * {@link #bandOmega()}). History: 420 ms read as sluggish and was cut to 140, which turned
     * out to be a twitch (the eye fuses stretch and release into one flinch); 280 separates the
     * halves again. Independent of {@link #BOUNDARY_SLOPE}, which has since returned to 0.42 —
     * the slope decides how far an impulse gets, the duration how long the unload takes.
     */
    public static final long SPRING_DURATION_MS = 280L;

    /**
     * Normalized natural frequency of the return ({@code ω}, with {@code t} ∈ [0,1] mapped onto
     * {@link #SPRING_DURATION_MS}). The motion is ~74 % done one time-constant in and ~92 % by
     * two, so the eye reads "snapped home" well before the animator ends; the rest is a
     * sub-pixel tail so the value lands on the boundary already at rest.
     */
    public static final float SPRING_OMEGA = 8.5f;

    /**
     * Sanity cap on an absorbed fling velocity, <b>in dp/s</b> — the only place the finger's
     * speed is bounded, and dp/s rather than px/s because a px/s cap silently retunes the bounce
     * per device. With the band saturating the extra is bounded anyway; this only guarantees
     * bounded arithmetic for an absurd velocity. (It used to be 8000 dp/s in the transcript and
     * a literal 6000 px/s in the pager — several times apart on any real phone.)
     */
    public static final float MAX_ABSORB_VELOCITY_DP = 8000f;

    /**
     * Velocity, <b>in dp/s</b>, spent before the band sees anything, so the response is
     * continuous at the fling threshold (the platform's own
     * {@code ViewConfiguration#getScaledMinimumFlingVelocity()}, expressed in dp). Lives here
     * because only the transcript used to subtract it — the same flick produced a bounce at the
     * pager's edge and nothing at the transcript's.
     */
    public static final float MIN_ABSORB_VELOCITY_DP = 50f;

    /**
     * Integrator accuracy target {@code ω·dt}: 0.03 keeps the symplectic Euler energy error far
     * below a pixel, is unconditionally stable for this pendulum, and lands the step at ~1 ms for
     * the current {@link #SPRING_DURATION_MS}. Derived (not a literal "1 ms"), so it keeps this
     * guarantee if the spring is retuned.
     */
    private static final float FLYOUT_OMEGA_DT = 0.03f;

    /**
     * How much rope the fly-out loop gets, as a multiple of the longest possible quarter swing
     * (≤ ~1.18 small-amplitude ones at full stretch). Pure insurance so the loop can never run
     * away if the constants are retuned; hitting it would truncate only the timing — the last
     * sample is pinned to the closed-form peak regardless.
     */
    private static final float FLYOUT_STEP_MARGIN = 2f;

    // ══ DERIVED ══════════════════════════════════════════════════════════════════════════════
    // Nothing below this banner may be hand-edited: every value follows from the TUNING block.

    /**
     * The band's natural frequency, in rad/s — the {@code ω} behind both halves of a bounce.
     * {@link #SPRING_OMEGA} is that same frequency normalized to the return's duration, so this
     * is simply the de-normalized form (~30.4 rad/s, a 33 ms time constant).
     */
    public static float bandOmega() {
        return SPRING_OMEGA * 1000f / (float) SPRING_DURATION_MS;
    }

    /** {@code y(1)} — the residual of the plain step response, removed by {@link #SPRING}. */
    private static final float SPRING_RESIDUAL =
            (1f + SPRING_OMEGA) * (float) Math.exp(-SPRING_OMEGA);

    /**
     * The integrator step, in ms: {@code ω·dt = FLYOUT_OMEGA_DT}. Derived, so it follows
     * {@link #SPRING_DURATION_MS} instead of being a literal that has to be revisited by hand.
     */
    public static float flyoutStepMs() {
        return FLYOUT_OMEGA_DT * 1000f / bandOmega();
    }

    /**
     * Worst-case quarter swing as a multiple of the small-amplitude one:
     * {@code K(1/√2)/(π/2) ≈ 1.18} — a property of the pendulum's shape, not a knob.
     */
    private static final float QUARTER_PERIOD_STRETCH = 1.1804f;

    /**
     * Hard bound on fly-out steps: longest quarter swing in steps of {@link #flyoutStepMs()},
     * times {@link #FLYOUT_STEP_MARGIN} (~124 at current constants). Used to be a literal 128
     * corrected by hand whenever {@link #SPRING_DURATION_MS} changed; now it cannot go stale.
     */
    private static int flyoutMaxSteps() {
        final float quarterPeriodMs = 1000f * (float) (Math.PI / 2d) / bandOmega();
        return (int) Math.ceil(FLYOUT_STEP_MARGIN * QUARTER_PERIOD_STRETCH * quarterPeriodMs
                / flyoutStepMs());
    }

    private static final float HALF_PI = (float) (Math.PI / 2d);

    private ElasticOverdrag() { }

    // ── the two scales ─────────────────────────────────────────────────────────────────────

    /** {@link #maxPull(float, float)} without a quantum (the pager: continuous px). */
    public static float maxPull(float extentPx) {
        return maxPull(extentPx, 0f);
    }

    /**
     * The cap, in px: how far the surface can ever be pulled off its boundary. With a quantum,
     * the cap snaps to a whole number of units, so at full stretch the displacement lands on a
     * glyph-row/column boundary (error under half a unit).
     *
     * @param extentPx the surface's extent along the drag axis (view height / pager width).
     * @param unitPx   the surface's quantum (glyph row height for the transcript), or 0 for none.
     */
    public static float maxPull(float extentPx, float unitPx) {
        float m = Math.max(1f, extentPx) * MAX_FRACTION;
        if (isFinite(unitPx) && unitPx > 0f) {
            m = Math.max(1f, (float) Math.round(m / unitPx)) * unitPx;
        }
        return m;
    }

    /** {@link #saturationTravel(float, float)} without a quantum. */
    public static float saturationTravel(float extentPx) {
        return saturationTravel(extentPx, 0f);
    }

    /**
     * The finger travel, in px, that reaches {@link #maxPull(float, float)} — and the bound the
     * raw accumulator is clamped to. Derived from the cap, so it inherits its quantization: a
     * whole number of units of cap means a whole number of units of travel.
     */
    public static float saturationTravel(float extentPx, float unitPx) {
        return maxPull(extentPx, unitPx) * SATURATION_RATIO;
    }

    // ── the rubber band ────────────────────────────────────────────────────────────────────

    /** {@link #damp(float, float, float)} without a quantum. */
    public static float damp(float rawPx, float extentPx) {
        return damp(rawPx, extentPx, 0f);
    }

    /**
     * The pull curve: {@code f(x) = M·sin((π/2)·x/L)}, sign-preserving and clamped to the cap.
     *
     * <p>Finite and bounded for every input ({@code x} clamped to {@code [0,L]}, {@code sin ≤ 1}).
     * A NaN input yields 0 — the caller's {@code setTranslationX}/{@code glyphYOffset} must never
     * see a non-finite value (an invalid RenderNode transform stops the whole subtree drawing).</p>
     *
     * @param rawPx    accumulated finger travel past the boundary; the sign is the pull's side.
     * @param extentPx the surface's extent along the drag axis.
     * @param unitPx   the surface's quantum (glyph row height for the transcript), or 0 for none.
     * @return the displacement in px, same sign as {@code rawPx}.
     */
    public static float damp(float rawPx, float extentPx, float unitPx) {
        if (!isFinite(rawPx)) return 0f;
        float a = Math.abs(rawPx);
        if (!(a > 0f)) return 0f;              // also rejects ±0 and NaN
        // One maxPull(): saturationTravel() would evaluate it a second time.
        final float m = maxPull(extentPx, unitPx);
        final float l = m * SATURATION_RATIO;
        a = Math.min(a, l);
        final float d = m * (float) Math.sin(HALF_PI * (a / l));
        return rawPx < 0f ? -d : d;
    }

    /** {@link #undamp(float, float, float)} without a quantum. */
    public static float undamp(float dampedPx, float extentPx) {
        return undamp(dampedPx, extentPx, 0f);
    }

    /**
     * The inverse of {@link #damp(float, float, float)}: the raw travel that produces
     * {@code dampedPx}. Used to re-seed a held pull from the displacement on screen (the pager's
     * spring-cancel path) and to publish the fly-out of {@link #impact} — simulated in
     * displacement — back into the raw accumulator both call sites animate.
     * {@code damp(undamp(y)) == y} exactly, so nothing is lost in the round trip.
     */
    public static float undamp(float dampedPx, float extentPx, float unitPx) {
        if (!isFinite(dampedPx)) return 0f;
        float a = Math.abs(dampedPx);
        if (!(a > 0f)) return 0f;
        final float m = maxPull(extentPx, unitPx);
        a = Math.min(a, m);
        // One maxPull(): saturationTravel() would evaluate it a second time.
        final float raw = (2f * (m * SATURATION_RATIO) / (float) Math.PI)
                * (float) Math.asin(Math.min(1f, a / m));
        return dampedPx < 0f ? -raw : raw;
    }

    /** {@link #clampRaw(float, float, float)} without a quantum. */
    public static float clampRaw(float rawPx, float extentPx) {
        return clampRaw(rawPx, extentPx, 0f);
    }

    /**
     * Clamp the raw accumulator into {@code ±saturationTravel}: the arithmetical guard (a
     * bounded accumulator can never make {@link #damp} produce a non-finite value) and what keeps
     * the return free of a dead zone (nothing to pay back past L).
     */
    public static float clampRaw(float rawPx, float extentPx, float unitPx) {
        if (!isFinite(rawPx)) return 0f;
        final float l = maxPull(extentPx, unitPx) * SATURATION_RATIO;
        return Math.max(-l, Math.min(l, rawPx));
    }

    /**
     * The impact speed, <b>in dp/s</b>, that just bottoms the band out (the flick that reaches
     * the full cap); slower reaches {@code INITIAL_SLOPE·v/ω}, faster is clamped anyway. Useful
     * as the scale for reasoning about {@link #MAX_ABSORB_VELOCITY_DP}: at the current slope it
     * is ≈13.0 · extent_dp, so the 8000 dp/s cap loads a pager all the way to its cap and a
     * taller transcript to ~77 % of its own — a hard flick ends up deep in the band on both.
     *
     * @param extentDp the surface's extent along the drag axis, <b>in dp</b>.
     */
    public static float saturationVelocityDp(float extentDp) {
        final float m = Math.max(1f, extentDp) * MAX_FRACTION;
        return (float) (Math.sqrt(2d) * bandOmega() * m / (BOUNDARY_SLOPE * Math.PI / 2d));
    }

    // ── the impulse: the only density-dependent step in the system ──────────────────────────

    /**
     * Turn a raw fling velocity into the impulse the band receives, <b>in dp/s</b> — the single
     * crossing point between the two unit systems: velocity arrives in px/s (what
     * {@code VelocityTracker} reports) and leaves in dp/s so the same physical gesture means the
     * same thing on every screen. Both surfaces call it, so both thresholds and both caps are the
     * same number, and the {@link #MIN_ABSORB_VELOCITY_DP} subtraction is shared.
     *
     * @param velocityPxPerSecond raw fling speed from the platform (magnitude; sign irrelevant).
     * @param density             the display's {@code DisplayMetrics#density}.
     * @return the impulse speed in dp/s, clamped to {@code [0, MAX_ABSORB_VELOCITY_DP]}.
     */
    public static float absorbVelocity(float velocityPxPerSecond, float density) {
        if (!isFinite(velocityPxPerSecond) || !isFinite(density) || !(density > 0f)) return 0f;
        float v = Math.abs(velocityPxPerSecond) / density;
        v = Math.min(v, MAX_ABSORB_VELOCITY_DP);
        v = Math.max(0f, v - MIN_ABSORB_VELOCITY_DP);
        return v;
    }

    // ── the fly-out: the band being stretched, in time ─────────────────────────────────────

    /**
     * The fly-out half of an impact: how the band is stretched, sample by sample, by an impulse
     * arriving at {@code v} px/s. Starts at the content's incoming speed and is decelerated by
     * the band alone, so a harder flick flies out faster as well as further. Samples are
     * published as <b>raw travel</b> even though the integration runs in displacement
     * ({@code damp(undamp(y)) == y}), so the curve that comes back is bit-for-bit the one that
     * was integrated. See the class doc.
     */
    public static final class Impact {

        /**
         * Raw travel per sample; {@code travelPx[0] == 0}, and the last of the {@code count}
         * entries is the peak. Backing array is allocated once at the step bound and never
         * copied — {@code count} is the length that matters.
         */
        private final float[] travelPx;
        private final int count;

        /** How long the fly-out takes, in ms. */
        public final long durationMs;

        private Impact(float[] travelPx, int count, long durationMs) {
            this.travelPx = travelPx;
            this.count = count;
            this.durationMs = durationMs;
        }

        /** The peak raw travel — the displacement at the turning point, converted back. */
        public float peakTravelPx() {
            return travelPx[count - 1];
        }

        /** Raw travel at {@code progress ∈ [0,1]}, linearly interpolated between samples. */
        public float travelAt(float progress) {
            if (!isFinite(progress) || progress <= 0f) return 0f;
            if (progress >= 1f) return travelPx[count - 1];
            final float pos = progress * (count - 1);
            final int i = (int) pos;
            final int j = Math.min(count - 1, i + 1);
            return travelPx[i] + (travelPx[j] - travelPx[i]) * (pos - i);
        }
    }

    /**
     * Simulate the fly-out of an impact — see {@link Impact}. Play the samples forward once
     * ({@code travelAt} linearly in time), then hand the peak to {@link #SPRING} for the return.
     * Deliberately no fling friction and no row-height reference: the band is three orders of
     * magnitude stiffer than the platform's fling deceleration, so friction is noise here, and
     * the peak is a property of the band's stiffness, not of the content.
     *
     * @param velocityPxPerSecond impact speed along the drag axis (magnitude); converted to dp/s
     *                            by {@link #absorbVelocity} first, so the same physical gesture
     *                            loads the band identically on every screen.
     * @param extentPx            the surface's extent along the drag axis.
     * @param unitPx              the surface's quantum (glyph row height), or 0 for none.
     * @param density             the display's {@code DisplayMetrics#density}.
     */
    public static Impact impact(float velocityPxPerSecond, float extentPx, float unitPx,
                                float density) {
        // The impulse is the only density-dependent quantity (class doc): measured in dp/s so a
        // physical flick means the same thing everywhere, then brought back into the surface's
        // own unit for the integration below. Extent/quantum/output are unit-agnostic.
        final float v = absorbVelocity(velocityPxPerSecond, density) * density;
        final float m = maxPull(extentPx, unitPx);
        if (!(v > 0f)) return new Impact(new float[] {0f}, 1, 0L);

        // The boundary resists an impulse exactly as hard as a finger: right at it the pull curve
        // has slope INITIAL_SLOPE, so content enters the band at INITIAL_SLOPE·v and the
        // remaining ~58 % of the impulse is spent against the boundary. A flick twice as fast
        // still flies out twice as fast — the proportion is what is fixed. This is NOT the old
        // "impulse through the pull curve" bug (that integrated in raw-travel space where the
        // effective stiffness is ∝ 1/L², stretching the fly-out to ~460 ms).
        final float v0 = INITIAL_SLOPE * v;

        final float omega = bandOmega();
        final float kappa = HALF_PI / m;                 // rad per px of displacement
        // U(y) = A·(1 − cos κy) with A = (ω/κ)², i.e. U''(0) = ω²: the pendulum's small-amplitude
        // frequency is exactly the one the return spring runs at.
        final float a = (omega / kappa) * (omega / kappa);

        // Turning point, closed form: ½v0² = U(y_peak) ⇒ 1 − cos(κ·y_peak) = (v0·κ/ω)²/2.
        final float sigma = v0 * kappa / omega;
        final float peak = Math.min(m, (float) Math.acos(Math.max(-1f, 1f - sigma * sigma / 2f))
                / kappa);

        // The scales the loop needs, computed once (undamp() would re-evaluate maxPull() twice
        // per sample — up to ~250 redundant Math.round calls per impact).
        final float invScale = 2f * (m * SATURATION_RATIO) / (float) Math.PI;

        final float stepMs = flyoutStepMs();
        final float dt = stepMs / 1000f;
        final int maxSteps = flyoutMaxSteps();
        final float[] samples = new float[maxSteps + 2];   // + the pinned peak
        int n = 0;
        samples[n++] = 0f;
        float y = 0f;
        float vy = v0;
        for (int i = 0; i < maxSteps; i++) {
            // Symplectic Euler: velocity first, then position. Energy-conserving to O(dt) and
            // unconditionally stable for this step size, so the bounce cannot gain energy.
            vy -= a * kappa * (float) Math.sin(kappa * y) * dt;
            y += vy * dt;
            if (y >= m || vy <= 0f) break;               // band full, or turning point
            samples[n++] = invScale * (float) Math.asin(Math.min(1f, y / m));
        }
        // Pin the last sample to the closed-form peak: the integrator decides *when*, the algebra
        // decides *how far* (they agree to well under a pixel).
        samples[n++] = invScale * (float) Math.asin(Math.min(1f, peak / m));
        // Duration is the time actually simulated, not an assumed "1 ms per step".
        return new Impact(samples, n, Math.round((n - 1) * stepMs));
    }

    // ── the return ─────────────────────────────────────────────────────────────────────────

    /**
     * Spring-back interpolator: critically damped return that lands exactly on the boundary.
     *
     * <p>Used with {@code ValueAnimator.ofFloat(from, 0f)}, so the animated value is
     * {@code from · (1 − getInterpolation(t)) = from · Y(t)} with {@code Y(0)=1}, {@code Y(1)=0},
     * {@code Y' < 0} everywhere — monotone, no overshoot, no ringing, and no residual to snap
     * away in the final frame.</p>
     */
    public static final Interpolator SPRING = input -> {
        final float w = SPRING_OMEGA;
        final float y = (1f + w * input) * (float) Math.exp(-w * input);
        // Y = y - y(1)·t: removes the residual while keeping the response strictly monotone.
        final float settled = y - SPRING_RESIDUAL * input;
        return 1f - settled;
    };

    /**
     * Shared linear interpolator for the fly-out — the samples <em>are</em> the timing, so any
     * easing would re-shape the motion the integration just produced. Stateless, hence safe to
     * share; both surfaces used to allocate a fresh one per impact.
     */
    public static final Interpolator LINEAR = new LinearInterpolator();

    static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }
}
