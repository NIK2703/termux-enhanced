package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ImageView;

import androidx.annotation.Nullable;

/**
 * ImageView that fills its width without stretching and keeps the image's top edge visible.
 *
 * <p>A terminal thumbnail has a well-defined aspect ratio. When its rectangle is shorter than the
 * bitmap, the old {@code fitXY} scaled both axes and distorted the glyphs; when it was taller, the
 * remaining space was spread around the image. This view instead scales the bitmap only by width:
 * the top edge stays pinned to the view's top, a shorter image leaves the bottom empty, and a
 * taller image is clipped at the bottom. The overview uses the same rule for rendered previews and
 * for the terminal snapshots that fly in and out of the grid, so the hand-off between them cannot
 * change the glyph aspect ratio or move the first row.</p>
 *
 * <p>The view clips its own drawing rectangle because the ghost layer deliberately does not clip
 * its children; the rounded outline remains an additional clip for the card corners.</p>
 */
public final class TopCropImageView extends ImageView {

    private final Matrix mScaleMatrix = new Matrix();

    public TopCropImageView(Context context) {
        this(context, null);
    }

    public TopCropImageView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TopCropImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setScaleType(ScaleType.MATRIX);
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        updateScaleMatrix();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        setClipBounds(width > 0 && height > 0 ? new Rect(0, 0, width, height) : null);
        updateScaleMatrix();
    }

    /**
     * Recompute the width-fill matrix after the drawable or the view bounds changed.
     *
     * <p>{@link ImageView} in {@code MATRIX} mode draws the drawable at its intrinsic size and then
     * applies this matrix, so the scale is simply available width divided by bitmap width. There is
     * deliberately no vertical component and no centring translation: a taller image must be cut
     * off at the bottom, and a shorter one must stay at the top.</p>
     */
    private void updateScaleMatrix() {
        // The ImageView constructor can call setImageDrawable() through an android:src attribute
        // before this subclass's field initializer has run. There is no image content to size yet
        // at that point; the normal setImageDrawable()/onSizeChanged() path will update it later.
        if (mScaleMatrix == null) return;

        final Drawable drawable = getDrawable();
        final int drawableWidth = drawable != null ? drawable.getIntrinsicWidth() : -1;
        final int availableWidth = Math.max(0,
                getWidth() - getPaddingLeft() - getPaddingRight());
        if (drawableWidth <= 0 || availableWidth <= 0 || getHeight() <= 0) {
            mScaleMatrix.reset();
            setImageMatrix(mScaleMatrix);
            return;
        }

        final float scale = (float) availableWidth / drawableWidth;
        mScaleMatrix.setScale(scale, scale);
        setImageMatrix(mScaleMatrix);
    }
}
