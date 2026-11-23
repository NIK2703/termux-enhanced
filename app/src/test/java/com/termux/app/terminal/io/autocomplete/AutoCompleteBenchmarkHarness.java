package com.termux.app.terminal.io.autocomplete;

import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

/**
 * Small self-contained measurement harness for the autocomplete benchmarks.
 *
 * <p>It deliberately does not depend on JMH: Android unit tests run in a plain JVM
 * with Robolectric, and the strict measurement rules are small enough to keep here.
 * Two modes are supported:
 *
 * <ul>
 *   <li>{@link #measureBatch} measures a repeatable operation in batches and reports
 *       the distribution of batch means. This is the right mode for nanosecond
 *       micro-ops, where per-operation {@code nanoTime} would dominate.</li>
 *   <li>{@link #measureEach} measures one stateful operation per sample with an
 *       out-of-timer reset. This is the right mode for controller updates, where the
 *       reset itself triggers a full history rebuild and must not be counted.</li>
 * </ul>
 *
 * <p>Every row also reports allocated bytes per operation when the JVM exposes
 * {@code com.sun.management.ThreadMXBean}. Allocation is often the metric that
 * decides whether an Android hot path is acceptable even when wall time is small.
 *
 * <p>Results are printed to stdout and, when a writer is supplied, as TSV. This is a
 * report-only harness: it asserts nothing about absolute timings, because those are
 * host- and JIT-dependent. Optimizations must be accepted from a before/after diff
 * on the same host, not from a single magic number.
 */
final class AutoCompleteBenchmarkHarness {

    interface Op {
        long run();
    }

    static final int DEFAULT_BATCHES = 7;
    static final int DEFAULT_WARMUP_BATCHES = 2;

    private static long sSink;

    private final PrintWriter mTsv;
    private final boolean mConsole;
    private boolean mHeaderWritten;

    AutoCompleteBenchmarkHarness(PrintWriter tsv, boolean console) {
        mTsv = tsv;
        mConsole = console;
    }

    void header(String title) {
        if (mHeaderWritten) return;
        mHeaderWritten = true;
        if (mConsole) {
            System.out.println();
            System.out.println("=== " + title + " ===");
            System.out.printf(Locale.US, "%-12s %-30s %6s %12s %12s %12s %14s%n",
                    "suite", "scenario", "N", "p50", "p95", "p99", "alloc/op");
        }
        if (mTsv != null) {
            mTsv.println("suite\tscenario\tn\titers\twarmup\tbatches"
                    + "\tp50_ns\tp95_ns\tp99_ns\talloc_bytes");
            mTsv.flush();
        }
    }

    /**
     * Measure a repeatable operation. {@code iters} is the number of calls in one
     * batch; {@code batches} is the number of measured batches.
     */
    void measureBatch(String suite, String scenario, int n, int iters, int batches, Op op) {
        measureBatch(suite, scenario, n, iters, batches, DEFAULT_WARMUP_BATCHES, op);
    }

    void measureBatch(String suite, String scenario, int n, int iters, int batches,
            int warmupBatches, Op op) {
        header("autocomplete hot-path benchmark");
        if (iters <= 0 || batches <= 0) return;
        for (int b = 0; b < Math.max(0, warmupBatches); b++) {
            for (int i = 0; i < iters; i++) sSink += op.run();
        }

        double[] nsPerOp = new double[batches];
        double[] allocPerOp = new double[batches];
        for (int b = 0; b < batches; b++) {
            final long alloc0 = allocatedBytes();
            final long t0 = System.nanoTime();
            for (int i = 0; i < iters; i++) sSink += op.run();
            final long dt = System.nanoTime() - t0;
            final long alloc1 = allocatedBytes();
            nsPerOp[b] = dt / (double) iters;
            allocPerOp[b] = (alloc0 >= 0 && alloc1 >= alloc0)
                    ? (alloc1 - alloc0) / (double) iters : -1;
        }
        Arrays.sort(nsPerOp);
        Arrays.sort(allocPerOp);
        emit(suite, scenario, n, iters, warmupBatches, batches,
                nsPerOp, allocPerOp);
    }

    /**
     * Measure one operation per sample, resetting state outside the timer. This is
     * used for controller updates: resetting the EditText is itself a full rebuild
     * and would otherwise hide the per-keystroke cost.
     */
    void measureEach(String suite, String scenario, int n, int samples,
            int warmupSamples, Op op, Runnable reset) {
        header("autocomplete hot-path benchmark");
        if (samples <= 0) return;
        for (int i = 0; i < Math.max(0, warmupSamples); i++) {
            reset.run();
            sSink += op.run();
        }
        double[] nsPerOp = new double[samples];
        double[] allocPerOp = new double[samples];
        for (int i = 0; i < samples; i++) {
            reset.run();
            final long alloc0 = allocatedBytes();
            final long t0 = System.nanoTime();
            sSink += op.run();
            final long dt = System.nanoTime() - t0;
            final long alloc1 = allocatedBytes();
            nsPerOp[i] = dt;
            allocPerOp[i] = (alloc0 >= 0 && alloc1 >= alloc0) ? (alloc1 - alloc0) : -1;
        }
        Arrays.sort(nsPerOp);
        Arrays.sort(allocPerOp);
        emit(suite, scenario, n, samples, warmupSamples, samples,
                nsPerOp, allocPerOp);
    }

    private void emit(String suite, String scenario, int n, int iters, int warmup,
            int batches, double[] nsPerOp, double[] allocPerOp) {
        final double p50 = percentile(nsPerOp, 0.50);
        final double p95 = percentile(nsPerOp, 0.95);
        final double p99 = percentile(nsPerOp, 0.99);
        final double alloc = percentile(allocPerOp, 0.50);
        if (mConsole) {
            System.out.printf(Locale.US, "%-12s %-30s %6d %10.1fns %10.1fns %10.1fns %10.1fB%n",
                    suite, scenario, n, p50, p95, p99, alloc);
        }
        if (mTsv != null) {
            mTsv.printf(Locale.US, "%s\t%s\t%d\t%d\t%d\t%d\t%.1f\t%.1f\t%.1f\t%.1f%n",
                    suite, scenario, n, iters, warmup, batches, p50, p95, p99, alloc);
            mTsv.flush();
        }
    }

    static long sink() {
        return sSink;
    }

    /** Nearest-rank percentile over a sorted array. */
    private static double percentile(double[] sorted, double fraction) {
        if (sorted.length == 0) return -1;
        int index = (int) Math.ceil(fraction * sorted.length) - 1;
        if (index < 0) index = 0;
        if (index >= sorted.length) index = sorted.length - 1;
        return sorted[index];
    }

    // ── Optional JVM allocation counter ──

    private static final Object ALLOC_BEAN;
    private static final Method ALLOC_METHOD;

    static {
        Object bean = null;
        Method method = null;
        try {
            Class<?> clazz = Class.forName("com.sun.management.ThreadMXBean");
            Class<?> factory = Class.forName("java.lang.management.ManagementFactory");
            Object candidate = factory.getMethod("getThreadMXBean").invoke(null);
            if (clazz.isInstance(candidate)) {
                bean = candidate;
                method = clazz.getMethod("getThreadAllocatedBytes", long.class);
            }
        } catch (Throwable ignored) {
            // Allocation reporting is optional; timing remains valid.
        }
        ALLOC_BEAN = bean;
        ALLOC_METHOD = method;
    }

    static long allocatedBytes() {
        if (ALLOC_METHOD == null || ALLOC_BEAN == null) return -1;
        try {
            return (Long) ALLOC_METHOD.invoke(ALLOC_BEAN, Thread.currentThread().getId());
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
