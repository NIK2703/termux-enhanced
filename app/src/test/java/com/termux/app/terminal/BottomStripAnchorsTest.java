package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

/**
 * Pins the invariant that broke the tab panel: the terminal and the tab panel are the two topmost
 * children of one {@code RelativeLayout}, the terminal declared <em>after</em> the panel and
 * therefore painting on top of it. Anchoring both to the same bottom strip does not make the two
 * redundant — it hides the tab panel behind the terminal, and only the "tabs at bottom" setting
 * ever reaches that arrangement, so the default build looks fine while it is broken.
 *
 * <p>A plain JVM test on purpose: the decision carries no Android type, and the activity that uses
 * it cannot even be loaded here.
 */
public class BottomStripAnchorsTest {

    private static final int TAB_STRIP = 0x7f0a0001;
    private static final int EXTRA_KEYS_STRIP = 0x7f0a0002;
    private static final int INPUT_PANEL_STRIP = 0x7f0a0003;

    @Test
    public void tabsAtBottomStackTerminalAboveTheTabPanel() {
        final int[] anchors = BottomStripAnchors.pagerAndTabs(true, TAB_STRIP, EXTRA_KEYS_STRIP);

        Assert.assertEquals("terminal must stop at the tab panel", TAB_STRIP, anchors[0]);
        Assert.assertEquals("tab panel must sit on the bottom strip", EXTRA_KEYS_STRIP, anchors[1]);
    }

    @Test
    public void tabsAtBottomNeverShareOneAnchor() {
        Assert.assertNotEquals(
            BottomStripAnchors.pagerAndTabs(true, TAB_STRIP, EXTRA_KEYS_STRIP)[0],
            BottomStripAnchors.pagerAndTabs(true, TAB_STRIP, EXTRA_KEYS_STRIP)[1]);
    }

    @Test
    public void tabsAtTopLeaveTheTerminalOnTheBottomStripAndTheTabsToTheTopEdge() {
        final int[] anchors = BottomStripAnchors.pagerAndTabs(false, TAB_STRIP, EXTRA_KEYS_STRIP);

        Assert.assertEquals(EXTRA_KEYS_STRIP, anchors[0]);
        // The tab panel is pinned to the top edge, so it claims no bottom edge here.
        Assert.assertEquals(BottomStripAnchors.NO_BOTTOM_EDGE, anchors[1]);
    }

    /**
     * The input panel's legacy placement makes the panel itself the bottom strip, so the two views
     * would coincide by another route if the decision did not hold for that anchor too.
     */
    @Test
    public void inputPanelAsBottomStripStillKeepsTheTwoApart() {
        final int[] anchors = BottomStripAnchors.pagerAndTabs(true, TAB_STRIP, INPUT_PANEL_STRIP);

        Assert.assertEquals(TAB_STRIP, anchors[0]);
        Assert.assertEquals(INPUT_PANEL_STRIP, anchors[1]);
        Assert.assertNotEquals(anchors[0], anchors[1]);
    }
}
