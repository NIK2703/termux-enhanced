package com.termux.view.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Every expected value below is hand-computed from the AOSP reference implementation
 * ({@code VelocityTracker.cpp :: calculateImpulseVelocity}), so the port can be verified on the
 * JVM without a device.
 */
public class ScrollImpulseTrackerTest {

    private static final float TOL = 0.5f;

    /** Feed {@code deltas} as a stream spaced {@code stepMs} milliseconds apart. */
    private static void feed(ScrollImpulseTracker tracker, long startMs, long stepMs, float... deltas) {
        long time = startMs;
        for (float d : deltas) {
            tracker.addSample(time, d);
            time += stepMs;
        }
    }

    @Test
    public void singleSampleHasNoVelocity() {
        ScrollImpulseTracker t = new ScrollImpulseTracker();
        assertEquals(0f, t.getVelocity(), 0f);
        t.addSample(0, 1f);
        assertEquals(0f, t.getVelocity(), 0f);
    }

    @Test
    public void twoSamplesGivePlainDeltaOverDeltaTime() {
        ScrollImpulseTracker t = new ScrollImpulseTracker();
        feed(t, 0, 10, 1f, 1f);
        // Newest delta 1 across a 10 ms interval -> 100 units/s.
        assertEquals(100f, t.getVelocity(), TOL);
    }

    @Test
    public void constantStreamReturnsExactlyThatVelocity() {
        // The halved oldest segment is precisely what keeps a constant stream unbiased.
        ScrollImpulseTracker t = new ScrollImpulseTracker();
        feed(t, 0, 10, 1f, 1f, 1f, 1f, 1f);
        assertEquals(100f, t.getVelocity(), TOL);

        ScrollImpulseTracker fast = new ScrollImpulseTracker();
        feed(fast, 0, 8, 0.5f, 0.5f, 0.5f, 0.5f);
        assertEquals(62.5f, fast.getVelocity(), TOL);
    }

    @Test
    public void acceleratingStreamIsWeightedTowardsTheRecentEnd() {
        ScrollImpulseTracker t = new ScrollImpulseTracker();
        feed(t, 0, 10, 1f, 1f, 2f);
        // work = 0.5 * (100 * 100) + (200 - 100) * 200 = 25000  ->  sqrt(2 * 25000)
        assertEquals(223.607f, t.getVelocity(), TOL);
    }

    @Test
    public void theOldestDeltaIsDroppedBecauseAVelocityComesFromTheNewerEndpoint() {
        ScrollImpulseTracker t = new ScrollImpulseTracker();
        // The leading 2 belongs to the oldest sample; with N samples only N-1 intervals exist
        // and each takes its value from the newer endpoint, so it never enters the sum.
        feed(t, 0, 10, 2f, 1f, 1f);
        assertEquals(100f, t.getVelocity(), TOL);
    }

    @Test
    public void brakingBelowZeroFlipsTheSign() {
        ScrollImpulseTracker t = new ScrollImpulseTracker();
        feed(t, 0, 10, -1f, -1f, -1f);
        assertEquals(-100f, t.getVelocity(), TOL);
    }

    @Test
    public void reversalIsDominatedByTheLatestDirection() {
        ScrollImpulseTracker t = new ScrollImpulseTracker();
        // Strong scroll one way, then a stronger flick back.
        feed(t, 0, 10, 3f, 3f, -4f, -4f);
        assertTrue("expected the latest direction to win, got " + t.getVelocity(), t.getVelocity() < 0f);
    }

    @Test
    public void samplesBeyondTheHorizonAreIgnored() {
        ScrollImpulseTracker stale = new ScrollImpulseTracker();
        feed(stale, 0, 200, 1f, 1f);
        // The 200 ms-old sample falls outside the 100 ms horizon -> only one usable sample.
        assertEquals(0f, stale.getVelocity(), 0f);

        ScrollImpulseTracker fresh = new ScrollImpulseTracker();
        feed(fresh, 0, 90, 1f, 1f);
        assertEquals(11.11f, fresh.getVelocity(), TOL);
    }

    @Test
    public void batchedSamplesWithTheSameTimestampAccumulate() {
        ScrollImpulseTracker t = new ScrollImpulseTracker();
        t.addSample(0, 1f);
        t.addSample(10, 0.5f);
        t.addSample(10, 0.5f); // same timestamp: must add up, not overwrite
        assertEquals(100f, t.getVelocity(), TOL);
    }

    @Test
    public void historyIsBoundedButKeepsTheNewestSamples() {
        ScrollImpulseTracker t = new ScrollImpulseTracker();
        for (int i = 0; i < 100; i++) {
            t.addSample(i * 10L, 1f);
        }
        // Everything inside the horizon is a constant 1-per-10ms stream, so a saturated ring
        // buffer must still report it exactly.
        assertEquals(100f, t.getVelocity(), TOL);
    }

    @Test
    public void clearResetsTheTracker() {
        ScrollImpulseTracker t = new ScrollImpulseTracker();
        feed(t, 0, 10, 1f, 1f);
        assertEquals(100f, t.getVelocity(), TOL);
        t.clear();
        assertEquals(0f, t.getVelocity(), 0f);
    }

    @Test
    public void staleDetectionMatchesTheHorizon() {
        ScrollImpulseTracker t = new ScrollImpulseTracker();
        t.addSample(1000, 1f);
        assertTrue(!t.isStale(1050));
        assertTrue(t.isStale(1200));
    }
}
