package com.termux.app.terminal;

/**
 * Pure geometry for the tab-strip drag. Android types stay out so the slot maths can be tested
 * without a device; the controller copies live bounds into primitive arrays.
 */
final class TabStripDragMath {

    private TabStripDragMath() {
    }

    /**
     * Returns the slot whose simulated centre is closest to the dragged tab's centre. Ties keep
     * {@code currentTarget}, so a tab resting on a boundary does not flicker.
     *
     * @param fromIndex          index of the dragged tab in the original order.
     * @param currentTarget      target resolved on the previous frame; wins ties.
     * @param draggedCenter      current visual centre of the dragged tab, in container pixels.
     * @param lefts              view left of every tab in the original order.
     * @param widths             view width of every tab in the original order.
     * @param marginStarts       start margin of every tab in the original order.
     * @param marginEnds         end margin of every tab in the original order.
     * @param draggedWidth       dragged tab width including its start and end margins.
     * @param draggedMarginStart start margin of the dragged tab.
     * @param draggedMarginEnd   end margin of the dragged tab.
     * @return an index in {@code [0, lefts.length - 1]}.
     */
    static int resolveTargetIndex(int fromIndex, int currentTarget, float draggedCenter,
                                  int[] lefts, int[] widths,
                                  int[] marginStarts, int[] marginEnds,
                                  float draggedWidth,
                                  int draggedMarginStart, int draggedMarginEnd) {
        final int count = lefts.length;
        if (count < 2 || fromIndex < 0 || fromIndex >= count) {
            return Math.max(0, Math.min(fromIndex, Math.max(0, count - 1)));
        }
        if (currentTarget < 0 || currentTarget >= count) currentTarget = fromIndex;

        // The strip without the dragged tab. Tabs to its right shift left by its occupied width.
        final int otherCount = count - 1;
        final int[] otherIndex = new int[otherCount];
        final float[] otherLeft = new float[otherCount];
        int o = 0;
        for (int i = 0; i < count; i++) {
            if (i == fromIndex) continue;
            otherIndex[o] = i;
            otherLeft[o] = lefts[i] - (i > fromIndex ? draggedWidth : 0f);
            o++;
        }

        int bestSlot = currentTarget;
        float bestDistance = slotCenter(bestSlot, otherIndex, otherLeft, widths,
                marginStarts, marginEnds, draggedWidth,
                draggedMarginStart, draggedMarginEnd) - draggedCenter;
        bestDistance = Math.abs(bestDistance);
        for (int slot = 0; slot < count; slot++) {
            if (slot == currentTarget) continue;
            final float center = slotCenter(slot, otherIndex, otherLeft, widths,
                    marginStarts, marginEnds, draggedWidth,
                    draggedMarginStart, draggedMarginEnd);
            final float distance = Math.abs(center - draggedCenter);
            if (distance < bestDistance - 0.01f) {
                bestDistance = distance;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    /** Centre the dragged tab would have if inserted into {@code slot}. */
    private static float slotCenter(int slot, int[] otherIndex, float[] otherLeft,
                                    int[] widths, int[] marginStarts, int[] marginEnds,
                                    float draggedWidth, int draggedMarginStart,
                                    int draggedMarginEnd) {
        final int last = otherIndex.length - 1;
        final float insertionLeft;
        if (slot == 0) {
            final int first = 0;
            insertionLeft = otherLeft[first] - marginStarts[otherIndex[first]];
        } else if (slot == otherIndex.length) {
            final int index = otherIndex[last];
            insertionLeft = otherLeft[last] + widths[index] + marginEnds[index];
        } else {
            final int index = otherIndex[slot];
            insertionLeft = otherLeft[slot] - marginStarts[index];
        }
        return insertionLeft + draggedWidth / 2f
                + (draggedMarginStart - draggedMarginEnd) / 2f;
    }

    /**
     * Horizontal translation that opens a gap for the dragged tab. Moving it right shifts every
     * tab it passes one dragged-width left; moving it left shifts them right.
     */
    static float neighborShift(int index, int fromIndex, int toIndex, float draggedWidth) {
        if (index == fromIndex || fromIndex == toIndex) return 0f;
        if (fromIndex < toIndex && index > fromIndex && index <= toIndex) {
            return -draggedWidth;
        }
        if (fromIndex > toIndex && index >= toIndex && index < fromIndex) {
            return draggedWidth;
        }
        return 0f;
    }

    /**
     * Auto-scroll step for a finger at {@code pointerX} inside the strip viewport.
     *
     * @param pointerX      finger X relative to the strip viewport.
     * @param viewportWidth visible width of the strip.
     * @param edgeZonePx    requested edge zone; clamped to a quarter of the viewport.
     * @param maxStepPx     step at the very edge (one animation frame).
     * @param rtl           whether the strip is laid out right-to-left.
     * @return a signed scroll step in pixels for {@code HorizontalScrollView.scrollBy()}.
     */
    static int autoScrollStep(int pointerX, int viewportWidth, int edgeZonePx,
                              int maxStepPx, boolean rtl) {
        if (viewportWidth <= 0 || edgeZonePx <= 0 || maxStepPx <= 0) return 0;
        final int zone = Math.min(edgeZonePx, viewportWidth / 4);
        if (zone <= 0) return 0;

        float strength = 0f;
        if (pointerX < zone) {
            strength = (zone - pointerX) / (float) zone;
        } else if (pointerX > viewportWidth - zone) {
            strength = (pointerX - (viewportWidth - zone)) / (float) zone;
        }
        if (strength <= 0f) return 0;
        if (strength > 1f) strength = 1f;

        int step = Math.max(1, Math.round(maxStepPx * strength));
        if (pointerX < zone) step = -step;
        return rtl ? -step : step;
    }
}
