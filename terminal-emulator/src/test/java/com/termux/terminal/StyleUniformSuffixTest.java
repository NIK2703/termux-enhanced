package com.termux.terminal;

import java.util.Random;

/**
 * G3: {@code TerminalRenderer} merges the whole blank tail of a row into a single run using
 * {@link TerminalRow#getStyleUniformFromColumn()}. That is only correct while the reported column
 * is <em>not an underestimate</em>: if the suffix the row claims is uniform really contains a style
 * change, the merged run paints one colour over cells that should have had another, and the bug is
 * invisible in the common case (a row whose styles are all equal) but corrupts TUI status bars.
 *
 * <p>The bound is maintained incrementally by every writer, including the bulk ones that bypass
 * {@code setChar()}, so these tests pin the invariant down directly instead of trying to observe it
 * through rendering.</p>
 */
public class StyleUniformSuffixTest extends TerminalTestCase {

	private static final int COLUMNS = 16;
	/** U+FF37 FULLWIDTH LATIN CAPITAL LETTER W — display width 2, so it makes a row "complex". */
	private static final int WIDE_CHAR = 0xFF37;

	/** The one property that matters: from the reported column on, no two neighbours differ. */
	private static void assertSuffixIsReallyUniform(TerminalRow row, String what) {
		int from = row.getStyleUniformFromColumn();
		assertTrue(what + ": suffix start out of range: " + from, from >= 0 && from <= COLUMNS);
		for (int column = from + 1; column < COLUMNS; column++) {
			assertEquals(what + ": row claims a uniform style from column " + from + ", but columns "
				+ (column - 1) + " and " + column + " differ", row.getStyle(column - 1), row.getStyle(column));
		}
	}

	public void testClearedRowIsUniformFromZero() {
		TerminalRow row = new TerminalRow(COLUMNS, 0L);
		assertEquals(0, row.getStyleUniformFromColumn());
		assertSuffixIsReallyUniform(row, "cleared");
		row.clear(42L);
		assertEquals(0, row.getStyleUniformFromColumn());
		assertSuffixIsReallyUniform(row, "re-cleared");
	}

	public void testWriteRaisesTheBoundToTheNextColumn() {
		TerminalRow row = new TerminalRow(COLUMNS, 0L);
		row.setChar(5, 'x', 1L);
		assertEquals(6, row.getStyleUniformFromColumn());
		assertSuffixIsReallyUniform(row, "single write");

		row.setChar(9, 'y', 2L);
		assertEquals(10, row.getStyleUniformFromColumn());
		assertSuffixIsReallyUniform(row, "second write to the right");

		// A write left of the current bound cannot make the suffix less uniform, so it must not
		// move the bound down — that is the whole reason the update is "only ever raise it".
		row.setChar(3, 'z', 3L);
		assertEquals(10, row.getStyleUniformFromColumn());
		assertSuffixIsReallyUniform(row, "write left of the bound");
	}

	public void testWritingTheSameStyleDoesNotShrinkTheTail() {
		TerminalRow row = new TerminalRow(COLUMNS, 7L);
		// Every cell already carries style 7; writing style 7 again leaves the row uniform, but a
		// conservative bound is still allowed — it just means the renderer merges less. It must
		// never claim uniformity it does not have, which is what the assertion below checks.
		row.setChar(0, 'a', 7L);
		assertSuffixIsReallyUniform(row, "same-style write");
	}

	public void testBulkPlainCopyRaisesTheBound() {
		TerminalRow source = new TerminalRow(COLUMNS, 5L);
		TerminalRow destination = new TerminalRow(COLUMNS, 0L);
		destination.copyPlainInterval(source, 0, 2, 5);
		assertEquals(7, destination.getStyleUniformFromColumn());
		assertSuffixIsReallyUniform(destination, "plain bulk copy");
	}

	public void testBulkCopyOfComplexRowsKeepsTheBoundValid() {
		TerminalRow source = new TerminalRow(COLUMNS, 0L);
		// A wide char makes the row complex, so copyPlainInterval() bails out and copyInterval()
		// falls back to one setChar() per cell. The bound still has to hold.
		source.setChar(0, WIDE_CHAR, 1L);
		TerminalRow destination = new TerminalRow(COLUMNS, 0L);
		destination.copyInterval(source, 0, 4, 1);
		assertSuffixIsReallyUniform(destination, "complex bulk copy");
	}

	public void testDeccaraKeepsTheBoundValid() {
		// DECCARA writes mStyle directly through setOrClearEffect(), bypassing setChar(). The cells
		// it does not touch keep their style, so a uniform suffix still starts past the region.
		TerminalBuffer buffer = new TerminalBuffer(COLUMNS, 8, 4);
		buffer.setOrClearEffect(TextStyle.CHARACTER_ATTRIBUTE_BOLD, true, false, false, 0, COLUMNS, 0, 3, 4, 8);
		for (int row = 0; row < 4; row++) {
			TerminalRow line = buffer.getLineOrBlank(row);
			assertTrue("DECCARA: suffix start out of range", line.getStyleUniformFromColumn() <= COLUMNS);
			assertSuffixIsReallyUniform(line, "DECCARA row " + row);
		}
	}

	public void testFuzzNeverUnderestimatesTheSuffix() {
		Random random = new Random(20260910L);
		for (int iteration = 0; iteration < 4000; iteration++) {
			TerminalRow row = new TerminalRow(COLUMNS, 0L);
			for (int operation = 0; operation < 12; operation++) {
				switch (random.nextInt(6)) {
					case 0:
						row.clear((long) random.nextInt(4));
						break;
					case 1:
						row.setChar(random.nextInt(COLUMNS), 'a', random.nextInt(3));
						break;
					case 2: {
						// A wide char turns the row complex mid-flight.
						row.setChar(random.nextInt(COLUMNS - 1), WIDE_CHAR, random.nextInt(3));
						break;
					}
					case 3: {
						int from = random.nextInt(COLUMNS);
						int to = from + random.nextInt(COLUMNS - from);
						row.copyInterval(row, from, to, random.nextInt(COLUMNS - (to - from)));
						break;
					}
					case 4: {
						TerminalRow other = new TerminalRow(COLUMNS, 0L);
						other.setChar(random.nextInt(COLUMNS), 'b', random.nextInt(3));
						row.copyPlainInterval(other, 0, random.nextInt(COLUMNS / 2), COLUMNS / 2);
						break;
					}
					default:
						row.setChar(random.nextInt(COLUMNS), (char) random.nextInt(0x80), random.nextInt(3));
						break;
				}
				assertSuffixIsReallyUniform(row, "fuzz iteration " + iteration + " operation " + operation);
			}
		}
	}

}
