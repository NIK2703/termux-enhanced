package com.termux.app.terminal;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Trace;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;

/**
 * Lays out session cards from the pure {@link SessionOverviewGridMath.Slot} list.
 *
 * <p>The view never computes rows or columns of its own. It applies the slot rectangles produced
 * by the shared geometry, mirrors them for RTL, and publishes the whole layout to listeners. A
 * single card is therefore centred on both axes by the same code that centres any other content
 * bound.</p>
 */
public class SessionOverviewGridView extends ViewGroup {

    public interface OnGridLayoutListener {
        void onGridLayout(@NonNull SessionOverviewGridMath.Layout layout);
    }

    private int mGapPx;
    /** Larger breathing space above the first row and below the last row. */
    private int mOuterGapPx;
    private int mViewportHeight;
    /**
     * Largest viewport this grid has been measured with at the current width. The keyboard
     * briefly shrinks the scroll view; cards must not shrink with it. The maximum is the
     * keyboard-hidden viewport the overview is designed around, and it is reset only when the
     * width changes (rotation or split-screen), where a new geometry is legitimate.
     */
    private int mMaxViewportHeight;
    private int mLastMeasuredWidth = -1;
    /** Terminal-grid aspect; 0 falls back to the content-area aspect. */
    private double mCardAspect;
    /** Exact card size derived from the integer-column raster; ≤0 uses the aspect path. */
    private int mExactCardWidth = -1;
    private int mExactCardHeight = -1;
    private SessionOverviewGridMath.Layout mLayout;
    private OnGridLayoutListener mOnGridLayoutListener;
    private int mLastCardWidth = -1;
    private int mLastCardHeight = -1;
    private int mLastColumns = -1;
    private int mLastRows = -1;
    private int mLastTop = -1;
    private int mLastLeft = -1;
    private int mLastGridHeight = -1;
    private int mLastVisibleHeight = -1;
    private int mLastSessionCount = -1;

    public SessionOverviewGridView(Context context) {
        this(context, null);
    }

    public SessionOverviewGridView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mGapPx = Math.round(getResources().getDimension(R.dimen.session_overview_gap));
        mOuterGapPx = Math.round(
                getResources().getDimension(R.dimen.session_overview_outer_gap));
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mGapPx = Math.round(getResources().getDimension(R.dimen.session_overview_gap));
        mOuterGapPx = Math.round(
                getResources().getDimension(R.dimen.session_overview_outer_gap));
        requestLayout();
    }

    public void setOnGridLayoutListener(@Nullable OnGridLayoutListener listener) {
        mOnGridLayoutListener = listener;
    }

    /**
     * Explicit viewport notification used on configuration/IME changes. Card sizing keeps the
     * stable height; centring uses the visible one. No polling of a layout pass is needed.
     */
    public void onOverviewViewportChanged(int visibleHeight, int stableHeight) {
        final int visible = Math.max(0, visibleHeight);
        final int stable = Math.max(visible, Math.max(0, stableHeight));
        boolean changed = false;
        if (stable > mMaxViewportHeight) {
            mMaxViewportHeight = stable;
            changed = true;
        }
        if (mViewportHeight != visible) {
            mViewportHeight = visible;
            changed = true;
        }
        if (changed) requestLayout();
    }

    /**
     * Set the height of the visible viewport. Called by the scroll view immediately before it
     * measures its child.
     *
     * <p>The scroll view measures the grid with an unspecified height, so a viewport change alone
     * does not alter the measure specs. Without {@link #forceLayout()} the platform would reuse
     * the cached measurement and the grid would keep the old viewport (the keyboard would cover
     * rows it should have made scrollable). Forcing layout does not schedule a traversal; the
     * caller is already measuring and will run {@link #onMeasure} in the same pass.</p>
     */
    public void setViewportHeight(int height) {
        final int safeHeight = Math.max(0, height);
        if (safeHeight == mViewportHeight) return;
        mViewportHeight = safeHeight;
        if (safeHeight > mMaxViewportHeight) mMaxViewportHeight = safeHeight;
        forceLayout();
    }

    public void setCardAspect(double aspect) {
        final double next = (aspect > 0
                && !Double.isNaN(aspect) && !Double.isInfinite(aspect)) ? aspect : 0;
        if (mCardAspect == next) return;
        mCardAspect = next;
        requestLayout();
    }

    /** Use an exact card size already fitted to the current slot. */
    public void setCardSize(int widthPx, int heightPx) {
        final int width = Math.max(0, widthPx);
        final int height = Math.max(0, heightPx);
        if (mExactCardWidth == width && mExactCardHeight == height) return;
        mExactCardWidth = width;
        mExactCardHeight = height;
        requestLayout();
    }

    /** Largest card the current policy can place, packed as {@code {maxWidth, maxHeight}}. */
    public int[] getMaxCardSlotSize() {
        final int width = getWidth() > 0 ? getWidth() : mLastMeasuredWidth;
        final int stableHeight = Math.max(mViewportHeight, mMaxViewportHeight);
        final int viewportHeight = stableHeight > 0 ? stableHeight : getHeight();
        final int availableWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        final int availableHeight = Math.max(0,
                viewportHeight - getPaddingTop() - getPaddingBottom());
        final int columns = SessionOverviewGridMath.COLUMNS;
        final int visibleRows = SessionOverviewGridMath.VISIBLE_ROWS;
        final int maxWidth = Math.max(0,
                (availableWidth - mGapPx * (columns + 1)) / columns);
        final int maxHeight = Math.max(0,
                (availableHeight - (mGapPx * (visibleRows - 1) + mOuterGapPx * 2)) / visibleRows);
        return new int[]{maxWidth, maxHeight};
    }

    private SessionOverviewGridMath.Layout computeLayout(int sessionCount, int availableWidth,
                                                          int stableHeight, int visibleHeight) {
        final SessionOverviewGridMath.Layout layout;
        if (mExactCardWidth > 0 && mExactCardHeight > 0) {
            layout = SessionOverviewGridMath.compute(sessionCount, availableWidth,
                    stableHeight, mGapPx, mOuterGapPx, mExactCardWidth, mExactCardHeight);
        } else {
            layout = SessionOverviewGridMath.compute(sessionCount, availableWidth,
                    stableHeight, mGapPx, mOuterGapPx, mCardAspect);
        }
        return SessionOverviewGridMath.recenter(layout, visibleHeight);
    }

    @Nullable
    public SessionOverviewGridMath.Layout getGridLayout() {
        return mLayout;
    }

    public int getCardWidth() {
        return mLayout != null ? mLayout.cardWidth : 0;
    }

    public int getCardHeight() {
        return mLayout != null ? mLayout.cardHeight : 0;
    }

    /**
     * First (inclusive) and exclusive end child index whose slot intersects the viewport. This
     * scans the same slot rectangles that {@link #onLayout} uses, so no row/column arithmetic is
     * duplicated here.
     */
    public void getVisibleChildRange(int scrollY, int viewportHeight, int[] out) {
        final int childCount = getChildCount();
        out[0] = 0;
        out[1] = childCount;
        final SessionOverviewGridMath.Layout layout = mLayout;
        if (layout == null || !layout.hasCards() || childCount == 0) return;
        if (viewportHeight <= 0) return;

        final int viewportTop = scrollY - getTop();
        final int viewportBottom = viewportTop + viewportHeight;
        final boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int first = childCount;
        int end = 0;
        for (int i = 0; i < childCount; i++) {
            final SessionOverviewGridMath.Slot slot = layout.slot(i);
            if (slot == null) continue;
            final int top = getPaddingTop() + slot.top;
            final int bottom = top + layout.cardHeight;
            if (bottom > viewportTop && top < viewportBottom) {
                if (i < first) first = i;
                if (i + 1 > end) end = i + 1;
            }
        }
        out[0] = first;
        out[1] = end;
    }

    /**
     * Centre of every slot for a grid holding {@code sessionCount} cards, in this view's
     * coordinates and interleaved as {@code x0, y0, x1, y1, ...}.
     */
    @Nullable
    public float[] getSlotCenters(int sessionCount) {
        if (sessionCount <= 0 || getWidth() <= 0) return null;
        final int stableHeight = Math.max(mViewportHeight, mMaxViewportHeight);
        final int stableViewportHeight = stableHeight > 0 ? stableHeight : getHeight();
        final int currentViewportHeight = mViewportHeight > 0 ? mViewportHeight : getHeight();
        final int availableWidth = Math.max(0,
                getWidth() - getPaddingLeft() - getPaddingRight());
        final int stableAvailableHeight = Math.max(0,
                stableViewportHeight - getPaddingTop() - getPaddingBottom());
        final int visibleAvailableHeight = Math.max(0,
                currentViewportHeight - getPaddingTop() - getPaddingBottom());
        final SessionOverviewGridMath.Layout layout = computeLayout(
                sessionCount, availableWidth, stableAvailableHeight, visibleAvailableHeight);
        if (!layout.hasCards()) return null;

        final boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        final float[] centers = new float[sessionCount * 2];
        for (int i = 0; i < sessionCount; i++) {
            centers[i * 2] = getPaddingLeft() + layout.centerX(i, rtl);
            centers[i * 2 + 1] = getPaddingTop() + layout.centerY(i);
        }
        return centers;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Trace.beginSection("overview.grid.measure");
        final int measuredWidth = resolveSize(MeasureSpec.getSize(widthMeasureSpec), widthMeasureSpec);
        if (measuredWidth != mLastMeasuredWidth) {
            mLastMeasuredWidth = measuredWidth;
            mMaxViewportHeight = 0;
        }
        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        final int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        final int viewportHeight;
        if (heightMode == MeasureSpec.UNSPECIFIED) {
            viewportHeight = mViewportHeight > 0 ? mViewportHeight : measuredWidth;
        } else {
            viewportHeight = heightSize;
            mViewportHeight = heightSize;
        }
        if (viewportHeight > mMaxViewportHeight) mMaxViewportHeight = viewportHeight;

        final int availableWidth = Math.max(0,
                measuredWidth - getPaddingLeft() - getPaddingRight());
        final int layoutViewportHeight = Math.max(viewportHeight, mMaxViewportHeight);
        final int availableHeight = Math.max(0,
                layoutViewportHeight - getPaddingTop() - getPaddingBottom());
        final int visibleAvailableHeight = Math.max(0,
                viewportHeight - getPaddingTop() - getPaddingBottom());

        mLayout = computeLayout(getChildCount(), availableWidth, availableHeight,
                visibleAvailableHeight);

        final int childWidthSpec = MeasureSpec.makeMeasureSpec(
                Math.max(0, mLayout.cardWidth), MeasureSpec.EXACTLY);
        final int childHeightSpec = MeasureSpec.makeMeasureSpec(
                Math.max(0, mLayout.cardHeight), MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(childWidthSpec, childHeightSpec);
        }

        final int contentHeight = mLayout.gridHeight + getPaddingTop() + getPaddingBottom();
        final int desiredHeight = Math.max(contentHeight, viewportHeight);
        final int measuredHeight;
        if (heightMode == MeasureSpec.EXACTLY) {
            measuredHeight = heightSize;
        } else if (heightMode == MeasureSpec.AT_MOST) {
            measuredHeight = Math.min(desiredHeight, heightSize);
        } else {
            measuredHeight = desiredHeight;
        }
        setMeasuredDimension(measuredWidth, measuredHeight);
        Trace.endSection();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final SessionOverviewGridMath.Layout layout = mLayout;
        if (layout == null || !layout.hasCards()) return;
        Trace.beginSection("overview.grid.layout");
        final boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

        for (int i = 0; i < getChildCount(); i++) {
            final SessionOverviewGridMath.Slot slot = layout.slot(i);
            final View child = getChildAt(i);
            if (slot == null || child == null) continue;
            final int childLeft = getPaddingLeft() + layout.visualLeft(i, rtl);
            final int childTop = getPaddingTop() + slot.top;
            child.layout(childLeft, childTop,
                    childLeft + layout.cardWidth, childTop + layout.cardHeight);
        }

        if (layout.cardWidth != mLastCardWidth || layout.cardHeight != mLastCardHeight
                || layout.columns != mLastColumns || layout.rows != mLastRows
                || layout.top != mLastTop || layout.left != mLastLeft
                || layout.gridHeight != mLastGridHeight
                || layout.visibleHeight != mLastVisibleHeight
                || layout.sessionCount != mLastSessionCount) {
            mLastCardWidth = layout.cardWidth;
            mLastCardHeight = layout.cardHeight;
            mLastColumns = layout.columns;
            mLastRows = layout.rows;
            mLastTop = layout.top;
            mLastLeft = layout.left;
            mLastGridHeight = layout.gridHeight;
            mLastVisibleHeight = layout.visibleHeight;
            mLastSessionCount = layout.sessionCount;
            if (mOnGridLayoutListener != null) {
                mOnGridLayoutListener.onGridLayout(layout);
            }
        }
        Trace.endSection();
    }
}
