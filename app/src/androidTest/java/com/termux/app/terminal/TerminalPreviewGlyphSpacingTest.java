package com.termux.app.terminal;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalNotification;
import com.termux.terminal.TerminalOutput;
import com.termux.view.TerminalPreviewRenderer;
import com.termux.view.TerminalRenderer;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pixel-level check that a terminal preview keeps every glyph on its cell.
 *
 * <p>The preview renderer scales the emulator's glyph grid into the card bitmap with
 * {@link TerminalPreviewRenderer#render}. Giving the bitmap the grid's own aspect ratio removes
 * margins, so the visible cell pitch is exactly {@code bitmap.width / columns}. A row of identical
 * glyphs is the simplest way to expose a cumulative spacing error; varying the bitmap width makes
 * that pitch fractional.</p>
 *
 * <p>This is an instrumentation test because it needs real Skia rasterisation. The Robolectric
 * runtime available to the JVM tests does not provide native graphics on Android/arm64.</p>
 */
@RunWith(AndroidJUnit4.class)
public class TerminalPreviewGlyphSpacingTest {

    private static final Typeface TYPEFACE = Typeface.MONOSPACE;
    private static final int COLUMNS = 24;
    // Keep a spare row so writing the full first row cannot scroll it out of the visible screen.
    private static final int ROWS = 2;
    private static final int TEXT_SIZE = 32;

    @Test
    public void glyphSpacingMatchesTheCellPitch() {
        final int[] targetWidths = {301, 307, 313, 319, 327, 331};
        for (int targetWidth : targetWidths) {
            final TerminalEmulator emulator = createEmulator();
            final Preview preview = render(emulator, targetWidth);
            assertGlyphSpacing(preview.bitmap, preview.cellWidth);
            preview.bitmap.recycle();
        }
    }

    /**
     * A block element must cover its cell in the preview as completely as it does full-screen.
     *
     * <p>Centres are already checked above; this test checks the other half of the same defect:
     * two glyphs can be centred on their cells and still look separated when their ink is too
     * narrow. U+2588 is a solid area, so a preview that leaves a background column between two
     * cells is visibly striped even though every cluster centre is correct. The reference is the
     * same emulator painted by the ordinary full-screen renderer, not a hardcoded width.</p>
     */
    @Test
    public void blockGlyphsLeaveNoNewBackgroundColumnsInThePreview() {
        final TerminalEmulator emulator = createBlockEmulator();
        final Preview preview = render(emulator, 400);

        final Bitmap fullScreen = renderFullScreen(emulator);
        final boolean[] fullInk = inkedColumns(fullScreen);
        final boolean[] previewInk = inkedColumns(preview.bitmap);
        final float fullEmpty = emptyColumnFraction(fullInk, 0, fullScreen.getWidth());
        final float previewEmpty = emptyColumnFraction(previewInk, 0,
                preview.bitmap.getWidth());
        assertTrue("preview introduced background columns between block glyphs: preview="
                        + previewEmpty + ", full-screen=" + fullEmpty,
                previewEmpty <= fullEmpty + 0.10f);

        final float cellWidth = preview.cellWidth;
        for (int column = 0; column < COLUMNS; column++) {
            final int start = Math.round(column * cellWidth);
            final int end = Math.min(preview.bitmap.getWidth(),
                    Math.round((column + 1) * cellWidth));
            boolean any = false;
            for (int x = start; x < end; x++) {
                if (previewInk[x]) {
                    any = true;
                    break;
                }
            }
            assertTrue("block cell " + column + " has no ink at all", any);
        }

        fullScreen.recycle();
        preview.bitmap.recycle();
    }

    private static TerminalEmulator createBlockEmulator() {
        final TerminalEmulator emulator = newEmulator();
        feed(emulator, "\033[?25l");
        final StringBuilder line = new StringBuilder(COLUMNS);
        for (int i = 0; i < COLUMNS; i++) line.append('\u2588');
        feed(emulator, line.toString());
        return emulator;
    }

    private static Bitmap renderFullScreen(TerminalEmulator emulator) {
        final TerminalRenderer renderer = new TerminalRenderer(TEXT_SIZE, TYPEFACE);
        final int width = Math.max(1, Math.round(COLUMNS * renderer.getFontWidth()));
        final int height = Math.max(1, renderer.getFontLineSpacing() * (ROWS + 2));
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLACK);
        renderer.render(emulator, new Canvas(bitmap), 0,
                -1, -1, -1, -1, 0f, 0f, width, null, false);
        return bitmap;
    }

    private static boolean[] inkedColumns(Bitmap bitmap) {
        final boolean[] inked = new boolean[bitmap.getWidth()];
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                final int color = bitmap.getPixel(x, y);
                final int red = (color >> 16) & 0xFF;
                final int green = (color >> 8) & 0xFF;
                final int blue = color & 0xFF;
                if (red + green + blue > 24) inked[x] = true;
            }
        }
        return inked;
    }

    private static float emptyColumnFraction(boolean[] inked, int start, int end) {
        if (end <= start) return 0f;
        int empty = 0;
        for (int x = start; x < end; x++) {
            if (!inked[x]) empty++;
        }
        return empty / (float) (end - start);
    }

    private static TerminalEmulator newEmulator() {
        final TerminalOutput output = new TerminalOutput() {
            @Override
            public void write(byte[] data, int offset, int count) {}

            @Override
            public void titleChanged(String oldTitle, String newTitle) {}

            @Override
            public void onCopyTextToClipboard(String text) {}

            @Override
            public void onPasteTextFromClipboard() {}

            @Override
            public void onBell() {}

            @Override
            public void onNotification(TerminalNotification notification) {}

            @Override
            public void onColorsChanged() {}
        };
        return new TerminalEmulator(output, COLUMNS, ROWS, 10, 20, ROWS * 2, null);
    }

    private static TerminalEmulator createEmulator() {
        final TerminalEmulator emulator = newEmulator();
        // A visible block cursor would add ink to one cell and make the cluster count ambiguous.
        feed(emulator, "\033[?25l");
        final StringBuilder line = new StringBuilder(COLUMNS);
        for (int i = 0; i < COLUMNS; i++) line.append('|');
        feed(emulator, line.toString());
        return emulator;
    }

    private static void feed(TerminalEmulator emulator, String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }

    private static Preview render(TerminalEmulator emulator, int width) {
        final double aspect = TerminalPreviewRenderer.aspectRatio(emulator, TEXT_SIZE, TYPEFACE);
        assertTrue("no usable grid aspect", aspect > 0d);
        final int height = Math.max(1, (int) Math.round(width / aspect));
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLACK);
        TerminalPreviewRenderer.render(emulator, bitmap, TEXT_SIZE, TYPEFACE, 0, 0);
        return new Preview(bitmap, width / (float) COLUMNS);
    }

    /**
     * Project the bitmap onto the x axis and treat every connected run of inked columns as one
     * glyph. All glyphs are the same character, so their centres must be exactly one cell pitch
     * apart. A rounded advance that is smaller or larger than the cell pitch accumulates into a
     * measurable drift by the end of the row.
     */
    private static void assertGlyphSpacing(Bitmap bitmap, float cellWidth) {
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        final boolean[] inkedColumns = new boolean[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int color = bitmap.getPixel(x, y);
                final int red = (color >> 16) & 0xFF;
                final int green = (color >> 8) & 0xFF;
                final int blue = color & 0xFF;
                if (red + green + blue > 96) inkedColumns[x] = true;
            }
        }

        final List<int[]> clusters = new ArrayList<>();
        int clusterStart = -1;
        for (int x = 0; x < width; x++) {
            if (inkedColumns[x]) {
                if (clusterStart < 0) clusterStart = x;
            } else if (clusterStart >= 0) {
                clusters.add(new int[]{clusterStart, x - 1});
                clusterStart = -1;
            }
        }
        if (clusterStart >= 0) clusters.add(new int[]{clusterStart, width - 1});

        assertEquals("width=" + width + ": expected one ink cluster per glyph, got " + clusters,
                COLUMNS, clusters.size());

        final float firstCenter = clusterCenter(clusters.get(0));
        final float lastCenter = clusterCenter(clusters.get(clusters.size() - 1));
        final float expectedSpan = (COLUMNS - 1) * cellWidth;
        assertEquals("width=" + width + ": accumulated glyph drift",
                expectedSpan, lastCenter - firstCenter, 1.5f);

        for (int i = 1; i < clusters.size(); i++) {
            final float previous = clusterCenter(clusters.get(i - 1));
            final float current = clusterCenter(clusters.get(i));
            assertEquals("width=" + width + ": spacing before glyph " + i,
                    cellWidth, current - previous, 0.9f);
        }
    }

    private static float clusterCenter(int[] cluster) {
        return (cluster[0] + cluster[1] + 1) / 2f;
    }

    private static final class Preview {
        final Bitmap bitmap;
        final float cellWidth;

        Preview(Bitmap bitmap, float cellWidth) {
            this.bitmap = bitmap;
            this.cellWidth = cellWidth;
        }
    }
}
