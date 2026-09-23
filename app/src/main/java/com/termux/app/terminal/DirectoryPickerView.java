package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
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
 * <p>Rows are painted straight onto the page rather than built as child views: the list lives for
 * one gesture (≤ ten rows) and a Canvas pass avoids the inflation/layout a {@code LinearLayout}
 * would cost on the swipe's critical path. Geometry comes verbatim from
 * {@link DirectoryPickerLayout.Result}, so drawing and hit-testing in
 * {@link DirectoryPickerController} cannot disagree about where a row is.
 *
 * <p>Rows are drawn at their canonical, fully-revealed geometry; how much of the list is on screen
 * is a horizontal translation of this view (see {@link #setRevealedWidth(float)}), so
 * {@code onDraw()} never depends on the reveal and the display list is recorded once per gesture.
 *
 * <p>Every history path shares the long prefix ({@code /data/data/com.termux/files/home/…}), so the
 * <em>tail</em> identifies the entry: labels are right-aligned and the head runs off-screen —
 * truncation by the page's clip, with no {@code ellipsize()}. No background is painted; only the
 * highlighted row gets a fill.
 */
public final class DirectoryPickerView extends View {

    private final TextPaint mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    /**
     * Separate paint at reduced alpha for rows the release fade takes away, so the row the fade
     * keeps is drawn pixel-identically to its pre-release picture.
     */
    private final TextPaint mFadeTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSeparatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /**
     * {@link #mSeparatorPaint} at reduced alpha: separators are only drawn for non-highlighted
     * rows, so during a fade every drawn separator belongs to a leaving row.
     */
    private final Paint mFadeSeparatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRowRect = new RectF();

    private final ArrayList<String> mItems = new ArrayList<>();
    /**
     * Cached label widths, px — measured once per item set (and on a text-size change) so the draw
     * pass never measures text. Indexed alongside {@link #mMeasuredLabels}.
     */
    private float[] mLabelWidths = new float[0];
    /**
     * The labels {@link #mLabelWidths} belongs to, so a new item set can be recognised as a prefix
     * of the measured one (see {@link #premeasure}).
     */
    private final ArrayList<String> mMeasuredLabels = new ArrayList<>();

    @Nullable
    private DirectoryPickerLayout.Result mLayout;
    private int mHighlight = -1;

    /**
     * Row the release fade leaves alone; {@code -1} = none was selected, so every row fades. Set on
     * finger lift so the choice stays legible while the unselected rows leave. See
     * {@code DirectoryPickerController#beginRowFadeOut}.
     */
    private int mFadeKeepRow = -1;
    /**
     * Opacity multiplier for every row but {@link #mFadeKeepRow}: 1 = no fade (this, not
     * {@code mFadeKeepRow == -1}, says whether a fade is running). A <em>multiplier</em>, not an
     * alpha: {@code Paint.setAlpha()} overwrites the alpha baked into the colour rather than
     * scaling it, and the scheme's colours are not all opaque. Re-derived at the top of every
     * draw pass, so a fade can never leave a stale alpha behind.
     */
    private float mRowFadeAlpha = 1f;
    /** Alpha baked into the text colour by {@link #setColors}; the fade scales it. */
    private int mTextBaseAlpha = 255;
    /** Alpha baked into the separator colour by {@link #setColors}; the fade scales it. */
    private int mSeparatorBaseAlpha = 255;

    private float mPaddingH;
    private float mCornerRadius;
    /**
     * Width of the revealed part of this page, px — applied as a translation of the whole view
     * (see {@link #applyRevealTranslation()}), not as a row length. {@link Float#MAX_VALUE} means
     * "not constrained" (fully settled).
     */
    private float mRevealedWidth = Float.MAX_VALUE;

    public DirectoryPickerView(Context context) {
        this(context, null);
    }

    public DirectoryPickerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mTextPaint.setStyle(Paint.Style.FILL);
        mFadeTextPaint.setStyle(Paint.Style.FILL);
        mFillPaint.setStyle(Paint.Style.FILL);
        mSeparatorPaint.setStyle(Paint.Style.STROKE);
        mSeparatorPaint.setStrokeWidth(1f);
        mFadeSeparatorPaint.setStyle(Paint.Style.STROKE);
        mFadeSeparatorPaint.setStrokeWidth(1f);
    }

    /**
     * Text size in px.
     *
     * <p>Identity early-out matters: the controller sets this on every bind, and the old body
     * re-measured the <em>previous</em> gesture's list — up to ten labels only to discard them and
     * drop the {@link #premeasure} width cache. A real change still clears
     * {@link #mMeasuredLabels}, which is what makes the next measure run instead of reuse.
     */
    public void setLabelTextSize(float px) {
        if (mTextPaint.getTextSize() == px && mFadeTextPaint.getTextSize() == px) return;
        mTextPaint.setTextSize(px);
        mFadeTextPaint.setTextSize(px);
        // The cached widths are in the old size, so they must not be reused.
        mMeasuredLabels.clear();
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
        mFadeTextPaint.setColor(textColor);
        mFillPaint.setColor(highlightFill);
        mSeparatorPaint.setColor(separatorColor);
        mFadeSeparatorPaint.setColor(separatorColor);
        mTextBaseAlpha = Color.alpha(textColor);
        mSeparatorBaseAlpha = Color.alpha(separatorColor);
        // Fade alphas are re-derived at the top of every draw pass, so a mid-fade restyle picks the
        // new base up on the next frame without depending on this method running at the right moment.
        invalidate();
    }

    /**
     * Install a new list + geometry. Pass an empty list (or a null layout) to clear.
     *
     * <p>Labels are measured here — or reused from {@link #premeasure} — so the per-frame draw pass
     * never touches the font engine.
     */
    public void setItems(@NonNull List<String> items, @Nullable DirectoryPickerLayout.Result layout) {
        mItems.clear();
        mItems.addAll(items);
        mLayout = layout;
        mHighlight = -1;
        // A new item set is a new gesture: the previous release fade is meaningless here, and a
        // recycled view may still carry it.
        mFadeKeepRow = -1;
        mRowFadeAlpha = 1f;
        // Reuse the widths when this list is a prefix of the measured one (the normal case: the
        // controller pre-measures the full history and this call installs its first `rows` entries);
        // only a history that changed in between pays for the shaping.
        if (!isMeasuredPrefix()) measureInto(mItems);
        invalidate();
    }

    /**
     * Measure the labels of {@code items} without installing them, so the widths are already known
     * when a gesture starts — {@link #setItems} runs on the reveal's critical frame, where up to
     * ten shaping calls must not land. Reuses the cache when {@code items} is a prefix of what it
     * covers (the view is recycled between gestures and history rarely changes).
     */
    public void premeasure(@NonNull List<String> items) {
        if (isMeasuredPrefixOf(items)) return;
        measureInto(items);
    }

    /** @return true if {@link #mLabelWidths} already covers {@code items} entry by entry. */
    private boolean isMeasuredPrefixOf(@NonNull List<String> items) {
        final int count = items.size();
        if (count > mMeasuredLabels.size()) return false;
        for (int i = 0; i < count; i++) {
            if (!java.util.Objects.equals(items.get(i), mMeasuredLabels.get(i))) return false;
        }
        return true;
    }

    /** @return true if {@link #mLabelWidths} already covers {@link #mItems} entry by entry. */
    private boolean isMeasuredPrefix() {
        return isMeasuredPrefixOf(mItems);
    }

    /** Highlight the row under the finger; {@code -1} clears it. */
    public void setHighlight(int index) {
        if (index == mHighlight) return;
        mHighlight = index;
        invalidate();
    }

    /**
     * Fade every row except {@code keepRow} to {@code alpha} (0 = gone, 1 = no fade).
     *
     * <p>Applied per row in {@link #onDraw} via the dedicated fade paints; the selected row is
     * never dimmed. The fade is 50 ms, once per gesture. Alphas are derived at the top of the next
     * draw pass, so an ended/cancelled fade cannot leave anything behind.
     *
     * @param keepRow row left untouched, or {@code -1} to fade them all.
     */
    public void setRowFade(int keepRow, float alpha) {
        final float clamped = Math.max(0f, Math.min(1f, alpha));
        if (keepRow == mFadeKeepRow && clamped == mRowFadeAlpha) return;
        mFadeKeepRow = keepRow;
        mRowFadeAlpha = clamped;
        invalidate();
    }

    /**
     * How much of this page is on screen, in px — the list is revealed together with the page.
     * Pass {@link Float#MAX_VALUE} (or anything past the view's width) for the fully revealed
     * picture.
     */
    public void setRevealedWidth(float px) {
        final float clamped = Math.max(0f, px);
        if (clamped == mRevealedWidth) return;
        mRevealedWidth = clamped;
        applyRevealTranslation();
    }

    /**
     * Express the reveal as a horizontal translation of the whole row block:
     * {@code translationX = revealed − width}, so exactly the right {@code revealed} pixels stay
     * inside the page and the page's bounds clip the rest — the same mechanism that truncates an
     * over-long path. A translation is a render-node property, so the reveal does not re-record
     * the display list; during a gesture the only invalidations left are {@link #setHighlight},
     * {@link #setItems}, and the 50 ms {@link #setRowFade}.
     */
    private void applyRevealTranslation() {
        final float width = getWidth();
        // Not laid out yet: bind() pushes a reveal while the page is still being measured; the
        // width is real by onSizeChanged, which re-applies it.
        if (width <= 0f) return;
        // Math.min keeps the "unconstrained" default (and any over-wide value) at translation 0.
        setTranslationX(Math.min(mRevealedWidth, width) - width);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyRevealTranslation();
    }

    /**
     * Measure {@code items} into the width cache, so the draw pass can right-align without touching
     * the font engine. Called on install, on a text-size change and by {@link #premeasure} — never
     * per frame.
     */
    private void measureInto(@NonNull List<String> items) {
        final int count = items.size();
        if (mLabelWidths.length < count) mLabelWidths = new float[count];
        for (int i = 0; i < count; i++) {
            mLabelWidths[i] = mTextPaint.measureText(items.get(i));
        }
        mMeasuredLabels.clear();
        mMeasuredLabels.addAll(items);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        final DirectoryPickerLayout.Result layout = mLayout;
        if (layout == null || !layout.hasList() || mItems.isEmpty()) return;

        // Canonical (fully revealed) geometry; the reveal is the view's own translation and the
        // page's bounds clip the rest, so nothing below depends on the reveal — which is what lets
        // the display list survive the whole gesture.
        final float rowWidth = getWidth();
        if (rowWidth <= 0f) return;

        final int rowCount = Math.min(mItems.size(), layout.rows);
        final float rowHeight = layout.rowHeight;
        final float textRight = rowWidth - mPaddingH;
        // Vertically centre the text: the baseline sits at half the row plus half the font's own
        // ascent/descent span, which keeps it optically centred for any typeface.
        final float baselineOffset = (rowHeight - (mTextPaint.descent() + mTextPaint.ascent())) / 2f;

        // Release fade: only the two dedicated fade paints are touched, re-derived from
        // mRowFadeAlpha on every pass; the ordinary paints keep setColors()'s values untouched.
        final boolean rowFading = mRowFadeAlpha < 1f;
        if (rowFading) {
            mFadeTextPaint.setAlpha(Math.round(mTextBaseAlpha * mRowFadeAlpha));
            mFadeSeparatorPaint.setAlpha(Math.round(mSeparatorBaseAlpha * mRowFadeAlpha));
        }

        for (int i = 0; i < rowCount; i++) {
            final float top = layout.listTop + i * rowHeight;
            final float bottom = top + rowHeight;
            if (bottom <= 0f || top >= getHeight()) continue; // clipped away entirely

            // The preserved row keeps the ordinary paints; everything else switches to the faded
            // ones for the duration of the fade.
            final boolean keep = !rowFading || i == mFadeKeepRow;
            final TextPaint textPaint = keep ? mTextPaint : mFadeTextPaint;
            final Paint separatorPaint = keep ? mSeparatorPaint : mFadeSeparatorPaint;

            if (i == mHighlight) {
                mRowRect.set(0f, top, rowWidth, bottom);
                canvas.drawRoundRect(mRowRect, mCornerRadius, mCornerRadius, mFillPaint);
            } else if (i > 0 && textRight > mPaddingH) {
                // Hairline between rows so the list stays readable over arbitrary terminal content.
                canvas.drawLine(mPaddingH, top, textRight, top, separatorPaint);
            }

            // Right-aligned to the canonical row end and clipped by the page's bounds: the path's
            // tail (what identifies the directory) is on screen, the head is cut by the clip.
            // The row→entry mapping comes from the layout, so the newest entry is always on the row
            // nearest the finger (the list is reversed relative to the data).
            final int itemIndex = layout.itemIndexAt(i);
            if (itemIndex < 0) continue;
            canvas.drawText(mItems.get(itemIndex), textRight - mLabelWidths[itemIndex],
                    top + baselineOffset, textPaint);
        }
    }
}
