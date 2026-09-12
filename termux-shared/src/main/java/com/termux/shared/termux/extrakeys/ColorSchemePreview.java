package com.termux.shared.termux.extrakeys;

import android.content.Context;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.monet.MonetSchemeStore;
import com.termux.terminal.TerminalColorScheme;
import com.termux.terminal.TextStyle;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The terminal colors a color-scheme picker entry would actually paint: its background, its
 * foreground and its 16 ANSI colors.
 *
 * <p>Resolved through exactly the same path the terminal itself uses — the entry's
 * {@code .properties} are parsed by {@link TerminalColorScheme#updateWith(Properties)}, which
 * starts from the built-in defaults and overlays whatever the scheme defines. So a scheme that
 * only sets {@code background} / {@code foreground} previews with the built-in ANSI colors, which
 * is precisely what the terminal would show; the picker never has to invent colors of its own.
 *
 * <p>Resolution is memoized process-wide. The cache key includes the theme (light/dark) and, for
 * Monet entries, the generated scheme's token, so a new wallpaper or a new variant regenerates the
 * preview instead of serving a stale one.
 */
public final class ColorSchemePreview {

    private static final String LOG_TAG = "ColorSchemePreview";

    /** Columns of the swatch table; ANSI colors are laid out eight per row. */
    public static final int PALETTE_COLUMNS = 8;
    /** Rows of the swatch table: dim (0-7) on top, bright (8-15) below. */
    public static final int PALETTE_ROWS = 2;
    /** {@code color0}..{@code color15}. */
    public static final int PALETTE_SIZE = PALETTE_COLUMNS * PALETTE_ROWS;

    /** How far the swatch frame is pulled from the background towards the foreground. */
    private static final float FRAME_BLEND = 0.5f;

    private static final Map<String, ColorSchemePreview> CACHE = new HashMap<>();

    /** The terminal background this scheme would paint. */
    public final int background;
    /** The terminal foreground this scheme would paint. */
    public final int foreground;
    /**
     * The swatch frame: the scheme's own foreground blended halfway into its own background, so
     * the table is always visible against the row without importing a foreign color.
     */
    public final int frame;

    private final int[] mPalette = new int[PALETTE_SIZE];

    private ColorSchemePreview(int background, int foreground, @NonNull int[] palette) {
        this.background = background;
        this.foreground = foreground;
        this.frame = blend(background, foreground, FRAME_BLEND);
        System.arraycopy(palette, 0, mPalette, 0, PALETTE_SIZE);
    }

    /** @return The {@code color0}..{@code color15} of the scheme, in ANSI order. */
    @NonNull
    public int[] palette() {
        return mPalette;
    }

    /** @return The ANSI color at {@code index}, or {@code 0} when out of range. */
    public int paletteColor(int index) {
        return (index < 0 || index >= PALETTE_SIZE) ? 0 : mPalette[index];
    }

    // ------------------------------------------------------------------ resolution ---

    /**
     * Resolve the preview for a picker entry, reading its colors once per process.
     *
     * @param context    used to reach the Termux:Style assets and the system palette; may be
     *                   {@code null}, in which case only the built-in scheme is resolvable.
     * @param isNight    which theme's terminal colors are being previewed.
     * @param schemeName the entry's identifier, i.e. exactly what
     *                   {@link ColorSchemeUtils#listStylingColorSchemes(Context)} returned.
     */
    @NonNull
    public static ColorSchemePreview resolve(@Nullable Context context, boolean isNight,
                                             @NonNull String schemeName) {
        final String key = cacheKey(isNight, schemeName);
        synchronized (CACHE) {
            ColorSchemePreview cached = CACHE.get(key);
            if (cached != null) return cached;
        }

        final ColorSchemePreview preview = build(context, isNight, schemeName);

        // Keyed again after the build: resolving a Monet entry is what generates its variant, so
        // the token that identifies the scheme only becomes meaningful once the build is done.
        synchronized (CACHE) {
            CACHE.put(cacheKey(isNight, schemeName), preview);
        }
        return preview;
    }

    /** Drop every memoized preview (e.g. after the Termux:Style plugin was reinstalled). */
    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    @NonNull
    private static String cacheKey(boolean isNight, @NonNull String schemeName) {
        long token = 0L;
        if (ColorSchemeUtils.isMonetScheme(schemeName)) {
            token = MonetSchemeStore.token(ColorSchemeUtils.monetVariantOf(schemeName));
        }
        return (isNight ? "dark|" : "light|") + schemeName + "|" + token;
    }

    @NonNull
    private static ColorSchemePreview build(@Nullable Context context, boolean isNight,
                                            @NonNull String schemeName) {
        TerminalColorScheme scheme = new TerminalColorScheme();
        final Properties props = readProperties(context, isNight, schemeName);
        if (props != null && !props.isEmpty()) {
            try {
                scheme.updateWith(props);
            } catch (Exception e) {
                // A hand-edited or newer scheme file must not take the picker down; fall back to
                // the built-in colors, which is also what the terminal falls back to.
                Logger.logError(LOG_TAG, "Failed to parse color scheme \"" + schemeName + "\": "
                        + e.getMessage());
                scheme = new TerminalColorScheme();
            }
        }
        return from(scheme);
    }

    @Nullable
    private static Properties readProperties(@Nullable Context context, boolean isNight,
                                             @NonNull String schemeName) {
        if (ColorSchemeUtils.SCHEME_DEFAULT.equals(schemeName)) {
            // The built-in scheme of the theme: the light array in light mode, the built-in dark
            // colors (i.e. an empty overlay) in night mode.
            return isNight ? new Properties() : ColorSchemeUtils.getBuiltinLightSchemeProperties(context);
        }
        if (ColorSchemeUtils.isMonetScheme(schemeName)) {
            if (context == null) return null;
            return MonetSchemeStore.get(context, isNight,
                    ColorSchemeUtils.monetVariantOf(schemeName));
        }
        return ColorSchemeUtils.loadStylingSchemeProperties(context, schemeName);
    }

    @NonNull
    private static ColorSchemePreview from(@NonNull TerminalColorScheme scheme) {
        final int[] palette = new int[PALETTE_SIZE];
        System.arraycopy(scheme.mDefaultColors, 0, palette, 0, PALETTE_SIZE);
        return new ColorSchemePreview(
                scheme.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND],
                scheme.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND],
                palette);
    }

    /** Composite {@code from} -> {@code to} by {@code ratio} (0 = from, 1 = to), opaque. */
    public static int blend(int from, int to, float ratio) {
        final int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * ratio);
        final int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * ratio);
        final int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ratio);
        return Color.rgb(r, g, b);
    }
}
