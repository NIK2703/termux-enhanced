package com.termux.app.terminal;

import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxAppConstants;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

/**
 * The pill height and the title line count are two halves of one mode. A rotation must re-apply
 * both, including the title text itself: keeping the old one-line render inside a two-line pill
 * is the bug this pins.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TabHeightModeApplicationTest {

    private static final String LONG_TITLE =
            "first line of the session title\nsecond line of the session title";

    @Test
    public void rotationRoundTripKeepsHeightAndTitleLinesInSync() {
        final TestTermuxActivity activity =
                Robolectric.buildActivity(TestTermuxActivity.class).get();

        activity.getSharedPreferences(TermuxAppConstants.Preferences.FILE_NAME, 0)
                .edit()
                .putString(TermuxAppConstants.Preferences.TAB_HEIGHT_MODE,
                        TermuxAppConstants.Preferences.TAB_HEIGHT_MODE_DOUBLE_LANDSCAPE_SINGLE)
                .commit();

        final LinearLayout tabs = new LinearLayout(activity);
        final HorizontalScrollView scroll = new HorizontalScrollView(activity);
        activity.installStrip(tabs, scroll);

        final View tab = LayoutInflater.from(activity)
                .inflate(R.layout.item_session_tab, tabs, false);
        final TextView title = tab.findViewById(R.id.session_tab_title);
        // Simulate a tab that was populated while the window was landscape: the view already
        // holds the one-line render, and only the full title survives in the accessibility text.
        title.setText("first line of the session title");
        title.setContentDescription(LONG_TITLE);
        tabs.addView(tab);
        tabs.addView(new View(activity)); // the (+) button, always the last child

        final TermuxSessionTabsController controller =
                new TermuxSessionTabsController(activity);

        final int doubleHeight = activity.getResources().getDimensionPixelSize(
                R.dimen.terminal_tab_height_double);
        final int singleHeight = activity.getResources().getDimensionPixelSize(
                R.dimen.terminal_tab_height_single);

        activity.setLastWindowLandscape(false);
        controller.applyTabHeightMode(portrait());
        Assert.assertEquals(doubleHeight, tab.getLayoutParams().height);
        Assert.assertEquals(2, title.getMaxLines());
        Assert.assertTrue("portrait must render two lines: " + title.getText(),
                title.getText().toString().indexOf('\n') > 0);

        activity.setLastWindowLandscape(true);
        controller.applyTabHeightMode(landscape());
        Assert.assertEquals(singleHeight, tab.getLayoutParams().height);
        Assert.assertEquals(1, title.getMaxLines());
        Assert.assertFalse("landscape must render one line: " + title.getText(),
                title.getText().toString().contains("\n"));

        // The second rotation is the regression: the render state used to be invalidated after
        // the first one, so this pass only changed maxLines and left the landscape one-line text
        // inside the taller pill.
        activity.setLastWindowLandscape(false);
        controller.applyTabHeightMode(portrait());
        Assert.assertEquals(doubleHeight, tab.getLayoutParams().height);
        Assert.assertEquals(2, title.getMaxLines());
        Assert.assertTrue("returning to portrait must render two lines: " + title.getText(),
                title.getText().toString().indexOf('\n') > 0);
    }

    private static Configuration portrait() {
        final Configuration configuration = new Configuration();
        configuration.orientation = Configuration.ORIENTATION_PORTRAIT;
        configuration.screenWidthDp = 400;
        configuration.screenHeightDp = 800;
        return configuration;
    }

    private static Configuration landscape() {
        final Configuration configuration = new Configuration();
        configuration.orientation = Configuration.ORIENTATION_LANDSCAPE;
        configuration.screenWidthDp = 800;
        configuration.screenHeightDp = 400;
        return configuration;
    }

    /** Minimal activity: the controller only needs the two strip views, preferences and shape. */
    public static class TestTermuxActivity extends TermuxActivity {

        private LinearLayout mTabs;
        private HorizontalScrollView mScroll;
        private boolean mLastWindowLandscape;

        void installStrip(LinearLayout tabs, HorizontalScrollView scroll) {
            mTabs = tabs;
            mScroll = scroll;
        }

        void setLastWindowLandscape(boolean landscape) {
            mLastWindowLandscape = landscape;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T extends View> T findViewById(int id) {
            if (id == R.id.session_tabs) return (T) mTabs;
            if (id == R.id.session_tabs_scroll) return (T) mScroll;
            return super.findViewById(id);
        }

        @Override
        public boolean isWindowLandscape(Configuration changedConfig) {
            return changedConfig != null
                    ? TermuxSessionTabsController.isWindowLandscape(changedConfig)
                    : mLastWindowLandscape;
        }
    }
}
