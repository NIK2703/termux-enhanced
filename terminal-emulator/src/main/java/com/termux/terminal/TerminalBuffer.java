package com.termux.terminal;

import java.util.Arrays;

/**
 * A circular buffer of {@link TerminalRow}:s which keeps notes about what is visible on a logical screen and the scroll
 * history.
 * <p>
 * See {@link #externalToInternalRow(int)} for how to map from logical screen rows to array indices.
 */
public final class TerminalBuffer {

    TerminalRow[] mLines;
    /** The length of {@link #mLines}. */
    int mTotalRows;
    /** The number of rows and columns visible on the screen. */
    int mScreenRows, mColumns;
    /** The number of rows kept in history. */
    private int mActiveTranscriptRows = 0;
    /** The index in the circular buffer where the visible screen starts. */
    private int mScreenFirstRow = 0;

    // ── Dirty-row tracking for partial redraws ────────────────────────────────────────────
    // E2: one bit per *internal* (ring) row instead of an external-coordinate range.
    //
    // Why internal: `scrollDownOneLine()` does not rewrite the rows in the screen window, it only
    // advances `mScreenFirstRow`, i.e. it changes which internal row a given external coordinate
    // points at. Tracking external rows therefore forces every scroll to declare the whole buffer
    // dirty (or to shift the entire pending set), which is what made each output line cost a full
    // frame. An internal row keeps its identity across a scroll, so "this row was written to"
    // stays both true and precise, and `scrollDownOneLine()` only has to mark the single row it
    // actually rewrites — the newly revealed one.
    //
    // The dirty set alone does not decide full-vs-partial: when the visible window moves (live
    // bottom, wheel, fling, snap) every pixel changes although no row is dirty. That is the
    // view's "content anchor" check, see TerminalView.repaintAfterUpdate().
    private long[] mDirtyRows;
    private boolean mAnyRowDirty;
    /** Forces a complete repaint: set on construction, resize, partial scrolls, buffer switches. */
    private boolean mAllDirty = true;

    /** Shared read-only blank row returned by {@link #getLineOrBlank(int)} for never-written lines. */
    private TerminalRow mBlankRow;
    private int mBlankRowColumns = -1;
    private long mBlankRowStyle = -1;

    /**
     * Style used to fill rows that are materialized lazily (see
     * {@link #allocateFullLineIfNecessary(int)}) and the shared blank row. It follows the
     * emulator's current style as of the last {@link #resize(int, int, int, int[], long, boolean)} —
     * the same style that the pre-lazy-allocation code gave to every row it allocated eagerly.
     * <p>
     * It must never be left at 0: style 0 decodes to palette index 0 for <em>both</em> the
     * foreground and the background, so freshly allocated rows would render as black glyphs on black
     * background rectangles instead of the scheme's default colors.
     */
    private long mDefaultStyle = TextStyle.NORMAL;

    /** Mark a single external row as needing a repaint. */
    public void markRowDirty(int externalRow) {
        if (mAllDirty) return;
        // Defensive: callers may hold a row index from before the last resize — the view's
        // "previous cursor row" is the realistic one (mLastCursorRow can still be 47 when a
        // rotation cut the screen to 24 rows). externalToInternalRow() *throws* out of range,
        // and a stale index denotes a row that no longer exists, i.e. nothing to repaint — so
        // ignore it instead of turning a rotation into a crash.
        if (externalRow < -mActiveTranscriptRows || externalRow > mScreenRows) return;
        markInternalRowDirty(externalToInternalRow(externalRow));
    }

    /**
     * E1/E2: mark a ring slot dirty. This is the only mark {@link #scrollDownOneLine(int, int, long)}
     * needs for a full-screen scroll — the one row it clears. Everything else in the window merely
     * moved, and movement is handled by the view's content anchor, not by the dirty set.
     */
    public void markInternalRowDirty(int internalRow) {
        if (mAllDirty || mDirtyRows == null) return;
        if (internalRow < 0 || internalRow >= mTotalRows) return;
        mDirtyRows[internalRow >>> 6] |= (1L << (internalRow & 63));
        mAnyRowDirty = true;
    }

    /** E2: has the content of this external row changed since it was last drawn? */
    public boolean isRowDirty(int externalRow) {
        if (mAllDirty) return true;
        if (!mAnyRowDirty) return false;
        final int internalRow = externalToInternalRow(externalRow);
        if (internalRow < 0 || internalRow >= mTotalRows) return false;
        return (mDirtyRows[internalRow >>> 6] & (1L << (internalRow & 63))) != 0L;
    }

    /**
     * E2: drop the dirty bit of one external row, once it has actually been drawn.
     *
     * <p>Rows that were dirty but not visible keep their bit: the emulator can write to a screen
     * row while the user is scrolled away, and that row must still be repainted when it scrolls
     * back into view. Clearing per drawn row (instead of dropping the whole state up-front, as
     * the view used to do) is what makes that work.</p>
     */
    public void clearRowDirty(int externalRow) {
        if (mAllDirty || mDirtyRows == null) return;
        final int internalRow = externalToInternalRow(externalRow);
        if (internalRow < 0 || internalRow >= mTotalRows) return;
        mDirtyRows[internalRow >>> 6] &= ~(1L << (internalRow & 63));
    }

    /** Force a complete repaint of this buffer on the next frame. */
    public void markAllDirty() {
        if (mAllDirty) return;
        mAllDirty = true;
        mAnyRowDirty = false;
        if (mDirtyRows != null) java.util.Arrays.fill(mDirtyRows, 0L);
    }

    public boolean isAllDirty() {
        return mAllDirty;
    }

    public boolean hasDirtyRows() {
        return mAllDirty || mAnyRowDirty;
    }

    /**
     * Reset dirty tracking once the pending repaint has been drawn.
     *
     * <p>Callers: only {@code TerminalRenderer.render()}, after the pixels are on the canvas. The
     * dirty set is read *during* {@code onDraw()}, so it must not be cleared when the repaint is
     * scheduled — only when it has happened.</p>
     */
    public void clearDirtyState() {
        mAllDirty = false;
        mAnyRowDirty = false;
        if (mDirtyRows != null) java.util.Arrays.fill(mDirtyRows, 0L);
    }

    /**
     * Create a transcript screen.
     *
     * @param columns    the width of the screen in characters.
     * @param totalRows  the height of the entire text area, in rows of text.
     * @param screenRows the height of just the screen, not including the transcript that holds lines that have scrolled off
     *                   the top of the screen.
     */
    public TerminalBuffer(int columns, int totalRows, int screenRows) {
        mColumns = columns;
        mTotalRows = totalRows;
        mScreenRows = screenRows;
        mLines = new TerminalRow[totalRows];
        mDirtyRows = new long[(totalRows + 63) >>> 6];

        blockSet(0, 0, columns, screenRows, ' ', TextStyle.NORMAL);
    }

    public String getTranscriptText() {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows).trim();
    }

    public String getTranscriptTextWithoutJoinedLines() {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows, false).trim();
    }

    public String getTranscriptTextWithFullLinesJoined() {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows, true, true).trim();
    }

    public String getSelectedText(int selX1, int selY1, int selX2, int selY2) {
        return getSelectedText(selX1, selY1, selX2, selY2, true);
    }

    public String getSelectedText(int selX1, int selY1, int selX2, int selY2, boolean joinBackLines) {
        return getSelectedText(selX1, selY1, selX2, selY2, joinBackLines, false);
    }

    public String getSelectedText(int selX1, int selY1, int selX2, int selY2, boolean joinBackLines, boolean joinFullLines) {
        final StringBuilder builder = new StringBuilder();
        final int columns = mColumns;

        if (selY1 < -getActiveTranscriptRows()) selY1 = -getActiveTranscriptRows();
        if (selY2 >= mScreenRows) selY2 = mScreenRows - 1;

        for (int row = selY1; row <= selY2; row++) {
            int x1 = (row == selY1) ? selX1 : 0;
            int x2;
            if (row == selY2) {
                x2 = selX2 + 1;
                if (x2 > columns) x2 = columns;
            } else {
                x2 = columns;
            }
            TerminalRow lineObject = getLineOrBlank(row);
            int x1Index = lineObject.findStartOfColumn(x1);
            int x2Index = (x2 < mColumns) ? lineObject.findStartOfColumn(x2) : lineObject.getSpaceUsed();
            if (x2Index == x1Index) {
                // Selected the start of a wide character.
                x2Index = lineObject.findStartOfColumn(x2 + 1);
            }
            char[] line = lineObject.mText;
            int lastPrintingCharIndex = -1;
            int i;
            boolean rowLineWrap = getLineWrap(row);
            if (rowLineWrap && x2 == columns) {
                // If the line was wrapped, we shouldn't lose trailing space:
                lastPrintingCharIndex = x2Index - 1;
            } else {
                for (i = x1Index; i < x2Index; ++i) {
                    char c = line[i];
                    if (c != ' ') lastPrintingCharIndex = i;
                }
            }

            int len = lastPrintingCharIndex - x1Index + 1;
            if (lastPrintingCharIndex != -1 && len > 0)
                builder.append(line, x1Index, len);

            boolean lineFillsWidth = lastPrintingCharIndex == x2Index - 1;
            if ((!joinBackLines || !rowLineWrap) && (!joinFullLines || !lineFillsWidth)
                && row < selY2 && row < mScreenRows - 1) builder.append('\n');
        }
        return builder.toString();
    }

    public String getWordAtLocation(int x, int y) {
        // Set y1 and y2 to the lines where the wrapped line starts and ends.
        // I.e. if a line that is wrapped to 3 lines starts at line 4, and this
        // is called with y=5, then y1 would be set to 4 and y2 would be set to 6.
        int y1 = y;
        int y2 = y;
        while (y1 > 0 && !getSelectedText(0, y1 - 1, mColumns, y, true, true).contains("\n")) {
            y1--;
        }
        while (y2 < mScreenRows && !getSelectedText(0, y, mColumns, y2 + 1, true, true).contains("\n")) {
            y2++;
        }

        // Get the text for the whole wrapped line
        String text = getSelectedText(0, y1, mColumns, y2, true, true);
        // The index of x in text
        int textOffset = (y - y1) * mColumns + x;

        if (textOffset >= text.length()) {
          // The click was to the right of the last word on the line, so
          // there's no word to return
          return "";
        }

        // Set x1 and x2 to the indices of the last space before x and the
        // first space after x in text respectively
        int x1 = text.lastIndexOf(' ', textOffset);
        int x2 = text.indexOf(' ', textOffset);
        if (x2 == -1) {
            x2 = text.length();
        }

        if (x1 == x2) {
          // The click was on a space, so there's no word to return
          return "";
        }
        return text.substring(x1 + 1, x2);
    }

    public int getActiveTranscriptRows() {
        return mActiveTranscriptRows;
    }

    public int getActiveRows() {
        return mActiveTranscriptRows + mScreenRows;
    }

    /**
     * Convert a row value from the public external coordinate system to our internal private coordinate system.
     *
     * <pre>
     * - External coordinate system: -mActiveTranscriptRows to mScreenRows-1, with the screen being 0..mScreenRows-1.
     * - Internal coordinate system: the mScreenRows lines starting at mScreenFirstRow comprise the screen, while the
     *   mActiveTranscriptRows lines ending at mScreenFirstRow-1 form the transcript (as a circular buffer).
     *
     * External ↔ Internal:
     *
     * [ ...                            ]     [ ...                                     ]
     * [ -mActiveTranscriptRows         ]     [ mScreenFirstRow - mActiveTranscriptRows ]
     * [ ...                            ]     [ ...                                     ]
     * [ 0 (visible screen starts here) ]  ↔  [ mScreenFirstRow                         ]
     * [ ...                            ]     [ ...                                     ]
     * [ mScreenRows-1                  ]     [ mScreenFirstRow + mScreenRows-1         ]
     * </pre>
     *
     * @param externalRow a row in the external coordinate system.
     * @return The row corresponding to the input argument in the private coordinate system.
     */
    public int externalToInternalRow(int externalRow) {
        if (externalRow < -mActiveTranscriptRows || externalRow > mScreenRows)
            throw new IllegalArgumentException("extRow=" + externalRow + ", mScreenRows=" + mScreenRows + ", mActiveTranscriptRows=" + mActiveTranscriptRows);
        final int internalRow = mScreenFirstRow + externalRow;
        return (internalRow < 0) ? (mTotalRows + internalRow) : (internalRow % mTotalRows);
    }

    /**
     * Note: {@link #setLineWrap(int)} / {@link #clearLineWrap(int)} intentionally do NOT mark the
     * row dirty. {@code mLineWrap} only affects text extraction ({@link #getSelectedText(int, int, int, int)}),
     * never the rendered pixels, and the row's cells are always (re)written via {@link #setChar(int, int, int, long)}
     * at the same time, which does mark it. If the renderer ever starts drawing based on wrap state,
     * these methods must start calling {@link #markRowDirty(int)}.
     */
    public void setLineWrap(int row) {
        // A3: allocate-if-necessary so lazy (null) transcript rows never NPE.
        allocateFullLineIfNecessary(externalToInternalRow(row)).mLineWrap = true;
    }

    public boolean getLineWrap(int row) {
        // A3: a never-written (null) row has no wrap.
        final TerminalRow line = mLines[externalToInternalRow(row)];
        return line != null && line.mLineWrap;
    }

    public void clearLineWrap(int row) {
        // A3: a never-written (null) row has no wrap to clear.
        final TerminalRow line = mLines[externalToInternalRow(row)];
        if (line != null) line.mLineWrap = false;
    }

    /**
     * Resize the screen which this transcript backs. Currently, this only works if the number of columns does not
     * change or the rows expand (that is, it only works when shrinking the number of rows).
     *
     * @param newColumns The number of columns the screen should have.
     * @param newRows    The number of rows the screen should have.
     * @param cursor     An int[2] containing the (column, row) cursor location.
     */
    public void resize(int newColumns, int newRows, int newTotalRows, int[] cursor, long currentStyle, boolean altScreen) {
        // Rows created after this point (lazily) must be filled with the same style that the
        // eager allocation used to give them, not with 0 (see {@link #mDefaultStyle}).
        mDefaultStyle = currentStyle;

        // E2: the dirty set is indexed by internal (ring) row, so it has to be resized together
        // with the ring — and before the re-flow below, which calls scrollDownOneLine().
        mDirtyRows = new long[(newTotalRows + 63) >>> 6];
        mAnyRowDirty = false;

        // newRows > mTotalRows should not normally happen since mTotalRows is TRANSCRIPT_ROWS (10000):
        if (newColumns == mColumns && newRows <= mTotalRows) {
            // Fast resize where just the rows changed.
            int shiftDownOfTopRow = mScreenRows - newRows;
            if (shiftDownOfTopRow > 0 && shiftDownOfTopRow < mScreenRows) {
                // Shrinking. Check if we can skip blank rows at bottom below cursor.
                for (int i = mScreenRows - 1; i > 0; i--) {
                    if (cursor[1] >= i) break;
                    int r = externalToInternalRow(i);
                    if (mLines[r] == null || mLines[r].isBlank()) {
                        if (--shiftDownOfTopRow == 0) break;
                    }
                }
            } else if (shiftDownOfTopRow < 0) {
                // Negative shift down = expanding. Only move screen up if there is transcript to show:
                int actualShift = Math.max(shiftDownOfTopRow, -mActiveTranscriptRows);
                if (shiftDownOfTopRow != actualShift) {
                    // The new lines revealed by the resizing are not all from the transcript. Blank the below ones.
                    for (int i = 0; i < actualShift - shiftDownOfTopRow; i++)
                        allocateFullLineIfNecessary((mScreenFirstRow + mScreenRows + i) % mTotalRows).clear(currentStyle);
                    shiftDownOfTopRow = actualShift;
                }
            }
            mScreenFirstRow += shiftDownOfTopRow;
            mScreenFirstRow = (mScreenFirstRow < 0) ? (mScreenFirstRow + mTotalRows) : (mScreenFirstRow % mTotalRows);
            mTotalRows = newTotalRows;
            mActiveTranscriptRows = altScreen ? 0 : Math.max(0, mActiveTranscriptRows + shiftDownOfTopRow);
            cursor[1] -= shiftDownOfTopRow;
            mScreenRows = newRows;
        } else {
            // Copy away old state and update new:
            TerminalRow[] oldLines = mLines;
            // A3: do NOT eagerly allocate every transcript row (up to 50000). Leave the array slots
            // null and let setChar/allocateFullLineIfNecessary/getLineOrBlank materialize only the
            // rows that are actually written — the same lazy pattern the constructor already uses via
            // blockSet→setChar. This avoids ~1.8–45 MB of garbage (and a GC pause) on every
            // orientation change / font switch, while all direct mLines readers above are null-safe.
            mLines = new TerminalRow[newTotalRows];

            final int oldActiveTranscriptRows = mActiveTranscriptRows;
            final int oldScreenFirstRow = mScreenFirstRow;
            final int oldScreenRows = mScreenRows;
            final int oldTotalRows = mTotalRows;
            mTotalRows = newTotalRows;
            mScreenRows = newRows;
            mActiveTranscriptRows = mScreenFirstRow = 0;
            mColumns = newColumns;

            int newCursorRow = -1;
            int newCursorColumn = -1;
            int oldCursorRow = cursor[1];
            int oldCursorColumn = cursor[0];
            boolean newCursorPlaced = false;

            int currentOutputExternalRow = 0;
            int currentOutputExternalColumn = 0;

            // Loop over every character in the initial state.
            // Blank lines should be skipped only if at end of transcript (just as is done in the "fast" resize), so we
            // keep track how many blank lines we have skipped if we later on find a non-blank line.
            int skippedBlankLines = 0;
            for (int externalOldRow = -oldActiveTranscriptRows; externalOldRow < oldScreenRows; externalOldRow++) {
                // Do what externalToInternalRow() does but for the old state:
                int internalOldRow = oldScreenFirstRow + externalOldRow;
                internalOldRow = (internalOldRow < 0) ? (oldTotalRows + internalOldRow) : (internalOldRow % oldTotalRows);

                TerminalRow oldLine = oldLines[internalOldRow];
                boolean cursorAtThisRow = externalOldRow == oldCursorRow;
                // The cursor may only be on a non-null line, which we should not skip:
                if (oldLine == null || (!(!newCursorPlaced && cursorAtThisRow)) && oldLine.isBlank()) {
                    skippedBlankLines++;
                    continue;
                } else if (skippedBlankLines > 0) {
                    // After skipping some blank lines we encounter a non-blank line. Insert the skipped blank lines.
                    for (int i = 0; i < skippedBlankLines; i++) {
                        if (currentOutputExternalRow == mScreenRows - 1) {
                            scrollDownOneLine(0, mScreenRows, currentStyle);
                        } else {
                            currentOutputExternalRow++;
                        }
                        currentOutputExternalColumn = 0;
                    }
                    skippedBlankLines = 0;
                }

                int lastNonSpaceIndex = 0;
                boolean justToCursor = false;
                if (cursorAtThisRow || oldLine.mLineWrap) {
                    // Take the whole line, either because of cursor on it, or if line wrapping.
                    lastNonSpaceIndex = oldLine.getSpaceUsed();
                    if (cursorAtThisRow) justToCursor = true;
                } else {
                    for (int i = 0; i < oldLine.getSpaceUsed(); i++)
                        // NEWLY INTRODUCED BUG! Should not index oldLine.mStyle with char indices
                        if (oldLine.mText[i] != ' '/* || oldLine.mStyle[i] != currentStyle */)
                            lastNonSpaceIndex = i + 1;
                }

                // B2/F6: a plain row (one char per column) that fits the new width without
                // wrapping is two arraycopies instead of one setChar() per cell. Cursor rows are
                // excluded: the loop below also has to place the cursor and to wrap it.
                int currentOldCol = 0;
                long styleAtCol = 0;
                boolean copiedInBulk = false;
                if (!cursorAtThisRow && !oldLine.mHasNonOneWidthOrSurrogateChars
                        && currentOutputExternalColumn + lastNonSpaceIndex <= mColumns) {
                    TerminalRow newLine = allocateFullLineIfNecessary(externalToInternalRow(currentOutputExternalRow));
                    if (newLine.copyPlainInterval(oldLine, 0, currentOutputExternalColumn, lastNonSpaceIndex)) {
                        currentOutputExternalColumn += lastNonSpaceIndex;
                        copiedInBulk = true;
                    }
                }

                for (int i = 0; !copiedInBulk && i < lastNonSpaceIndex; i++) {
                    // Note that looping over java character, not cells.
                    char c = oldLine.mText[i];
                    int codePoint = (Character.isHighSurrogate(c)) ? Character.toCodePoint(c, oldLine.mText[++i]) : c;
                    int displayWidth = WcWidth.width(codePoint);
                    // Use the last style if this is a zero-width character:
                    if (displayWidth > 0) styleAtCol = oldLine.getStyle(currentOldCol);

                    // Line wrap as necessary:
                    if (currentOutputExternalColumn + displayWidth > mColumns) {
                        setLineWrap(currentOutputExternalRow);
                        if (currentOutputExternalRow == mScreenRows - 1) {
                            if (newCursorPlaced) newCursorRow--;
                            scrollDownOneLine(0, mScreenRows, currentStyle);
                        } else {
                            currentOutputExternalRow++;
                        }
                        currentOutputExternalColumn = 0;
                    }

                    int offsetDueToCombiningChar = ((displayWidth <= 0 && currentOutputExternalColumn > 0) ? 1 : 0);
                    int outputColumn = currentOutputExternalColumn - offsetDueToCombiningChar;
                    setChar(outputColumn, currentOutputExternalRow, codePoint, styleAtCol);

                    if (displayWidth > 0) {
                        if (oldCursorRow == externalOldRow && oldCursorColumn == currentOldCol) {
                            newCursorColumn = currentOutputExternalColumn;
                            newCursorRow = currentOutputExternalRow;
                            newCursorPlaced = true;
                        }
                        currentOldCol += displayWidth;
                        currentOutputExternalColumn += displayWidth;
                        if (justToCursor && newCursorPlaced) break;
                    }
                }
                // Old row has been copied. Check if we need to insert newline if old line was not wrapping:
                if (externalOldRow != (oldScreenRows - 1) && !oldLine.mLineWrap) {
                    if (currentOutputExternalRow == mScreenRows - 1) {
                        if (newCursorPlaced) newCursorRow--;
                        scrollDownOneLine(0, mScreenRows, currentStyle);
                    } else {
                        currentOutputExternalRow++;
                    }
                    currentOutputExternalColumn = 0;
                }
            }

            cursor[0] = newCursorColumn;
            cursor[1] = newCursorRow;
        }

        // Handle cursor scrolling off screen:
        if (cursor[0] < 0 || cursor[1] < 0) cursor[0] = cursor[1] = 0;
        markAllDirty();
    }

    /**
     * Block copy lines and associated metadata from one location to another in the circular buffer, taking wraparound
     * into account.
     *
     * @param srcInternal The first line to be copied.
     * @param len         The number of lines to be copied.
     */
    private void blockCopyLinesDown(int srcInternal, int len) {
        if (len == 0) return;
        int totalRows = mTotalRows;

        int start = len - 1;
        // Save away line to be overwritten:
        TerminalRow lineToBeOverWritten = mLines[(srcInternal + start + 1) % totalRows];
        // Do the copy from bottom to top.
        for (int i = start; i >= 0; --i)
            mLines[(srcInternal + i + 1) % totalRows] = mLines[(srcInternal + i) % totalRows];
        // Put back overwritten line, now above the block:
        mLines[(srcInternal) % totalRows] = lineToBeOverWritten;
    }

    /**
     * Scroll the screen down one line. To scroll the whole screen of a 24 line screen, the arguments would be (0, 24).
     *
     * @param topMargin    First line that is scrolled.
     * @param bottomMargin One line after the last line that is scrolled.
     * @param style        the style for the newly exposed line.
     */
    public void scrollDownOneLine(int topMargin, int bottomMargin, long style) {
        if (topMargin > bottomMargin - 1 || topMargin < 0 || bottomMargin > mScreenRows)
            throw new IllegalArgumentException("topMargin=" + topMargin + ", bottomMargin=" + bottomMargin + ", mScreenRows=" + mScreenRows);

        // E1: what a scroll changes is the *mapping* external row -> internal row, not the rows
        // themselves. With a full-screen scroll every row in the window keeps its TerminalRow; only
        // mScreenFirstRow advances. Marking everything dirty here (the previous behaviour) is what
        // forced a full frame per output line, including while the user is scrolled into history and
        // the view compensates with `mTopRow -= rowShift` so that not a single pixel moves.
        //
        // A partial scroll (scroll region, as used by htop/vi) is different: blockCopyLinesDown()
        // physically moves TerminalRow references between ring slots, so those slots really do
        // change content and the simple "nothing moved" argument does not hold. Full repaint there.
        if (!(topMargin == 0 && bottomMargin == mScreenRows)) markAllDirty();

        // Copy the fixed topMargin lines one line down so that they remain on screen in same position:
        blockCopyLinesDown(mScreenFirstRow, topMargin);
        // Copy the fixed mScreenRows-bottomMargin lines one line down so that they remain on screen in same
        // position:
        blockCopyLinesDown(externalToInternalRow(bottomMargin), mScreenRows - bottomMargin);

        // Update the screen location in the ring buffer:
        mScreenFirstRow = (mScreenFirstRow + 1) % mTotalRows;
        // Note that the history has grown if not already full:
        if (mActiveTranscriptRows < mTotalRows - mScreenRows) mActiveTranscriptRows++;

        // Blank the newly revealed line above the bottom margin:
        int blankRow = externalToInternalRow(bottomMargin - 1);
        if (mLines[blankRow] == null) {
            mLines[blankRow] = new TerminalRow(mColumns, style);
        } else {
            mLines[blankRow].clear(style);
        }
        // E1: the only row whose content actually changed in this scroll.
        markInternalRowDirty(blankRow);
    }

    /**
     * Block copy characters from one position in the screen to another. The two positions can overlap. All characters
     * of the source and destination must be within the bounds of the screen, or else an InvalidParameterException will
     * be thrown.
     *
     * @param sx source X coordinate
     * @param sy source Y coordinate
     * @param w  width
     * @param h  height
     * @param dx destination X coordinate
     * @param dy destination Y coordinate
     */
    public void blockCopy(int sx, int sy, int w, int h, int dx, int dy) {
        if (w == 0) return;
        if (sx < 0 || sx + w > mColumns || sy < 0 || sy + h > mScreenRows || dx < 0 || dx + w > mColumns || dy < 0 || dy + h > mScreenRows)
            throw new IllegalArgumentException();
        for (int y = 0; y < h; y++) {
            markRowDirty(sy + y);
            markRowDirty(dy + y);
        }
        boolean copyingUp = sy > dy;
        for (int y = 0; y < h; y++) {
            int y2 = copyingUp ? y : (h - (y + 1));
            TerminalRow sourceRow = allocateFullLineIfNecessary(externalToInternalRow(sy + y2));
            allocateFullLineIfNecessary(externalToInternalRow(dy + y2)).copyInterval(sourceRow, sx, sx + w, dx);
        }
    }

    /**
     * Block set characters. All characters must be within the bounds of the screen, or else and
     * InvalidParemeterException will be thrown. Typically this is called with a "val" argument of 32 to clear a block
     * of characters.
     */
    public void blockSet(int sx, int sy, int w, int h, int val, long style) {
        if (sx < 0 || sx + w > mColumns || sy < 0 || sy + h > mScreenRows) {
            throw new IllegalArgumentException(
                "Illegal arguments! blockSet(" + sx + ", " + sy + ", " + w + ", " + h + ", " + val + ", " + mColumns + ", " + mScreenRows + ")");
        }
        // B2/F6: erasing whole rows is an Arrays.fill per row, not one setChar() per cell — the
        // constructor alone used to run screenRows*columns of them to blank a buffer whose rows
        // are already blank on allocation.
        if (val == ' ' && sx == 0 && w == mColumns) {
            for (int y = 0; y < h; y++) {
                markRowDirty(sy + y);
                allocateFullLineIfNecessary(externalToInternalRow(sy + y)).clear(style);
            }
            return;
        }
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                setChar(sx + x, sy + y, val, style);
    }

    public TerminalRow allocateFullLineIfNecessary(int row) {
        TerminalRow line = mLines[row];
        if (line == null) line = mLines[row] = new TerminalRow(mColumns, mDefaultStyle);
        return line;
    }

    /**
     * Read-only access to the line at an external row, falling back to a shared blank row when the
     * line has never been written to. Unlike {@link #allocateFullLineIfNecessary(int)} this never
     * mutates the buffer, so it is safe to call from the renderer during {@code onDraw} without
     * allocating rows (and without racing a background writer). The returned blank row must only be
     * read, never modified.
     */
    public TerminalRow getLineOrBlank(int externalRow) {
        if (mBlankRow == null || mBlankRowColumns != mColumns || mBlankRowStyle != mDefaultStyle) {
            mBlankRow = new TerminalRow(mColumns, mDefaultStyle);
            mBlankRowColumns = mColumns;
            mBlankRowStyle = mDefaultStyle;
        }
        TerminalRow line = mLines[externalToInternalRow(externalRow)];
        return line != null ? line : mBlankRow;
    }

    public void setChar(int column, int row, int codePoint, long style) {
        if (row  < 0 || row >= mScreenRows || column < 0 || column >= mColumns)
            throw new IllegalArgumentException("TerminalBuffer.setChar(): row=" + row + ", column=" + column + ", mScreenRows=" + mScreenRows + ", mColumns=" + mColumns);
        markRowDirty(row);
        row = externalToInternalRow(row);
        allocateFullLineIfNecessary(row).setChar(column, codePoint, style);
    }

    public long getStyleAt(int externalRow, int column) {
        return allocateFullLineIfNecessary(externalToInternalRow(externalRow)).getStyle(column);
    }

    /** Support for http://vt100.net/docs/vt510-rm/DECCARA and http://vt100.net/docs/vt510-rm/DECCARA */
    public void setOrClearEffect(int bits, boolean setOrClear, boolean reverse, boolean rectangular, int leftMargin, int rightMargin, int top, int left,
                                 int bottom, int right) {
        for (int y = top; y < bottom; y++) {
            markRowDirty(y);
            // A3: allocate-if-necessary so lazy (null) transcript rows never NPE.
            TerminalRow line = allocateFullLineIfNecessary(externalToInternalRow(y));
            // E4: setOrClearEffect writes line.mStyle[x] directly, bypassing setChar(), so the
            // "every cell has the same style" invariant the A2 fast path relies on is no longer
            // something we know — drop the flag rather than try to prove uniformity.
            line.mBlankAndUniform = false;
            int startOfLine = (rectangular || y == top) ? left : leftMargin;
            int endOfLine = (rectangular || y + 1 == bottom) ? right : rightMargin;
            for (int x = startOfLine; x < endOfLine; x++) {
                long currentStyle = line.getStyle(x);
                int foreColor = TextStyle.decodeForeColor(currentStyle);
                int backColor = TextStyle.decodeBackColor(currentStyle);
                int effect = TextStyle.decodeEffect(currentStyle);
                if (reverse) {
                    // Clear out the bits to reverse and add them back in reversed:
                    effect = (effect & ~bits) | (bits & ~effect);
                } else if (setOrClear) {
                    effect |= bits;
                } else {
                    effect &= ~bits;
                }
                line.mStyle[x] = TextStyle.encode(foreColor, backColor, effect);
            }
        }
    }

    public void clearTranscript() {
        if (mScreenFirstRow < mActiveTranscriptRows) {
            Arrays.fill(mLines, mTotalRows + mScreenFirstRow - mActiveTranscriptRows, mTotalRows, null);
            Arrays.fill(mLines, 0, mScreenFirstRow, null);
        } else {
            Arrays.fill(mLines, mScreenFirstRow - mActiveTranscriptRows, mScreenFirstRow, null);
        }
        mActiveTranscriptRows = 0;
        markAllDirty();
    }

}
