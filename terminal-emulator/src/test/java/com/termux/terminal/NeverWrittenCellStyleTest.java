package com.termux.terminal;

/**
 * Cells the program never wrote must keep the default style, whatever the program's current SGR
 * happened to be when the window was resized.
 *
 * <p>The visible symptom this pins: a full-screen program on the alternate screen buffer (vim and
 * friends) leaves a reverse-video status line as the last thing it drew, so at the instant the app
 * resizes — the extra keys panel losing or gaining a row is enough — the "current style" is the
 * status line style. That style used to be adopted as the style of every never-written cell, and
 * because {@code TerminalRenderer} resolves a reverse-video cell by swapping fore and back, the
 * background of the whole empty area became the scheme's (dark) foreground: solid black blocks
 * painted over the terminal background.</p>
 */
public class NeverWrittenCellStyleTest extends TerminalTestCase {

    private static final int COLS = 20;
    private static final int ROWS = 6;
    /** vim's end-of-file filler rows: a '~' at column 0 and nothing the program ever wrote after it. */
    private static final int FILLER_FIRST_ROW = 2;

    /** vim-like screen: text rows, end-of-file filler, and a status line drawn last. */
    private TerminalTestCase enterVimLikeScreen(final int rows, final String statusLineSgr) {
        withTerminalSized(COLS, rows);
        final StringBuilder sb = new StringBuilder("\033[?1049h"); // alternate screen buffer
        for (int r = 0; r < rows - 1; r++) {
            sb.append('\033').append('[').append(r + 1).append(";1H");
            if (r < FILLER_FIRST_ROW) sb.append("some file text");
            else sb.append('~'); // vim's end-of-file filler
        }
        sb.append('\033').append('[').append(rows).append(";1H").append(statusLineSgr).append("termcolors.sh");
        return enterString(sb.toString());
    }

    /**
     * Assert that rows {@code [firstRow, lastRow)} are default-styled throughout: the program wrote
     * no character and issued no erase in them, so their cells carry no attributes of their own.
     * The status line row is excluded — the program did write that one.
     */
    private void assertRowsAreDefault(final int lastRow, final int cols, final int firstRow) {
        for (int r = firstRow; r < lastRow; r++) {
            final TerminalRow line = mTerminal.getScreen().getLineOrBlank(r);
            for (int c = 0; c < cols; c++) {
                final long style = line.getStyle(c);
                final int fg = TextStyle.decodeForeColor(style);
                final int bg = TextStyle.decodeBackColor(style);
                final int effect = TextStyle.decodeEffect(style);
                if (bg != TextStyle.COLOR_INDEX_BACKGROUND || fg != TextStyle.COLOR_INDEX_FOREGROUND || effect != 0) {
                    fail("row=" + r + " column=" + c + " never-written cell is not default: fg=" + fg
                        + " bg=" + bg + " effect=" + effect);
                }
            }
        }
    }

    /** The reported case: a reverse-video status line, then the alt screen grows by four rows. */
    public void testAlternateScreenGrowWithReverseVideoStatusLine() {
        enterVimLikeScreen(ROWS, "\033[7m");
        assertTrue("expected the alternate screen buffer", mTerminal.isAlternateBufferActive());

        resize(COLS, ROWS + 4);

        // The four new rows are new area: nothing was ever written or erased there.
        assertRowsAreDefault(ROWS + 4, COLS, ROWS);
        // The re-created rows must not have picked up a reverse-video tail either: the blank cells
        // after the '~' of a filler row are as never-written as the whole of the new rows.
        assertRowsAreDefault(ROWS - 1, COLS, FILLER_FIRST_ROW);
        // The status line keeps its own attributes. Nothing has redrawn yet, so it is still the
        // last row of the old screen: growing appends below it.
        final TerminalRow status = mTerminal.getScreen().getLineOrBlank(ROWS - 1);
        assertEquals("status line lost its text", 't', status.mText[0]);
        assertEquals("status line lost reverse video",
            TextStyle.CHARACTER_ATTRIBUTE_INVERSE, TextStyle.decodeEffect(status.getStyle(0)));
    }

    /** Same, with an explicit background colour (`:hi StatusLine ctermbg=Black`) rather than SGR 7. */
    public void testAlternateScreenGrowWithExplicitBackground() {
        enterVimLikeScreen(ROWS, "\033[40m");
        resize(COLS, ROWS + 4);
        assertRowsAreDefault(ROWS + 4, COLS, ROWS);
        assertRowsAreDefault(ROWS - 1, COLS, FILLER_FIRST_ROW);
    }

    /** The same leak used to happen on any column change: the re-flow re-creates every row. */
    public void testColumnChangeWithReverseVideoStatusLine() {
        enterVimLikeScreen(ROWS, "\033[7m");
        resize(COLS + 1, ROWS);
        assertRowsAreDefault(ROWS - 1, COLS + 1, FILLER_FIRST_ROW);
    }

    /** And on a shrink. */
    public void testShrinkWithReverseVideoStatusLine() {
        enterVimLikeScreen(ROWS, "\033[7m");
        resize(COLS, ROWS - 2);
        assertRowsAreDefault(ROWS - 2, COLS, FILLER_FIRST_ROW);
    }

    /**
     * The other half of the contract, which must keep working: a cell the program <em>erased</em>
     * keeps the attributes that were active at the erase. This is what
     * {@code ResizeTest.testVerticalResize} pins for the rows a grow reveals, and it is the reason
     * {@code TerminalBuffer.resize} still hands {@code currentStyle} to the clear/scroll calls.
     */
    public void testErasedCellsKeepTheEraseAttributes() {
        withTerminalSized(COLS, ROWS);
        enterString("\033[41m\033[2J"); // red background, then erase the display
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++)
                assertEquals("row=" + r + " column=" + c, 1, TextStyle.decodeBackColor(getStyleAt(r, c)));

        // Growing the main buffer must not disturb it: the revealed rows are erased area and get
        // the current background, the existing rows keep theirs.
        resize(COLS, ROWS + 4);
        for (int r = 0; r < ROWS + 4; r++)
            for (int c = 0; c < COLS; c++)
                assertEquals("row=" + r + " column=" + c, 1, TextStyle.decodeBackColor(getStyleAt(r, c)));
    }

    /**
     * Documents a pre-existing limitation so that it is a decision and not an accident: a row that
     * is blank but carries a non-default background loses that background when the re-flow skips it
     * (the re-flow only copies rows with text, and re-inserts the skipped ones with
     * {@code currentStyle}). Before this rule existed the common case was masked, because the
     * program's last SGR happened to match the erase colour; upstream Termux behaves the same.
     * Fixing it means carrying a skipped row's own style through the re-flow instead of
     * {@code currentStyle}, which is a change to the re-flow itself.
     */
    public void testBlankErasedRowFallsBackToTheDefaultOnColumnChange() {
        withTerminalSized(COLS, ROWS);
        enterString("\033[41m\033[2J");   // red background, erase everything
        enterString("\033[44mHELLO");     // row 0 now has text on a blue background
        resize(COLS + 1, ROWS);
        // Row 0 has text, so the re-flow copies it with the attributes it was written with.
        assertEquals("written text must keep its attributes", 4, TextStyle.decodeBackColor(getStyleAt(0, 0)));
        // The blank rows carry no text; they come back with the default background, not with
        // either the erase colour or the last SGR.
        assertEquals("row=1", TextStyle.COLOR_INDEX_BACKGROUND, TextStyle.decodeBackColor(getStyleAt(1, 0)));
    }
}
