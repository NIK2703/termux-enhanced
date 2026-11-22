package com.termux.app.terminal;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;

import com.termux.R;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

/** The card is one full-bleed preview with the tab inset from its top, start and end edges. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SessionOverviewCardLayoutTest {

    @Test
    public void tabIsAnOverlayOnTopOfThePreviewNotASeparateRow() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View card = LayoutInflater.from(activity)
                .inflate(R.layout.item_session_overview_card, null, false);

        Assert.assertTrue(card instanceof ViewGroup);
        Assert.assertTrue(card instanceof SessionOverviewCardView);
        Assert.assertEquals(3, ((ViewGroup) card).getChildCount());

        View preview = card.findViewById(R.id.session_overview_preview);
        SessionOverviewTabTitleView tab = card.findViewById(R.id.session_overview_tab);
        ImageButton close = card.findViewById(R.id.session_overview_close);
        Assert.assertTrue(preview instanceof ImageView);
        Assert.assertNotNull(tab);
        Assert.assertNotNull(close);

        // The tab and close button are the later children, so they draw over the preview
        // rather than beside it.
        Assert.assertSame(preview, ((ViewGroup) card).getChildAt(0));
        Assert.assertSame(tab, ((ViewGroup) card).getChildAt(1));
        Assert.assertSame(close, ((ViewGroup) card).getChildAt(2));
        Assert.assertEquals(ViewGroup.LayoutParams.MATCH_PARENT,
                preview.getLayoutParams().width);
        Assert.assertEquals(ViewGroup.LayoutParams.MATCH_PARENT,
                preview.getLayoutParams().height);

        FrameLayout.LayoutParams tabParams = (FrameLayout.LayoutParams) tab.getLayoutParams();
        Assert.assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, tabParams.width);
        Assert.assertTrue((tabParams.gravity & Gravity.TOP) == Gravity.TOP);
        Assert.assertTrue((tabParams.gravity & Gravity.START) == Gravity.START);
        // The pill is inset from the card's top, start and end edges by the same amount.
        Assert.assertTrue(tabParams.topMargin > 0);
        Assert.assertEquals(tabParams.topMargin, tabParams.leftMargin);
        Assert.assertEquals(tabParams.topMargin, tabParams.rightMargin);
        // No elevation: the overview pill must not cast a shadow onto the preview.
        Assert.assertEquals(0f, tab.getElevation(), 0f);
        // The end padding leaves room for the close button, so the faded title stops before it.
        Assert.assertTrue(tab.getPaddingEnd() > tab.getPaddingStart());
        Assert.assertEquals(1, tab.getMaxLines());

        FrameLayout.LayoutParams closeParams =
                (FrameLayout.LayoutParams) close.getLayoutParams();
        Assert.assertTrue((closeParams.gravity & Gravity.TOP) == Gravity.TOP);
        Assert.assertTrue((closeParams.gravity & Gravity.END) == Gravity.END);
        Assert.assertTrue(closeParams.getMarginEnd() > tabParams.getMarginEnd());
        Assert.assertNotNull(close.getContentDescription());
        Assert.assertEquals(0f, close.getElevation(), 0f);
        Assert.assertEquals(0f, close.getTranslationZ(), 0f);

        // A tap on the card body selects it, but the close button is a real, separate target.
        Assert.assertTrue(card.isClickable());
        Assert.assertFalse(tab.isClickable());
        Assert.assertFalse(tab.isFocusable());
        Assert.assertTrue(close.isClickable());
        Assert.assertTrue(close.isFocusable());
    }

    @Test
    public void cardHasNoBackgroundBorderOrPressRipple() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SessionOverviewCardView card = (SessionOverviewCardView) LayoutInflater.from(activity)
                .inflate(R.layout.item_session_overview_card, null, false);
        View preview = card.findViewById(R.id.session_overview_preview);

        Assert.assertNull(card.getBackground());
        Assert.assertNull(card.getForeground());
        Assert.assertFalse(card.getClipToOutline());
        Assert.assertNull(preview.getBackground());
    }

    @Test
    public void overviewTabBackgroundIsHalfOpaque() {
        final int background = SessionOverviewController.overviewTabBackground(
                0xFF000000, 0x14FFFFFF);
        Assert.assertEquals(0x80, Color.alpha(background));
        Assert.assertEquals(0x14, Color.red(background));
        Assert.assertEquals(0x14, Color.green(background));
        Assert.assertEquals(0x14, Color.blue(background));
    }
}
