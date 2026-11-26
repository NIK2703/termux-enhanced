package com.termux.app.terminal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

/**
 * Pins the layout assumption behind {@link TerminalContentViewport}: the pager is full-bleed and
 * its page's {@code TerminalView} is {@code match_parent} with terminal margins, so the view is
 * exactly the pager rectangle minus those margins.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TerminalContentViewportLayoutTest {

    @Test
    public void marginsShrinkThePageExactlyLikeTheContentViewportMath() {
        final int pagerWidth = 1080;
        final int pagerHeight = 1920;
        final int left = 55;
        final int top = 60;
        final int right = 55;
        final int bottom = 80;

        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout pager = new FrameLayout(activity);
        View page = new View(activity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        params.setMargins(left, top, right, bottom);
        pager.addView(page, params);
        pager.measure(
                View.MeasureSpec.makeMeasureSpec(pagerWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(pagerHeight, View.MeasureSpec.EXACTLY));
        pager.layout(0, 0, pagerWidth, pagerHeight);

        assertEquals(pagerWidth - left - right, page.getWidth());
        assertEquals(pagerHeight - top - bottom, page.getHeight());
        assertArrayEquals(
                new int[]{page.getWidth(), page.getHeight()},
                TerminalContentViewport.fromPager(
                        pagerWidth, pagerHeight, left, top, right, bottom, 0));
    }
}
