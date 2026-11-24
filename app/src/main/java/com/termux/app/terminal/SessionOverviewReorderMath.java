package com.termux.app.terminal;

/**
 * Pure geometry and permutation maths for dragging cards in the overview grid.
 *
 * <p>Only the lifted card follows the finger. This class owns the parts that do not need Android
 * types: finding the slot under the finger, the vertical auto-scroll step, and the single
 * remove-and-insert move applied to the session model on drop.</p>
 */
final class SessionOverviewReorderMath {

    /** Edge band and peak speed shared by both overview auto-scrolls, in dp. */
    static final int AUTO_SCROLL_BAND_DP = 64;
    static final int AUTO_SCROLL_MAX_STEP_DP = 48;

    private SessionOverviewReorderMath() {
    }

    /**
     * Returns the slot whose centre is closest to the finger. Ties keep {@code currentIndex}, so a
     * card resting on a boundary does not flicker between two slots.
     *
     * @param x            finger X in grid coordinates.
     * @param y            finger Y in grid coordinates.
     * @param centreXs     centre X of every slot, indexed by slot.
     * @param centreYs     centre Y of every slot, indexed by slot.
     * @param currentIndex slot resolved on the previous frame; wins ties.
     * @return a slot in {@code [0, count - 1]}, or -1 when there are no slots.
     */
    static int resolveTargetIndex(float x, float y, float[] centreXs, float[] centreYs,
                                  int currentIndex) {
        final int count = Math.min(centreXs.length, centreYs.length);
        if (count <= 0) return -1;
        if (currentIndex < 0 || currentIndex >= count) currentIndex = 0;

        int best = currentIndex;
        float bestDistance = distanceSquared(x, y, centreXs[best], centreYs[best]);
        for (int i = 0; i < count; i++) {
            if (i == best) continue;
            final float distance = distanceSquared(x, y, centreXs[i], centreYs[i]);
            if (distance < bestDistance - 0.01f) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private static float distanceSquared(float x, float y, float otherX, float otherY) {
        final float dx = x - otherX;
        final float dy = y - otherY;
        return dx * dx + dy * dy;
    }

    /**
     * Translation for a card that follows the finger from the point where the long press lifted
     * it. The card keeps its original offset instead of jumping its centre under the finger.
     *
     * @param startTranslation card translation when the drag started.
     * @param startPointer     pointer coordinate at the moment of the lift.
     * @param pointer          current pointer coordinate, in the same space.
     */
    static float dragTranslation(float startTranslation, float startPointer, float pointer) {
        return startTranslation + pointer - startPointer;
    }

    /**
     * Auto-scroll step for a finger at {@code pointerY} inside the overview viewport.
     *
     * @param pointerY    finger Y relative to the scroll viewport.
     * @param viewportH   visible height of the scroll viewport.
     * @param bandPx      edge-sensitive zone at each end of the viewport.
     * @param maxStepPx   step at the very edge for one 16 ms reference frame.
     * @param frameRatio  real frame time divided by 16 ms, capped by the caller.
     * @return a signed scroll step for {@code ScrollView.scrollBy()}: negative near the top,
     *         positive near the bottom, zero in the middle.
     */
    static int autoScrollStep(float pointerY, int viewportH, int bandPx, int maxStepPx,
                              float frameRatio) {
        if (viewportH <= 0 || bandPx <= 0 || maxStepPx <= 0) return 0;

        final float strength;
        final boolean top;
        if (pointerY < bandPx) {
            strength = (bandPx - pointerY) / (float) bandPx;
            top = true;
        } else if (pointerY > viewportH - bandPx) {
            strength = (pointerY - (viewportH - bandPx)) / (float) bandPx;
            top = false;
        } else {
            return 0;
        }
        if (!(strength > 0f)) return 0;
        return Math.round((top ? -1f : 1f) * maxStepPx * Math.min(1f, strength) * frameRatio);
    }

    /**
     * Child order after a drop: remove {@code fromIndex} and insert it at {@code toIndex}.
     *
     * <p>This is the same remove-and-insert operation as {@link
     * com.termux.app.terminal.SessionPagerManager#moveSession(int, int)}, which is why the host can
     * apply the whole visual reorder to the session model with one move. Every other child keeps
     * its relative order and shifts by one slot where needed.</p>
     *
     * @return {@code order[newIndex] = oldChildIndex}, or null when an index is out of range.
     */
    static int[] orderAfterMove(int count, int fromIndex, int toIndex) {
        if (count <= 0 || fromIndex < 0 || fromIndex >= count
                || toIndex < 0 || toIndex >= count) {
            return null;
        }

        final int[] order = new int[count];
        int out = 0;
        for (int child = 0; child < count; child++) {
            if (child == fromIndex) continue;
            if (out == toIndex) order[out++] = fromIndex;
            order[out++] = child;
        }
        if (out == toIndex) order[out++] = fromIndex;
        return order;
    }
}
