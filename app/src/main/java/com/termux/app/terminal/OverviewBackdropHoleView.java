package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Trace;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Clears the window decor under the flying terminal during an overview flight.
 *
 * <p>The activity paints the fading background colour on the decor view before any child is
 * drawn. This view is the first child of the overview ghost layer, so it clears the whole rounded
 * flight rect before the ghost is composited over it. The ghost view supplies the terminal
 * background itself at the live alpha; without the hole the same alpha would be stacked twice.
 * At a flight endpoint a tint is drawn back into the hole so the hand-off to the resting card has
 * no darkening step.</p>
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
    /** Integer bounds used only for damage rectangles. */
    private final Rect mHole = new Rect();
    private final Rect mInvalidateRect = new Rect();
    /** Exact rounded outline of the flight. */
    private final RectF mHoleF = new RectF();
    private float mCornerRadius;
    private final Path mHolePath = new Path();

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

    /** Update the cut-out shape and its endpoint tint in one pass. */
    void setContent(@Nullable Rect hole, float cornerRadius, int fillColor, int fillAlpha) {
        if (mHole.isEmpty()) {
            mInvalidateRect.setEmpty();
        } else {
            mInvalidateRect.set(mHole);
        }

        final boolean geometryChanged;
        if (hole == null || hole.isEmpty()) {
            geometryChanged = !mHole.isEmpty();
            mHole.setEmpty();
            mHoleF.setEmpty();
            mCornerRadius = 0f;
        } else {
            final float radius = Math.max(0f,
                    Math.min(cornerRadius, Math.min(hole.width(), hole.height()) / 2f));
            geometryChanged = mHole.left != hole.left
                    || mHole.top != hole.top
                    || mHole.right != hole.right
                    || mHole.bottom != hole.bottom
                    || mCornerRadius != radius;
            mHole.set(hole);
            mHoleF.set(hole.left, hole.top, hole.right, hole.bottom);
            mCornerRadius = radius;
        }

        fillAlpha = Math.max(0, Math.min(255, fillAlpha));
        final boolean fillChanged = mFillColor != fillColor || mFillAlpha != fillAlpha;
        mFillColor = fillColor;
        mFillAlpha = fillAlpha;
        if (!geometryChanged && !fillChanged) return;

        if (geometryChanged) {
            // The outline is a pure function of the rect and radius. Rebuild it here, not on
            // every draw: most frames only change mFillAlpha.
            mHolePath.reset();
            if (!mHole.isEmpty() && mCornerRadius > 0f) {
                mHolePath.addRoundRect(mHoleF, mCornerRadius, mCornerRadius, Path.Direction.CW);
            }
            if (!mInvalidateRect.isEmpty()) invalidate(mInvalidateRect);
            if (!mHole.isEmpty()) invalidate(mHole);
        } else if (!mHole.isEmpty()) {
            invalidate(mHole);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (mHole.isEmpty()) return;
        Trace.beginSection("overview.hole.draw");

        if (mCornerRadius <= 0f) {
            canvas.drawRect(mHoleF, mClearPaint);
        } else {
            canvas.drawPath(mHolePath, mClearPaint);
        }
        if (mFillAlpha > 0) {
            mFillPaint.setColor((mFillAlpha << 24) | (mFillColor & 0x00FFFFFF));
            if (mCornerRadius <= 0f) {
                canvas.drawRect(mHoleF, mFillPaint);
            } else {
                canvas.drawPath(mHolePath, mFillPaint);
            }
        }
        Trace.endSection();
    }
}
