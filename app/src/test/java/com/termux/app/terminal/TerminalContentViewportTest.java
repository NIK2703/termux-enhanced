package com.termux.app.terminal;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

/**
 * The overview must size its cards from the glyph-grid viewport, not from the full-bleed pager.
 * These tests pin the arithmetic that removes the per-page terminal margins and adds the IME
 * inset back.
 */
public class TerminalContentViewportTest {

    @Test
    public void pagerMinusMarginsIsTheTerminalViewport() {
        assertArrayEquals(new int[]{970, 1780},
                TerminalContentViewport.fromPager(1080, 1920, 55, 60, 55, 80, 0));
    }

    @Test
    public void zeroMarginsKeepThePagerSize() {
        assertArrayEquals(new int[]{1080, 1920},
                TerminalContentViewport.fromPager(1080, 1920, 0, 0, 0, 0, 0));
    }

    @Test
    public void imeInsetIsAddedBack() {
        assertArrayEquals(new int[]{970, 2380},
                TerminalContentViewport.fromPager(1080, 1920, 55, 60, 55, 80, 600));
    }

    @Test
    public void negativeMarginsExpandTheContentRect() {
        assertArrayEquals(new int[]{1090, 1930},
                TerminalContentViewport.fromPager(1080, 1920, -5, -10, -5, 0, 0));
    }

    @Test
    public void marginsThatLeaveNoContentReturnZero() {
        assertArrayEquals(new int[]{0, 0},
                TerminalContentViewport.fromPager(100, 100, 60, 0, 60, 0, 0));
        assertArrayEquals(new int[]{0, 0},
                TerminalContentViewport.fromPager(100, 100, 0, 60, 0, 60, 0));
    }

    @Test
    public void invalidPagerReturnsZero() {
        assertArrayEquals(new int[]{0, 0},
                TerminalContentViewport.fromPager(0, 1920, 0, 0, 0, 0, 0));
        assertArrayEquals(new int[]{0, 0},
                TerminalContentViewport.fromPager(1080, 0, 0, 0, 0, 0, 0));
    }

    @Test
    public void laidOutTerminalViewAddsTheImeInset() {
        assertArrayEquals(new int[]{970, 1780},
                TerminalContentViewport.fromView(970, 1780, 0));
        assertArrayEquals(new int[]{970, 2380},
                TerminalContentViewport.fromView(970, 1780, 600));
    }

    @Test
    public void unmeasuredTerminalViewReturnsZero() {
        assertArrayEquals(new int[]{0, 0}, TerminalContentViewport.fromView(0, 1780, 600));
        assertArrayEquals(new int[]{0, 0}, TerminalContentViewport.fromView(970, 0, 600));
    }
}
