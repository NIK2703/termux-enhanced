package com.termux.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.termux.shared.logger.Logger;

/**
 * Base class for all Termux settings fragments. Removes the default AndroidX
 * Preference dividers so no stray separator line is drawn at the end of each
 * preference category (sub-section) on a settings screen.
 */
public abstract class TermuxPreferenceFragmentBase extends PreferenceFragmentCompat {

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setDivider(null);

        // Set the Activity title from the PreferenceScreen title if present.
        PreferenceScreen screen = getPreferenceScreen();
        if (screen != null && screen.getTitle() != null) {
            AppCompatActivity activity = (AppCompatActivity) requireActivity();
            activity.setTitle(screen.getTitle());
        }
    }

    /**
     * Populates a {@link ListPreference} with the available log levels and selects the current one.
     * Shared by every settings screen that exposes a "Log level" control (the main Diagnostics
     * screen and the per-plugin Debugging sub-screens) so the helper lives in one place.
     */
    @NonNull
    public static ListPreference setLogLevelListPreferenceData(@NonNull ListPreference logLevelListPreference,
                                                              @NonNull Context context, int logLevel) {
        if (logLevelListPreference == null)
            logLevelListPreference = new ListPreference(context);

        CharSequence[] logLevels = Logger.getLogLevelsArray();
        CharSequence[] logLevelLabels = Logger.getLogLevelLabelsArray(context, logLevels);

        logLevelListPreference.setEntryValues(logLevels);
        logLevelListPreference.setEntries(logLevelLabels);

        logLevelListPreference.setValue(String.valueOf(logLevel));
        logLevelListPreference.setDefaultValue(Logger.DEFAULT_LOG_LEVEL);

        return logLevelListPreference;
    }
}