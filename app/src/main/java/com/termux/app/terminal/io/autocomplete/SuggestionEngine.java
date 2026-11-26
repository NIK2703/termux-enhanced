package com.termux.app.terminal.io.autocomplete;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Pure, allocation-light candidate search over a {@link HistorySnapshot}.
 *
 * <p>Two tiers, in this order:
 * <ol>
 *   <li>full history entries whose <b>first line</b> starts with the typed line
 *       prefix (the text after the last newline);</li>
 *   <li>individual sentences (line segments ending at a newline or at an ordinary
 *       sentence terminator) that start with the typed active sentence, only in the
 *       slots the full entries left free.</li>
 * </ol>
 *
 * <p>All matching is a strict case-insensitive prefix. An exact match (no remaining
 * text) is never a candidate. The first sentence of the first line is never offered
 * as a separate sentence: when it matches it is either the full-entry candidate
 * itself or an exact match with nothing left to insert.
 *
 * <p>The scan is resumable: {@link ScanPos} points at the next unexamined position in
 * the canonical order, so a top-up after a filter never re-scans the whole history.
 */
final class SuggestionEngine {

    enum Kind { FULL, SENTENCE }

    /** One suggestion: the text to insert plus the history window it matched. */
    static final class Candidate {
        @NonNull final String text;
        @NonNull final Kind kind;
        final int entryIndex;
        /** Stable id of the source record; 0 in string-only tests. */
        final long entryId;
        /** Full entry = -1, sentence index otherwise. */
        final int sentenceIndex;
        /** Inclusive start of the matched window in the history entry. */
        final int windowStart;
        /** Exclusive end of the matched window. */
        final int windowEnd;

        Candidate(@NonNull String text, @NonNull Kind kind, int entryIndex, long entryId,
                int sentenceIndex, int windowStart, int windowEnd) {
            this.text = text;
            this.kind = kind;
            this.entryIndex = entryIndex;
            this.entryId = entryId;
            this.sentenceIndex = sentenceIndex;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
        }

        /** Bit in the record's dismissal set: 0 full, {@code sentence + 1} sentence. */
        int dismissalBit() {
            return sentenceIndex < 0 ? 0 : sentenceIndex + 1;
        }
    }

    /** Cursor into the canonical scan order: tier 0 full entries, tier 1 sentences. */
    static final class ScanPos {
        int tier;
        int entry;
        int sentence;
        boolean atEnd;

        void reset() {
            tier = 0;
            entry = 0;
            sentence = 0;
            atEnd = false;
        }
    }

    private SuggestionEngine() {}

    /**
     * Append up to {@code maxCount} candidates from both tiers. The full-entry tier
     * matches the line prefix; the sentence tier matches the active sentence.
     * {@code seen} holds case-folded keys shared by both tiers.
     */
    static void scan(@NonNull HistorySnapshot snap, @NonNull CharSequence text,
            int lineStart, int lineLen, int sentenceStart, int sentenceLen,
            int maxCount, @NonNull ScanPos pos, @NonNull List<Candidate> out,
            @NonNull Set<String> seen) {
        scan(snap, text, lineStart, lineLen, sentenceStart, sentenceLen, true,
                maxCount, pos, out, seen);
    }

    /**
     * Scan with an explicit sentence-tier switch. With {@code includeSentences = false},
     * tier 1 is the whole scan and {@link ScanPos#atEnd} is set when it finishes.
     */
    static void scan(@NonNull HistorySnapshot snap, @NonNull CharSequence text,
            int lineStart, int lineLen, int sentenceStart, int sentenceLen,
            boolean includeSentences, int maxCount, @NonNull ScanPos pos,
            @NonNull List<Candidate> out, @NonNull Set<String> seen) {
        if (maxCount <= 0 || out.size() >= maxCount) {
            return;
        }
        if (!includeSentences) {
            scanFullOnly(snap, text, lineStart, lineLen, maxCount, pos, out, seen);
            return;
        }

        // Fold the line prefix once. The active sentence is always a suffix of the
        // active line prefix, so the sentence tier can reuse the same folded array
        // with an offset instead of folding (and allocating) the sentence again.
        final char[] foldedLine = fold(text, lineStart, lineLen);

        // Tier 1: whole entries matched by their first line.
        if (pos.tier == 0) {
            final String[] entries = snap.entries;
            for (int e = pos.entry; e < entries.length; e++) {
                final int windowEnd = snap.firstLineEnd(e);
                if (snap.isFullDismissed(e)) continue;
                if (matchesFull(snap, e, foldedLine, lineLen)) {
                    // Dedup keys are shared by both tiers.
                    if (!seen.add(new String(snap.fold(e)))) continue;
                    out.add(new Candidate(entries[e], Kind.FULL, e, snap.entryId(e), -1,
                            0, windowEnd));
                    if (out.size() >= maxCount) {
                        pos.entry = e + 1;
                        pos.sentence = 0;
                        pos.atEnd = false;
                        return;
                    }
                }
            }
            pos.tier = 1;
            pos.entry = 0;
            pos.sentence = 0;
        }

        // Reuse the folded line when the sentence is a suffix of it.
        final int sentenceOffset = sentenceStart - lineStart;
        final char[] foldedSentence;
        final int foldedSentenceStart;
        if (sentenceOffset >= 0 && sentenceOffset + sentenceLen <= lineLen) {
            foldedSentence = foldedLine;
            foldedSentenceStart = sentenceOffset;
        } else {
            foldedSentence = fold(text, sentenceStart, sentenceLen);
            foldedSentenceStart = 0;
        }

        // Tier 2: individual sentences. The first sentence of a shown full entry is
        // skipped so it does not duplicate the full candidate.
        final String[] entries = snap.entries;
        // Full candidates form a prefix of out; count them instead of allocating a flag array.
        int fullCount = 0;
        while (fullCount < out.size() && out.get(fullCount).kind == Kind.FULL) {
            fullCount++;
        }
        for (int e = pos.entry; e < entries.length; e++) {
            final HistorySnapshot.SentenceSpans sentences = snap.sentences(e);
            final int sentenceCount = sentences.count;
            final int startSentence = (e == pos.entry) ? pos.sentence : 0;
            boolean fullShown = false;
            for (int i = 0; i < fullCount; i++) {
                if (out.get(i).entryIndex == e) {
                    fullShown = true;
                    break;
                }
            }
            for (int s = startSentence; s < sentenceCount; s++) {
                if (s == 0 && fullShown) continue;
                if (snap.isSentenceDismissed(e, s)) continue;
                final int windowStart = sentences.starts[s];
                final int windowEnd = sentences.ends[s];
                final int windowLen = windowEnd - windowStart;
                if (sentenceLen <= 0 || windowLen < sentenceLen
                        || !foldedMatches(snap.fold(e), windowStart,
                                foldedSentence, foldedSentenceStart, sentenceLen)) {
                    continue;
                }
                // An exact match has no remainder, so it is not a suggestion.
                if (windowLen <= sentenceLen) continue;
                if (!seen.add(new String(snap.fold(e), windowStart, windowLen))) continue;
                final String sentence = entries[e].substring(windowStart, windowEnd);
                out.add(new Candidate(sentence, Kind.SENTENCE, e, snap.entryId(e), s,
                        windowStart, windowEnd));
                if (out.size() >= maxCount) {
                    pos.entry = e;
                    pos.sentence = s + 1;
                    pos.atEnd = false;
                    return;
                }
            }
        }
        pos.atEnd = true;
    }

    /**
     * Scan only the full-entry tier. This is the whole scan while sentence
     * suggestions are disabled: no sentence span is parsed or touched, and the typed
     * line prefix is folded once.
     */
    static void scanFullOnly(@NonNull HistorySnapshot snap, @NonNull CharSequence text,
            int lineStart, int lineLen, int maxCount, @NonNull ScanPos pos,
            @NonNull List<Candidate> out, @NonNull Set<String> seen) {
        if (pos.tier != 0) {
            pos.atEnd = true;
            return;
        }
        if (maxCount <= 0 || out.size() >= maxCount) {
            return;
        }

        final char[] foldedLine = fold(text, lineStart, lineLen);
        final String[] entries = snap.entries;
        for (int e = pos.entry; e < entries.length; e++) {
            final int windowEnd = snap.firstLineEnd(e);
            if (snap.isFullDismissed(e)) continue;
            if (matchesFull(snap, e, foldedLine, lineLen)) {
                if (!seen.add(new String(snap.fold(e)))) continue;
                out.add(new Candidate(entries[e], Kind.FULL, e, snap.entryId(e), -1,
                        0, windowEnd));
                if (out.size() >= maxCount) {
                    pos.entry = e + 1;
                    pos.sentence = 0;
                    pos.atEnd = false;
                    return;
                }
            }
        }
        pos.tier = 1;
        pos.entry = entries.length;
        pos.sentence = 0;
        pos.atEnd = true;
    }

    /**
     * Path B: drop candidates whose next character (at the old typed length into
     * their window) does not match {@code newChar}, and candidates whose window is
     * now exactly the typed prefix (an exact match has no remainder). The full-entry
     * tier advances along the line prefix; the sentence tier along the active
     * sentence. Zero allocations.
     */
    static void filter(@NonNull List<Candidate> candidates, @NonNull HistorySnapshot snap,
            int oldLineLen, int oldSentenceLen, char newChar) {
        final char foldedNew = Character.toLowerCase(newChar);
        for (int i = candidates.size() - 1; i >= 0; i--) {
            final Candidate c = candidates.get(i);
            final int oldLen = (c.kind == Kind.FULL) ? oldLineLen : oldSentenceLen;
            if (c.windowEnd - c.windowStart <= oldLen + 1) {
                candidates.remove(i);
                continue;
            }
            final int at = c.windowStart + oldLen;
            if (snap.fold(c.entryIndex)[at] != foldedNew) {
                candidates.remove(i);
            }
        }
    }

    /** True when the entry's first line starts with the folded typed line prefix. */
    static boolean matchesFull(@NonNull HistorySnapshot snap, int entryIndex,
            @NonNull char[] foldedLine, int lineLen) {
        final int windowEnd = snap.firstLineEnd(entryIndex);
        return lineLen > 0 && windowEnd > lineLen
                && foldedMatches(snap.fold(entryIndex), 0, foldedLine, 0, lineLen);
    }

    /** Fold a typed prefix once so the hot comparisons are plain char reads. */
    static char[] fold(@NonNull CharSequence text, int start, int len) {
        final char[] out = new char[len];
        for (int i = 0; i < len; i++) out[i] = Character.toLowerCase(text.charAt(start + i));
        return out;
    }

    /** Case-folded dedup key using the same per-char fold as matching. */
    @NonNull
    static String foldedKey(@NonNull CharSequence text) {
        final int len = text.length();
        final char[] folded = new char[len];
        for (int i = 0; i < len; i++) folded[i] = Character.toLowerCase(text.charAt(i));
        return new String(folded);
    }

    /** True when the pre-folded history window starts with the pre-folded typed prefix. */
    static boolean foldedMatches(@NonNull char[] foldedEntry, int entryStart,
            @NonNull char[] foldedTyped, int typedStart, int len) {
        if (len < 0 || entryStart < 0 || typedStart < 0
                || entryStart + len > foldedEntry.length
                || typedStart + len > foldedTyped.length) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (foldedEntry[entryStart + i] != foldedTyped[typedStart + i]) return false;
        }
        return true;
    }

    /** True when a pre-folded history window equals an already folded string. */
    static boolean foldedEquals(@NonNull char[] foldedEntry, int start, int len,
            @NonNull String foldedText) {
        if (start < 0 || len < 0 || start + len > foldedEntry.length
                || foldedText.length() != len) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (foldedEntry[start + i] != foldedText.charAt(i)) return false;
        }
        return true;
    }

    /** Start of the line containing {@code caret} (the text after the last newline). */
    static int activeLineStart(@NonNull CharSequence text, int caret) {
        return HistorySyntax.activeLineStart(text, caret);
    }

    /**
     * Start of the active sentence containing {@code caret}, using the same
     * whitespace and boundary rules as {@link HistorySnapshot#parseSpans}.
     */
    static int activeSentenceStart(@NonNull CharSequence text, int caret) {
        return HistorySyntax.activeSentenceStart(text, caret);
    }

    /** Zero-based index of the active sentence on its line. */
    static int activeSentenceIndex(@NonNull CharSequence text, int lineStart, int sentenceStart) {
        return HistorySyntax.activeSentenceIndex(text, lineStart, sentenceStart);
    }
}
