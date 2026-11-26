package com.termux.app.terminal;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Drawable bridge between a {@link TerminalFrameSource} and the flying {@link TopCropImageView}.
 *
 * <p>The image view keeps owning the width-fill/top-crop transform. The drawable only paints the
 * source raster at its intrinsic size, so a source update becomes a normal image invalidation.</p>
 */
final class LiveTerminalDrawable extends Drawable {

    @NonNull
    private final TerminalFrameSource mSource;
    private int mAlpha = 255;

    LiveTerminalDrawable(@NonNull TerminalFrameSource source) {
        mSource = source;
        source.setInvalidationCallback(this::invalidateSelf);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        mSource.draw(canvas, mAlpha);
    }

    @Override
    public int getIntrinsicWidth() {
        return mSource.getWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return mSource.getHeight();
    }

    @Override
    public void setAlpha(int alpha) {
        final int clamped = Math.max(0, Math.min(255, alpha));
        if (mAlpha == clamped) return;
        mAlpha = clamped;
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // The source is already painted with the terminal colours.
    }

    @Override
    public int getOpacity() {
        return mAlpha == 255 && mSource.isOpaque()
                ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT;
    }
}
