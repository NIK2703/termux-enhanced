package com.termux.shared.termux.extrakeys;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The 16 ANSI colors ({@code color0}..{@code color15}) of a terminal color scheme, drawn as a
 * two-row by eight-column table — the "little palette swatch" shown opposite every entry of the
 * color-scheme picker.
 *
 * <p>Rows are the ANSI split: the top row is the dim half (0-7), the bottom row the bright half
 * (8-15), columns are black/red/green/yellow/blue/magenta/cyan/white.
 *
 * <p>Why a custom {@link View} and not sixteen child views: the swatch is bound from
 * {@code ListAdapter#getView} on every scroll, and sixteen nested {@code View}s would mean sixteen
 * measure/layout/draw passes per row for something that is, in the end, sixteen filled rectangles.
 * Drawing them directly costs one canvas pass and no view objects at all.
 *
 * <p>The cells are drawn edge to edge from the same float grid, so neighbouring cells share their
 * boundary exactly and no seams can appear between them; only the outer frame is inset by half a
 * stroke so it stays inside the view's bounds.
 */
public class ColorSchemePaletteView extends View {

    /** ANSI colors per row: black, red, green, yellow, blue, magenta, cyan, white. */
    public static final int COLUMNS = 8;
    /** Dim half on top, bright half below. */
    public static final int ROWS = 2;
    /** {@code color0}..{@code color15}. */
    public static final int CELL_COUNT = COLUMNS * ROWS;

    /** Cell size used when the view is measured with {@code wrap_content}. */
    private static final float CELL_WIDTH_DP = 12f;
    private static final float CELL_HEIGHT_DP = 11f;

    private final int[] mColors = new int[CELL_COUNT];
    private final Paint mCellPaint = new Paint();
    private final Paint mFramePaint = new Paint();

    private final float mDefaultWidth;
    private final float mDefaultHeight;

    private int mFrameColor;
    private boolean mHasColors;

    public ColorSchemePaletteView(Context context) {
        this(context, null);
    }

    public ColorSchemePaletteView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ColorSchemePaletteView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        final float density = getResources().getDisplayMetrics().density;
        mDefaultWidth = CELL_WIDTH_DP * COLUMNS * density;
        mDefaultHeight = CELL_HEIGHT_DP * ROWS * density;

        // No anti-aliasing on the fills: half-covered edge pixels would show up as the background
        // bleeding through the seam between two cells.
        mCellPaint.setStyle(Paint.Style.FILL);
        mCellPaint.setAntiAlias(false);

        mFramePaint.setStyle(Paint.Style.STROKE);
        mFramePaint.setAntiAlias(false);
        mFramePaint.setStrokeWidth(Math.max(1f, density));
    }

    /**
     * Set the colors to draw, indexed by ANSI color number.
     *
     * @param colors at least {@link #CELL_COUNT} colors; extra entries are ignored.
     */
    public void setPalette(@Nullable int[] colors) {
        mHasColors = colors != null && colors.length >= CELL_COUNT;
        if (mHasColors) {
            System.arraycopy(colors, 0, mColors, 0, CELL_COUNT);
        }
        invalidate();
    }

    /**
     * Set the color of the one-pixel frame around the table. It is meant to be a blend of the
     * scheme's own foreground and background, so the swatch reads as a table on any background
     * without importing a color the scheme does not contain.
     */
    public void setFrameColor(int color) {
        mFrameColor = color;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(
                resolveSize(Math.round(mDefaultWidth), widthMeasureSpec),
                resolveSize(Math.round(mDefaultHeight), heightMeasureSpec));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (!mHasColors) return;

        final int width = getWidth();
        final int height = getHeight();
        if (width <= 0 || height <= 0) return;

        final float cellWidth = (float) width / COLUMNS;
        final float cellHeight = (float) height / ROWS;

        for (int row = 0; row < ROWS; row++) {
            final float top = row * cellHeight;
            final float bottom = (row == ROWS - 1) ? height : top + cellHeight;
            for (int column = 0; column < COLUMNS; column++) {
                final float left = column * cellWidth;
                final float right = (column == COLUMNS - 1) ? width : left + cellWidth;
                mCellPaint.setColor(mColors[row * COLUMNS + column]);
                canvas.drawRect(left, top, right, bottom, mCellPaint);
            }
        }

        if (Color.alpha(mFrameColor) != 0) {
            final float inset = mFramePaint.getStrokeWidth() / 2f;
            mFramePaint.setColor(mFrameColor);
            canvas.drawRect(inset, inset, width - inset, height - inset, mFramePaint);
        }
    }
}
