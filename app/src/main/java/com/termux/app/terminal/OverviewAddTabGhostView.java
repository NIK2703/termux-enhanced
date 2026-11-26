package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Full-screen, non-interactive ghost for the add-session flight.
 *
 * <p>One {@link View} draws the whole morph: the button's exact fill and stroke, a fixed "+"
 * icon that fades out, and a snapshot of the real card that fades in. The view's outline follows
 * the current rounded rectangle, so the platform elevation shadow starts as the button's circular
 * shadow and fades out with the flight instead of staying a hard rectangle.</p>
 */
public final class OverviewAddTabGhostView extends View {

    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final Path mClipPath = new Path();

    private float mRadius;
    private int mFillColor = Color.TRANSPARENT;
    private float mPlusAlpha;
    private float mContentAlpha;
    private int mStrokeColor = Color.TRANSPARENT;
    private float mStrokeWidthPx;
    @Nullable private Bitmap mCardBitmap;
    @Nullable private Drawable mPlusIcon;
    private float mPlusIconSizePx;
    private int mShadowElevationPx;
    private int mShadowColor = Color.TRANSPARENT;

    public OverviewAddTabGhostView(@NonNull Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        mStrokePaint.setStyle(Paint.Style.STROKE);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                if (mRect.width() <= 0f || mRect.height() <= 0f) {
                    outline.setEmpty();
                    return;
                }
                final float radius = Math.min(mRadius,
                        Math.min(mRect.width(), mRect.height()) / 2f);
                outline.setRoundRect(
                        Math.round(mRect.left), Math.round(mRect.top),
                        Math.round(mRect.right), Math.round(mRect.bottom), radius);
            }
        });
    }

    /** Set the current frame. No allocation, just field updates + invalidate. */
    public void setFlightFrame(float left, float top, float right, float bottom,
                               float radius, int fillColor,
                               float plusAlpha, float contentAlpha) {
        mRect.set(left, top, right, bottom);
        mRadius = Math.max(0f, radius);
        mFillColor = fillColor;
        mPlusAlpha = clamp01(plusAlpha);
        mContentAlpha = clamp01(contentAlpha);
        // The shadow outline has to follow the morphing shape, not the full-screen view bounds.
        invalidateOutline();
        invalidate();
    }

    /** The button's stroke at the start, the card's stroke at the end. */
    public void setStroke(float widthPx, int color) {
        mStrokeWidthPx = Math.max(0f, widthPx);
        mStrokeColor = color;
        mStrokePaint.setStrokeWidth(mStrokeWidthPx);
        invalidate();
    }

    public void setCardBitmap(@Nullable Bitmap bitmap) {
        if (mCardBitmap == bitmap) return;
        mCardBitmap = bitmap;
        invalidate();
    }

    public void clearCardBitmap() {
        if (mCardBitmap == null) return;
        mCardBitmap = null;
        invalidate();
    }

    /**
     * Start with the add button's exact elevation and shadow colour. The outline is already the
     * source circle, so the first frame matches the real button.
     */
    public void setFlightShadow(int elevationPx, int shadowColor) {
        mShadowElevationPx = Math.max(0, elevationPx);
        mShadowColor = shadowColor;
        applyShadow(1f);
    }

    /** Fade the button shadow out as the card takes over. */
    public void setShadowAlpha(float alpha) {
        applyShadow(clamp01(alpha));
    }

    private void applyShadow(float alpha) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // API 28+ supports an independent shadow colour; keep the elevation and fade alpha.
            FloatingShadow.setShadowColors(this,
                    FloatingShadow.withAlphaFraction(mShadowColor, alpha));
            setElevation(mShadowElevationPx);
        } else {
            // Older platforms ignore the shadow colour. Lowering the elevation is the only way
            // to make the platform shadow disappear.
            setElevation(mShadowElevationPx * alpha);
        }
    }

    /** Fixed-size "+" icon; it fades out, it is not scaled with the card. */
    public void setPlusIcon(@Nullable Drawable icon, float sizePx) {
        mPlusIcon = icon;
        mPlusIconSizePx = Math.max(1f, sizePx);
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (mRect.isEmpty() || mRect.width() <= 0f || mRect.height() <= 0f) return;

        final float radius = Math.min(mRadius, Math.min(mRect.width(), mRect.height()) / 2f);
        mFillPaint.setColor(mFillColor);
        canvas.drawRoundRect(mRect, radius, radius, mFillPaint);

        final Bitmap bitmap = mCardBitmap;
        if (bitmap != null && !bitmap.isRecycled() && mContentAlpha > 0f) {
            mBitmapPaint.setAlpha(Math.round(mContentAlpha * 255f));
            canvas.save();
            mClipPath.reset();
            mClipPath.addRoundRect(mRect, radius, radius, Path.Direction.CW);
            canvas.clipPath(mClipPath);
            canvas.drawBitmap(bitmap, null, mRect, mBitmapPaint);
            canvas.restore();
        }

        drawStroke(canvas, radius);

        final Drawable plus = mPlusIcon;
        if (plus != null && mPlusAlpha > 0f) {
            final int size = Math.max(1, Math.round(mPlusIconSizePx));
            final float cx = mRect.centerX();
            final float cy = mRect.centerY();
            final int left = Math.round(cx - size / 2f);
            final int top = Math.round(cy - size / 2f);
            plus.setBounds(left, top, left + size, top + size);
            plus.setAlpha(Math.round(mPlusAlpha * 255f));
            plus.draw(canvas);
        }
    }

    /**
     * The button's GradientDrawable stroke is clipped to its oval, so only the inner half is
     * visible. Inset by half the width to reproduce that on the ghost and to match
     * {@link SessionOverviewCardView}, which also draws its stroke fully inside the card outline.
     */
    private void drawStroke(@NonNull Canvas canvas, float radius) {
        if (mStrokeWidthPx <= 0f || Color.alpha(mStrokeColor) == 0
                || mRect.width() <= mStrokeWidthPx || mRect.height() <= mStrokeWidthPx) {
            return;
        }
        final float inset = mStrokeWidthPx / 2f;
        final float strokeRadius = Math.max(0f, radius - inset);
        mStrokePaint.setColor(mStrokeColor);
        canvas.drawRoundRect(
                mRect.left + inset, mRect.top + inset,
                mRect.right - inset, mRect.bottom - inset,
                strokeRadius, strokeRadius, mStrokePaint);
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) return 0f;
        if (value <= 0f) return 0f;
        if (value >= 1f) return 1f;
        return value;
    }
}
