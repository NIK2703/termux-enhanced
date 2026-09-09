package com.termux.shared.termux.monet;

import androidx.annotation.NonNull;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;

import java.util.Properties;

/**
 * The {@code monet-*} tunables, read from the app {@link android.content.SharedPreferences}.
 *
 * <p>Every key is optional; missing or unparseable values fall back to the kde defaults so that a
 * bare install behaves exactly like {@code kde-material-you-colors}.
 *
 * <p>The keys used to live in {@code ~/.termux/termux.properties}, but that file is renamed away
 * by the preferences migration, so every tunable was lost on restart. The preferences are the
 * single source of truth now - the same store the rest of the settings use.
 *
 * <pre>
 * monet-variant=system            # system|content|...|fruit-salad | kde index 0..8
 * monet-background=surface        # surface|container-low|container-lowest
 * monet-accent-source=wallpaper   # wallpaper|palette|rotational
 * monet-accent-contrast=0         # 0 = kde defaults (2.5 dark / 2.0 light)
 * monet-chroma=1.0                # 0.5 .. 2.0
 * monet-tone=1.0                  # 0.5 .. 1.5
 * monet-color0=bg                 # bg|dim
 * </pre>
 */
public final class MonetOptions {

    private static final String LOG_TAG = "MonetOptions";

    public static final String KEY_VARIANT = "monet-variant";
    public static final String KEY_BACKGROUND = "monet-background";
    public static final String KEY_ACCENT_SOURCE = "monet-accent-source";
    public static final String KEY_ACCENT_CONTRAST = "monet-accent-contrast";
    public static final String KEY_CHROMA = "monet-chroma";
    public static final String KEY_TONE = "monet-tone";
    public static final String KEY_COLOR0 = "monet-color0";

    /** kde's thresholds, used whenever {@code monet-accent-contrast} is 0 or absent. */
    public static final double DEFAULT_ACCENT_CONTRAST_DARK = 2.5;
    public static final double DEFAULT_ACCENT_CONTRAST_LIGHT = 2.0;

    /** How deep the terminal background sits in the surface stack. */
    public enum Background {
        /** {@code surface} - kde's choice, and what every M3 app uses as its canvas. */
        SURFACE("surface"),
        /** {@code surfaceContainerLow}. */
        CONTAINER_LOW("container-low"),
        /** {@code surfaceContainerLowest} - the darkest/deepest surface. */
        CONTAINER_LOWEST("container-lowest");

        @NonNull public final String key;

        Background(@NonNull String key) {
            this.key = key;
        }

        @NonNull
        static Background parse(@NonNull String raw) {
            for (Background b : values()) {
                if (b.key.equalsIgnoreCase(raw) || b.name().equalsIgnoreCase(raw.replace('-', '_')))
                    return b;
            }
            return SURFACE;
        }
    }

    /** Where the seven ANSI accent candidates come from. */
    public enum AccentSource {
        /** {@code WallpaperColors.getPrimary/Secondary/TertiaryColor()} - colors the system picked. */
        WALLPAPER("wallpaper"),
        /** Derive them from the primary/tertiary tonal palettes (kde's "fill up" path). */
        PALETTE("palette"),
        /** Rotate the hue around the seed - how M3 builds Rainbow/FruitSalad. */
        ROTATIONAL("rotational");

        @NonNull public final String key;

        AccentSource(@NonNull String key) {
            this.key = key;
        }

        @NonNull
        static AccentSource parse(@NonNull String raw) {
            for (AccentSource s : values()) {
                if (s.key.equalsIgnoreCase(raw) || s.name().equalsIgnoreCase(raw)) return s;
            }
            return WALLPAPER;
        }
    }

    /** What goes into ANSI slot 0. */
    public enum Color0 {
        /** {@code background} - the pywal/kde convention. */
        BG("bg"),
        /** A dimmed background so TUI apps drawing "black" stay visible. */
        DIM("dim");

        @NonNull public final String key;

        Color0(@NonNull String key) {
            this.key = key;
        }

        @NonNull
        static Color0 parse(@NonNull String raw) {
            for (Color0 c : values()) {
                if (c.key.equalsIgnoreCase(raw) || c.name().equalsIgnoreCase(raw)) return c;
            }
            return BG;
        }
    }

    @NonNull public final SchemeVariant variant;
    @NonNull public final Background background;
    @NonNull public final AccentSource accentSource;
    public final double accentContrast;
    public final double chroma;
    public final double tone;
    @NonNull public final Color0 color0;

    /** A raw copy of the six parsed values, used for change detection and logging. */
    @NonNull private final String mSignature;

    public MonetOptions(@NonNull SchemeVariant variant, @NonNull Background background,
                              @NonNull AccentSource accentSource, double accentContrast,
                              double chroma, double tone, @NonNull Color0 color0) {
        this.variant = variant;
        this.background = background;
        this.accentSource = accentSource;
        this.accentContrast = accentContrast < 0 ? 0 : accentContrast;
        this.chroma = ColorMath.clamp(chroma, 0.0, 10.0);
        this.tone = ColorMath.clamp(tone, 0.0, 1.5);
        this.color0 = color0;
        this.mSignature = variant.key + "|" + background.key + "|" + accentSource.key + "|"
                + this.accentContrast + "|" + this.chroma + "|" + this.tone + "|" + color0.key;
    }

    /**
     * A copy of these options that produces the given scheme variant.
     *
     * <p>Used when the variant comes from the selected scheme name ({@code Monet-rainbow})
     * rather than from the {@code monet-variant} property.
     */
    @NonNull
    public MonetOptions withVariant(@NonNull SchemeVariant newVariant) {
        if (newVariant == variant) return this;
        return new MonetOptions(newVariant, background, accentSource, accentContrast,
                chroma, tone, color0);
    }

    /** The accent contrast threshold for the given night mode. */
    public double accentContrastFor(boolean isDark) {
        if (accentContrast > 0) return accentContrast;
        return isDark ? DEFAULT_ACCENT_CONTRAST_DARK : DEFAULT_ACCENT_CONTRAST_LIGHT;
    }

    /** Value that changes whenever any option changes; folded into the scheme cache key. */
    public int revision() {
        return mSignature.hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        return mSignature;
    }

    // ------------------------------------------------------------------ Loading ---

    /** Options with every field at its default. */
    @NonNull
    public static MonetOptions defaults() {
        return new MonetOptions(SchemeVariant.DEFAULT, Background.SURFACE,
                AccentSource.WALLPAPER, 0, 1.0, 1.0, Color0.BG);
    }

    /**
     * Read the {@code monet-*} options from the app {@link android.content.SharedPreferences}.
     *
     * <p>They used to live in {@code ~/.termux/termux.properties}, but that file was renamed away
     * by the preferences migration on every launch, so every tunable was lost on restart. The
     * preferences are the single source of truth now - the same place the rest of the settings
     * live.
     *
     * <p>Never throws: unreachable or unset values yield the defaults.
     */
    @NonNull
    public static MonetOptions load() {
        Properties props = new Properties();
        TermuxAppSharedPreferences prefs = TermuxAppSharedProperties.getPreferences();
        if (prefs == null) {
            Logger.logDebug(LOG_TAG, "Preferences are not available yet, using the default options.");
        } else {
            copyProperty(props, prefs, KEY_VARIANT);
            copyProperty(props, prefs, KEY_BACKGROUND);
            copyProperty(props, prefs, KEY_ACCENT_SOURCE);
            copyProperty(props, prefs, KEY_ACCENT_CONTRAST);
            copyProperty(props, prefs, KEY_CHROMA);
            copyProperty(props, prefs, KEY_TONE);
            copyProperty(props, prefs, KEY_COLOR0);
        }
        return fromProperties(props);
    }

    /** Copy one {@code monet-*} value out of the preferences, when it is set. */
    private static void copyProperty(@NonNull Properties props,
                                     @NonNull TermuxAppSharedPreferences prefs,
                                     @NonNull String key) {
        String value = prefs.getStringByKey(key);
        if (value != null && !value.trim().isEmpty()) props.setProperty(key, value.trim());
    }

    /**
     * No-op kept for API compatibility: the options are no longer cached in memory, they are read
     * from the preferences on every {@link #load()}, so there is nothing to drop.
     */
    public static void invalidate() {
    }

    @NonNull
    static MonetOptions fromProperties(@NonNull Properties props) {
        String rawVariant = trim(props.getProperty(KEY_VARIANT));
        SchemeVariant variant = null;
        if (!rawVariant.isEmpty()) {
            variant = SchemeVariant.parse(rawVariant);
            if (variant == SchemeVariant.DEFAULT && !SchemeVariant.DEFAULT.key.equalsIgnoreCase(rawVariant)
                    && !"-1".equals(rawVariant)) {
                Logger.logWarn(LOG_TAG, "Unknown \"" + KEY_VARIANT + "\" value \"" + rawVariant
                        + "\", falling back to \"" + SchemeVariant.DEFAULT.key + "\".");
            }
        }

        return new MonetOptions(
                variant == null ? SchemeVariant.DEFAULT : variant,
                Background.parse(trim(props.getProperty(KEY_BACKGROUND))),
                AccentSource.parse(trim(props.getProperty(KEY_ACCENT_SOURCE))),
                parseDouble(props.getProperty(KEY_ACCENT_CONTRAST), 0),
                parseDouble(props.getProperty(KEY_CHROMA), 1.0),
                parseDouble(props.getProperty(KEY_TONE), 1.0),
                Color0.parse(trim(props.getProperty(KEY_COLOR0))));
    }

    /**
     * Write one {@code monet-*} key into the app {@link android.content.SharedPreferences}.
     *
     * <p>{@code null} clears the key, which restores the default.
     */
    public static void persist(@NonNull String key, String value) {
        TermuxAppSharedPreferences prefs = TermuxAppSharedProperties.getPreferences();
        if (prefs == null) {
            Logger.logError(LOG_TAG, "Failed to persist \"" + key + "\": preferences are not available.");
            return;
        }
        prefs.setGenericString(key, value);
    }

    /** Persist the chosen scheme variant. */
    public static void persistVariant(@NonNull SchemeVariant variant) {
        persist(KEY_VARIANT, variant.toPropertyValue());
    }

    @NonNull
    private static String trim(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"')
            v = v.substring(1, v.length() - 1).trim();
        return v;
    }

    private static double parseDouble(String value, double fallback) {
        String v = trim(value);
        if (v.isEmpty()) return fallback;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            Logger.logWarn(LOG_TAG, "Invalid number \"" + v + "\", using " + fallback + ".");
            return fallback;
        }
    }
}
