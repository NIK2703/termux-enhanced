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
		// Produces no space and only prohibits a line break at its position, so it claims no cell.
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
	 * wide, not four.
	 */
	public void testEmojiSkinToneModifiers() {
		for (int cp = 0x1F3FB; cp <= 0x1F3FF; cp++) {
			assertWidthIs(0, cp);
		}
	}

	/**
	 * A few combining marks are also in the wide table, and those must keep their width of two.
	 *
	 * <p>U+302E and U+302F are the Hangul single dot tone marks, U+16FF0 and U+16FF1 the
	 * Vietnamese alternate reading marks. They are {@code Mc} — spacing combining marks — but
	 * spacing is exactly the point: each occupies a cell of its own rather than sitting on the
	 * consonant in front of it, which is why UAX #29 gives them
	 * {@code Grapheme_Cluster_Break = SpacingMark} instead of {@code Extend}. The general
	 * "every {@code Mc} is zero width" rule is right for the obligatory-shaping scripts, whose vowel
	 * signs really do combine with their base, and wrong for these four.
	 */
	public void testSpacingCombiningMarksStayWide() {
		assertWidthIs(2, 0x302E); // HANGUL SINGLE DOT TONE MARK
		assertWidthIs(2, 0x302F); // HANGUL DOUBLE DOT TONE MARK
		assertWidthIs(2, 0x16FF0); // VIETNAMESE ALTERNATE READING MARK CA
		assertWidthIs(2, 0x16FF1); // VIETNAMESE ALTERNATE READING MARK NHAY
	}

	public void testUnlistedCombiningMarksAreZeroWidth() {
		assertWidthIs(0, 0x093E); // DEVANAGARI VOWEL SIGN AA — Mc, not in the wide table
		assertWidthIs(0, 0x093F); // DEVANAGARI VOWEL SIGN I
		assertWidthIs(0, 0x0940); // DEVANAGARI VOWEL SIGN II
		// THAI CHARACTER SARA AM is the near miss: the one spacing mark in an
		// obligatory-shaping script that really does take a cell, escaping the category rule because
		// it is Lo rather than Mc.
		assertWidthIs(1, 0x0E33); // THAI CHARACTER SARA AM
	}

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
		assertWidthIs(0, 0x0901); // SIGN CANDRABINDU
		assertWidthIs(0, 0x0902); // SIGN ANUSVARA
		assertWidthIs(0, 0x093C); // SIGN NUKTA
		assertWidthIs(0, 0x0941); // VOWEL SIGN U
		assertWidthIs(0, 0x0947); // VOWEL SIGN E
		assertWidthIs(0, 0x094D); // SIGN VIRAMA
	}

	/** AVAGRAHA is {@code Lo} even though it sits among the vowel signs, and the danda is punctuation. */
	public void testDevanagariNonMarks() {
		assertWidthIs(1, 0x093D); // SIGN AVAGRAHA
		assertWidthIs(1, 0x0964); // DANDA
		assertWidthIs(1, 0x0966); // DIGIT ZERO
		assertWidthIs(1, 0x0915); // LETTER KA
	}

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
	 * Prepended vowels with a glyph of their own: {@code Lo} letters, so the category rule cannot
	 * swallow them and a line starting with one is genuinely that much wider.
	 */
	public void testPrependedVowelsAreSpacing() {
		assertWidthIs(1, 0x0E33); // THAI CHARACTER SARA AM
		assertWidthIs(1, 0x0EB0); // LAO VOWEL SIGN E
	}

	public void testDevanagariSyllableWidths() {
		assertEquals("पिछली", 3, widthOf("पिछली"));
		assertEquals("सूचना", 3, widthOf("सूचना"));
		// स ् व ी क ा र — a virama joins two consonants into one cell, so four consonants are
		// three cells, and neither vowel sign adds one.
		assertEquals("स्वीकार", 4, widthOf("स्वीकार"));
	}

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
