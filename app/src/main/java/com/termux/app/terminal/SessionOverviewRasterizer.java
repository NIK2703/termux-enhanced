package com.termux.app.terminal;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import androidx.annotation.NonNull;

import com.termux.terminal.TerminalRenderSnapshot;
import com.termux.view.TerminalPreviewRenderer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serializes terminal raster work off the main thread.
 *
 * <p>There is one FIFO lane for card previews and one for the live flight frame. Each lane has a
 * single thread and therefore its own stateful {@code TerminalRenderer} cache. A request owns its
 * snapshot and target bitmap; callers coalesce per card or per frame, so the queue never contains
 * more than one pending request for the same surface.</p>
 */
final class SessionOverviewRasterizer {

    interface Completion {
        void onRasterComplete(int generation);
    }

    private static final AtomicInteger sThreadIds = new AtomicInteger();

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mPreviewExecutor;
    private final ExecutorService mFrameExecutor;
    private volatile boolean mReleased;

    SessionOverviewRasterizer() {
        // Preview work is small and bounded, but background priority can starve it for
        // hundreds of milliseconds. DEFAULT stays below the UI/RenderThread and avoids that.
        mPreviewExecutor = Executors.newSingleThreadExecutor(new LaneThreadFactory(
                "overview-preview-raster", Process.THREAD_PRIORITY_DEFAULT));
        mFrameExecutor = Executors.newSingleThreadExecutor(new LaneThreadFactory(
                "overview-frame-raster", Process.THREAD_PRIORITY_DEFAULT));
    }

    void submitPreview(@NonNull TerminalRenderSnapshot snapshot, @NonNull Bitmap target,
                       int textSizePx, @NonNull Typeface typeface,
                       int backgroundTransparencyPercent, int generation,
                       @NonNull Completion completion) {
        submit(mPreviewExecutor, snapshot, target, textSizePx, typeface,
                backgroundTransparencyPercent, generation, completion);
    }

    void submitFrame(@NonNull TerminalRenderSnapshot snapshot, @NonNull Bitmap target,
                     int textSizePx, @NonNull Typeface typeface,
                     int backgroundTransparencyPercent, int generation,
                     @NonNull Completion completion) {
        submit(mFrameExecutor, snapshot, target, textSizePx, typeface,
                backgroundTransparencyPercent, generation, completion);
    }

    private void submit(@NonNull ExecutorService executor,
                        @NonNull TerminalRenderSnapshot snapshot, @NonNull Bitmap target,
                        int textSizePx, @NonNull Typeface typeface,
                        int backgroundTransparencyPercent, int generation,
                        @NonNull Completion completion) {
        if (mReleased) return;
        try {
            executor.execute(() -> {
                try {
                    TerminalPreviewRenderer.render(snapshot, target, textSizePx, typeface,
                            snapshot.getTopRow(), backgroundTransparencyPercent);
                } catch (Throwable ignored) {
                    // Keep the previous surface; the completion still releases the in-flight flag.
                } finally {
                    if (!mReleased) {
                        mMainHandler.post(() -> {
                            if (!mReleased) completion.onRasterComplete(generation);
                        });
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // The controller is shutting down; its card/frame state is already being released.
        }
    }

    void shutdown() {
        mReleased = true;
        mPreviewExecutor.shutdownNow();
        mFrameExecutor.shutdownNow();
        mMainHandler.removeCallbacksAndMessages(null);
    }

    private static final class LaneThreadFactory implements ThreadFactory {
        private final String mName;
        private final int mPriority;

        LaneThreadFactory(String name, int priority) {
            mName = name;
            mPriority = priority;
        }

        @Override
        public Thread newThread(@NonNull Runnable runnable) {
            final Thread thread = new Thread(() -> {
                Process.setThreadPriority(mPriority);
                runnable.run();
            }, mName + "-" + sThreadIds.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) -> {
                // A raster failure must never take down the UI process. The surface simply keeps
                // its previous bitmap; the next output event requests another snapshot.
            });
            return thread;
        }
    }
}
