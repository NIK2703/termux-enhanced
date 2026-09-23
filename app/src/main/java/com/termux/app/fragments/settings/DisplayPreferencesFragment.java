package com.termux.app.fragments.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.bubble.TermuxBubbleManager;
import com.termux.app.terminal.TermuxActivityBroadcastManager;
import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants;
import com.termux.shared.theme.NightMode;

import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single "Display" screen. Subsections:
 * Theme, Language, Panel Transparency (former Appearance),
 * View, Window (former Display),
 * Tabs (moved here from the deleted Sessions screen).
 *
 * No global PreferenceDataStore is used — every persisted key is wired with an
 * explicit OnPreferenceChangeListener (and setPersistent(false) where needed) so
 * values land in the correct backing store (termux.properties / termux_prefs /
 * TermuxAppSharedPreferences) without relying on the framework's default persist.
 */
@Keep
public class DisplayPreferencesFragment extends TermuxPreferenceFragmentBase {

    /** The blur-radius slider. A radius of 0 disables the wallpaper blur; any positive value
     *  enables it. Kept as a field so the transparency slider can enable or disable it (blur is
     *  meaningless behind an opaque terminal background). */
    private SeekBarPreference mBlurRadiusPref;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setPreferencesFromResource(R.xml.termux_display_preferences, rootKey);

        // --- Theme ---
        final TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(context, true);

        configureColorSchemePreference("color_scheme_light", false);
        configureColorSchemePreference("color_scheme_dark", true);

        final ListPreference themePref = findPreference("theme_mode");
        if (themePref != null) {
            themePref.setPersistent(false);
            String currentValue = prefs != null ? prefs.getNightMode() : "system";
            themePref.setValue(!android.text.TextUtils.isEmpty(currentValue) ? currentValue : "system");

            themePref.setOnPreferenceChangeListener((preference, newValue) -> {
                String val = (String) newValue;
                if (prefs != null) prefs.setNightMode(val);
                NightMode.setAppNightMode(val);
                Context ctx = getContext();
                if (ctx != null) {
                    TermuxActivity.updateTermuxActivityStyling(ctx, true);
                }
                AppCompatActivity activity = (AppCompatActivity) getActivity();
                if (activity != null) {
                    activity.recreate();
                }
                return true;
            });
        }

        // --- Transparency ---
        SeekBarPreference inactiveSlider = findPreference("button_bg_inactive_alpha");
        if (inactiveSlider != null) {
            inactiveSlider.setMin(0);
            inactiveSlider.setMax(10);
        }
        SeekBarPreference activeSlider = findPreference("button_bg_active_alpha");
        if (activeSlider != null) {
            activeSlider.setMin(10);
            activeSlider.setMax(20);
        }
        wireSliderListener("button_bg_inactive_alpha", context);
        wireSliderListener("button_bg_active_alpha", context);

        // --- Window: screen orientation ---
        final ListPreference orientationPref = findPreference("screen_orientation");
        if (orientationPref != null) {
            orientationPref.setPersistent(false);
            final SharedPreferences termuxPrefs = termuxPrefs();
            final boolean isTablet = requireContext().getResources()
                    .getConfiguration().smallestScreenWidthDp >= 600;
            orientationPref.setValue(termuxPrefs.getString("screen_orientation", isTablet ? "sensor" : "portrait"));
            orientationPref.setOnPreferenceChangeListener((preference, newValue) -> {
                termuxPrefs.edit().putString("screen_orientation", (String) newValue).apply();
                final androidx.fragment.app.FragmentActivity activity = getActivity();
                if (activity != null) {
                    // Applied to the window this screen is drawn in — in the bubble that means no
                    // preference, so the display is not rotated under it.
                    TermuxActivity.applyScreenOrientation(activity);
                }
                return true;
            });
        }

        // --- Window: fullscreen ---
        configureSwitch("fullscreen", prefs != null && prefs.isUsingFullScreen(),
            value -> { if (prefs != null) prefs.setFullScreen(value); });

        // --- Window: bubble on background ---
        configureSwitch("bubble-on-background", prefs != null && prefs.isBubbleOnBackgroundEnabled(),
            value -> { if (prefs != null) prefs.setBubbleOnBackgroundEnabled(value); });
        configureBubbleOnBackgroundSupport();

        // --- Terminal appearance (moved from Terminal screen) ---
        configureTerminalAppearancePreferences(prefs);

        // --- Tabs ---
        configureTabListPrefWithBroadcast("tab_panel_position", "top", "com.termux.TAB_PANEL_POSITION_CHANGED");
        configureTabListPrefWithBroadcast("tab_height_mode", "single", "com.termux.TAB_HEIGHT_MODE_CHANGED");
        configureTermuxPrefsSwitch("swipe_rightmost_new_tab", true);
        configureTermuxPrefsSwitch("restore_sessions", false);
        configureDirectoryHistoryMaxPreference();
    }

    // Terminal appearance

    private void configureTerminalAppearancePreferences(TermuxAppSharedPreferences prefs) {
        configureIntEditDialog("terminal-transcript-rows",
            prefs.getTerminalTranscriptRows(),
            value -> prefs.setTerminalTranscriptRows(value),
            R.string.terminal_transcript_rows_title,
            TermuxPreferenceConstants.TERMUX_APP.MIN_TERMINAL_TRANSCRIPT_ROWS,
            TermuxPreferenceConstants.TERMUX_APP.MAX_TERMINAL_TRANSCRIPT_ROWS);

        final ListPreference cursorPref = findPreference("terminal-cursor-style");
        if (cursorPref != null) {
            cursorPref.setPersistent(false);
            cursorPref.setValue(cursorStyleToString(prefs.getTerminalCursorStyle()));
            cursorPref.setOnPreferenceChangeListener((preference, newValue) -> {
                prefs.setTerminalCursorStyle(stringToCursorStyle((String) newValue));
                updateStyling();
                return true;
            });
        }

        // --- Cursor blink enabled ---
        final SwitchPreferenceCompat blinkPref = findPreference("terminal-cursor-blink-enabled");
        if (blinkPref != null) {
            blinkPref.setPersistent(false);
            blinkPref.setChecked(prefs.getTerminalCursorBlinkEnabled());
            blinkPref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enabled = (Boolean) newValue;
                prefs.setTerminalCursorBlinkEnabled(enabled);
                Preference ratePref = findPreference("terminal-cursor-blink-rate");
                if (ratePref != null) ratePref.setEnabled(enabled);
                updateStyling();
                return true;
            });
        }

        configureIntEditDialog("terminal-cursor-blink-rate",
            prefs.getTerminalCursorBlinkRate(),
            value -> prefs.setTerminalCursorBlinkRate(value),
            R.string.terminal_cursor_blink_rate_title,
            TermuxPreferenceConstants.TERMUX_APP.MIN_TERMINAL_CURSOR_BLINK_RATE,
            TermuxPreferenceConstants.TERMUX_APP.MAX_TERMINAL_CURSOR_BLINK_RATE);

        // Sync initial rate pref enabled state
        Preference ratePref = findPreference("terminal-cursor-blink-rate");
        if (ratePref != null) ratePref.setEnabled(prefs.getTerminalCursorBlinkEnabled());

        configureSeekBarInt("terminal-margin-left", prefs.getTerminalMarginLeft(),
            value -> prefs.setTerminalMarginLeft(value));

        configureSeekBarInt("terminal-margin-top", prefs.getTerminalMarginTop(),
            value -> prefs.setTerminalMarginTop(value));

        configureSeekBarInt("terminal-margin-right", prefs.getTerminalMarginRight(),
            value -> prefs.setTerminalMarginRight(value));

        configureSeekBarInt("terminal-margin-bottom", prefs.getTerminalMarginBottom(),
            value -> prefs.setTerminalMarginBottom(value));

        configureBackgroundTransparencySeekBar(prefs);
        configureBackgroundBlurRadiusSeekBar(prefs);
        updateBackgroundBlurPrefState(prefs);

        configureFontSizeSeekBar(prefs);

        configureSwitch("scroll-on-new-output", prefs.isScrollOnNewOutputEnabled(),
            value -> prefs.setScrollOnNewOutputEnabled(value));
    }

    /**
     * Font-size slider: same {@code fontsize} bounds as the pinch gesture, so the slider cannot
     * reach a size the gesture cannot; the density-derived (4dp) lower bound must be applied here,
     * not in the XML. {@link TermuxAppSharedPreferences#FONT_SIZE_STEP} only affects keyboard/DPAD
     * stepping — a finger drag stays at full resolution so thumb and number cannot desync.
     * Deliberately <em>not</em> {@link #configureSeekBarInt}: that helper recreates the activity on
     * every value; here one broadcast is sent on finger release (default
     * {@code updatesContinuously = false}), not per frame.
     */
    private void configureFontSizeSeekBar(TermuxAppSharedPreferences prefs) {
        final SeekBarPreference pref = findPreference("terminal-font-size");
        if (pref == null) return;

        pref.setMin(prefs.getMinFontSize());
        pref.setMax(prefs.getMaxFontSize());
        pref.setSeekBarIncrement(TermuxAppSharedPreferences.FONT_SIZE_STEP);
        pref.setPersistent(false);
        pref.setValue(prefs.getFontSize());

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            prefs.setFontSize((Integer) newValue);
            TermuxActivityBroadcastManager.notifyFontSizeChanged(requireContext());
            return true;
        });
    }

    private void configureSeekBarInt(String key, int current,
                                     PreferenceValueSetter<Integer> setter) {
        final SeekBarPreference pref = findPreference(key);
        if (pref == null) return;
        pref.setPersistent(false);
        // The seek bar max is defined in the XML preference; clamp any previously
        // stored value (e.g. from an older build with a higher max) so the terminal
        // never keeps an out-of-range margin.
        current = clampToMax(current, pref.getMax(), setter);
        pref.setValue(current);
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            setter.set((Integer) newValue);
            updateStyling();
            return true;
        });
    }

    /**
     * Enable/disable the blur-radius slider: usable only on Android 12+ with non-zero background
     * transparency (nothing behind the terminal to blur at 0%). The radius itself ({@code > 0})
     * decides whether blur is on, so no separate switch is needed.
     */
    private void updateBackgroundBlurPrefState(TermuxAppSharedPreferences prefs) {
        final boolean blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        final boolean hasWallpaper = prefs.getTerminalBackgroundTransparency() > 0;
        final boolean blurUsable = blurSupported && hasWallpaper;

        if (mBlurRadiusPref != null) {
            mBlurRadiusPref.setEnabled(blurUsable);
        }
    }

    /**
     * Wallpaper-blur radius slider (Android 12+ {@code setBlurBehindRadius}, pixels).
     *
     * Like the blur switch, only window flags change — no activity recreate — so the reload is
     * broadcast with {@code recreate=false} and the new radius is applied live. The slider is
     * disabled unless blur is actually on (see {@link #updateBackgroundBlurPrefState}).
     */
    private void configureBackgroundBlurRadiusSeekBar(TermuxAppSharedPreferences prefs) {
        mBlurRadiusPref = findPreference(TermuxPreferenceConstants.TERMUX_APP.KEY_TERMINAL_BACKGROUND_BLUR_RADIUS);
        if (mBlurRadiusPref == null) return;

        mBlurRadiusPref.setPersistent(false);
        // Older build with a higher max — clamp so an out-of-range radius never reaches setBlurBehindRadius.
        int current = clampToMax(prefs.getTerminalBackgroundBlurRadius(), mBlurRadiusPref.getMax(),
            prefs::setTerminalBackgroundBlurRadius);
        mBlurRadiusPref.setValue(current);
        mBlurRadiusPref.setOnPreferenceChangeListener((preference, newValue) -> {
            prefs.setTerminalBackgroundBlurRadius((Integer) newValue);
            Context ctx = getContext();
            if (ctx != null) TermuxActivity.updateTermuxActivityStyling(ctx, false);
            return true;
        });
    }

    /**
     * Background-transparency slider (real device wallpaper behind the terminal).
     *
     * Deliberately <em>not</em> {@link #configureSeekBarInt}: recreating on every frame of a drag
     * is unusable. The value is written straight away and a styling reload <em>without</em>
     * recreate is broadcast, so the change is live. {@code TermuxActivity} still recreates itself
     * when the value crosses the 0% boundary, because {@code android:windowIsTranslucent} is a
     * static theme attribute.
     */
    private void configureBackgroundTransparencySeekBar(TermuxAppSharedPreferences prefs) {
        final SeekBarPreference pref =
            findPreference(TermuxPreferenceConstants.TERMUX_APP.KEY_TERMINAL_BACKGROUND_TRANSPARENCY);
        if (pref == null) return;

        pref.setPersistent(false);
        // Older build with a higher max — clamp so the terminal never keeps an out-of-range value.
        int current = clampToMax(prefs.getTerminalBackgroundTransparency(), pref.getMax(),
            prefs::setTerminalBackgroundTransparency);
        pref.setValue(current);

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            prefs.setTerminalBackgroundTransparency((Integer) newValue);
            // Crossing the 0% boundary also flips the usefulness of the blur switch.
            updateBackgroundBlurPrefState(prefs);
            Context ctx = getContext();
            if (ctx != null) TermuxActivity.updateTermuxActivityStyling(ctx, false);
            return true;
        });
    }

    private void configureIntEditDialog(String key, int current,
                                         PreferenceValueSetter<Integer> setter,
                                         int titleRes, int min, int max) {
        final Preference pref = findPreference(key);
        if (pref == null) return;
        pref.setPersistent(false);
        updateIntEditSummary(pref, current);

        AtomicInteger currentRef = new AtomicInteger(current);
        pref.setOnPreferenceClickListener(preference -> {
            if (!preference.isEnabled()) return false;
            Context ctx = getContext();
            if (ctx == null) return true;

            EditText input = new EditText(ctx);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setText(String.valueOf(currentRef.get()));
            input.setSelection(input.getText().length());

            new AlertDialog.Builder(ctx)
                .setTitle(titleRes)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    try {
                        int value = Integer.parseInt(input.getText().toString());
                        if (value < min) value = min;
                        if (value > max) value = max;
                        setter.set(value);
                        currentRef.set(value);
                        updateIntEditSummary(pref, currentRef.get());
                        updateStyling();
                    } catch (NumberFormatException e) {
                        Toast.makeText(ctx, R.string.invalid_number, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return true;
        });
    }

    private void updateIntEditSummary(Preference pref, int value) {
        pref.setSummary(String.valueOf(value));
    }

    private void configureSwitch(String key, boolean current,
                                  PreferenceValueSetter<Boolean> setter) {
        final SwitchPreferenceCompat pref = findPreference(key);
        if (pref == null) return;
        pref.setPersistent(false);
        pref.setChecked(current);
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            setter.set((Boolean) newValue);
            updateStyling();
            return true;
        });
    }

    private interface PreferenceValueSetter<T> {
        void set(T value);
    }

    private static int clampToMax(int current, int max, PreferenceValueSetter<Integer> setter) {
        if (current > max) {
            current = max;
            setter.set(current);
        }
        return current;
    }

    private SharedPreferences termuxPrefs() {
        return requireContext().getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
    }

    /**
     * Deactivate (not hide) the "bubble on background" switch where
     * {@link TermuxBubbleManager#isSupported} is false — Android version, the framework bubble
     * API, the Android Go / low-RAM case; deliberately <em>not</em> the user's per-app bubble
     * preference, since this switch is what turns the automatic bubble on and must stay usable on
     * the way to system settings. The stored value is left alone — the feature is gated off at
     * every entry point anyway.
     */
    private void configureBubbleOnBackgroundSupport() {
        final SwitchPreferenceCompat pref = findPreference("bubble-on-background");
        if (pref == null) return;
        pref.setEnabled(TermuxBubbleManager.isSupported(requireContext()));
    }

    private void updateStyling() {
        Context ctx = getContext();
        if (ctx != null) TermuxActivity.updateTermuxActivityStyling(ctx, true);
    }

    private static String cursorStyleToString(int style) {
        switch (style) {
            case 1: return "underline";
            case 2: return "bar";
            default: return "block";
        }
    }

    private static int stringToCursorStyle(String value) {
        if ("underline".equals(value)) return 1;
        if ("bar".equals(value)) return 2;
        return 0;
    }

    private void configureDirectoryHistoryMaxPreference() {
        final androidx.preference.SeekBarPreference pref = findPreference("directory_history_max");
        if (pref == null) return;

        final SharedPreferences termuxPrefs = termuxPrefs();
        pref.setPersistent(false);
        int current = termuxPrefs.getInt("directory_history_max", 20);
        if (current < 10) current = 10;
        if (current > 100) current = 100;
        pref.setValue(current);

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            int value = (Integer) newValue;
            if (value < 10) {
                value = 10;
                pref.setValue(value);
            }
            termuxPrefs.edit().putInt("directory_history_max", value).apply();
            return true;
        });
    }

    // Colour-scheme selection (light/dark)

    private void configureColorSchemePreference(String key, boolean isNight) {
        final Preference pref = findPreference(key);
        if (pref == null) return;

        updateColorSchemeSummary(pref, isNight);

        pref.setOnPreferenceClickListener(preference -> {
            Context ctx = getContext();
            if (ctx == null) return true;
                ColorSchemeUtils.showColorSchemeDialog(ctx, isNight, pref.getTitle(),
                getString(R.string.error_styling_not_installed), () -> {
                    updateColorSchemeSummary(pref, isNight);
                    TermuxActivity.updateTermuxActivityStyling(ctx, false);
                });
            return true;
        });
    }

    private void updateColorSchemeSummary(Preference pref, boolean isNight) {
        pref.setSummary(ColorSchemeUtils.schemeDisplayName(
            ColorSchemeUtils.getSelectedSchemeName(isNight)));
    }

    // Transparency-slider listeners

    private void wireSliderListener(String key, Context context) {
        SeekBarPreference slider = findPreference(key);
        if (slider == null) return;
        slider.setOnPreferenceChangeListener((preference, newValue) -> {
            TermuxActivity.updateTermuxActivityStyling(context, false);
            return true;
        });
    }

    // Tabs

    private void configureTermuxPrefsSwitch(String key, boolean defaultValue) {
        final SwitchPreferenceCompat pref = findPreference(key);
        if (pref == null) return;

        final SharedPreferences termuxPrefs = termuxPrefs();
        pref.setPersistent(false);
        pref.setChecked(termuxPrefs.getBoolean(key, defaultValue));

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            termuxPrefs.edit().putBoolean(key, (Boolean) newValue).apply();
            return true;
        });
    }

    private void configureTabListPrefWithBroadcast(String key, String defaultValue, String action) {
        final ListPreference pref = findPreference(key);
        if (pref == null) return;

        final SharedPreferences termuxPrefs = termuxPrefs();
        pref.setPersistent(false);
        pref.setValue(termuxPrefs.getString(key, defaultValue));

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            termuxPrefs.edit().putString(key, (String) newValue).apply();
            final Context context = requireContext();
            final Intent intent = new Intent(action);
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);
            return true;
        });
    }

}
