package com.termux.app.terminal;

import android.graphics.RectF;

import androidx.annotation.NonNull;

/**
 * Pure timing and geometry for the gesture that releases over the overview "+" button and morphs
 * that button into the newly opened terminal.
 *
 * <p>The source ghost is visible only for the first quarter of the flight. The terminal frame
 * starts completely transparent, becomes opaque exactly when the source is gone, and keeps the
 * source's rounded outline until the expansion removes it. Nothing here touches views, so every
 * boundary can be tested on the JVM.</p>
 */
public final class SessionOverviewAddButtonTerminalMath {

    private SessionOverviewAddButtonTerminalMath() {}

    /** Progress by which the source button ghost has completely faded out. */
    public static final float HANDOFF_END = 0.25f;

    /** Progress by which the source "+" icon has completely faded out. */
    public static final float PLUS_FADE_END = 0.15f;

    /** Progress by which the terminal border and inherited shadow are fully transparent. */
    public static final float SHADOW_FADE_END = 0.5f;

    /** 0..1 hand-off ramp for the source and the terminal frame. */
    public static float handoff(float progress) {
        return SessionOverviewTransitionMath.clamp01(
                progress / HANDOFF_END);
    }

    /** Source button opacity: the button's frozen alpha faded out over the hand-off. */
    public static float sourceAlpha(float buttonAlpha, float progress) {
        return SessionOverviewTransitionMath.clamp01(buttonAlpha)
                * (1f - handoff(progress));
    }

    /** Source "+" icon opacity: fades a little earlier than the button body. */
    public static float sourcePlusAlpha(float imageAlpha, float progress) {
        final float p = SessionOverviewTransitionMath.clamp01(progress);
        final float fade = 1f - SessionOverviewTransitionMath.clamp01(p / PLUS_FADE_END);
        return SessionOverviewTransitionMath.clamp01(imageAlpha) * fade;
    }

    /** Terminal frame image opacity: zero on the first frame, one at the hand-off end. */
    public static float terminalContentAlpha(float progress) {
        return handoff(progress);
    }

    /**
     * Terminal frame outline opacity. It rises with the image during the hand-off, then fades
     * with the inherited shadow and reaches zero halfway through the expansion.
     */
    public static float terminalBorderAlpha(float progress) {
        return handoff(progress) * terminalShadowAlpha(progress);
    }

    /** Inherited elevation-shadow alpha; zero at the halfway point. */
    public static float terminalShadowAlpha(float progress) {
        final float p = SessionOverviewTransitionMath.clamp01(progress);
        return 1f - SessionOverviewTransitionMath.clamp01(p / SHADOW_FADE_END);
    }

    /** Corner radius morphs from the source circle to a square over the whole flight. */
    public static float radius(float progress, float startRadius) {
        final float start = Math.max(0f, startRadius);
        return SessionOverviewTransitionMath.lerp(
                start, 0f, SessionOverviewTransitionMath.clamp01(progress));
    }

    /** Stroke starts at the button hairline and becomes the card stroke during the hand-off. */
    public static float strokeWidth(float progress, float startWidth, float endWidth) {
        return SessionOverviewTransitionMath.lerp(
                Math.max(0f, startWidth), Math.max(0f, endWidth), handoff(progress));
    }

    /** Pivot-aware visual bounds of a scaled view in its parent's coordinate space. */
    public static void visualRect(@NonNull RectF out,
                                  float layoutLeft, float layoutTop,
                                  float width, float height,
                                  float pivotX, float pivotY,
                                  float scaleX, float scaleY) {
        final float[] bounds = new float[4];
        visualRect(bounds, layoutLeft, layoutTop, width, height,
                pivotX, pivotY, scaleX, scaleY);
        out.set(bounds[0], bounds[1], bounds[2], bounds[3]);
    }

    /**
     * Primitive-array overload for the JVM tests. The Android {@link RectF} constructor is a
     * stub outside an instrumented or Robolectric run, so the geometry itself stays free of
     * Android types.
     */
    public static void visualRect(@NonNull float[] out,
                                  float layoutLeft, float layoutTop,
                                  float width, float height,
                                  float pivotX, float pivotY,
                                  float scaleX, float scaleY) {
        if (out.length < 4) throw new IllegalArgumentException("out needs four elements");
        final float sx = Float.isFinite(scaleX) ? scaleX : 1f;
        final float sy = Float.isFinite(scaleY) ? scaleY : 1f;
        final float left = layoutLeft + pivotX - pivotX * sx;
        final float top = layoutTop + pivotY - pivotY * sy;
        final float right = left + width * sx;
        final float bottom = top + height * sy;
        out[0] = Math.min(left, right);
        out[1] = Math.min(top, bottom);
        out[2] = Math.max(left, right);
        out[3] = Math.max(top, bottom);
    }

    /** Radius of the circular button source. */
    public static float sourceRadius(float width, float height) {
        if (width <= 0f || height <= 0f) return 0f;
        return Math.min(width, height) / 2f;
    }

    /**
     * Size of the "+" glyph inside the visual button. The view's padding is in layout pixels,
     * while the hover scale applies to the whole content area, so the padding and the content
     * both scale with the button.
     */
    public static float scaledContentSize(float width, float height,
                                          float padding, float scale) {
        final float content = Math.min(width, height) - Math.max(0f, padding);
        if (content <= 0f) return 1f;
        final float s = Float.isFinite(scale) ? Math.abs(scale) : 1f;
        return Math.max(1f, content * s);
    }
}
