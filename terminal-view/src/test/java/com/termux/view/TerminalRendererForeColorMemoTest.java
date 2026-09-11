package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression tests for the B4 paint-colour memo in {@link TerminalRenderer}.
 *
 * <p>The memo caches the colour last pushed into the shared {@code mTextPaint} so that adjacent
 * runs sharing a colour skip the (expensive, native) {@code Paint.setColor()} call. It is reset to
 * an "unknown" sentinel at the start of every frame.</p>
 *
 * <p>The bug these tests pin down: the sentinel used to be the {@code int} {@code -1}, which is
 * <em>not</em> distinguishable from the colour {@code 0xFFFFFFFF} — opaque white. A freshly reset
 * memo therefore looked like "white is already applied", the first opaque-white run of the frame
 * skipped {@code setColor()}, and the glyphs were painted with whatever colour the shared Paint
 * carried over from the previous frame. On screen that is a bright line (or text) that turns dark
 * grey/black in the topmost repainted row, and it moves with the viewport because the topmost row
 * is the first run drawn.</p>
 *
 * <p>The collision stayed invisible for a long time because blank rows used to be drawn as runs of
 * spaces, which called {@code setColor()} with the default foreground and primed the memo before
 * any white glyph run was reached. Once the blank-run draws were optimised away (blank-row fast
 * path, whitespace-run trimming) nothing primed it any more and the bug became reachable.</p>
 */
public class TerminalRendererForeColorMemoTest {

    /** The value the memo is reset to at the start of every {@code render()}. */
    private static final long UNKNOWN = -1L;

    /**
     * The exact failure: opaque white is a real colour and must never be mistaken for the sentinel.
     */
    @Test
    public void opaqueWhiteIsNotTheUnknownSentinel() {
        final int opaqueWhite = 0xFFFFFFFF;
        assertEquals("0xFFFFFFFF sign-extends to -1", -1, opaqueWhite);
        assertTrue("the first opaque-white run of a frame must set the paint colour",
            TerminalRenderer.needsForeColorSet(opaqueWhite, UNKNOWN));
        assertNotEquals("the memo key of opaque white must differ from the sentinel",
            UNKNOWN, TerminalRenderer.foreColorKey(opaqueWhite));
    }

    /** No colour whatsoever may collide with the sentinel. */
    @Test
    public void noColorCollidesWithTheUnknownSentinel() {
        for (int grey = 0; grey <= 0xFF; grey++) {
            final int rgb = (grey << 16) | (grey << 8) | grey;
            assertTrue("grey " + grey + " must be distinguishable from the sentinel",
                TerminalRenderer.needsForeColorSet(0xFF000000 | rgb, UNKNOWN));
        }
        // Fully transparent and fully opaque variants, plus the corners of each channel.
        final int[] colours = {
            0x00000000, 0xFFFFFFFF, 0xFF000000, 0x00FFFFFF,
            0xFFFF0000, 0xFF00FF00, 0xFF0000FF,
            0xFFFFFF00, 0xFF00FFFF, 0xFFFF00FF,
            0xFF7F7F7F, 0x80000000, 0x7FFFFFFF,
        };
        for (int colour : colours) {
            assertTrue("colour " + Integer.toHexString(colour) + " collides with the sentinel",
                TerminalRenderer.needsForeColorSet(colour, UNKNOWN));
        }
    }

    /** The memo must still do its job: an unchanged colour skips the setter. */
    @Test
    public void unchangedColorStillSkipsTheSetter() {
        final int colour = 0xFFFFFFFF;
        assertFalse("same colour twice in a row must not re-set the paint",
            TerminalRenderer.needsForeColorSet(colour, TerminalRenderer.foreColorKey(colour)));
        final int grey = 0xFF8A8A8A;
        assertFalse(TerminalRenderer.needsForeColorSet(grey, TerminalRenderer.foreColorKey(grey)));
    }

    /** A colour change must be detected for every pair in a small set. */
    @Test
    public void everyColorChangeIsDetected() {
        final int[] colours = {0xFFFFFFFF, 0xFF000000, 0xFF8A8A8A, 0xFF3F4027, 0x00000000};
        for (int previous : colours) {
            for (int next : colours) {
                assertEquals("previous=" + Integer.toHexString(previous)
                        + " next=" + Integer.toHexString(next),
                    previous != next,
                    TerminalRenderer.needsForeColorSet(next, TerminalRenderer.foreColorKey(previous)));
            }
        }
    }

    /** The memo key is the unsigned 32-bit value, so it must be stable and lossless. */
    @Test
    public void memoKeyIsTheUnsignedColorValue() {
        assertEquals(0L, TerminalRenderer.foreColorKey(0x00000000));
        assertEquals(0xFFFFFFFFL, TerminalRenderer.foreColorKey(0xFFFFFFFF));
        assertEquals(0xFF8A8A8AL, TerminalRenderer.foreColorKey(0xFF8A8A8A));
        // Distinct colours must produce distinct keys.
        assertNotEquals(TerminalRenderer.foreColorKey(0xFFFFFFFF),
            TerminalRenderer.foreColorKey(0xFF000000));
    }
}
