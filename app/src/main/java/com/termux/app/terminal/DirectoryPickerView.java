package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the directory list of the right-swipe picker over the placeholder page.
 *
 * <p>The rows are painted straight onto the page rather than built as child views: the whole list
 * lives for the duration of one gesture, is at most ten rows, and repositions only when the finger
 * moves — a Canvas pass avoids both the view inflation and the layout pass that a
 * {@code LinearLayout} of {@code TextView}s would cost on the swipe's critical path.
 *
 * <p>Coordinates are page-local (the view is {@code match_parent} inside the page), and the geometry
 * comes verbatim from {@link DirectoryPickerLayout.Result}, so the drawing and the hit-testing in
 * {@link DirectoryPickerController} can never disagree about where a row is.
 *
 * <p>Rows are only as long as the part of the page that is currently on screen (see
 * {@link #setRevealedWidth(float)}), so the list grows into view with the page instead of being laid
 * out at full length the instant it appears.
 *
 * <h2>Truncation by occlusion, not by recomposition</h2>
 *
 * <p>Every path in the history shares the same long prefix
 * ({@code /data/data/com.termux/files/home/…}), so the <em>tail</em> is what tells one entry from
 * another. The label is therefore right-aligned to the revealed edge: its tail is pinned to the part
 * of the page that is already on screen, and its head simply runs off the page's left edge.
 *
 * <p>That left edge is not an arbitrary cut. While the gesture is in progress the page is still
 * sliding in, so its left edge coincides exactly with the right edge of the neighbouring terminal —
 * the truncated head reads as continuing <em>under</em> that terminal. The clip does all the work:
 * there is no {@code ellipsize()} call, no per-frame text measurement and nothing recomputed as the
 * reveal grows. Label widths are measured once per item set (see {@link #setItems}) and reused.
 *
 * <p>No background is painted: the unbound {@code TerminalView} underneath already fills the page
 * with the scheme colour at the configured transparency, and a second layer here would double it.
 * Only the highlighted row gets a fill.
 */
public final class DirectoryPickerView extends View {

    private final TextPaint mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSeparatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRowRect = new RectF();

    private final ArrayList<String> mItems = new ArrayList<>();
    /**
     * Cached label widths, px — measured once per item set (and on a text-size change) so the draw
     * pass never measures text. Indexed alongside {@link #mItems}; entries past the current size are
     * simply not read.
     */
    private float[] mLabelWidths = new float[0];

    @Nullable
    private DirectoryPickerLayout.Result mLayout;
    private int mHighlight = -1;

    private float mPaddingH;
    private float mCornerRadius;
    /**
     * Width of the part of this page that is currently revealed, px. During the swipe the page
     * slides in from the right, so this is the page-local {@code [0, revealed]} slice — rows are
     * drawn no longer than that. {@link Float#MAX_VALUE} means "not constrained" (fully settled).
     */
    private float mRevealedWidth = Float.MAX_VALUE;

    public DirectoryPickerView(Context context) {
        this(context, null);
    }

    public DirectoryPickerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mTextPaint.setStyle(Paint.Style.FILL);
        mFillPaint.setStyle(Paint.Style.FILL);
        mSeparatorPaint.setStyle(Paint.Style.STROKE);
        mSeparatorPaint.setStrokeWidth(1f);
    }

    /** Text size in px. */
    public void setLabelTextSize(float px) {
        mTextPaint.setTextSize(px);
        measureLabels();
        invalidate();
    }

    /** Horizontal padding inside a row and the corner radius of the highlight, in px. */
    public void setMetrics(float paddingH, float cornerRadius) {
        mPaddingH = paddingH;
        mCornerRadius = cornerRadius;
        invalidate();
    }

    public void setColors(int textColor, int highlightFill, int separatorColor) {
        mTextPaint.setColor(textColor);
        mFillPaint.setColor(highlightFill);
        mSeparatorPaint.setColor(separatorColor);
        invalidate();
    }

    /**
     * Install a new list + geometry. Pass an empty list (or a null layout) to clear.
     *
     * <p>This is the one place labels are measured, so the per-frame draw pass never touches the
     * font engine.
     */
    public void setItems(@NonNull List<String> items, @Nullable DirectoryPickerLayout.Result layout) {
        mItems.clear();
        mItems.addAll(items);
        mLayout = layout;
        mHighlight = -1;
        measureLabels();
        invalidate();
    }

    /** Highlight the row under the finger; {@code -1} clears it. */
    public void setHighlight(int index) {
        if (index == mHighlight) return;
        mHighlight = index;
        invalidate();
    }

    /**
     * How much of this page is currently on screen, in px — the rows are drawn no longer than this,
     * so the list is revealed together with the page instead of appearing at full length.
     *
     * <p>Pass {@link Float#MAX_VALUE} (or anything past the view's width) to draw full-width.
     */
    public void setRevealedWidth(float px) {
        final float clamped = Math.max(0f, px);
        if (clamped == mRevealedWidth) return;
        mRevealedWidth = clamped;
        invalidate();
    }

    /**
     * Measure every label once, so the draw pass can right-align without touching the font engine.
     * Called on install and on a text-size change — never per frame.
     */
    private void measureLabels() {
        final int count = mItems.size();
        if (mLabelWidths.length < count) mLabelWidths = new float[count];
        for (int i = 0; i < count; i++) {
            mLabelWidths[i] = mTextPaint.measureText(mItems.get(i));
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        final DirectoryPickerLayout.Result layout = mLayout;
        if (layout == null || !layout.hasList() || mItems.isEmpty()) return;

        // Rows stop where the page stops being visible. A page that is only 5% revealed shows a
        // 5%-wide stub of a row, which is exactly the "grows into view" read we want.
        final float rowWidth = Math.min(mRevealedWidth, getWidth());
        if (rowWidth <= 0f) return;

        final int rowCount = Math.min(mItems.size(), layout.rows);
        final float rowHeight = layout.rowHeight;
        final float textRight = rowWidth - mPaddingH;
        // Vertically centre the text inside the row: the baseline sits at half the row plus half the
        // font's own ascent/descent span, which keeps it optically centred for any typeface.
        final float baselineOffset = (rowHeight - (mTextPaint.descent() + mTextPaint.ascent())) / 2f;

        for (int i = 0; i < rowCount; i++) {
            final float top = layout.listTop + i * rowHeight;
            final float bottom = top + rowHeight;
            if (bottom <= 0f || top >= getHeight()) continue; // clipped away entirely

            if (i == mHighlight) {
                mRowRect.set(0f, top, rowWidth, bottom);
                canvas.drawRoundRect(mRowRect, mCornerRadius, mCornerRadius, mFillPaint);
            } else if (i > 0 && textRight > mPaddingH) {
                // Hairline between rows so the list stays readable over arbitrary terminal content.
                canvas.drawLine(mPaddingH, top, textRight, top, mSeparatorPaint);
            }

            // Right-aligned to the revealed edge, clipped by the page's own bounds. The tail of the
            // path — the part that identifies the directory — is therefore on screen from the first
            // pixel of the reveal, and the head is simply cut where the neighbouring terminal begins.
            // A label wider than the row needs no special case: the clip is the truncation.
            //
            // The row→entry mapping comes from the layout, so the newest entry is always the one on
            // the row nearest the finger — the list is reversed relative to the data.
            final int itemIndex = layout.itemIndexAt(i);
            if (itemIndex < 0) continue;
            canvas.drawText(mItems.get(itemIndex), textRight - mLabelWidths[itemIndex],
                    top + baselineOffset, mTextPaint);
        }
    }
}
