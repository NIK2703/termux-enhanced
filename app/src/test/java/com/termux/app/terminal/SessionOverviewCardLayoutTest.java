package com.termux.app.terminal;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.CharacterStyle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

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
        // The overview keeps its own pill padding; only the strip's height, radius and line
        // count are adopted at runtime.
        final android.content.res.Resources res = activity.getResources();
        Assert.assertEquals(res.getDimensionPixelSize(
                R.dimen.session_overview_tab_padding_horizontal), tab.getPaddingStart());
        Assert.assertEquals(res.getDimensionPixelSize(
                R.dimen.session_overview_tab_padding_end), tab.getPaddingEnd());
        Assert.assertEquals(res.getDimensionPixelSize(
                R.dimen.session_overview_tab_padding_vertical), tab.getPaddingTop());
        // The end padding leaves room for the close button, so the faded title stops before it.
        Assert.assertTrue(tab.getPaddingEnd() > tab.getPaddingStart());
        Assert.assertEquals(1, tab.getMaxLines());

        FrameLayout.LayoutParams closeParams =
                (FrameLayout.LayoutParams) close.getLayoutParams();
        Assert.assertTrue((closeParams.gravity & Gravity.TOP) == Gravity.TOP);
        Assert.assertTrue((closeParams.gravity & Gravity.END) == Gravity.END);
        Assert.assertTrue(closeParams.getMarginEnd() > tabParams.getMarginEnd());
        Assert.assertNotNull(close.getContentDescription());
        // No borderless selectable background: tapping the cross must not pulse a ripple.
        Assert.assertNull(close.getBackground());
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
    public void overviewTitleUsesTheSameTextSizeAsTheStripTitle() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LayoutInflater inflater = LayoutInflater.from(activity);
        TextView stripTitle = inflater.inflate(R.layout.item_session_tab, null, false)
                .findViewById(R.id.session_tab_title);
        SessionOverviewTabTitleView overviewTitle = inflater
                .inflate(R.layout.item_session_overview_card, null, false)
                .findViewById(R.id.session_overview_tab);

        Assert.assertNotNull(stripTitle);
        Assert.assertNotNull(overviewTitle);
        Assert.assertEquals(stripTitle.getTextSize(), overviewTitle.getTextSize(), 0f);
        // The strip title has no vertical padding. Symmetric padding would keep the baseline
        // centred but shrink the content box, clipping the same font at larger scales.
        Assert.assertEquals(0, overviewTitle.getPaddingTop());
        Assert.assertEquals(0, overviewTitle.getPaddingBottom());
    }

    @Test
    public void cardHasNoBackgroundOrShadowAndUsesTheFloatingOutline() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SessionOverviewCardView card = (SessionOverviewCardView) LayoutInflater.from(activity)
                .inflate(R.layout.item_session_overview_card, null, false);
        View preview = card.findViewById(R.id.session_overview_preview);

        Assert.assertNull(card.getBackground());
        Assert.assertNull(card.getForeground());
        Assert.assertFalse(card.getClipToOutline());
        Assert.assertNotNull(card.getOutlineProvider());
        // The thumbnail casts no shadow; the thin outline is its only chrome.
        Assert.assertEquals(0f, card.getElevation(), 0f);
        Assert.assertNull(preview.getBackground());
    }

    @Test
    public void outlineIsInsetInsideTheRoundedCornerWithTheFloatingStroke() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SessionOverviewCardView card = (SessionOverviewCardView) LayoutInflater.from(activity)
                .inflate(R.layout.item_session_overview_card, null, false);
        card.layout(0, 0, 100, 80);
        final RecordingCanvas canvas = new RecordingCanvas();
        final int color = 0x80123456;

        card.setBorder(2, color, 12f);
        card.dispatchDraw(canvas);

        Assert.assertEquals(1, canvas.roundRectDraws);
        // A 2px stroke is inset by half its width so the outline stays inside the clip.
        Assert.assertEquals(1f, canvas.left, 0f);
        Assert.assertEquals(1f, canvas.top, 0f);
        Assert.assertEquals(99f, canvas.right, 0f);
        Assert.assertEquals(79f, canvas.bottom, 0f);
        Assert.assertEquals(11f, canvas.radius, 0f);
        Assert.assertEquals(color, canvas.paint.getColor());
        Assert.assertEquals(2f, canvas.paint.getStrokeWidth(), 0f);
        Assert.assertEquals(Paint.Style.STROKE, canvas.paint.getStyle());

        card.setBorderSuppressed(true);
        card.dispatchDraw(canvas);
        Assert.assertEquals(1, canvas.roundRectDraws);
    }

    private static final class RecordingCanvas extends Canvas {
        int roundRectDraws;
        float left;
        float top;
        float right;
        float bottom;
        float radius;
        Paint paint;

        @Override
        public void drawRoundRect(float left, float top, float right, float bottom,
                                  float rx, float ry, Paint paint) {
            roundRectDraws++;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.radius = rx;
            this.paint = paint;
        }
    }

    @Test
    public void twoLineModeUsesTheStripWrappingPath() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TextView title = new TextView(activity);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f);
        title.setMaxLines(2);
        title.setIncludeFontPadding(true);
        title.setLineSpacing(-2f, 1f);

        final String source = "a long session title that needs more than two lines at this width";
        TermuxSessionTabsController.TitleRender render =
                TermuxSessionTabsController.renderTitle(title, source, 20);

        // A two-line title is allowed to use the second line; overflow past it is faded instead
        // of being forced back onto a single line.
        Assert.assertTrue("text=" + render.text + " truncated=" + render.truncated
                        + " maxLines=" + title.getMaxLines()
                        + " measure=" + title.getPaint().measureText(source),
                render.truncated);
        Assert.assertTrue(render.text.length() < source.length());
    }

    @Test
    public void bothLineModesFadeTheTailWithoutAnEllipsis() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TextView title = new TextView(activity);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f);
        title.setIncludeFontPadding(true);
        title.setLineSpacing(-2f, 1f);

        final String source = "a long session title that needs more than two lines at this width";
        SessionOverviewTabTitleView overviewTitle = LayoutInflater.from(activity)
                .inflate(R.layout.item_session_overview_card, null, false)
                .findViewById(R.id.session_overview_tab);
        overviewTitle.setPadding(0, 0, 0, 0);
        overviewTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f);
        overviewTitle.setIncludeFontPadding(true);
        overviewTitle.setLineSpacing(-2f, 1f);
        overviewTitle.layout(0, 0, 20, 34);
        overviewTitle.setFullTitle(source);

        for (int maxLines : new int[]{1, 2}) {
            title.setMaxLines(maxLines);
            TermuxSessionTabsController.TitleRender render =
                    TermuxSessionTabsController.renderTitle(title, source, 20);
            final String text = render.text.toString();

            Assert.assertTrue("maxLines=" + maxLines + " text=" + text, render.truncated);
            Assert.assertFalse("platform ellipsis in " + text, text.contains("..."));
            Assert.assertFalse("platform ellipsis in " + text, text.contains("\u2026"));
            Assert.assertTrue("fade spans missing for " + text, render.text instanceof Spanned);
            final Spanned spanned = (Spanned) render.text;
            Assert.assertEquals("maxLines=" + maxLines + " text=" + text, 3,
                    spanned.getSpans(0, spanned.length(), CharacterStyle.class).length);
            if (maxLines == 2) {
                Assert.assertTrue("two-line title must use the second line: " + text,
                        text.indexOf('\n') > 0);
            }
            for (String line : text.split("\n", -1)) {
                Assert.assertTrue("line too wide: " + line,
                        title.getPaint().measureText(line) <= 20);
            }

            // The view must use that same faded prefix in its own layout. Simulate an ellipsis
            // arriving from a theme or an earlier line-count mode; rendering has to remove it.
            overviewTitle.setMaxLines(maxLines);
            overviewTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            overviewTitle.refreshTitle();
            overviewTitle.measure(
                    View.MeasureSpec.makeMeasureSpec(20, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(34, View.MeasureSpec.EXACTLY));
            overviewTitle.layout(0, 0, 20, 34);
            Assert.assertNull(overviewTitle.getEllipsize());
            Assert.assertEquals(text, overviewTitle.getText().toString());
            final Layout layout = overviewTitle.getLayout();
            Assert.assertNotNull(layout);
            for (int line = 0; line < layout.getLineCount(); line++) {
                Assert.assertEquals("maxLines=" + maxLines + " line=" + line,
                        0, layout.getEllipsisCount(line));
            }
        }
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

    @Test
    public void thumbnailRadiusUsesTheTabsClampedRadius() {
        // GradientDrawable clamps 18dp to half the tab height. The thumbnail must use the same
        // visible radius as the tab in both line modes, not the unclamped resource value.
        Assert.assertEquals(12f,
                SessionOverviewController.effectiveTabCornerRadius(18, 24), 0.001f);
        Assert.assertEquals(17f,
                SessionOverviewController.effectiveTabCornerRadius(18, 34), 0.001f);
        Assert.assertEquals(10f,
                SessionOverviewController.effectiveTabCornerRadius(10, 34), 0.001f);
        Assert.assertEquals(0f,
                SessionOverviewController.effectiveTabCornerRadius(18, 0), 0.001f);
    }
}
