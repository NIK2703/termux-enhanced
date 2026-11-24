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
 * Clears a rounded rectangle out of the window decor while an overview flight is running.
 *
 * <p>The activity paints the fading background colour on the decor view before any child is
 * drawn. This view is the first child of the overview ghost layer, so it punches a transparent
 * hole in that colour before the flying terminal snapshot is composited over it. The snapshot
 * already carries the terminal background at the configured alpha; without the hole the same
 * alpha would be stacked twice underneath it.</p>
 *
 * <p>The window must be translucent when this view is used. The caller only installs it while
 * the wallpaper feature is enabled; on an opaque window {@link PorterDuff.Mode#CLEAR} would show
 * black instead of the wallpaper.</p>
 */
final class OverviewBackdropHoleView extends View {

    private final Paint mClearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mHole = new Rect();
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
     * Set the hole in this view's coordinates; {@code null} or an empty rect disables it.
     *
     * <p>The radii must follow the flying terminal's visible shape. The top corners match the
     * ghost outline. The bottom corners only match it when the bitmap fills the whole view;
     * {@link TopCropImageView} leaves the bottom empty when the view is taller than the scaled
     * bitmap, and that strip must keep the decor tint instead of being cut out.</p>
     */
    void setHole(@Nullable Rect hole, float topRadius, float bottomRadius) {
        final boolean changed;
        if (hole == null || hole.isEmpty()) {
            changed = !mHole.isEmpty();
            mHole.setEmpty();
            mTopRadius = 0f;
            mBottomRadius = 0f;
        } else {
            final float limit = Math.min(hole.width(), hole.height()) / 2f;
            final float top = Math.max(0f, Math.min(topRadius, limit));
            final float bottom = Math.max(0f, Math.min(bottomRadius, limit));
            changed = !mHole.equals(hole) || mTopRadius != top || mBottomRadius != bottom;
            mHole.set(hole);
            mTopRadius = top;
            mBottomRadius = bottom;
        }
        if (changed) invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (mHole.isEmpty()) return;
        mHoleF.set(mHole);
        mRadii[0] = mTopRadius;
        mRadii[1] = mTopRadius;
        mRadii[2] = mTopRadius;
        mRadii[3] = mTopRadius;
        mRadii[4] = mBottomRadius;
        mRadii[5] = mBottomRadius;
        mRadii[6] = mBottomRadius;
        mRadii[7] = mBottomRadius;
        mHolePath.reset();
        mHolePath.addRoundRect(mHoleF, mRadii, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(mHolePath);
        canvas.drawRect(mHole, mClearPaint);
        canvas.restore();
    }
}
