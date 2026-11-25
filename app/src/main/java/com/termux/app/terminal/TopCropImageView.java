package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
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
    /** Reused bounds for the rectangular clip; flying ghosts resize every animation frame. */
    private final Rect mClipRect = new Rect();
    private boolean mSkipClipBounds;
    /** Optional card outline drawn over the snapshot while it is flying. */
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float mBorderWidth;
    private float mBorderRadius;
    private int mBorderColor;
    private float mBorderAlpha;

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

    /**
     * Disable the explicit clip rectangle and rely on {@code clipToOutline} alone. Needed when a
     * flight ghost must cast an elevation shadow: {@code setClipBounds} clips the shadow to the
     * view's own bounds, hiding everything outside it.
     */
    void setSkipClipBounds(boolean skip) {
        if (mSkipClipBounds == skip) return;
        mSkipClipBounds = skip;
        if (skip) setClipBounds(null);
        else if (getWidth() > 0 && getHeight() > 0) {
            setClipBounds(widthBounds());
        }
    }

    private Rect widthBounds() {
        mClipRect.set(0, 0, getWidth(), getHeight());
        return mClipRect;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        setClipBounds(!mSkipClipBounds && width > 0 && height > 0
                ? widthBounds() : null);
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

    /**
     * Draw the same rounded stroke as a resting overview card on the flying snapshot. The flight
     * owns this outline so it can fade it independently of the card hidden underneath it.
     */
    void setFlightBorder(float width, int color, float radius, float alpha) {
        width = Math.max(0f, width);
        radius = Math.max(0f, radius);
        alpha = Math.max(0f, Math.min(1f, alpha));
        if (mBorderWidth == width && mBorderRadius == radius
                && mBorderColor == color && mBorderAlpha == alpha) return;
        mBorderWidth = width;
        mBorderRadius = radius;
        mBorderColor = color;
        mBorderAlpha = alpha;
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(width);
        mBorderPaint.setColor((Math.round(Color.alpha(color) * alpha) << 24)
                | (color & 0x00FFFFFF));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mBorderWidth <= 0f || mBorderAlpha <= 0f
                || getWidth() <= mBorderWidth || getHeight() <= mBorderWidth) return;
        final float inset = mBorderWidth / 2f;
        final float radius = Math.max(0f, mBorderRadius - inset);
        canvas.drawRoundRect(inset, inset, getWidth() - inset, getHeight() - inset,
                radius, radius, mBorderPaint);
    }
}
