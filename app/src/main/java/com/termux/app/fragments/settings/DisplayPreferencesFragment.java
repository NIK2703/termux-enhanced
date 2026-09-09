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

    /**
     * The wallpaper-blur switch. Kept as a field so the transparency slider can enable or
     * disable it (blur is meaningless behind an opaque terminal background).
     */
    private SwitchPreferenceCompat mBlurPref;

    /** The blur-radius slider. Kept as a field so the blur switch can enable or disable it. */
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
            final SharedPreferences termuxPrefs =
                    requireContext().getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
            final boolean isTablet = requireContext().getResources()
                    .getConfiguration().smallestScreenWidthDp >= 600;
            orientationPref.setValue(termuxPrefs.getString("screen_orientation", isTablet ? "sensor" : "portrait"));
            orientationPref.setOnPreferenceChangeListener((preference, newValue) -> {
                termuxPrefs.edit().putString("screen_orientation", (String) newValue).apply();
                final androidx.fragment.app.FragmentActivity activity = getActivity();
                if (activity != null) {
                    TermuxActivity.applyScreenOrientation(activity);
                }
                return true;
            });
        }

        // --- Window: fullscreen ---
        configureSwitch("fullscreen", prefs != null && prefs.isUsingFullScreen(),
            value -> { if (prefs != null) prefs.setFullScreen(value); });

        configureSwitch("use-fullscreen-workaround", prefs != null && prefs.isUsingFullScreenWorkAround(),
            value -> { if (prefs != null) prefs.setFullScreenWorkAround(value); });

        // --- Window: terminal margin ---
        final SwitchPreferenceCompat marginPref = findPreference("terminal_margin_adjustment");
        if (marginPref != null) {
            marginPref.setPersistent(false);
            marginPref.setChecked(prefs != null && prefs.isTerminalMarginAdjustmentEnabled());
            marginPref.setOnPreferenceChangeListener((preference, newValue) -> {
                if (prefs != null) prefs.setTerminalMarginAdjustment((Boolean) newValue);
                return true;
            });
        }

        // --- Tabs ---
        // --- Terminal appearance (moved from Terminal screen) ---
        configureTerminalAppearancePreferences(prefs);

        configureTabPanelPositionPreference();
        configureTabHeightModePreference();
        configureSwipeRightmostNewTabPreference();
        configureRestoreSessionsPreference();
        configureDirectoryHistoryMaxPreference();
    }

    // -----------------------------------------------------------------------
    //  Terminal appearance (moved from TerminalPreferencesFragment)
    // -----------------------------------------------------------------------

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
        configureBackgroundBlurSwitch(prefs);
        configureBackgroundBlurRadiusSeekBar(prefs);
        updateBackgroundBlurPrefState(prefs);

        configureSwitch("scroll-on-new-output", prefs.isScrollOnNewOutputEnabled(),
            value -> prefs.setScrollOnNewOutputEnabled(value));
    }

    private void configureSeekBarInt(String key, int current,
                                     PreferenceValueSetter<Integer> setter) {
        final SeekBarPreference pref = findPreference(key);
        if (pref == null) return;
        pref.setPersistent(false);
        // The seek bar max is defined in the XML preference; clamp any previously
        // stored value (e.g. from an older build with a higher max) so the terminal
        // never keeps an out-of-range margin.
        int max = pref.getMax();
        if (current > max) {
            current = max;
            setter.set(current);
        }
        pref.setValue(current);
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            setter.set((Integer) newValue);
            updateStyling();
            return true;
        });
    }

    /**
     * Wallpaper-blur switch (Android 12+ {@code FLAG_BLUR_BEHIND}).
     *
     * The system blur is only ever applied to the wallpaper that shows through a translucent
     * terminal background, so the switch is meaningless at 0% transparency and below API 31
     * ({@code FLAG_BLUR_BEHIND} / {@code RenderEffect} are both API 31, and there is
     * deliberately no custom blur fallback) — in both cases the preference is disabled and its
     * summary says why.
     *
     * Toggling it only changes {@code WindowManager.LayoutParams} flags, so no activity
     * recreate is needed; the reload is broadcast with {@code recreate=false}.
     */
    private void configureBackgroundBlurSwitch(TermuxAppSharedPreferences prefs) {
        mBlurPref = findPreference(TermuxPreferenceConstants.TERMUX_APP.KEY_TERMINAL_BACKGROUND_BLUR);
        if (mBlurPref == null) return;

        mBlurPref.setPersistent(false);
        mBlurPref.setChecked(prefs.isTerminalBackgroundBlurEnabled());
        mBlurPref.setOnPreferenceChangeListener((preference, newValue) -> {
            prefs.setTerminalBackgroundBlurEnabled((Boolean) newValue);
            updateBackgroundBlurPrefState(prefs);
            Context ctx = getContext();
            if (ctx != null) TermuxActivity.updateTermuxActivityStyling(ctx, false);
            return true;
        });
    }

    /**
     * Enable/disable the blur switch (and its radius slider) and explain why they are off:
     * unsupported OS version, an opaque terminal background (nothing behind it to blur), or the
     * blur switch is itself off.
     */
    private void updateBackgroundBlurPrefState(TermuxAppSharedPreferences prefs) {
        final boolean blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        final boolean hasWallpaper = prefs.getTerminalBackgroundTransparency() > 0;
        final boolean blurOn = prefs.isTerminalBackgroundBlurEnabled();
        final boolean blurUsable = blurSupported && hasWallpaper && blurOn;

        if (mBlurPref != null) {
            mBlurPref.setEnabled(blurSupported && hasWallpaper);
            if (!blurSupported) {
                mBlurPref.setSummary(R.string.terminal_background_blur_unsupported);
            } else if (!hasWallpaper) {
                mBlurPref.setSummary(R.string.terminal_background_blur_needs_transparency);
            } else {
                mBlurPref.setSummary(R.string.terminal_background_blur_summary);
            }
        }

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
        int current = prefs.getTerminalBackgroundBlurRadius();
        final int max = mBlurRadiusPref.getMax();
        if (current > max) {
            // Older build with a higher max — clamp so an out-of-range radius never reaches setBlurBehindRadius.
            current = max;
            prefs.setTerminalBackgroundBlurRadius(current);
        }
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
     * Deliberately <em>not</em> {@link #configureSeekBarInt(String, int, PreferenceValueSetter)}:
     * that helper asks for an activity recreate on every value, and recreating on every frame of
     * a drag is unusable. Instead the value is written straight away and a styling reload
     * <em>without</em> recreate is broadcast, so the transparency changes live.
     * {@code TermuxActivity} still recreates itself on its own when the value crosses the 0%
     * boundary, because {@code android:windowIsTranslucent} is a static theme attribute.
     */
    private void configureBackgroundTransparencySeekBar(TermuxAppSharedPreferences prefs) {
        final SeekBarPreference pref =
            findPreference(TermuxPreferenceConstants.TERMUX_APP.KEY_TERMINAL_BACKGROUND_TRANSPARENCY);
        if (pref == null) return;

        pref.setPersistent(false);
        int current = prefs.getTerminalBackgroundTransparency();
        final int max = pref.getMax();
        if (current > max) {
            // Older build with a higher max — clamp so the terminal never keeps an out-of-range value.
            current = max;
            prefs.setTerminalBackgroundTransparency(current);
        }
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
        updateIntEditSummary(pref, current, min, max);

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
                        updateIntEditSummary(pref, currentRef.get(), min, max);
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

    private void updateIntEditSummary(Preference pref, int value, int min, int max) {
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

        final SharedPreferences termuxPrefs =
                requireContext().getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
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

    // -----------------------------------------------------------------------
    //  Colour-scheme selection (light/dark)
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    //  Transparency-slider listeners
    // -----------------------------------------------------------------------

    private void wireSliderListener(String key, Context context) {
        SeekBarPreference slider = findPreference(key);
        if (slider == null) return;
        slider.setOnPreferenceChangeListener((preference, newValue) -> {
            TermuxActivity.updateTermuxActivityStyling(context, false);
            return true;
        });
    }

    // -----------------------------------------------------------------------
    //  Tabs
    // -----------------------------------------------------------------------

    private void configureSwipeRightmostNewTabPreference() {
        final SwitchPreferenceCompat pref = findPreference("swipe_rightmost_new_tab");
        if (pref == null) return;

        final SharedPreferences termuxPrefs =
                requireContext().getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        pref.setPersistent(false);
        pref.setChecked(termuxPrefs.getBoolean("swipe_rightmost_new_tab", true));

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            termuxPrefs.edit().putBoolean("swipe_rightmost_new_tab", (Boolean) newValue).apply();
            return true;
        });
    }

    private void configureTabPanelPositionPreference() {
        final ListPreference pref = findPreference("tab_panel_position");
        if (pref == null) return;

        final SharedPreferences termuxPrefs =
                requireContext().getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        pref.setPersistent(false);
        pref.setValue(termuxPrefs.getString("tab_panel_position", "top"));

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            termuxPrefs.edit().putString("tab_panel_position", (String) newValue).apply();
            final Context context = requireContext();
            final Intent intent = new Intent("com.termux.TAB_PANEL_POSITION_CHANGED");
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);
            return true;
        });
    }

    private void configureTabHeightModePreference() {
        final ListPreference pref = findPreference("tab_height_mode");
        if (pref == null) return;

        final SharedPreferences termuxPrefs =
                requireContext().getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        pref.setPersistent(false);
        pref.setValue(termuxPrefs.getString("tab_height_mode", "single"));

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            termuxPrefs.edit().putString("tab_height_mode", (String) newValue).apply();
            final Context context = requireContext();
            final Intent intent = new Intent("com.termux.TAB_HEIGHT_MODE_CHANGED");
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);
            return true;
        });
    }

    private void configureRestoreSessionsPreference() {
        final SwitchPreferenceCompat pref = findPreference("restore_sessions");
        if (pref == null) return;

        final SharedPreferences termuxPrefs =
                requireContext().getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        pref.setPersistent(false);
        pref.setChecked(termuxPrefs.getBoolean("restore_sessions", true));

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            termuxPrefs.edit().putBoolean("restore_sessions", (Boolean) newValue).apply();
            return true;
        });
    }

}
