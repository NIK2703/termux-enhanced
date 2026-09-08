package com.termux.shared.termux.materialyou;

import android.graphics.Color;

import androidx.annotation.NonNull;

import com.google.android.material.color.utilities.Blend;
import com.google.android.material.color.utilities.Contrast;
import com.google.android.material.color.utilities.Hct;
import com.google.android.material.color.utilities.TonalPalette;

import java.util.Locale;

/**
 * Thin wrapper around {@code com.google.android.material.color.utilities}.
 *
 * <p>This is the <b>only</b> class in the project that mentions the Material color utilities
 * package. That package is not part of Material Components' public API surface (it is absent from
 * {@code public.txt}), so if it ever moves or changes, this single file is the only place that
 * needs fixing (or, as a plan B, the ~8 MCU classes can be vendored into the project without
 * touching anything else).
 *
 * <p>Everything here is pure math - no {@link android.content.Context}, no resources, no IPC - so
 * it is trivially unit-testable and safe to call from any thread.
 */
public final class ColorMath {

    private ColorMath() {}

    // ------------------------------------------------------------------ HCT ---

    /** Wrap an ARGB color into HCT. */
    @NonNull
    public static Hct hct(int argb) {
        return Hct.fromInt(argb);
    }

    /** Build an HCT color from components. */
    @NonNull
    public static Hct hct(double hue, double chroma, double tone) {
        return Hct.from(hue, chroma, tone);
    }

    /**
     * Defensive copy of an HCT instance.
     *
     * <p>{@code DynamicColor#getHct()} hands out a <i>cached</i> mutable instance; mutating it
     * would poison the cache for every later read of that role. Always copy before touching.
     */
    @NonNull
    public static Hct copy(@NonNull Hct h) {
        return Hct.from(h.getHue(), h.getChroma(), h.getTone());
    }

    /** L* (tone) of an ARGB color. */
    public static double toneOf(int argb) {
        return Hct.fromInt(argb).getTone();
    }

    // ------------------------------------------------------- Tonal palettes ---

    /**
     * Sample a tonal palette at an arbitrary (fractional) tone.
     *
     * <p>{@link TonalPalette#tone(int)} only accepts ints; {@link TonalPalette#getHct(double)}
     * accepts anything and gives exactly the same result, so we use it for the fractional tones
     * this scheme needs (1, 8, 25, 87 ...).
     */
    public static int tone(@NonNull TonalPalette palette, double tone) {
        return palette.getHct(tone).toInt();
    }

    /** Rebuild a tonal palette from a single ARGB sample (hue + chroma of that sample). */
    @NonNull
    public static TonalPalette paletteFrom(int argb) {
        Hct h = Hct.fromInt(argb);
        return TonalPalette.fromHueAndChroma(h.getHue(), h.getChroma());
    }

    // ------------------------------------------------------------- Contrast ---

    /** WCAG contrast ratio between two <i>tones</i> (L* values). */
    public static double contrastOfTones(double toneA, double toneB) {
        return Contrast.ratioOfTones(toneA, toneB);
    }

    /** WCAG contrast ratio between two ARGB colors, computed through their tones. */
    public static double contrast(int argbA, int argbB) {
        return Contrast.ratioOfTones(toneOf(argbA), toneOf(argbB));
    }

    /** The lightest tone that still reaches {@code ratio} against {@code tone}. */
    public static double lighterTone(double tone, double ratio) {
        return Contrast.lighter(tone, ratio);
    }

    /** The darkest tone that still reaches {@code ratio} against {@code tone}. */
    public static double darkerTone(double tone, double ratio) {
        return Contrast.darker(tone, ratio);
    }

    // --------------------------------------------------------------- Blend ---

    /**
     * Perceptually blend {@code from} towards {@code to}.
     *
     * <p>{@code amount} is the share of {@code to}: 0 keeps {@code from}, 1 returns {@code to}.
     * Uses CAM16-UCS - the space Material itself is built on - instead of kde's Oklab, because
     * CAM16-UCS ships with the Material library on every API level while the platform
     * {@code ColorSpace.Named.OK_LAB} only exists from API 36.
     */
    public static int blend(int from, int to, double amount) {
        return Blend.cam16Ucs(from, to, amount);
    }

    // ------------------------------------------------------------ Utilities ---

    /**
     * Push HSV saturation to 100 % while keeping hue and value.
     *
     * <p>This is kde's {@code scale_saturation(color, 1)} - it is applied to the light-theme
     * accents only and is the reason kde's light schemes look so much punchier than its dark ones.
     */
    public static int maximizeSaturation(int argb) {
        float[] hsv = new float[3];
        Color.colorToHSV(argb, hsv);
        hsv[1] = 1f;
        return Color.HSVToColor(hsv);
    }

    /** {@code #RRGGBB} - the only format {@code TerminalColors#parse()} accepts reliably. */
    @NonNull
    public static String hex(int argb) {
        return String.format(Locale.ROOT, "#%06X", argb & 0x00FFFFFF);
    }

    /** Clamp into [{@code min}, {@code max}]. */
    public static double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}
