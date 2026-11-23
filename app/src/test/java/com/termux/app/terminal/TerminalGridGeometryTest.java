package com.termux.app.terminal;

import android.graphics.Rect;

import com.termux.view.TerminalGridGeometry;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

/**
 * The terminal flight must use the glyph grid, not the full TerminalView. The view's background
 * margins around the grid are absent from the card preview, so including them changes the aspect
 * ratio mid-flight and makes the terminal jump when the preview appears.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TerminalGridGeometryTest {

    @Test
    public void gridBoundsExcludeTheTerminalBackgroundMargins() {
        final Rect out = new Rect();

        Assert.assertTrue(TerminalGridGeometry.visibleBounds(
                1080, 1920, 60, 40, 16.5f, 30, 45f, 0f, out));

        // 60 * 16.5 = 990 wide, 40 * 30 = 1200 tall; the 45 px side bands and the
        // 720 px band below the last row are terminal background, not terminal.
        Assert.assertEquals(new Rect(45, 0, 1035, 1200), out);
    }

    @Test
    public void gridBoundsClipToTheViewDuringOverdrag() {
        final Rect out = new Rect();

        Assert.assertTrue(TerminalGridGeometry.visibleBounds(
                1080, 1920, 60, 40, 16.5f, 30, 45f, -12f, out));

        Assert.assertEquals(45, out.left);
        Assert.assertEquals(0, out.top);
        Assert.assertEquals(1035, out.right);
        Assert.assertEquals(1188, out.bottom);
    }

    @Test
    public void degenerateGridIsReportedAsEmpty() {
        final Rect out = new Rect(1, 2, 3, 4);

        Assert.assertFalse(TerminalGridGeometry.visibleBounds(
                1080, 1920, 0, 40, 16.5f, 30, 45f, 0f, out));
        Assert.assertTrue(out.isEmpty());
    }
}
