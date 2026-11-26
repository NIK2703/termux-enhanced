package com.termux.app.terminal;

import android.animation.ValueAnimator;

import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;

/**
 * The shared small fade-and-shrink used by overview items: a card closed from its X button, or the
 * add button when the session limit is reached. {@link #animator(View)} is its exact time reverse:
 * a newly appended card, or the add button returning after a tab closed.
 *
 * <p>Both directions use the same duration, scale endpoint and easing, so the call sites cannot
 * drift apart. The caller owns the view's other properties (for example a card's translation or
 * the add button's icon alpha).</p>
 */
public final class OverviewAppearanceAnimation {

    /** Curve used by the X-button close that this reveal mirrors. */
    public static final PathInterpolator CLOSE_INTERPOLATOR =
            new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    /** Time reverse of {@link #CLOSE_INTERPOLATOR}: 1 - close(1 - progress). */
    private static final PathInterpolator INTERPOLATOR =
            new PathInterpolator(0.8f, 0f, 0.6f, 1f);

    private OverviewAppearanceAnimation() {}

    /** Hide the view at just under its final size before its first laid-out frame. */
    public static void arm(@NonNull View view) {
        view.setAlpha(0f);
        view.setScaleX(SessionOverviewTransitionMath.ADD_APPEAR_START_SCALE);
        view.setScaleY(SessionOverviewTransitionMath.ADD_APPEAR_START_SCALE);
    }

    /** Force the final visual values in case an animation was cut short. */
    public static void reset(@NonNull View view) {
        view.setAlpha(1f);
        view.setScaleX(1f);
        view.setScaleY(1f);
    }

    /** Scale around the measured centre rather than the view's top-left corner. */
    public static void pivotToCenter(@NonNull View view) {
        view.setPivotX(view.getWidth() / 2f);
        view.setPivotY(view.getHeight() / 2f);
    }

    /**
     * Build the reveal animator without starting it, so the caller can add its own properties
     * (such as a card's settling translation) before the single start.
     */
    @NonNull
    public static ViewPropertyAnimator animator(@NonNull View view) {
        return view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(SessionOverviewTransitionMath.ADD_APPEAR_DURATION_MS)
                .setInterpolator(INTERPOLATOR);
    }

    /**
     * Build the shrink-and-fade animator without starting it. The caller adds its own end action
     * (finish the session, hide the button, ...) so the same motion drives every overview item.
     */
    @NonNull
    public static ValueAnimator hideAnimator(@NonNull View view) {
        final float fromAlpha = view.getAlpha();
        final float fromScale = view.getScaleX();
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(SessionOverviewTransitionMath.ADD_APPEAR_DURATION_MS);
        animator.setInterpolator(CLOSE_INTERPOLATOR);
        animator.addUpdateListener(animation -> {
            final float progress = (float) animation.getAnimatedValue();
            view.setAlpha(fromAlpha * (1f - progress));
            final float scale = SessionOverviewTransitionMath.lerp(
                    fromScale, SessionOverviewTransitionMath.ADD_APPEAR_START_SCALE, progress);
            view.setScaleX(scale);
            view.setScaleY(scale);
        });
        return animator;
    }
}
