package com.termux.view.support;

/**
 * Impulse ("kinetic energy") velocity tracker for a <em>differential</em> scroll axis.
 *
 * <p>This is a Java port of the AOSP {@code ImpulseVelocityTrackerStrategy}
 * ({@code frameworks/native/libs/input/VelocityTracker.cpp}), which is the strategy
 * {@code VelocityTracker} uses <em>by default for {@link android.view.MotionEvent#AXIS_VSCROLL}</em>
 * (and any other differential axis). Planar axes X/Y default to {@code LSQ2} instead, and for
 * those we simply use the platform {@code GestureDetector}, so this class is only ever fed
 * wheel / precision-trackpad deltas.</p>
 *
 * <h2>Why "impulse"</h2>
 * A differential axis carries <em>deltas</em>, not positions: every {@code ACTION_SCROLL} event
 * reports "how much was scrolled", not "where the finger is". A least-squares fit is therefore a
 * poor model for it. AOSP instead treats the touch surface as a physical body and accumulates the
 * <em>work</em> done by the finger:
 *
 * <pre>
 *     E  = 1/2 * m * v^2
 *     dW = F * dx = m * (dv/dt) * dx = m * v * dv
 *     W  = m * sum( v * dv )        ->  v = sqrt(2 * sum( (v[i] - v[i-1]) * |v[i]| ))
 * </pre>
 *
 * {@code |v[i]|} makes braking (negative work) subtract energy instead of adding it, and the
 * oldest segment's contribution is <b>halved</b> — the initial condition "the surface was already
 * moving at {@code v[0]}", whose kinetic energy is {@code 1/2 * v0^2}. A consequence: a perfectly
 * constant stream of deltas yields exactly that constant velocity back, with no bias.
 *
 * <h2>Deviations from the C++ original</h2>
 * <ul>
 *   <li>Times are passed in milliseconds ({@code MotionEvent#getEventTime()}) and stored as
 *       nanoseconds, matching the original's nanosecond arithmetic.</li>
 *   <li>If two samples share a timestamp, the deltas are <b>added</b> instead of overwriting the
 *       stored sample. The original is written for positions (where overwriting is right); on a
 *       differential axis overwriting would silently drop a whole notch of the wheel.</li>
 * </ul>
 */
public final class ScrollImpulseTracker {

    /** AOSP {@code VelocityTracker.h}: maximum number of retained samples. */
    private static final int HISTORY_SIZE = 20;

    /** AOSP {@code VelocityTracker.h}: how far back in time a sample still counts. */
    private static final long HORIZON_NS = 100_000_000L; // 100 ms

    private static final long MS_TO_NS = 1_000_000L;

    private static final float SECONDS_PER_NANO = 1E-9f;

    private static final float SQRT2 = 1.41421356237f;

    private final long[] mTimes = new long[HISTORY_SIZE];
    private final float[] mValues = new float[HISTORY_SIZE];

    /** Index of the newest sample, or -1 when empty. */
    private int mNewest = -1;

    /** Number of valid samples, saturating at {@link #HISTORY_SIZE}. */
    private int mCount;

    public void clear() {
        mNewest = -1;
        mCount = 0;
    }

    /**
     * Record one delta of a differential axis.
     *
     * @param timeMs timestamp of the event, {@link android.view.MotionEvent#getEventTime()}.
     * @param delta  the axis value (a delta, not a position).
     */
    public void addSample(long timeMs, float delta) {
        if (delta == 0f) return;
        final long t = timeMs * MS_TO_NS;
        if (mCount > 0 && mTimes[mNewest] == t) {
            // Same timestamp (batched events): accumulate so no notch of the wheel is dropped.
            mValues[mNewest] += delta;
            return;
        }
        mNewest = (mNewest + 1) % HISTORY_SIZE;
        mTimes[mNewest] = t;
        mValues[mNewest] = delta;
        if (mCount < HISTORY_SIZE) mCount++;
    }

    /** Velocity in axis units per second; 0 while fewer than two usable samples exist. */
    public float getVelocity() {
        if (mCount < 2) return 0f;
        final float[] values = new float[HISTORY_SIZE];
        final long[] times = new long[HISTORY_SIZE];

        // Walk backwards in time from the newest sample, newest-first, exactly like AOSP.
        final long newestTime = mTimes[mNewest];
        int m = 0;
        int index = mNewest;
        for (int k = 0; k < mCount; k++) {
            final long age = newestTime - mTimes[index];
            if (age > HORIZON_NS) break;
            values[m] = mValues[index];
            times[m] = mTimes[index];
            m++;
            index = (index == 0 ? HISTORY_SIZE : index) - 1;
        }
        if (m < 2) return 0f;
        return calculateImpulseVelocity(times, values, m);
    }

    /** True once the newest sample is older than the horizon, i.e. the gesture has ended. */
    public boolean isStale(long nowMs) {
        return mCount > 0 && (nowMs * MS_TO_NS - mTimes[mNewest]) > HORIZON_NS;
    }

    /**
     * AOSP {@code calculateImpulseVelocity()}. Input arrays are in <b>reverse</b> time order:
     * index 0 is the newest sample. Only the differential-axis branch ({@code deltaValues = true})
     * is kept, since that is the only axis this class is used for.
     */
    private static float calculateImpulseVelocity(long[] t, float[] x, int count) {
        if (count < 2) return 0f;
        if (count == 2) {
            if (t[1] == t[0]) return 0f;
            final float deltaX = -x[0];
            return deltaX / (SECONDS_PER_NANO * (t[1] - t[0]));
        }
        float work = 0f;
        // Oldest pair first (index count-1), walking forward in time as i decreases.
        for (int i = count - 1; i > 0; i--) {
            if (t[i] == t[i - 1]) continue;
            final float vprev = kineticEnergyToVelocity(work);
            // For a differential axis the stored value IS the delta; x[i-1] is the newer sample.
            final float deltaX = -x[i - 1];
            final float vcurr = deltaX / (SECONDS_PER_NANO * (t[i] - t[i - 1]));
            work += (vcurr - vprev) * Math.abs(vcurr);
            if (i == count - 1) {
                // Initial condition: the surface was already moving, so the oldest segment
                // contributes its kinetic energy 1/2*v^2, not the full v*dv.
                work *= 0.5f;
            }
        }
        return kineticEnergyToVelocity(work);
    }

    /** AOSP {@code kineticEnergyToVelocity()}: {@code sign(work) * sqrt(2 * |work|)}. */
    private static float kineticEnergyToVelocity(float work) {
        return (work < 0f ? -1f : 1f) * (float) Math.sqrt(Math.abs(work)) * SQRT2;
    }
}
