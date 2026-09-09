package com.termux.shared.termux.monet;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.google.android.material.color.utilities.DislikeAnalyzer;
import com.google.android.material.color.utilities.DynamicScheme;
import com.google.android.material.color.utilities.Hct;
import com.google.android.material.color.utilities.TonalPalette;
import com.termux.shared.logger.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the Monet palettes out of the system - no wallpaper file access, no Python, no
 * private API, no Termux:Style.
 *
 * <p>Android 12 already runs the whole Monet pipeline for us and publishes the result as the public
 * {@code android.R.color.system_*} resources. So instead of "compute Monet" the job is "read the
 * finished tonal palettes and reconstruct them exactly":
 *
 * <pre>
 *   int argb500 = getColor(android.R.color.system_accent1_500);       // tone 50
 *   Hct  h      = Hct.fromInt(argb500);
 *   TonalPalette p = TonalPalette.fromHueAndChroma(h.getHue(), h.getChroma());
 *   p.tone(87);        // bit-for-bit what the system would have generated
 * </pre>
 *
 * <p>A tonal palette is just a {@code (hue, chroma)} pair with a free tone, so sampling any one of
 * the thirteen published tones recovers the whole continuous ramp.
 */
@RequiresApi(Build.VERSION_CODES.S)
public final class SystemPaletteSource {

    private static final String LOG_TAG = "SystemPaletteSource";

    /** Google Blue - the same fallback kde-material-you-colors uses for {@code Score}. */
    public static final int FALLBACK_SEED_ARGB = 0xFF4285F4;

    /** How many ANSI accent slots the terminal palette has. */
    public static final int ACCENT_COUNT = 7;

    private SystemPaletteSource() {}

    /**
     * Snapshot the current system palette + wallpaper colors.
     *
     * <p>Never throws - OEM firmware (MIUI/HyperOS/OneUI) may not implement dynamic color at all,
     * in which case we degrade to Google Blue and let the caller fall back to the default scheme.
     */
    @NonNull
    public static MonetSource read(@NonNull Context context, @NonNull MonetOptions options) {
        Context ctx = context.getApplicationContext();

        WallpaperColors wallpaperColors = null;
        int wallpaperId = -1;
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            wallpaperColors = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            wallpaperId = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to read wallpaper colors: " + e.getMessage());
        }

        int seedArgb = seedFrom(wallpaperColors, ctx, options);
        Palettes palettes = palettes(ctx, options, seedArgb);
        int[] accents = accents(options, palettes, wallpaperColors);

        return new MonetSource(seedArgb, options.variant,
                palettes.primary, palettes.secondary, palettes.tertiary,
                palettes.neutral, palettes.neutralVariant, accents, wallpaperId);
    }

    // ------------------------------------------------------------- Palettes ---

    /** The five M3 tonal palettes, in one bundle. */
    public static final class Palettes {
        @NonNull public final TonalPalette primary;
        @NonNull public final TonalPalette secondary;
        @NonNull public final TonalPalette tertiary;
        @NonNull public final TonalPalette neutral;
        @NonNull public final TonalPalette neutralVariant;

        public Palettes(@NonNull TonalPalette primary, @NonNull TonalPalette secondary,
                        @NonNull TonalPalette tertiary, @NonNull TonalPalette neutral,
                        @NonNull TonalPalette neutralVariant) {
            this.primary = primary;
            this.secondary = secondary;
            this.tertiary = tertiary;
            this.neutral = neutral;
            this.neutralVariant = neutralVariant;
        }
    }

    @NonNull
    static Palettes palettes(@NonNull Context ctx, @NonNull MonetOptions options, int seedArgb) {
        if (!options.variant.isDerived()) {
            try {
                return new Palettes(
                        paletteFrom(ctx, android.R.color.system_accent1_500),
                        paletteFrom(ctx, android.R.color.system_accent2_500),
                        paletteFrom(ctx, android.R.color.system_accent3_500),
                        paletteFrom(ctx, android.R.color.system_neutral1_500),
                        paletteFrom(ctx, android.R.color.system_neutral2_500));
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "system_* palette resources unavailable (" + e.getMessage()
                        + "), deriving from the wallpaper seed instead.");
            }
        }

        // Rebuild all five palettes from the seed, exactly like kde's SchemeX(...) call.
        // Material's Scheme* constructors do not depend on isDark for their palettes, so a single
        // light build is enough to serve both the light and the dark terminal scheme.
        DynamicScheme s = options.variant.create(Hct.fromInt(seedArgb), false, 0.0);
        return new Palettes(s.primaryPalette, s.secondaryPalette, s.tertiaryPalette,
                s.neutralPalette, s.neutralVariantPalette);
    }

    /**
     * Reconstruct a tonal palette from one published {@code system_*_500} entry.
     *
     * <p>Suffix {@code X} maps to {@code tone = 100 - X / 10}, so {@code _500} is tone 50 - the
     * mid-point of the ramp, far enough from both clipping ends to carry a real hue and chroma.
     */
    @NonNull
    static TonalPalette paletteFrom(@NonNull Context ctx, int resId) {
        return ColorMath.paletteFrom(ContextCompat.getColor(ctx, resId));
    }

    // ----------------------------------------------------------------- Seed ---

    static int seedFrom(@Nullable WallpaperColors colors, @NonNull Context ctx,
                        @NonNull MonetOptions options) {
        if (colors != null) {
            android.graphics.Color primary = colors.getPrimaryColor();
            if (primary != null) return primary.toArgb();
        }
        // No wallpaper colors (common on OEM ROMs and on a fresh profile): fall back to the
        // system primary palette so the derived variants still get a sensible seed.
        try {
            return ContextCompat.getColor(ctx, android.R.color.system_accent1_500);
        } catch (Exception e) {
            return FALLBACK_SEED_ARGB;
        }
    }

    // --------------------------------------------------------------- Accents ---

    /**
     * The seven colors that become ANSI 1..7.
     *
     * <p>Android has no public "top N wallpaper colors" API, so we reproduce kde's behaviour:
     * use the real wallpaper colors when they exist, then fill up from the tonal palettes.
     */
    @NonNull
    static int[] accents(@NonNull MonetOptions options, @NonNull Palettes p,
                         @Nullable WallpaperColors colors) {
        switch (options.accentSource) {
            case ROTATIONAL:
                return rotationalAccents(p.primary.getHue(), options.variant.isDerived());
            case PALETTE:
                return fillFromPalettes(new int[0], p);
            case WALLPAPER:
            default:
                return fillFromPalettes(bestWallpaperColors(colors), p);
        }
    }

    /**
     * The colors the system itself picked out of the wallpaper - the very candidates Monet builds
     * its palettes from. Monochrome wallpapers legitimately return {@code null} for the secondary
     * and tertiary slots, and {@code DislikeAnalyzer} rejects the muddy/bile hues Monet avoids.
     */
    @NonNull
    static int[] bestWallpaperColors(@Nullable WallpaperColors colors) {
        List<Integer> best = new ArrayList<>(3);
        if (colors != null) {
            addIfUsable(best, colors.getPrimaryColor());
            addIfUsable(best, colors.getSecondaryColor());
            addIfUsable(best, colors.getTertiaryColor());
        }
        int[] out = new int[best.size()];
        for (int i = 0; i < best.size(); i++) out[i] = best.get(i);
        return out;
    }

    private static void addIfUsable(@NonNull List<Integer> out, @Nullable android.graphics.Color color) {
        if (color == null) return;
        int argb = color.toArgb();
        if (((argb >>> 24) & 0xFF) != 0xFF) return;           // translucent -> not a real wallpaper color
        try {
            if (DislikeAnalyzer.isDisliked(Hct.fromInt(argb))) return;
        } catch (Exception ignored) {
            // DislikeAnalyzer is internal API; if it ever blows up, just accept the color.
        }
        out.add(argb);
    }

    /**
     * kde's fill-up rule, verbatim: pairs of {@code primary[tone]} + {@code tertiary[tone]} with
     * the tone walking 50 → 90 in steps of 8, so seven slots fill in three or four iterations.
     */
    @NonNull
    static int[] fillFromPalettes(@NonNull int[] best, @NonNull Palettes p) {
        int[] acc = new int[ACCENT_COUNT];
        int n = 0;
        for (int i = 0; i < best.length && n < ACCENT_COUNT; i++) acc[n++] = best[i];

        int tone = 50;
        for (int x = 0; x < ACCENT_COUNT && n < ACCENT_COUNT; x++) {
            if (x < best.length) continue;                      // already consumed from the wallpaper
            if (n < ACCENT_COUNT) acc[n++] = ColorMath.tone(p.primary, tone);
            if (n < ACCENT_COUNT) acc[n++] = ColorMath.tone(p.tertiary, tone);
            if (tone < 91) tone += 8;                           // 50, 58, 66, 74, 82, 90, 90
        }
        // Last-resort pad: primary and tertiary can coincide, and a null/empty wallpaper set plus
        // a pathological palette must still yield seven distinct-ish slots.
        while (n < ACCENT_COUNT) acc[n++] = ColorMath.tone(p.primary, 50 + 5 * n);
        return acc;
    }

    /**
     * Hue-rotated accents - the trick M3 itself uses for {@code SchemeRainbow} /
     * {@code SchemeFruitSalad}. Handy when primary and tertiary sit too close together (which
     * Monet's palettes often do) and every filled slot ends up the same hue.
     */
    @NonNull
    static int[] rotationalAccents(double baseHue, boolean isDark) {
        double[] rotations = {0, 60, -60, 120, -120, 180, 30, -30, 90, -90};
        double chroma = 48.0;
        double tone = isDark ? 80.0 : 40.0;
        int[] out = new int[ACCENT_COUNT];
        double[] chosen = new double[ACCENT_COUNT];
        int n = 0;
        for (double rotation : rotations) {
            if (n >= ACCENT_COUNT) break;
            double hue = ((baseHue + rotation) % 360.0 + 360.0) % 360.0;
            boolean tooClose = false;
            for (int i = 0; i < n; i++) {
                double d = Math.abs(chosen[i] - hue);
                if (d > 180) d = 360 - d;
                if (d < 15) { tooClose = true; break; }         // drop hues within 15 degrees
            }
            if (tooClose) continue;
            chosen[n] = hue;
            out[n++] = TonalPalette.fromHueAndChroma(hue, chroma).getHct(tone).toInt();
        }
        while (n < ACCENT_COUNT) {                              // should not happen, but stay total
            out[n] = TonalPalette.fromHueAndChroma((baseHue + 37.0 * n) % 360.0, chroma)
                    .getHct(tone).toInt();
            n++;
        }
        return out;
    }
}
