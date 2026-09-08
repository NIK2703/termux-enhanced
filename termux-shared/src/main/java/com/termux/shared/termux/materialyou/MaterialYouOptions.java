package com.termux.shared.termux.materialyou;

import androidx.annotation.NonNull;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * The {@code material-you-*} tunables from {@code ~/.termux/termux.properties}.
 *
 * <p>Every key is optional; missing or unparseable values fall back to the kde defaults so that a
 * bare install behaves exactly like {@code kde-material-you-colors}.
 *
 * <p>The file is only re-read when its mtime changes, so the (relatively expensive)
 * {@code Properties} parse does not happen on every resume.
 *
 * <pre>
 * material-you-variant=system            # system|content|...|fruit-salad | kde index 0..8
 * material-you-background=surface        # surface|container-low|container-lowest
 * material-you-accent-source=wallpaper   # wallpaper|palette|rotational
 * material-you-accent-contrast=0         # 0 = kde defaults (2.5 dark / 2.0 light)
 * material-you-chroma=1.0                # 0.5 .. 2.0
 * material-you-tone=1.0                  # 0.5 .. 1.5
 * material-you-color0=bg                 # bg|dim
 * </pre>
 */
public final class MaterialYouOptions {

    private static final String LOG_TAG = "MaterialYouOptions";

    public static final String KEY_VARIANT = "material-you-variant";
    public static final String KEY_BACKGROUND = "material-you-background";
    public static final String KEY_ACCENT_SOURCE = "material-you-accent-source";
    public static final String KEY_ACCENT_CONTRAST = "material-you-accent-contrast";
    public static final String KEY_CHROMA = "material-you-chroma";
    public static final String KEY_TONE = "material-you-tone";
    public static final String KEY_COLOR0 = "material-you-color0";

    /** kde's thresholds, used whenever {@code material-you-accent-contrast} is 0 or absent. */
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

    public MaterialYouOptions(@NonNull SchemeVariant variant, @NonNull Background background,
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
     * <p>Used when the variant comes from the selected scheme name ({@code MaterialYou-rainbow})
     * rather than from the {@code material-you-variant} property.
     */
    @NonNull
    public MaterialYouOptions withVariant(@NonNull SchemeVariant newVariant) {
        if (newVariant == variant) return this;
        return new MaterialYouOptions(newVariant, background, accentSource, accentContrast,
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

    private static final Object LOCK = new Object();
    private static volatile MaterialYouOptions sCached;
    private static long sCachedMtime = -1;

    /** Options with every field at its default. */
    @NonNull
    public static MaterialYouOptions defaults() {
        return new MaterialYouOptions(SchemeVariant.DEFAULT, Background.SURFACE,
                AccentSource.WALLPAPER, 0, 1.0, 1.0, Color0.BG);
    }

    /**
     * Read the options from {@code ~/.termux/termux.properties}, re-parsing only when the file
     * actually changed. Never throws - a broken file yields the defaults.
     */
    @NonNull
    public static MaterialYouOptions load() {
        File file = TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE;
        long mtime = (file == null) ? 0 : file.lastModified();
        MaterialYouOptions cached = sCached;
        if (cached != null && mtime == sCachedMtime) return cached;

        synchronized (LOCK) {
            if (sCached != null && mtime == sCachedMtime) return sCached;
            Properties props = new Properties();
            if (file != null && file.isFile()) {
                try (InputStream in = new FileInputStream(file)) {
                    props.load(in);
                } catch (Exception e) {
                    Logger.logError(LOG_TAG, "Failed to read termux.properties: " + e.getMessage());
                }
            }
            MaterialYouOptions options = fromProperties(props);
            sCached = options;
            sCachedMtime = mtime;
            return options;
        }
    }

    /** Drop the cached parse; the next {@link #load()} re-reads the file. */
    public static void invalidate() {
        synchronized (LOCK) {
            sCached = null;
            sCachedMtime = -1;
        }
    }

    @NonNull
    static MaterialYouOptions fromProperties(@NonNull Properties props) {
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

        return new MaterialYouOptions(
                variant == null ? SchemeVariant.DEFAULT : variant,
                Background.parse(trim(props.getProperty(KEY_BACKGROUND))),
                AccentSource.parse(trim(props.getProperty(KEY_ACCENT_SOURCE))),
                parseDouble(props.getProperty(KEY_ACCENT_CONTRAST), 0),
                parseDouble(props.getProperty(KEY_CHROMA), 1.0),
                parseDouble(props.getProperty(KEY_TONE), 1.0),
                Color0.parse(trim(props.getProperty(KEY_COLOR0))));
    }

    /**
     * Write one {@code material-you-*} key into {@code termux.properties}, preserving every other
     * key, and drop the cached parse.
     *
     * <p>{@code null} removes the key, which restores the default.
     */
    public static void persist(@NonNull String key, String value) {
        File file = TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE;
        synchronized (LOCK) {
            Properties props = new Properties();
            if (file != null && file.isFile()) {
                try (InputStream in = new FileInputStream(file)) {
                    props.load(in);
                } catch (Exception e) {
                    Logger.logError(LOG_TAG, "Failed to read termux.properties: " + e.getMessage());
                }
            }
            if (value == null) props.remove(key);
            else props.setProperty(key, value);
            if (file != null) {
                try {
                    File parent = file.getParentFile();
                    if (parent != null && !parent.isDirectory()) parent.mkdirs();
                    try (java.io.OutputStream out = new java.io.FileOutputStream(file)) {
                        props.store(out, null);
                    }
                } catch (Exception e) {
                    Logger.logError(LOG_TAG, "Failed to write termux.properties: " + e.getMessage());
                }
            }
            invalidate();
        }
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
