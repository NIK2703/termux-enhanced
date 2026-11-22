package com.termux.app.terminal;

/**
 * Pure decisions for the overview's horizontal swipe-to-close gesture.
 *
 * <p>The card view owns touch tracking and animation; these functions only answer "is this a
 * dismiss?", "how much should the card fade while dragged?" and "which way is it going?". Keeping
 * them here makes the thresholds testable without a running view hierarchy.</p>
 */
public final class SessionOverviewSwipeMath {

    /** Fraction of the card width that commits a dismiss even without a fling. */
    public static final float DISMISS_FRACTION = 0.28f;

    /** Maximum opacity lost while dragging. The card is fully faded at one card width. */
    public static final float MAX_DRAG_ALPHA = 1f;

    private SessionOverviewSwipeMath() {}

    /**
     * Whether releasing at {@code dx} with horizontal velocity {@code velocityX} should close the
     * session. A fling must point the same way as the drag; a fast flick back toward the centre
     * should not dismiss the card it is returning from.
     */
    public static boolean shouldDismiss(float dx, float velocityX, int width,
                                        int minFlingVelocity) {
        if (!isFinite(dx) || !isFinite(velocityX) || width <= 0) return false;
        if (Math.abs(dx) >= width * DISMISS_FRACTION) return true;
        if (dx == 0f) return false;
        return Math.abs(velocityX) > minFlingVelocity
                && Math.signum(velocityX) == Math.signum(dx);
    }

    /** Opacity of the card while it is dragged horizontally. */
    public static float dragAlpha(float dx, int width) {
        if (!isFinite(dx) || width <= 0) return 1f;
        final float fraction = Math.min(1f, Math.abs(dx) / width);
        return 1f - fraction * MAX_DRAG_ALPHA;
    }

    /** The side the card leaves toward: -1 for left, +1 for right. */
    public static int dismissDirection(float dx) {
        return dx < 0f ? -1 : 1;
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
