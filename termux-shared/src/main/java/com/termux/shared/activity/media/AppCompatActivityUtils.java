package com.termux.shared.activity.media;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;

import com.termux.shared.theme.NightMode;

public class AppCompatActivityUtils {

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
