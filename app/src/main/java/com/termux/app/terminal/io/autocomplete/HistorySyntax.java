package com.termux.app.terminal.io.autocomplete;

import androidx.annotation.NonNull;

/**
 * One definition of whitespace, sentence boundaries and sentence windows for the
 * history snapshot, the suggestion engine and insertion.
 *
 * <p>A sentence is a line segment ending at a newline or at an ordinary terminator
 * ({@code .}, {@code !}, {@code ?}, {@code …}) followed by whitespace or end of
 * line. A terminator followed by non-whitespace (the dot in {@code ./}, {@code .git}
 * or {@code 1.2}) is not a boundary. Windows are trimmed at both ends; leading
 * indentation and spaces before a newline are not part of the completion text.
 */
final class HistorySyntax {

    private HistorySyntax() {
    }

    static boolean isWhitespace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    static boolean isSentenceEnd(char c) {
        return c == '.' || c == '!' || c == '?' || c == '…';
    }

    /** Start of the line containing {@code caret} (the text after the last newline). */
    static int activeLineStart(@NonNull CharSequence text, int caret) {
        int end = clampCaret(text, caret);
        for (int i = end - 1; i >= 0; i--) {
            if (text.charAt(i) == '\n') return i + 1;
        }
        return 0;
    }

    /**
     * Start of the active sentence containing {@code caret}. Leading whitespace
     * after the line start or after a sentence boundary is not part of the window,
     * so completion leaves the user's indentation in place.
     */
    static int activeSentenceStart(@NonNull CharSequence text, int caret) {
        int end = clampCaret(text, caret);
        int lineStart = activeLineStart(text, end);
        int sentenceStart = lineStart;
        while (sentenceStart < end && isWhitespace(text.charAt(sentenceStart))) {
            sentenceStart++;
        }
        int i = sentenceStart;
        while (i < end) {
            char c = text.charAt(i);
            if (isSentenceEnd(c)
                    && (i + 1 == end || isWhitespace(text.charAt(i + 1)))) {
                int j = i + 1;
                while (j < end && isWhitespace(text.charAt(j))) j++;
                sentenceStart = j;
                i = j;
            } else {
                i++;
            }
        }
        return sentenceStart;
    }

    private static int clampCaret(@NonNull CharSequence text, int caret) {
        if (caret < 0) return 0;
        if (caret > text.length()) return text.length();
        return caret;
    }

    /** Exclusive end of the sentence starting at {@code start}. */
    static int findSentenceEnd(@NonNull String entry, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = entry.charAt(i);
            if (isSentenceEnd(c) && (i + 1 == end || isWhitespace(entry.charAt(i + 1)))) {
                return i + 1;
            }
        }
        return end;
    }

    /** Parse line and trimmed sentence offsets for one entry. */
    @NonNull
    static HistorySnapshot.Spans parseSpans(@NonNull String entry) {
        int lines = 1;
        for (int j = 0; j < entry.length(); j++) {
            if (entry.charAt(j) == '\n') lines++;
        }
        int[] starts = new int[lines];
        starts[0] = 0;
        int line = 1;
        for (int j = 0; j < entry.length(); j++) {
            if (entry.charAt(j) == '\n') starts[line++] = j + 1;
        }

        int count = 0;
        for (int l = 0; l < lines; l++) {
            count += countSentences(entry, starts[l], lineEnd(entry, starts, l));
        }
        int[] sStarts = new int[count];
        int[] sEnds = new int[count];
        int si = 0;
        for (int l = 0; l < lines; l++) {
            int lineStart = starts[l];
            int lineEnd = lineEnd(entry, starts, l);
            int pos = lineStart;
            while (pos < lineEnd) {
                int sentEnd = findSentenceEnd(entry, pos, lineEnd);
                int sentStart = pos;
                while (sentStart < sentEnd && isWhitespace(entry.charAt(sentStart))) {
                    sentStart++;
                }
                int trimmedEnd = sentEnd;
                while (trimmedEnd > sentStart && isWhitespace(entry.charAt(trimmedEnd - 1))) {
                    trimmedEnd--;
                }
                sStarts[si] = sentStart;
                sEnds[si] = trimmedEnd;
                si++;
                if (sentEnd >= lineEnd) break;
                int next = sentEnd;
                while (next < lineEnd && isWhitespace(entry.charAt(next))) next++;
                pos = next;
            }
        }
        char[] fold = new char[entry.length()];
        for (int i = 0; i < entry.length(); i++) {
            fold[i] = Character.toLowerCase(entry.charAt(i));
        }
        int firstLineEnd = lines > 1 ? starts[1] - 1 : entry.length();
        return new HistorySnapshot.Spans(starts, sStarts, sEnds, count, firstLineEnd, fold);
    }

    private static int lineEnd(String entry, int[] starts, int line) {
        if (line + 1 < starts.length) return starts[line + 1] - 1;
        return entry.length();
    }

    private static int countSentences(String entry, int start, int end) {
        int count = 0;
        int pos = start;
        while (pos < end) {
            int sentEnd = findSentenceEnd(entry, pos, end);
            count++;
            if (sentEnd >= end) break;
            int next = sentEnd;
            while (next < end && isWhitespace(entry.charAt(next))) next++;
            pos = next;
        }
        return count;
    }
}
