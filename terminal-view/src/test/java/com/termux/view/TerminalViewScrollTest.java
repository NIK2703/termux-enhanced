package com.termux.view;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Policy tests for the arithmetic viewport clamp used by ordinary transcript
 * scrolling. Mouse-tracking and alternate-buffer scrolling still emit one event
 * per row; the normal path applies the whole wheel/PageUp batch at once.
 */
public class TerminalViewScrollTest {

    @Test
    public void scrollUpMovesByTheWholeBatch() {
        assertEquals(-5, TerminalView.clampScrollTop(0, 100, -5));
    }

    @Test
    public void scrollUpClampsAtTheOldestTranscriptRow() {
        assertEquals(-100, TerminalView.clampScrollTop(-98, 100, -5));
    }

    @Test
    public void scrollDownClampsAtTheLiveBottom() {
        assertEquals(0, TerminalView.clampScrollTop(-2, 100, 5));
    }

    @Test
    public void emptyHistoryCannotBeScrolled() {
        assertEquals(0, TerminalView.clampScrollTop(0, 0, -5));
    }

    @Test
    public void invalidHistoryIsTreatedAsEmpty() {
        assertEquals(0, TerminalView.clampScrollTop(0, -1, -5));
    }
}
