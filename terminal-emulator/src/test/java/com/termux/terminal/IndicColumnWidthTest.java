package com.termux.terminal;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;

/**
 * Cell accounting for Devanagari and for emoji modifiers, the two cases that made a full-screen
 * TUI's line overflow and auto-wrap break <em>inside a word</em>.
 *
 * <p>These are emulator-level assertions, so they hold independently of any font: what they pin is
 * how many <em>cells</em> a code point claims, which is what decides where the wrap lands.</p>
 */
public class IndicColumnWidthTest extends TestCase {

	/** "पिछली" — three consonants, two of them carrying a vowel sign. */
	private static final String PICHHLI = "पिछली";
	/** "सूचना" — the word every one of the translated *_deduplication_summary strings ends up in. */
	private static final String SUCCHNA = "सूचना";
	/** A thumbs-up with a skin-tone modifier, which claims no cell of its own. */
	private static final String THUMBS_UP_TONE = "👍🏽";

	private static int displayWidth(String text) {
		int width = 0;
		for (int i = 0; i < text.length(); ) {
			final int codePoint = text.codePointAt(i);
			width += WcWidth.width(codePoint);
			i += Character.charCount(codePoint);
		}
		return width;
	}

	private static void feed(TerminalEmulator em, String s) {
		final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		em.append(bytes, bytes.length);
	}

	public void testDevanagariWordFitsOnOneRow() {
		assertEquals(3, displayWidth(PICHHLI));
		assertEquals(3, displayWidth(SUCCHNA));

		// With the vowel signs counted as cells the word needed 5, so the tail was wrapped into the
		// next row's column 0.
		final TerminalEmulator em = new TerminalEmulator(new TerminalTestCase.MockTerminalOutput(), 8, 4, 13, 15, 8, null);
		feed(em, "abcde" + SUCCHNA);
		assertEquals("abcde" + SUCCHNA, rowText(em, 0, 8));
		assertEquals("the word must not spill onto the next row", blanks(8), rowText(em, 1, 8));
	}

	/**
	 * A line that ends exactly full wraps at the *next* character, so the next word starts whole at
	 * column 0. Counted as cells, the word needed five columns instead of three and tore across the
	 * wrap.
	 */
	public void testDevanagariWordWrapsAsAWhole() {
		final TerminalEmulator em = new TerminalEmulator(new TerminalTestCase.MockTerminalOutput(), 7, 4, 13, 15, 8, null);
		// 4 + 3 = 7, so the row is exactly full after the word and "xy" starts the next one.
		feed(em, "abcd" + SUCCHNA + "xy");
		assertEquals("abcd" + SUCCHNA, rowText(em, 0, 7));
		assertEquals("xy" + blanks(5), rowText(em, 1, 7));
	}

	/**
	 * A mark is stored in the cell of the consonant it belongs to, so the run handed to the shaper
	 * starts with a base and no dotted circle is substituted for it.
	 */
	public void testVowelSignSharesItsConsonantCell() {
		final TerminalEmulator em = new TerminalEmulator(new TerminalTestCase.MockTerminalOutput(), 8, 4, 13, 15, 8, null);
		feed(em, PICHHLI);
		final TerminalRow line = em.getScreen().getLineOrBlank(0);
		// Five java chars in three cells: the two vowel signs live in their consonant's cell rather
		// than occupying one each.
		assertEquals(3, displayWidth(PICHHLI));
		assertTrue("the row must hold the whole word's characters",
			line.getSpaceUsed() >= PICHHLI.length());
		// Column 0 holds exactly प + ि — two chars, one cell.
		assertEquals(PICHHLI.charAt(0), line.mText[0]);
		assertEquals(PICHHLI.charAt(1), line.mText[1]);
		assertEquals("प and ि share one cell", 2, line.findStartOfColumn(1) - line.findStartOfColumn(0));
		// All five chars span three cells: छ alone in the second, ल + ी in the third.
		assertEquals(5, line.findStartOfColumn(3) - line.findStartOfColumn(0));
	}

	/** After "ab" the cursor is at column 4, not 6: the modifier claims no cell of its own. */
	public void testEmojiWithSkinToneKeepsTwoCells() {
		assertEquals(2, displayWidth(THUMBS_UP_TONE));

		final TerminalEmulator em = new TerminalEmulator(new TerminalTestCase.MockTerminalOutput(), 6, 4, 13, 15, 8, null);
		feed(em, "ab" + THUMBS_UP_TONE);
		assertEquals("the emoji occupies columns 2 and 3 and nothing follows it on the row",
			4, em.getCursorCol());
		assertEquals("ab" + THUMBS_UP_TONE, rowText(em, 0, 6).trim());
	}

	private static String blanks(int count) {
		final StringBuilder sb = new StringBuilder(count);
		for (int i = 0; i < count; i++) sb.append(' ');
		return sb.toString();
	}

	/** Render the row as one string of its display columns, blanks included. */
	private static String rowText(TerminalEmulator em, int row, int columns) {
		final TerminalRow line = em.getScreen().getLineOrBlank(row);
		final char[] text = line.mText;
		final int used = line.getSpaceUsed();
		final StringBuilder out = new StringBuilder();
		int column = 0;
		int charIndex = 0;
		final StringBuilder cell = new StringBuilder();
		// A cell is emitted once its width is fully covered, and a mark belongs to the cell in
		// front of it. The loop stops on the column count, so the marks trailing the last cell
		// have to be drained afterwards or the word loses its final sign.
		while (charIndex < used && (column < columns || WcWidth.width(text, charIndex) == 0)) {
			final char c = text[charIndex];
			final int codePoint = Character.isHighSurrogate(c)
				? Character.toCodePoint(c, text[charIndex + 1]) : c;
			final int charCount = Character.charCount(codePoint);
			final int width = WcWidth.width(codePoint);
			if (width == 0) {
				cell.appendCodePoint(codePoint);
			} else {
				out.append(cell);
				cell.setLength(0);
				cell.appendCodePoint(codePoint);
				for (int i = 1; i < width; i++) {
					out.append(cell);
					cell.setLength(0);
				}
				column += width;
			}
			charIndex += charCount;
		}
		out.append(cell);
		while (out.length() < columns) out.append(' ');
		return out.toString();
	}
}
