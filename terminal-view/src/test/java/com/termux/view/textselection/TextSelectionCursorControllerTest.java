package com.termux.view.textselection;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Tests for the "one line" copy action on terminal selections. */
public class TextSelectionCursorControllerTest {

    @Test
    public void emptySelectionStaysEmpty() {
        assertEquals("", TextSelectionCursorController.joinLinesIntoOneLine(null));
        assertEquals("", TextSelectionCursorController.joinLinesIntoOneLine(""));
        assertEquals("   ", TextSelectionCursorController.joinLinesIntoOneLine("   "));
    }

    @Test
    public void bareLineBreaksAreRemovedWithoutAddingSpaces() {
        assertEquals("helloworld",
                TextSelectionCursorController.joinLinesIntoOneLine("hello\nworld"));
        assertEquals("helloworld",
                TextSelectionCursorController.joinLinesIntoOneLine("hello\r\nworld"));
        assertEquals("helloworld",
                TextSelectionCursorController.joinLinesIntoOneLine("hello\n\nworld"));
    }

    @Test
    public void spacesAroundLineBreaksAreCollapsedToOne() {
        assertEquals("hello world",
                TextSelectionCursorController.joinLinesIntoOneLine("hello   \n   world"));
        assertEquals("hello world",
                TextSelectionCursorController.joinLinesIntoOneLine("hello \t\r\n \t world"));
        assertEquals("hello world",
                TextSelectionCursorController.joinLinesIntoOneLine("hello \nworld"));
        assertEquals("hello world",
                TextSelectionCursorController.joinLinesIntoOneLine("hello\n world"));
    }

    @Test
    public void spacesAwayFromLineBreaksArePreserved() {
        assertEquals("a  bc", TextSelectionCursorController.joinLinesIntoOneLine("a  b\nc"));
        assertEquals("  a b  ", TextSelectionCursorController.joinLinesIntoOneLine("  a \n b  "));
    }

    @Test
    public void leadingAndTrailingLineBreaksDoNotLeaveSpaces() {
        assertEquals("text", TextSelectionCursorController.joinLinesIntoOneLine("\n\ntext\n\n"));
    }
}
