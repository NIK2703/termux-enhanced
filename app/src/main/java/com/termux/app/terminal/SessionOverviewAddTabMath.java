package com.termux.app.terminal;

import android.graphics.RectF;

import androidx.annotation.NonNull;

/**
 * Pure timing and geometry math for the add-session flight from the overview "+" button.
 *
 * <p>The controller feeds the animator's eased 0..1 progress through these functions; nothing here
 * touches views or bitmaps so the phase boundaries are testable on the JVM.</p>
 */
public final class SessionOverviewAddTabMath {

    private SessionOverviewAddTabMath() {}

    /** Total flight time; same 300 ms as the rest of the overview flights. */
    public static final long FLIGHT_DURATION_MS = SessionOverviewTransitionMath.DURATION_MS;

    /** Progress by which the "+" icon has fully faded out. */
    public static final float PLUS_FADE_END = 0.30f;

    /** Progress at which the card snapshot starts fading in. */
    public static final float CONTENT_FADE_START = 0.55f;

    /** Progress at which the card snapshot is fully opaque. */
    public static final float CONTENT_FADE_END = 0.90f;

    /** The add button comes back when the old button's shape is halfway to the card. */
    public static final float ADD_BUTTON_REVEAL_PROGRESS = 0.5f;

    /** The button shadow is fully gone by the time the shape is halfway to the card. */
    public static final float SHADOW_FADE_END = 0.5f;

    /** Whether the returning add button should start its reveal on this frame. */
    public static boolean shouldRevealAddButton(float progress) {
        return shapeProgress(progress) >= ADD_BUTTON_REVEAL_PROGRESS;
    }

    /** Opacity of the "+" icon during the flight. */
    public static float plusAlpha(float progress) {
        final float p = SessionOverviewTransitionMath.clamp01(progress);
        if (p >= PLUS_FADE_END) return 0f;
        return 1f - p / PLUS_FADE_END;
    }

    /** Opacity of the card snapshot (preview + tab + border) during the flight. */
    public static float contentAlpha(float progress) {
        final float p = SessionOverviewTransitionMath.clamp01(progress);
        if (p <= CONTENT_FADE_START) return 0f;
        if (p >= CONTENT_FADE_END) return 1f;
        return (p - CONTENT_FADE_START) / (CONTENT_FADE_END - CONTENT_FADE_START);
    }

    /** Shape progress; the rect and radius morph over the whole flight. */
    public static float shapeProgress(float progress) {
        return SessionOverviewTransitionMath.clamp01(progress);
    }

    /** The button shadow is fully visible at the start and gone by the halfway point. */
    public static float shadowAlpha(float progress) {
        final float p = shapeProgress(progress);
        if (p >= SHADOW_FADE_END) return 0f;
        return 1f - p / SHADOW_FADE_END;
    }

    /** Stroke width morphs from the button's hairline to the card's outline. */
    public static float strokeWidth(float progress, float startWidth, float endWidth) {
        return SessionOverviewTransitionMath.lerp(
                Math.max(0f, startWidth), Math.max(0f, endWidth), shapeProgress(progress));
    }

    /** Interpolated corner radius, clamped non-negative. */
    public static float cornerRadius(float progress, float startRadius, float endRadius) {
        final float start = Math.max(0f, startRadius);
        final float end = Math.max(0f, endRadius);
        return SessionOverviewTransitionMath.lerp(start, end, shapeProgress(progress));
    }

    /** Pivot radius for a circular source of the given size. */
    public static float sourceRadius(float width, float height) {
        if (width <= 0f || height <= 0f) return 0f;
        return Math.min(width, height) / 2f;
    }

    /** Per-component lerp for a {@link RectF}. */
    public static void lerpRect(@NonNull RectF out, @NonNull RectF start,
                                @NonNull RectF end, float progress) {
        final float p = SessionOverviewTransitionMath.clamp01(progress);
        out.set(
                SessionOverviewTransitionMath.lerp(start.left, end.left, p),
                SessionOverviewTransitionMath.lerp(start.top, end.top, p),
                SessionOverviewTransitionMath.lerp(start.right, end.right, p),
                SessionOverviewTransitionMath.lerp(start.bottom, end.bottom, p));
    }
}
