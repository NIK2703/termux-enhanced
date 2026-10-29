package com.termux.terminal;

import static org.junit.Assert.assertArrayEquals;
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

    @Test
    public void testRenderPayloadCarriesUtf16TextAfterEnd() {
        mTerminal = new TerminalEmulator(mOutput, 10, 5, INITIAL_CELL_WIDTH_PIXELS,
            INITIAL_CELL_HEIGHT_PIXELS, 100, null);
        enterString("abc");

        int[] payload = mTerminal.getRenderCommands(0, -1, -1, 0, 0, 255,
            8f, 16, 12, 10, 5);
        int cursor = 0;
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
                default:
                    fail("unknown render opcode " + op);
            }
        }
        assertTrue("payload must contain the text length after OP_END", cursor < payload.length);
        int textLength = payload[cursor++];
        assertTrue(textLength >= 3);
        assertEquals(payload.length, cursor + textLength);

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < textLength; i++) text.append((char) payload[cursor + i]);
        assertTrue("render text must contain the printed line: " + text, text.indexOf("abc") >= 0);

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
        assertArrayEquals(first, Arrays.copyOf(buffer, countAgain));

        int required = mTerminal.fillRenderCommands(new int[1], 0, -1, -1, 0, 0, 255,
            8f, 16, 12, 10, 5);
        assertTrue("a short buffer must report the required length", required < 0);
        assertEquals(count, -required);

        int[] exact = new int[count];
        assertEquals(count, mTerminal.fillRenderCommands(exact, 0, -1, -1, 0, 0, 255,
            8f, 16, 12, 10, 5));
        assertArrayEquals(first, exact);
    }
}
