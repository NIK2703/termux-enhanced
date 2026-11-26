package com.termux.app.terminal;

import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.termux.R;

/**
 * Lays out all session cards in a top-aligned two-column grid with two visible rows.
 *
 * <p>The grid is measured from the area it is actually given, never from display metrics. It
 * therefore follows rotation, split-screen resizes, foldables and any density without a separate
 * configuration path. The block is horizontally centered; when it is shorter than the visible
 * viewport, the unused vertical space is split above the first row and below the last one so the
 * cards are vertically centered too. A fifth session adds another row below the viewport; the
 * surrounding {@link SessionOverviewScrollView} makes those rows reachable. Empty slots are left
 * at the end and are not clickable because no view is created for them.</p>
 */
public class SessionOverviewGridView extends ViewGroup {

    public interface OnGridLayoutListener {
        void onGridLayout(int cardWidth, int cardHeight, int columns, int rows);
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
    /** Exact card size derived from the preview's integer cell pitch; -1 uses the aspect path. */
    private int mExactCardWidth = -1;
    private int mExactCardHeight = -1;
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
     * Set the height of the visible viewport. The grid is taller than this when there are more
     * than four sessions; the surrounding scroll view uses that extra height to scroll.
     *
     * <p>Called by {@link SessionOverviewScrollView} immediately before it measures its child.
     * It deliberately does not call {@code requestLayout()}: the caller is already measuring, and
     * a layout request from inside a measure pass would schedule a redundant traversal.</p>
     */
    public void setViewportHeight(int height) {
        final int safeHeight = Math.max(0, height);
        mViewportHeight = safeHeight;
        if (safeHeight > mMaxViewportHeight) mMaxViewportHeight = safeHeight;
    }

    /**
     * Set the width/height ratio of the terminal grid a card represents.
     *
     * <p>Cards use this instead of the content-area ratio so the preview's glyph grid fills the
     * whole thumbnail. A value at or below zero restores the content-area fallback.</p>
     */
    public void setCardAspect(double aspect) {
        final double next = (aspect > 0
                && !Double.isNaN(aspect) && !Double.isInfinite(aspect)) ? aspect : 0;
        if (mCardAspect == next) return;
        mCardAspect = next;
        requestLayout();
    }

    /**
     * Use an exact card size instead of deriving one from the terminal aspect.
     *
     * <p>The caller has already fitted the integer cell pitch to the slot returned by
     * {@link #getMaxCardSlotSize()}, so no further rounding happens here.</p>
     */
    public void setCardSize(int widthPx, int heightPx) {
        final int width = Math.max(0, widthPx);
        final int height = Math.max(0, heightPx);
        if (mExactCardWidth == width && mExactCardHeight == height) return;
        mExactCardWidth = width;
        mExactCardHeight = height;
        requestLayout();
    }

    /**
     * Largest card the current viewport can hold, before the cell pitch is applied.
     *
     * @return packed {@code {maxWidth, maxHeight}} in pixels
     */
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
     * First (inclusive) and exclusive end child index whose slots intersect the viewport.
     *
     * <p>One arithmetic pass reads {@link #mLayout} once and writes into {@code out}, so a render
     * pass performs no allocation and no duplicated pitch/padding calculation. A zero viewport
     * is the first-frame fallback: the whole grid is returned.</p>
     */
    public void getVisibleChildRange(int scrollY, int viewportHeight, int[] out) {
        final int childCount = getChildCount();
        out[0] = 0;
        out[1] = childCount;

        final SessionOverviewGridMath.Layout layout = mLayout;
        if (layout == null || layout.columns <= 0 || layout.rows <= 0
                || layout.cardHeight <= 0 || childCount == 0) return;
        final int pitch = layout.cardHeight + layout.gap;
        if (pitch <= 0) return;

        final int contentTop = getPaddingTop() + layout.top;
        final int top = scrollY - getTop();
        final int firstRow;
        if (viewportHeight <= 0) {
            firstRow = 0;
        } else {
            final int distance = top - contentTop - layout.cardHeight;
            firstRow = distance < 0 ? 0 : distance / pitch + 1;
        }
        if (firstRow >= layout.rows) {
            out[0] = childCount;
            out[1] = childCount;
            return;
        }
        out[0] = Math.min(childCount, firstRow * layout.columns);

        if (viewportHeight <= 0) {
            out[1] = childCount;
            return;
        }
        final int distance = top + viewportHeight - contentTop;
        if (distance <= 0) {
            out[1] = 0;
            return;
        }
        final int endRow = Math.min(layout.rows, (distance - 1) / pitch + 1);
        out[1] = Math.min(childCount, endRow * layout.columns);
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
        if (measuredWidth != mLastMeasuredWidth) {
            mLastMeasuredWidth = measuredWidth;
            // A width change is a real geometry change (rotation, split-screen, fold). The
            // keyboard only changes height, so its maximum survives the IME animation.
            mMaxViewportHeight = 0;
        }
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
        if (viewportHeight > mMaxViewportHeight) mMaxViewportHeight = viewportHeight;

        final int availableWidth = Math.max(0,
                measuredWidth - getPaddingLeft() - getPaddingRight());
        // Size cards from the keyboard-hidden viewport even while the IME is still shrinking
        // the scroll view. The grid then scrolls for the few frames before the keyboard is
        // gone, instead of resizing every thumbnail twice.
        final int layoutViewportHeight = Math.max(viewportHeight, mMaxViewportHeight);
        final int availableHeight = Math.max(0,
                layoutViewportHeight - getPaddingTop() - getPaddingBottom());
        // Centring follows the height the user can actually see. Keeping the stable height here
        // would let an old, taller viewport push the grid against the bottom edge.
        final int visibleAvailableHeight = Math.max(0,
                viewportHeight - getPaddingTop() - getPaddingBottom());

        mLayout = computeLayout(getChildCount(), availableWidth, availableHeight,
                visibleAvailableHeight);

        final int childWidth = mLayout.cardWidth;
        final int childHeight = mLayout.cardHeight;
        final int childWidthSpec = MeasureSpec.makeMeasureSpec(Math.max(0, childWidth),
                MeasureSpec.EXACTLY);
        final int childHeightSpec = MeasureSpec.makeMeasureSpec(Math.max(0, childHeight),
                MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(childWidthSpec, childHeightSpec);
        }

        // For up to four sessions the grid is shorter than the viewport and is centred by
        // Layout.top. More sessions make it taller than the viewport, which is the scroll range;
        // the outer bottom gutter is the last thing the user can scroll to.
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
            // Use the layout's gap, which spreads the leftover width evenly across the
            // outer gutters and the gaps between cards.
            final int childLeft = originLeft + visualColumn * (cardWidth + mLayout.gap);
            final int childTop = originTop + row * (cardHeight + mLayout.gap);
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
