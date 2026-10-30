package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;

/** Verifies the packed render payload used by {@code TerminalRenderer}. */
public class RenderPayloadTest extends TerminalTestCase {

    private static final int OP_END = 0;
    private static final int OP_ROW_BEGIN = 1;
    private static final int OP_ROW_END = 2;
    private static final int OP_FRAME_BEGIN = 6;
    private static final int OP_DAMAGE = 7;
    private static final int OP_FRAME_END = 8;
    private static final int OP_FILL_RECT = 9;
    private static final int OP_GLYPH = 10;
    private static final int OP_FILL_TRAPEZOID = 11;
    private static final int OP_IMAGE = 12;
    private static final int OP_SCROLL = 13;

    @Test
    public void testRenderPayloadCarriesUtf16TextAfterEnd() {
        mTerminal = new TerminalEmulator(mOutput, 10, 5, INITIAL_CELL_WIDTH_PIXELS,
            INITIAL_CELL_HEIGHT_PIXELS, 100, null);
        enterString("abc");

        int[] payload = mTerminal.getRenderCommands(0, -1, -1, -1, -1, 255,
            8f, 16, 12, 10, 5);
        int cursor = 0;
        int frameCount = 0;
        while (cursor < payload.length) {
            int op = payload[cursor++];
            if (op == OP_END) break;
            switch (op) {
                case OP_ROW_BEGIN:
                    cursor += 1;
                    break;
                case OP_ROW_END:
                    break;
                case OP_FILL_RECT:
                    cursor += 5;
                    break;
                case OP_GLYPH:
                    cursor += 7;
                    break;
                case OP_FILL_TRAPEZOID:
                    cursor += 9;
                    break;
                case OP_SCROLL:
                    cursor += 4;
                    break;
                case OP_FRAME_BEGIN: {
                    int damageRanges = payload[cursor + 7];
                    assertEquals(10, payload[cursor + 3]); // terminal columns
                    assertEquals(5, payload[cursor + 4]); // terminal rows
                    assertEquals(0, payload[cursor + 5]); // render top
                    assertEquals(5, payload[cursor + 6]); // render rows
                    cursor += 8;
                    for (int i = 0; i < damageRanges; i++) {
                        assertEquals(OP_DAMAGE, payload[cursor]);
                        assertTrue(payload[cursor + 1] <= payload[cursor + 2]);
                        cursor += 3;
                    }
                    frameCount++;
                    break;
                }
                case OP_FRAME_END:
                    break;
                default:
                    fail("unknown render opcode " + op);
            }
        }
        assertEquals("payload must carry exactly one frame header", 1, frameCount);
        assertTrue("payload must contain the text length after OP_END", cursor < payload.length);
        int textLength = payload[cursor++];
        assertTrue(textLength >= 3);
        assertEquals(payload.length, cursor + textLength);

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < textLength; i++) text.append((char) payload[cursor + i]);
        String units = text.toString();
        assertTrue("render text must contain the printed line: " + units,
            units.contains("abc") || units.contains("cba"));

        int[] legacy = mTerminal.getRenderText();
        assertEquals(textLength, legacy.length);
        for (int i = 0; i < textLength; i++) assertEquals(payload[cursor + i], legacy[i]);
    }

    @Test
    public void testRenderIntoReusesCallerBuffer() {
        mTerminal = new TerminalEmulator(mOutput, 10, 5, INITIAL_CELL_WIDTH_PIXELS,
            INITIAL_CELL_HEIGHT_PIXELS, 100, null);
        enterString("abc");

        int[] buffer = new int[4096];
        int count = mTerminal.fillRenderCommands(buffer, 0, -1, -1, -1, -1, 255,
            8f, 16, 12, 10, 5, true);
        assertTrue("render payload must be non-empty", count > 0);
        int[] first = Arrays.copyOf(buffer, count);

        int countAgain = mTerminal.fillRenderCommands(buffer, 0, -1, -1, -1, -1, 255,
            8f, 16, 12, 10, 5, true);
        assertEquals(count, countAgain);
        assertPayloadEqualsExceptFrameId(first, Arrays.copyOf(buffer, countAgain));

        int required = mTerminal.fillRenderCommands(new int[1], 0, -1, -1, -1, -1, 255,
            8f, 16, 12, 10, 5, true);
        assertTrue("a short buffer must report the required length", required < 0);
        assertEquals(count, -required);

        int[] exact = new int[count];
        assertEquals(count, mTerminal.fillRenderCommands(exact, 0, -1, -1, -1, -1, 255,
            8f, 16, 12, 10, 5, true));
        assertPayloadEqualsExceptFrameId(first, exact);
    }

    @Test
    public void testCleanFrameKeepsHeaderButOmitsRows() {
        mTerminal = new TerminalEmulator(mOutput, 10, 5, INITIAL_CELL_WIDTH_PIXELS,
            INITIAL_CELL_HEIGHT_PIXELS, 100, null);
        enterString("abc");

        int[] buffer = new int[8192];
        int count = mTerminal.fillRenderCommands(buffer, 0, -1, -1, -1, -1, 255,
            8f, 16, 12, 10, 5, true);
        assertTrue("initial full frame must be non-empty", count > 0);
        assertEquals("frame header must be first", OP_FRAME_BEGIN, buffer[0]);
        assertEquals("forceAllRows must emit every visible row", 5,
            countRowCommands(buffer, count));

        mTerminal.commitRenderRows(new int[]{0, 1, 2, 3, 4}, 5);

        count = mTerminal.fillRenderCommands(buffer, 0, -1, -1, -1, -1, 255,
            8f, 16, 12, 10, 5, false);
        assertTrue("a clean frame still carries its header", count > 0);
        assertEquals(OP_FRAME_BEGIN, buffer[0]);
        assertEquals("a clean frame must not emit row commands", 0,
            countRowCommands(buffer, count));

        count = mTerminal.fillRenderCommands(buffer, 0, -1, -1, -1, -1, 255,
            8f, 16, 12, 10, 5, true);
        assertEquals("forceAllRows must rebuild the clean band", 5,
            countRowCommands(buffer, count));
    }

    /** Count OP_ROW_BEGIN entries in a packed frame without decoding row payload. */
    private static int countRowCommands(int[] commands, int count) {
        int rows = 0;
        int cursor = 0;
        while (cursor < count) {
            final int op = commands[cursor++];
            if (op == OP_END) break;
            switch (op) {
                case OP_ROW_BEGIN:
                    rows++;
                    cursor++; // row index
                    break;
                case OP_ROW_END:
                case OP_FRAME_END:
                    break;
                case OP_FILL_RECT:
                    cursor += 5;
                    break;
                case OP_GLYPH:
                    cursor += 7;
                    break;
                case OP_FILL_TRAPEZOID:
                    cursor += 9;
                    break;
                case OP_IMAGE:
                    cursor += 8;
                    break;
                case OP_SCROLL:
                    cursor += 4;
                    break;
                case OP_FRAME_BEGIN: {
                    final int damageRanges = commands[cursor + 7];
                    cursor += 8 + damageRanges * 3;
                    break;
                }
                case OP_DAMAGE:
                    cursor += 2;
                    break;
                default:
                    fail("unknown render opcode " + op);
            }
        }
        return rows;
    }

    @Test
    public void testSixelImageCommandCarriesPixels() {
        mTerminal = new TerminalEmulator(mOutput, 10, 5, INITIAL_CELL_WIDTH_PIXELS,
            INITIAL_CELL_HEIGHT_PIXELS, 100, null);
        enterString("\u001bPq#0;2;100;0;0#0~~~~~~~~~~~~~~~~~~~~\u001b\\");

        int[] payload = mTerminal.getRenderCommands(0, -1, -1, -1, -1, 255,
            8f, 16, 12, 10, 5);
        int cursor = 0;
        boolean sawImage = false;
        while (cursor < payload.length) {
            int op = payload[cursor++];
            if (op == OP_END) break;
            switch (op) {
                case OP_ROW_BEGIN:
                    cursor += 1;
                    break;
                case OP_ROW_END:
                case OP_FRAME_END:
                    break;
                case OP_FILL_RECT:
                    cursor += 5;
                    break;
                case OP_GLYPH:
                    cursor += 7;
                    break;
                case OP_FILL_TRAPEZOID:
                    cursor += 9;
                    break;
                case OP_SCROLL:
                    cursor += 4;
                    break;
                case OP_FRAME_BEGIN: {
                    int damageRanges = payload[cursor + 7];
                    cursor += 8 + damageRanges * 3;
                    break;
                }
                case OP_DAMAGE:
                    cursor += 2;
                    break;
                case OP_IMAGE: {
                    int x = payload[cursor++];
                    int y = payload[cursor++];
                    int width = payload[cursor++];
                    int height = payload[cursor++];
                    long imageId = ((long) payload[cursor + 1] << 32)
                        | (payload[cursor] & 0xFFFFFFFFL);
                    cursor += 2;
                    int revision = payload[cursor++];
                    cursor++; // opaque
                    assertEquals(0, x);
                    assertEquals(0, y);
                    assertTrue(width > 0);
                    assertTrue(height > 0);
                    assertTrue(imageId != 0);
                    int[] pixels = mTerminal.getSixelPixels(imageId, revision, width, height);
                    assertNotNull("visible sixel must be copied through JNI", pixels);
                    assertTrue(pixels.length >= width * height);
                    assertEquals(0xFFFF0000, pixels[0]);
                    sawImage = true;
                    break;
                }
                default:
                    fail("unknown render opcode " + op);
            }
        }
        assertTrue("sixel payload must contain OP_IMAGE", sawImage);
    }

    @Test
    public void testViewportShiftReportsRetainedSurfaceMovement() {
        mTerminal = new TerminalEmulator(mOutput, 10, 5, INITIAL_CELL_WIDTH_PIXELS,
            INITIAL_CELL_HEIGHT_PIXELS, 100, null);
        for (int i = 0; i < 20; i++) enterString("line" + i + "\r\n");
        // Retire the initial output damage/scroll before exercising the
        // user-scroll blit path.
        mTerminal.getRenderCommands(0, -1, -1, -1, -1, 255,
            8f, 16, 12, 10, 5);
        mTerminal.commitRenderRows(new int[]{0, 1, 2, 3, 4}, 5);

        mTerminal.setViewport(-3);
        assertTrue(mTerminal.hasPendingViewportShift());
        assertEquals(-3, mTerminal.consumeViewportShift());
        assertFalse(mTerminal.hasPendingViewportShift());
        assertEquals("a shift is consumed exactly once", 0, mTerminal.consumeViewportShift());

        mTerminal.setViewport(-1);
        assertEquals(2, mTerminal.consumeViewportShift());

        /* A jump at least as large as the viewport cannot reuse pixels. */
        mTerminal.setViewport(-5);
        assertEquals(-4, mTerminal.consumeViewportShift());
        mTerminal.setViewport(0);
        assertEquals(0, mTerminal.consumeViewportShift());
        mTerminal.getRenderCommands(0, -1, -1, -1, -1, 255,
            8f, 16, 12, 10, 5);
        mTerminal.commitRenderRows(new int[]{0, 1, 2, 3, 4}, 5);

        mTerminal.setViewport(-2);
        assertEquals(-2, mTerminal.consumeViewportShift());
        mTerminal.reset();
        assertEquals("reset invalidates any pending surface shift", 0,
            mTerminal.consumeViewportShift());
    }

    /** The frame id is the only intentionally changing field between two draws. */
    private static void assertPayloadEqualsExceptFrameId(int[] expected, int[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            if (i == 1) continue;
            assertEquals("payload differs at index " + i, expected[i], actual[i]);
        }
    }
}
