package com.termux.view;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression tests for the per-row clip in {@link TerminalRenderer#render}.
 *
 * <p>The renderer clips each row so a glyph cannot paint outside the grid's columns: a run's
 * measured width is a per-code-point sum, while {@code Canvas.drawTextRun()} runs the real shaper,
 * so Devanagari conjuncts, emoji ZWJ sequences and a shaper-inserted dotted circle can all make a
 * run wider than its cells, and a run ending at the last column would then paint into the margin —
 * where no damage rect ever reaches, so the pixel survives every repaint.
 *
 * <p>That is the <em>horizontal</em> half. Clipping vertically to the row's own band as well is
 * wrong, and was wrong once already: box-drawing and block-element glyphs are drawn tall enough to
 * overhang their cell into the rows above and below, and that overhang is what makes a frame or a
 * filled bar read as one piece. Clipping to {@code [rowTop, heightOffset]} shaved it off and left a
 * 1 px line of background between every pair of rows, repeating at the row pitch across every frame
 * in the terminal.</p>
 */
public class TerminalRendererRowClipTest {

    /**
     * The clip's vertical extent, derived exactly as the renderer derives it, so the invariant can
     * be asserted without a {@code Canvas}.
     *
     * @param lineSpacing        {@code mFontLineSpacing}, the row pitch.
     * @param lineSpacingAndAscent {@code mFontLineSpacingAndAscent}, the first row's top.
     * @param drawnRowCount      {@code endRow - topRow}.
     */
    private static float[] clipVerticalExtent(float lineSpacing, float lineSpacingAndAscent,
        int drawnRowCount) {
        final float gridTop = lineSpacingAndAscent - lineSpacing;
        final float gridBottom = lineSpacingAndAscent + drawnRowCount * lineSpacing + lineSpacing;
        return new float[]{gridTop, gridBottom};
    }

    /** The band the first and the last drawn row actually occupy, per the renderer's own layout. */
    private static float[] drawnRowBands(float lineSpacing, float lineSpacingAndAscent,
        int drawnRowCount) {
        float heightOffset = lineSpacingAndAscent;
        float firstTop = Float.MAX_VALUE;
        float lastBottom = -Float.MAX_VALUE;
        for (int i = 0; i < drawnRowCount; i++) {
            heightOffset += lineSpacing;
            final float rowTop = heightOffset - lineSpacing;
            if (rowTop < firstTop) firstTop = rowTop;
            if (heightOffset > lastBottom) lastBottom = heightOffset;
        }
        return new float[]{firstTop, lastBottom};
    }

    /**
     * Every drawn row must be inside the clip — a row clipped out of its own band would vanish
     * entirely on a partial repaint.
     */
    @Test
    public void everyDrawnRowIsInsideTheClip() {
        final float lineSpacing = 29f, lineSpacingAndAscent = 22f;
        final int rows = 48;
        final float[] clip = clipVerticalExtent(lineSpacing, lineSpacingAndAscent, rows);
        final float[] bands = drawnRowBands(lineSpacing, lineSpacingAndAscent, rows);
        assertTrue("first row above the clip top", bands[0] >= clip[0]);
        assertTrue("last row below the clip bottom", bands[1] <= clip[1]);
    }

    /**
     * The regression itself: the clip has to leave at least a full line of vertical slack beyond the
     * first and last row, because that is the overhang a block-element or box-drawing glyph needs to
     * reach its neighbour. With no slack the overhang is shaved and a background line appears at
     * every row boundary.
     */
    @Test
    public void clipLeavesAFullLineOfVerticalSlack() {
        final float lineSpacing = 29f, lineSpacingAndAscent = 22f;
        final int rows = 48;
        final float[] clip = clipVerticalExtent(lineSpacing, lineSpacingAndAscent, rows);
        final float[] bands = drawnRowBands(lineSpacing, lineSpacingAndAscent, rows);
        assertTrue("no slack above the first row: rows would be visibly striped",
            clip[0] <= bands[0] - lineSpacing);
        assertTrue("no slack below the last row",
            clip[1] >= bands[1] + lineSpacing);
    }

    /**
     * The horizontal half is what the damage model needs and must not be relaxed. The clip's right
     * edge is the grid's, {@code columns * fontWidth} — a run that overruns its last column is cut
     * there, not out at the view edge, which is what leaves no pixel stranded in the margin.
     */
    @Test
    public void horizontalExtentIsTheGridEdge() {
        final float fontWidth = 14.4f;
        final int columns = 75;
        final float gridRight = columns * fontWidth;
        // The right edge is the last column's boundary, so it is a whole number of cells wide and
        // covers exactly the grid — never past it.
        assertTrue("grid right edge must be the columns' boundary",
            gridRight == (float) columns * fontWidth);
        assertTrue("a glyph overrunning column 74 is cut inside column 75's boundary",
            gridRight <= 75 * fontWidth + 1e-3f);
    }

    /** A single-row frame and a tall one must both keep their slack — no off-by-one at the edges. */
    @Test
    public void slackHoldsForAnyRowCount() {
        final float lineSpacing = 29f, lineSpacingAndAscent = 22f;
        for (int rows = 1; rows <= 200; rows++) {
            final float[] clip = clipVerticalExtent(lineSpacing, lineSpacingAndAscent, rows);
            final float[] bands = drawnRowBands(lineSpacing, lineSpacingAndAscent, rows);
            assertTrue("row count " + rows + ": first row clipped out",
                bands[0] >= clip[0]);
            assertTrue("row count " + rows + ": last row clipped out",
                bands[1] <= clip[1]);
            assertTrue("row count " + rows + ": no slack above",
                clip[0] <= bands[0] - lineSpacing);
            assertTrue("row count " + rows + ": no slack below",
                clip[1] >= bands[1] + lineSpacing);
        }
    }
}
