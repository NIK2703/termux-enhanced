package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.fragments.settings.TermuxPreferenceFragmentBase;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

/**
 * The "Terminal" screen. Every migrated {@code termux.properties} key now lives in
 * {@link TermuxAppSharedPreferences}. No {@link androidx.preference.PreferenceDataStore}
 * is used — each preference is wired with a non-persistent backing (setPersistent(false))
 * plus an explicit OnPreferenceChangeListener that writes through to
 * TermuxAppSharedPreferences and refreshes the activity styling where relevant.
 */
@Keep
public class TerminalPreferencesFragment extends TermuxPreferenceFragmentBase {

    private static final String LOG_TAG = "TerminalPrefsFragment";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setPreferencesFromResource(R.xml.termux_terminal_preferences, rootKey);

        TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(context, true);
        if (prefs == null) return;

        // --- Terminal: View ---
        configureIntEditText("delete-tmpdir-files-older-than-x-days-on-exit",
            prefs.getDeleteTMPDIRFilesOlderThanXDaysOnExit(),
            value -> prefs.setDeleteTMPDIRFilesOlderThanXDaysOnExit(value), false);

        configureListPreference("bell-character",
            bellBehaviourToString(prefs.getBellBehaviour()),
            R.array.bell_character_values,
            value -> prefs.setBellBehaviour(stringToBellBehaviour(value)), true);

        configureStringEditText("default-working-directory", prefs.getDefaultWorkingDirectory(),
            value -> prefs.setDefaultWorkingDirectory(value), false);

        // --- Key behaviour ---
        // (Previously hosted the Back key, Volume keys, keyboard toggle, Ctrl+Space workaround,
        // char-based input, hardware keyboard shortcuts, session-change toast and URL-on-click
        // toggles. These were migrated to the "Input" screen's TerminalIOPreferencesDataStore.)

        // Night/theme mode is configured on the Display screen (theme_mode) to avoid a duplicate.

        // --- Shortcuts (raw "Ctrl+KEY" strings) ---
        configureShortcut("shortcut.create-session", prefs.getShortcutString("shortcut.create-session"));
        configureShortcut("shortcut.next-session", prefs.getShortcutString("shortcut.next-session"));
        configureShortcut("shortcut.previous-session", prefs.getShortcutString("shortcut.previous-session"));
        configureShortcut("shortcut.rename-session", prefs.getShortcutString("shortcut.rename-session"));

        // --- Misc ---
        configureSwitch("allow-external-apps", prefs.shouldAllowExternalApps(),
            value -> prefs.setAllowExternalApps(value), false);

        configureSwitch("disable-file-share-receiver", prefs.isFileShareReceiverDisabled(),
            value -> prefs.setFileShareReceiverDisabled(value), false);

        configureSwitch("disable-file-view-receiver", prefs.isFileViewReceiverDisabled(),
            value -> prefs.setFileViewReceiverDisabled(value), false);

        configureSwitch("run-termux-am-socket-server", prefs.shouldRunTermuxAmSocketServer(),
            value -> prefs.setRunTermuxAmSocketServer(value), false);

        // --- Diagnostics (migrated from the deleted Debugging screen) ---
        configureSwitch("terminal_view_key_logging_enabled", prefs.isTerminalViewKeyLoggingEnabled(),
            value -> prefs.setTerminalViewKeyLoggingEnabled(value), false);

        configureSwitch("plugin_error_notifications_enabled", prefs.arePluginErrorNotificationsEnabled(false),
            value -> prefs.setPluginErrorNotificationsEnabled(value), false);

        configureSwitch("crash_report_notifications_enabled", prefs.areCrashReportNotificationsEnabled(false),
            value -> prefs.setCrashReportNotificationsEnabled(value), false);

        ListPreference logLevelPref = findPreference("log_level");
        if (logLevelPref != null) {
            setLogLevelListPreferenceData(logLevelPref, context, prefs.getLogLevel());
            logLevelPref.setPersistent(false);
            logLevelPref.setOnPreferenceChangeListener((preference, newValue) -> {
                prefs.setLogLevel(context, Integer.parseInt((String) newValue));
                return true;
            });
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private void configureSwitch(String key, boolean current,
                                 PreferenceValueSetter<Boolean> setter, boolean affectsStyling) {
        SwitchPreferenceCompat pref = findPreference(key);
        if (pref == null) return;
        pref.setPersistent(false);
        pref.setChecked(current);
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean value = (Boolean) newValue;
            setter.set(value);
            if (affectsStyling) updateStyling();
            return true;
        });
    }

    private void configureSeekBarInt(String key, int current,
                                     PreferenceValueSetter<Integer> setter, boolean affectsStyling) {
        SeekBarPreference pref = findPreference(key);
        if (pref == null) return;
        pref.setPersistent(false);
        pref.setValue(current);
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            int value = (Integer) newValue;
            setter.set(value);
            if (affectsStyling) updateStyling();
            return true;
        });
    }

    private void configureListPreference(String key, String current, int valueArrayRes,
                                         PreferenceValueSetter<String> setter, boolean affectsStyling) {
        ListPreference pref = findPreference(key);
        if (pref == null) return;
        pref.setPersistent(false);
        if (current != null) pref.setValue(current);
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            String value = (String) newValue;
            setter.set(value);
            if (affectsStyling) updateStyling();
            return true;
        });
    }

    private void configureStringEditText(String key, String current,
                                         PreferenceValueSetter<String> setter, boolean affectsStyling) {
        EditTextPreference pref = findPreference(key);
        if (pref == null) return;
        pref.setPersistent(false);
        pref.setText(current);
        pref.setSummary(current != null && !current.isEmpty() ? current : getString(R.string.empty_value_summary));
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            String value = (String) newValue;
            setter.set(value);
            pref.setSummary(value != null && !value.isEmpty() ? value : getString(R.string.empty_value_summary));
            if (affectsStyling) updateStyling();
            return true;
        });
    }

    private void configureFloatEditText(String key, float current,
                                        PreferenceValueSetter<Float> setter, boolean affectsStyling) {
        EditTextPreference pref = findPreference(key);
        if (pref == null) return;
        pref.setPersistent(false);
        String currentStr = String.valueOf(current);
        pref.setText(currentStr);
        pref.setSummary(currentStr);
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            String raw = (String) newValue;
            float value;
            try {
                value = Float.parseFloat(raw);
            } catch (NumberFormatException e) {
                value = 1f;
            }
            if (value < 0.4f) value = 0.4f;
            if (value > 3f) value = 3f;
            String normalized = String.valueOf(value);
            pref.setText(normalized);
            pref.setSummary(normalized);
            setter.set(value);
            if (affectsStyling) updateStyling();
            return true;
        });
    }

    private void configureIntEditText(String key, int current,
                                     PreferenceValueSetter<Integer> setter, boolean affectsStyling) {
        EditTextPreference pref = findPreference(key);
        if (pref == null) return;
        pref.setPersistent(false);
        String currentStr = String.valueOf(current);
        pref.setText(currentStr);
        pref.setSummary(currentStr);
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            String raw = (String) newValue;
            int value;
            try {
                value = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                value = -1;
            }
            if (value < -1) value = -1;
            if (value > 100000) value = 100000;
            String normalized = String.valueOf(value);
            pref.setText(normalized);
            pref.setSummary(normalized);
            setter.set(value);
            if (affectsStyling) updateStyling();
            return true;
        });
    }

    private void configureShortcut(String key, String current) {
        EditTextPreference pref = findPreference(key);
        if (pref == null) return;
        pref.setPersistent(false);
        pref.setText(current);
        pref.setSummary(current != null && !current.isEmpty() ? current : getString(R.string.shortcut_empty_summary));
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            String value = (String) newValue;
            // Empty value disables the shortcut. Otherwise require "ctrl+<key>".
            if (!value.isEmpty() && !SHORTCUT_PATTERN.matcher(value).matches()) {
                Context context = getContext();
                if (context != null)
                    android.widget.Toast.makeText(context, R.string.shortcut_invalid_format,
                        android.widget.Toast.LENGTH_LONG).show();
                return false;
            }
            Context context = getContext();
            if (context != null) {
                TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(context, true);
                if (prefs != null) prefs.setShortcutString(key, value);
            }
            pref.setSummary(value != null && !value.isEmpty() ? value : getString(R.string.shortcut_empty_summary));
            // Shortcuts are re-read by setSessionShortcuts without recreating the Activity.
            Context ctx = getContext();
            if (ctx != null) TermuxActivity.updateTermuxActivityStyling(ctx, false);
            return true;
        });
    }

    private static final java.util.regex.Pattern SHORTCUT_PATTERN =
        java.util.regex.Pattern.compile("^ctrl\\+.$", java.util.regex.Pattern.CASE_INSENSITIVE);

    private void updateStyling() {
        Context context = getContext();
        if (context != null) TermuxActivity.updateTermuxActivityStyling(context, true);
    }

    private interface PreferenceValueSetter<T> {
        void set(T value);
    }

    // -----------------------------------------------------------------------
    //  TerminalEmulator cursor-style / bell-behaviour converters
    // -----------------------------------------------------------------------

    private String cursorStyleToString(int style) {
        switch (style) {
            case 1: return "underline";
            case 2: return "bar";
            default: return "block";
        }
    }

    private int stringToCursorStyle(String value) {
        if ("underline".equals(value)) return 1;
        if ("bar".equals(value)) return 2;
        return 0;
    }

    private String bellBehaviourToString(int behaviour) {
        switch (behaviour) {
            case 2: return "beep";
            case 3: return "ignore";
            default: return "vibrate";
        }
    }

    private int stringToBellBehaviour(String value) {
        if ("beep".equals(value)) return 2;
        if ("ignore".equals(value)) return 3;
        return 1;
    }

}
