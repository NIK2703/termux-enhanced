package com.termux.shared.termux.materialyou;

import androidx.annotation.NonNull;

import com.google.android.material.color.utilities.TonalPalette;

import java.util.Arrays;

/**
 * An immutable snapshot of everything the palette builder needs.
 *
 * <p>One snapshot serves <b>both</b> the light and the dark scheme: the palettes themselves do not
 * depend on the night mode, only the roles derived from them do. Building both variants up front
 * from a single snapshot is what makes switching night mode free (see
 * {@link MaterialYouSchemeStore}).
 */
public final class MaterialYouSource {

    /** The seed the palettes were derived from (or the system's primary wallpaper color). */
    public final int seedArgb;

    /** M3 {@code primary} - {@code android.R.color.system_accent1_*}. */
    @NonNull public final TonalPalette primary;
    /** M3 {@code secondary} - {@code android.R.color.system_accent2_*}. */
    @NonNull public final TonalPalette secondary;
    /** M3 {@code tertiary} - {@code android.R.color.system_accent3_*}. */
    @NonNull public final TonalPalette tertiary;
    /** M3 {@code neutral} - {@code android.R.color.system_neutral1_*}. */
    @NonNull public final TonalPalette neutral;
    /** M3 {@code neutralVariant} - {@code android.R.color.system_neutral2_*}. */
    @NonNull public final TonalPalette neutralVariant;

    /** Up to seven ARGB candidates for ANSI slots 1..7; never {@code null}, never empty. */
    @NonNull public final int[] accents;

    /** The variant this snapshot was built for - folded into {@link #token}. */
    @NonNull public final SchemeVariant variant;

    /** {@code WallpaperManager.getWallpaperId(FLAG_SYSTEM)}, or {@code -1} when unavailable. */
    public final int wallpaperId;

    /** Changes whenever the wallpaper, the system palette or the variant changes. */
    public final long token;

    public MaterialYouSource(int seedArgb, @NonNull SchemeVariant variant,
                             @NonNull TonalPalette primary, @NonNull TonalPalette secondary,
                             @NonNull TonalPalette tertiary, @NonNull TonalPalette neutral,
                             @NonNull TonalPalette neutralVariant, @NonNull int[] accents,
                             int wallpaperId) {
        this.seedArgb = seedArgb;
        this.variant = variant;
        this.primary = primary;
        this.secondary = secondary;
        this.tertiary = tertiary;
        this.neutral = neutral;
        this.neutralVariant = neutralVariant;
        this.accents = accents;
        this.wallpaperId = wallpaperId;
        this.token = computeToken();
    }

    private long computeToken() {
        long h = 1125899906842597L; // FNV-ish odd prime
        h = mix(h, seedArgb);
        h = mix(h, variant.ordinal());
        h = mix(h, wallpaperId);
        h = mix(h, primary.getHct(50).toInt());
        h = mix(h, secondary.getHct(50).toInt());
        h = mix(h, tertiary.getHct(50).toInt());
        h = mix(h, neutral.getHct(50).toInt());
        h = mix(h, neutralVariant.getHct(50).toInt());
        for (int accent : accents) h = mix(h, accent);
        return h == 0 ? 1 : h;
    }

    private static long mix(long h, long v) {
        h ^= v + 0x9E3779B97F4A7C15L + (h << 6) + (h >>> 2);
        return h;
    }

    @NonNull
    @Override
    public String toString() {
        return "MaterialYouSource{variant=" + variant.key
                + ", seed=#" + Integer.toHexString(seedArgb & 0xFFFFFF)
                + ", accents=" + accents.length
                + ", wallpaperId=" + wallpaperId
                + ", token=" + token + "}";
    }

    /** Equality by content, so two identical snapshots compare equal. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MaterialYouSource)) return false;
        MaterialYouSource other = (MaterialYouSource) o;
        return token == other.token
                && seedArgb == other.seedArgb
                && variant == other.variant
                && Arrays.equals(accents, other.accents);
    }

    @Override
    public int hashCode() {
        return (int) (token ^ (token >>> 32));
    }
}
