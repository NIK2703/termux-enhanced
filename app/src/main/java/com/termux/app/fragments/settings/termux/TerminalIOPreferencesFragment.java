package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.fragments.settings.TermuxPreferenceFragmentBase;
import com.termux.shared.termux.extrakeys.KeyCombination;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * The single "Input" screen. Routes I/O preferences through
 * {@link TerminalIOPreferencesDataStore} and extra-keys visibility/hide-with-keyboard
 * through {@link TermuxAppSharedPreferences}.
 */
@Keep
public class TerminalIOPreferencesFragment extends TermuxPreferenceFragmentBase {

    /** The four session shortcuts, in the order they are shown. */
    private static final String[] SESSION_SHORTCUT_KEYS = {
        "shortcut.create-session",
        "shortcut.next-session",
        "shortcut.previous-session",
        "shortcut.rename-session"
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(new TerminalIOPreferencesDataStore(context));

        setPreferencesFromResource(R.xml.termux_terminal_io_preferences, rootKey);

        // "Never" greys out the rest of the section — see configureNeverDisablesSection.
        ListPreference extraKeysVisibility = findPreference("extra_keys_visibility");
        if (extraKeysVisibility != null) {
            configureNeverDisablesSection(extraKeysVisibility, "never");
        }

        SwitchPreferenceCompat textInputPref = findPreference("text_input_enabled");
        if (textInputPref != null) {
            textInputPref.setOnPreferenceChangeListener((preference, newValue) -> {
                android.content.Intent intent = new android.content.Intent("com.termux.TEXT_INPUT_ENABLED_CHANGED");
                intent.setPackage(context.getPackageName());
                context.sendBroadcast(intent);
                return true;
            });
        }

        // Hand-rolled AlertDialog (not a ListPreference), so useSimpleSummaryProvider cannot
        // reach it; the selected option's name is written onto the row here instead.
        final TermuxAppSharedPreferences appPrefs = TermuxAppSharedPreferences.build(context, true);
        Preference historyRestorePref = findPreference("text_input_insert_at_cursor");
        if (historyRestorePref != null && appPrefs != null) {
            historyRestorePref.setSummary(historyRestoreSummaryRes(appPrefs));
            historyRestorePref.setOnPreferenceClickListener(pref -> {
                showHistoryRestoreDialog(pref, appPrefs);
                return true;
            });
        }

        configureHistorySlider("message_history_max", 10, 100);

        // Session shortcuts are combinations, not free text, so they are picked with the same
        // dialog the extra-keys editor uses (minus the delay entry) instead of being typed.
        for (String key : SESSION_SHORTCUT_KEYS) {
            Preference shortcutPref = findPreference(key);
            if (shortcutPref == null || appPrefs == null) continue;
            shortcutPref.setPersistent(false);
            shortcutPref.setSummary(sessionShortcutSummary(appPrefs, key));
            shortcutPref.setOnPreferenceClickListener(pref -> {
                showSessionShortcutPicker(appPrefs, key, pref.getTitle());
                return true;
            });
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Registered on the view lifecycle owner so the result is not delivered to a dead view.
        // The picker is shown on the parent manager, which is the manager it reports back on.
        getParentFragmentManager().setFragmentResultListener(
            SignalPickerDialogFragment.REQUEST_KEY,
            getViewLifecycleOwner(),
            (requestKey, result) -> onSessionShortcutPicked(result));
    }

    // Session shortcuts

    /** The row summary: the bound combination, or the "not set" hint when nothing is bound. */
    @NonNull
    private String sessionShortcutSummary(@NonNull TermuxAppSharedPreferences prefs, @NonNull String key) {
        List<String> tokens = KeyCombination.parse(prefs.getShortcutString(key));
        if (tokens.isEmpty()) return getString(R.string.shortcut_empty_summary);
        return KeyCombination.toDisplayString(tokens);
    }

    private void showSessionShortcutPicker(@NonNull TermuxAppSharedPreferences prefs,
                                           @NonNull String key, @Nullable CharSequence title) {
        List<String> current = KeyCombination.parse(prefs.getShortcutString(key));
        SignalPickerDialogFragment.newInstanceForSessionShortcut(
                key, title != null ? title.toString() : key, current)
            .show(getParentFragmentManager(), "session_shortcut_picker");
    }

    private void onSessionShortcutPicked(@NonNull Bundle result) {
        String key = result.getString(SignalPickerDialogFragment.RESULT_REQUEST_ID);
        if (key == null || key.isEmpty()) return;

        Context context = getContext();
        if (context == null) return;
        TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(context, true);
        if (prefs == null) return;

        ArrayList<String> signals = result.getStringArrayList(SignalPickerDialogFragment.RESULT_SIGNALS);
        // An empty selection clears the binding — that is how a shortcut is turned off.
        prefs.setShortcutString(key, signals == null || signals.isEmpty() ? "" : KeyCombination.format(signals));

        Preference row = findPreference(key);
        if (row != null) row.setSummary(sessionShortcutSummary(prefs, key));

        // Shortcuts are re-read by setSessionShortcuts without recreating the Activity.
        TermuxActivity.updateTermuxActivityStyling(context, false);
    }

    /**
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

    private void configureHistorySlider(String key, int min, int max) {
        SeekBarPreference slider = findPreference(key);
        if (slider == null) return;
        slider.setOnPreferenceChangeListener((preference, newValue) -> {
            int value = (Integer) newValue;
            if (value < min) {
                value = min;
                slider.setValue(value);
            }
            return true;
        });
    }

    /** The resource of whichever {@code text_input_insert_at_cursor} option is currently selected. */
    private static int historyRestoreSummaryRes(@NonNull TermuxAppSharedPreferences prefs) {
        return prefs.shouldInsertAtCursorOnHistoryPick()
                ? R.string.text_input_restore_mode_option_insert
                : R.string.text_input_restore_mode_option_replace;
    }

    private void showHistoryRestoreDialog(@NonNull Preference pref,
                                          @NonNull TermuxAppSharedPreferences prefs) {
        Context context = getContext();
        if (context == null) return;

        String[] items = {
                context.getString(R.string.text_input_restore_mode_option_replace),
                context.getString(R.string.text_input_restore_mode_option_insert)
        };
        int checkedItem = prefs.shouldInsertAtCursorOnHistoryPick() ? 1 : 0;

        new androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.text_input_restore_mode_dialog_title)
                .setSingleChoiceItems(items, checkedItem, (dialog, which) -> {
                    prefs.setInsertAtCursorOnHistoryPick(which == 1);
                    pref.setSummary(historyRestoreSummaryRes(prefs));
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

}

class TerminalIOPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final TermuxAppSharedPreferences mPreferences;

    public TerminalIOPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = TermuxAppSharedPreferences.build(context, true);
    }

    private android.content.SharedPreferences getTermuxPrefs() {
        return mContext.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
    }

    @Override
    public void putBoolean(String key, boolean value) {
        if (mPreferences == null) return;
        if (key == null) return;

        switch (key) {
            case "soft_keyboard_enabled":
                    mPreferences.setSoftKeyboardEnabled(value);
                break;
            case "soft_keyboard_enabled_only_if_no_hardware":
                mPreferences.setSoftKeyboardEnabledOnlyIfNoHardware(value);
                break;
            case "keyboard_state_follow_tab_switch":
                mPreferences.setKeyboardStateFollowTabSwitch(value);
                break;
            case "back-key":
                mPreferences.setBackKeyBehaviour(value
                    ? TermuxPropertyConstants.IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE
                    : TermuxPropertyConstants.IVALUE_BACK_KEY_BEHAVIOUR_BACK);
                break;
            case "volume-keys":
                mPreferences.setVolumeKeysBehaviour(value
                    ? TermuxPropertyConstants.IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL
                    : TermuxPropertyConstants.IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME);
                break;
            case "ctrl-space-workaround":
                mPreferences.setCtrlSpaceWorkaround(value);
                break;
            case "enforce-char-based-input":
                mPreferences.setEnforceCharBasedInput(value);
                break;
            case "disable-hardware-keyboard-shortcuts":
                mPreferences.setHardwareKeyboardShortcutsDisabled(value);
                break;
            case "terminal-onclick-url-open":
                mPreferences.setOpenTerminalTranscriptURLOnClick(value);
                break;
            case "hide-soft-keyboard-on-startup":
                mPreferences.setSoftKeyboardHiddenOnStartup(value);
                break;
            case "text_input_enabled":
                getTermuxPrefs().edit().putBoolean("text_input_enabled", value).apply();
                break;
            case "text_input_append_enter":
                if (mPreferences != null) mPreferences.setTextInputAppendEnter(value);
                break;
            case "per_directory_message_history":
                getTermuxPrefs().edit().putBoolean("per_directory_message_history", value).apply();
                break;
            case "save_cleared_to_history":
                getTermuxPrefs().edit().putBoolean("save_cleared_to_history", value).apply();
                break;
            default:
                break;
        }
    }

    @Override
    public void putInt(String key, int value) {
        if (key == null) return;
        switch (key) {
            case "suggestions_max_count":
                getTermuxPrefs().edit().putInt("suggestions_max_count", value).apply();
                break;
            case "message_history_max":
                getTermuxPrefs().edit().putInt("message_history_max", value).apply();
                break;
            default:
                break;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        if (mPreferences == null) return false;

        switch (key) {
            case "soft_keyboard_enabled":
                return mPreferences.isSoftKeyboardEnabled();
            case "soft_keyboard_enabled_only_if_no_hardware":
                return mPreferences.isSoftKeyboardEnabledOnlyIfNoHardware();
            case "keyboard_state_follow_tab_switch":
                return mPreferences.isKeyboardStateFollowTabSwitch();
            case "back-key":
                return mPreferences.isBackKeyTheEscapeKey();
            case "volume-keys":
                return !mPreferences.areVirtualVolumeKeysDisabled();
            case "ctrl-space-workaround":
                return mPreferences.isUsingCtrlSpaceWorkaround();
            case "enforce-char-based-input":
                return mPreferences.isEnforcingCharBasedInput();
            case "disable-hardware-keyboard-shortcuts":
                return mPreferences.areHardwareKeyboardShortcutsDisabled();
            case "terminal-onclick-url-open":
                return mPreferences.shouldOpenTerminalTranscriptURLOnClick();
            case "hide-soft-keyboard-on-startup":
                return mPreferences.shouldSoftKeyboardBeHiddenOnStartup();
            case "text_input_enabled":
                return getTermuxPrefs().getBoolean("text_input_enabled", true);
            case "text_input_append_enter":
                return mPreferences != null && mPreferences.shouldTextInputAppendEnter();
            case "per_directory_message_history":
                return getTermuxPrefs().getBoolean("per_directory_message_history", false);
            case "save_cleared_to_history":
                return getTermuxPrefs().getBoolean("save_cleared_to_history", true);
            default:
                return false;
        }
    }

    @Override
    public int getInt(String key, int defValue) {
        if (key == null) return defValue;
        switch (key) {
            case "suggestions_max_count":
                return getTermuxPrefs().getInt("suggestions_max_count", 4);
            case "message_history_max":
                return getTermuxPrefs().getInt("message_history_max", 20);
            default:
                return defValue;
        }
    }

    @Override
    public String getString(String key, String defValue) {
        if (key == null || mPreferences == null) return defValue;
        switch (key) {
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE:
                return mPreferences.getExtraKeysSpecialButtonMode();
            case "extra-keys-haptic":
                int hapticVal = mPreferences.getExtraKeysHaptic();
                String hapticStr = TermuxPropertyConstants.MAP_EXTRA_KEYS_HAPTIC.inverse().get(hapticVal);
                return hapticStr != null ? hapticStr : TermuxPropertyConstants.DEFAULT_VALUE_EXTRA_KEYS_HAPTIC;
            case "extra_keys_visibility":
                if (mPreferences == null) return "keyboard";
                if (!mPreferences.shouldShowTerminalToolbar()) return "never";
                return mPreferences.shouldHideExtraKeysWithKeyboard() ? "keyboard" : "always";
            case "text_input_action_on_send":
                return mPreferences != null ? mPreferences.getTextInputActionOnSend() : "hide_keyboard";
            case "soft-keyboard-toggle-behaviour":
                return mPreferences != null ? mPreferences.getSoftKeyboardToggleBehaviour() : "show/hide";
            default:
                return defValue;
        }
    }

    @Override
    public void putString(String key, String value) {
        if (key == null || mPreferences == null) return;
        switch (key) {
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE:
                mPreferences.setExtraKeysSpecialButtonMode(value);
                TermuxActivity.updateTermuxActivityStyling(mContext, true);
                break;
            case "extra-keys-haptic":
                Integer mappedHaptic = TermuxPropertyConstants.MAP_EXTRA_KEYS_HAPTIC.get(value);
                mPreferences.setExtraKeysHaptic(mappedHaptic != null ? mappedHaptic : TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_HAPTIC);
                TermuxActivity.updateTermuxActivityStyling(mContext, true);
                break;
            case "extra_keys_visibility":
                if ("never".equals(value)) {
                    mPreferences.setShowTerminalToolbar(false);
                } else if ("keyboard".equals(value)) {
                    mPreferences.setShowTerminalToolbar(true);
                    mPreferences.setHideExtraKeysWithKeyboard(true);
                } else {
                    mPreferences.setShowTerminalToolbar(true);
                    mPreferences.setHideExtraKeysWithKeyboard(false);
                }
                TermuxActivity.updateTermuxActivityStyling(mContext, true);
                break;
            case "text_input_action_on_send":
                if (mPreferences != null) mPreferences.setTextInputActionOnSend(String.valueOf(value));
                break;
            case "soft-keyboard-toggle-behaviour":
                if (mPreferences != null) mPreferences.setSoftKeyboardToggleBehaviour(value);
                break;
            default:
                break;
        }
    }

}
