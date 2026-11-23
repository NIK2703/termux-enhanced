package com.termux.app.terminal;

import android.content.res.Resources;
import android.graphics.Color;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;

/**
 * The elevation and colour pair shared by every surface that floats over the terminal.
 *
 * <p>The input-panel controls and the scrollbar thumb share this shadow. Overview thumbnails
 * deliberately cast no shadow; they use a thin outline in the floating controls' stroke colour
 * instead. The colour follows the terminal scheme: a dark shadow on a light scheme, a light one on
 * a dark scheme. A black shadow would simply disappear on a dark terminal. Below API 28 the
 * platform only exposes the theme's shadow colour, so the elevation is still the same but the
 * colour is whatever the theme supplies.</p>
 */
final class FloatingShadow {

    private FloatingShadow() {
    }

    /** The shared elevation, in pixels. */
    static int elevationPx(@NonNull Resources res) {
        return res.getDimensionPixelSize(R.dimen.floating_shadow_elevation);
    }

    /**
     * Build the shadow colour for the given scheme and alpha fraction resource.
     *
     * @param alphaFractionResId a fraction resource such as
     *                           {@link R.fraction#floating_shadow_alpha}.
     */
    static int color(@NonNull Resources res, boolean isSchemeLight, int alphaFractionResId) {
        final float alpha = res.getFraction(alphaFractionResId, 1, 1);
        final int channel = isSchemeLight ? 0 : 255;
        return Color.argb(Math.round(alpha * 255), channel, channel, channel);
    }

    /**
     * Apply the shared elevation and, on API 28+, both shadow colours. A view has an ambient and a
     * spot shadow; setting only one leaves the other at the theme default.
     */
    static void apply(@Nullable View view, int elevationPx, int shadowColor) {
        if (view == null) return;
        view.setElevation(elevationPx);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.setOutlineAmbientShadowColor(shadowColor);
            view.setOutlineSpotShadowColor(shadowColor);
        }
    }
}
