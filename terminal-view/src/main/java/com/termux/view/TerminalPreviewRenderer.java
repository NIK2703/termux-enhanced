package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRenderSource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders a terminal emulator into a bitmap for the session overview.
 *
 * <p>Card geometry and card painting are deliberately separate. {@link GridRaster} carries only
 * the exact {@code ceil(columns*advance) x ceil(rows*lineSpacing)} size used to lay out the card
 * and allocate its bitmap. The pixels are painted with the ordinary full-size renderer and one
 * uniform {@code canvas.scale}: glyphs are rasterised at the user's integer ppem with subpixel
 * positioning off, then downscaled as a whole. That is the pre-regression behaviour. Rendering
 * directly at a fractional text size with subpixel positioning on leaves hairline gaps between
 * neighbouring glyphs and runs, because each glyph edge lands at a partial device pixel.</p>
 *
 * <p>The scale is the same on both axes, so no glyph is stretched and no background band is
 * introduced. The bitmap is drawn 1:1 by the card's {@code ImageView}.</p>
 */
public final class TerminalPreviewRenderer {

    /** Upper bound on growing the preview font to fill a large slot. */
    private static final float MAX_SIZE_FACTOR = 2f;
    private static final float MIN_TEXT_SIZE_PX = 4f;
    private static final int RASTER_CACHE_SIZE = 8;
    /** One renderer per distinct font/geometry pair; preview refreshes reuse them. */
    private static final int RENDERER_CACHE_SIZE = 4;

    /**
     * Pixel format of every preview and flight raster, opaque or not.
     *
     * <p>RGB_565 halves the memory, but its 5-6-5 channel split rounds neutral greys towards
     * green: {@code #FAFAFA} is stored as {@code #F7FBF7}, because green gets six bits while red
     * and blue get five. The live terminal is ARGB_8888, so a card must be too, otherwise a light
     * terminal scheme visibly changes colour when the overview opens and the green tone
     * disappears again during the flight back.</p>
     */
    public static final Bitmap.Config BITMAP_CONFIG = Bitmap.Config.ARGB_8888;

    private TerminalPreviewRenderer() {}

    /**
     * The exact glyph grid a terminal session should carry for a given viewport.
     */
    public static final class ViewportGrid {
        public final int columns;
        public final int rows;
        /** Integer cell width reported to the pty, matching {@code TerminalView.updateSize()}. */
        public final int cellWidthPx;
        /** Line spacing reported to the pty, matching {@code TerminalView.updateSize()}. */
        public final int cellHeightPx;
        private final double aspect;

        private ViewportGrid(int columns, int rows, int cellWidthPx, int cellHeightPx,
                             double aspect) {
            this.columns = columns;
            this.rows = rows;
            this.cellWidthPx = cellWidthPx;
            this.cellHeightPx = cellHeightPx;
            this.aspect = aspect;
        }

        /** Width/height ratio of the glyph grid; always positive for a constructed grid. */
        public double aspect() {
            return aspect;
        }

        /** True when the emulator already carries exactly this grid, pty cell size included. */
        public boolean matches(@NonNull TerminalEmulator emulator) {
            return emulator.hasSize(columns, rows, cellWidthPx, cellHeightPx);
        }

    }

    /**
     * Exact grid geometry for one preview font, shared by every card in an overview.
     *
     * <p>{@link #cellWidth} is the font's natural advance at {@link #textSizePx}.
     * {@link #lineHeight} is not the font's raw spacing: it is the pitch that keeps this cell
     * width on the <em>live</em> grid's aspect ratio. The live terminal rounds its row pitch up
     * to a whole pixel ({@code ceil(getFontSpacing())}), while a raw spacing would describe a
     * slightly shorter cell. Using the live ratio here is what makes the card rectangle and the
     * flight snapshot share one aspect; otherwise {@link #render} letterboxes the grid inside the
     * card and the terminal jumps when the preview takes over from the flying snapshot.</p>
     *
     * <p>A smaller session reuses the same pitch, so its bitmap is exactly
     * {@code widthFor(itsColumns) x heightFor(itsRows)}.</p>
     */
    public static final class GridRaster {
        @Nullable public final Typeface typeface;
        public final float textSizePx;
        /** Natural advance of one monospace cell at {@link #textSizePx}. */
        public final float cellWidth;
        /** Row pitch at {@link #cellWidth} that preserves the live glyph grid's aspect. */
        public final float lineHeight;
        /** Natural ascent (negative) at {@link #textSizePx}. */
        public final float ascent;

        GridRaster(@Nullable Typeface typeface, float textSizePx, float cellWidth,
                   float lineHeight, float ascent) {
            this.typeface = typeface;
            this.textSizePx = textSizePx;
            this.cellWidth = cellWidth;
            this.lineHeight = lineHeight;
            this.ascent = ascent;
        }

        /** Exact bitmap width for a grid of {@code columns} cells. */
        public int widthFor(int columns) {
            return gridPixels(columns, cellWidth);
        }

        /** Exact bitmap height for a grid of {@code rows} cells. */
        public int heightFor(int rows) {
            return gridPixels(rows, lineHeight);
        }

        private static int gridPixels(int cells, float cellSize) {
            if (cells <= 0 || !(cellSize > 0f) || !Float.isFinite(cellSize)) return 0;
            final double value = (double) cells * cellSize;
            if (!(value > 0d) || Double.isInfinite(value)) return 0;
            final double ceiled = Math.ceil(value);
            return ceiled >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ceiled;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GridRaster)) return false;
            GridRaster other = (GridRaster) o;
            return typeface == other.typeface
                    && Float.compare(textSizePx, other.textSizePx) == 0
                    && Float.compare(cellWidth, other.cellWidth) == 0
                    && Float.compare(lineHeight, other.lineHeight) == 0
                    && Float.compare(ascent, other.ascent) == 0;
        }

        @Override
        public int hashCode() {
            int result = typeface == null ? 0 : System.identityHashCode(typeface);
            result = 31 * result + Float.floatToIntBits(textSizePx);
            result = 31 * result + Float.floatToIntBits(cellWidth);
            result = 31 * result + Float.floatToIntBits(lineHeight);
            result = 31 * result + Float.floatToIntBits(ascent);
            return result;
        }
    }

    /** Package-private seam for exercising the raster fit without a real Paint. */
    interface MetricsSource {
        @NonNull
        RasterMetrics measure(float textSizePx);
    }

    static final class RasterMetrics {
        final float advance;
        final float lineHeight;
        final float ascent;

        RasterMetrics(float advance, float lineHeight, float ascent) {
            this.advance = advance;
            this.lineHeight = lineHeight;
            this.ascent = ascent;
        }
    }

    private static final class PaintMetricsSource implements MetricsSource {
        private final Paint mPaint = new Paint();

        PaintMetricsSource(@NonNull Typeface typeface) {
            mPaint.setTypeface(typeface);
            mPaint.setAntiAlias(true);
            mPaint.setSubpixelText(true);
            mPaint.setTextScaleX(1f);
        }

        @NonNull
        @Override
        public RasterMetrics measure(float textSizePx) {
            mPaint.setTextSize(textSizePx);
            return new RasterMetrics(
                    mPaint.measureText("X"),
                    mPaint.getFontSpacing(),
                    mPaint.ascent());
        }
    }

    @Nullable
    public static ViewportGrid viewportGrid(int viewWidth, int viewHeight,
                                            int textSizePx, @NonNull Typeface typeface) {
        if (viewWidth <= 0 || viewHeight <= 0) return null;
        final TerminalRenderer.Metrics metrics = TerminalRenderer.metrics(textSizePx, typeface);
        if (metrics == null) return null;
        return fromViewport(viewWidth, viewHeight, metrics.fontWidth,
                metrics.lineSpacing, metrics.lineSpacingAndAscent);
    }

    @Nullable
    public static ViewportGrid emulatorGrid(@NonNull TerminalRenderSource source,
                                            int textSizePx, @NonNull Typeface typeface) {
        if (source.getColumns() <= 0 || source.getRows() <= 0) return null;
        final TerminalRenderer.Metrics metrics = TerminalRenderer.metrics(textSizePx, typeface);
        if (metrics == null) return null;
        return fromGrid(source.getColumns(), source.getRows(),
                metrics.fontWidth, metrics.lineSpacing);
    }

    @Nullable
    static ViewportGrid fromViewport(int viewWidth, int viewHeight,
                                     float fontWidth, int fontLineSpacing,
                                     int fontLineSpacingAndAscent) {
        if (viewWidth <= 0 || viewHeight <= 0 || !(fontWidth > 0f)
                || !Float.isFinite(fontWidth) || fontLineSpacing <= 0) {
            return null;
        }
        final int columns = Math.max(TerminalGridGeometry.MIN_COLUMNS, (int) (viewWidth / fontWidth));
        final int rows = Math.max(TerminalGridGeometry.MIN_ROWS,
                (int) ((viewHeight - fontLineSpacingAndAscent) / fontLineSpacing));
        return fromGrid(columns, rows, fontWidth, fontLineSpacing);
    }

    @Nullable
    private static ViewportGrid fromGrid(int columns, int rows,
                                         float fontWidth, int fontLineSpacing) {
        if (columns <= 0 || rows <= 0 || !(fontWidth > 0f)
                || !Float.isFinite(fontWidth) || fontLineSpacing <= 0) return null;
        final float gridWidth = columns * fontWidth;
        final float gridHeight = rows * fontLineSpacing;
        if (gridWidth <= 0f || gridHeight <= 0f) return null;
        return new ViewportGrid(columns, rows, (int) fontWidth, fontLineSpacing,
                (double) gridWidth / gridHeight);
    }

    /**
     * Fit the reference grid into a card slot at natural font metrics.
     *
     * <p>This is a direct estimate plus a fixed number of proportional corrections, not a search
     * over a text-size range. The first estimate scales the preferred font uniformly into the
     * slot; the corrections absorb hinting, which can make the measured advance non-linear in
     * text size. Every returned raster is verified against its exact
     * {@code ceil(columns*advance) x ceil(rows*lineHeight)} bitmap size.</p>
     *
     * <p>The row pitch is derived from the live grid, not from the fitted font's raw spacing:
     * the live renderer uses {@code ceil(getFontSpacing())}, so a card built from the raw spacing
     * would be slightly wider than the terminal it animates into. {@code referenceAdvance} and
     * {@code referenceLineSpacing} are the metrics the live {@link TerminalRenderer} uses at
     * {@code preferredTextSizePx}; their ratio fixes the cell aspect for every fitted size.</p>
     *
     * @return the raster, or null when the inputs or the minimum font size are unusable
     */
    @Nullable
    static GridRaster fitGridRaster(int referenceColumns, int referenceRows,
                                    int preferredTextSizePx, @Nullable Typeface typeface,
                                    int maxWidth, int maxHeight, @NonNull MetricsSource source,
                                    float referenceAdvance, int referenceLineSpacing) {
        if (referenceColumns <= 0 || referenceRows <= 0 || preferredTextSizePx <= 0
                || maxWidth <= 0 || maxHeight <= 0
                || !(referenceAdvance > 0f) || !Float.isFinite(referenceAdvance)
                || referenceLineSpacing <= 0) {
            return null;
        }
        final RasterMetrics preferred = source.measure(preferredTextSizePx);
        if (!isUsable(preferred)) return null;
        // Height of one cell per unit of advance. This is the live renderer's integer row pitch
        // over its float advance; every card cell must keep exactly this ratio.
        final double rowPitchRatio = (double) referenceLineSpacing / referenceAdvance;

        double scale = Math.min(
                (double) maxWidth / ((double) referenceColumns * preferred.advance),
                (double) maxHeight
                        / ((double) referenceRows * preferred.advance * rowPitchRatio));
        if (!(scale > 0d) || !Double.isFinite(scale)) return null;
        scale = Math.min(scale, MAX_SIZE_FACTOR);

        for (int attempt = 0; attempt < 4; attempt++) {
            final float textSize = (float) (preferredTextSizePx * scale);
            if (!(textSize >= MIN_TEXT_SIZE_PX) || !Float.isFinite(textSize)) return null;
            final RasterMetrics metrics = source.measure(textSize);
            if (!isUsable(metrics)) return null;
            final float lineHeight = (float) (metrics.advance * rowPitchRatio);
            if (!(lineHeight > 0f) || !Float.isFinite(lineHeight)) return null;
            final GridRaster raster = new GridRaster(typeface, textSize,
                    metrics.advance, lineHeight, metrics.ascent);
            if (raster.widthFor(referenceColumns) <= maxWidth
                    && raster.heightFor(referenceRows) <= maxHeight) {
                return raster;
            }
            // The correction uses the un-rounded grid size: ceil(x) fits an integer slot iff x
            // fits it. A 0.1% margin absorbs float rounding and stops a hinted step from
            // approaching the limit asymptotically without ever crossing it.
            final double correction = Math.min(
                    (double) maxWidth / ((double) referenceColumns * metrics.advance),
                    (double) maxHeight
                            / ((double) referenceRows * metrics.advance * rowPitchRatio));
            if (!(correction > 0d) || correction >= 1d) return null;
            scale *= correction * 0.999d;
        }
        return null;
    }

    private static boolean isUsable(@Nullable RasterMetrics metrics) {
        return metrics != null
                && metrics.advance > 0f && Float.isFinite(metrics.advance)
                && metrics.lineHeight > 0f && Float.isFinite(metrics.lineHeight)
                && Float.isFinite(metrics.ascent);
    }

    private static final class RasterKey {
        final Typeface typeface;
        final int preferredTextSize, columns, rows, maxWidth, maxHeight;

        RasterKey(Typeface typeface, int preferredTextSize, int columns, int rows,
                  int maxWidth, int maxHeight) {
            this.typeface = typeface;
            this.preferredTextSize = preferredTextSize;
            this.columns = columns;
            this.rows = rows;
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RasterKey)) return false;
            RasterKey k = (RasterKey) o;
            return typeface == k.typeface && preferredTextSize == k.preferredTextSize
                    && columns == k.columns && rows == k.rows
                    && maxWidth == k.maxWidth && maxHeight == k.maxHeight;
        }

        @Override
        public int hashCode() {
            int h = typeface == null ? 0 : System.identityHashCode(typeface);
            h = 31 * h + preferredTextSize;
            h = 31 * h + columns;
            h = 31 * h + rows;
            h = 31 * h + maxWidth;
            return 31 * h + maxHeight;
        }
    }

    private static final Map<RasterKey, GridRaster> sRasterCache =
            new LinkedHashMap<RasterKey, GridRaster>(RASTER_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<RasterKey, GridRaster> e) {
                    return size() > RASTER_CACHE_SIZE;
                }
            };

    /** Font and integer ppem that fully determine a reusable full-size renderer. */
    private static final class RendererKey {
        final int textSizePx;
        final Typeface typeface;

        RendererKey(int textSizePx, Typeface typeface) {
            this.textSizePx = textSizePx;
            this.typeface = typeface;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RendererKey)) return false;
            RendererKey k = (RendererKey) o;
            return textSizePx == k.textSizePx && typeface == k.typeface;
        }

        @Override
        public int hashCode() {
            return 31 * (typeface == null ? 0 : System.identityHashCode(typeface)) + textSizePx;
        }
    }

    /**
     * One renderer cache per thread. {@link TerminalRenderer} is stateful, so the UI renderer and
     * the background overview rasterizer must never share an instance.
     */
    private static final ThreadLocal<Map<RendererKey, TerminalRenderer>> sRendererCache =
            new ThreadLocal<Map<RendererKey, TerminalRenderer>>() {
                @Override
                protected Map<RendererKey, TerminalRenderer> initialValue() {
                    return new LinkedHashMap<RendererKey, TerminalRenderer>(
                            RENDERER_CACHE_SIZE, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<RendererKey, TerminalRenderer> e) {
                            return size() > RENDERER_CACHE_SIZE;
                        }
                    };
                }
            };

    /**
     * Get a full-size renderer for this font on the calling thread. {@link TerminalRenderer#render}
     * resets its transient paint state on entry, so instances are safe to reuse across consecutive
     * preview passes on that thread. The implicit constructor keeps subpixel text off, which is
     * what avoids hairline gaps in the downscaled preview.
     */
    @NonNull
    private static TerminalRenderer rendererFor(int textSizePx, @NonNull Typeface typeface) {
        final RendererKey key = new RendererKey(textSizePx, typeface);
        final Map<RendererKey, TerminalRenderer> cache = sRendererCache.get();
        final TerminalRenderer cached = cache.get(key);
        if (cached != null) return cached;
        final TerminalRenderer renderer = new TerminalRenderer(textSizePx, typeface);
        cache.put(key, renderer);
        return renderer;
    }

    /**
     * Build the natural-metric raster for a reference grid and the largest card slot.
     *
     * <p>Called on layout changes only. The result is cached by its inputs; the fit is a direct
     * estimate with a constant number of corrections, never a search over a text-size range.</p>
     */
    @Nullable
    public static GridRaster fitGridRaster(int columns, int rows,
                                           int maxCardWidth, int maxCardHeight,
                                           int preferredTextSizePx,
                                           @NonNull Typeface typeface) {
        return fitGridRasterLocked(columns, rows, maxCardWidth, maxCardHeight,
                preferredTextSizePx, typeface);
    }

    @Nullable
    private static GridRaster fitGridRasterLocked(int columns, int rows,
                                                  int maxCardWidth, int maxCardHeight,
                                                  int preferredTextSizePx,
                                                  @NonNull Typeface typeface) {
        if (columns <= 0 || rows <= 0 || maxCardWidth <= 0 || maxCardHeight <= 0
                || preferredTextSizePx <= 0) {
            return null;
        }
        final RasterKey key = new RasterKey(typeface, preferredTextSizePx,
                columns, rows, maxCardWidth, maxCardHeight);
        synchronized (sRasterCache) {
            final GridRaster cached = sRasterCache.get(key);
            if (cached != null) return cached;
        }
        // Same metrics as the live TerminalView: the card's cell aspect must be the terminal's
        // ceil-sized row pitch over its float advance, not the raw font spacing.
        final TerminalRenderer.Metrics reference =
                TerminalRenderer.metrics(preferredTextSizePx, typeface);
        final GridRaster raster = fitGridRaster(columns, rows, preferredTextSizePx,
                typeface, maxCardWidth, maxCardHeight, new PaintMetricsSource(typeface),
                reference.fontWidth, reference.lineSpacing);
        if (raster != null) {
            synchronized (sRasterCache) {
                sRasterCache.put(key, raster);
            }
        }
        return raster;
    }

    /**
     * Paint an emulator into a thumbnail bitmap with one uniform scale.
     *
     * <p>This is the only preview rasterisation path, used for both cards and flight snapshots.
     * The bitmap is normally the exact size from {@link GridRaster}; because the grid aspect and
     * the raster aspect are the same, the uniform scale fills it without stretching a single axis.
     * Painting at the user's integer ppem and scaling the finished raster keeps glyph edges clean:
     * a fractional text size with subpixel positioning leaves hairline gaps between cells.</p>
     */
    public static void render(@NonNull TerminalRenderSource source, @NonNull Bitmap target,
                              int textSizePx, @NonNull Typeface typeface, int topRow,
                              int backgroundTransparencyPercent) {
        renderLocked(source, target, textSizePx, typeface, topRow,
                backgroundTransparencyPercent);
    }

    private static void renderLocked(@NonNull TerminalRenderSource source, @NonNull Bitmap target,
                                     int textSizePx, @NonNull Typeface typeface, int topRow,
                                     int backgroundTransparencyPercent) {
        if (target.isRecycled() || target.getWidth() <= 0 || target.getHeight() <= 0) return;
        if (source.getColumns() <= 0 || source.getRows() <= 0) return;
        final TerminalRenderer renderer = rendererFor(textSizePx, typeface);
        renderer.setBackgroundTransparencyPercent(backgroundTransparencyPercent);
        final float gridWidth = source.getColumns() * renderer.mFontWidth;
        final float gridHeight = source.getRows() * renderer.mFontLineSpacing;
        if (gridWidth <= 0f || gridHeight <= 0f) return;
        final float scale = Math.min(target.getWidth() / gridWidth,
                target.getHeight() / gridHeight);
        if (!(scale > 0f) || !Float.isFinite(scale)) return;

        final int clampedTopRow = Math.min(0, Math.max(topRow,
                -source.getActiveTranscriptRows()));
        final Canvas canvas = new Canvas(target);
        canvas.scale(scale, scale);
        final float xOffset = (target.getWidth() / scale - gridWidth) / 2f;
        final float yOffset = (target.getHeight() / scale - gridHeight) / 2f
                - renderer.mFontLineSpacingAndAscent;
        renderer.render(source, canvas, clampedTopRow, -1, -1, -1, -1,
                xOffset, yOffset, Math.max(1, (int) Math.ceil(target.getWidth() / scale)),
                null, false);
    }

    /**
     * Flight snapshots use the same uniform path as cards. Keeping the name makes the transition
     * call sites explicit: the flying grid must fill its bitmap so the scaled glyph pitch matches
     * the card's pitch at progress zero.
     */
    public static void renderForFlight(@NonNull TerminalRenderSource source, @NonNull Bitmap target,
                                       int textSizePx, @NonNull Typeface typeface, int topRow,
                                       int backgroundTransparencyPercent) {
        render(source, target, textSizePx, typeface, topRow, backgroundTransparencyPercent);
    }

}
