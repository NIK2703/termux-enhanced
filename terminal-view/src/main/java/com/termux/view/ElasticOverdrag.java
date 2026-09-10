package com.termux.view;

import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;

/**
 * The one and only definition of the elastic ("rubber band") over-drag physics.
 *
 * <p>Two surfaces use it — the vertical scroll of the terminal transcript
 * ({@link TerminalView}, displacement applied in glyph space) and the horizontal session pager
 * ({@code PagerOverscrollController}, displacement applied as a {@code translationX}). They used
 * to carry two separate copies of the same maths, which is how they drifted apart. This class is
 * the shared source: both call sites are pure adapters that decide <em>where</em> the resulting
 * displacement goes, never <em>how</em> it is computed. Any change here is felt identically in
 * both places, which is the whole point — a pull must feel like the same material whether it is
 * the transcript or a session page.</p>
 *
 * <h2>0. Where the numbers live</h2>
 * Every physics number in the system is written exactly once, in the <b>TUNING</b> block below.
 * Nothing downstream is hand-written: the saturation travel, the boundary slope, the spring
 * residual, the band frequency, the integrator step and the loop bound are all <em>derived</em>
 * from those knobs, so retuning one of them can never leave a stale constant behind. (The bound
 * on the fly-out loop used to be a literal {@code 128} with a comment asking whoever changed the
 * spring duration to remember to update it by hand — it is now computed from that duration.)
 *
 * <h2>0b. Units: the band is scale-free, the finger is not</h2>
 * The band has no intrinsic length: the cap is a fraction of the surface, the finger travel that
 * reaches it is a multiple of that cap, and the return is a function of time only. Feed
 * {@link #damp(float, float, float)} millimetres and it answers in millimetres. So the
 * displacement channel needs <em>no</em> density handling — converting px to dp and back cancels
 * exactly, and pretending otherwise would only add rounding.
 *
 * <p>The one quantity that is <em>not</em> scale-free is the finger's <b>speed</b>. A flick is a
 * physical event: the same gesture reports {@code v_px = v_dp · density} px/s, so a px/s constant
 * means something different on every screen. That is exactly the bug this layout fixes — the
 * pager clamped the impulse at a literal {@code 6000 px/s} (≈2286 dp/s on a 420 dpi phone,
 * ≈3000 dp/s on a 320 dpi one) while the transcript clamped it at {@code 8000 dp/s}, so the two
 * surfaces answered the same gesture differently and the pager's answer drifted with the device.
 *
 * <p>So the single unit convention is: <b>geometry in whatever unit the surface is measured in,
 * velocity in dp/s</b>. {@link #absorbVelocity(float, float)} is the one place that crosses that
 * boundary, and {@link #impact(float, float, float, float)} takes the density so it can bring the
 * impulse back into the surface's own unit before simulating. Nothing else in the system mentions
 * density.</p>
 *
 * <h2>1. The cap: 20 % of the surface, measured in its own units</h2>
 * Everything scales with the surface's own extent along the drag axis ({@code extentPx} — the
 * view height for the transcript, the pager width for the sessions), not with a dp constant:
 * <pre>
 *   M = maxPull(E)          = 0.2 · E   ← the cap, i.e. how far it can ever be pulled
 *   L = saturationTravel(E) ≈ 0.75 · E  ← the finger travel that reaches the cap
 * </pre>
 * A proportional cap is what makes the gesture read as "this surface is elastic" rather than
 * "there is a 56 dp spring somewhere": a tablet and a phone pull by the same fraction of their
 * own screen, and a landscape pager pulls further than a portrait one, because there is more of
 * it to pull.
 *
 * <p><b>The quantum.</b> Every method takes an optional {@code unitPx}: the size of the smallest
 * thing the surface is made of. The transcript passes its glyph row height, so the cap becomes a
 * <em>whole number of rows</em> ({@code round(0.2 · visibleRows) · rowHeight} instead of
 * {@code 0.2 · height}); the pager has none and passes 0, which means "no quantum". It matters
 * because of where the displacement ends up: at full stretch the transcript is shifted by an
 * exact number of glyph rows, so the extreme position lands on a row boundary instead of cutting
 * a row in half. Doing the arithmetic in the surface's own unit and converting once, at the end,
 * is what "correctly convert it" means; mixing the two is how a 20 % cap turns into 17 % of
 * usable travel.</p>
 *
 * <h2>2. The pull curve: a quarter sine, with a firm boundary</h2>
 * <pre>
 *   f(x) = M · sin( (π/2) · x / L ),   0 ≤ x ≤ L
 * </pre>
 * Three properties make this the nicest of the candidates (see
 * {@code docs/elastic-terminal-overdrag-design.md} §4 for the ones that lost):
 * <ul>
 *   <li><b>It starts straight.</b> {@code f''(0) = 0}, so right at the boundary the response is
 *       <em>exactly</em> linear — no mush, no dead millimetre at the start of the gesture. The
 *       slope there is {@link #BOUNDARY_SLOPE} = 0.42: the content follows the finger at 42 %.
 *       It is a middle course — enough that the band is felt from the very first pixel, so the
 *       gesture reads as "pulling against something" rather than as "the content simply
 *       continues", but not so much that the cap becomes unreachable in practice.</li>
 *   <li><b>It arrives at the cap instead of chasing it.</b> {@code f'(L) = 0}: the curve is
 *       already flat where it meets the cap, so a finger that keeps going simply stops being
 *       answered — there is no knee, no wall, no sudden firming up. (A hyperbola only ever
 *       <em>approaches</em> its cap, so a "max pull of 20 %" would in practice have been a max
 *       pull of ~12 %; here the stated cap is reachable, and it is reached smoothly.)</li>
 *   <li><b>It is invertible in closed form</b> ({@link #undamp(float, float, float)}), which the
 *       bleed path needs to re-seed a held pull from the displacement on screen.</li>
 * </ul>
 *
 * <p>{@code M} is the requirement and {@code BOUNDARY_SLOPE} is the taste; {@code L} is
 * <em>derived</em> from both ({@code L = π·M / (2·BOUNDARY_SLOPE)}) so the two can never drift
 * apart. At this slope {@code L} comes out at ~0.75 of a surface of finger travel: the cap is a
 * firm but genuinely reachable stop, about three quarters of a screen of dragging away.</p>
 *
 * <p>The raw accumulator is clamped to {@code L} ({@link #clampRaw(float, float, float)}), not to
 * some large safety constant. That is both the arithmetic guard (everything is bounded by
 * construction, so no NaN can be produced) and a feel fix: a finger that travels past {@code L}
 * adds nothing, but it also has nothing to <em>pay back</em>, so the return starts moving on the
 * very first pixel back instead of having a dead zone proportional to how far past the cap the
 * user went.</p>
 *
 * <h2>3. The return: critically damped, and it actually lands</h2>
 * <pre>
 *   y(t) = (1 + ωt)·e^(−ωt)             step response of a critically damped oscillator
 *   Y(t) = y(t) − y(1)·t                … with the residual removed
 * </pre>
 * The animated value is {@code from · Y(t)}: {@code Y(0)=1}, {@code Y(1)=0} <em>exactly</em>.
 *
 * <p><b>Critical damping (ζ = 1)</b> is the fastest return that does not oscillate, and combined
 * with the residual removal it is <b>provably monotonic</b>: {@code Y'(t) = −ω²t·e^(−ωt) − y(1)},
 * both terms negative for all {@code t ∈ [0,1]}. No overshoot, no second swing, no ringing —
 * the content crosses the boundary once and stops there, which is the whole request. (The old
 * ζ = 0.65 overshot by 6.8 % of the amplitude; harmless at a 56 dp cap, a visible 15 px flinch
 * once the cap became a third of the screen.)</p>
 *
 * <p>It still feels like a spring and not like an ease-out because it is one: released from rest,
 * the content <em>accelerates</em> toward the boundary and only then settles. A bezier ease-out
 * does the opposite — it is fastest at the very first frame, which is why it reads as "sliding
 * away" rather than "snapping home".</p>
 *
 * <p><b>Why the residual is removed.</b> Plain {@code y(t)} is still ~0.2 % away from zero at
 * {@code t=1} (0.4 px on a 220 px pull — under a pixel, but still a discontinuity, and still at
 * the one moment the eye is most sensitive to it). Subtracting {@code y(1)·t} spreads that over
 * the whole return (a drift of well under 1 % of the amplitude), so the motion reaches the
 * boundary already at rest: no snap, no correction frame. It also makes
 * {@code onAnimationEnd}'s "write a clean 0" a genuine no-op instead of a fix-up.</p>
 *
 * <h2>4. Fling into the boundary: the impulse is momentum, and it enters at its own speed</h2>
 * A flick is not a finger, and the previous version of this class made the mistake of treating it
 * as one. Two things decide what the bounce looks like, and both of them were wrong:
 *
 * <ul>
 *   <li><b>The speed is the incoming speed, damped by the boundary.</b> The content is already
 *       moving at {@code v} px/s when it reaches the boundary, and it enters the band at
 *       {@code ẏ(0) = INITIAL_SLOPE · v} — the impulse scaled by exactly the resistance a finger
 *       meets at that same boundary (§2), so the two channels of over-drag answer in the same
 *       proportion. What matters is that the factor is a <em>constant</em>: a flick twice as fast
 *       still flies out twice as fast, just as a finger dragged twice as far pulls twice as far.
 *       (The old model also multiplied by {@code BOUNDARY_SLOPE}, but then integrated in
 *       raw-travel space, where the effective stiffness is {@code ∝ 1/L²}; the amplitude came
 *       from elsewhere, so the same distance took ~460 ms — the "animation got three times
 *       slower". Timing and reach are independent here, see below.)</li>
 *   <li><b>The distance is the band's business.</b> How far {@code v} gets before the band has
 *       absorbed it is decided by the band's stiffness alone — and by the resistance at the
 *       boundary, since the same slope that damps the entry speed also sizes the cap's reach. A
 *       flick therefore flies out roughly as far as a finger would have to drag to load the band
 *       the same amount. Under the old model the peak was anchored to a fraction of the cap
 *       regardless of the resistance, so stiffening the band left the distance untouched and only
 *       stretched the time — the worst of both worlds.</li>
 * </ul>
 *
 * <p>Concretely the band is a pendulum <b>in the displacement</b> — in the space the eye actually
 * measures, not in the finger's raw travel, and it is the <em>same</em> ω the return runs at:
 * a slower return is a softer band, and a softer band is stretched further by the same flick.
 * Halving ω therefore doubles the fly-out's duration <em>and</em> its reach — which is not a side
 * effect to be trimmed away but the trade itself: the flick still arrives at {@code v}, it simply
 * takes twice as long to be absorbed. (Holding the reach fixed while doubling the time would mean
 * the content enters the band at half the speed it actually had, i.e. exactly the lie §4 exists
 * to avoid.)</p>
 *
 * <pre>
 *   U(y) = A·(1 − cos κy),   κ = π/(2M),   A = (ω/κ)²
 *   ÿ    = −U'(y) = −A·κ·sin(κy)
 * </pre>
 * {@code A} is fixed by the requirement that the small-amplitude frequency is exactly {@code ω}
 * — the very natural frequency the return spring of §3 runs at. One band, one stiffness, two
 * halves of one bounce; the flick loads it and the spring unloads it, and they meet at rest at
 * the turning point. The turning point itself comes out in closed form from
 * {@code ½v² = U(y_peak)}:
 * <pre>
 *   y_peak = acos(1 − (v·κ/ω)² / 2) / κ      clamped to M
 * </pre>
     * which is {@code INITIAL_SLOPE·v/ω} for every ordinary flick and saturates at the cap for a
     * hard one. So, to within the last few per cent, <b>the fly-out covers one time-constant's worth
     * of travel at the speed the boundary let through</b>. At {@code ω = 30.4 rad/s} on a 1080 dp
     * surface (cap 216 dp — a 2835 px wide view at 420 dpi): a 3000 dp/s flick arrives, enters at
     * ~1260 dp/s and reaches ~42 dp; a hard 8000 dp/s one enters at ~3360 dp/s and reaches
     * ~114 dp; past ~14 000 dp/s the band simply bottoms out at the cap. It takes ~52 ms, up to
     * ~61 ms at full amplitude, because a pendulum's quarter period barely depends on amplitude.
     * Against the ~200 px over ~460 ms this class used to produce, that is both closer to home
     * and an order of magnitude quicker. (The band is scale-free, so these hold unchanged in px;
     * only the impulse needed to reach them is density-dependent.)</p>
 *
 * <p>The shape is integrated with symplectic Euler at {@link #flyoutStepMs()}
 * ({@code ω·dt ≈ 0.03}, so the energy
 * error stays far below a pixel and the step is unconditionally stable), and the last sample is
 * pinned to the closed form above — the integrator owns the timing, the algebra owns the
 * amplitude. Because the result is a <em>displacement</em> while the callers animate the raw
 * accumulator, the samples are pushed back through {@link #undamp(float, float, float)} on the
 * way out: {@code damp(undamp(y)) == y} exactly, so what is played is still this curve, and the
 * "raw is the single source of truth" invariant of both call sites is untouched.</p>
 */
public final class ElasticOverdrag {

    // ══ TUNING ═══════════════════════════════════════════════════════════════════════════════
    //
    // Every physics number in the system is written here and nowhere else. Everything below the
    // matching DERIVED banner is computed from these, so retuning a knob cannot leave a stale
    // constant behind.
    //
    // Units: dimensionless where the quantity is a ratio, ms for time, dp/s for speed. Nothing
    // here is in px — see §0b.

    /**
     * The cap, as a fraction of the dragged surface's extent: the content can be pulled 20 % of
     * the screen height (transcript) or 20 % of the page width (session pager) away from its
     * boundary — and no further, however far the finger travels.
     */
    public static final float MAX_FRACTION = 0.2f;

    /**
     * How much of the finger's movement the content follows right at the boundary, i.e.
     * {@code f'(0)}. This is the one figure that is actually chosen by taste — everything else
     * follows from it. 0.42 is a firm band: from the very first pixel the content gives at
     * appreciably less than half the finger's rate, so an over-drag reads as pulling against
     * something that is genuinely resisting, while the cap (20 % of the surface) stays within
     * reach — it is met after ~0.75 of a surface of drag.
     */
    public static final float BOUNDARY_SLOPE = 0.42f;

    /** {@code L / M = π / (2·f'(0))} — follows from {@code f(x) = M·sin((π/2)·x/L)}, see §2. */
    private static final float SATURATION_RATIO = (float) (Math.PI / (2d * BOUNDARY_SLOPE));

    /**
     * Finger travel that reaches the cap, as a fraction of the same extent — <em>derived</em>
     * from the two numbers above rather than picked by hand:
     * {@code L = M · π/(2·BOUNDARY_SLOPE) ≈ 0.748 · E}.
     */
    public static final float SATURATION_FRACTION = MAX_FRACTION * SATURATION_RATIO;

    /**
     * The actual boundary response, {@code f'(0) = π·M/(2L)}. Kept as a named constant (rather
     * than inlined) because it is the number to quote when comparing against other platforms;
     * by construction it equals {@link #BOUNDARY_SLOPE}.
     */
    public static final float INITIAL_SLOPE =
            (float) (Math.PI * MAX_FRACTION / (2f * SATURATION_FRACTION));

    /**
     * Spring-back duration, in ms. The one number the whole bounce hangs off — see
     * {@link #bandOmega()}: the return takes this long, and the fly-out of §4 is a quarter swing
     * of the same oscillator, so it scales with it.
     *
     * <p>History: at the original slope (0.42) the return took 420 ms and read as sluggish, so it
     * was cut to 140 when the band was stiffened — but on a real screen 140 ms turned out to be a
     * twitch: short enough that the eye fuses the stretch and its release into a single flinch.
     * 280 ms separates the two halves again. Note that the slope has since been brought back to
     * 0.42 (see {@link #BOUNDARY_SLOPE}) while this stayed at 280: the two are independent knobs
     * — the slope decides how far an impulse gets, the duration how long the unload takes — so
     * the old 420 ms pairing does not return with it.</p>
     */
    public static final long SPRING_DURATION_MS = 280L;

    /**
     * Normalized natural frequency of the return ({@code ω}, with {@code t} ∈ [0,1] mapped onto
     * {@link #SPRING_DURATION_MS}). The motion is ~74 % done one time-constant in and ~92 % done
     * by two, so the eye reads the return as "snapped home" well before the animator ends; the
     * rest of the duration is a sub-pixel tail that exists only so the value lands on the
     * boundary already at rest.
     */
    public static final float SPRING_OMEGA = 8.5f;

    /**
     * Sanity cap on an absorbed fling velocity, <b>in dp/s</b> — the only place the finger's speed
     * is bounded, and it is dp/s rather than px/s because a flick is a physical event: the same
     * gesture reports {@code density} times more px/s on a denser screen, so a px/s cap silently
     * retunes the bounce per device. With the band saturating (see
     * {@link #saturationVelocityDp(float)}) the extra is bounded anyway — this only guarantees
     * bounded arithmetic for an absurd velocity.
     *
     * <p>It used to be two different numbers: 8000 dp/s in the transcript and a literal
     * 6000 px/s in the pager, i.e. several times apart on any real phone.</p>
     */
    public static final float MAX_ABSORB_VELOCITY_DP = 8000f;

    /**
     * Velocity, <b>in dp/s</b>, that is spent before the band sees anything: a flick that only
     * just qualifies produces a pull of ~0 instead of stepping straight to a visible bounce, so
     * the response is continuous at the fling threshold. This is the platform's own threshold
     * ({@code ViewConfiguration#getScaledMinimumFlingVelocity()} is 50 dp/s scaled by density),
     * expressed in dp so that it is one number everywhere.
     *
     * <p>It lives here rather than at the call sites because only the transcript used to subtract
     * it: the same flick produced a bounce at the pager's edge and nothing at the transcript's.</p>
     */
    public static final float MIN_ABSORB_VELOCITY_DP = 50f;

    /**
     * Integrator accuracy target: {@code ω·dt}. 0.03 keeps the symplectic Euler energy error far
     * below a pixel and is unconditionally stable for this pendulum, while landing the step at
     * ~1 ms for the current {@link #SPRING_DURATION_MS}. The step is <em>derived</em> from this
     * and {@link #bandOmega()} rather than written as "1 ms", so it keeps its accuracy guarantee
     * if the spring is ever retuned.
     */
    private static final float FLYOUT_OMEGA_DT = 0.03f;

    /**
     * How much rope the fly-out loop gets, as a multiple of the longest possible quarter swing.
     * The pendulum's quarter period grows with amplitude, but only up to {@code K(1/√2)/(π/2)}
     * ≈ 1.18 small-amplitude ones at full stretch; this margin is pure insurance so the loop can
     * never run away if the constants above are retuned. Hitting it would truncate nothing but
     * the timing — the last sample is pinned to the closed-form peak regardless.
     */
    private static final float FLYOUT_STEP_MARGIN = 2f;

    // ══ DERIVED ══════════════════════════════════════════════════════════════════════════════
    // Nothing below this banner may be hand-edited: every value follows from the TUNING block.

    /**
     * The band's natural frequency, in rad/s: the {@code ω} behind both halves of a bounce.
     * {@code SPRING_OMEGA} is that same frequency <em>normalized</em> to the return's duration
     * ({@code ω · SPRING_DURATION_MS/1000 = SPRING_OMEGA}), so this is simply the de-normalized
     * form — the return runs {@code SPRING_OMEGA} time-constants, and the fly-out of §4 is a
     * quarter swing of the very same oscillator. ~30.4 rad/s, i.e. a 33 ms time constant.
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
     * The worst-case quarter swing, as a multiple of the small-amplitude one:
     * {@code K(1/√2)/(π/2) ≈ 1.18}. A property of the pendulum's shape (which is fixed by
     * {@code M} and {@code ω}), not a knob.
     */
    private static final float QUARTER_PERIOD_STRETCH = 1.1804f;

    /**
     * Hard bound on the number of fly-out steps: the longest quarter swing the pendulum can
     * produce, in steps of {@link #flyoutStepMs()}, times {@link #FLYOUT_STEP_MARGIN}. With the
     * current constants it lands at ~124 for a ~61 ms worst case. It used to be a literal 128
     * that had to be corrected by hand whenever {@link #SPRING_DURATION_MS} changed; now it
     * cannot go stale.
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
     * The cap, in px: how far the surface can ever be pulled off its boundary.
     *
     * <p>With a quantum, the cap is snapped to a whole number of units, so at full stretch the
     * surface is displaced by an exact number of glyph rows / columns instead of by whatever
     * fraction of one {@code extentPx} happens to be. The error is under half a unit
     * (< 2 % of the cap for a typical font), and it buys a clean extreme position.</p>
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
     * <p>Finite and bounded for every input: {@code L ≥ 1}, {@code x} is clamped into {@code [0,L]}
     * and {@code sin ≤ 1}, so the result is always within {@code ±M}. A NaN input yields 0 —
     * the caller's {@code setTranslationX}/{@code glyphYOffset} must never see a non-finite value
     * (an invalid RenderNode transform silently stops drawing the whole subtree).</p>
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
     * {@code dampedPx}. Used to re-seed a held pull from the displacement currently on screen
     * (the pager's bleed path), and to publish the fly-out of {@link #impact(float, float, float)}
     * — which is simulated in displacement — back into the raw accumulator both call sites
     * animate. {@code damp(undamp(y)) == y} exactly, so nothing is lost in the round trip.
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
     * Clamp the raw accumulator into {@code ±saturationTravel}. This is the arithmetical guard
     * (a bounded accumulator can never make {@link #damp(float, float, float)} produce a
     * non-finite value) and it is also what keeps the return free of a dead zone — see §2.
     */
    public static float clampRaw(float rawPx, float extentPx, float unitPx) {
        if (!isFinite(rawPx)) return 0f;
        final float l = maxPull(extentPx, unitPx) * SATURATION_RATIO;
        return Math.max(-l, Math.min(l, rawPx));
    }

    /**
     * The impact speed, <b>in dp/s</b>, that just bottoms the band out — i.e. the flick that
     * reaches the full cap. Anything slower reaches {@code INITIAL_SLOPE·v/ω}; anything faster is
     * clamped to the cap anyway.
     *
     * <p>Useful as the scale for reasoning about {@link #MAX_ABSORB_VELOCITY_DP}: it is
     * {@code √2·ω·M/(BOUNDARY_SLOPE·π/2)} ≈ 13.0 · extent_dp at the current slope, so a 411 dp
     * wide pager bottoms out at ~5350 dp/s while a 762 dp tall transcript — the taller surface,
     * hence the softer band — needs ~9920 dp/s. The 8000 dp/s cap therefore loads the pager all
     * the way to its cap and the transcript to ~77 % of its own: a hard flick ends up deep in the
     * band on both, which is what "the same material" actually requires.</p>
     *
     * @param extentDp the surface's extent along the drag axis, <b>in dp</b>.
     */
    public static float saturationVelocityDp(float extentDp) {
        final float m = Math.max(1f, extentDp) * MAX_FRACTION;
        return (float) (Math.sqrt(2d) * bandOmega() * m / (BOUNDARY_SLOPE * Math.PI / 2d));
    }

    // ── the impulse: the only density-dependent step in the system ──────────────────────────

    /**
     * Turn a raw fling velocity into the impulse the band actually receives, <b>in dp/s</b>.
     *
     * <p>This is the single crossing point between the two unit systems of §0b: velocity arrives
     * in px/s because that is what {@code VelocityTracker} and {@code RecyclerView} report, and
     * it leaves in dp/s because that is what makes a physical gesture mean the same thing on
     * every screen. Both surfaces call it, so both thresholds and both caps are the same number
     * — and the fling-threshold subtraction ({@link #MIN_ABSORB_VELOCITY_DP}) that only the
     * transcript used to apply is now part of the shared model.</p>
     *
     * @param velocityPxPerSecond raw fling speed from the platform (magnitude; sign irrelevant).
     * @param density             the display's {@code DisplayMetrics#density}.
     * @return the impulse speed in dp/s, already clamped to {@code [0, MAX_ABSORB_VELOCITY_DP]}.
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
     * that reaches the boundary at {@code v} px/s.
     *
     * <p>The motion starts at {@code v} — the speed the content already had — and is decelerated
     * by the band alone, so a harder flick flies out faster instead of merely further, which is
     * the whole point of simulating it rather than jumping to the peak in one frame. See §4.</p>
     *
     * <p>The samples are published as <b>raw travel</b> (the accumulator both call sites animate)
     * even though the integration runs in displacement: {@code damp(undamp(y)) == y}, so the
     * displacement that comes back out is bit-for-bit the curve that was integrated.</p>
     */
    public static final class Impact {

        /**
         * Raw travel per sample; {@code travelPx[0] == 0}, and the last of the {@code count}
         * entries is the peak. The backing array is allocated once at the step bound and never
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
     * ({@code travelAt} linearly in time), then hand the peak to {@link #SPRING} for the return:
     * the band is loaded by the impulse and released by the spring, which is exactly the division
     * the gesture has.
     *
     * <p>Note what is <em>not</em> here any more: no fling friction, no "screens of scroll left
     * over" calibration, no reference to the row height. The band is three orders of magnitude
     * stiffer than the platform's fling deceleration ({@code ω²y ≈ 3·10⁵ px/s²} at an 80 px
     * stretch against ~2·10³ px/s² of friction), so friction is noise on this leg; and the peak
     * is a property of the band's stiffness rather than of the content, so it has no business
     * being measured in rows.</p>
     *
     * @param velocityPxPerSecond impact speed along the drag axis as reported by the platform
     *                            (magnitude; sign is irrelevant). It is converted to dp/s by
     *                            {@link #absorbVelocity(float, float)} first, so the same physical
     *                            gesture loads the band identically on every screen.
     * @param extentPx            the surface's extent along the drag axis.
     * @param unitPx              the surface's quantum (glyph row height), or 0 for none.
     * @param density             the display's {@code DisplayMetrics#density}.
     */
    public static Impact impact(float velocityPxPerSecond, float extentPx, float unitPx,
                                float density) {
        // The impulse is the only density-dependent quantity in the model (§0b): it is measured in
        // dp/s so a physical flick means the same thing everywhere, and it is brought back into
        // the surface's own unit here so the integration below stays in one unit system. Every
        // other quantity — extent, quantum, output — is unit-agnostic: the band is scale-free, so
        // converting those to dp and back would cancel exactly.
        final float v = absorbVelocity(velocityPxPerSecond, density) * density;
        final float m = maxPull(extentPx, unitPx);
        if (!(v > 0f)) return new Impact(new float[] {0f}, 1, 0L);

        // The boundary resists an arriving impulse exactly as hard as it resists a finger: right
        // at it the pull curve has slope INITIAL_SLOPE (§2), i.e. INITIAL_SLOPE px of content per
        // px of input. So the content does not enter the band at the fling's speed — it enters at
        // INITIAL_SLOPE·v, and the remaining 58 % of the impulse is spent against the boundary.
        // A flick twice as fast therefore still flies out twice as fast; it is the *proportion*
        // that is fixed, by the same number that governs the drag.
        //
        // This is NOT the old "impulse through the pull curve" bug. That one fed the impulse
        // through the whole curve *and* integrated in raw-travel space, where the effective
        // stiffness is ∝ 1/L², so the fly-out stretched to ~460 ms. Here only the entry speed is
        // scaled; the integration is a quarter swing of the real pendulum in displacement, whose
        // period is amplitude-independent — so attenuating the impulse shortens the throw and
        // leaves the timing at ~52 ms. Slowing down and shrinking are separate knobs again.
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

        // The scales the loop needs, computed once. undamp() would re-evaluate maxPull() twice per
        // sample (once directly, once through saturationTravel()) — up to ~250 redundant calls per
        // impact, each carrying a Math.round.
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
        // decides *how far*. The two agree to well under a pixel.
        samples[n++] = invScale * (float) Math.asin(Math.min(1f, peak / m));
        // The duration is the time actually simulated, not an assumed "1 ms per step": the step is
        // derived from ω, so it is only coincidentally a whole millisecond.
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

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }
}
