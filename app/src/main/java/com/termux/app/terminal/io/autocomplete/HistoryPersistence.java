package com.termux.app.terminal.io.autocomplete;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single writer for the history document.
 *
 * <p>All disk writes run on one executor in submission order. A newer save bumps a
 * generation counter, so a task that has not started yet is skipped; a task already
 * inside the sink may finish, but the newer task runs after it and the final document
 * is the latest one. The main thread never writes preferences itself.
 *
 * <p>The snapshot supplier is evaluated on the calling (main) thread. The executor
 * only receives the immutable {@link HistoryDocument}, never the live store.
 */
final class HistoryPersistence {

    interface Sink {
        /** @return whether the write succeeded. */
        boolean write(@NonNull HistoryDocument document, boolean sync);
    }

    interface SnapshotSupplier {
        @NonNull HistoryDocument get();
    }

    private static final long DEBOUNCE_MS = 250;

    @NonNull private final Sink mSink;
    @NonNull private final Handler mHandler;
    @NonNull private final ExecutorService mExecutor;
    @NonNull private final AtomicInteger mGeneration = new AtomicInteger();
    @Nullable private Runnable mPending;
    private boolean mClosed;

    HistoryPersistence(@NonNull Sink sink) {
        this(sink, new Handler(Looper.getMainLooper()), newExecutor());
    }

    @VisibleForTesting
    HistoryPersistence(@NonNull Sink sink, @NonNull Handler handler,
            @NonNull ExecutorService executor) {
        mSink = sink;
        mHandler = handler;
        mExecutor = executor;
    }

    /** Debounced snapshot; the supplier runs when the timer fires on the main thread. */
    void schedule(@NonNull SnapshotSupplier supplier) {
        if (mClosed) return;
        if (mPending != null) mHandler.removeCallbacks(mPending);
        mPending = () -> {
            mPending = null;
            submit(supplier.get(), false);
        };
        mHandler.postDelayed(mPending, DEBOUNCE_MS);
    }

    /**
     * Capture now and write. With {@code sync=true}, wait for that write to finish;
     * an older in-flight write may complete first, but this one lands after it.
     */
    void saveNow(@NonNull SnapshotSupplier supplier, boolean sync) {
        cancel();
        if (mClosed) return;
        Future<Boolean> future = submit(supplier.get(), sync);
        if (sync) await(future);
    }

    /** Write an already-captured document, used by migration and clear barriers. */
    boolean writeNow(@NonNull HistoryDocument document, boolean sync) {
        cancel();
        if (mClosed) return false;
        Future<Boolean> future = submit(document, sync);
        return sync ? await(future) : true;
    }

    void cancel() {
        if (mPending != null) {
            mHandler.removeCallbacks(mPending);
            mPending = null;
        }
    }

    void close() {
        cancel();
        mClosed = true;
        mExecutor.shutdown();
        try {
            if (!mExecutor.awaitTermination(1, TimeUnit.SECONDS)) mExecutor.shutdownNow();
        } catch (InterruptedException e) {
            mExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @NonNull
    private Future<Boolean> submit(@NonNull HistoryDocument document, boolean sync) {
        final int generation = mGeneration.incrementAndGet();
        return mExecutor.submit(() -> {
            if (generation < mGeneration.get()) return false;
            try {
                return mSink.write(document, sync);
            } catch (RuntimeException e) {
                return false;
            }
        });
    }

    private static boolean await(@NonNull Future<Boolean> future) {
        try {
            Boolean result = future.get();
            return result != null && result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            return false;
        }
    }

    @NonNull
    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "message-history-persist");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }
}
