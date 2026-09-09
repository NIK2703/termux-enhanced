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
    /** Resolved ARGB foreground/background color of each run, computed once per row (see render). */
    private int[] mRunForeColor;
    private int[] mRunBackColor;
    private int mRunCount;
    /** Paint for the background rectangles - never carries text attributes. */
    private final Paint mBgPaint = new Paint();
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

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

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

    /** Measure the on-screen width of a code point, using the per-code-point cache. */
    private float measureCodePoint(int codePoint, char[] line, int index, int count) {
        if (codePoint < 0x10000) {
            float cached = bmpMeasures[codePoint];
            if (cached == 0f && codePoint != 0) {
                cached = mTextPaint.measureText(line, index, count);
                bmpMeasures[codePoint] = cached;
            }
            return cached;
        }
        Float cached = supplementaryMeasures.get(codePoint);
        if (cached == null) {
            cached = mTextPaint.measureText(line, index, count);
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
        // background colour (ls --color, htop, syntax highlighting) keep their own opaque
        // colour — that colour is part of the program's layout and must stay readable. Pass A
        // below already skips those runs (backColor == palette[COLOR_INDEX_BACKGROUND]), so
        // nothing else has to change to keep them solid.
        final int bgColor = (mBackgroundAlpha >= 255)
            ? rawBgColor
            : ((rawBgColor & 0x00FFFFFF) | (mBackgroundAlpha << 24));
        if (dirtyRect == null) {
            canvas.drawColor(bgColor, PorterDuff.Mode.SRC);
        } else {
            if (mBackgroundAlpha >= 255) {
                // Fast path: identical to the pre-transparency behaviour, no xfermode churn.
                mBgPaint.setColor(bgColor);
                canvas.drawRect(dirtyRect.left, dirtyRect.top, dirtyRect.right, dirtyRect.bottom, mBgPaint);
            } else {
                mBgPaint.setXfermode(SRC_XFERMODE);
                mBgPaint.setColor(bgColor);
                canvas.drawRect(dirtyRect.left, dirtyRect.top, dirtyRect.right, dirtyRect.bottom, mBgPaint);
                // mBgPaint is reused for the per-run background fills in pass A, which need
                // the default SRC_OVER — always restore it.
                mBgPaint.setXfermode(null);
            }
        }

        // Translate the whole grid so the leftover space (from glyphs that do not fit the
        // view) is split symmetrically around it. The drawTextRun() scale compensation block
        // (canvas.scale + left *= mes/runWidthColumns) composes with this translate, so
        // width-mismatched glyph runs keep landing exactly on their shifted cells.
        canvas.save();
        canvas.translate(xOffset, yOffset);

        ensureRunCapacity(columns);

        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;
            // Partial repaint: skip rows that do not intersect the dirty region. heightOffset is
            // advanced above for every row, so the y-coordinate stays correct for rendered rows.
            if (row < renderStartRow || row >= renderEndRow) continue;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.getLineOrBlank(row);
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();

            mRunCount = 0;
            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;

            for (int column = 0; column < columns; ) {
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final int codePointWcWidth = WcWidth.width(codePoint);
                final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = column >= selx1 && column <= selx2;
                final long style = lineObject.getStyle(column);

                // Check if the measured text width for this code point is not the same as that expected by wcwidth().
                // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
                // smileys which android font renders as wide.
                // If this is detected, we draw this code point scaled to match what wcwidth() expects.
                final float measuredCodePointWidth = measureCodePoint(codePoint, line, currentCharIndex, charsForCodePoint);
                final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

                // Break the run whenever the font-width-mismatch flag changes, AND additionally
                // break on every mismatched code point so each such glyph is scaled individually
                // rather than averaged with its neighbours. Averaging across a run is what makes
                // some emoji get clipped (glyph wider than its cell) or squeezed (glyph narrower
                // than its cell).
                if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch) {
                    if (column != 0) {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }
                        addRun(lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            lastRunStyle, cursorColor, cursorShape, reverseVideo || invertCursorTextColor || lastRunInsideSelection, lastRunFontWidthMismatch);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                }
                measuredWidthForRun += measuredCodePointWidth;
                column += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    // Eat combining chars so that they are treated as part of the last non-combining code point,
                    // instead of e.g. being considered inside the cursor in the next run.
                    currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                }
            }

            final int columnWidthSinceLastRun = columns - lastRunStartColumn;
            final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
            int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
            boolean invertCursorTextColor = false;
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true;
            }
            addRun(lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                lastRunStyle, cursorColor, cursorShape, reverseVideo || invertCursorTextColor || lastRunInsideSelection, lastRunFontWidthMismatch);

            // Resolve each run's colors once here so that pass A (backgrounds) and pass B (text)
            // below do not each re-run the palette lookup + reverse-video swap per run per frame.
            for (int i = 0; i < mRunCount; i++) {
                resolveRunColors(mRunStyle[i], mRunReverseVideo[i], palette, mColorOut);
                mRunForeColor[i] = mColorOut[0];
                mRunBackColor[i] = mColorOut[1];
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
                if (backColor == palette[TextStyle.COLOR_INDEX_BACKGROUND]) continue;

                // Extend the group to the right while the background color stays the same.
                int endRun = i + 1;
                while (endRun < mRunCount && !mRunFontWidthMismatch[endRun]) {
                    if (mRunBackColor[endRun] != backColor) break;
                    endRun++;
                }

                final float left = mRunStartColumn[i] * mFontWidth;
                final float right = (mRunStartColumn[endRun - 1] + mRunWidthColumns[endRun - 1]) * mFontWidth;
                mBgPaint.setColor(backColor);
                canvas.drawRect(left, heightOffset - mFontLineSpacingAndAscent + mFontAscent, right, heightOffset, mBgPaint);

                i = endRun - 1;  // skip the runs already covered by this rectangle
            }

            // Pass B: text (and cursor, and any scaled background) drawn on top of the backgrounds.
            for (int i = 0; i < mRunCount; i++) {
                drawRunText(canvas, line, palette, heightOffset, mRunStartColumn[i], mRunWidthColumns[i], mRunStartChar[i],
                    mRunCharCount[i], mRunMeasuredWidth[i], mRunCursorColor[i], mRunCursorStyle[i], mRunStyle[i],
                    mRunForeColor[i], mRunBackColor[i], mRunFontWidthMismatch[i]);
            }
        }

        canvas.restore();
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
            mRunForeColor = new int[columns];
            mRunBackColor = new int[columns];
        }
    }

    private void addRun(int startColumn, int runWidthColumns, int startCharIndex, int runWidthChars, float measuredWidth,
                        long style, int cursorColor, int cursorStyle, boolean reverseVideo, boolean fontWidthMismatch) {
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

    private void drawRunText(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, int foreColor, int backColor, boolean fontWidthMismatch) {
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
        if (fontWidthMismatch && backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
            // Paint color was changed for the bg fill; force the text color to be re-applied below.
            mLastPaintForeColor = -1;
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= ((right - left) * 3) / 4.;
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
            // Paint color was changed for the cursor fill; force the text color to be re-applied below.
            mLastPaintForeColor = -1;
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
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
            canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, false, mTextPaint);
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
