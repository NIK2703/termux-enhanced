package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression tests for {@link ScrollbarThumbGeometry}.
 *
 * <p>The squash is driven by the visible over-drag, so a pull's end state must be a one-pixel
 * capsule just short of a circle, the two ends of the signed range must pin the same edge they
 * are pulled from, and the height must never cross the width (which would turn the capsule into
 * a horizontal pill).</p>
 */
public class ScrollbarThumbGeometryTest {

    private static final float HEIGHT_EPSILON = 0.0001f;

    @Test
    public void restHeightNeverExceedsTheTrack() {
        assertEquals(128f, ScrollbarThumbGeometry.restHeight(128f, 800f), HEIGHT_EPSILON);
        assertEquals(120f, ScrollbarThumbGeometry.restHeight(128f, 120f), HEIGHT_EPSILON);
    }

    @Test
    public void circleDiameterNeverExceedsTheHandle() {
        assertEquals(24f, ScrollbarThumbGeometry.circleDiameter(24f, 64f), HEIGHT_EPSILON);
        assertEquals(20f, ScrollbarThumbGeometry.circleDiameter(24f, 20f), HEIGHT_EPSILON);
    }

    @Test
    public void squashFractionIsZeroAtTheBoundaryAndOneAtTheCap() {
        assertEquals(0f, ScrollbarThumbGeometry.squashFraction(0, 400f), HEIGHT_EPSILON);
        assertEquals(0.25f, ScrollbarThumbGeometry.squashFraction(-100, 400f), HEIGHT_EPSILON);
        assertEquals(1f, ScrollbarThumbGeometry.squashFraction(-400, 400f), HEIGHT_EPSILON);
        assertEquals(1f, ScrollbarThumbGeometry.squashFraction(-900, 400f), HEIGHT_EPSILON);
    }

    @Test
    public void squashFractionIsSafeWithoutACap() {
        assertEquals(0f, ScrollbarThumbGeometry.squashFraction(-100, 0f), HEIGHT_EPSILON);
        assertEquals(0f, ScrollbarThumbGeometry.squashFraction(-100, -5f), HEIGHT_EPSILON);
    }

    @Test
    public void heightStopsOnePixelAboveTheCircle() {
        float rest = 168f;
        float diameter = 59f;

        assertEquals(rest, ScrollbarThumbGeometry.height(rest, diameter, 0f), HEIGHT_EPSILON);
        assertEquals(diameter + 1f, ScrollbarThumbGeometry.height(rest, diameter, 1f),
            HEIGHT_EPSILON);
        assertEquals(diameter + 1f, ScrollbarThumbGeometry.height(rest, diameter, 2f),
            HEIGHT_EPSILON);
        assertEquals(rest, ScrollbarThumbGeometry.height(rest, diameter, -1f), HEIGHT_EPSILON);
    }

    @Test
    public void fullSquashKeepsOnePixelOfStraightSide() {
        float rest = 168f;
        float diameter = 59f;
        float height = ScrollbarThumbGeometry.height(rest, diameter, 1f);

        assertTrue(height > diameter);
        assertEquals(1f, height - diameter, HEIGHT_EPSILON);
        // Radius still follows the width, so the capsule is rounded, not a taller circle.
        assertEquals(diameter / 2f,
            ScrollbarThumbGeometry.cornerRadius(diameter, height), HEIGHT_EPSILON);
    }

    @Test
    public void heightIsMonotoneInTheSquashFraction() {
        float rest = 168f;
        float diameter = 59f;
        float floor = diameter + 1f;
        float previous = Float.MAX_VALUE;
        for (int i = 0; i <= 100; i++) {
            float height = ScrollbarThumbGeometry.height(rest, diameter, i / 100f);
            assertTrue("height grew at t=" + i, height <= previous + HEIGHT_EPSILON);
            assertTrue("height crossed the one-pixel limit at t=" + i,
                height >= floor - HEIGHT_EPSILON);
            previous = height;
        }
    }

    @Test
    public void heightIsNotReducedWhenTheTrackIsShorterThanTheHandle() {
        float rest = 20f;
        float diameter = ScrollbarThumbGeometry.circleDiameter(59f, rest);
        assertEquals(rest, diameter, HEIGHT_EPSILON);
        assertEquals(rest, ScrollbarThumbGeometry.height(rest, diameter, 1f), HEIGHT_EPSILON);
    }

    @Test
    public void bottomEdgeStaysPinnedWhileTheHeightShrinks() {
        float viewHeight = 1000f;
        float rest = 168f;
        float diameter = 59f;

        for (int i = 0; i <= 100; i++) {
            float height = ScrollbarThumbGeometry.height(rest, diameter, i / 100f);
            float top = ScrollbarThumbGeometry.top(1f, viewHeight, height);
            assertEquals("bottom edge moved at t=" + i,
                viewHeight, top + height, HEIGHT_EPSILON);
        }
    }

    @Test
    public void topEdgeStaysPinnedWhileTheHeightShrinks() {
        float rest = 168f;
        float diameter = 59f;

        for (int i = 0; i <= 100; i++) {
            float height = ScrollbarThumbGeometry.height(rest, diameter, i / 100f);
            assertEquals("top edge moved at t=" + i,
                0f, ScrollbarThumbGeometry.top(0f, 1000f, height), HEIGHT_EPSILON);
        }
    }

    @Test
    public void cornerRadiusBecomesAHalfHeightOnlyBelowTheCircle() {
        assertEquals(12f, ScrollbarThumbGeometry.cornerRadius(24f, 64f), HEIGHT_EPSILON);
        assertEquals(12f, ScrollbarThumbGeometry.cornerRadius(24f, 24f), HEIGHT_EPSILON);
        assertEquals(10f, ScrollbarThumbGeometry.cornerRadius(24f, 20f), HEIGHT_EPSILON);
    }
}
