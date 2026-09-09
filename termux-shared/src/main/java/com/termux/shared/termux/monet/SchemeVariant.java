package com.termux.shared.termux.monet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.utilities.DynamicScheme;
import com.google.android.material.color.utilities.Hct;
import com.google.android.material.color.utilities.SchemeContent;
import com.google.android.material.color.utilities.SchemeExpressive;
import com.google.android.material.color.utilities.SchemeFidelity;
import com.google.android.material.color.utilities.SchemeFruitSalad;
import com.google.android.material.color.utilities.SchemeMonochrome;
import com.google.android.material.color.utilities.SchemeNeutral;
import com.google.android.material.color.utilities.SchemeRainbow;
import com.google.android.material.color.utilities.SchemeTonalSpot;
import com.google.android.material.color.utilities.SchemeVibrant;
import com.google.android.material.color.utilities.Variant;

import java.util.Locale;

/**
 * The "color scheme type" of kde-material-you-colors ({@code --scheme-variant}).
 *
 * <p>A variant decides all five tonal palettes at once, so it is chosen first and everything
 * downstream (roles, ANSI accents, slot layout) is identical for every variant.
 *
 * <p>{@link #SYSTEM} is the odd one out: it is not a Material variant at all. It means "read the
 * palettes the system already computed" ({@code android.R.color.system_*}) instead of rebuilding
 * them from a seed. It is the default because it makes the terminal match the rest of the device
 * bit-for-bit.
 */
public enum SchemeVariant {

    /** Use {@code android.R.color.system_*} directly - no re-derivation. */
    SYSTEM(-1, "system", Variant.TONAL_SPOT),

    CONTENT(0, "content", Variant.CONTENT),
    EXPRESSIVE(1, "expressive", Variant.EXPRESSIVE),
    FIDELITY(2, "fidelity", Variant.FIDELITY),
    MONOCHROME(3, "monochrome", Variant.MONOCHROME),
    NEUTRAL(4, "neutral", Variant.NEUTRAL),
    TONAL_SPOT(5, "tonal-spot", Variant.TONAL_SPOT),
    VIBRANT(6, "vibrant", Variant.VIBRANT),
    RAINBOW(7, "rainbow", Variant.RAINBOW),
    FRUIT_SALAD(8, "fruit-salad", Variant.FRUIT_SALAD);

    /** Alias of {@link #TONAL_SPOT} - kde's own default. */
    public static final SchemeVariant DEFAULT = SYSTEM;

    /** The same number kde uses for {@code --scheme-variant}; {@code -1} for {@link #SYSTEM}. */
    public final int kdeIndex;

    /** The value accepted by {@code monet-variant} in termux.properties. */
    @NonNull
    public final String key;

    /**
     * The Material {@link Variant} used when a {@link DynamicScheme} has to be assembled by hand
     * from externally supplied palettes (the {@link #SYSTEM} path). It is a stub there - the role
     * tones that matter ({@code surface}, {@code onSurface}) come from {@code neutralPalette}, and
     * {@code primary}/{@code secondary} come from the supplied palettes, so the stub does not
     * influence the result.
     */
    @NonNull
    public final Variant variant;

    SchemeVariant(int kdeIndex, @NonNull String key, @NonNull Variant variant) {
        this.kdeIndex = kdeIndex;
        this.key = key;
        this.variant = variant;
    }

    /** Whether this variant rebuilds palettes from a seed instead of reading the system ones. */
    public boolean isDerived() {
        return this != SYSTEM;
    }

    /**
     * Build the {@link DynamicScheme} for this variant.
     *
     * <p>This is deliberately the only {@code switch} over the nine Material scheme classes in the
     * project; everything else talks to {@link DynamicScheme} generically.
     */
    @NonNull
    public DynamicScheme create(@NonNull Hct sourceColorHct, boolean isDark, double contrastLevel) {
        switch (this) {
            case CONTENT:
                return new SchemeContent(sourceColorHct, isDark, contrastLevel);
            case EXPRESSIVE:
                return new SchemeExpressive(sourceColorHct, isDark, contrastLevel);
            case FIDELITY:
                return new SchemeFidelity(sourceColorHct, isDark, contrastLevel);
            case MONOCHROME:
                return new SchemeMonochrome(sourceColorHct, isDark, contrastLevel);
            case NEUTRAL:
                return new SchemeNeutral(sourceColorHct, isDark, contrastLevel);
            case TONAL_SPOT:
                return new SchemeTonalSpot(sourceColorHct, isDark, contrastLevel);
            case VIBRANT:
                return new SchemeVibrant(sourceColorHct, isDark, contrastLevel);
            case RAINBOW:
                return new SchemeRainbow(sourceColorHct, isDark, contrastLevel);
            case FRUIT_SALAD:
                return new SchemeFruitSalad(sourceColorHct, isDark, contrastLevel);
            case SYSTEM:
            default:
                // SYSTEM has no scheme of its own - callers must use the palette-based
                // DynamicScheme constructor. Falling back to TonalSpot keeps the enum total.
                return new SchemeTonalSpot(sourceColorHct, isDark, contrastLevel);
        }
    }

    /**
     * Parse a {@code monet-variant} value.
     *
     * <p>Accepts the key name ({@code rainbow}), the kde index ({@code 7}) and the enum constant
     * name ({@code RAINBOW}), case-insensitively; surrounding quotes are stripped, which keeps
     * hand-edited termux.properties forgiving.
     *
     * @return the matching variant, or {@link #DEFAULT} when the value is empty or unknown.
     */
    @NonNull
    public static SchemeVariant parse(@Nullable String value) {
        if (value == null) return DEFAULT;
        String v = value.trim();
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"')
            v = v.substring(1, v.length() - 1).trim();
        if (v.isEmpty()) return DEFAULT;

        for (SchemeVariant variant : values()) {
            if (variant.key.equalsIgnoreCase(v) || variant.name().equalsIgnoreCase(v)) return variant;
        }
        // kde index (0..8), and -1 for system.
        try {
            int index = Integer.parseInt(v);
            for (SchemeVariant variant : values()) {
                if (variant.kdeIndex == index) return variant;
            }
        } catch (NumberFormatException ignored) {
            // not a number and not a name - fall through to the default
        }
        return DEFAULT;
    }

    /** The value to write back into termux.properties. */
    @NonNull
    public String toPropertyValue() {
        return key;
    }

    @NonNull
    @Override
    public String toString() {
        return key.toLowerCase(Locale.ROOT);
    }
}
