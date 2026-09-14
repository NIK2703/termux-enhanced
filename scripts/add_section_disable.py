"""Add value-based section disabling to TerminalIOPreferencesFragment.

extra_keys_visibility is a ListPreference, so it cannot drive app:dependency
(that only reacts to a switch's checked state). Its "Никогда" value disables the
rest of its section from code instead.
"""
import io

PATH = (r"E:\projects\termux-enhanced\app\src\main\java\com\termux\app\fragments\settings"
        r"\termux\TerminalIOPreferencesFragment.java")

txt = open(PATH, encoding="utf-8", newline="").read()
had_crlf = "\r\n" in txt
txt = txt.replace("\r\n", "\n")

IMPORTS_OLD = """import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
"""
IMPORTS_NEW = """import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceGroup;
"""
assert IMPORTS_OLD in txt, "imports anchor not found"
txt = txt.replace(IMPORTS_OLD, IMPORTS_NEW, 1)

CALL_ANCHOR = '        SwitchPreferenceCompat textInputPref = findPreference("text_input_enabled");'
CALL_NEW = '''        // "Никогда" on the extra-keys row disables the rest of its section. A ListPreference
        // cannot drive app:dependency (that only reacts to a switch's checked state), so the
        // section is greyed out from here instead. The master row always stays enabled.
        ListPreference extraKeysVisibility = findPreference("extra_keys_visibility");
        if (extraKeysVisibility != null) {
            configureNeverDisablesSection(extraKeysVisibility, "never");
        }

'''
assert CALL_ANCHOR in txt, "call anchor not found"
txt = txt.replace(CALL_ANCHOR, CALL_NEW + CALL_ANCHOR, 1)

METHOD_ANCHOR = "    private void configureHistorySlider(String key, int min, int max) {"
METHODS = '''    /**
     * Greys out every other row of a section while its master row holds {@code neverValue}.
     * A ListPreference cannot drive app:dependency - that only reacts to a switch's checked
     * state - so value-based disabling has to be done here. The master row itself is always
     * left enabled, since it is the only way back out of the disabled state.
     */
    private void configureNeverDisablesSection(@NonNull ListPreference master,
                                               @NonNull String neverValue) {
        PreferenceGroup section = master.getParent();
        if (section == null) return;

        setSectionRowsEnabled(section, master, !neverValue.equals(master.getValue()));
        master.setOnPreferenceChangeListener((preference, newValue) -> {
            setSectionRowsEnabled(section, master, !neverValue.equals(String.valueOf(newValue)));
            return true;
        });
    }

    private static void setSectionRowsEnabled(@NonNull PreferenceGroup section,
                                              @NonNull Preference master, boolean enabled) {
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            Preference row = section.getPreference(i);
            if (row == master) continue;
            row.setEnabled(enabled);
        }
    }

'''
assert METHOD_ANCHOR in txt, "method anchor not found"
txt = txt.replace(METHOD_ANCHOR, METHODS + METHOD_ANCHOR, 1)

if had_crlf:
    txt = txt.replace("\n", "\r\n")
open(PATH, "w", encoding="utf-8", newline="").write(txt)
print("java updated; crlf preserved:", had_crlf)
