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

    /** Fade time shared by every overview-card shadow: hover, reorder lift and swipe lift. */
    static final long OVERVIEW_SHADOW_ANIM_MS = 160L;

    /**
     * Lift transform shared by hover, reorder drag and swipe. The alpha matters: with a semi-
     * transparent preview, the shadow behind the card is only visible through the card itself
     * when its content is translucent too.
     */
    static final float OVERVIEW_CARD_LIFT_SCALE = 1.05f;
    static final float OVERVIEW_CARD_LIFT_ALPHA = 0.82f;

    private FloatingShadow() {
    }

    /** The shared elevation, in pixels. */
    static int elevationPx(@NonNull Resources res) {
        return res.getDimensionPixelSize(R.dimen.floating_shadow_elevation);
    }

    /**
     * Elevation of an overview card's hover/lift shadow: four times the floating controls' shadow,
     * which is the single size used by hover, reorder-lift and swipe-to-delete alike.
     */
    static int overviewCardElevationPx(@NonNull Resources res) {
        return elevationPx(res) * 4;
    }

    /** Colour of that same overview card shadow, with its own alpha fraction. */
    static int overviewCardShadowColor(@NonNull Resources res, boolean isSchemeLight) {
        return color(res, isSchemeLight, R.fraction.session_overview_hover_shadow_alpha);
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
        setShadowColors(view, shadowColor);
    }

    /** Only the colour, so a flight can fade an existing shadow without changing its geometry. */
    static void setShadowColors(@Nullable View view, int shadowColor) {
        if (view == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        view.setOutlineAmbientShadowColor(shadowColor);
        view.setOutlineSpotShadowColor(shadowColor);
    }

    /** Scale a shadow colour's alpha, leaving its RGB and the elevation untouched. */
    static int withAlphaFraction(int color, float fraction) {
        final float f = Math.max(0f, Math.min(1f, fraction));
        final int alpha = Math.round(Color.alpha(color) * f);
        return (alpha << 24) | (color & 0x00FFFFFF);
    }
}
