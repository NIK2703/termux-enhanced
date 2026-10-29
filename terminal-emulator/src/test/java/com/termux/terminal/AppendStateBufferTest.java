package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** Verifies the reusable packed append-state buffer used by {@link TerminalEmulator}. */
public class AppendStateBufferTest {

    @Test
    public void testAppendIntoRejectsSmallBufferBeforeConsumingInput() {
        final long handle = TerminalNative.nativeCreate(10, 5, 32, 8, 16,
            new TerminalColorScheme().mDefaultColors);
        assertTrue("native terminal must be created", handle != 0);
        try {
            /* Discard startup notifications such as reset_title. */
            TerminalNative.nativeDrainEvents(handle);
            final byte[] text = "abc".getBytes(StandardCharsets.UTF_8);
            final int[] tooSmall = new int[TerminalNative.APPEND_STATE_HEADER_SIZE + 1];

            final int required = TerminalNative.nativeAppendInto(
                handle, text, text.length, tooSmall);
            assertEquals(-(TerminalNative.APPEND_STATE_HEADER_SIZE + 5), required);
            assertEquals("an undersized buffer must not consume input",
                0, TerminalNative.nativeCursorCol(handle));

            final int[] state = new int[-required];
            final int stateLength = TerminalNative.nativeAppendInto(
                handle, text, text.length, state);
            assertTrue(stateLength >= TerminalNative.APPEND_STATE_HEADER_SIZE);
            assertEquals(3, state[4]);
            assertTrue(state[0] >= 1);
            boolean row0Dirty = false;
            for (int i = 0; i < state[0]; i++) {
                if (state[TerminalNative.APPEND_STATE_HEADER_SIZE + i] == 0) row0Dirty = true;
            }
            assertTrue("the first screen row must be reported dirty", row0Dirty);

            final byte[] next = "d".getBytes(StandardCharsets.UTF_8);
            final int nextLength = TerminalNative.nativeAppendInto(
                handle, next, next.length, state);
            assertTrue(nextLength >= TerminalNative.APPEND_STATE_HEADER_SIZE);
            assertEquals(4, state[4]);
            assertEquals(4, TerminalNative.nativeCursorCol(handle));

            assertNull("plain text must not allocate an empty event string",
                TerminalNative.nativeDrainEvents(handle));
        } finally {
            TerminalNative.nativeDestroy(handle);
        }
    }
}
