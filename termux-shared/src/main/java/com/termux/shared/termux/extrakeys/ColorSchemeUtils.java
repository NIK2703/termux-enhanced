package com.termux.shared.termux.extrakeys;

import android.content.Context;
import android.view.ContextThemeWrapper;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.termux.terminal.TerminalColors;
import com.termux.terminal.TextStyle;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.logger.Logger;
import androidx.annotation.NonNull;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.materialyou.MaterialYouSchemeStore;
import com.termux.shared.termux.materialyou.SchemeVariant;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/** Termux app preference keys for per-theme color scheme selection (stored in termux.properties). */
public final class ColorSchemeUtils {

    private static final String LOG_TAG = "ColorSchemeUtils";

    /** Key in termux.properties selecting the color scheme for the light app theme. */
    public static final String KEY_COLOR_SCHEME_LIGHT = TermuxPropertyConstants.KEY_COLOR_SCHEME_LIGHT;
    /** Key in termux.properties selecting the color scheme for the dark app theme. */
    public static final String KEY_COLOR_SCHEME_DARK = TermuxPropertyConstants.KEY_COLOR_SCHEME_DARK;

    /** Perceived-brightness threshold (0-255) above which a color is treated as "light". */
    public static final int LIGHTNESS_THRESHOLD = 130;

    // Translucent button backgrounds: dark (for light schemes) / light (for dark schemes).
    public static final int BUTTON_BG_LIGHT_SCHEME = 0x0D000000;   // ~5% black
    public static final int BUTTON_BG_DARK_SCHEME = 0x0DFFFFFF;   // ~5% white
    public static final int BUTTON_BG_ACTIVE_LIGHT_SCHEME = 0x1F000000; // ~12% black
    public static final int BUTTON_BG_ACTIVE_DARK_SCHEME = 0x1FFFFFFF; // ~12% white

    private ColorSchemeUtils() {}

    /**
     * Convert an integer percentage (0‑100) to an alpha byte (0‑255).
     * Uses {@link Math#round} so 5% → 13 (0x0D) and 12% → 31 (0x1F),
     * matching the historical hardcoded defaults exactly.
     */
    public static int percentToAlpha(int percent) {
        int a = Math.round(percent * 255.0f / 100.0f);
        if (a < 0) return 0;
        if (a > 255) return 255;
        return a;
    }

    /** @deprecated Use {@link #getButtonBackground(boolean, int)} to honour user transparency slider. */
    @Deprecated
    public static int getButtonBackground(boolean isLight) {
        return getButtonBackground(isLight, 5);
    }

    /**
     * Build a translucent button background colour.
     *
     * @param isLight       Whether the colour scheme is perceived as light.
     * @param alphaPercent  User-configured alpha percentage (0‑20).
     * @return An ARGB colour with the given alpha over dark (light scheme) or light (dark scheme) base.
     */
    public static int getButtonBackground(boolean isLight, int alphaPercent) {
        int alpha = percentToAlpha(alphaPercent);
        int base = isLight ? 0x000000 : 0xFFFFFF;
        return (alpha << 24) | base;
    }

    /** @deprecated Use {@link #getButtonActiveBackground(boolean, int)} to honour user transparency slider. */
    @Deprecated
    public static int getButtonActiveBackground(boolean isLight) {
        return getButtonActiveBackground(isLight, 12);
    }

    /**
     * Build a translucent active/pressed button background colour.
     *
     * @param isLight       Whether the colour scheme is perceived as light.
     * @param alphaPercent  User-configured alpha percentage (10‑20).
     * @return An ARGB colour with the given alpha over dark (light scheme) or light (dark scheme) base.
     */
    public static int getButtonActiveBackground(boolean isLight, int alphaPercent) {
        int alpha = percentToAlpha(alphaPercent);
        int base = isLight ? 0x000000 : 0xFFFFFF;
        return (alpha << 24) | base;
    }

    /**
     *         panel buttons need a dark or light translucent background and whether the status
     *         bar needs dark or light icons).
     */
    public static boolean isColorLight(int argb) {
        return TerminalColors.getPerceivedBrightnessOfColor(argb) >= LIGHTNESS_THRESHOLD;
    }

    /**
     * @return Whether the currently applied terminal color scheme (the static
     *         {@link TerminalColors#COLOR_SCHEME}) is light, based on its background color.
     */
    public static boolean isTerminalSchemeLight() {
        int background = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND];
        return isColorLight(background);
    }

    /**
     * @return The foreground color of the currently applied terminal color scheme, or a
     *         contrast color (black/white) derived from its background when no custom scheme is
     *         active (i.e. the built-in default was used).
     */
    public static int getSchemeForeground() {
        int foreground = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND];
        // Guarantee readable panel/button/tab text against the scheme background: force black on a
        // light scheme and white on a dark scheme whenever the foreground would otherwise be
        // low-contrast. Symmetric guard (previously only white-on-light was handled), which fixes
        // black text on a dark background in night mode.
        boolean schemeLight = isTerminalSchemeLight();
        boolean foregroundLight = TerminalColors.getPerceivedBrightnessOfColor(foreground) >= LIGHTNESS_THRESHOLD;
        if (schemeLight && foregroundLight) {
            return 0xFF000000;
        }
        if (!schemeLight && !foregroundLight) {
            return 0xFFFFFFFF;
        }
        return foreground;
    }

    /**
     * Whether the given colors.properties actually defines real terminal color keys
     * (background / foreground / cursor / colorN). A file that contains only comments or
     * unrelated keys (e.g. the "Default" marker written by Termux:Style) is treated as
     * "no custom colors".
     */
    public static boolean hasRealColors(Properties props) {
        for (String key : props.stringPropertyNames()) {
            if (key.equals("foreground") || key.equals("background") || key.equals("cursor")
                    || key.startsWith("color")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Load and apply a user colors.properties to the static {@link TerminalColors#COLOR_SCHEME}.
     *
     * @param file The {@code ~/.termux/colors.properties} file.
     * @return {@code true} if a real (non-default) custom color scheme was applied,
     *         {@code false} if the file is absent or only a "Default" marker (in which case the
     *         caller should fall back to the theme-derived light/dark scheme).
     */
    public static boolean loadTerminalColorScheme(File file) {
        if (file == null || !file.isFile()) return false;
        final Properties props = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (Exception e) {
            return false;
        }
        if (!hasRealColors(props)) return false;
        TerminalColors.COLOR_SCHEME.updateWith(props);
        return true;
    }

    /**
     * Ensure the static {@link TerminalColors#COLOR_SCHEME} reflects the given night mode.
     * <p>
     * If a per-theme color file exists ({@code colors.light.properties} /
     * {@code colors.dark.properties}), it is loaded first. Otherwise the built-in default
     * scheme is applied: the default dark scheme (black background) for night mode, or the
     * provided {@code lightScheme} for light mode.
     *
     * @param isNight     {@code true} for night (dark) mode.
     * @param lightScheme A {@link Properties} with light color scheme values (background=white,
     *                    foreground=black, etc.) to use in light mode when no custom file exists.
     *                    May be {@code null} — in that case dark scheme is used as fallback.
     * @return {@code true} if a custom per-theme color file was loaded,
     *         {@code false} if the built-in default scheme was applied instead.
     */
    public static boolean ensureColorSchemeForTheme(boolean isNight, Properties lightScheme) {
        return ensureColorSchemeForTheme(null, isNight, lightScheme);
    }

    /**
     * Ensure the static {@link TerminalColors#COLOR_SCHEME} reflects the given night mode.
     * <p>
     * If a per-theme color file exists ({@code colors.light.properties} /
     * {@code colors.dark.properties}), it is loaded first. Otherwise, when the selected scheme is
     * {@link #SCHEME_MATERIAL_YOU}, the wallpaper-derived scheme is generated and applied.
     * Otherwise the built-in default scheme is applied: the default dark scheme (black background)
     * for night mode, or the provided {@code lightScheme} for light mode.
     *
     * @param context  A context used to read the system palette; may be {@code null}, in which case
     *                 the Material You path is skipped (it needs a Context).
     * @param isNight  {@code true} for night (dark) mode.
     * @param lightScheme A {@link Properties} with light color scheme values (background=white,
     *                    foreground=black, etc.) to use in light mode when no custom file exists.
     *                    May be {@code null} — in that case dark scheme is used as fallback.
     * @return {@code true} if a custom or generated scheme was applied,
     *         {@code false} if the built-in default scheme was applied instead.
     */
    public static boolean ensureColorSchemeForTheme(Context context, boolean isNight,
                                                    Properties lightScheme) {
        return applyColorSchemeForTheme(context, isNight, lightScheme);
    }

    /**
     * The one and only resolution chain for a theme's terminal colors. Every caller (the activity,
     * the session client, the extra-keys editor) must funnel through here so that "Default" and
     * "Material You" cannot disagree between surfaces.
     *
     * <p>Priority:
     * <ol>
     *   <li>the per-theme Termux:Style file ({@code colors.light/dark.properties}), if present;</li>
     *   <li>the generated Material You scheme — <b>only</b> when the theme actually selected one
     *       ({@code MaterialYou} / {@code MaterialYou-&lt;variant&gt;});</li>
     *   <li>the built-in scheme: {@code lightScheme} in light mode, the default dark scheme
     *       (black background) otherwise. This is the "Default" behaviour and is deliberately
     *       <b>not</b> Material-derived.</li>
     * </ol>
     *
     * @param context     Used to read the system palette; may be {@code null}, in which case the
     *                    Material You step is skipped (it needs a Context).
     * @param isNight     {@code true} for night (dark) mode.
     * @param lightScheme Light-mode fallback colors; may be {@code null} (dark mode).
     * @return {@code true} if a custom or generated scheme was applied,
     *         {@code false} if the built-in default scheme was applied instead.
     */
    public static boolean applyColorSchemeForTheme(Context context, boolean isNight,
                                                   Properties lightScheme) {
        File colorsFile = getColorSchemeFileForTheme(isNight);
        boolean customApplied = (colorsFile != null) && loadTerminalColorScheme(colorsFile);
        if (!customApplied) {
            // Returns false unless this theme selected Material You, so "Default" stays default.
            customApplied = applyMaterialYouScheme(context, isNight);
        }
        if (!customApplied) {
            if (!isNight && lightScheme != null) {
                TerminalColors.COLOR_SCHEME.updateWith(lightScheme);
            } else {
                TerminalColors.COLOR_SCHEME.updateWith(new Properties());
            }
        }
        return customApplied;
    }

    /** Whether the wallpaper-derived scheme is the one selected for the given theme. */
    public static boolean isMaterialYouSelected(boolean isNight) {
        return isMaterialYouScheme(getSelectedSchemeName(isNight));
    }

    /**
     * Generate and apply the Material You scheme <b>selected for the given theme</b>.
     *
     * <p>Returns {@code false} immediately when the theme did not select Material You — this is
     * what keeps "Default" (and every Termux:Style scheme) on the built-in, non-Material scheme.
     *
     * @return {@code true} when the generated scheme was applied; {@code false} when the theme does
     *         not use Material You, or when the device cannot provide a palette (API &lt; 31 or a
     *         firmware without dynamic color), in which case the caller falls back to the built-in
     *         scheme.
     */
    public static boolean applyMaterialYouScheme(Context context, boolean isNight) {
        if (!isMaterialYouSelected(isNight)) return false;
        return applyMaterialYouScheme(context, isNight,
                materialYouVariantOf(getSelectedSchemeName(isNight)));
    }

    /**
     * Generate and apply the Material You scheme for an explicit {@link SchemeVariant}.
     *
     * <p>Each variant is a separate entry in {@link MaterialYouSchemeStore}, so picking
     * {@code MaterialYou-content} really yields the <i>content</i> theme and
     * {@code MaterialYou-expressive} the <i>expressive</i> one.
     *
     * @return {@code true} when the generated scheme was applied; {@code false} when the device
     *         cannot provide a palette (API &lt; 31 or a firmware without dynamic color), in which
     *         case the caller falls back to the built-in scheme.
     */
    public static boolean applyMaterialYouScheme(Context context, boolean isNight,
                                                 @NonNull SchemeVariant variant) {
        if (context == null || !MaterialYouSchemeStore.isSupported()) return false;
        Properties props = MaterialYouSchemeStore.get(context, isNight, variant);
        if (props == null || props.isEmpty()) return false;
        try {
            TerminalColors.COLOR_SCHEME.updateWith(props);
            return true;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to apply the Material You scheme: " + e.getMessage());
            return false;
        }
    }

    /**
     * Cache identity of the Material You scheme currently selected for the given theme — {@code 0}
     * when the theme does not use Material You. Folded into {@code buildSchemeKey()}.
     */
    public static long materialYouToken(boolean isNight) {
        if (!isMaterialYouSelected(isNight)) return 0L;
        return MaterialYouSchemeStore.token(materialYouVariantOf(getSelectedSchemeName(isNight)));
    }

    /**
     * Build the Material You scheme of the given theme up front, so that
     * {@link #materialYouToken(boolean)} already reports a stable, non-zero value when
     * {@code buildSchemeKey()} reads it.
     *
     * <p>Why this has to happen before the key is built: the token is {@code 0} until the variant
     * exists in the cache, so a key built first and a key built afterwards disagree — the caller
     * then sees a "changed" key and re-applies the scheme a second time for nothing. Warming is a
     * cheap map lookup once the variant exists (the wallpaper is read at most once per process).
     *
     * <p>No-op when the theme did not select Material You or the device does not support it.
     */
    public static void warmUpMaterialYou(Context context, boolean isNight) {
        if (context == null || !isMaterialYouSelected(isNight)) return;
        MaterialYouSchemeStore.warmUp(context,
                materialYouVariantOf(getSelectedSchemeName(isNight)));
    }

    /**
     * Resolve the color-scheme file to use for the given UI night mode.
     * Per-theme files ({@code colors.light.properties} / {@code colors.dark.properties}) take
     * priority so a light/dark scheme can be assigned independently of the app theme.
     *
     * @return The per-theme file to load, or {@code null} if it does not exist (caller should use
     *         the theme-derived built-in light/dark scheme). The shared colors.properties is NOT
     *         used as a fallback: selection is per-theme, so choosing "Default" (which deletes the
     *         per-theme file) must reset to the built-in light/dark scheme, not to a stale shared file.
     */
    public static File getColorSchemeFileForTheme(boolean isNight) {
        File perTheme = isNight ? TermuxConstants.TERMUX_COLOR_DARK_PROPERTIES_FILE
                                : TermuxConstants.TERMUX_COLOR_LIGHT_PROPERTIES_FILE;
        return perTheme.isFile() ? perTheme : null;
    }

    /**
     * Sentinel display name (and Termux:Style asset marker) meaning "reset to the default terminal
     * scheme" — i.e. remove the per-theme colors file so the theme-derived built-in scheme is used.
     */
    public static final String SCHEME_DEFAULT = "Default";

    /**
     * Sentinel value for {@code color-scheme-light} / {@code color-scheme-dark} selecting the
     * wallpaper-derived "Material You" scheme in the <b>system</b> flavour — the palette is read
     * straight from {@code android.R.color.system_*}, so the terminal matches the device theme.
     *
     * <p>Unlike the Termux:Style entries this is <b>not</b> an asset file name: the scheme is
     * generated at runtime and never touches {@code colors.light/dark.properties} (those remain
     * owned by Termux:Style, and writing a generated scheme there would break the "Default"
     * semantics).
     */
    public static final String SCHEME_MATERIAL_YOU = "MaterialYou";

    /**
     * Prefix for the per-variant Material You entries: {@code MaterialYou-<variant>}, e.g.
     * {@code MaterialYou-rainbow}. Each variant is a separate item in the scheme picker, so the
     * "type of color scheme" is chosen where the scheme itself is chosen.
     */
    public static final String MATERIAL_YOU_PREFIX = SCHEME_MATERIAL_YOU + "-";

    /** Human-readable label shared by every Material You entry. */
    public static final String MATERIAL_YOU_DISPLAY_NAME = "Material You";

    /** Whether {@code schemeName} is any of the Material You entries (bare or {@code -variant}). */
    public static boolean isMaterialYouScheme(String schemeName) {
        if (schemeName == null) return false;
        return SCHEME_MATERIAL_YOU.equals(schemeName) || schemeName.startsWith(MATERIAL_YOU_PREFIX);
    }

    /** Build the {@code color-scheme-*} value for a Material You variant. */
    @NonNull
    public static String materialYouSchemeName(@NonNull SchemeVariant variant) {
        return variant == SchemeVariant.SYSTEM
                ? SCHEME_MATERIAL_YOU
                : MATERIAL_YOU_PREFIX + variant.key;
    }

    /**
     * The variant a Material You entry stands for.
     *
     * <p>The mapping is <b>purely a function of the entry name</b> — never of
     * {@code material-you-variant}. That is what keeps the picker honest: a bare {@code MaterialYou}
     * is the <i>System</i> row and must stay System, and {@code MaterialYou-rainbow} is the
     * <i>Rainbow</i> row. Resolving the bare name through the property made the first row silently
     * mutate into a copy of whatever variant was picked last (both its label and its colors).
     *
     * <p>Unknown variant names (hand-edited, or a newer build's value) degrade to
     * {@link SchemeVariant#DEFAULT} rather than throwing.
     */
    @NonNull
    public static SchemeVariant materialYouVariantOf(String schemeName) {
        if (schemeName == null) return SchemeVariant.DEFAULT;
        if (schemeName.startsWith(MATERIAL_YOU_PREFIX)) {
            return SchemeVariant.parse(schemeName.substring(MATERIAL_YOU_PREFIX.length()));
        }
        if (SCHEME_MATERIAL_YOU.equals(schemeName)) return SchemeVariant.SYSTEM;
        return SchemeVariant.DEFAULT;
    }

    /**
     * Human-readable label for a Material You entry: {@code "Material You Rainbow"}.
     *
     * <p>The variant part reuses Termux:Style's own title-casing so all entries in the picker are
     * formatted the same way.
     */
    @NonNull
    public static String materialYouDisplayName(@NonNull SchemeVariant variant) {
        return MATERIAL_YOU_DISPLAY_NAME + " " + titleCaseWords(variant.key.replace('-', ' '));
    }

    /** Termux:Style stores its color schemes as {@code *.properties} files under this asset folder. */
    private static final String STYLING_COLORS_ASSET_DIR = "colors";

    /**
     * List the color schemes shipped by the installed Termux:Style app, read directly from its
     * assets (no duplication, always current). The returned array is the asset file names (e.g.
     * {@code solarized-dark.properties}) sorted alphabetically, with {@link #SCHEME_DEFAULT} first.
     *
     * @return The scheme file names, or {@code null} if Termux:Style is not installed / has no assets.
     */
    public static String[] listStylingColorSchemes(Context context) {
        Context stylingContext = getStylingContext(context);
        List<String> schemes = new ArrayList<>();
        if (stylingContext != null) {
            try {
                String[] files = stylingContext.getAssets().list(STYLING_COLORS_ASSET_DIR);
                if (files != null) {
                    for (String f : files) {
                        if (f.endsWith(".properties")) schemes.add(f);
                    }
                }
            } catch (IOException e) {
                Logger.logError(LOG_TAG, "Failed to list Termux:Style color assets: " + e.getMessage());
            }
        }
        // Material You does NOT depend on Termux:Style — it is generated from the system palette,
        // so it is offered even when the plugin is missing (and it is the only entry then).
        // One entry per variant: choosing the variant IS choosing the scheme.
        final boolean materialYou = MaterialYouSchemeStore.isSupported();
        if (schemes.isEmpty() && !materialYou) return null;
        Collections.sort(schemes, String.CASE_INSENSITIVE_ORDER);
        // Order: "Default" first, then the Material You variants (they need no plugin and are the
        // interesting new option), then the Termux:Style schemes.
        schemes.add(0, SCHEME_DEFAULT);
        if (materialYou) {
            int index = 1;
            for (SchemeVariant variant : SchemeVariant.values()) {
                schemes.add(index++, materialYouSchemeName(variant));
            }
        }
        return schemes.toArray(new String[0]);
    }

    /**
     * Show the color-scheme picker dialog (the single shared style-picker used from both Settings
     * and the terminal's long-press menu). Lists every scheme shipped by the installed Termux:Style
     * app, and on selection persists the choice for the given theme, writes the per-theme colors
     * file and invokes {@code onApplied} so the caller can trigger a live restyle.
     *
     * @param context   A UI context to show the dialog with.
     * @param isNight   Whether the selection targets the dark or light terminal scheme.
     * @param title     Dialog title.
     * @param notInstalledMessage Message shown if Termux:Style is not installed.
     * @param onApplied Run after a scheme is applied (e.g. to reload styling live); may be {@code null}.
     */
    public static void showColorSchemeDialog(Context context, boolean isNight, CharSequence title,
                                             CharSequence notInstalledMessage, Runnable onApplied) {
        final String[] schemes = listStylingColorSchemes(context);
        final Context dialogContext = new ContextThemeWrapper(context, com.termux.shared.R.style.ThemeOverlay_BaseDialog_DayNight);
        if (schemes == null) {
            AlertDialog d = new MaterialAlertDialogBuilder(dialogContext)
                .setMessage(notInstalledMessage)
                .setPositiveButton(android.R.string.ok, null)
                .create();
            d.show();
            return;
        }

        final String[] labels = new String[schemes.length];
        for (int i = 0; i < schemes.length; i++)
            labels[i] = schemeDisplayName(schemes[i]);

        // Pre-select whatever is currently stored for the theme, so the dialog opens on "Default"
        // (or the previously chosen Material You variant) rather than an arbitrary row.
        final String current = getSelectedSchemeName(isNight);
        int checkedItem = 0;
        for (int i = 0; i < schemes.length; i++) {
            if (schemes[i].equals(current)) { checkedItem = i; break; }
        }

        AlertDialog d = new MaterialAlertDialogBuilder(dialogContext)
            .setTitle(title)
            .setSingleChoiceItems(labels, checkedItem, (dialog, which) -> {
                persistSelection(isNight, schemes[which]);
                applyStylingScheme(context, isNight, schemes[which]);
                if (onApplied != null) onApplied.run();
                dialog.dismiss();
            })
            .create();
        d.show();
    }

    /**
     * Persist the selected scheme file name for the given theme into termux.properties.
     *
     * <p>The scheme name alone is the source of truth: a Material You entry carries its variant
     * in the name ({@code MaterialYou} = System, {@code MaterialYou-rainbow} = Rainbow), so
     * {@code material-you-variant} is deliberately NOT touched here. Writing it used to make the
     * bare {@code MaterialYou} row resolve to the last picked variant, turning "System" into a
     * duplicate of it.
     */
    public static void persistSelection(boolean isNight, String schemeFile) {
        File propsFile = new File(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH);
        Properties props = new Properties();
        if (propsFile.isFile()) {
            try (FileInputStream in = new FileInputStream(propsFile)) {
                props.load(in);
            } catch (IOException e) {
                Logger.logError(LOG_TAG, "Failed to read termux.properties: " + e.getMessage());
            }
        }
        props.setProperty(isNight ? KEY_COLOR_SCHEME_DARK : KEY_COLOR_SCHEME_LIGHT, schemeFile);
        try (FileOutputStream out = new FileOutputStream(propsFile)) {
            props.store(out, null);
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to write termux.properties: " + e.getMessage());
        }
    }

    /**
     * Turn a Termux:Style scheme asset file name into a human-readable label, matching Termux:Style's
     * own display formatting: strip the extension, replace '-' with spaces and title-case words.
     */
    public static String schemeDisplayName(String fileName) {
        if (SCHEME_DEFAULT.equals(fileName)) return SCHEME_DEFAULT;
        if (isMaterialYouScheme(fileName)) return materialYouDisplayName(materialYouVariantOf(fileName));
        String name = fileName.replace('-', ' ');
        int dot = name.lastIndexOf('.');
        if (dot != -1) name = name.substring(0, dot);
        return titleCaseWords(name);
    }

    /**
     * Termux:Style's display formatting: strip the extension, replace '-' with spaces and
     * title-case every word.
     */
    @NonNull
    private static String titleCaseWords(@NonNull String name) {
        StringBuilder sb = new StringBuilder(name.length());
        boolean lastWhitespace = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetter(c)) {
                sb.append(lastWhitespace ? Character.toUpperCase(c) : c);
                lastWhitespace = false;
            } else {
                sb.append(c);
                lastWhitespace = Character.isWhitespace(c);
            }
        }
        return sb.toString();
    }

    /**
     * Apply a Termux:Style color scheme to the per-theme file ({@code colors.light/dark.properties}),
     * reading the scheme content straight from the Termux:Style assets.
     * - {@link #SCHEME_DEFAULT}: delete the per-theme file so the theme-derived built-in scheme is used.
     * - otherwise: copy the named asset's contents into the per-theme file.
     *
     * @return {@code true} if the per-theme file was updated.
     */
    public static boolean applyStylingScheme(Context context, boolean isNight, String fileName) {
        File perThemeFile = isNight ? TermuxConstants.TERMUX_COLOR_DARK_PROPERTIES_FILE
                                    : TermuxConstants.TERMUX_COLOR_LIGHT_PROPERTIES_FILE;

        if (SCHEME_DEFAULT.equals(fileName)) {
            if (perThemeFile.isFile()) perThemeFile.delete();
            return true;
        }

        if (isMaterialYouScheme(fileName)) {
            // Generated at runtime from the system palette — nothing to copy. We only drop a stale
            // per-theme file so the generator, not a leftover Termux:Style scheme, owns the theme.
            if (perThemeFile.isFile()) perThemeFile.delete();
            MaterialYouSchemeStore.invalidate();
            return true;
        }

        Context stylingContext = getStylingContext(context);
        if (stylingContext == null) return false;

        try (InputStream in = stylingContext.getAssets().open(STYLING_COLORS_ASSET_DIR + "/" + fileName);
             FileOutputStream out = new FileOutputStream(perThemeFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return true;
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to apply Termux:Style scheme \"" + fileName + "\": " + e.getMessage());
            return false;
        }
    }

    /** Package context of the installed Termux:Style app, or {@code null} if not installed. */
    private static Context getStylingContext(Context context) {
        return PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_STYLING_PACKAGE_NAME);
    }

    /**
     * Resolve the scheme asset file name currently selected for the given theme, stored in
     * {@code termux.properties} under {@code color-scheme-light} / {@code color-scheme-dark}.
     *
     * @return The selected scheme file name, or {@link #SCHEME_DEFAULT} if none.
     */
    public static String getSelectedSchemeName(boolean isNight) {
        Properties props = new Properties();
        File propsFile = new File(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH);
        if (propsFile.isFile()) {
            try (FileInputStream in = new FileInputStream(propsFile)) {
                props.load(in);
            } catch (IOException e) {
                Logger.logError(LOG_TAG, "Failed to read termux.properties: " + e.getMessage());
            }
        }
        String value = props.getProperty(isNight ? KEY_COLOR_SCHEME_DARK : KEY_COLOR_SCHEME_LIGHT);
        return (value == null || value.isEmpty()) ? SCHEME_DEFAULT : value;
    }

}