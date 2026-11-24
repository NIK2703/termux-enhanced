package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Shapes the decor colour under the flying terminal during an overview flight.
 *
 * <p>The activity paints the fading background colour on the decor view before any child is
 * drawn. This view is the first child of the overview ghost layer, so it clears a rounded hole
 * in that colour before the flying terminal snapshot is composited over it. The snapshot
 * already carries the terminal background at the configured alpha; without the hole the same
 * alpha would be stacked twice. At a flight endpoint a tint is drawn back into the hole so the
 * hand-off to the resting card has no darkening step.</p>
 *
 * <p>The window must be translucent when this view is used. The caller only installs it while
 * the wallpaper feature is enabled; on an opaque window {@link PorterDuff.Mode#CLEAR} would show
 * black instead of the wallpaper.</p>
 */
final class OverviewBackdropHoleView extends View {

    private final Paint mClearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int mFillColor;
    private int mFillAlpha;
    private final Rect mHole = new Rect();
    private final Rect mInvalidateRect = new Rect();
    private final RectF mHoleF = new RectF();
    private final Path mHolePath = new Path();
    private final float[] mRadii = new float[8];
    private float mTopRadius;
    private float mBottomRadius;

    OverviewBackdropHoleView(@NonNull Context context) {
        super(context);
        mClearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Drawing straight into the window surface is required: a hardware layer would clear
        // only the layer, leaving the decor background below it intact.
    }

    /**
     * Update the cut-out shape and its endpoint tint in one pass.
     *
     * <p>The radii follow the flying terminal's visible shape: top corners match the ghost
     * outline, bottom corners only when the scaled bitmap fills the view. The tint ramps back in
     * at a flight endpoint so the hand-off to the resting card has no darkening step.</p>
     */
    void setContent(@Nullable Rect hole, float topRadius, float bottomRadius,
                    int fillColor, int fillAlpha) {
        // CLEAR is destructive: the area the hole vacates must be repainted by the views below.
        if (mHole.isEmpty()) {
            mInvalidateRect.setEmpty();
        } else {
            mInvalidateRect.set(mHole);
        }

        final boolean geometryChanged;
        if (hole == null || hole.isEmpty()) {
            geometryChanged = !mHole.isEmpty();
            mHole.setEmpty();
            mTopRadius = 0f;
            mBottomRadius = 0f;
        } else {
            final float limit = Math.min(hole.width(), hole.height()) / 2f;
            final float top = Math.max(0f, Math.min(topRadius, limit));
            final float bottom = Math.max(0f, Math.min(bottomRadius, limit));
            geometryChanged = !mHole.equals(hole)
                    || mTopRadius != top || mBottomRadius != bottom;
            mHole.set(hole);
            mTopRadius = top;
            mBottomRadius = bottom;
        }

        fillAlpha = Math.max(0, Math.min(255, fillAlpha));
        final boolean fillChanged = mFillColor != fillColor || mFillAlpha != fillAlpha;
        mFillColor = fillColor;
        mFillAlpha = fillAlpha;
        if (!geometryChanged && !fillChanged) return;

        // Invalidate only the moving cut-out, not the whole match-parent view. Decor already
        // repaints the full window each frame, but this view must not add a second full-screen
        // damage region of its own.
        if (geometryChanged) {
            // Two small damage rects are cheaper than their (possibly much larger) union.
            if (!mInvalidateRect.isEmpty()) invalidate(mInvalidateRect);
            if (!mHole.isEmpty()) invalidate(mHole);
        } else if (!mHole.isEmpty()) {
            invalidate(mHole);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (mHole.isEmpty()) return;
        if (mTopRadius <= 0f && mBottomRadius <= 0f) {
            // No rounding yet: clear and tint the rect directly, without canvas clip state.
            canvas.drawRect(mHole, mClearPaint);
            if (mFillAlpha > 0) {
                mFillPaint.setColor((mFillAlpha << 24) | (mFillColor & 0x00FFFFFF));
                canvas.drawRect(mHole, mFillPaint);
            }
            return;
        }

        mHoleF.set(mHole);
        mHolePath.reset();
        if (mTopRadius == mBottomRadius) {
            // Normal case: the whole outline rounds uniformly.
            mHolePath.addRoundRect(mHoleF, mTopRadius, mTopRadius, Path.Direction.CW);
        } else {
            // Empty strip under a width-scaled snapshot: only the top corners follow the outline.
            mRadii[0] = mTopRadius;
            mRadii[1] = mTopRadius;
            mRadii[2] = mTopRadius;
            mRadii[3] = mTopRadius;
            mRadii[4] = mBottomRadius;
            mRadii[5] = mBottomRadius;
            mRadii[6] = mBottomRadius;
            mRadii[7] = mBottomRadius;
            mHolePath.addRoundRect(mHoleF, mRadii, Path.Direction.CW);
        }
        // Draw the shape itself instead of clipping to it: one draw per layer.
        canvas.drawPath(mHolePath, mClearPaint);
        if (mFillAlpha > 0) {
            mFillPaint.setColor((mFillAlpha << 24) | (mFillColor & 0x00FFFFFF));
            canvas.drawPath(mHolePath, mFillPaint);
        }
    }
}
