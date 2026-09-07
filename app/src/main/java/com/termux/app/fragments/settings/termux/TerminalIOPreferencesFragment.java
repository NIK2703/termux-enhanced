package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceManager;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.fragments.settings.TermuxPreferenceFragmentBase;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

/**
 * The single "Input" screen. Routes I/O preferences through
 * {@link TerminalIOPreferencesDataStore} and extra-keys visibility/hide-with-keyboard
 * through {@link TermuxAppSharedPreferences}.
 */
@Keep
public class TerminalIOPreferencesFragment extends TermuxPreferenceFragmentBase {

    private static final String LOG_TAG = "TerminalIOPrefsFragment";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(new TerminalIOPreferencesDataStore(context));

        setPreferencesFromResource(R.xml.termux_terminal_io_preferences, rootKey);

        SwitchPreferenceCompat textInputPref = findPreference("text_input_enabled");
        if (textInputPref != null) {
            textInputPref.setOnPreferenceChangeListener((preference, newValue) -> {
                android.content.Intent intent = new android.content.Intent("com.termux.TEXT_INPUT_ENABLED_CHANGED");
                intent.setPackage(context.getPackageName());
                context.sendBroadcast(intent);
                return true;
            });
        }

        Preference historyRestorePref = findPreference("text_input_insert_at_cursor");
        if (historyRestorePref != null) {
            historyRestorePref.setOnPreferenceClickListener(pref -> {
                showHistoryRestoreDialog();
                return true;
            });
        }

        configureHistorySlider("message_history_max", 10, 100);
        configureHistorySlider("directory_history_max", 10, 100);
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

    private void showHistoryRestoreDialog() {
        Context context = getContext();
        if (context == null) return;

        TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(context, true);

        String[] items = {
                context.getString(R.string.text_input_insert_at_cursor_option_replace),
                context.getString(R.string.text_input_insert_at_cursor_option_insert)
        };
        int checkedItem = prefs.shouldInsertAtCursorOnHistoryPick() ? 1 : 0;

        new androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.text_input_insert_at_cursor_dialog_title)
                .setSingleChoiceItems(items, checkedItem, (dialog, which) -> {
                    prefs.setInsertAtCursorOnHistoryPick(which == 1);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

}


class TerminalIOPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final TermuxAppSharedPreferences mPreferences;

    private static TerminalIOPreferencesDataStore mInstance;

    public TerminalIOPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = TermuxAppSharedPreferences.build(context, true);
    }

    public static synchronized TerminalIOPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new TerminalIOPreferencesDataStore(context);
        }
        return mInstance;
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
            case "text_input_enabled":
                getTermuxPrefs().edit().putBoolean("text_input_enabled", value).apply();
                break;
            case "text_input_append_enter":
                if (mPreferences != null) mPreferences.setTextInputAppendEnter(value);
                break;
            case "text_input_insert_at_cursor":
                if (mPreferences != null) mPreferences.setInsertAtCursorOnHistoryPick(value);
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
            case "directory_history_max":
                getTermuxPrefs().edit().putInt("directory_history_max", value).apply();
                break;
            case "terminal-toolbar-height":
                mPreferences.setTerminalToolbarHeightScaleFactor(((Integer) value) / 100f);
                TermuxActivity.updateTermuxActivityStyling(mContext, true);
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
            case "text_input_enabled":
                return getTermuxPrefs().getBoolean("text_input_enabled", true);
            case "text_input_append_enter":
                return mPreferences != null && mPreferences.shouldTextInputAppendEnter();
            case "text_input_insert_at_cursor":
                return mPreferences != null && mPreferences.shouldInsertAtCursorOnHistoryPick();
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
            case "directory_history_max":
                return getTermuxPrefs().getInt("directory_history_max", 20);
            case "terminal-toolbar-height":
                return mPreferences != null ? Math.round(mPreferences.getTerminalToolbarHeightScaleFactor() * 100f) : 100;
            default:
                return defValue;
        }
    }

    @Override
    public String getString(String key, String defValue) {
        if (key == null || mPreferences == null) return defValue;
        switch (key) {
            case TermuxPropertyConstants.KEY_EXTRA_KEYS:
                return mPreferences.getExtraKeys();
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
            default:
                return defValue;
        }
    }

    @Override
    public void putString(String key, String value) {
        if (key == null || mPreferences == null) return;
        switch (key) {
            case TermuxPropertyConstants.KEY_EXTRA_KEYS:
                mPreferences.setExtraKeys(value);
                TermuxActivity.updateTermuxActivityStyling(mContext, true);
                break;
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
            default:
                break;
        }
    }

}
