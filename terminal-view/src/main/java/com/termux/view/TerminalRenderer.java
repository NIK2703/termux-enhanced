package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.SparseArray;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    /**
     * Cache of {@link Paint#measureText} results per code point. {@code bmpMeasures} covers the
     * BMP (0..0xFFFF) and is lazily filled; {@code supplementaryMeasures} covers the supplementary
     * planes (rare). This avoids calling the native Skia/FreeType measureText on every non-ASCII
     * character on every frame — previously the single hottest call in the renderer.
     * A value of 0.0f in {@code bmpMeasures} means "not yet measured" except for code point 0.
     *
     * B3: {@code bmpMeasures} is shared across {@link TerminalRenderer} instances via the static
     * {@link #sBmpMeasuresCache}, keyed by (typeface, textSize). A new renderer is created on
     * every pinch-zoom step and per ViewPager page, so without sharing each one would allocate a
     * fresh 256 KB array and re-run 128 native {@code measureText} calls — a guaranteed GC on
     * every zoom step. The metrics depend only on the typeface and text size (anti-alias is always
     * on), so the array is safe to reuse across instances.
     */
    private float[] bmpMeasures;
    private final SparseArray<Float> supplementaryMeasures = new SparseArray<>();

    /**
     * G1: flat per-ASCII tables derived once from {@link #bmpMeasures} and {@link WcWidth#width(int)}.
     *
     * <p>The hot column loop used to resolve every cell with a {@code WcWidth.width()} call — a read
     * from a static 64 KB {@code byte[]}, i.e. a near-guaranteed L1 miss — plus a
     * {@link #measureCodePoint} call (bounds check, branch, possible lazy fill). ASCII is the
     * overwhelming majority of the cells of a real frame, and for those the answer is a constant
     * per code point, so the loop now does three reads from 128-entry arrays instead. Measured on
     * the render scan stand (48&times;80, 55 % fill): 16.6 &rarr; 9.8 µs/frame for the scan loop.</p>
     *
     * <p>No per-row state and no new invariant: the tables are a pure function of
     * (typeface, text size), both fixed for the lifetime of the renderer.</p>
     */
    private final float[] asciiMeasure = new float[0x80];
    private final byte[] asciiWc = new byte[0x80];
    private final boolean[] asciiMismatch = new boolean[0x80];

    /**
     * G5: the font-width mismatch tolerance ({@code 0.01 * mFontWidth}) and the expected width of a
     * double-width cell, hoisted out of the per-cell loop. Two multiplies per cell removed from the
     * hottest loop in the renderer for free.
     */
    private final float mFontWidthTolerance;
    private final float mFontWidth2;

    /** B3: per-(typeface,textSize) cache of the BMP measure table, bounded via LRU eviction. */
    private static final java.util.LinkedHashMap<RenderKey, float[]> sBmpMeasuresCache =
        new java.util.LinkedHashMap<RenderKey, float[]>(4, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<RenderKey, float[]> eldest) {
                return size() > 4;
            }
        };

    /** B3 cache key — identity of the typeface plus the font size (both determine glyph metrics). */
    private static final class RenderKey {
        final Typeface typeface;
        final int textSize;
        RenderKey(Typeface typeface, int textSize) {
            this.typeface = typeface;
            this.textSize = textSize;
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RenderKey)) return false;
            RenderKey r = (RenderKey) o;
            return textSize == r.textSize && typeface == r.typeface;
        }
        @Override
        public int hashCode() {
            return 31 * textSize + (typeface == null ? 0 : System.identityHashCode(typeface));
        }
    }

    /**
     * Reusable per-frame run list. The renderer splits each row into "runs" of equal style; instead
     * of drawing every run immediately (which issues a separate background {@link Paint#drawRect}
     * per run), runs are collected into these arrays once per row and drawn in two passes: all
     * background rectangles, then all text. This avoids per-run allocation and collapses adjacent
     * same-color backgrounds into a single {@link Paint#drawRect} call.
     */
    private int[] mRunStartColumn;
    private int[] mRunWidthColumns;
    private int[] mRunStartChar;
    private int[] mRunCharCount;
    private float[] mRunMeasuredWidth;
    private long[] mRunStyle;
    private int[] mRunCursorColor;
    private int[] mRunCursorStyle;
    private boolean[] mRunReverseVideo;
    private boolean[] mRunFontWidthMismatch;
    /**
     * H1: true when every code point of the run is a block-element "area fill" (U+2580..U+259F,
     * i.e. {@code ▀▄█▌▐░▒▓} and the quadrant / eighth variants). Such a glyph does not read as
     * text — it paints a solid (or dithered) area and is visually a background — so it must
     * honour the configured background transparency exactly like pass A's rectangles do.
     * See {@link #drawRunText} for how that is done without an offscreen layer.
     */
    private boolean[] mRunBlockFill;
    /** Resolved ARGB foreground/background color of each run, computed once per row (see render). */
    private int[] mRunForeColor;
    private int[] mRunBackColor;
    private int mRunCount;
    /** Paint for the background rectangles - never carries text attributes. */
    private final Paint mBgPaint = new Paint();
    /**
     * C2: identical to {@link #mBgPaint} but with {@link PorterDuff.Mode#SRC} permanently attached.
     * Every translucent background fill (base fill, pass A, mismatch runs, cursor) needs SRC, and
     * every opaque one needs the default SRC_OVER, so the renderer used to call
     * {@code setXfermode(SRC)} / {@code setXfermode(null)} twice per rectangle — two native paint
     * mutations for every run on every frame. Keeping two paints with a fixed mode makes the
     * choice a field selection, and neither paint's mode is ever mutated at draw time.
     */
    private final Paint mBgSrcPaint = new Paint();
    /**
     * Dedicated base-fill paints, one per xfermode. The base fill used to share
     * {@link #mBgPaint}/{@link #mBgSrcPaint} with pass A, the cursor fill, the mismatch-run
     * fill and the A2 fast path, all of which mutate the paint's colour on every run. With the
     * E2 hoist of the per-row base fill, that meant the colour set in pass A (or by the cursor
     * rect) leaked into the base fill of the *next* row — a coloured block visually continued
     * downward through the following rows' backgrounds until the next block rebased the colour.
     * Splitting the base fill into its own paints, which no other code touches, is the only way
     * to make the colour set here (once before the loop) hold for every row.
     */
    private final Paint mBaseFillPaint = new Paint();
    private final Paint mBaseFillSrcPaint = new Paint();
    private final int[] mColorOut = new int[2];

    /**
     * Alpha used for the *default* background fill: 255 = fully opaque, which is also the
     * "wallpaper behind the terminal" feature being off. Anything below 255 lets the real
     * device wallpaper (drawn by SurfaceFlinger below our translucent window) show through.
     */
    private int mBackgroundAlpha = 255;

    /**
     * Reusable {@link PorterDuff.Mode#SRC} xfermode for the base background fill. The canvas
     * retains the previous frame's pixels (both with Surface.lockCanvas() and with HWUI), so a
     * partially repainted region filled with a translucent colour in SRC_OVER would accumulate
     * alpha frame after frame and creep towards opaque. SRC ignores the destination and resets
     * the region to exactly (colour, alpha) instead.
     */
    private static final PorterDuffXfermode SRC_XFERMODE = new PorterDuffXfermode(PorterDuff.Mode.SRC);
    /**
     * H1: {@link PorterDuff.Mode#SRC_ATOP}, the single mode that makes a block-element glyph
     * honour the background transparency without touching its geometry.
     *
     * <p>A block element ({@code ▀▄█▌▐░▒▓} and friends) paints an <em>area</em>, so semantically it
     * is the cell's background, not its text: where the glyph covers a fraction {@code m} of a
     * pixel, the cell's background colour {@code A} must be replaced by {@code m·C + (1-m)·A}, and
     * the whole cell must then be handed to the compositor at the background alpha {@code a}. The
     * renderer has already painted the cell as {@code (a·A, a)} (premultiplied), so the target is
     * {@code (a·(m·C + (1-m)·A), a)} — the alpha has to stay put while the colour changes.</p>
     *
     * <p>SRC_ATOP is exactly "replace the colour, keep the destination alpha": with source
     * {@code (m·C, m)} — an <em>opaque</em> paint, the coverage is the only alpha — and destination
     * {@code (a·A, a)} it yields {@code (m·C·a + (1-m)·a·A, a)}. Correct for every {@code m},
     * including the anti-aliased rim. Alternatives all lose:</p>
     * <ul>
     *   <li>{@code SRC_OVER} with {@code (C, a)}: composes to {@code 2a-a²} — 0.96 at the 20 %
     *       setting, i.e. still nearly opaque. That was the original defect.</li>
     *   <li>{@code SRC}: erases the rest of the cell, because a text paint's coverage is 0 there.</li>
     *   <li>{@code DST_OUT} + {@code SRC_OVER(C, a)} (the first attempt): correct at {@code m=1},
     *       but it cuts the base with the glyph's own coverage <em>and</em> then re-stamps with the
     *       glyph's alpha, so the two partial-coverage factors multiply. At the rim the cell ends up
     *       at {@code a·m + a·(1-m)·(1-a·m) < a} — too transparent — and where two neighbouring
     *       block glyphs overlap by a pixel (a fallback font's 18 px advance inside a 16 px cell)
     *       the pair is applied twice, so every cell boundary of a block-art run got a visible
     *       light/dark seam. Measured ripple: std 0.00 before, 6.45 after.</li>
     * </ul>
     *
     * <p>SRC_ATOP is additionally <em>idempotent</em>: drawing the same full-coverage glyph twice
     * yields {@code (C·a, a)} both times, so overlapping neighbours cannot produce a seam — which
     * is precisely what the double-draw could not guarantee.</p>
     */
    private static final PorterDuffXfermode SRC_ATOP_XFERMODE = new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP);

    /**
     * B4: last {@link Paint} text-style state applied, so {@link #drawRunText} only touches the
     * native paint setters when something actually changed. With colored output (ls --color,
     * htop, syntax highlighting) a frame has hundreds-to-thousands of runs; most adjacent runs
     * share the same color/bold/underline/italic/strike state, so the setter churn is almost
     * entirely redundant. Reset to "unknown" at the start of each {@link #render}.
     */
    private int mLastPaintForeColor = -1;
    private boolean mLastPaintBold = false;
    private boolean mLastPaintUnderline = false;
    private boolean mLastPaintItalic = false;
    private boolean mLastPaintStrike = false;

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        mBgPaint.setStyle(Paint.Style.FILL);
        // C2: the SRC twin is set up once here and never touched again.
        mBgSrcPaint.setStyle(Paint.Style.FILL);
        mBgSrcPaint.setXfermode(SRC_XFERMODE);
        // Base-fill paints: never mutated anywhere except in render() (setColor only), so the colour
        // they hold when the row loop starts is exactly the colour every base fill uses.
        mBaseFillPaint.setStyle(Paint.Style.FILL);
        mBaseFillSrcPaint.setStyle(Paint.Style.FILL);
        mBaseFillSrcPaint.setXfermode(SRC_XFERMODE);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");
        // G5: derived constants used by the per-cell mismatch test.
        mFontWidthTolerance = 0.01f * mFontWidth;
        mFontWidth2 = 2.f * mFontWidth;

        // B3: reuse a cached BMP measure table for this (typeface, textSize) if one exists,
        // otherwise create and pre-measure it, then store it for later renderers to share.
        final RenderKey key = new RenderKey(typeface, textSize);
        float[] shared;
        synchronized (sBmpMeasuresCache) {
            shared = sBmpMeasuresCache.get(key);
        }
        if (shared == null) {
            shared = new float[0x10000];
            // Pre-measure ASCII so the first paint does not pay for it; the rest is filled lazily.
            StringBuilder sb = new StringBuilder(" ");
            for (int i = 0; i < 0x80; i++) {
                sb.setCharAt(0, (char) i);
                shared[i] = mTextPaint.measureText(sb, 0, 1);
            }
            synchronized (sBmpMeasuresCache) {
                sBmpMeasuresCache.put(key, shared);
            }
        }
        bmpMeasures = shared;

        // G1: fill the ASCII tables. shared[] is pre-measured for 0..0x7F both when it is created
        // above and when it comes from the cache, so these entries are already the values
        // measureCodePoint() would return.
        for (int i = 0; i < 0x80; i++) {
            final float measured = shared[i];
            final int wc = WcWidth.width(i);
            asciiMeasure[i] = measured;
            asciiWc[i] = (byte) wc;
            asciiMismatch[i] = Math.abs(measured - wc * mFontWidth) > mFontWidthTolerance;
        }
    }

    /**
     * Set how transparent the terminal background is, so the real device wallpaper shows
     * through it.
     *
     * @param percent 0 = opaque (feature disabled, the default and the historical behaviour),
     *                50 = maximum transparency (background alpha 128).
     */
    public void setBackgroundTransparencyPercent(int percent) {
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
        mBackgroundAlpha = Math.round(255f * (100 - percent) / 100f);
    }

    /**
     * Measure text with the shared Paint forced into a style-neutral state.
     *
     * <p>The per-code-point advance cache is keyed by (typeface, text size) and is shared by every
     * renderer of that font, so a value stored in it must not depend on the style the Paint happens
     * to carry at the moment of the first measurement. That style is <em>not</em> neutral: a row's
     * runs are built after the previous row has already been drawn, and {@code drawRunText()} leaves
     * the style of the last run it drew on the shared Paint. For the box-drawing and block glyphs
     * that terminal art is made of, {@code setTextSkewX()} (italic) changes the measured advance by
     * a whole pixel at the font sizes in use — 18.0 becomes 19.0 at size 26. A single italic run
     * drawn above the art therefore caches the wrong advance for the whole process, and because
     * {@code drawRunText()} derives its mismatch scale as
     * {@code runWidthColumns * mFontWidth / measuredWidth}, every glyph of the affected run is then
     * drawn 18 * 16/19 ≈ 15.2 px wide inside a 16 px cell: a ~0.8 px seam at every cell boundary,
     * i.e. visibly "dancing" block art. Measuring here with the style stripped makes the cache
     * deterministic and independent of what was drawn before.</p>
     */
    private float measureNeutral(char[] line, int index, int count) {
        final boolean bold = mTextPaint.isFakeBoldText();
        final float skew = mTextPaint.getTextSkewX();
        final boolean underline = mTextPaint.isUnderlineText();
        final boolean strike = mTextPaint.isStrikeThruText();
        mTextPaint.setFakeBoldText(false);
        mTextPaint.setTextSkewX(0.f);
        mTextPaint.setUnderlineText(false);
        mTextPaint.setStrikeThruText(false);
        final float measured = mTextPaint.measureText(line, index, count);
        // Restore the exact previous state: drawRunText() tracks it in mLastPaint* and would skip a
        // setter it believes is already applied, leaking the wrong style into the next run.
        mTextPaint.setFakeBoldText(bold);
        mTextPaint.setTextSkewX(skew);
        mTextPaint.setUnderlineText(underline);
        mTextPaint.setStrikeThruText(strike);
        return measured;
    }

    /** Measure the on-screen width of a code point, using the per-code-point cache. */
    private float measureCodePoint(int codePoint, char[] line, int index, int count) {
        if (codePoint < 0x10000) {
            float cached = bmpMeasures[codePoint];
            if (cached == 0f && codePoint != 0) {
                cached = measureNeutral(line, index, count);
                bmpMeasures[codePoint] = cached;
            }
            return cached;
        }
        Float cached = supplementaryMeasures.get(codePoint);
        if (cached == null) {
            cached = measureNeutral(line, index, count);
            supplementaryMeasures.put(codePoint, cached);
        }
        return cached;
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection.
     *
     * @param xOffset horizontal pixel offset applied to the glyph grid (centering the leftover space).
     * @param yOffset vertical pixel offset applied to the glyph grid (centering the leftover space).
     */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2,
                             float xOffset, float yOffset) {
        render(mEmulator, canvas, topRow, selectionY1, selectionY2, selectionX1, selectionX2,
            xOffset, yOffset, null);
    }

    /**
     * Render the terminal to a canvas.
     *
     * @param dirtyRect when non-null, only rows intersecting this (view-coordinate) rectangle
     *                  are re-rendered and only that region is cleared. When null, the whole
     *                  canvas is cleared and all rows are drawn (full repaint — required after
     *                  a color-scheme/theme change, a scroll, a resize or a buffer switch).
     */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2,
                             float xOffset, float yOffset, Rect dirtyRect) {
        render(mEmulator, canvas, topRow, selectionY1, selectionY2, selectionX1, selectionX2,
            xOffset, yOffset, dirtyRect, false);
    }

    /**
     * Render the terminal to a canvas, with an explicit "pixels valid" flag.
     *
     * <p>{@code pixelsValid} tells the renderer whether the surface region outside {@code dirtyRect}
     * already holds the correct terminal content. The E2 optimisation skips non-dirty rows on a
     * partial repaint because the canvas supposedly still shows their previous-frame pixels; that
     * invariant is broken whenever the canvas may have lost pixels (first frame after a surface
     * recreate, a view attach/resize, a transparent scheme change, the first frame on a newly
     * paged-in pager page, etc.). In that case the view passes {@code false} so the renderer draws
     * every visible row instead of trusting stale or absent pixels.</p>
     */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2,
                             float xOffset, float yOffset, Rect dirtyRect, boolean pixelsValid) {
        // B4: bring the Paint back to the baseline style *and* record that in the cache. The Paint is
        // a field shared by every frame, so a frame whose last drawn run was italic/bold/underline/
        // struck-through leaves the Paint in that state. Only resetting the cache to "clean" here
        // (without touching the Paint) would make drawRunText() believe the style is already applied
        // and skip the setter, leaking that style into every run of every following frame — visible
        // as a whole screen of italic text once the styled row scrolls out of view. The color is
        // tracked separately below because drawRunText() also sets it for cursor/background fills.
        mTextPaint.setFakeBoldText(false);
        mTextPaint.setUnderlineText(false);
        mTextPaint.setTextSkewX(0.f);
        mTextPaint.setStrikeThruText(false);
        mLastPaintBold = false;
        mLastPaintUnderline = false;
        mLastPaintItalic = false;
        mLastPaintStrike = false;
        mLastPaintForeColor = -1;

        final boolean reverseVideo = mEmulator.isReverseVideo();
        // C1: the view's scroll position can transiently point deeper than the buffer's real
        // history: the emulator switches to/from the alternate screen or clears the transcript
        // during input processing, and a draw can run before the next onScreenUpdated() clamp
        // catches up. getLineOrBlank() throws for external rows < -activeTranscriptRows (crash:
        // "extRow=-243, mScreenRows=48, mActiveTranscriptRows=0"), so clamp the requested scroll
        // offset to the live buffer before deriving the row window from it.
        final int minTopRow = -mEmulator.getScreen().getActiveTranscriptRows();
        if (topRow < minTopRow) topRow = minTopRow;
        final int endRow = topRow + mEmulator.mRows;
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        // Rows to actually render this frame. For a full repaint it is the whole visible range;
        // for a partial repaint only the rows whose pixel band intersects dirtyRect.
        int renderStartRow = topRow;
        int renderEndRow = endRow; // exclusive
        if (dirtyRect != null) {
            final float ls = mFontLineSpacing;
            // View-y of the top edge of row `topRow` (matches TerminalView.rowToPixelTop).
            final float base = yOffset + mFontLineSpacingAndAscent;
            int r0 = topRow + (int) Math.floor((dirtyRect.top - base) / ls);
            int r1 = topRow + (int) Math.floor((dirtyRect.bottom - 1 - base) / ls);
            renderStartRow = Math.max(r0, topRow);
            renderEndRow = Math.min(r1 + 1, endRow);
        }

        // Background. A full repaint clears the entire canvas — this is what keeps theme /
        // OSC color-scheme swaps correct (see the original comment). A partial repaint clears
        // only the dirty region; the framework has already clipped the canvas to it, and we
        // bound the fill explicitly so clean rows are never erased.
        final int rawBgColor = reverseVideo
            ? palette[TextStyle.COLOR_INDEX_FOREGROUND]
            : palette[TextStyle.COLOR_INDEX_BACKGROUND];
        // Only the *default* background is made translucent. Cells that explicitly set a
        // background colour (ls --color, htop, syntax highlighting) keep their own colour —
        // it is part of the program's layout and must stay readable — but it is drawn with
        // the same alpha as the default fill so that every kind of background honours the
        // configured transparency uniformly.
        final int bgColor = (mBackgroundAlpha >= 255)
            ? rawBgColor
            : ((rawBgColor & 0x00FFFFFF) | (mBackgroundAlpha << 24));
        // E2: on a partial repaint the base fill moves into the row loop, so that only the rows
        // whose content actually changed are cleared and redrawn. Filling the whole band here would
        // erase the untouched rows in it — the canvas keeps the previous frame's pixels (the same
        // invariant the translucent SRC fill relies on), so leaving them alone is both correct and
        // cheaper. dirtyRect.left/right are in view coordinates; the row loop draws after the
        // canvas.translate(xOffset, yOffset) below, so they are shifted back there.
        if (dirtyRect == null) {
            canvas.drawColor(bgColor, PorterDuff.Mode.SRC);
        }

        // Translate the whole grid so the leftover space (from glyphs that do not fit the
        // view) is split symmetrically around it. The drawTextRun() scale compensation block
        // (canvas.scale + left *= mes/runWidthColumns) composes with this translate, so
        // width-mismatched glyph runs keep landing exactly on their shifted cells.
        canvas.save();
        canvas.translate(xOffset, yOffset);

        ensureRunCapacity(columns);

        // Right edge of the glyph grid in post-translate coordinates. F4: the per-row base fill
        // always spans the whole grid (0 .. gridRight) instead of the horizontal extent of
        // dirtyRect — see the fill itself below.
        final float gridRight = columns * mFontWidth;
        // Base-fill paints are dedicated: no other code path (pass A, cursor, mismatch, A2 fast
        // path) ever sets a colour on them, so the colour we set here is the colour every row
        // draws with.
        final Paint baseFillPaint;
        if (mBackgroundAlpha >= 255) {
            // Fast path: identical to the pre-transparency behaviour, no xfermode churn.
            baseFillPaint = mBaseFillPaint;
            baseFillPaint.setColor(bgColor);
        } else {
            // mBaseFillSrcPaint already carries SRC.
            baseFillPaint = mBaseFillSrcPaint;
            baseFillPaint.setColor(bgColor);
        }
        // E3: a partial repaint in which *no* row of the clip is dirty cannot be a content change
        // — it is the scrollbar thumb having moved (or the framework clipping a full invalidate
        // below the view bounds). Skipping every row would leave whatever was painted on top of
        // them last frame (the old thumb) on the canvas, and covering the band with the background
        // colour instead would erase the glyphs that live under the thumb — the grid spans the
        // full view width. The only correct answer is to re-render the rows, so detect the case
        // up-front and draw them.
        boolean forceDraw = false;
        if (dirtyRect != null && pixelsValid) {
            boolean anyDirty = false;
            for (int row = renderStartRow; row < renderEndRow; row++) {
                if (screen.isRowDirty(row)) { anyDirty = true; break; }
            }
            forceDraw = !anyDirty;
        }

        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;
            // Partial repaint: skip rows that do not intersect the dirty region. heightOffset is
            // advanced above for every row, so the y-coordinate stays correct for rendered rows.
            if (row < renderStartRow || row >= renderEndRow) continue;

            // E2: skip rows whose content did not change. The pixels they show are still the ones
            // from the previous frame, so neither the base fill nor any glyph is needed. This is
            // what turns "the program touched 2 rows out of 48" into 2 rows of work instead of 48
            // (and, together with the view's content anchor, into no work at all while the user is
            // scrolled into history). The cursor is not part of the row's content, so the view
            // marks the old and new cursor rows dirty itself when the cursor moves.
            //
            // Safety: the "previous frame still shows this row" invariant only holds once the view
            // has done a true full-frame repaint since the last structural event. Until then the
            // surface may not hold the row's pixels at all (first frame on a newly paged-in pager
            // page, transparent-scheme change, etc.) and skipping would leave the row transparent
            // — the black-row regression. The view passes {@code pixelsValid=false} in that case,
            // and we draw every visible row (clipped to dirtyRect) to be safe.
            if (dirtyRect != null && pixelsValid && !forceDraw && !screen.isRowDirty(row)) continue;
            final float rowTop = heightOffset - mFontLineSpacing;
            // F1: on a full repaint drawColor() above has already covered the whole canvas with
            // bgColor, so filling every row again with that same colour over that same area is a
            // second full-screen pass per frame for nothing — and this is the hottest path there
            // is (live bottom, fling).
            //
            // F4: on a partial repaint the fill spans the *whole* grid, not dirtyRect's horizontal
            // extent. dirtyRect is canvas.getClipBounds(), i.e. the bounding box of the damage:
            // when two differently-sized rects coalesce into one frame (a narrow scrollbar strip
            // plus a row band, or a clip imposed by a parent) the bbox is wider than either, and
            // deriving the fill from it erases part of a row without repainting it. The canvas
            // clips the rect to the real damage anyway, so the painted area is unchanged — the
            // same clipped quad, just no longer able to out-run the text.
            if (dirtyRect != null) {
                canvas.drawRect(0f, rowTop, gridRight, heightOffset, baseFillPaint);
            }
            if (dirtyRect != null) screen.clearRowDirty(row);

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.getLineOrBlank(row);
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();
            // G2: hoisted for the whole row — see the combining-char eater and the tail scan below.
            final boolean rowHasComplexChars = lineObject.hasNonOneWidthOrSurrogateChars();

            // T8 ("tail-run"): for plain rows, find the first column of the blank tail before the
            // main column loop, then run the loop in a cheaper "tail mode" from that column on.
            // Every cell in the tail is ' ' with WcWidth 1, so we can skip the WcWidth table
            // lookup, the isHighSurrogate branch, the code-point decode and the per-cell
            // measureText — the four biggest per-cell costs in the hot loop. The run-merge and F0
            // logic are identical to the content path, so runs of styled blanks (TUI status
            // bars), cursor and selection cells and underline / strike-through decoration all
            // still split and draw exactly as before. The pre-scan is a true char compare only
            // when one char == one column (no wide, no surrogate, no combining), so the fast
            // path is gated on `!mHasNonOneWidthOrSurrogateChars`; other rows walk all `columns`
            // cells the same as today.
            int tailStartCol = columns;
            boolean spaceMismatch = false;
            if (!rowHasComplexChars) {
                int c = columns - 1;
                while (c >= 0 && line[c] == ' ') c--;
                tailStartCol = c + 1;
                if (tailStartCol < columns) {
                    // The tail-mode assumption "measured(' ') == mFontWidth" must hold, otherwise
                    // the tail run's measured width would be wrong. Measure once (line/index/count
                    // are only consulted for supplementary code points; ' ' is BMP so they're
                    // effectively ignored).
                    spaceMismatch = Math.abs(measureCodePoint(' ', line, 0, 1) - mFontWidth) > mFontWidthTolerance;
                    if (spaceMismatch) tailStartCol = columns; // disable tail mode this row
                }
            }

            // A2 fast path: a row that is nothing but spaces under a single uniform style, with no
            // cursor and no selection, has no glyph to draw — the only thing it can contribute to
            // the frame is a background rectangle when that style paints a non-default background.
            // Skipping the run split here saves the per-column wcwidth + measure work and the
            // drawTextRun() of `columns` blanks. This is the common case for the empty rows below
            // the prompt, for cleared regions and for the tail of the alternate screen.
            if (cursorX < 0 && selx1 < 0 && selx2 < 0 && lineObject.isBlankAndUniform()) {
                final long uniformStyle = lineObject.getStyle(0);
                {
                    // Underline and strike-through are text decorations: drawTextRun() paints them
                    // across blank cells too, so a decorated row of spaces is *not* blank on screen
                    // and must go through the normal path.
                    final int effect = TextStyle.decodeEffect(uniformStyle);
                    if ((effect & (TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE | TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH)) == 0) {
                        resolveRunColors(uniformStyle, reverseVideo, palette, mColorOut);
                        final int backColor = mColorOut[1];
                        if (backColor != rawBgColor) {
                            final Paint fillPaint;
                            if (mBackgroundAlpha >= 255) {
                                fillPaint = mBgPaint;
                                fillPaint.setColor(backColor);
                            } else {
                                fillPaint = mBgSrcPaint;
                                fillPaint.setColor((backColor & 0x00FFFFFF) | (mBackgroundAlpha << 24));
                            }
                            canvas.drawRect(0f, heightOffset - mFontLineSpacingAndAscent + mFontAscent,
                                columns * mFontWidth, heightOffset, fillPaint);
                        }
                        continue;
                    }
                }
            }

            // G3: everything from `g3Start` to the right edge is provably
            //   · a blank (' ') cell — the T8 pre-scan above,
            //   · one char wide — the row has no wide/surrogate/combining char,
            //   · under one single style — TerminalRow's maintained uniform-suffix bound,
            //   · and outside the cursor and the selection.
            // So it is exactly one run, and the loop can stop there: emitting it directly replaces
            // `columns - g3Start` iterations of getStyle/cursor/selection/run-break bookkeeping
            // with two addRun() calls. This is the difference between "the tail is cheap" (T8) and
            // "the tail is free". Measured on the render scan stand: 16.6 -> 5.6 µs/frame with the
            // ASCII path. The bound is only ever an over-estimate, so when in doubt the whole
            // optimisation simply does not fire and the row is walked as before.
            int g3Start = columns;
            if (tailStartCol < columns) {
                final int uniformFrom = lineObject.getStyleUniformFromColumn();
                final int candidate = (uniformFrom > tailStartCol) ? uniformFrom : tailStartCol;
                // A cursor or a selection inside the tail would have to split it, so bail out
                // instead — correctness before speed, and both are rare.
                if (candidate < columns && cursorX < candidate && (selx1 < 0 || selx2 < candidate)) {
                    g3Start = candidate;
                }
            }
            final int lastColumn = (g3Start < columns) ? g3Start : columns;

            mRunCount = 0;
            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            // H1: same, for the "this run paints solid area, not text" flag.
            boolean lastRunBlockFill = false;
            // A3: scale factor (measured / expected) of the current run's mismatched glyphs, -1 = none.
            float lastRunMismatchRatio = -1.f;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;
            // F0: char index just past the last non-space code point seen in the current run, and
            // the two reasons a run must keep its trailing blanks. See the trim at the run closes.
            int runContentEnd = 0;
            boolean runHasCombining = false;
            boolean lastRunNoTrim = false;

            for (int column = 0; column < lastColumn; ) {
                // T8: in the blank tail the cell is provably ' ' with WcWidth 1, so all the
                // per-cell decode + measure work is unnecessary. The run-merge and F0 logic that
                // follows is the same code in both modes.
                final boolean inTail = column >= tailStartCol;
                final char charAtIndex;
                final boolean charIsHighsurrogate;
                final int charsForCodePoint;
                final int codePoint;
                final int codePointWcWidth;
                final float measuredCodePointWidth;
                if (inTail) {
                    charAtIndex = ' ';
                    charIsHighsurrogate = false;
                    charsForCodePoint = 1;
                    codePoint = ' ';
                    codePointWcWidth = 1;
                    measuredCodePointWidth = mFontWidth;
                } else {
                    charAtIndex = line[currentCharIndex];
                    // G1: ASCII is the common case and needs no WcWidth table lookup, no surrogate
                    // branch and no measureCodePoint() call — three array reads instead.
                    if (charAtIndex < 0x80) {
                        charIsHighsurrogate = false;
                        charsForCodePoint = 1;
                        codePoint = charAtIndex;
                        codePointWcWidth = asciiWc[charAtIndex];
                        measuredCodePointWidth = asciiMeasure[charAtIndex];
                    } else {
                        charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                        charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                        codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                        codePointWcWidth = WcWidth.width(codePoint);
                        measuredCodePointWidth = measureCodePoint(codePoint, line, currentCharIndex, charsForCodePoint);
                    }
                }
                // G5: the width the cell is supposed to occupy. wcwidth is 0, 1 or 2, so the
                // multiply is avoided for the (by far most common) single-width case.
                final float expectedCodePointWidth = (codePointWcWidth == 1) ? mFontWidth
                    : (codePointWcWidth == 2) ? mFontWidth2 : codePointWcWidth * mFontWidth;
                final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = column >= selx1 && column <= selx2;
                final long style = lineObject.getStyle(column);

                // Check if the measured text width for this code point is not the same as that expected by wcwidth().
                // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
                // smileys which android font renders as wide.
                // If this is detected, we draw this code point scaled to match what wcwidth() expects.
                // F5: same test as before, expressed as a multiply — one float division per code
                // point of every frame removed from the hottest loop in the renderer.
                // T8: the tail's ' ' is already known to match (the row was disqualified above when
                // it didn't), so this collapses to false with no multiply.
                final boolean fontWidthMismatch = inTail ? false
                    : (charAtIndex < 0x80) ? asciiMismatch[charAtIndex]
                    : Math.abs(measuredCodePointWidth - expectedCodePointWidth) > mFontWidthTolerance;

                // A3: how much this code point's measured width deviates from the cell width it is
                // supposed to occupy. A run is drawn with a single canvas scale derived from its
                // totals (see drawRunText), so a run may only hold mismatched code points that need
                // the *same* scale — mixing different factors is what makes an emoji get clipped
                // (glyph wider than its cell) or squeezed (glyph narrower than its cell).
                //
                // Adjacent mismatched glyphs with a bit-identical factor (a row of box-drawing
                // characters, a run of the same emoji, braille) are therefore merged into one run:
                // one save/scale/restore + one drawTextRun + one background rect instead of one of
                // each per glyph. The result is pixel-identical — every glyph in the run is scaled
                // by exactly the factor it would have had on its own. Anything less than exact
                // equality goes back to the old one-run-per-glyph behaviour.
                final float mismatchRatio = (fontWidthMismatch && codePointWcWidth > 0)
                    ? measuredCodePointWidth / expectedCodePointWidth : -1.f;
                final boolean scaleChanged = fontWidthMismatch && lastRunFontWidthMismatch
                    && (mismatchRatio < 0.f || lastRunMismatchRatio < 0.f || mismatchRatio != lastRunMismatchRatio);

                // H1: a block element (U+2580..U+259F) paints an area, not a glyph outline — the cell
                // it sits in reads as a background, not as text. Such a run has to honour the
                // configured background transparency (see drawRunText), so it may not share a run
                // with neighbouring text: break on the flag. The tail's ' ' and every ASCII cell
                // fall outside the range, so the hot path only pays one compare.
                final boolean blockFill = codePoint >= 0x2580 && codePoint <= 0x259F;

                if (blockFill != lastRunBlockFill || style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch != lastRunFontWidthMismatch || scaleChanged) {
                    if (column != 0) {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        // F0: hand drawTextRun() only up to the last non-blank code point of the
                        // run. See the comment on the final addRun() below for why and when this
                        // is safe.
                        if (!lastRunNoTrim && !runHasCombining && runContentEnd < currentCharIndex) {
                            charsSinceLastRun = runContentEnd - lastRunStartIndex;
                        }
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }
                        addRun(lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            lastRunStyle, cursorColor, cursorShape, reverseVideo || invertCursorTextColor || lastRunInsideSelection, lastRunFontWidthMismatch,
                            lastRunBlockFill);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                    lastRunBlockFill = blockFill;
                    lastRunMismatchRatio = mismatchRatio;
                    // F0: a new run starts here, so its content boundary starts here too. Whether
                    // it may be trimmed is decided by what the run *is*:
                    //  · underline / strike-through are text decorations that drawTextRun() paints
                    //    across blank cells as well, so trimming would shorten the line;
                    //  · a mismatched run is drawn with a single canvas scale derived from the
                    //    measured width of all its code points, so dropping some would rescale it.
                    lastRunNoTrim = fontWidthMismatch
                        || (TextStyle.decodeEffect(style) & (TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE | TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH)) != 0;
                    runContentEnd = currentCharIndex;
                    runHasCombining = false;
                }
                measuredWidthForRun += measuredCodePointWidth;
                column += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
                // F0: remember how far the run's real content reaches. A trailing blank is a
                // genuine ' ' that has gone through clear(): `mSpaceUsed` is the column count (not
                // the content length), so the loop above walks all `columns` cells of every row
                // and every one of those blanks used to be shaped and drawn.
                if (codePoint != ' ') runContentEnd = currentCharIndex;
                // G2: the combining-char eater called WcWidth.width(line, i) for *every* cell of
                // every row — a 64 KB static table read per cell, on the hot path, to almost always
                // conclude "nothing to eat". `hasNonOneWidthOrSurrogateChars()` is a maintained
                // per-row flag that is exactly "this row contains a code point whose display width
                // is not 1, or a surrogate pair": a zero-width (combining) char sets it, so a row
                // without it provably has no combining mark here and the loop can be skipped
                // wholesale. Measured on the render scan stand: 16.6 -> 14.3 µs/frame on its own.
                if (rowHasComplexChars) {
                    while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                        // Eat combining chars so that they are treated as part of the last non-combining code point,
                        // instead of e.g. being considered inside the cursor in the next run.
                        currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                        // F0: a combining mark over a *space* is visible, and a mark belongs to the
                        // code point before it, so a run that swallowed any must keep its full text.
                        runHasCombining = true;
                    }
                }
            }

            int columnWidthSinceLastRun = columns - lastRunStartColumn;
            int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
            // G3: the loop stopped at the tail, so close the run in progress there and emit the
            // tail itself as a second run. g3Start == 0 means the loop never ran and there is no
            // run in progress; `lastRunStartColumn == -1` would otherwise produce a bogus one.
            final boolean g3 = g3Start < columns;
            if (g3) columnWidthSinceLastRun = g3Start - lastRunStartColumn;
            // F0: trim the final run too — it is the one that holds the whole tail of the row,
            // which is where essentially all the wasted blanks live.
            //
            // Safe because everything that is visible on a blank cell is drawn from *columns*,
            // never from the run's char count: the base fill, pass A (background / selection),
            // the cursor rectangle and the scale of a mismatch run are all derived from
            // startColumn/runWidthColumns. Only drawTextRun() itself consumes the char count, and
            // a trailing space contributes no glyph. Underlined and mismatched runs are excluded
            // above (decorations and scale), runs with combining marks just above (a mark over a
            // space is visible).
            if (!lastRunNoTrim && !runHasCombining && runContentEnd < currentCharIndex) {
                charsSinceLastRun = runContentEnd - lastRunStartIndex;
            }
            int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
            boolean invertCursorTextColor = false;
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true;
            }
            // When g3Start == 0 there is nothing to the left of the tail.
            if (!g3 || g3Start > 0) {
                addRun(lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                    lastRunStyle, cursorColor, cursorShape, reverseVideo || invertCursorTextColor || lastRunInsideSelection, lastRunFontWidthMismatch,
                    lastRunBlockFill);
            }
            if (g3) {
                // The tail run: `columns - g3Start` blank cells under one style, no cursor and no
                // selection in it. Its char count is 0 unless the style carries a text decoration —
                // underline and strike-through are painted by drawTextRun() across blank cells too,
                // so those runs keep their blanks (the same rule as F0's `lastRunNoTrim`).
                final int tailColumns = columns - g3Start;
                final long tailStyle = lineObject.getStyle(g3Start);
                final boolean tailNoTrim = (TextStyle.decodeEffect(tailStyle)
                    & (TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE | TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH)) != 0;
                addRun(g3Start, tailColumns, currentCharIndex, tailNoTrim ? tailColumns : 0,
                    tailColumns * mFontWidth, tailStyle, 0, cursorShape, reverseVideo, false,
                    false);
            }

            // Resolve each run's colors once here so that pass A (backgrounds) and pass B (text)
            // below do not each re-run the palette lookup + reverse-video swap per run per frame.
            for (int i = 0; i < mRunCount; i++) {
                resolveRunColors(mRunStyle[i], mRunReverseVideo[i], palette, mColorOut);
                mRunForeColor[i] = mColorOut[0];
                mRunBackColor[i] = mColorOut[1];
            }

            // G4: merge adjacent runs that are indistinguishable on screen. Pass A already collapses
            // same-colour background rects, but pass B still issues one drawTextRun() per run, and
            // two neighbouring cells that carry *different* raw style words can still be identical
            // once resolved — a 256-colour index that maps to the same ARGB, a bold flag that only
            // matters for the first 8 palette entries, a protected/blink bit nothing renders.
            //
            // This is a post-pass over the finished run list rather than a change to the run-break
            // condition: resolving colours per *cell* (to compare them) would put two palette
            // lookups and a handful of branches into the hottest loop in the renderer, which is
            // exactly the trade the plan flagged as "measure first". Over the ~10 runs of a row it
            // costs a dozen long compares.
            if (mRunCount > 1) {
                int w = 0;
                for (int r = 1; r < mRunCount; r++) {
                    if (runsMerge(w, r)) {
                        mergeRunInto(w, r);
                    } else {
                        w++;
                        if (w != r) copyRun(r, w);
                    }
                }
                mRunCount = w + 1;
            }

            // Pass A: background rectangles, grouped per consecutive same-color runs and drawn
            // immediately with drawRect(). Mismatch runs (scaled glyphs) are skipped here and
            // drawn in pass B with their scale. Note: no Path batching - drawPath() records the
            // path into the display list and replays it after onDraw() returns, which is fragile
            // (e.g. rewinding or reusing the path too early yields garbage primitives on some
            // GPU drivers), so plain drawRect() calls are used instead.
            for (int i = 0; i < mRunCount; i++) {
                if (mRunFontWidthMismatch[i]) continue;
                final int backColor = mRunBackColor[i];
                // Skip runs whose background equals the base fill — it is already on the canvas.
                // The comparison must use rawBgColor, the *effective* base colour: reverse video
                // swaps fore/back in resolveRunColors, so there the default cells carry the
                // foreground palette entry as their background (comparing against
                // COLOR_INDEX_BACKGROUND would repaint the whole screen opaque and kill the
                // transparency in reverse-video mode).
                if (backColor == rawBgColor) continue;

                // Extend the group to the right while the background color stays the same.
                int endRun = i + 1;
                while (endRun < mRunCount && !mRunFontWidthMismatch[endRun]) {
                    if (mRunBackColor[endRun] != backColor) break;
                    endRun++;
                }

                final float left = mRunStartColumn[i] * mFontWidth;
                final float right = (mRunStartColumn[endRun - 1] + mRunWidthColumns[endRun - 1]) * mFontWidth;
                final Paint fillPaint;
                if (mBackgroundAlpha >= 255) {
                    // Fast path: opaque painted background, default SRC_OVER compositing.
                    fillPaint = mBgPaint;
                    fillPaint.setColor(backColor);
                } else {
                    // Painted backgrounds (explicit SGR/truecolor colours, inverse video,
                    // selection) get the same alpha as the default fill so the wallpaper shows
                    // through them at the same rate as through the default background. The fill
                    // must use SRC: it replaces the base fill instead of stacking on it — an
                    // SRC_OVER translucent layer over the translucent base would compose to a
                    // higher alpha (2A−A²), leaving painted cells more opaque than (and tinted
                    // by) their neighbours.
                    // C2: mBgSrcPaint already carries SRC, so no mode mutation here.
                    fillPaint = mBgSrcPaint;
                    fillPaint.setColor((backColor & 0x00FFFFFF) | (mBackgroundAlpha << 24));
                }
                canvas.drawRect(left, heightOffset - mFontLineSpacingAndAscent + mFontAscent, right, heightOffset, fillPaint);

                i = endRun - 1;  // skip the runs already covered by this rectangle
            }

            // Pass B: text (and cursor, and any scaled background) drawn on top of the backgrounds.
            for (int i = 0; i < mRunCount; i++) {
                drawRunText(canvas, line, heightOffset, mRunStartColumn[i], mRunWidthColumns[i], mRunStartChar[i],
                    mRunCharCount[i], mRunMeasuredWidth[i], mRunCursorColor[i], mRunCursorStyle[i], mRunStyle[i],
                    mRunForeColor[i], mRunBackColor[i], mRunFontWidthMismatch[i], rawBgColor, mRunBlockFill[i]);
            }
        }

        canvas.restore();

        // E2: the per-row dirty set is dropped incrementally as each visible row is drawn (see
        // clearRowDirty() above). Rows that were dirty but outside the clip keep their bit and
        // are redrawn when they scroll back into view. Dropping the whole pending set up-front
        // (the old behaviour) would lose those bits if the view forced a full draw into a
        // partial clip while the surface was still "unknown" (see TerminalView.onDraw's
        // mPixelsValid handling), so the global clearDirtyState() lives in the view now, called
        // only after a real full-frame draw.
    }

    private void ensureRunCapacity(int columns) {
        if (mRunStartColumn == null || mRunStartColumn.length < columns) {
            mRunStartColumn = new int[columns];
            mRunWidthColumns = new int[columns];
            mRunStartChar = new int[columns];
            mRunCharCount = new int[columns];
            mRunMeasuredWidth = new float[columns];
            mRunStyle = new long[columns];
            mRunCursorColor = new int[columns];
            mRunCursorStyle = new int[columns];
            mRunReverseVideo = new boolean[columns];
            mRunFontWidthMismatch = new boolean[columns];
            mRunBlockFill = new boolean[columns];
            mRunForeColor = new int[columns];
            mRunBackColor = new int[columns];
        }
    }

    /**
     * G4: can runs {@code a} and {@code b} be drawn as one run?
     *
     * <p>Resolved colours are compared rather than the raw style words — that is the whole point —
     * together with the full effect bits, because {@link #drawRunText} reads bold / underline /
     * italic / strike / dim / invisible straight out of the style. The cursor colour must match as
     * well so a merged run never widens the cursor rectangle onto a cell that is not part of the
     * cursor. Scaled (font-width-mismatched) runs are excluded: a run is drawn with one canvas
     * scale derived from its own measured width, and two adjacent mismatch runs by construction
     * have different ratios (A3 merged the ones that do not).</p>
     */
    private boolean runsMerge(int a, int b) {
        return mRunForeColor[a] == mRunForeColor[b]
            && mRunBackColor[a] == mRunBackColor[b]
            && mRunReverseVideo[a] == mRunReverseVideo[b]
            && mRunCursorColor[a] == mRunCursorColor[b]
            && mRunBlockFill[a] == mRunBlockFill[b]
            && !mRunFontWidthMismatch[a] && !mRunFontWidthMismatch[b]
            && TextStyle.decodeEffect(mRunStyle[a]) == TextStyle.decodeEffect(mRunStyle[b]);
    }

    /**
     * G4: append run {@code b} to run {@code a}.
     *
     * <p>A run's char range ends exactly where the next one begins, so the merged run is one
     * contiguous range starting at {@code a}'s start char — which is why the text still lines up
     * with the columns. Two cases: when {@code b} carries no glyph (F0 trimmed it to trailing
     * blanks) {@code a}'s text is untouched and only the column span grows; otherwise the range is
     * extended to the end of {@code b}'s text, picking {@code a}'s own trimmed blanks back up on
     * the way. Those blanks are spaces of a non-mismatch run, so they advance by exactly one cell
     * and place {@code b}'s glyphs where they belong.</p>
     */
    private void mergeRunInto(int a, int b) {
        mRunWidthColumns[a] += mRunWidthColumns[b];
        mRunMeasuredWidth[a] += mRunMeasuredWidth[b];
        if (mRunCharCount[b] > 0) {
            mRunCharCount[a] = (mRunStartChar[b] + mRunCharCount[b]) - mRunStartChar[a];
        }
    }

    /** G4: move run {@code from} to slot {@code to} during the in-place run compaction. */
    private void copyRun(int from, int to) {
        mRunStartColumn[to] = mRunStartColumn[from];
        mRunWidthColumns[to] = mRunWidthColumns[from];
        mRunStartChar[to] = mRunStartChar[from];
        mRunCharCount[to] = mRunCharCount[from];
        mRunMeasuredWidth[to] = mRunMeasuredWidth[from];
        mRunStyle[to] = mRunStyle[from];
        mRunCursorColor[to] = mRunCursorColor[from];
        mRunCursorStyle[to] = mRunCursorStyle[from];
        mRunReverseVideo[to] = mRunReverseVideo[from];
        mRunFontWidthMismatch[to] = mRunFontWidthMismatch[from];
        mRunBlockFill[to] = mRunBlockFill[from];
        mRunForeColor[to] = mRunForeColor[from];
        mRunBackColor[to] = mRunBackColor[from];
    }

    private void addRun(int startColumn, int runWidthColumns, int startCharIndex, int runWidthChars, float measuredWidth,
                        long style, int cursorColor, int cursorStyle, boolean reverseVideo, boolean fontWidthMismatch,
                        boolean blockFill) {
        ensureRunCapacity(mRunCount + 1);
        mRunStartColumn[mRunCount] = startColumn;
        mRunWidthColumns[mRunCount] = runWidthColumns;
        mRunStartChar[mRunCount] = startCharIndex;
        mRunCharCount[mRunCount] = runWidthChars;
        mRunMeasuredWidth[mRunCount] = measuredWidth;
        mRunStyle[mRunCount] = style;
        mRunCursorColor[mRunCount] = cursorColor;
        mRunCursorStyle[mRunCount] = cursorStyle;
        mRunReverseVideo[mRunCount] = reverseVideo;
        mRunFontWidthMismatch[mRunCount] = fontWidthMismatch;
        mRunBlockFill[mRunCount] = blockFill;
        mRunCount++;
    }

    /** Resolve a run's style into foreground/background colors (with bold + reverse-video handling). */
    private void resolveRunColors(long textStyle, boolean reverseVideo, int[] palette, int[] out) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }
        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }
        out[0] = foreColor;
        out[1] = backColor;
    }

    private void drawRunText(Canvas canvas, char[] text, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, int foreColor, int backColor, boolean fontWidthMismatch,
                             int baseBgColor, boolean blockFill) {
        // foreColor/backColor are pre-resolved ARGB colors (palette lookup + reverse-video swap done
        // once per run in render()); only the effect bits and the dim adjustment are handled here.
        final int effect = TextStyle.decodeEffect(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        if (fontWidthMismatch && Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }

        // Background for mismatch (scaled) runs only; non-mismatch backgrounds are batched in pass A.
        // baseBgColor is the effective base fill colour (palette entry of the default background,
        // or the default foreground under reverse video) — a swapped cell carrying it is the
        // default background and needs no fill here. Painted colours get the same alpha as the
        // base fill so all backgrounds share the configured transparency.
        //
        // The fill must go through mBgPaint in SRC mode, exactly like pass A — NOT through
        // mTextPaint: mTextPaint has no xfermode, so a translucent cell colour laid down in
        // SRC_OVER over the translucent base fill composes to 2A-A^2 (0x80 over 0x80 = 0xC0)
        // and picks up a (1-A)/(2-A) share of the base colour. That is exactly what made
        // box-drawing / block / braille glyphs (which come from a fallback font and therefore
        // always take the mismatch path) render as denser cells with the wallpaper barely
        // showing through. Never set SRC on mTextPaint itself: text is drawn with partial
        // glyph coverage, and SRC would erase the background behind the anti-aliased edges.
        if (fontWidthMismatch && backColor != baseBgColor) {
            final Paint fillPaint;
            if (mBackgroundAlpha >= 255) {
                // Fast path: opaque painted background, default SRC_OVER compositing.
                fillPaint = mBgPaint;
                fillPaint.setColor(backColor);
            } else {
                // C2: mBgSrcPaint already carries SRC, so no mode mutation here.
                fillPaint = mBgSrcPaint;
                fillPaint.setColor((backColor & 0x00FFFFFF) | (mBackgroundAlpha << 24));
            }
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, fillPaint);
        }

        if (cursor != 0) {
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= ((right - left) * 3) / 4.;
            // The cursor is a background fill too, so it honours the configured transparency
            // exactly like pass A and the mismatch-run fill: same alpha, laid down in SRC.
            // An opaque cursor rect was the last remaining "solid" patch on a translucent
            // screen — it punched a dense hole through the wallpaper wherever it blinked.
            // Only the *glyph* on top of a block cursor stays opaque (it is text, and it is
            // drawn below by the normal text path with the reverse-video swap applied).
            final Paint cursorPaint;
            if (mBackgroundAlpha >= 255) {
                // Fast path: opaque cursor, default SRC_OVER compositing.
                cursorPaint = mBgPaint;
                cursorPaint.setColor(cursor);
            } else {
                // C2: mBgSrcPaint already carries SRC, so no mode mutation here.
                cursorPaint = mBgSrcPaint;
                cursorPaint.setColor((cursor & 0x00FFFFFF) | (mBackgroundAlpha << 24));
            }
            canvas.drawRect(left, y - cursorHeight, right, y, cursorPaint);
        }

        // F0: runWidthChars == 0 after trimming means the run holds nothing but trailing blanks —
        // there is no glyph to shape or draw. The cursor/background rectangles above are still
        // drawn, which is the whole point of keeping the run around.
        if (runWidthChars > 0 && (effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling per xterm convention
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            // B4: only touch the native paint setters when the value actually changed. The style
            // setters are the expensive ones (they recompute the Skia font state), and the vast
            // majority of adjacent runs share the same state, so this eliminates most of their calls.
            if (foreColor != mLastPaintForeColor) {
                mTextPaint.setColor(foreColor);
                mLastPaintForeColor = foreColor;
            }
            if (bold != mLastPaintBold) {
                mTextPaint.setFakeBoldText(bold);
                mLastPaintBold = bold;
            }
            if (underline != mLastPaintUnderline) {
                mTextPaint.setUnderlineText(underline);
                mLastPaintUnderline = underline;
            }
            if (italic != mLastPaintItalic) {
                mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
                mLastPaintItalic = italic;
            }
            if (strikeThrough != mLastPaintStrike) {
                mTextPaint.setStrikeThruText(strikeThrough);
                mLastPaintStrike = strikeThrough;
            }

            // The text alignment is the default Paint.Align.LEFT.
            final float textY = y - mFontLineSpacingAndAscent;
            // H1: a block element (U+2580..U+259F) paints a solid or dithered *area*, so on a
            // translucent terminal it is a background in everything but name. Leaving it opaque
            // punches a fully saturated hole through the wallpaper — the visible symptom was an
            // opencode input-box border drawn as a row of U+2580, 16 px of flat (245,245,245)
            // with row std 0.00 while every neighbouring row sat at 10..39.
            //
            // The fix cannot be "push the alpha into the paint": SRC_OVER of (C, a) over the base
            // fill, which is itself (A, a), composes to 2a-a^2, and SRC on a text paint erases the
            // rest of the cell (a text paint's coverage is 0 outside the glyph — the same reason
            // the mismatch fill above refuses to use mTextPaint for its rectangle).
            //
            // SRC_ATOP is the mode that does it in one draw: it replaces the destination's colour
            // with the source's and keeps the destination's alpha, so the glyph's own coverage
            // becomes the only partial-alpha term and the cell is handed to the compositor at
            // exactly (a·(m·C + (1-m)·A), a) — the same value a pass-A rectangle of that colour
            // would have produced, for every coverage m, rim included. Being idempotent for
            // m = 1, it also makes the 1 px overlap between neighbouring block glyphs (fallback
            // font: 18 px advance in a 16 px cell) invisible instead of a seam.
            //
            // The paint keeps its normal *opaque* colour: the transparency is contributed by the
            // destination alpha, not by the ink. Adding mBackgroundAlpha here would re-introduce
            // the doubled-alpha error at the rim. Geometry is untouched — same text, same matrix,
            // same left/right — so a mismatch-scaled run looks exactly as it did before.
            final boolean blockTranslucent = blockFill && mBackgroundAlpha < 255;
            if (blockTranslucent) mTextPaint.setXfermode(SRC_ATOP_XFERMODE);
            canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, textY, false, mTextPaint);
            if (blockTranslucent) mTextPaint.setXfermode(null);
        }

        if (savedMatrix) canvas.restore();
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}
