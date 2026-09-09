package com.termux.shared.termux.monet;

import androidx.annotation.NonNull;

import com.google.android.material.color.utilities.DynamicColor;
import com.google.android.material.color.utilities.DynamicScheme;
import com.google.android.material.color.utilities.Hct;
import com.google.android.material.color.utilities.MaterialDynamicColors;
import com.google.android.material.color.utilities.TonalPalette;

import java.util.Properties;

/**
 * Turns a {@link MonetSource} snapshot into a terminal color scheme.
 *
 * <p>The output is a plain {@link Properties} in exactly the shape of
 * {@code ~/.termux/colors.properties} ({@code background}, {@code foreground}, {@code cursor},
 * {@code color0..color15}), so it can be fed straight into
 * {@code TerminalColors.COLOR_SCHEME.updateWith(props)}. Nothing below that call - the emulator
 * palette, the renderer, {@code TermuxColorSchemeManager}, the extra-keys panel - needs to know
 * that the colors came from the wallpaper.
 *
 * <p>Only the sixteen ANSI slots are written. {@code color16..color23} are <b>not</b> a "faint"
 * row: {@code TerminalColorScheme.updateWith()} stores {@code colorN} at index {@code N}, and
 * indices 16..231 are the xterm 6&times;6&times;6 cube, so those keys used to overwrite the first
 * eight cube entries.
 *
 * <p>The algorithm mirrors kde-material-you-colors:
 * <ol>
 *   <li><b>background is a surface, not black</b> - the terminal canvas is {@code surface},
 *       exactly what every M3 app paints.</li>
 *   <li><b>every accent must clear a hard contrast threshold against that background</b>, plus a
 *       constant 12 % pull towards the neutral ramp. This is what separates a readable scheme from
 *       a pretty unreadable one.</li>
 *   <li><b>the same seven accents appear at two intensities</b> (normal / intense) so the palette
 *       is a coherent ramp rather than sixteen unrelated colors.</li>
 * </ol>
 *
 * <p><b>Both night modes read the same way:</b> slot 0 is the darkest swatch, slot 7 the lightest,
 * and row 8..15 is the lighter, more intense twin of row 0..7. That is the one property every
 * classic 16-colour ramp has, and it is why the light scheme needs two extra steps - see
 * {@link #build(MonetSource, boolean, MonetOptions)}.
 */
public final class TerminalPaletteBuilder {

    private static final String LOG_TAG = "TerminalPaletteBuilder";

    /** kde's minimum contrast for the foreground against the background. */
    public static final double MIN_FOREGROUND_CONTRAST = 7.0;
    /** Minimum contrast for the cursor against the background. */
    public static final double MIN_CURSOR_CONTRAST = 3.0;

    /** kde's constant neutral pull applied to every accent ({@code blend2contrast} else-branch). */
    public static final double ACCENT_NEUTRAL_PULL = 0.12;

    private TerminalPaletteBuilder() {}

    /**
     * Build one terminal scheme.
     *
     * @param source The palette snapshot (shared by the light and the dark build).
     * @param isDark Whether to build the night variant.
     * @param options The {@code monet-*} tunables.
     * @return Properties containing only keys {@code TerminalColorScheme} understands.
     */
    @NonNull
    public static Properties build(@NonNull MonetSource source, boolean isDark,
                                   @NonNull MonetOptions options) {
        DynamicScheme scheme = new DynamicScheme(
                Hct.fromInt(source.seedArgb),
                options.variant.variant,
                isDark,
                0.0,
                source.primary, source.secondary, source.tertiary,
                source.neutral, source.neutralVariant);

        // Roles are INSTANCE methods on MaterialDynamicColors (verified in the 1.12.0 bytecode).
        MaterialDynamicColors mdc = new MaterialDynamicColors();

        // ---- background / foreground / cursor --------------------------------------------
        // Tone multiplier only applies to backgrounds - same rule kde uses (is_background).
        final int bg = role(scheme, mdc, options.background, isDark, options.tone);
        final double bgTone = ColorMath.toneOf(bg);

        final int onSurface = ColorMath.copy(mdc.onSurface().getHct(scheme)).toInt();
        final int cursorRole = onSurface;

        // secondary[90] on dark / secondary[25] on light - kde's "bright reference".
        final int brightRef = ColorMath.tone(source.secondary, isDark ? 90 : 25);
        // "paper" the normal row is blended against.
        final int ref = ColorMath.tone(source.neutral, isDark ? 99 : 1);
        // Paper for the "intense" row. It has to be the LIGHT end in both night modes: the
        // intense row being lighter than the normal row is the one property every classic
        // 16-colour ramp shares. Blending it towards the near-black paper instead (what the
        // light scheme used to do) produced a row 8..15 that was darker than row 0..7, i.e. a
        // ramp running backwards.
        final int intensePaper = ColorMath.tone(source.neutral, isDark ? 99 : 92);
        // neutral used by the constant 12 % pull of blend2contrast().
        final int contrastRef = ColorMath.tone(source.neutral, isDark ? 99 : 10);

        int foreground = ColorMath.blend(bg, brightRef, 0.98);
        if (ColorMath.contrast(foreground, bg) < MIN_FOREGROUND_CONTRAST) {
            foreground = enforceContrast(onSurface, bg, MIN_FOREGROUND_CONTRAST, isDark);
        }
        int cursor = ColorMath.contrast(cursorRole, bg) >= MIN_CURSOR_CONTRAST
                ? cursorRole
                : enforceContrast(onSurface, bg, MIN_CURSOR_CONTRAST, isDark);

        // ---- the seven accents -------------------------------------------------------------
        final double minAccent = options.accentContrastFor(isDark);
        final int[] accents = new int[source.accents.length];
        for (int i = 0; i < source.accents.length; i++) {
            int c = source.accents[i];

            // kde cranks saturation to the maximum in the LIGHT scheme only (scale_saturation 1.0
            // == HSV s = 1). Doing it before the contrast step keeps the tone guarantee intact:
            // the 12 % pull below only moves the color further away from the background.
            if (!isDark) c = ColorMath.maximizeSaturation(c);

            c = enforceContrast(c, bg, minAccent, isDark);
            c = ColorMath.blend(c, contrastRef, ACCENT_NEUTRAL_PULL);

            if (options.chroma != 1.0) {
                Hct h = ColorMath.hct(c);
                h.setChroma(h.getChroma() * options.chroma);
                c = h.toInt();
            }
            accents[i] = c;
        }
        sortByLuminance(accents);

        // ---- re-space the ramp (light scheme only) -------------------------------------------
        // On a light background maximizeSaturation() plus the contrast floor squash all seven
        // accents into a narrow dark band (measured: L* 25.6..36.8 for a Google-Blue seed), and
        // what is left of the ramp then runs *towards* the background instead of away from it.
        // Re-space them over the band the background actually allows, so slot 1 is the dark end
        // and slot 7 the light end - the same direction as the classic palette and as the dark
        // scheme. The dark scheme is deliberately left alone: there the accents already land on
        // a wide, monotonic ramp.
        double rampLo = 0.0;
        if (!isDark) {
            // The end that faces the background: the closest an accent may get to it while still
            // clearing the contrast floor (this is the *lightest* readable tone on light).
            double near = ColorMath.darkerTone(bgTone, minAccent);
            if (Double.isNaN(near) || near < 0.0 || near > 100.0) near = 70.0;
            // The far end: as dark as the foreground goes, so the ramp uses the whole range.
            double far = Math.max(20.0, ColorMath.toneOf(foreground));
            rampLo = Math.min(near, far);
            final double rampHi = Math.max(near, far);
            for (int i = 0; i < accents.length; i++) {
                double t = accents.length == 1
                        ? rampHi
                        : rampLo + (rampHi - rampLo) * i / (accents.length - 1);
                Hct h = ColorMath.hct(accents[i]);
                h.setTone(t);
                accents[i] = enforceContrast(h.toInt(), bg, minAccent, isDark);
            }
        }

        // ---- slot layout: three tiers of eight ----------------------------------------------
        final Properties props = new Properties();
        props.setProperty("background", ColorMath.hex(bg));
        props.setProperty("foreground", ColorMath.hex(foreground));
        props.setProperty("cursor", ColorMath.hex(cursor));

        // On a light background slot 0 must be the darkest swatch, not the background: with
        // color0 = background the row read "brightest first", which is the inversion the
        // 16-colour test makes obvious.
        props.setProperty("color0", ColorMath.hex(
                options.color0 == MonetOptions.Color0.DIM
                        ? ColorMath.blend(bg, foreground, isDark ? 0.15 : 0.60)
                        : (isDark ? bg : ColorMath.tone(source.neutral, Math.max(4.0, rampLo - 14)))));
        props.setProperty("color8", ColorMath.hex(
                isDark
                        ? ColorMath.blend(bg, brightRef, 0.80)
                        : ColorMath.tone(source.neutral, Math.max(8.0, rampLo - 6))));

        for (int i = 0; i < accents.length; i++) {
            int accent = enforceContrast(accents[i], bg, minAccent, isDark);
            accents[i] = accent;
            props.setProperty("color" + (i + 1), ColorMath.hex(ColorMath.blend(ref, accent, 0.95)));
            props.setProperty("color" + (i + 9), ColorMath.hex(ColorMath.blend(intensePaper, accent, 0.82)));
        }

        return props;
    }

    // ------------------------------------------------------------------ roles ---

    /**
     * Resolve the background role and apply kde's {@code tone_mult}.
     *
     * <p>kde clips the multiplier asymmetrically ({@code 0.0} floor on dark, {@code 0.5} on light)
     * so a light scheme can never be pushed to pure black.
     */
    private static int role(@NonNull DynamicScheme scheme, @NonNull MaterialDynamicColors mdc,
                            @NonNull MonetOptions.Background background, boolean isDark,
                            double toneMultiplier) {
        DynamicColor role;
        switch (background) {
            case CONTAINER_LOW:
                role = mdc.surfaceContainerLow();
                break;
            case CONTAINER_LOWEST:
                role = mdc.surfaceContainerLowest();
                break;
            case SURFACE:
            default:
                role = mdc.surface();
                break;
        }
        Hct hct = ColorMath.copy(role.getHct(scheme));
        if (toneMultiplier != 1.0) {
            double multiplier = ColorMath.clamp(toneMultiplier, isDark ? 0.0 : 0.5, 1.5);
            hct.setTone(ColorMath.clamp(hct.getTone() * multiplier, 0.0, 100.0));
        }
        return hct.toInt();
    }

    // --------------------------------------------------------------- contrast ---

    /**
     * Push {@code color} along the tone axis until it reaches {@code minRatio} against {@code bg}.
     *
     * <p>kde searches for the blending ratio in RGB with a 0.01 step loop; Material gives us the
     * same answer in one call ({@code Contrast.lighter} / {@code Contrast.darker}) and does it in
     * HCT, which is perceptually uniform. O(1) and deterministic instead of up to 100 iterations.
     */
    private static int enforceContrast(int color, int bg, double minRatio, boolean wantLighter) {
        if (ColorMath.contrast(color, bg) >= minRatio) return color;

        double bgTone = ColorMath.toneOf(bg);
        double needed = wantLighter
                ? ColorMath.lighterTone(bgTone, minRatio)
                : ColorMath.darkerTone(bgTone, minRatio);
        if (Double.isNaN(needed) || needed < 0.0 || needed > 100.0) {
            needed = wantLighter ? 100.0 : 0.0;
        }

        Hct hct = ColorMath.hct(color);
        hct.setTone(needed);
        int out = hct.toInt();
        if (ColorMath.contrast(out, bg) < minRatio) {
            // Should not happen, but stay total: slam the tone to the extreme end.
            hct = ColorMath.hct(color);
            hct.setTone(wantLighter ? 100.0 : 0.0);
            out = hct.toInt();
        }
        return out;
    }

    /**
     * kde's {@code sort_colors_luminance}: order the accents dark → light so the ANSI ramp is
     * monotonic in brightness instead of jumping around.
     *
     * <p>Ascending is the direction for <b>both</b> night modes: a classic 16-colour ramp always
     * runs dark → light. What differs is which end sits next to the background, and that is the
     * job of the re-spacing step in {@link #build(MonetSource, boolean, MonetOptions)}, not of
     * this sort.
     */
    private static void sortByLuminance(@NonNull int[] colors) {
        final double[] tones = new double[colors.length];
        for (int i = 0; i < colors.length; i++) tones[i] = ColorMath.toneOf(colors[i]);
        // Insertion sort - n is 7 and the array is nearly sorted already.
        for (int i = 1; i < colors.length; i++) {
            int c = colors[i];
            double t = tones[i];
            int j = i - 1;
            while (j >= 0 && tones[j] > t) {
                colors[j + 1] = colors[j];
                tones[j + 1] = tones[j];
                j--;
            }
            colors[j + 1] = c;
            tones[j + 1] = t;
        }
    }

    /** Exposed for diagnostics: the palette a snapshot resolves to, without slot layout. */
    @NonNull
    static TonalPalette[] palettesOf(@NonNull MonetSource source) {
        return new TonalPalette[]{
                source.primary, source.secondary, source.tertiary,
                source.neutral, source.neutralVariant};
    }
}
