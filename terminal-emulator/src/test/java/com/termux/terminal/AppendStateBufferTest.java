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

    @Test
    public void testCommitAndViewportOwnership() {
        final long handle = TerminalNative.nativeCreate(10, 5, 32, 8, 16,
            new TerminalColorScheme().mDefaultColors);
        assertTrue("native terminal must be created", handle != 0);
        try {
            TerminalNative.nativeDrainEvents(handle);
            final int[] state = new int[TerminalNative.APPEND_STATE_HEADER_SIZE + 5];

            byte[] text = "a".getBytes(StandardCharsets.UTF_8);
            int length = TerminalNative.nativeAppendInto(handle, text, text.length, state);
            assertTrue(length >= TerminalNative.APPEND_STATE_HEADER_SIZE);
            assertTrue(state[0] > 0);
            final int[] dirty = new int[state[0]];
            System.arraycopy(state, TerminalNative.APPEND_STATE_HEADER_SIZE, dirty, 0, state[0]);
            TerminalNative.nativeCommitRows(handle, dirty, dirty.length);

            /* Dirty damage must survive until the commit, then disappear. */
            text = "\u001b[2;1H".getBytes(StandardCharsets.UTF_8);
            length = TerminalNative.nativeAppendInto(handle, text, text.length, state);
            assertTrue(length >= TerminalNative.APPEND_STATE_HEADER_SIZE);
            assertEquals(0, state[0]);

            /* Produce enough history for a negative viewport. */
            StringBuilder lines = new StringBuilder();
            for (int i = 0; i < 20; i++) lines.append('\n');
            text = lines.toString().getBytes(StandardCharsets.UTF_8);
            length = TerminalNative.nativeAppendInto(handle, text, text.length, state);
            assertTrue(length >= TerminalNative.APPEND_STATE_HEADER_SIZE);
            TerminalNative.nativeCommitAllRows(handle);

            TerminalNative.nativeSetView(handle, -2);
            text = "\u001b[0m".getBytes(StandardCharsets.UTF_8);
            length = TerminalNative.nativeAppendInto(handle, text, text.length, state);
            assertTrue(length >= TerminalNative.APPEND_STATE_HEADER_SIZE);
            assertEquals(5, state[0]);
            assertTrue("viewport change must be reported as pending full damage",
                (state[7] & TerminalNative.APPEND_FLAG_FULL_DAMAGE) != 0);
            boolean found = false;
            for (int i = 0; i < state[0]; i++) {
                if (state[TerminalNative.APPEND_STATE_HEADER_SIZE + i] == -2) found = true;
            }
            assertTrue("full damage must cover the scrolled-up viewport", found);

            TerminalNative.nativeCommitAllRows(handle);
            length = TerminalNative.nativeAppendInto(handle, text, text.length, state);
            assertTrue(length >= TerminalNative.APPEND_STATE_HEADER_SIZE);
            assertEquals(0, state[0]);
            assertEquals(0, state[7] & TerminalNative.APPEND_FLAG_FULL_DAMAGE);
        } finally {
            TerminalNative.nativeDestroy(handle);
        }
    }
}
