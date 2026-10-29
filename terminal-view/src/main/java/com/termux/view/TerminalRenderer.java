package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/**
 * Executes the render command list planned by the native terminal core on an Android
 * {@link Canvas}. All terminal state interpretation (runs, colours, selection, reverse video,
 * cursor inversion) happens natively; this class only owns font shaping, glyph measurement and
 * the Canvas raster path, which is where the platform's emoji/CJK/box-drawing quality comes from.
 */
public final class TerminalRenderer {

    /** Command stream terminator; the UTF-16 text payload follows it. */
    private static final int OP_END = 0;
    private static final int OP_ROW_BEGIN = 1;
    private static final int OP_ROW_END = 2;
    private static final int OP_RECT = 3;
    private static final int OP_TEXT = 4;
    private static final int OP_CURSOR = 5;

    private static final int EFFECT_BOLD = 1;
    private static final int EFFECT_ITALIC = 1 << 1;
    private static final int EFFECT_UNDERLINE = 1 << 2;
    private static final int EFFECT_BLINK = 1 << 3;
    private static final int EFFECT_INVERSE = 1 << 4;
    private static final int EFFECT_INVISIBLE = 1 << 5;
    private static final int EFFECT_STRIKETHROUGH = 1 << 6;
    private static final int EFFECT_BLOCK_FILL = 1 << 7;
    private static final int EFFECT_DIM = 1 << 8;

    private static final int CURSOR_BLOCK = 0;
    private static final int CURSOR_UNDERLINE = 1;
    private static final int CURSOR_BAR = 2;

    private static final float MAX_MISMATCH_SCALE = 8f;
    private static final float MIN_MISMATCH_SCALE = 0.125f;

    private static final PorterDuffXfermode SRC_XFERMODE =
        new PorterDuffXfermode(PorterDuff.Mode.SRC);
    private static final PorterDuffXfermode SRC_ATOP_XFERMODE =
        new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP);

    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();
    private final Paint mBgPaint = new Paint();
    private final Paint mBgSrcPaint = new Paint();
    private final Paint mBaseFillPaint = new Paint();
    private final Paint mBaseFillSrcPaint = new Paint();

    /** Width of a single monospaced character, measured on 'X'. */
    final float mFontWidth;
    /** The Paint font spacing, rounded up. */
    final int mFontLineSpacing;
    /** The Paint ascent (negative), rounded up. */
    private final int mFontAscent;
    /** {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private int mBackgroundAlpha = 255;

    private char[] mTextUnits = new char[1024];
    /** Number of valid UTF-16 units in {@link #mTextUnits} for the current frame. */
    private int mTextUnitCount;

    private long mLastPaintForeColorKey = -1L;
    private boolean mLastPaintBold;
    private boolean mLastPaintUnderline;
    private boolean mLastPaintItalic;
    private boolean mLastPaintStrike;

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);
        mTextPaint.setStyle(Paint.Style.FILL);

        mBgPaint.setStyle(Paint.Style.FILL);
        mBgSrcPaint.setStyle(Paint.Style.FILL);
        mBgSrcPaint.setXfermode(SRC_XFERMODE);
        mBaseFillPaint.setStyle(Paint.Style.FILL);
        mBaseFillSrcPaint.setStyle(Paint.Style.FILL);
        mBaseFillSrcPaint.setXfermode(SRC_XFERMODE);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");
    }

    /**
     * Set how transparent the terminal background is, so the wallpaper can show through.
     *
     * @param percent 0 = opaque (default), 50 = background alpha 128.
     */
    public void setBackgroundTransparencyPercent(int percent) {
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
        mBackgroundAlpha = Math.round(255f * (100 - percent) / 100f);
    }

    public final void render(TerminalEmulator emulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2,
                             float xOffset, float yOffset, int viewWidth,
                             Rect dirtyRect, boolean pixelsValid) {
        resetPaintState();

        final int minTopRow = -emulator.getScreen().getActiveTranscriptRows();
        if (topRow < minTopRow) topRow = minTopRow;

        final int columns = emulator.mColumns;
        final int rows = emulator.mRows;
        final int endRow = topRow + rows;
        final TerminalBuffer screen = emulator.getScreen();
        final int[] palette = emulator.mColors.mCurrentColors;

        final boolean reverseVideo = emulator.isReverseVideo();
        final int rawBgColor = reverseVideo
            ? palette[TextStyle.COLOR_INDEX_FOREGROUND]
            : palette[TextStyle.COLOR_INDEX_BACKGROUND];
        final int bgColor = withBackgroundAlpha(rawBgColor);

        if (dirtyRect == null) {
            canvas.drawColor(bgColor, PorterDuff.Mode.SRC);
        }

        canvas.save();
        canvas.translate(xOffset, yOffset);

        int renderStartRow = topRow;
        int renderEndRow = endRow;
        if (dirtyRect != null) {
            final float base = yOffset + mFontLineSpacingAndAscent;
            int r0 = topRow + (int) Math.floor((dirtyRect.top - base) / (float) mFontLineSpacing);
            int r1 = topRow + (int) Math.floor((dirtyRect.bottom - 1 - base) / (float) mFontLineSpacing);
            renderStartRow = Math.max(r0, topRow);
            renderEndRow = Math.min(r1 + 1, endRow);
        }

        final float viewLeft = -xOffset;
        final float viewRight = viewWidth - xOffset;
        final float gridRight = columns * mFontWidth;

        boolean forceDraw = false;
        if (dirtyRect != null && pixelsValid) {
            boolean anyDirty = false;
            for (int row = renderStartRow; row < renderEndRow; row++) {
                if (screen.isRowDirty(row)) {
                    anyDirty = true;
                    break;
                }
            }
            forceDraw = !anyDirty;
        }

        final Paint baseFillPaint = (mBackgroundAlpha >= 255) ? mBaseFillPaint : mBaseFillSrcPaint;
        baseFillPaint.setColor(bgColor);

        // For a partial frame, only ask the native core to build commands for the rows that
        // intersect the clip. Native row indices stay absolute (relative to topRow); the
        // renderer still positions them with currentRow - topRow.
        final int renderRows = renderEndRow - renderStartRow;
        final int[] commands = emulator.getRenderCommands(renderStartRow, selectionY1, selectionY2,
            selectionX1, selectionX2, mBackgroundAlpha, mFontWidth, mFontLineSpacing,
            mFontAscent, columns, renderRows);
        loadRenderText(commands, emulator);

        if (commands == null || commands.length == 0) {
            canvas.restore();
            return;
        }

        final float gridTop = mFontLineSpacingAndAscent - mFontLineSpacing;
        final float gridBottom = mFontLineSpacingAndAscent + (endRow - topRow) * mFontLineSpacing
            + mFontLineSpacing;

        float rowTop = mFontLineSpacingAndAscent;
        int cursor = 0;
        int currentRow = topRow;
        boolean rowPushed = false;
        boolean rowInWindow = false;
        boolean rowDrawn = false;
        float baseline = 0f;

        while (cursor < commands.length) {
            final int op = commands[cursor++];
            if (op == OP_END) break;

            if (op == OP_ROW_BEGIN) {
                currentRow = commands[cursor++];
                rowTop = mFontLineSpacingAndAscent + (currentRow - topRow) * mFontLineSpacing;
                baseline = rowTop - mFontAscent;
                rowInWindow = currentRow >= renderStartRow && currentRow < renderEndRow;
                rowDrawn = rowInWindow && (dirtyRect == null || !pixelsValid || forceDraw
                    || screen.isRowDirty(currentRow));

                if (rowDrawn) {
                    if (dirtyRect != null) {
                        canvas.drawRect(viewLeft, rowTop, viewRight, rowTop + mFontLineSpacing,
                            baseFillPaint);
                    }
                    canvas.save();
                    canvas.clipRect(0f, gridTop, gridRight, gridBottom);
                    rowPushed = true;
                }
                continue;
            }

            if (op == OP_ROW_END) {
                if (rowPushed) {
                    canvas.restore();
                    rowPushed = false;
                }
                if (dirtyRect != null && rowInWindow) screen.clearRowDirty(currentRow);
                continue;
            }

            if (!rowDrawn) continue;

            switch (op) {
                case OP_RECT: {
                    int startColumn = commands[cursor++];
                    int endColumn = commands[cursor++];
                    int color = commands[cursor++];
                    cursor++; // RECT_SPAN_VIEW: rows already extend to the view edges.
                    float left = startColumn * mFontWidth;
                    float right = endColumn * mFontWidth;
                    canvas.drawRect(left, rowTop, right, rowTop + mFontLineSpacing,
                        backgroundPaint(color));
                    break;
                }
                case OP_CURSOR: {
                    int column = commands[cursor++];
                    int widthColumns = commands[cursor++];
                    int color = commands[cursor++];
                    int shape = commands[cursor++];
                    drawCursor(canvas, column, widthColumns, color, shape, rowTop);
                    break;
                }
                case OP_TEXT: {
                    int startChar = commands[cursor++];
                    int charCount = commands[cursor++];
                    int startColumn = commands[cursor++];
                    int widthColumns = commands[cursor++];
                    int foreground = commands[cursor++];
                    cursor++; // background colour, already painted by OP_RECT
                    int effects = commands[cursor++];
                    drawTextRun(canvas, startChar, charCount, startColumn, widthColumns,
                        foreground, effects, baseline);
                    break;
                }
                default:
                    // Unknown opcode: stop rather than misinterpreting the rest of the stream.
                    cursor = commands.length;
                    break;
            }
        }

        if (rowPushed) canvas.restore();
        canvas.restore();
    }

    /**
     * The native render payload carries its UTF-16 text after {@code OP_END}. Prefer that copy:
     * it avoids a second JNI call and a second int[] allocation. Fall back to the legacy
     * {@code nativeRenderText} entry point for an older native library.
     */
    private void loadRenderText(int[] commands, TerminalEmulator emulator) {
        if (commands != null) {
            int i = 0;
            while (i < commands.length) {
                final int op = commands[i++];
                if (op == OP_END) {
                    if (i + 1 < commands.length) {
                        final int count = commands[i];
                        if (count >= 0 && i + 1 + count <= commands.length) {
                            loadRenderText(commands, i + 1, count);
                            return;
                        }
                    }
                    break;
                }
                switch (op) {
                    case OP_ROW_BEGIN:
                    case OP_ROW_END:
                        i += (op == OP_ROW_BEGIN) ? 1 : 0;
                        break;
                    case OP_RECT:
                    case OP_CURSOR:
                        i += 4;
                        break;
                    case OP_TEXT:
                        i += 7;
                        break;
                    default:
                        i = commands.length;
                        break;
                }
            }
        }
        loadRenderText(emulator.getRenderText());
    }

    private void loadRenderText(int[] units, int offset, int count) {
        if (count <= 0) {
            mTextUnitCount = 0;
            return;
        }
        if (mTextUnits.length < count) mTextUnits = new char[count];
        for (int i = 0; i < count; i++) mTextUnits[i] = (char) units[offset + i];
        mTextUnitCount = count;
    }

    private void loadRenderText(int[] units) {
        if (units == null) {
            if (mTextUnits.length > 0) java.util.Arrays.fill(mTextUnits, '\0');
            mTextUnitCount = 0;
            return;
        }
        if (mTextUnits.length < units.length) mTextUnits = new char[units.length];
        for (int i = 0; i < units.length; i++) mTextUnits[i] = (char) units[i];
        mTextUnitCount = units.length;
    }

    private Paint backgroundPaint(int color) {
        if (mBackgroundAlpha >= 255) {
            mBgPaint.setColor(color);
            return mBgPaint;
        }
        mBgSrcPaint.setColor(color);
        return mBgSrcPaint;
    }

    private void drawCursor(Canvas canvas, int column, int widthColumns, int color, int shape,
                            float rowTop) {
        float left = column * mFontWidth;
        float right = left + widthColumns * mFontWidth;
        float top = rowTop;
        float bottom = rowTop + mFontLineSpacing;
        if (shape == CURSOR_UNDERLINE) {
            top = bottom - mFontLineSpacing / 4f;
        } else if (shape == CURSOR_BAR) {
            right = left + (right - left) / 4f;
        }
        canvas.drawRect(left, top, right, bottom, backgroundPaint(color));
    }

    private void drawTextRun(Canvas canvas, int startChar, int charCount, int startColumn,
                             int widthColumns, int foreground, int effects, float baseline) {
        if (charCount <= 0 || startChar < 0 || startChar + charCount > mTextUnitCount) return;
        if ((effects & EFFECT_INVISIBLE) != 0) return;

        final boolean bold = (effects & (EFFECT_BOLD | EFFECT_BLINK)) != 0;
        final boolean underline = (effects & EFFECT_UNDERLINE) != 0;
        final boolean italic = (effects & EFFECT_ITALIC) != 0;
        final boolean strike = (effects & EFFECT_STRIKETHROUGH) != 0;
        final boolean dim = (effects & EFFECT_DIM) != 0;

        if (dim) {
            int red = (0xFF & (foreground >> 16)) * 2 / 3;
            int green = (0xFF & (foreground >> 8)) * 2 / 3;
            int blue = (0xFF & foreground) * 2 / 3;
            foreground = 0xFF000000 | (red << 16) | (green << 8) | blue;
        }

        applyTextStyle(foreground, bold, underline, italic, strike);

        final float left = startColumn * mFontWidth;
        final float expectedWidth = widthColumns * mFontWidth;
        // The terminal font is monospaced, so a run of narrow ASCII cells has an exact
        // advance of widthColumns * mFontWidth. Skipping measureText() here is safe and
        // removes a shaping call for the common shell/TUI text path. Runs with wide,
        // combining, or fallback glyphs still take the measured path below.
        final float measured = (widthColumns > 0 && charCount == widthColumns
            && isAsciiNarrow(startChar, charCount))
            ? expectedWidth
            : measureNeutral(startChar, charCount);

        if (expectedWidth > 0f && mismatch(measured, expectedWidth)) {
            drawTextClusters(canvas, startChar, charCount, left, baseline, effects);
        } else {
            drawGlyphs(canvas, startChar, charCount, left, expectedWidth, measured, baseline, effects);
        }
    }

    private void applyTextStyle(int foreground, boolean bold, boolean underline, boolean italic,
                                boolean strike) {
        final long key = foreground & 0xFFFFFFFFL;
        if (key != mLastPaintForeColorKey) {
            mTextPaint.setColor(foreground);
            mLastPaintForeColorKey = key;
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
            mTextPaint.setTextSkewX(italic ? -0.35f : 0f);
            mLastPaintItalic = italic;
        }
        if (strike != mLastPaintStrike) {
            mTextPaint.setStrikeThruText(strike);
            mLastPaintStrike = strike;
        }
    }

    /**
     * Draw a run whose measured advance matches its cells. If the whole run is off (a fallback
     * font glyph at 18 px in a 16 px cell, for example) the caller splits it into clusters and
     * this method scales each one to its cell width.
     */
    private void drawGlyphs(Canvas canvas, int startChar, int charCount, float left,
                            float expectedWidth, float measured, float baseline, int effects) {
        boolean scaled = false;
        float scale = 1f;
        if (expectedWidth > 0f && measured > 0f && mismatch(measured, expectedWidth)) {
            scale = expectedWidth / measured;
            if (scale <= MAX_MISMATCH_SCALE && scale >= MIN_MISMATCH_SCALE) {
                canvas.save();
                canvas.scale(scale, 1f);
                left /= scale;
                scaled = true;
            }
        }

        final boolean translucentBlock = (effects & EFFECT_BLOCK_FILL) != 0
            && mBackgroundAlpha < 255;
        if (translucentBlock) mTextPaint.setXfermode(SRC_ATOP_XFERMODE);
        // The char[] overload takes (index, count, contextIndex, contextCount), not an end index.
        canvas.drawTextRun(mTextUnits, startChar, charCount,
            startChar, charCount, left, baseline, false, mTextPaint);
        if (translucentBlock) mTextPaint.setXfermode(null);
        if (scaled) canvas.restore();
    }

    /**
     * Fallback path for runs mixing glyphs with different advance mismatches (emoji next to
     * ASCII, box-drawing art). Each base code point plus its combining marks is measured and
     * scaled on its own so one glyph cannot distort its neighbours.
     */
    private void drawTextClusters(Canvas canvas, int startChar, int charCount, float left,
                                  float baseline, int effects) {
        final int end = startChar + charCount;
        final boolean translucentBlock = (effects & EFFECT_BLOCK_FILL) != 0
            && mBackgroundAlpha < 255;
        if (translucentBlock) mTextPaint.setXfermode(SRC_ATOP_XFERMODE);
        int index = startChar;
        float x = left;
        while (index < end) {
            int codePoint = Character.codePointAt(mTextUnits, index);
            int charLength = Character.charCount(codePoint);
            int clusterEnd = index + charLength;
            // Combining marks belong to the preceding base and must be shaped with it.
            while (clusterEnd < end) {
                int next = Character.codePointAt(mTextUnits, clusterEnd);
                if (WcWidth.width(next) > 0) break;
                clusterEnd += Character.charCount(next);
            }
            int width = WcWidth.width(codePoint);
            if (width < 0) width = 0;
            float expected = width * mFontWidth;
            float measured = measureNeutral(index, clusterEnd - index);
            boolean scaled = false;
            float scale = 1f;
            if (expected > 0f && measured > 0f && mismatch(measured, expected)) {
                scale = expected / measured;
                if (scale <= MAX_MISMATCH_SCALE && scale >= MIN_MISMATCH_SCALE) {
                    canvas.save();
                    canvas.scale(scale, 1f);
                    scaled = true;
                }
            }
            canvas.drawTextRun(mTextUnits, index, clusterEnd - index, index, clusterEnd - index,
                scaled ? x / scale : x, baseline, false, mTextPaint);
            if (scaled) canvas.restore();
            x += expected;
            index = clusterEnd;
        }
        if (translucentBlock) mTextPaint.setXfermode(null);
    }

    private boolean mismatch(float measured, float expected) {
        return Math.abs(measured - expected) > Math.max(0.01f * mFontWidth, 0.01f);
    }

    private float measureNeutral(int index, int count) {
        final boolean bold = mTextPaint.isFakeBoldText();
        final float skew = mTextPaint.getTextSkewX();
        final boolean underline = mTextPaint.isUnderlineText();
        final boolean strike = mTextPaint.isStrikeThruText();
        mTextPaint.setFakeBoldText(false);
        mTextPaint.setTextSkewX(0f);
        mTextPaint.setUnderlineText(false);
        mTextPaint.setStrikeThruText(false);
        final float measured = mTextPaint.measureText(mTextUnits, index, count);
        mTextPaint.setFakeBoldText(bold);
        mTextPaint.setTextSkewX(skew);
        mTextPaint.setUnderlineText(underline);
        mTextPaint.setStrikeThruText(strike);
        return measured;
    }

    private boolean isAsciiNarrow(int startChar, int charCount) {
        for (int i = 0; i < charCount; i++) {
            if (mTextUnits[startChar + i] >= 0x80) return false;
        }
        return true;
    }

    private void resetPaintState() {
        mTextPaint.setFakeBoldText(false);
        mTextPaint.setUnderlineText(false);
        mTextPaint.setTextSkewX(0f);
        mTextPaint.setStrikeThruText(false);
        mTextPaint.setXfermode(null);
        mLastPaintBold = false;
        mLastPaintUnderline = false;
        mLastPaintItalic = false;
        mLastPaintStrike = false;
        mLastPaintForeColorKey = -1L;
    }

    private int withBackgroundAlpha(int color) {
        if (mBackgroundAlpha >= 255) return color;
        return (color & 0x00FFFFFF) | (mBackgroundAlpha << 24);
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}
