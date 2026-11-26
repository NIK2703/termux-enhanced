package com.termux.app.terminal.io.autocomplete;

import androidx.annotation.NonNull;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only per-history-version view of the message history: the entries themselves
 * (no copies) plus the offsets needed to address a first line or a single sentence
 * without splitting the entry on every keystroke. The first-line offset and the folded
 * copy are parsed once; sentence offsets are materialized lazily and only while
 * sentence suggestions are enabled. Entry text and offsets are immutable; the
 * dismissal bits are live and owned by {@link SuggestionDismissals}.
 *
 * <p>A <b>sentence</b> is a line segment that ends at a newline or at an ordinary
 * sentence terminator ({@code .}, {@code !}, {@code ?}, {@code …}) followed by
 * whitespace. A sentence window is indexed without leading or trailing whitespace,
 * so a candidate never carries the indentation of a line or the spaces before a
 * newline. A terminator followed by a non-whitespace character (for example the dot
 * in {@code ./}, {@code .git} or {@code 1.2}) is not a boundary.
 *
 * <p>The full-entry offsets are parsed once per entry (see {@link #parseSpans}) and
 * cached by {@link MessageHistoryController}; sentence offsets are parsed on first
 * use and cached inside the same {@link Spans}. {@link #build(int, List, Map)} only
 * references the cached spans, so a snapshot rebuild is O(N) and no entry is parsed
 * twice.
 */
final class HistorySnapshot {

    final int version;
    @NonNull final String[] entries;
    @NonNull final Spans[] spans;
    /** Stable record ids, aligned with {@link #entries}; 0 for the string-only test builder. */
    @NonNull final long[] ids;
    /**
     * Dismissal bits aligned with {@link #entries}. The objects are owned by
     * {@link SuggestionDismissals} and shared with it, so a mark made after this
     * snapshot was built is visible here without a rebuild. Null entries mean the
     * string-only builder or a record with no flags.
     */
    @NonNull final BitSet[] dismissed;
    final int sentenceModel;

    /**
     * Offsets of one history entry. The full-entry tier only needs {@link #firstLineEnd}
     * and {@link #fold}; sentence arrays are materialized on first use and published
     * through a volatile field so a background history write cannot race the first read.
     */
    static final class Spans {
        /** Exclusive end of the first line; cached because the full-tier scan reads it per entry. */
        final int firstLineEnd;
        /**
         * Case-folded entry ({@code Character.toLowerCase} per char), parsed once.
         * The hot scan compares it with a single {@code ==} instead of per-char
         * {@code toLowerCase}/{@code toUpperCase}.
         */
        @NonNull final char[] fold;
        /** Lazily parsed sentence offsets, or null while only full-entry data is needed. */
        private volatile SentenceSpans mSentences;

        Spans(int firstLineEnd, @NonNull char[] fold) {
            this.firstLineEnd = firstLineEnd;
            this.fold = fold;
        }

        @NonNull
        SentenceSpans sentences(@NonNull String entry) {
            SentenceSpans sentences = mSentences;
            if (sentences == null) {
                synchronized (this) {
                    sentences = mSentences;
                    if (sentences == null) {
                        sentences = HistorySyntax.parseSentences(entry);
                        mSentences = sentences;
                    }
                }
            }
            return sentences;
        }

        boolean hasSentences() {
            return mSentences != null;
        }
    }

    /** Trimmed sentence windows of one entry. The arrays are read-only after construction. */
    static final class SentenceSpans {
        @NonNull final int[] starts;
        @NonNull final int[] ends;
        final int count;

        SentenceSpans(@NonNull int[] starts, @NonNull int[] ends, int count) {
            this.starts = starts;
            this.ends = ends;
            this.count = count;
        }
    }

    private HistorySnapshot(int version, @NonNull String[] entries, @NonNull Spans[] spans,
            @NonNull long[] ids, @NonNull BitSet[] dismissed, int sentenceModel) {
        this.version = version;
        this.entries = entries;
        this.spans = spans;
        this.ids = ids;
        this.dismissed = dismissed;
        this.sentenceModel = sentenceModel;
    }

    /** Build a snapshot, parsing every entry (used by tests and one-off consumers). */
    @NonNull
    static HistorySnapshot build(int version, @NonNull List<String> history) {
        return build(version, history, new HashMap<String, Spans>());
    }

    /**
     * Build a snapshot against a cache of parsed entries. Missing entries get their
     * full-entry offsets parsed once and stored in the cache; entries already present
     * are referenced directly. Sentence offsets stay lazy. The cache is keyed by the
     * entry text, which fully determines the offsets.
     */
    @NonNull
    static HistorySnapshot build(int version, @NonNull List<String> history,
            @NonNull Map<String, Spans> cache) {
        final int n = history.size();
        String[] entries = new String[n];
        Spans[] spans = new Spans[n];
        long[] ids = new long[n];
        BitSet[] dismissed = new BitSet[n];
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
        return new HistorySnapshot(version, entries, spans, ids, dismissed,
                SuggestionDismissals.SENTENCE_MODEL);
    }

    /**
     * Build a snapshot over records, attaching their stable ids and live dismissal
     * bits. The {@link BitSet} instances are the canonical ones from
     * {@code dismissals}, not copies.
     */
    @NonNull
    static HistorySnapshot build(int version, @NonNull List<HistoryEntry> history,
            @NonNull Map<String, Spans> cache, @NonNull SuggestionDismissals dismissals) {
        final int n = history.size();
        String[] entries = new String[n];
        Spans[] spans = new Spans[n];
        long[] ids = new long[n];
        BitSet[] dismissed = new BitSet[n];
        for (int i = 0; i < n; i++) {
            HistoryEntry entry = history.get(i);
            String text = entry.text;
            entries[i] = text;
            ids[i] = entry.id;
            dismissed[i] = dismissals.bitsFor(entry.id);
            Spans s = cache.get(text);
            if (s == null) {
                s = parseSpans(text);
                cache.put(text, s);
            }
            spans[i] = s;
        }
        return new HistorySnapshot(version, entries, spans, ids, dismissed,
                SuggestionDismissals.SENTENCE_MODEL);
    }

    /** Whether the full-entry suggestion for {@code entry} is hidden. */
    boolean isFullDismissed(int entry) {
        BitSet bits = dismissed[entry];
        return bits != null && bits.get(0);
    }

    /**
     * Whether sentence {@code sentence} of {@code entry} is hidden. A full-entry
     * dismissal covers every window of that record, so deleting the whole command
     * cannot leave one of its sentences behind as a separate suggestion.
     */
    boolean isSentenceDismissed(int entry, int sentence) {
        if (sentence < 0) return false;
        BitSet bits = dismissed[entry];
        return bits != null && (bits.get(0) || bits.get(sentence + 1));
    }

    long entryId(int entry) {
        return ids[entry];
    }

    /** Parse one entry's full-entry offsets; sentence offsets stay lazy. */
    @NonNull
    static Spans parseSpans(@NonNull String entry) {
        return HistorySyntax.parseFullSpans(entry);
    }

    /** Parse one entry's sentence offsets (used by tests and the lazy span). */
    @NonNull
    static SentenceSpans parseSentences(@NonNull String entry) {
        return HistorySyntax.parseSentences(entry);
    }

    /** Exclusive end of the first line, i.e. the window used by a full-entry candidate. */
    int firstLineEnd(int entry) {
        return spans[entry].firstLineEnd;
    }

    /** Lazily parsed sentence offsets for {@code entry}. */
    @NonNull
    SentenceSpans sentences(int entry) {
        return spans[entry].sentences(entries[entry]);
    }

    int sentenceCount(int entry) {
        return sentences(entry).count;
    }

    int sentenceStart(int entry, int sentence) {
        return sentences(entry).starts[sentence];
    }

    int sentenceEnd(int entry, int sentence) {
        return sentences(entry).ends[sentence];
    }

    /** Case-folded entry text, used by the hot scan. */
    @NonNull
    char[] fold(int entry) {
        return spans[entry].fold;
    }

    /** Ordinary sentence terminators. */
    static boolean isSentenceEnd(char c) {
        return HistorySyntax.isSentenceEnd(c);
    }

    static boolean isBoundaryWhitespace(char c) {
        return HistorySyntax.isWhitespace(c);
    }
}
