package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;

/** Verifies the packed render payload used by {@code TerminalRenderer}. */
public class RenderPayloadTest extends TerminalTestCase {

    private static final int OP_END = 0;
    private static final int OP_ROW_BEGIN = 1;
    private static final int OP_ROW_END = 2;
    private static final int OP_RECT = 3;
    private static final int OP_TEXT = 4;
    private static final int OP_CURSOR = 5;
    private static final int OP_FRAME_BEGIN = 6;
    private static final int OP_DAMAGE = 7;
    private static final int OP_FRAME_END = 8;
    private static final int OP_FILL_RECT = 9;
    private static final int OP_GLYPH = 10;
    private static final int OP_FILL_TRAPEZOID = 11;

    @Test
    public void testRenderPayloadCarriesUtf16TextAfterEnd() {
        mTerminal = new TerminalEmulator(mOutput, 10, 5, INITIAL_CELL_WIDTH_PIXELS,
            INITIAL_CELL_HEIGHT_PIXELS, 100, null);
        enterString("abc");

        int[] payload = mTerminal.getRenderCommands(0, -1, -1, 0, 0, 255,
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
                case OP_RECT:
                case OP_CURSOR:
                    cursor += 4;
                    break;
                case OP_TEXT:
                    cursor += 7;
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
        int count = mTerminal.fillRenderCommands(buffer, 0, -1, -1, 0, 0, 255,
            8f, 16, 12, 10, 5);
        assertTrue("render payload must be non-empty", count > 0);
        int[] first = Arrays.copyOf(buffer, count);

        int countAgain = mTerminal.fillRenderCommands(buffer, 0, -1, -1, 0, 0, 255,
            8f, 16, 12, 10, 5);
        assertEquals(count, countAgain);
        assertPayloadEqualsExceptFrameId(first, Arrays.copyOf(buffer, countAgain));

        int required = mTerminal.fillRenderCommands(new int[1], 0, -1, -1, 0, 0, 255,
            8f, 16, 12, 10, 5);
        assertTrue("a short buffer must report the required length", required < 0);
        assertEquals(count, -required);

        int[] exact = new int[count];
        assertEquals(count, mTerminal.fillRenderCommands(exact, 0, -1, -1, 0, 0, 255,
            8f, 16, 12, 10, 5));
        assertPayloadEqualsExceptFrameId(first, exact);
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
