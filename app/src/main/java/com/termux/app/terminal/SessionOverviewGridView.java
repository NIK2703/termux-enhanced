package com.termux.app.terminal;

import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.termux.R;

/**
 * Lays out all session cards in a centered two-column grid with two visible rows.
 *
 * <p>The grid is measured from the area it is actually given, never from display metrics. It
 * therefore follows rotation, split-screen resizes, foldables and any density without a separate
 * configuration path. A fifth session adds another row below the viewport; the surrounding
 * {@link SessionOverviewScrollView} makes those rows reachable. Empty slots are left at the end
 * and are not clickable because no view is created for them.</p>
 */
public class SessionOverviewGridView extends ViewGroup {

    public interface OnGridLayoutListener {
        void onGridLayout(int cardWidth, int cardHeight, int columns, int rows);
    }

    private int mGapPx;
    private int mViewportHeight;
    private SessionOverviewGridMath.Layout mLayout;
    private OnGridLayoutListener mOnGridLayoutListener;
    private int mLastCardWidth = -1;
    private int mLastCardHeight = -1;
    private int mLastColumns = -1;
    private int mLastRows = -1;

    public SessionOverviewGridView(Context context) {
        this(context, null);
    }

    public SessionOverviewGridView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mGapPx = Math.round(getResources().getDimension(R.dimen.session_overview_gap));
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mGapPx = Math.round(getResources().getDimension(R.dimen.session_overview_gap));
        requestLayout();
    }

    public void setOnGridLayoutListener(@Nullable OnGridLayoutListener listener) {
        mOnGridLayoutListener = listener;
    }

    /**
     * Set the height of the visible viewport. The grid is taller than this when there are more
     * than four sessions; the surrounding scroll view uses that extra height to scroll.
     *
     * <p>Called by {@link SessionOverviewScrollView} immediately before it measures its child.
     * It deliberately does not call {@code requestLayout()}: the caller is already measuring, and
     * a layout request from inside a measure pass would schedule a redundant traversal.</p>
     */
    public void setViewportHeight(int height) {
        mViewportHeight = Math.max(0, height);
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
     * Centre of every slot for a grid holding {@code sessionCount} cards, in this view's
     * coordinates and interleaved as {@code x0, y0, x1, y1, ...}.
     *
     * <p>Used when the model changes while the overview is open: the new cards have not been
     * laid out yet, so their final centres have to come from the same geometry that
     * {@link #onLayout} will use. Returns null until the view has a usable size.</p>
     */
    @Nullable
    public float[] getSlotCenters(int sessionCount) {
        if (sessionCount <= 0 || getWidth() <= 0) return null;
        final int viewportHeight = mViewportHeight > 0 ? mViewportHeight : getHeight();
        final int availableWidth = Math.max(0,
                getWidth() - getPaddingLeft() - getPaddingRight());
        final int availableHeight = Math.max(0,
                viewportHeight - getPaddingTop() - getPaddingBottom());
        final SessionOverviewGridMath.Layout layout = SessionOverviewGridMath.compute(
                sessionCount, availableWidth, availableHeight, mGapPx);
        if (!layout.hasCards()) return null;

        final boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        final int originLeft = getPaddingLeft() + layout.left;
        final int originTop = getPaddingTop() + layout.top;
        final float[] centers = new float[sessionCount * 2];
        for (int i = 0; i < sessionCount; i++) {
            final int row = i / layout.columns;
            final int column = i % layout.columns;
            final int visualColumn = rtl ? (layout.columns - 1 - column) : column;
            final int left = originLeft + visualColumn * (layout.cardWidth + layout.gap);
            final int top = originTop + row * (layout.cardHeight + layout.gap);
            centers[i * 2] = left + layout.cardWidth / 2f;
            centers[i * 2 + 1] = top + layout.cardHeight / 2f;
        }
        return centers;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int measuredWidth = resolveSize(MeasureSpec.getSize(widthMeasureSpec), widthMeasureSpec);
        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        final int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        final int viewportHeight;
        if (heightMode == MeasureSpec.UNSPECIFIED) {
            // Inside a ScrollView the child is measured with an unspecified height. Use the
            // viewport height supplied by the scroll view; the fallback keeps the grid usable if
            // it is ever measured on its own without one.
            viewportHeight = mViewportHeight > 0 ? mViewportHeight : measuredWidth;
        } else {
            viewportHeight = heightSize;
            mViewportHeight = heightSize;
        }

        final int availableWidth = Math.max(0,
                measuredWidth - getPaddingLeft() - getPaddingRight());
        final int availableHeight = Math.max(0,
                viewportHeight - getPaddingTop() - getPaddingBottom());

        mLayout = SessionOverviewGridMath.compute(getChildCount(),
                availableWidth, availableHeight, mGapPx);

        final int childWidth = mLayout.cardWidth;
        final int childHeight = mLayout.cardHeight;
        final int childWidthSpec = MeasureSpec.makeMeasureSpec(Math.max(0, childWidth),
                MeasureSpec.EXACTLY);
        final int childHeightSpec = MeasureSpec.makeMeasureSpec(Math.max(0, childHeight),
                MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(childWidthSpec, childHeightSpec);
        }

        // For up to four sessions the grid is exactly as tall as the viewport and the 2x2 block
        // is centered. More sessions make it taller than the viewport, which is the scroll range.
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
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (mLayout == null) return;

        final int columns = mLayout.columns;
        final int rows = mLayout.rows;
        final int cardWidth = mLayout.cardWidth;
        final int cardHeight = mLayout.cardHeight;
        if (columns <= 0 || rows <= 0) return;

        final boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        final int originLeft = getPaddingLeft() + mLayout.left;
        final int originTop = getPaddingTop() + mLayout.top;

        for (int i = 0; i < getChildCount(); i++) {
            final int row = i / columns;
            final int column = i % columns;
            final int visualColumn = rtl ? (columns - 1 - column) : column;
            final int childLeft = originLeft + visualColumn * (cardWidth + mGapPx);
            final int childTop = originTop + row * (cardHeight + mGapPx);
            getChildAt(i).layout(childLeft, childTop,
                    childLeft + cardWidth, childTop + cardHeight);
        }

        if (cardWidth != mLastCardWidth || cardHeight != mLastCardHeight
                || columns != mLastColumns || rows != mLastRows) {
            mLastCardWidth = cardWidth;
            mLastCardHeight = cardHeight;
            mLastColumns = columns;
            mLastRows = rows;
            if (mOnGridLayoutListener != null) {
                mOnGridLayoutListener.onGridLayout(cardWidth, cardHeight, columns, rows);
            }
        }
    }
}
