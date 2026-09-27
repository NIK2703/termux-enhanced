package com.termux.terminal;

/**
 * DECSET 1004 focus reporting.
 *
 * <p>The emulator only ever emits {@code ESC[I} / {@code ESC[O} when a program has enabled mode
 * 1004, and never repeats an unchanged state — the app can therefore call
 * {@link TerminalEmulator#setTerminalFocused} from any lifecycle callback without guarding it.
 */
public class FocusReportingTest extends TerminalTestCase {

    private static final String FOCUS_IN = "\033[I";
    private static final String FOCUS_OUT = "\033[O";

    public void testNothingIsReportedBeforeTheModeIsEnabled() {
        withTerminalSized(10, 3);
        mTerminal.setTerminalFocused(true);
        mTerminal.setTerminalFocused(false);
        assertEquals("", mOutput.getOutputAndClear());
    }

    public void testEnablingReportsTheCurrentStateImmediately() {
        // A program that just turned the mode on has no other way to learn the state, and would
        // otherwise sit blind until the next window change.
        withTerminalSized(10, 3);
        enterString("\033[?1004h");
        assertEquals("a blurred window must be reported as such on enable", FOCUS_OUT, mOutput.getOutputAndClear());

        mTerminal.setTerminalFocused(true);
        mOutput.getOutputAndClear();
        enterString("\033[?1004l");
        mTerminal.setTerminalFocused(true);
        enterString("\033[?1004h");
        assertEquals("a focused window must be reported as such on enable", FOCUS_IN, mOutput.getOutputAndClear());
    }

    public void testFocusChangesAreReportedOnceEnabled() {
        withTerminalSized(10, 3);
        enterString("\033[?1004h");
        mOutput.getOutputAndClear();

        mTerminal.setTerminalFocused(true);
        assertEquals(FOCUS_IN, mOutput.getOutputAndClear());

        mTerminal.setTerminalFocused(false);
        assertEquals(FOCUS_OUT, mOutput.getOutputAndClear());
    }

    public void testRepeatedIdenticalStateIsNotRepeated() {
        withTerminalSized(10, 3);
        enterString("\033[?1004h");
        mTerminal.setTerminalFocused(true);
        mOutput.getOutputAndClear();

        mTerminal.setTerminalFocused(true);
        mTerminal.setTerminalFocused(true);
        assertEquals("", mOutput.getOutputAndClear());
    }

    public void testNothingIsReportedAfterTheModeIsDisabled() {
        withTerminalSized(10, 3);
        enterString("\033[?1004h");
        mTerminal.setTerminalFocused(true);
        mOutput.getOutputAndClear();

        enterString("\033[?1004l");
        mTerminal.setTerminalFocused(false);
        mTerminal.setTerminalFocused(true);
        assertEquals("", mOutput.getOutputAndClear());
    }

    public void testDecrqmReportsTheRealModeState() {
        // The reply must be honest: a client that trusts it will wait for events that never come.
        withTerminalSized(10, 3);
        enterString("\033[?1004$p");
        assertEquals("2 = reset", "\033[?1004;2$y", mOutput.getOutputAndClear());

        enterString("\033[?1004h");
        mOutput.getOutputAndClear();
        enterString("\033[?1004$p");
        assertEquals("1 = set", "\033[?1004;1$y", mOutput.getOutputAndClear());
    }
}
