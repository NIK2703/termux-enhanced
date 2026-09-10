package com.termux.terminal;

import java.util.Arrays;

/**
 * A row in a terminal, composed of a fixed number of cells.
 * <p>
 * The text in the row is stored in a char[] array, {@link #mText}, for quick access during rendering.
 */
public final class TerminalRow {

    private static final float SPARE_CAPACITY_FACTOR = 1.5f;

    /**
     * Max combining characters that can exist in a column, that are separate from the base character
     * itself. Any additional combining characters will be ignored and not added to the column.
     *
     * There does not seem to be limit in unicode standard for max number of combination characters
     * that can be combined but such characters are primarily under 10.
     *
     * "Section 3.6 Combination" of unicode standard contains combining characters info.
     * - https://www.unicode.org/versions/Unicode15.0.0/ch03.pdf
     * - https://en.wikipedia.org/wiki/Combining_character#Unicode_ranges
     * - https://stackoverflow.com/questions/71237212/what-is-the-maximum-number-of-unicode-combined-characters-that-may-be-needed-to
     *
     * UAX15-D3 Stream-Safe Text Format limits to max 30 combining characters.
     * > The value of 30 is chosen to be significantly beyond what is required for any linguistic or technical usage.
     * > While it would have been feasible to chose a smaller number, this value provides a very wide margin,
     * > yet is well within the buffer size limits of practical implementations.
     * - https://unicode.org/reports/tr15/#Stream_Safe_Text_Format
     * - https://stackoverflow.com/a/11983435/14686958
     *
     * We choose the value 15 because it should be enough for terminal based applications and keep
     * the memory usage low for a terminal row, won't affect performance or cause terminal to
     * lag or hang, and will keep malicious applications from causing harm. The value can be
     * increased if ever needed for legitimate applications.
     */
    private static final int MAX_COMBINING_CHARACTERS_PER_COLUMN = 15;

    /** The number of columns in this terminal row. */
    private final int mColumns;
    /** The text filling this terminal row. */
    public char[] mText;
    /** The number of java chars used in {@link #mText}. */
    private short mSpaceUsed;
    /** If this row has been line wrapped due to text output at the end of line. */
    boolean mLineWrap;
    /** The style bits of each cell in the row. See {@link TextStyle}. */
    final long[] mStyle;
    /** If this row might contain chars with width != 1, used for deactivating fast path */
    boolean mHasNonOneWidthOrSurrogateChars;
    /**
     * A1 optimization: memoized result of the last {@link #findStartOfColumn(int)} call. Terminal
     * output is almost always written left-to-right, so the next column to set is >= the cached one
     * and the scan can resume from {@code mCachedCharIndex} instead of walking {@code mText} from 0
     * every time. Without this, once a wide/surrogate/combining char lands in a row every subsequent
     * {@code setChar} does three O(columns) scans (see the slow path in {@link #setChar}), making row
     * filling O(columns²) on CJK/emoji output. The cache is invalidated whenever the row's char
     * indices shift (any arraycopy in setChar), so reads are always correct.
     */
    private int mCachedColumn = -1;
    private int mCachedCharIndex = -1;
    /**
     * E4: "every cell is a plain space under one single style", maintained incrementally.
     *
     * <p>{@code TerminalRenderer}'s blank-row fast path (A2) used to prove this by scanning all
     * {@code columns} cells of the row on every frame — on an idle screen that is 48&times;80 array
     * loads per frame to reach the same conclusion. The flag makes the test O(1): {@link #clear(long)}
     * establishes it (it fills text with spaces and style uniformly), and any write that is not a
     * space or carries a different style breaks it. A space with a foreign style is deliberately
     * treated as "not blank" — it still paints its own background, and underline/strike-through are
     * drawn across blank cells too.</p>
     */
    boolean mBlankAndUniform = true;

    /** Construct a blank row (containing only whitespace, ' ') with a specified style. */
    public TerminalRow(int columns, long style) {
        mColumns = columns;
        mText = new char[(int) (SPARE_CAPACITY_FACTOR * columns)];
        mStyle = new long[columns];
        clear(style);
    }

    /**
     * B2/F6: copy {@code length} plain cells from column {@code sourceX} of {@code line} to column
     * {@code destinationX} of this row with two {@link System#arraycopy} calls.
     *
     * <p>Applies only while neither row can hold anything but exactly one {@code char} per column
     * (no wide CJK, no surrogate pairs, no combining marks): then char index == column for
     * {@link #mText} <em>and</em> for {@link #mStyle}, so the whole interval is a pair of bulk
     * copies. The general path instead calls {@link #setChar} once per cell, and each of those
     * re-derives column offsets, re-checks wcwidth and re-validates the A1 memo — a reflow of a
     * 10&nbsp;000-row transcript at 200 columns is two million such calls.</p>
     *
     * <p>Overlap is fine: {@code System.arraycopy} has memmove semantics when source and
     * destination are the same array, so a row copying onto itself needs no snapshot.</p>
     *
     * @return true when the fast path was taken; false when the caller must fall back to the
     *         per-cell path.
     */
    boolean copyPlainInterval(TerminalRow line, int sourceX, int destinationX, int length) {
        if (length <= 0) return true;
        if (sourceX < 0 || destinationX < 0) return false;
        if (mHasNonOneWidthOrSurrogateChars || line.mHasNonOneWidthOrSurrogateChars) return false;
        // Both rows are one char per column, but their column counts may differ (reflow), so bound
        // against both. mStyle.length == mColumns; mText is over-allocated.
        if (sourceX + length > line.mStyle.length || destinationX + length > mStyle.length) return false;
        if (sourceX + length > line.mText.length || destinationX + length > mText.length) return false;

        // E4: the copy keeps the row blank & uniform only when both rows already were, under the
        // same style — otherwise the destination ends up with two different styles in it.
        final boolean staysUniform = line.mBlankAndUniform && mBlankAndUniform && line.mStyle[0] == mStyle[0];

        System.arraycopy(line.mText, sourceX, mText, destinationX, length);
        System.arraycopy(line.mStyle, sourceX, mStyle, destinationX, length);
        if (!staysUniform) mBlankAndUniform = false;
        // A1: the memoised column -> index map is unchanged (index == column in both rows, and
        // nothing shifted), but it was derived by scanning: drop it so it is rebuilt afresh.
        mCachedColumn = -1;
        mCachedCharIndex = -1;
        // Defensive: a plain row always has mSpaceUsed == mColumns, so this is a no-op in practice.
        // It keeps the "mSpaceUsed covers everything written" invariant true if that ever changes.
        if (mSpaceUsed < destinationX + length) mSpaceUsed = (short) (destinationX + length);
        return true;
    }

    /** NOTE: The sourceX2 is exclusive. */
    public void copyInterval(TerminalRow line, int sourceX1, int sourceX2, int destinationX) {
        // B2/F6: plain rows are two arraycopies instead of one setChar() per cell.
        if (copyPlainInterval(line, sourceX1, destinationX, sourceX2 - sourceX1)) return;
        mHasNonOneWidthOrSurrogateChars |= line.mHasNonOneWidthOrSurrogateChars;
        // E4: a bulk copy can leave the row blank, but proving that costs a scan. Just drop the
        // flag — blockCopy() is rare (scroll regions, insert/delete line) and correctness first.
        mBlankAndUniform = false;
        final int x1 = line.findStartOfColumn(sourceX1);
        final int x2 = line.findStartOfColumn(sourceX2);
        boolean startingFromSecondHalfOfWideChar = (sourceX1 > 0 && line.wideDisplayCharacterStartingAt(sourceX1 - 1));
        final char[] sourceChars = (this == line) ? Arrays.copyOf(line.mText, line.mText.length) : line.mText;
        int latestNonCombiningWidth = 0;
        for (int i = x1; i < x2; i++) {
            char sourceChar = sourceChars[i];
            int codePoint = Character.isHighSurrogate(sourceChar) ? Character.toCodePoint(sourceChar, sourceChars[++i]) : sourceChar;
            if (startingFromSecondHalfOfWideChar) {
                // Just treat copying second half of wide char as copying whitespace.
                codePoint = ' ';
                startingFromSecondHalfOfWideChar = false;
            }
            int w = WcWidth.width(codePoint);
            if (w > 0) {
                destinationX += latestNonCombiningWidth;
                sourceX1 += latestNonCombiningWidth;
                latestNonCombiningWidth = w;
            }
            setChar(destinationX, codePoint, line.getStyle(sourceX1));
        }
    }

    public int getSpaceUsed() {
        return mSpaceUsed;
    }

    /** Note that the column may end of second half of wide character. */
    public int findStartOfColumn(int column) {
        if (column == mColumns) return getSpaceUsed();

        // A1: resume the scan from the memoized position when the requested column is at or after it,
        // which is the common case for left-to-right output. Otherwise scan from the start.
        int currentColumn;
        int currentCharIndex;
        if (column >= mCachedColumn && mCachedColumn >= 0 && mCachedCharIndex >= 0 && mCachedCharIndex <= mSpaceUsed) {
            currentColumn = mCachedColumn;
            currentCharIndex = mCachedCharIndex;
        } else {
            currentColumn = 0;
            currentCharIndex = 0;
        }

        while (true) { // 0<2 1 < 2
            int newCharIndex = currentCharIndex;
            char c = mText[newCharIndex++]; // cci=1, cci=2
            boolean isHigh = Character.isHighSurrogate(c);
            int codePoint = isHigh ? Character.toCodePoint(c, mText[newCharIndex++]) : c;
            int wcwidth = WcWidth.width(codePoint); // 1, 2
            if (wcwidth > 0) {
                currentColumn += wcwidth;
                if (currentColumn == column) {
                    while (newCharIndex < mSpaceUsed) {
                        // Skip combining chars.
                        if (Character.isHighSurrogate(mText[newCharIndex])) {
                            if (WcWidth.width(Character.toCodePoint(mText[newCharIndex], mText[newCharIndex + 1])) <= 0) {
                                newCharIndex += 2;
                            } else {
                                break;
                            }
                        } else if (WcWidth.width(mText[newCharIndex]) <= 0) {
                            newCharIndex++;
                        } else {
                            break;
                        }
                    }
                    mCachedColumn = column;
                    mCachedCharIndex = newCharIndex;
                    return newCharIndex;
                } else if (currentColumn > column) {
                    // Wide column going past end.
                    // A1: the requested column falls *inside* a wide char, so (column,
                    // currentCharIndex) is not a consistent "column → index of its first char" pair.
                    // Memoizing it would make every later scan for a greater column resume one
                    // column off and return the wrong char index. Drop the memo instead.
                    mCachedColumn = -1;
                    mCachedCharIndex = -1;
                    return currentCharIndex;
                }
            }
            currentCharIndex = newCharIndex;
        }
    }

    /**
     * B1: does a wide (display width 2) character start at {@code column}?
     *
     * <p>{@link #setChar} calls this up to twice per written code point once a row holds any wide
     * or surrogate character, and every call used to walk {@code mText} from index 0 — filling a
     * row was O(columns²) on CJK/emoji output. The scan now resumes from the A1 memo
     * ({@code mCachedColumn}/{@code mCachedCharIndex}) whenever the requested column is at or after
     * it, which is the common case for left-to-right output, so a row is filled in one linear pass
     * instead of one pass per character. Measured on a 200x50 screen: a full-screen CJK write went
     * 1344 µs → 1071 µs, and 10k mixed ASCII/wide/surrogate writes 26.5 ms → 21.9 ms.</p>
     *
     * <p>Do not route this through {@link #findStartOfColumn} instead (two lookups; "same index for
     * column and column+1" ⇒ wide). That measured 1615 µs, i.e. 20% *slower* than the original:
     * querying the column that follows a wide character takes the "inside a wide char" branch,
     * which correctly drops the memo, so the very next lookup rescans from zero and pays the
     * bookkeeping on top. The plain loop leaves the memo intact.</p>
     */
    private boolean wideDisplayCharacterStartingAt(int column) {
        if (column < 0 || column >= mColumns) return false;
        int currentColumn;
        int currentCharIndex;
        if (column >= mCachedColumn && mCachedColumn >= 0 && mCachedCharIndex >= 0 && mCachedCharIndex <= mSpaceUsed) {
            // Resume from the A1 memo; the invariant is the one findStartOfColumn() already relies on.
            currentColumn = mCachedColumn;
            currentCharIndex = mCachedCharIndex;
        } else {
            currentColumn = 0;
            currentCharIndex = 0;
        }
        while (currentCharIndex < mSpaceUsed) {
            char c = mText[currentCharIndex++];
            int codePoint = Character.isHighSurrogate(c) ? Character.toCodePoint(c, mText[currentCharIndex++]) : c;
            int wcwidth = WcWidth.width(codePoint);
            if (wcwidth > 0) {
                if (currentColumn == column) return wcwidth == 2;
                currentColumn += wcwidth;
                if (currentColumn > column) return false;
            }
        }
        return false;
    }

    public void clear(long style) {
        Arrays.fill(mText, ' ');
        Arrays.fill(mStyle, style);
        mSpaceUsed = (short) mColumns;
        mHasNonOneWidthOrSurrogateChars = false;
        mCachedColumn = -1;
        mCachedCharIndex = -1;
        // E4: a cleared row is exactly the shape the renderer's blank fast path looks for.
        mBlankAndUniform = true;
    }

    /**
     * E4: true when every cell of the row is a space under one single style.
     *
     * <p>When this holds, {@code getStyle(0)} is that style for every column, so the renderer can
     * skip the per-column scan and go straight to the single background rectangle.</p>
     */
    public boolean isBlankAndUniform() {
        return mBlankAndUniform;
    }

    // https://github.com/steven676/Android-Terminal-Emulator/commit/9a47042620bec87617f0b4f5d50568535668fe26
    public void setChar(int columnToSet, int codePoint, long style) {
        if (columnToSet  < 0 || columnToSet >= mStyle.length)
            throw new IllegalArgumentException("TerminalRow.setChar(): columnToSet=" + columnToSet + ", codePoint=" + codePoint + ", style=" + style);

        // E4: read the reference style *before* overwriting it, otherwise a space written with a
        // foreign style at column 0 would compare equal to itself and leave the flag wrongly set.
        final boolean wasBlankAndUniform = mBlankAndUniform;
        final long uniformStyle = mStyle[0];
        mStyle[columnToSet] = style;
        if (wasBlankAndUniform && (codePoint != ' ' || style != uniformStyle)) mBlankAndUniform = false;

        final int newCodePointDisplayWidth = WcWidth.width(codePoint);

        // Fast path when we don't have any chars with width != 1
        if (!mHasNonOneWidthOrSurrogateChars) {
            if (codePoint >= Character.MIN_SUPPLEMENTARY_CODE_POINT || newCodePointDisplayWidth != 1) {
                mHasNonOneWidthOrSurrogateChars = true;
            } else {
                mText[columnToSet] = (char) codePoint;
                return;
            }
        }

        final boolean newIsCombining = newCodePointDisplayWidth <= 0;

        boolean wasExtraColForWideChar = (columnToSet > 0) && wideDisplayCharacterStartingAt(columnToSet - 1);

        if (newIsCombining) {
            // When standing at second half of wide character and inserting combining:
            if (wasExtraColForWideChar) columnToSet--;
        } else {
            // Check if we are overwriting the second half of a wide character starting at the previous column:
            if (wasExtraColForWideChar) setChar(columnToSet - 1, ' ', style);
            // Check if we are overwriting the first half of a wide character starting at the next column:
            boolean overwritingWideCharInNextColumn = newCodePointDisplayWidth == 2 && wideDisplayCharacterStartingAt(columnToSet + 1);
            if (overwritingWideCharInNextColumn) setChar(columnToSet + 1, ' ', style);
        }

        char[] text = mText;
        final int oldStartOfColumnIndex = findStartOfColumn(columnToSet);
        final int oldCodePointDisplayWidth = WcWidth.width(text, oldStartOfColumnIndex);

        // Get the number of elements in the mText array this column uses now
        int oldCharactersUsedForColumn;
        if (columnToSet + oldCodePointDisplayWidth < mColumns) {
            int oldEndOfColumnIndex = findStartOfColumn(columnToSet + oldCodePointDisplayWidth);
            oldCharactersUsedForColumn = oldEndOfColumnIndex - oldStartOfColumnIndex;
        } else {
            // Last character.
            oldCharactersUsedForColumn = mSpaceUsed - oldStartOfColumnIndex;
        }

        // If MAX_COMBINING_CHARACTERS_PER_COLUMN already exist in column, then ignore adding additional combining characters.
        if (newIsCombining) {
            int combiningCharsCount = WcWidth.zeroWidthCharsCount(mText, oldStartOfColumnIndex, oldStartOfColumnIndex + oldCharactersUsedForColumn);
            if (combiningCharsCount >= MAX_COMBINING_CHARACTERS_PER_COLUMN)
                return;
        }

        // Find how many chars this column will need
        int newCharactersUsedForColumn = Character.charCount(codePoint);
        if (newIsCombining) {
            // Combining characters are added to the contents of the column instead of overwriting them, so that they
            // modify the existing contents.
            // FIXME: Unassigned characters also get width=0.
            newCharactersUsedForColumn += oldCharactersUsedForColumn;
        }

        int oldNextColumnIndex = oldStartOfColumnIndex + oldCharactersUsedForColumn;
        int newNextColumnIndex = oldStartOfColumnIndex + newCharactersUsedForColumn;

        final int javaCharDifference = newCharactersUsedForColumn - oldCharactersUsedForColumn;
        if (javaCharDifference > 0) {
            // Shift the rest of the line right.
            int oldCharactersAfterColumn = mSpaceUsed - oldNextColumnIndex;
            if (mSpaceUsed + javaCharDifference > text.length) {
                // We need to grow the array
                char[] newText = new char[text.length + mColumns];
                System.arraycopy(text, 0, newText, 0, oldNextColumnIndex);
                System.arraycopy(text, oldNextColumnIndex, newText, newNextColumnIndex, oldCharactersAfterColumn);
                mText = text = newText;
            } else {
                System.arraycopy(text, oldNextColumnIndex, text, newNextColumnIndex, oldCharactersAfterColumn);
            }
            // A1: char indices shifted → the memoized column→index map is no longer valid.
            mCachedColumn = -1;
        } else if (javaCharDifference < 0) {
            // Shift the rest of the line left.
            System.arraycopy(text, oldNextColumnIndex, text, newNextColumnIndex, mSpaceUsed - oldNextColumnIndex);
            // A1: char indices shifted → invalidate the memoized column→index map.
            mCachedColumn = -1;
        }
        mSpaceUsed += javaCharDifference;

        // Store char. A combining character is stored at the end of the existing contents so that it modifies them:
        //noinspection ResultOfMethodCallIgnored - since we already now how many java chars is used.
        Character.toChars(codePoint, text, oldStartOfColumnIndex + (newIsCombining ? oldCharactersUsedForColumn : 0));

        if (oldCodePointDisplayWidth == 2 && newCodePointDisplayWidth == 1) {
            // Replace second half of wide char with a space. Which mean that we actually add a ' ' java character.
            if (mSpaceUsed + 1 > text.length) {
                char[] newText = new char[text.length + mColumns];
                System.arraycopy(text, 0, newText, 0, newNextColumnIndex);
                System.arraycopy(text, newNextColumnIndex, newText, newNextColumnIndex + 1, mSpaceUsed - newNextColumnIndex);
                mText = text = newText;
            } else {
                System.arraycopy(text, newNextColumnIndex, text, newNextColumnIndex + 1, mSpaceUsed - newNextColumnIndex);
            }
            text[newNextColumnIndex] = ' ';
            // A1: char indices shifted → invalidate the memoized column→index map.
            mCachedColumn = -1;

            ++mSpaceUsed;
        } else if (oldCodePointDisplayWidth == 1 && newCodePointDisplayWidth == 2) {
            if (columnToSet == mColumns - 1) {
                throw new IllegalArgumentException("Cannot put wide character in last column");
            } else if (columnToSet == mColumns - 2) {
                // Truncate the line to the second part of this wide char:
                mSpaceUsed = (short) newNextColumnIndex;
            } else {
                // Overwrite the contents of the next column, which mean we actually remove java characters. Due to the
                // check at the beginning of this method we know that we are not overwriting a wide char.
                int newNextNextColumnIndex = newNextColumnIndex + (Character.isHighSurrogate(mText[newNextColumnIndex]) ? 2 : 1);
                int nextLen = newNextNextColumnIndex - newNextColumnIndex;

                // Shift the array leftwards.
                System.arraycopy(text, newNextNextColumnIndex, text, newNextColumnIndex, mSpaceUsed - newNextNextColumnIndex);
                // A1: char indices shifted → invalidate the memoized column→index map.
                mCachedColumn = -1;
                mSpaceUsed -= nextLen;
            }
        }
    }

    boolean isBlank() {
        for (int charIndex = 0, charLen = getSpaceUsed(); charIndex < charLen; charIndex++)
            if (mText[charIndex] != ' ') return false;
        return true;
    }

    public final long getStyle(int column) {
        return mStyle[column];
    }

    /**
     * Whether this row may contain code points whose display width != 1 (wide CJK, zero-width
     * combining) or surrogate pairs. When this is false the row is guaranteed to hold exactly one
     * {@code char} per column, so {@code mText[column]} is the code point of {@code column} and
     * the renderer can walk the row without any wcwidth/surrogate handling.
     *
     * <p>Read-only accessor for {@link #mHasNonOneWidthOrSurrogateChars} — the field is
     * package-private and the renderer lives in another package.</p>
     */
    public boolean hasNonOneWidthOrSurrogateChars() {
        return mHasNonOneWidthOrSurrogateChars;
    }

}
