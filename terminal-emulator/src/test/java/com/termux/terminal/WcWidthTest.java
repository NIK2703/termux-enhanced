package com.termux.terminal;

import junit.framework.TestCase;

public class WcWidthTest extends TestCase {

	private static void assertWidthIs(int expectedWidth, int codePoint) {
		int wcWidth = WcWidth.width(codePoint);
		assertEquals(expectedWidth, wcWidth);
	}

	public void testPrintableAscii() {
		for (int i = 0x20; i <= 0x7E; i++) {
			assertWidthIs(1, i);
		}
	}

	public void testSomeWidthOne() {
		assertWidthIs(1, 'å');
		assertWidthIs(1, 'ä');
		assertWidthIs(1, 'ö');
		assertWidthIs(1, 0x23F2);
	}

	public void testSomeWide() {
		assertWidthIs(2, 'Ａ');
		assertWidthIs(2, 'Ｂ');
		assertWidthIs(2, 'Ｃ');
		assertWidthIs(2, '中');
		assertWidthIs(2, '文');

		assertWidthIs(2, 0x679C);
		assertWidthIs(2, 0x679D);

		assertWidthIs(2, 0x2070E);
		assertWidthIs(2, 0x20731);

		assertWidthIs(1, 0x1F781);
	}

	public void testSomeNonWide() {
		assertWidthIs(1, 0x1D11E);
		assertWidthIs(1, 0x1D11F);
	}

	public void testCombining() {
		assertWidthIs(0, 0x0302);
		assertWidthIs(0, 0x0308);
		assertWidthIs(0, 0xFE0F);
	}

	public void testWordJoiner() {
		// https://en.wikipedia.org/wiki/Word_joiner
		// The word joiner (WJ) is a code point in Unicode used to separate words when using scripts
		// that do not use explicit spacing. It is encoded since Unicode version 3.2
		// (released in 2002) as U+2060 WORD JOINER (HTML &#8288;).
		// The word joiner does not produce any space, and prohibits a line break at its position.
		assertWidthIs(0, 0x2060);
	}

	public void testSofthyphen() {
		// http://osdir.com/ml/internationalization.linux/2003-05/msg00006.html:
		// "Existing implementation practice in terminals is that the SOFT HYPHEN is
		// a spacing graphical character, and the purpose of my wcwidth() was to
		// predict the advancement of the cursor position after a string is sent to
		// a terminal. Hence, I have no choice but to keep wcwidth(SOFT HYPHEN) = 1.
		// VT100-style terminals do not hyphenate."
		assertWidthIs(1, 0x00AD);
	}

	public void testHangul() {
		assertWidthIs(1, 0x11A3);
	}

	public void testEmojis() {
		assertWidthIs(2, 0x1F428); // KOALA.
		assertWidthIs(2, 0x231a);  // WATCH.
		assertWidthIs(2, 0x1F643); // UPSIDE-DOWN FACE (Unicode 8).
	}

	/**
	 * The emoji skin-tone modifiers are {@code Sk} (modifier symbols), not combining marks, so a
	 * category test cannot find them — but every terminal renders them as a zero-advance modifier
	 * on the preceding base, so they must not take a cell of their own. {@code 👍🏽} is two cells
	 * wide, not four. They used to sit in the wide table, which made each one 2.
	 */
	public void testEmojiSkinToneModifiers() {
		for (int cp = 0x1F3FB; cp <= 0x1F3FF; cp++) {
			assertWidthIs(0, cp);
		}
	}

	/**
	 * A Devanagari syllable is one cell per consonant, and the vowel signs belong to the consonant
	 * in front of them.
	 *
	 * <p>These are {@code Mc} ("spacing combining mark"), which neither the C library original nor
	 * the jquast/wcwidth tables that {@link WcWidth} transcribes enumerate — so U+093E, U+093F and
	 * U+0940 each came out as a cell of their own. Two things followed, both visible: the mark was
	 * stored in its own cell, so the run handed to {@code Canvas.drawTextRun()} began with a mark
	 * that had no base and the shaper substituted a dotted circle for it ("पिछली" rendered as
	 * "प◌छली"); and the syllable was twice as wide as it looks, so a full-screen TUI's line overflowed
	 * and auto-wrap broke it inside the word, dropping the tail into column 0 of the next row.
	 */
	public void testDevanagariVowelSigns() {
		assertWidthIs(0, 0x093B); // VOWEL SIGN ORA
		assertWidthIs(0, 0x093E); // VOWEL SIGN AA
		assertWidthIs(0, 0x093F); // VOWEL SIGN I
		assertWidthIs(0, 0x0940); // VOWEL SIGN II
		assertWidthIs(0, 0x0949); // SIGN CANDRABINDU
		assertWidthIs(0, 0x094B); // VOWEL SIGN O
		assertWidthIs(0, 0x094E); // VOWEL SIGN VOCALIC L
		assertWidthIs(0, 0x094F); // VOWEL SIGN VOCALIC LL
		assertWidthIs(0, 0x0903); // SIGN VISARGA
		// The marks that were already listed keep their width.
		assertWidthIs(0, 0x0901); // SIGN CANDRABINDU
		assertWidthIs(0, 0x0902); // SIGN ANUSVARA
		assertWidthIs(0, 0x093C); // SIGN NUKTA
		assertWidthIs(0, 0x0941); // VOWEL SIGN U
		assertWidthIs(0, 0x0947); // VOWEL SIGN E
		assertWidthIs(0, 0x094D); // SIGN VIRAMA
	}

	/**
	 * AVAGRAHA is a letter ({@code Lo}) even though it sits between the vowel signs, and the danda
	 * is punctuation: neither is a mark, and both keep their cell.
	 */
	public void testDevanagariNonMarks() {
		assertWidthIs(1, 0x093D); // SIGN AVAGRAHA
		assertWidthIs(1, 0x0964); // DANDA
		assertWidthIs(1, 0x0966); // DIGIT ZERO
		assertWidthIs(1, 0x0915); // LETTER KA
	}

	/**
	 * The other obligatory-shaping scripts have the same {@code Mc} vowel-sign series, and the
	 * category rule covers them for the same reason it covers Devanagari.
	 */
	public void testOtherIndicVowelSigns() {
		assertWidthIs(0, 0x09BE); // BENGALI VOWEL SIGN AA
		assertWidthIs(0, 0x09BF); // BENGALI VOWEL SIGN I
		assertWidthIs(0, 0x09C7); // BENGALI VOWEL SIGN E
		assertWidthIs(0, 0x0A3E); // GURMUKHI VOWEL SIGN AA
		assertWidthIs(0, 0x0A3F); // GURMUKHI VOWEL SIGN I
		assertWidthIs(0, 0x0ABE); // GUJARATI VOWEL SIGN AA
		assertWidthIs(0, 0x0B3E); // ORIYA VOWEL SIGN AA
		assertWidthIs(0, 0x0BBE); // TAMIL VOWEL SIGN AA
		assertWidthIs(0, 0x0C3E); // TELUGU VOWEL SIGN AA
		assertWidthIs(0, 0x0CBE); // KANNADA VOWEL SIGN AA
		assertWidthIs(0, 0x0D3E); // MALAYALAM VOWEL SIGN AA
		assertWidthIs(0, 0x0DCF); // SINHALA VOWEL SIGN AELA-PILLA
		assertWidthIs(0, 0x0E4D); // THAI CHARACTER NIKHAHIT
	}

	/**
	 * Thai SARA AM and Lao VOWEL SIGN E are prepended vowels with a glyph of their own: they are
	 * {@code Lo} letters, not combining marks, so the category rule must not swallow them and they
	 * keep their cell. A line that starts with one of them is genuinely that much wider.
	 */
	public void testPrependedVowelsAreSpacing() {
		assertWidthIs(1, 0x0E33); // THAI CHARACTER SARA AM
		assertWidthIs(1, 0x0EB0); // LAO VOWEL SIGN E
	}

	/** The width of a whole string, the way a line of output is measured against the grid. */
	public void testDevanagariSyllableWidths() {
		assertEquals("पिछली", 3, widthOf("पिछली"));
		assertEquals("सूचना", 3, widthOf("सूचना"));
		// स ् व ी क ा र — a virama joins two consonants into one cell, so four consonants are
		// three cells, and neither vowel sign adds one.
		assertEquals("स्वीकार", 4, widthOf("स्वीकार"));
	}

	/** An emoji with a skin-tone modifier is one base glyph, not two. */
	public void testEmojiWithSkinTone() {
		assertEquals("👍🏽", 2, widthOf("👍🏽"));
	}

	private static int widthOf(String text) {
		int width = 0;
		for (int i = 0; i < text.length(); ) {
			final int codePoint = text.codePointAt(i);
			width += WcWidth.width(codePoint);
			i += Character.charCount(codePoint);
		}
		return width;
	}

}
