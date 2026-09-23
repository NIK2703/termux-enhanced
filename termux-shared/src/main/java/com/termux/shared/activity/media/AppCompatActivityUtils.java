package com.termux.shared.activity.media;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;

import com.termux.shared.logger.Logger;
import com.termux.shared.theme.NightMode;

public class AppCompatActivityUtils {

    private static final String LOG_TAG = "AppCompatActivityUtils";

    /**
     * Set activity night mode.
     *
     * @param local {@code true} → {@link AppCompatDelegate#setLocalNightMode(int)},
     *              {@code false} → {@link AppCompatDelegate#setDefaultNightMode(int)}.
     */
    public static void setNightMode(AppCompatActivity activity, String name, boolean local) {
        if (name == null) return;
        NightMode nightMode = NightMode.modeOf(name);
        if (nightMode != null) {
            if (local) {
                if (activity != null) {
                    activity.getDelegate().setLocalNightMode(nightMode.getMode());
                }
            } else {
                AppCompatDelegate.setDefaultNightMode(nightMode.getMode());
            }
        }

    }

    /** Set the activity toolbar from the given resource id. */
    public static void setToolbar(@NonNull AppCompatActivity activity, @IdRes int id) {
        Toolbar toolbar = activity.findViewById(id);
        if (toolbar != null)
            activity.setSupportActionBar(toolbar);
    }

    /** Set the activity toolbar title and its text appearance. */
    public static void setToolbarTitle(@NonNull AppCompatActivity activity, @IdRes int id,
                                       String title, @StyleRes int titleAppearance) {
        Toolbar toolbar = activity.findViewById(id);
        if (toolbar != null) {
            // toolbar.setTitle(title) does not work here; the title must be set via the ActionBar.
            final ActionBar actionBar = activity.getSupportActionBar();
            if (actionBar != null)
                actionBar.setTitle(title);

            try {
                if (titleAppearance != 0)
                    toolbar.setTitleTextAppearance(activity, titleAppearance);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to set toolbar title appearance to style resource id " + titleAppearance, e);
            }
        }
    }

    /** Set the activity toolbar subtitle and its text appearance. */
    public static void setToolbarSubtitle(@NonNull AppCompatActivity activity, @IdRes int id,
                                          String subtitle, @StyleRes int subtitleAppearance) {
        Toolbar toolbar = activity.findViewById(id);
        if (toolbar != null) {
            toolbar.setSubtitle(subtitle);
            try {
                if (subtitleAppearance != 0)
                    toolbar.setSubtitleTextAppearance(activity, subtitleAppearance);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to set toolbar subtitle appearance to style resource id " + subtitleAppearance, e);
            }
        }
    }

    /** Show or hide the back button in the activity toolbar. */
    public static void setShowBackButtonInActionBar(@NonNull AppCompatActivity activity,
                                                    boolean showBackButtonInActionBar) {
        final ActionBar actionBar = activity.getSupportActionBar();
        if (actionBar != null) {
            if (showBackButtonInActionBar) {
                actionBar.setDisplayHomeAsUpEnabled(true);
                actionBar.setDisplayShowHomeEnabled(true);
            } else {
                actionBar.setDisplayHomeAsUpEnabled(false);
                actionBar.setDisplayShowHomeEnabled(false);
            }
        }
    }

}
