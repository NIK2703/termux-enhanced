package com.termux.app.terminal;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.termux.R;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

/** The overview root keeps the flying layer above the grid and out of the touch path. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SessionOverviewLayoutTest {

    @Test
    public void ghostLayerIsTheLastChildAndDoesNotConsumeTouches() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View overlay = LayoutInflater.from(activity)
                .inflate(R.layout.view_session_overview, null, false);

        Assert.assertTrue(overlay instanceof ViewGroup);
        ViewGroup group = (ViewGroup) overlay;
        Assert.assertEquals(2, group.getChildCount());

        View scroll = group.getChildAt(0);
        View ghosts = group.getChildAt(1);
        Assert.assertEquals(R.id.session_overview_scroll, scroll.getId());
        Assert.assertTrue(scroll instanceof SessionOverviewScrollView);
        Assert.assertEquals(R.id.session_overview_ghost_layer, ghosts.getId());

        ViewGroup scrollGroup = (ViewGroup) scroll;
        Assert.assertEquals(1, scrollGroup.getChildCount());
        View grid = scrollGroup.getChildAt(0);
        Assert.assertEquals(R.id.session_overview_grid, grid.getId());
        Assert.assertTrue(ghosts instanceof FrameLayout);
        Assert.assertFalse(ghosts.isClickable());
        Assert.assertFalse(ghosts.isFocusable());
        Assert.assertFalse(((ViewGroup) ghosts).getClipChildren());
        Assert.assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                ghosts.getImportantForAccessibility());

        // The overview is the terminal's replacement, not a translucent sheet over it: the
        // original terminal must not remain visible as a background.
        Assert.assertTrue(overlay.getBackground() instanceof ColorDrawable);
        Assert.assertEquals(255, Color.alpha(((ColorDrawable) overlay.getBackground()).getColor()));
    }

    @Test
    public void scrollViewHasNoScrollbarAndLeavesOverscrollToTheElasticLayer() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View overlay = LayoutInflater.from(activity)
                .inflate(R.layout.view_session_overview, null, false);

        SessionOverviewScrollView scroll = overlay.findViewById(R.id.session_overview_scroll);
        Assert.assertFalse(scroll.isVerticalScrollBarEnabled());
        Assert.assertFalse(scroll.isHorizontalScrollBarEnabled());
        Assert.assertEquals(View.OVER_SCROLL_NEVER, scroll.getOverScrollMode());
    }
}
