package com.termux.app.terminal.io.autocomplete;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable per-history-version view of the message history: the entries themselves
 * (no copies) plus the line/sentence offsets needed to address a single sentence
 * without splitting the entry on every keystroke.
 *
 * <p>A <b>sentence</b> is a line segment that ends at a newline or at an ordinary
 * sentence terminator ({@code .}, {@code !}, {@code ?}, {@code …}) followed by
 * whitespace. The first sentence of a line keeps the line's leading whitespace;
 * later sentences start at the first non-whitespace character after the terminator.
 * A terminator followed by a non-whitespace character (for example the dot in
 * {@code ./}, {@code .git} or {@code 1.2}) is not a boundary.
 *
 * <p>The offsets are parsed once per entry (see {@link #parseSpans}) and cached by
 * {@link MessageHistoryController}; {@link #build(int, List, Map)} only references
 * the cached {@link Spans}, so a snapshot rebuild is O(N) and no entry is parsed
 * twice.
 */
final class HistorySnapshot {

    final int version;
    @NonNull final String[] entries;
    @NonNull final Spans[] spans;

    /**
     * Line/sentence offsets of one history entry. The arrays are owned by the
     * snapshot/cache and must be treated as read-only.
     */
    static final class Spans {
        /** {@code lineStarts[line]} is the index of the first character of the line. */
        @NonNull final int[] lineStarts;
        @NonNull final int[] sentenceStarts;
        @NonNull final int[] sentenceEnds;
        final int sentenceCount;
        /**
         * Case-folded entry ({@code Character.toLowerCase} per char), parsed once.
         * The hot scan compares it with a single {@code ==} instead of per-char
         * {@code toLowerCase}/{@code toUpperCase}.
         */
        @NonNull final char[] fold;

        Spans(@NonNull int[] lineStarts, @NonNull int[] sentenceStarts,
                @NonNull int[] sentenceEnds, int sentenceCount, @NonNull char[] fold) {
            this.lineStarts = lineStarts;
            this.sentenceStarts = sentenceStarts;
            this.sentenceEnds = sentenceEnds;
            this.sentenceCount = sentenceCount;
            this.fold = fold;
        }
    }

    private HistorySnapshot(int version, @NonNull String[] entries, @NonNull Spans[] spans) {
        this.version = version;
        this.entries = entries;
        this.spans = spans;
    }

    /** Build a snapshot, parsing every entry (used by tests and one-off consumers). */
    @NonNull
    static HistorySnapshot build(int version, @NonNull List<String> history) {
        return build(version, history, new HashMap<String, Spans>());
    }

    /**
     * Build a snapshot against a cache of parsed entries. Missing entries are
     * parsed once and stored in the cache; entries already present are referenced
     * directly. The cache is keyed by the entry text, which fully determines the
     * offsets.
     */
    @NonNull
    static HistorySnapshot build(int version, @NonNull List<String> history,
            @NonNull Map<String, Spans> cache) {
        final int n = history.size();
        String[] entries = new String[n];
        Spans[] spans = new Spans[n];
        for (int i = 0; i < n; i++) {
            String entry = history.get(i);
            if (entry == null) entry = "";
            entries[i] = entry;
            Spans s = cache.get(entry);
            if (s == null) {
                s = parseSpans(entry);
                cache.put(entry, s);
            }
            spans[i] = s;
        }
        return new HistorySnapshot(version, entries, spans);
    }

    /** Parse one entry's line/sentence offsets. */
    @NonNull
    static Spans parseSpans(@NonNull String entry) {
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
                sStarts[si] = pos;
                sEnds[si] = sentEnd;
                si++;
                if (sentEnd >= lineEnd) break;
                int next = sentEnd;
                while (next < lineEnd && isBoundaryWhitespace(entry.charAt(next))) next++;
                pos = next;
            }
        }
        char[] fold = new char[entry.length()];
        for (int i = 0; i < entry.length(); i++) {
            fold[i] = Character.toLowerCase(entry.charAt(i));
        }
        return new Spans(starts, sStarts, sEnds, count, fold);
    }

    /** Exclusive end of the first line, i.e. the window used by a full-entry candidate. */
    int firstLineEnd(int entry) {
        return lineEnd(entries[entry], spans[entry].lineStarts, 0);
    }

    /** Exclusive end of {@code line}, without the trailing newline. */
    private static int lineEnd(String entry, int[] starts, int line) {
        if (line + 1 < starts.length) return starts[line + 1] - 1;
        return entry.length();
    }

    int sentenceCount(int entry) {
        return spans[entry].sentenceCount;
    }

    int sentenceStart(int entry, int sentence) {
        return spans[entry].sentenceStarts[sentence];
    }

    int sentenceEnd(int entry, int sentence) {
        return spans[entry].sentenceEnds[sentence];
    }

    /** Case-folded entry text, used by the hot scan. */
    @NonNull
    char[] fold(int entry) {
        return spans[entry].fold;
    }

    private static int countSentences(String entry, int start, int end) {
        int count = 0;
        int pos = start;
        while (pos < end) {
            int sentEnd = findSentenceEnd(entry, pos, end);
            count++;
            if (sentEnd >= end) break;
            int next = sentEnd;
            while (next < end && isBoundaryWhitespace(entry.charAt(next))) next++;
            pos = next;
        }
        return count;
    }

    /**
     * End of the sentence that starts at {@code start}: the index just after the
     * terminator, or {@code end} when the sentence has no terminator.
     */
    private static int findSentenceEnd(String entry, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = entry.charAt(i);
            if (isSentenceEnd(c)
                    && (i + 1 == end || isBoundaryWhitespace(entry.charAt(i + 1)))) {
                return i + 1;
            }
        }
        return end;
    }

    /** Ordinary sentence terminators. */
    static boolean isSentenceEnd(char c) {
        return c == '.' || c == '!' || c == '?' || c == '…';
    }

    static boolean isBoundaryWhitespace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
