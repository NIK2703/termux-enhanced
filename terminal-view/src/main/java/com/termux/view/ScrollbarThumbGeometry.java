package com.termux.view;

/**
 * Pure geometry of the interactive terminal scrollbar thumb.
 *
 * <p>Kept out of {@link TerminalView} so the squash curve can be unit-tested without an Android
 * runtime: the view only supplies numbers and paints the result. The model has one moving part:
 * the handle shrinks from its rest height to just above a circle as the transcript is pulled past
 * either end of its scroll range, in lockstep with the elastic over-drag (see
 * {@code TerminalView#mOverdragPx}).</p>
 *
 * <p>Invariants, in the order the view uses them:</p>
 * <ul>
 *   <li>the rest height never exceeds the track (a short view shows a shorter handle);</li>
 *   <li>the circle diameter never exceeds the rest height (a short handle is not made taller);</li>
 *   <li>height is monotone from the rest height at {@code t = 0} to one pixel above the diameter
 *       at {@code t = 1}; it is never smaller than the diameter, so the rounded rect cannot become
 *       a horizontal pill;</li>
 *   <li>the thumb is pinned to the end of the track when the scroll fraction is exactly 0 or 1;
 *       at any other fraction the same formula positions it proportionally.</li>
 * </ul>
 */
final class ScrollbarThumbGeometry {

    private ScrollbarThumbGeometry() { }

    /** Rest height of the handle in px, clamped to the visible track. */
    static float restHeight(float thumbHeightPx, float viewHeightPx) {
        return Math.min(thumbHeightPx, viewHeightPx);
    }

    /**
     * Diameter of the circle the handle shrinks toward: its width, but never more than the handle
     * itself is tall. On a view shorter than the handle there is no room to shrink at all, so the
     * "circle" is simply the full (short) handle.
     */
    static float circleDiameter(float thumbWidthPx, float restHeightPx) {
        return Math.min(thumbWidthPx, restHeightPx);
    }

    /**
     * Normalized over-drag, {@code 0} at the boundary and {@code 1} at the band's cap.
     *
     * <p>The value is taken from the <em>visible</em> displacement, not from the finger, so the
     * handle reaches its smallest height exactly when the content reaches the cap of the rubber
     * band and stays there while the pull is held.</p>
     */
    static float squashFraction(int overdragPx, float capPx) {
        if (!(capPx > 0f) || overdragPx == 0) return 0f;
        final float fraction = Math.abs(overdragPx) / capPx;
        return fraction >= 1f ? 1f : fraction;
    }

    /**
     * One pixel of straight side kept at full squash. An exact circle makes the platform render
     * the elevation shadow as a coarse octagon, which shows through the translucent fill.
     */
    private static final float MIN_HEIGHT_ABOVE_DIAMETER_PX = 1f;

    /**
     * Handle height at squash fraction {@code t}: rest height at 0, one pixel above the diameter
     * at 1.
     */
    static float height(float restHeightPx, float diameterPx, float t) {
        final float clamped = t <= 0f ? 0f : (t >= 1f ? 1f : t);
        final float minHeight = Math.min(restHeightPx,
            diameterPx + MIN_HEIGHT_ABOVE_DIAMETER_PX);
        return restHeightPx - (restHeightPx - minHeight) * clamped;
    }

    /**
     * Corner radius for a round rect of the given size. For every height at or above the width
     * this is exactly {@code width / 2}, a capsule; the squash stops one pixel above
     * {@code height == width} (see {@link #height}). The clamp only matters on a view too short
     * for a circle.
     */
    static float cornerRadius(float widthPx, float heightPx) {
        return Math.min(widthPx, heightPx) / 2f;
    }

    /**
     * Top edge of the handle in view coordinates.
     *
     * <p>End-pinning falls out of the formula: at the live bottom the fraction is exactly 1, so
     * {@code top = viewHeight - height} and the bottom edge stays at the track end while the
     * height shrinks; at the top of history the fraction is 0 and the top edge stays at 0.</p>
     *
     * @param scrollFraction {@code 0} at the top of history, {@code 1} at the live bottom.
     */
    static float top(float scrollFraction, float viewHeightPx, float heightPx) {
        return scrollFraction * (viewHeightPx - heightPx);
    }
}
