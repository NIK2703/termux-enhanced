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
 * <p>The rows are painted straight onto the page rather than built as child views: the whole list
 * lives for the duration of one gesture, is at most ten rows, and repositions only when the finger
 * moves — a Canvas pass avoids both the view inflation and the layout pass that a
 * {@code LinearLayout} of {@code TextView}s would cost on the swipe's critical path.
 *
 * <p>Coordinates are page-local (the view is {@code match_parent} inside the page), and the geometry
 * comes verbatim from {@link DirectoryPickerLayout.Result}, so the drawing and the hit-testing in
 * {@link DirectoryPickerController} can never disagree about where a row is.
 *
 * <p>Rows are drawn at their canonical, fully-revealed geometry; how much of the list is on screen is
 * expressed as a horizontal translation of this view (see {@link #setRevealedWidth(float)}) rather
 * than as a length, and the page's own bounds do the clipping. The picture is identical to drawing
 * shorter rows right-aligned to the revealed edge, but nothing in {@code onDraw()} depends on the
 * reveal any more — so the display list is recorded once per gesture instead of once per frame.
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
    /**
     * The same text paint at a reduced alpha, used for the rows the release fade is taking away.
     *
     * <p>A <em>separate</em> paint rather than {@link #mTextPaint} with its alpha modulated: the
     * row the fade keeps has to be drawn pixel-identically to how it was drawn before the finger
     * lifted, and that is only provable if nothing ever writes to the paint it uses. Every write
     * the fade needs lands here instead, and this paint is never used outside a fade — so a bug in
     * the fade can dim the rows that are leaving, but can never touch the one that stays.
     */
    private final TextPaint mFadeTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSeparatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /**
     * {@link #mSeparatorPaint} at a reduced alpha, for the same reason as
     * {@link #mFadeTextPaint}. A separator is only ever drawn for a row that is <em>not</em>
     * highlighted, so during the fade every drawn separator belongs to a leaving row.
     */
    private final Paint mFadeSeparatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRowRect = new RectF();

    private final ArrayList<String> mItems = new ArrayList<>();
    /**
     * Cached label widths, px — measured once per item set (and on a text-size change) so the draw
     * pass never measures text. Indexed alongside {@link #mMeasuredLabels}; entries past the current
     * size are simply not read.
     */
    private float[] mLabelWidths = new float[0];
    /**
     * The labels {@link #mLabelWidths} belongs to.
     *
     * <p>Kept so a new item set can be recognised as a prefix of the one already measured, which is
     * the normal case: the controller pre-measures the full history at bind time (see
     * {@link #premeasure}) and {@code show()} then installs its first {@code rows} entries. That is
     * what keeps text shaping off the gesture's critical frame.
     */
    private final ArrayList<String> mMeasuredLabels = new ArrayList<>();

    @Nullable
    private DirectoryPickerLayout.Result mLayout;
    private int mHighlight = -1;

    /**
     * Row the release fade leaves alone; {@code -1} = none was selected, so every row fades.
     *
     * <p>Set on the finger lift, once the choice is made: the selected row stays legible while the
     * unselected ones leave, so the choice reads immediately instead of the whole list lingering
     * until the commit's own fade-out reaches it. See
     * {@code DirectoryPickerController#beginRowFadeOut}.
     */
    private int mFadeKeepRow = -1;
    /**
     * Multiplier applied to the opacity of every row but {@link #mFadeKeepRow}.
     *
     * <p>1 = the fade is not running and every row is painted as usual; this — not
     * {@link #mFadeKeepRow} — is what says whether there is a fade at all, because
     * {@code mFadeKeepRow == -1} is a meaningful state ("fade them all").
     *
     * <p>A <em>multiplier</em>, not an alpha: {@code Paint.setAlpha()} <em>overwrites</em> the alpha
     * baked into the colour rather than scaling it, and the scheme's colours are not all opaque —
     * the highlight fill is {@code withAlpha(textColor, 0x26)} and the separator is
     * {@code withAlpha(textColor, 0x3C)}. Writing a plain 255 into a paint therefore replaces those
     * with full opacity. The base alphas below are what keeps the fade a scaling of the configured
     * colours, and they are only ever written to {@link #mFadeTextPaint} and
     * {@link #mFadeSeparatorPaint}.
     *
     * <p>Derived afresh from this field at the top of every draw pass, so the fade can never leave
     * a stale alpha behind: whatever the paint held last frame is overwritten before it is used.
     */
    private float mRowFadeAlpha = 1f;
    /** Alpha baked into the text colour by {@link #setColors}; the fade scales it. */
    private int mTextBaseAlpha = 255;
    /** Alpha baked into the separator colour by {@link #setColors}; the fade scales it. */
    private int mSeparatorBaseAlpha = 255;

    private float mPaddingH;
    private float mCornerRadius;
    /**
     * Width of the part of this page that is currently revealed, px — the page-local
     * {@code [0, revealed]} slice. It is applied as a translation of the whole view (see
     * {@link #applyRevealTranslation()}), not as a row length.
     * {@link Float#MAX_VALUE} means "not constrained" (fully settled).
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

    /** Text size in px. */
    public void setLabelTextSize(float px) {
        mTextPaint.setTextSize(px);
        mFadeTextPaint.setTextSize(px);
        // The cached widths are in the old size, so they are re-measured rather than reused.
        measureInto(mItems);
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
        // No need to re-apply the fade's alphas here: they are written to the two fade paints at the
        // top of every draw pass (see onDraw), so a restyle mid-fade picks the new base up on the
        // very next frame instead of depending on this method running at the right moment.
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
        // A new item set is a new gesture: the release fade of the previous one is meaningless here,
        // and this view may be a recycled one that still carries the previous gesture's final value.
        mFadeKeepRow = -1;
        mRowFadeAlpha = 1f;
        // Reuse the widths when this list is a prefix of the one already measured, which is the
        // normal case: the controller pre-measures the full history at bind time and this call then
        // installs its first `rows` entries. Only a history that changed in between pays for the
        // shaping, and then it is exactly what this method used to do unconditionally.
        if (!isMeasuredPrefix()) measureInto(mItems);
        invalidate();
    }

    /**
     * Measure the labels of {@code items} without installing them, so the widths are already known
     * when a gesture starts.
     *
     * <p>{@link #setItems} runs on the first frame that reveals the placeholder — the gesture's
     * critical frame, where this view also goes from {@code GONE} to {@code VISIBLE} and has its
     * display list recorded from scratch. Measuring up to ten paths there means up to ten text
     * shaping calls on that one frame. The controller calls this while the page is at rest instead.
     */
    public void premeasure(@NonNull List<String> items) {
        measureInto(items);
    }

    /** @return true if {@link #mLabelWidths} already covers {@link #mItems} entry by entry. */
    private boolean isMeasuredPrefix() {
        final int count = mItems.size();
        if (count > mMeasuredLabels.size()) return false;
        for (int i = 0; i < count; i++) {
            if (!java.util.Objects.equals(mItems.get(i), mMeasuredLabels.get(i))) return false;
        }
        return true;
    }

    /** Highlight the row under the finger; {@code -1} clears it. */
    public void setHighlight(int index) {
        if (index == mHighlight) return;
        mHighlight = index;
        invalidate();
    }

    /**
     * Fade every row except {@code keepRow} to {@code alpha}.
     *
     * <p>Applied by choosing a paint per row in {@link #onDraw} rather than to the view, so the
     * selected row is not dimmed along with the rest and no second compositing layer is needed —
     * the rows are already drawn one by one. The price is that the display list is re-recorded on
     * each frame of the fade; the fade lasts 50 ms, i.e. a handful of frames once per gesture, and
     * only on the release (see {@code DirectoryPickerController#ROW_FADE_OUT_MS}).
     *
     * <p>The selected row is left <em>pixel-identical</em> to its pre-release picture: it keeps
     * drawing with {@link #mTextPaint} and {@link #mFillPaint}, which this method never writes to.
     *
     * <p>This method only stores the two values — the actual alphas are derived from them at the
     * top of the next draw pass, so a fade that ends, is cancelled, or is interrupted mid-frame
     * cannot leave anything behind.
     *
     * @param keepRow row left untouched, or {@code -1} to fade them all.
     * @param alpha   0 = gone, 1 = no fade at all.
     */
    public void setRowFade(int keepRow, float alpha) {
        final float clamped = Math.max(0f, Math.min(1f, alpha));
        if (keepRow == mFadeKeepRow && clamped == mRowFadeAlpha) return;
        mFadeKeepRow = keepRow;
        mRowFadeAlpha = clamped;
        invalidate();
    }

    /**
     * How much of this page is currently on screen, in px — the list is revealed together with the
     * page instead of appearing at full length.
     *
     * <p>Pass {@link Float#MAX_VALUE} (or anything past the view's width) for the fully revealed
     * picture.
     */
    public void setRevealedWidth(float px) {
        final float clamped = Math.max(0f, px);
        if (clamped == mRevealedWidth) return;
        mRevealedWidth = clamped;
        applyRevealTranslation();
    }

    /**
     * Express the reveal as a horizontal translation of the whole row block.
     *
     * <p>{@code onDraw()} paints the canonical, fully-revealed geometry; the reveal slides that block
     * left so exactly its right {@code revealed} pixels stay inside the page:
     * {@code translationX = revealed − width}. The page's own bounds clip the rest — the same
     * mechanism that already truncates a too-long path (see the class comment), so the row's head
     * simply continues under the neighbouring terminal.
     *
     * <p>This is the whole point of the arrangement: the picture is identical to drawing rows of
     * length {@code revealed} right-aligned to the revealed edge, but a translation is a render-node
     * property, so the reveal no longer re-records the display list. During a gesture the only
     * invalidations left are {@link #setHighlight} (once per 48 dp of finger travel),
     * {@link #setItems}, and the release fade — {@link #setRowFade}, which is bounded to the 50 ms
     * that follow the finger lift.
     */
    private void applyRevealTranslation() {
        final float width = getWidth();
        // Not laid out yet: bind() pushes a reveal while the page is still being measured. The
        // width is real by onSizeChanged, which re-applies it.
        if (width <= 0f) return;
        // Math.min keeps the "unconstrained" default — and any over-wide value — at translation 0,
        // i.e. the fully revealed picture. Same clamp the draw pass used to apply to the row width.
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

        // Canonical geometry: the fully revealed row. How much of it is on screen is the view's own
        // translation (see applyRevealTranslation) and the page's bounds clip the rest, so nothing
        // below depends on the reveal — which is what lets the display list survive the whole
        // gesture instead of being re-recorded on every frame.
        final float rowWidth = getWidth();
        if (rowWidth <= 0f) return;

        final int rowCount = Math.min(mItems.size(), layout.rows);
        final float rowHeight = layout.rowHeight;
        final float textRight = rowWidth - mPaddingH;
        // Vertically centre the text inside the row: the baseline sits at half the row plus half the
        // font's own ascent/descent span, which keeps it optically centred for any typeface.
        final float baselineOffset = (rowHeight - (mTextPaint.descent() + mTextPaint.ascent())) / 2f;

        // Release fade. Only the two dedicated fade paints are touched, and they are re-derived
        // from mRowFadeAlpha on every pass, so they can never carry a stale value in or out of a
        // frame. mTextPaint / mSeparatorPaint / mFillPaint are left exactly as setColors() put
        // them — which is what keeps the row the fade preserves identical to its pre-release
        // picture, and the whole list identical when no fade is running.
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
            // ones for the duration of the fade. With no fade running both branches hand back the
            // configured paints, so the drawn picture is the one setColors() describes.
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

            // Right-aligned to the canonical row end, clipped by the page's own bounds. The tail of
            // the path — the part that identifies the directory — is therefore on screen from the
            // first pixel of the reveal, and the head is simply cut where the neighbouring terminal
            // begins. A label wider than the row needs no special case: the clip is the truncation.
            //
            // The row→entry mapping comes from the layout, so the newest entry is always the one on
            // the row nearest the finger — the list is reversed relative to the data.
            final int itemIndex = layout.itemIndexAt(i);
            if (itemIndex < 0) continue;
            canvas.drawText(mItems.get(itemIndex), textRight - mLabelWidths[itemIndex],
                    top + baselineOffset, textPaint);
        }
    }
}
