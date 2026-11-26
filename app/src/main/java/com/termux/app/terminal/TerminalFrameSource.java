package com.termux.app.terminal;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalPreviewRenderer;

/**
 * A fixed-size terminal raster that stays in sync with its emulator while a transition is on
 * screen.
 *
 * <p>The raster is allocated once per flight. Output, cursor blinking, scrolling and a grid
 * resize only mark it dirty; the next draw repaints the same bitmap through
 * {@link TerminalPreviewRenderer}, the same path used by the overview cards. No bitmap is
 * allocated per frame and no PTY is resized.</p>
 */
final class TerminalFrameSource {

    interface TopRowProvider {
        int getTopRow();
    }

    @NonNull
    private final TerminalSession mSession;
    private final int mTextSizePx;
    @NonNull
    private final Typeface mTypeface;
    private final int mBackgroundTransparencyPercent;
    @NonNull
    private final TopRowProvider mTopRowProvider;
    @NonNull
    private final Bitmap mRaster;
    @NonNull
    private final Paint mAlphaPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

    @Nullable
    private Runnable mInvalidationCallback;
    private boolean mDirty = true;
    private boolean mReleased;

    private int mLastTopRow = Integer.MIN_VALUE;
    private int mLastCursorRow = Integer.MIN_VALUE;
    private int mLastCursorCol = Integer.MIN_VALUE;
    private boolean mLastCursorVisible;
    private int mLastColumns = Integer.MIN_VALUE;
    private int mLastRows = Integer.MIN_VALUE;

    TerminalFrameSource(@NonNull TerminalSession session, int width, int height,
                        int textSizePx, @NonNull Typeface typeface,
                        int backgroundTransparencyPercent,
                        @NonNull TopRowProvider topRowProvider) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Terminal frame must have a positive size");
        }
        mSession = session;
        mTextSizePx = textSizePx;
        mTypeface = typeface;
        mBackgroundTransparencyPercent = backgroundTransparencyPercent;
        mTopRowProvider = topRowProvider;
        mRaster = Bitmap.createBitmap(width, height,
                backgroundTransparencyPercent > 0
                        ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565);
    }

    int getWidth() {
        return mRaster.getWidth();
    }

    int getHeight() {
        return mRaster.getHeight();
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
     * Paint the raster at {@code alpha}. The add-button hand-off crossfades the source button
     * into the terminal through {@link android.widget.ImageView#setImageAlpha(int)}, which reaches
     * this drawable as {@code Drawable.setAlpha()}. The terminal view itself still owns its own
     * alpha; this only affects the drawable copy used by a flight.
     */
    void draw(@NonNull Canvas canvas, int alpha) {
        if (alpha <= 0 || mReleased || mRaster.isRecycled()) return;
        renderIfDirty();
        // Match the filtering of the BitmapDrawable used by resting cards, otherwise the
        // downscaled glyphs would visibly change at the opening and closing hand-offs.
        mAlphaPaint.setAlpha(alpha);
        canvas.drawBitmap(mRaster, 0f, 0f, mAlphaPaint);
    }

    private void renderIfDirty() {
        if (!mDirty) return;
        mDirty = false;

        final TerminalEmulator emulator = mSession.getEmulator();
        if (emulator == null) return;

        final int topRow = mTopRowProvider.getTopRow();
        TerminalPreviewRenderer.render(emulator, mRaster, mTextSizePx, mTypeface,
                topRow, mBackgroundTransparencyPercent);
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
        if (!mRaster.isRecycled()) mRaster.recycle();
    }
}
