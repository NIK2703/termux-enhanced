package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalEmulator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders a terminal emulator into a bitmap for the session overview.
 *
 * <p>The card is sized from an integer cell pitch: {@code cellWidth} and {@code cellHeight} are
 * whole pixels, and the bitmap is exactly {@code columns*cellWidth} by
 * {@code rows*cellHeight}. There is no canvas scale, so every glyph and background boundary lands
 * on a device pixel and no background shows through between rows or columns.</p>
 */
public final class TerminalPreviewRenderer {

    /** Same floor as {@link TerminalView#updateSize()} uses for degenerate viewports. */
    /** Smallest cell a preview may use; below this the grid is unreadable. */
    private static final int MIN_CELL_PX = 4;
    /** Upper bound on growing the preview font to fill a large slot. */
    private static final float MAX_SIZE_FACTOR = 2f;
    private static final float MIN_TEXT_SIZE_PX = 4f;
    private static final int GEOMETRY_CACHE_SIZE = 4;
    /** One renderer per distinct font/geometry pair; preview refreshes reuse them. */
    private static final int RENDERER_CACHE_SIZE = 4;

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
     * Integer geometry of one preview raster, shared by every card in an overview.
     *
     * <p>Cell sizes are whole pixels. A smaller session uses the same pitch, so its bitmap is
     * exactly {@code widthFor(itsColumns) x heightFor(itsRows)} and still needs no scale.</p>
     */
    public static final class PreviewCellGeometry {
        public final int columns;
        public final int rows;
        public final int cellWidth;
        public final int cellHeight;
        public final float textSizePx;
        public final int ascent;
        public final Typeface typeface;

        PreviewCellGeometry(int columns, int rows, int cellWidth, int cellHeight,
                            float textSizePx, int ascent, Typeface typeface) {
            this.columns = columns;
            this.rows = rows;
            this.cellWidth = cellWidth;
            this.cellHeight = cellHeight;
            this.textSizePx = textSizePx;
            this.ascent = ascent;
            this.typeface = typeface;
        }

        public int widthFor(int cellColumns) {
            return cellColumns * cellWidth;
        }

        public int heightFor(int cellRows) {
            return cellRows * cellHeight;
        }

        public int cardWidth() {
            return widthFor(columns);
        }

        public int cardHeight() {
            return heightFor(rows);
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
    public static ViewportGrid emulatorGrid(@NonNull TerminalEmulator emulator,
                                            int textSizePx, @NonNull Typeface typeface) {
        if (emulator.mColumns <= 0 || emulator.mRows <= 0) return null;
        final TerminalRenderer.Metrics metrics = TerminalRenderer.metrics(textSizePx, typeface);
        if (metrics == null) return null;
        return fromGrid(emulator.mColumns, emulator.mRows,
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
     * Determine the preview cell size in one pass: one {@code min}, two floors and no search.
     *
     * <p>Both cell sizes come from the same scale {@code k}, so the font keeps its proportions.
     * Flooring guarantees the grid fits the slot on both axes.</p>
     *
     * @return packed {@code {cellWidth, cellHeight}}, or null for unusable inputs
     */
    @Nullable
    static int[] computeCells(int columns, int rows, int maxWidth, int maxHeight,
                              float naturalAdvance, float naturalLineHeight) {
        if (columns <= 0 || rows <= 0 || maxWidth <= 0 || maxHeight <= 0
                || !(naturalAdvance > 0f) || !(naturalLineHeight > 0f)
                || !Float.isFinite(naturalAdvance) || !Float.isFinite(naturalLineHeight)) {
            return null;
        }
        final double gridWidth = (double) columns * naturalAdvance;
        final double gridHeight = (double) rows * naturalLineHeight;
        final double scale = Math.min(maxWidth / gridWidth, maxHeight / gridHeight);
        if (!(scale > 0d) || Double.isNaN(scale) || Double.isInfinite(scale)) return null;
        final double bounded = Math.min(scale, (double) MAX_SIZE_FACTOR);
        final int cellWidth = Math.max(MIN_CELL_PX, (int) Math.floor(naturalAdvance * bounded));
        final int cellHeight = Math.max(MIN_CELL_PX, (int) Math.floor(naturalLineHeight * bounded));
        if (columns * cellWidth > maxWidth || rows * cellHeight > maxHeight) return null;
        return new int[]{cellWidth, cellHeight};
    }

    private static final class GeometryKey {
        final Typeface typeface;
        final int preferredTextSize, columns, rows, maxWidth, maxHeight;

        GeometryKey(Typeface typeface, int preferredTextSize, int columns, int rows,
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
            if (!(o instanceof GeometryKey)) return false;
            GeometryKey k = (GeometryKey) o;
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

    private static final Map<GeometryKey, PreviewCellGeometry> sGeometryCache =
            new LinkedHashMap<GeometryKey, PreviewCellGeometry>(GEOMETRY_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<GeometryKey, PreviewCellGeometry> e) {
                    return size() > GEOMETRY_CACHE_SIZE;
                }
            };

    /** Font and cell geometry that fully determines a reusable {@link TerminalRenderer}. */
    private static final class RendererKey {
        final float textSize;
        final Typeface typeface;
        final float cellWidth, lineSpacing, ascent;
        final boolean explicitGrid;

        RendererKey(float textSize, Typeface typeface, float cellWidth,
                    float lineSpacing, float ascent, boolean explicitGrid) {
            this.textSize = textSize;
            this.typeface = typeface;
            this.cellWidth = cellWidth;
            this.lineSpacing = lineSpacing;
            this.ascent = ascent;
            this.explicitGrid = explicitGrid;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RendererKey)) return false;
            RendererKey k = (RendererKey) o;
            return explicitGrid == k.explicitGrid && typeface == k.typeface
                    && Float.floatToIntBits(textSize) == Float.floatToIntBits(k.textSize)
                    && Float.floatToIntBits(cellWidth) == Float.floatToIntBits(k.cellWidth)
                    && Float.floatToIntBits(lineSpacing) == Float.floatToIntBits(k.lineSpacing)
                    && Float.floatToIntBits(ascent) == Float.floatToIntBits(k.ascent);
        }

        @Override
        public int hashCode() {
            int h = typeface == null ? 0 : System.identityHashCode(typeface);
            h = 31 * h + Float.floatToIntBits(textSize);
            h = 31 * h + Float.floatToIntBits(cellWidth);
            h = 31 * h + Float.floatToIntBits(lineSpacing);
            h = 31 * h + Float.floatToIntBits(ascent);
            return 31 * h + (explicitGrid ? 1 : 0);
        }
    }

    private static final Map<RendererKey, TerminalRenderer> sRendererCache =
            new LinkedHashMap<RendererKey, TerminalRenderer>(RENDERER_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<RendererKey, TerminalRenderer> e) {
                    return size() > RENDERER_CACHE_SIZE;
                }
            };

    /**
     * Get a renderer for this font/geometry. {@link TerminalRenderer#render} resets its transient
     * paint state on entry, so instances are safe to reuse across consecutive preview passes.
     */
    @NonNull
    private static TerminalRenderer rendererFor(float textSize, @NonNull Typeface typeface,
                                                float cellWidth, float lineSpacing,
                                                float ascent, boolean explicitGrid) {
        final RendererKey key = new RendererKey(textSize, typeface,
                cellWidth, lineSpacing, ascent, explicitGrid);
        synchronized (sRendererCache) {
            final TerminalRenderer cached = sRendererCache.get(key);
            if (cached != null) return cached;
        }
        final TerminalRenderer renderer = explicitGrid
                ? new TerminalRenderer(textSize, typeface, cellWidth, lineSpacing, ascent)
                : new TerminalRenderer(Math.round(textSize), typeface);
        synchronized (sRendererCache) {
            sRendererCache.put(key, renderer);
        }
        return renderer;
    }

    /**
     * Build the integer geometry for a reference grid and the largest available card slot.
     *
     * <p>Called on layout changes only. The result is cached by its inputs; there is no fitting
     * loop, binary search or retry.</p>
     */
    @Nullable
    public static PreviewCellGeometry cellGeometry(int columns, int rows,
                                                   int maxCardWidth, int maxCardHeight,
                                                   int preferredTextSizePx,
                                                   @NonNull Typeface typeface) {
        final GeometryKey key = new GeometryKey(typeface, preferredTextSizePx,
                columns, rows, maxCardWidth, maxCardHeight);
        synchronized (sGeometryCache) {
            final PreviewCellGeometry cached = sGeometryCache.get(key);
            if (cached != null) return cached;
        }
        final TerminalRenderer.Metrics metrics =
                TerminalRenderer.metrics(preferredTextSizePx, typeface);
        if (metrics == null) return null;
        final int[] cells = computeCells(columns, rows, maxCardWidth, maxCardHeight,
                metrics.fontWidth, metrics.lineSpacing);
        if (cells == null) return null;
        final PreviewCellGeometry geometry = geometryForCells(columns, rows,
                cells[0], cells[1], preferredTextSizePx, typeface, metrics);
        if (geometry != null) {
            synchronized (sGeometryCache) {
                sGeometryCache.put(key, geometry);
            }
        }
        return geometry;
    }

    /** Build geometry for an explicitly chosen integer pitch. */
    @Nullable
    private static PreviewCellGeometry geometryForCells(int columns, int rows,
                                                        int cellWidth, int cellHeight,
                                                        int preferredTextSizePx,
                                                        @NonNull Typeface typeface,
                                                        @NonNull TerminalRenderer.Metrics metrics) {
        if (cellWidth <= 0 || cellHeight <= 0) return null;
        final double factor = Math.min((double) cellWidth / metrics.fontWidth,
                (double) cellHeight / metrics.lineSpacing);
        final float textSize = (float) Math.max(MIN_TEXT_SIZE_PX,
                Math.min(preferredTextSizePx * MAX_SIZE_FACTOR, preferredTextSizePx * factor));
        final Paint paint = new Paint();
        paint.setTypeface(typeface);
        paint.setAntiAlias(true);
        paint.setSubpixelText(true);
        paint.setTextSize(textSize);
        if (!(paint.measureText("X") > 0f)) return null;
        final int ascent = (int) Math.ceil(paint.ascent());
        return new PreviewCellGeometry(columns, rows, cellWidth, cellHeight,
                textSize, ascent, typeface);
    }

    /** Paint an emulator into a bitmap whose size matches {@code geometry} exactly. */
    public static void render(@NonNull TerminalEmulator emulator, @NonNull Bitmap target,
                              @NonNull PreviewCellGeometry geometry, int topRow,
                              int backgroundTransparencyPercent) {
        if (target.isRecycled() || target.getWidth() <= 0 || target.getHeight() <= 0) return;
        if (emulator.mColumns <= 0 || emulator.mRows <= 0) return;
        if (target.getWidth() != geometry.widthFor(emulator.mColumns)
                || target.getHeight() != geometry.heightFor(emulator.mRows)) return;

        final TerminalRenderer renderer = rendererFor(geometry.textSizePx, geometry.typeface,
                geometry.cellWidth, geometry.cellHeight, geometry.ascent, true);
        renderer.setBackgroundTransparencyPercent(backgroundTransparencyPercent);

        final int clampedTopRow = Math.min(0, Math.max(topRow,
                -emulator.getScreen().getActiveTranscriptRows()));
        final Canvas canvas = new Canvas(target);
        renderer.render(emulator, canvas, clampedTopRow, -1, -1, -1, -1,
                0f, -geometry.cellHeight - geometry.ascent, target.getWidth(), null, false);
    }

    /**
     * Render a snapshot for the closing flight rather than for a card.
     *
     * <p>Unlike a card, the flying image is scaled by {@code TopCropImageView} from the full
     * terminal width down to the card width. Its grid must therefore fill the target bitmap's
     * width: only then does the scaled glyph pitch equal the card's integer cell pitch, so the
     * hand-off at progress zero is invisible. Card previews must use {@link #render} instead.</p>
     */
    public static void renderForFlight(@NonNull TerminalEmulator emulator, @NonNull Bitmap target,
                                       int textSizePx, @NonNull Typeface typeface, int topRow,
                                       int backgroundTransparencyPercent) {
        if (target.isRecycled() || target.getWidth() <= 0 || target.getHeight() <= 0) return;
        if (emulator.mColumns <= 0 || emulator.mRows <= 0) return;
        final TerminalRenderer renderer = rendererFor(textSizePx, typeface, 0f, 0f, 0f, false);
        renderer.setBackgroundTransparencyPercent(backgroundTransparencyPercent);
        final float gridWidth = emulator.mColumns * renderer.mFontWidth;
        final float gridHeight = emulator.mRows * renderer.mFontLineSpacing;
        if (gridWidth <= 0f || gridHeight <= 0f) return;
        final float scale = Math.min(target.getWidth() / gridWidth,
                target.getHeight() / gridHeight);
        if (!(scale > 0f) || !Float.isFinite(scale)) return;

        final int clampedTopRow = Math.min(0, Math.max(topRow,
                -emulator.getScreen().getActiveTranscriptRows()));
        final Canvas canvas = new Canvas(target);
        canvas.scale(scale, scale);
        final float xOffset = (target.getWidth() / scale - gridWidth) / 2f;
        final float yOffset = (target.getHeight() / scale - gridHeight) / 2f
                - renderer.mFontLineSpacingAndAscent;
        renderer.render(emulator, canvas, clampedTopRow, -1, -1, -1, -1,
                xOffset, yOffset, Math.max(1, (int) Math.ceil(target.getWidth() / scale)),
                null, false);
    }

}
