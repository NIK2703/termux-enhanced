package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.LongSparseArray;

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
    private static final int OP_FRAME_BEGIN = 6;
    private static final int OP_DAMAGE = 7;
    private static final int OP_FRAME_END = 8;
    private static final int OP_FILL_RECT = 9;
    private static final int OP_GLYPH = 10;
    private static final int OP_FILL_TRAPEZOID = 11;
    private static final int OP_IMAGE = 12;

    private static final int EFFECT_BOLD = 1;
    private static final int EFFECT_ITALIC = 1 << 1;
    private static final int EFFECT_BLOCK_FILL = 1 << 7;

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
    private final Paint mImagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path mPath = new Path();
    private final Rect mImageSrcRect = new Rect();
    private final RectF mImageDstRect = new RectF();

    private static final int MAX_CACHED_SIXEL_IMAGES = 24;

    private static final class SixelBitmap {
        final Bitmap bitmap;
        final int revision;

        SixelBitmap(Bitmap bitmap, int revision) {
            this.bitmap = bitmap;
            this.revision = revision;
        }
    }

    /** Decoded sixel surfaces keyed by the native image id. */
    private final LongSparseArray<SixelBitmap> mSixelBitmaps = new LongSparseArray<>();

    /** Width of a single monospaced character, measured on 'X'. */
    final float mFontWidth;
    /**
     * Scale from the native canvas renderer's integer cell grid to this renderer's
     * measured (possibly fractional) grid. The upstream foot renderer works in whole
     * pixels, while Java lays the view out with {@link #mFontWidth}; without this
     * correction pixel positions drift by up to half a cell per column.
     */
    private final float mCanvasScaleX;
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

    /** Reused packed render payload; grows only when the native payload outgrows it. */
    private int[] mRenderCommands = new int[1024];

    /**
     * Visible rows that reached the Canvas in this frame. Native dirty bits are
     * retired only for these rows, so a clipped or partial frame cannot lose
     * damage for rows it did not paint.
     */
    private int[] mPaintedRows = new int[64];
    private int mPaintedRowCount;

    /** Native frame damage, as [firstRow,lastRow] pairs for this render range. */
    private int[] mFrameDamageRanges = new int[64];
    private int mFrameDamageRangeCount;
    private boolean mFrameSeen;
    private boolean mFrameFullDamage;
    private int mFrameViewTop;
    private int mFrameRenderTop;

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
        mCanvasScaleX = canvasScaleX(mFontWidth);
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
        mPaintedRowCount = 0;
        mFrameDamageRangeCount = 0;
        mFrameSeen = false;
        mFrameFullDamage = false;

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
        int commandCount;
        while (true) {
            commandCount = emulator.fillRenderCommands(mRenderCommands, renderStartRow,
                selectionY1, selectionY2, selectionX1, selectionX2, mBackgroundAlpha,
                mFontWidth, mFontLineSpacing, mFontAscent, columns, renderRows);
            if (commandCount >= 0) break;
            mRenderCommands = new int[-commandCount];
        }
        if (commandCount == 0) {
            canvas.restore();
            return;
        }
        final int[] commands = mRenderCommands;
        loadRenderText(commands, commandCount, emulator);

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

        while (cursor < commandCount) {
            final int op = commands[cursor++];
            if (op == OP_END) break;

            if (op == OP_FRAME_BEGIN) {
                /* frame id, view top, full damage, columns, rows, render top,
                 * render rows, damage range count; then OP_DAMAGE ranges. */
                if (cursor + 8 > commandCount) break;
                cursor++; /* frame id */
                mFrameViewTop = commands[cursor++];
                mFrameFullDamage = commands[cursor++] != 0;
                cursor += 2; /* terminal columns, rows */
                mFrameRenderTop = commands[cursor++];
                cursor++; /* render rows */
                final int damageCount = commands[cursor++];
                mFrameSeen = true;
                mFrameDamageRangeCount = 0;
                for (int i = 0; i < damageCount && cursor + 2 < commandCount; i++) {
                    final int damageOp = commands[cursor++];
                    final int first = commands[cursor++];
                    final int last = commands[cursor++];
                    if (damageOp == OP_DAMAGE) addFrameDamageRange(first, last);
                }
                /* A clamped/mismatched header means the native address space is
                 * not the one this frame is positioning rows in: draw the range
                 * instead of trusting a partial skip. */
                if (mFrameViewTop != topRow || mFrameRenderTop != renderStartRow) forceDraw = true;
                continue;
            }
            if (op == OP_FRAME_END) continue;
            if (op == OP_DAMAGE) {
                cursor += 2; /* stray range outside a frame header */
                continue;
            }
            if (op == OP_IMAGE) {
                if (cursor + 8 > commandCount) break;
                final int x = commands[cursor++];
                final int y = commands[cursor++];
                final int width = commands[cursor++];
                final int height = commands[cursor++];
                final int imageIdLo = commands[cursor++];
                final int imageIdHi = commands[cursor++];
                final long imageId = ((long) imageIdHi << 32)
                    | (imageIdLo & 0xFFFFFFFFL);
                final int revision = commands[cursor++];
                cursor++; /* opaque flag; Android always blends ARGB */
                drawSixel(canvas, emulator, imageId, revision, x, y, width, height,
                    mFontLineSpacingAndAscent);
                continue;
            }

            if (op == OP_ROW_BEGIN) {
                currentRow = commands[cursor++];
                rowTop = mFontLineSpacingAndAscent + (currentRow - topRow) * mFontLineSpacing;
                baseline = rowTop - mFontAscent;
                rowInWindow = currentRow >= renderStartRow && currentRow < renderEndRow;
                rowDrawn = rowInWindow && (dirtyRect == null || !pixelsValid || forceDraw
                    || isFrameRowDamaged(currentRow) || screen.isRowDirty(currentRow));

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
                if (rowDrawn) recordPaintedRow(currentRow);
                continue;
            }

            if (!rowDrawn) continue;

            switch (op) {
                case OP_FILL_RECT: {
                    int x = commands[cursor++];
                    int y = commands[cursor++];
                    int width = commands[cursor++];
                    int height = commands[cursor++];
                    int color = commands[cursor++];
                    float left = x * mCanvasScaleX;
                    float right = (x + width) * mCanvasScaleX;
                    canvas.drawRect(left, rowTop + y, right, rowTop + y + height,
                        backgroundPaint(color));
                    break;
                }
                case OP_GLYPH: {
                    int startChar = commands[cursor++];
                    int charCount = commands[cursor++];
                    int x = commands[cursor++];
                    int glyphBaseline = commands[cursor++];
                    int widthColumns = commands[cursor++];
                    int foreground = commands[cursor++];
                    int effects = commands[cursor++];
                    drawGlyph(canvas, startChar, charCount, x * mCanvasScaleX,
                        rowTop + glyphBaseline, widthColumns, foreground, effects);
                    break;
                }
                case OP_FILL_TRAPEZOID: {
                    int x1 = commands[cursor++];
                    int y1 = commands[cursor++];
                    int x2 = commands[cursor++];
                    int y2 = commands[cursor++];
                    int x3 = commands[cursor++];
                    int y3 = commands[cursor++];
                    int x4 = commands[cursor++];
                    int y4 = commands[cursor++];
                    int color = commands[cursor++];
                    mPath.reset();
                    mPath.moveTo(x1 * mCanvasScaleX, rowTop + y1);
                    mPath.lineTo(x2 * mCanvasScaleX, rowTop + y2);
                    mPath.lineTo(x3 * mCanvasScaleX, rowTop + y3);
                    mPath.lineTo(x4 * mCanvasScaleX, rowTop + y4);
                    mPath.close();
                    canvas.drawPath(mPath, backgroundPaint(color));
                    break;
                }
                default:
                    // Unknown opcode: stop rather than misinterpreting the rest of the stream.
                    cursor = commandCount;
                    break;
            }
        }

        if (rowPushed) canvas.restore();
        emulator.commitRenderRows(mPaintedRows, mPaintedRowCount);
        canvas.restore();
    }

    private void addFrameDamageRange(int first, int last) {
        if (last < first) return;
        final int needed = (mFrameDamageRangeCount + 1) * 2;
        if (needed > mFrameDamageRanges.length) {
            int[] grown = new int[Math.max(needed, mFrameDamageRanges.length * 2)];
            System.arraycopy(mFrameDamageRanges, 0, grown, 0, mFrameDamageRangeCount * 2);
            mFrameDamageRanges = grown;
        }
        mFrameDamageRanges[mFrameDamageRangeCount * 2] = first;
        mFrameDamageRanges[mFrameDamageRangeCount * 2 + 1] = last;
        mFrameDamageRangeCount++;
    }

    private boolean isFrameRowDamaged(int row) {
        if (!mFrameSeen) return false;
        if (mFrameFullDamage) return true;
        for (int i = 0; i < mFrameDamageRangeCount; i++) {
            if (row >= mFrameDamageRanges[i * 2] && row <= mFrameDamageRanges[i * 2 + 1]) {
                return true;
            }
        }
        return false;
    }

    private void recordPaintedRow(int row) {
        if (mPaintedRowCount == mPaintedRows.length) {
            int[] grown = new int[mPaintedRows.length * 2];
            System.arraycopy(mPaintedRows, 0, grown, 0, mPaintedRowCount);
            mPaintedRows = grown;
        }
        mPaintedRows[mPaintedRowCount++] = row;
    }

    /**
     * The native render payload carries its UTF-16 text after {@code OP_END}. Prefer that copy:
     * it avoids a second JNI call and a second int[] allocation. Fall back to the legacy
     * {@code nativeRenderText} entry point for an older native library.
     */
    private void loadRenderText(int[] commands, int limit, TerminalEmulator emulator) {
        if (commands != null) {
            int i = 0;
            while (i < limit) {
                final int op = commands[i++];
                if (op == OP_END) {
                    if (i + 1 < limit) {
                        final int count = commands[i];
                        if (count >= 0 && i + 1 + count <= limit) {
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
                    case OP_FILL_RECT:
                        i += 5;
                        break;
                    case OP_GLYPH:
                        i += 7;
                        break;
                    case OP_FILL_TRAPEZOID:
                        i += 9;
                        break;
                    case OP_IMAGE:
                        i += 8;
                        break;
                    case OP_FRAME_BEGIN: {
                        if (i + 7 >= limit) { i = limit; break; }
                        final int damageCount = commands[i + 7];
                        i += 8;
                        for (int d = 0; d < damageCount && i + 2 < limit; d++) i += 3;
                        break;
                    }
                    case OP_DAMAGE:
                        i += 2;
                        break;
                    case OP_FRAME_END:
                        break;
                    default:
                        i = limit;
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

    /**
     * Draw a sixel surface emitted by the native renderer. The native command
     * carries the surface's intrinsic pixel size; only the horizontal cell grid
     * needs the same fractional-font correction as the other Canvas commands.
     */
    private void drawSixel(Canvas canvas, TerminalEmulator emulator, long imageId,
                           int revision, int x, int y, int width, int height,
                           float rowTopBase) {
        if (width <= 0 || height <= 0 || (long) width * height > Integer.MAX_VALUE) return;

        SixelBitmap cached = mSixelBitmaps.get(imageId);
        if (cached != null && cached.revision == revision) {
            drawSixelBitmap(canvas, cached.bitmap, x, y, width, height, rowTopBase);
            return;
        }

        final int[] pixels = emulator.getSixelPixels(imageId, revision, width, height);
        if (pixels == null || pixels.length < width * height) {
            if (cached != null) {
                mSixelBitmaps.remove(imageId);
                cached.bitmap.recycle();
            }
            return;
        }

        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        } catch (RuntimeException e) {
            return;
        }

        if (cached != null) {
            mSixelBitmaps.remove(imageId);
            cached.bitmap.recycle();
        }
        if (mSixelBitmaps.size() >= MAX_CACHED_SIXEL_IMAGES) {
            final SixelBitmap oldest = mSixelBitmaps.valueAt(0);
            mSixelBitmaps.removeAt(0);
            if (oldest != null) oldest.bitmap.recycle();
        }
        mSixelBitmaps.put(imageId, new SixelBitmap(bitmap, revision));
        drawSixelBitmap(canvas, bitmap, x, y, width, height, rowTopBase);
    }

    private void drawSixelBitmap(Canvas canvas, Bitmap bitmap, int x, int y,
                                 int width, int height, float rowTopBase) {
        mImageSrcRect.set(0, 0, width, height);
        final float left = x * mCanvasScaleX;
        final float right = (x + width) * mCanvasScaleX;
        final float top = rowTopBase + y;
        mImageDstRect.set(left, top, right, top + height);
        canvas.drawBitmap(bitmap, mImageSrcRect, mImageDstRect, mImagePaint);
    }

    private void drawGlyph(Canvas canvas, int startChar, int charCount, float x,
                           float baseline, int widthColumns, int foreground, int effects) {
        if (charCount <= 0 || startChar < 0 || startChar + charCount > mTextUnitCount) return;
        final boolean bold = (effects & EFFECT_BOLD) != 0;
        final boolean italic = (effects & EFFECT_ITALIC) != 0;

        /* The native side coalesces adjacent upstream glyphs into one run and
         * carries the upstream cell span explicitly. That span is not always
         * the sum of codepoint widths (combining marks, ZWJ emoji, forced
         * grapheme widths), so do not guess it here. */
        if (widthColumns <= 0) widthColumns = 1;
        final float expectedWidth = widthColumns * mFontWidth;
        final float measured = (asciiFastPathAllowed(bold, italic, charCount, widthColumns)
            && isAsciiNarrow(startChar, charCount))
            ? expectedWidth
            : measureNeutral(startChar, charCount);

        applyTextStyle(foreground, bold, false, italic, false);
        drawRun(canvas, startChar, charCount, widthColumns, x, expectedWidth, measured,
            baseline, effects);
    }

    /**
     * Draw one run of cells, picking the per-cluster fallback whenever the run's measured
     * advance does not match its cell span.
     *
     * <p>Stage-5 glyph batching collapsed many upstream cells into one {@code OP_GLYPH} run.
     * Block-art wordmarks are exactly such a run: full blocks, half blocks and the spaces
     * between letters do not necessarily share the font's advance. Scaling the whole run to
     * its cell span squeezes the blocks away from their background fills and leaves a seam at
     * every cell boundary. Splitting into code-point clusters fits each cell independently
     * and keeps the art seamless.</p>
     */
    private void drawRun(Canvas canvas, int startChar, int charCount, int widthColumns,
                         float left, float expectedWidth, float measured, float baseline,
                         int effects) {
        if (runNeedsClusterFallback(widthColumns, expectedWidth, measured, mFontWidth)) {
            drawTextClusters(canvas, startChar, charCount, left, baseline, effects);
        } else {
            drawGlyphs(canvas, startChar, charCount, widthColumns, left, expectedWidth, measured,
                baseline, effects);
        }
    }

    /**
     * Whether a run must be split into per-code-point clusters before cell fitting.
     *
     * <p>Whole-run scaling is only safe when every glyph has the same advance. A mismatch can
     * otherwise mean the mixed run contains spaces or fallback glyphs with a different advance,
     * and squeezing the whole run shifts those glyphs relative to their cells. The common case
     * is a block-element wordmark with spaces between letters.</p>
     */
    static boolean runNeedsClusterFallback(int widthColumns, float expectedWidth, float measured,
                                           float fontWidth) {
        if (!(expectedWidth > 0f) || !(fontWidth > 0f)) return false;
        final float tolerance = Math.max(0.01f * fontWidth, 0.01f);
        if (Math.abs(measured - expectedWidth) <= tolerance) return false;
        return isMismatchScaleUsable(widthColumns, measured / fontWidth);
    }

    private void applyTextStyle(int foreground, boolean bold, boolean underline, boolean italic,
                                boolean strike) {
        final long key = foreColorKey(foreground);
        if (needsForeColorSet(foreground, mLastPaintForeColorKey)) {
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
    private void drawGlyphs(Canvas canvas, int startChar, int charCount, int widthColumns,
                            float left, float expectedWidth, float measured, float baseline,
                            int effects) {
        boolean scaled = false;
        float scale = 1f;
        if (expectedWidth > 0f && mismatch(measured, expectedWidth)
            && isMismatchScaleUsable(widthColumns, measured / mFontWidth)) {
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
            if (expected > 0f && mismatch(measured, expected)
                && isMismatchScaleUsable(width, measured / mFontWidth)) {
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

    /**
     * Scale from the upstream renderer's integer cell grid to the measured font grid.
     * Native code receives {@code font_width} and rounds it to a whole pixel per cell,
     * while Java positions columns at the exact fractional advance. Scaling the
     * pixel-space canvas commands keeps backgrounds, cursor shapes and glyph origins
     * aligned with {@link #mFontWidth} instead of accumulating a subpixel offset on
     * every column.
     */
    static float canvasScaleX(float fontWidth) {
        final int cellWidth = Math.max(1, Math.round(fontWidth));
        return fontWidth / cellWidth;
    }

    /**
     * Whether a run's width mismatch can be corrected with {@code canvas.scale()}.
     *
     * @param runWidthColumns width of the run's cells, in columns.
     * @param mes             the run's measured width in <em>cells</em>, i.e. already divided by
     *                        {@link #mFontWidth}. Zero for a run of zero-advance code points.
     */
    static boolean isMismatchScaleUsable(int runWidthColumns, float mes) {
        if (runWidthColumns <= 0 || !(mes > 0f)) return false;   // also rejects NaN
        final float scale = runWidthColumns / mes;
        return scale <= MAX_MISMATCH_SCALE && scale >= MIN_MISMATCH_SCALE;
    }

    /**
     * Unsigned key of an ARGB colour. Kept separate from the {@code -1} "unknown" sentinel so
     * opaque white ({@code 0xFFFFFFFF}) cannot be mistaken for a freshly reset memo.
     */
    static long foreColorKey(int foreground) {
        return foreground & 0xFFFFFFFFL;
    }

    static boolean needsForeColorSet(int foreground, long lastKey) {
        return foreColorKey(foreground) != lastKey;
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

    /**
     * Whether a full-width row damage rectangle was cut down by an ancestor before this frame.
     *
     * <p>Row damage is always invalidated across the whole width, but an ancestor (the session
     * pager translates its pages while a swipe settles, and its elastic spring can leave a page
     * a few pixels off-centre) may clip the canvas horizontally. Drawing such a frame updates
     * only the visible part of the dirty rows; treating it as complete would clear their dirty
     * bits and strand the clipped-off strips with the previous frame's pixels. Overlay damage
     * (scrollbar band, cursor cell) is intentionally narrower and must not trigger recovery.</p>
     */
    static boolean fullWidthDamageWasClipped(boolean fullClip, boolean pendingFullWidthDamage,
                                             int clipLeft, int clipRight, int viewWidth) {
        return !fullClip && pendingFullWidthDamage
            && (clipLeft > 0 || clipRight < viewWidth);
    }

    /**
     * The fast path is only valid for plain text: bold and italic may select a face with a
     * different advance, so those runs must still be measured and, if needed, cell-fitted.
     */
    static boolean asciiFastPathAllowed(boolean bold, boolean italic, int charCount,
                                        int widthColumns) {
        return !bold && !italic && charCount > 0 && charCount == widthColumns;
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
