package com.termux.app.terminal;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRenderSnapshot;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalPreviewRenderer;

/**
 * A fixed-size terminal raster that stays in sync with its emulator while a transition is on
 * screen.
 *
 * <p>The first frame is painted synchronously in the constructor so the flight never starts with
 * an empty bitmap. Later repaints are submitted to the frame raster lane as immutable
 * {@link TerminalRenderSnapshot}s captured on the main thread; the expensive
 * {@link TerminalPreviewRenderer} work happens off main. Three buffers rotate so the bitmap being
 * sampled by RenderThread is never the one being painted: {@code mFront} is drawn, {@code mBack}
 * is the raster target (or in flight), and {@code mSpare} is the retired front. No bitmap is
 * allocated per frame and no PTY is resized.</p>
 */
final class TerminalFrameSource {

    interface TopRowProvider {
        int getTopRow();
    }

    @NonNull
    private final TerminalSession mSession;
    @NonNull
    private final SessionOverviewRasterizer mRasterizer;
    @NonNull
    private final TopRowProvider mTopRowProvider;
    private final int mWidth;
    private final int mHeight;
    private final int mTextSizePx;
    @NonNull
    private final Typeface mTypeface;
    private final int mBackgroundTransparencyPercent;

    /** Bitmap currently handed to Canvas; only main reads it, RenderThread may still sample it. */
    @Nullable
    private Bitmap mFront;
    /** Offscreen raster target, owned by a background job while {@link #mInFlight}. */
    @Nullable
    private Bitmap mBack;
    /** Retired front, reused as the next back buffer once RenderThread has moved on. */
    @Nullable
    private Bitmap mSpare;
    @NonNull
    private final Paint mAlphaPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

    @Nullable
    private Runnable mInvalidationCallback;
    private boolean mDirty = true;
    private boolean mInFlight;
    private boolean mReleased;
    private int mGeneration;

    private int mLastTopRow = Integer.MIN_VALUE;
    private int mLastCursorRow = Integer.MIN_VALUE;
    private int mLastCursorCol = Integer.MIN_VALUE;
    private boolean mLastCursorVisible;
    private int mLastColumns = Integer.MIN_VALUE;
    private int mLastRows = Integer.MIN_VALUE;

    TerminalFrameSource(@NonNull TerminalSession session,
                        @NonNull SessionOverviewRasterizer rasterizer,
                        int width, int height,
                        int textSizePx, @NonNull Typeface typeface,
                        int backgroundTransparencyPercent,
                        @NonNull TopRowProvider topRowProvider) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Terminal frame must have a positive size");
        }
        mSession = session;
        mRasterizer = rasterizer;
        mWidth = width;
        mHeight = height;
        mTextSizePx = textSizePx;
        mTypeface = typeface;
        mBackgroundTransparencyPercent = backgroundTransparencyPercent;
        mTopRowProvider = topRowProvider;
        // Flight frames use the same 8-bit format as resting cards: RGB_565 would tint their
        // light greys green and the colour would pop when the frame lands.
        mFront = Bitmap.createBitmap(width, height, TerminalPreviewRenderer.BITMAP_CONFIG);
        mBack = Bitmap.createBitmap(width, height, TerminalPreviewRenderer.BITMAP_CONFIG);
        renderFirstFrameSynchronously();
    }

    int getWidth() {
        return mWidth;
    }

    int getHeight() {
        return mHeight;
    }

    boolean isOpaque() {
        return mBackgroundTransparencyPercent == 0;
    }

    void setInvalidationCallback(@Nullable Runnable callback) {
        mInvalidationCallback = callback;
    }

    /** Mark the raster stale after terminal output or another emulator change. */
    void markDirty() {
        if (mReleased || mDirty) return;
        mDirty = true;
        final Runnable callback = mInvalidationCallback;
        if (callback != null) callback.run();
    }

    /**
     * Compare the cheap transient emulator state that can change without a text callback:
     * cursor blink, cursor position and the terminal grid size. The scroll position is read from
     * the owning terminal view through {@link TopRowProvider}.
     */
    void syncTransientState() {
        if (mReleased) return;
        final TerminalEmulator emulator = mSession.getEmulator();
        if (emulator == null) return;

        final int topRow = mTopRowProvider.getTopRow();
        if (topRow != mLastTopRow
                || emulator.getCursorRow() != mLastCursorRow
                || emulator.getCursorCol() != mLastCursorCol
                || emulator.shouldCursorBeVisible() != mLastCursorVisible
                || emulator.mColumns != mLastColumns
                || emulator.mRows != mLastRows) {
            markDirty();
        }
    }

    void draw(@NonNull Canvas canvas) {
        draw(canvas, 255);
    }

    /**
     * Paint the front raster at {@code alpha}. The add-button hand-off crossfades the source button
     * into the terminal through {@link android.widget.ImageView#setImageAlpha(int)}, which reaches
     * this drawable as {@code Drawable.setAlpha()}. The terminal view itself still owns its own
     * alpha; this only affects the drawable copy used by a flight.
     */
    void draw(@NonNull Canvas canvas, int alpha) {
        if (alpha <= 0 || mReleased || mFront == null || mFront.isRecycled()) return;
        renderIfDirty();
        // Match the filtering of the BitmapDrawable used by resting cards, otherwise the
        // downscaled glyphs would visibly change at the opening and closing hand-offs.
        final Bitmap front = mFront;
        mAlphaPaint.setAlpha(alpha);
        canvas.drawBitmap(front, 0f, 0f, mAlphaPaint);
    }

    /**
     * Paint the opening frame on main. A flight must never start blank, and this one frame is
     * cheap enough to do inline; every later repaint is asynchronous.
     */
    private void renderFirstFrameSynchronously() {
        final TerminalEmulator emulator = mSession.getEmulator();
        if (emulator == null || mFront == null) return;
        final int topRow = mTopRowProvider.getTopRow();
        try {
            TerminalPreviewRenderer.render(emulator, mFront, mTextSizePx, mTypeface,
                    topRow, mBackgroundTransparencyPercent);
        } catch (RuntimeException ignored) {
            // A terminal that cannot be drawn leaves the freshly allocated bitmap blank.
        }
        recordTransientState(emulator, topRow);
        mDirty = false;
    }

    /** Capture one snapshot on main and hand it to the frame raster lane. */
    private void renderIfDirty() {
        if (!mDirty || mInFlight || mReleased) return;
        final TerminalEmulator emulator = mSession.getEmulator();
        if (emulator == null) return;
        final Bitmap target = obtainBack();
        if (target == null) return;

        final int topRow = mTopRowProvider.getTopRow();
        final TerminalRenderSnapshot snapshot;
        try {
            snapshot = TerminalRenderSnapshot.capture(emulator, topRow);
        } catch (RuntimeException ignored) {
            // The emulator may have been resized between the geometry read and the copy. Keep the
            // dirty flag; the next output or layout callback requests another snapshot.
            return;
        }
        recordTransientState(emulator, topRow);
        mDirty = false;
        mInFlight = true;
        final int generation = ++mGeneration;
        mRasterizer.submitFrame(snapshot, target, mTextSizePx, mTypeface,
                mBackgroundTransparencyPercent, generation,
                completedGeneration -> onFrameRendered(target, completedGeneration));
    }

    /** Select an offscreen buffer that is neither the bitmap on screen nor already in flight. */
    @Nullable
    private Bitmap obtainBack() {
        if (mBack != null && !mBack.isRecycled() && matchesBackSize(mBack)) return mBack;
        if (mBack != null && !mBack.isRecycled() && mBack != mFront && mBack != mSpare) {
            recycleBitmap(mBack);
        }
        if (mSpare != null && !mSpare.isRecycled() && matchesBackSize(mSpare)) {
            mBack = mSpare;
            mSpare = null;
            return mBack;
        }
        if (mSpare != null && !mSpare.isRecycled()) recycleBitmap(mSpare);
        try {
            mBack = Bitmap.createBitmap(mWidth, mHeight, TerminalPreviewRenderer.BITMAP_CONFIG);
            return mBack;
        } catch (OutOfMemoryError ignored) {
            mBack = null;
            return null;
        }
    }

    private boolean matchesBackSize(@NonNull Bitmap bitmap) {
        return bitmap.getWidth() == mWidth && bitmap.getHeight() == mHeight
                && bitmap.getConfig() == TerminalPreviewRenderer.BITMAP_CONFIG;
    }

    /** Called on main when the background raster has finished. Rotate buffers and redraw. */
    private void onFrameRendered(@NonNull Bitmap target, int generation) {
        if (mReleased || generation != mGeneration || mBack != target || mFront == target) {
            if (target != mFront && target != mSpare) recycleBitmap(target);
            return;
        }
        final Bitmap oldFront = mFront;
        mFront = target;
        mBack = mSpare;
        mSpare = oldFront;
        mInFlight = false;
        final Runnable callback = mInvalidationCallback;
        if (callback != null) callback.run();
    }

    private void recordTransientState(@NonNull TerminalEmulator emulator, int topRow) {
        mLastTopRow = topRow;
        mLastCursorRow = emulator.getCursorRow();
        mLastCursorCol = emulator.getCursorCol();
        mLastCursorVisible = emulator.shouldCursorBeVisible();
        mLastColumns = emulator.mColumns;
        mLastRows = emulator.mRows;
    }

    void release() {
        if (mReleased) return;
        mReleased = true;
        mInvalidationCallback = null;
        recycleBitmap(mFront);
        mFront = null;
        if (!mInFlight) {
            recycleBitmap(mBack);
            recycleBitmap(mSpare);
        } else if (mSpare != mBack) {
            // The in-flight target is owned by the raster job. Its completion sees mReleased and
            // recycles it; the retired front is safe to release here.
            recycleBitmap(mSpare);
        }
        mBack = null;
        mSpare = null;
    }

    private static void recycleBitmap(@Nullable Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }
}
