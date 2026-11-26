package com.termux.app.terminal.io.autocomplete;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure-JVM tests for the two-tier candidate search: tier order, sentence windows,
 * dedup, strict prefix matching, resumable scans and the additive filter.
 */
public class SuggestionEngineTest {

    /** Real history entry used for the reported full-entry-then-new-sentence case. */
    private static final String HISTORY_ENTRY_2648 =
            "исправь отработку мини-карты: при проведении по миникарте пальцем нормально "
                    + "откликается только зона примерно на уровне нижней части 50-60% миникарты "
                    + "и когда ведёшь палец вверх для перемещения камеры на верхнюю часть "
                    + "минимума она упирается в невидимый барьер примерно на 60% а когда ты "
                    + "тапаешь и водишь пальцем с верхней части миникарты  там уже работают "
                    + "жесты как для области игрового мира. исследуй логи , текущую реализацию "
                    + "и выясни в чем проблема";

    private static HistorySnapshot snap(String... entries) {
        return HistorySnapshot.build(1, Arrays.asList(entries));
    }

    private static List<SuggestionEngine.Candidate> scan(
            HistorySnapshot snapshot, String typed, int max) {
        return scan(snapshot, typed, max, new SuggestionEngine.ScanPos(), new HashSet<String>());
    }

    private static List<SuggestionEngine.Candidate> scan(
            HistorySnapshot snapshot, String typed, int max,
            SuggestionEngine.ScanPos pos, Set<String> seen) {
        List<SuggestionEngine.Candidate> out = new ArrayList<>();
        int caret = typed.length();
        int lineStart = SuggestionEngine.activeLineStart(typed, caret);
        int sentenceStart = SuggestionEngine.activeSentenceStart(typed, caret);
        SuggestionEngine.scan(snapshot, typed, lineStart, caret - lineStart,
                sentenceStart, caret - sentenceStart, max, pos, out, seen);
        return out;
    }

    @Test
    public void disabledSentenceTierStillOffersFullEntries() {
        HistorySnapshot snapshot = snap("git one", "x\ngit two");
        List<SuggestionEngine.Candidate> out = new ArrayList<>();
        SuggestionEngine.ScanPos pos = new SuggestionEngine.ScanPos();
        SuggestionEngine.scan(snapshot, "git", 0, 3, 0, 3, false, 4,
                pos, out, new HashSet<String>());

        assertEquals(1, out.size());
        assertEquals("git one", out.get(0).text);
        assertEquals(SuggestionEngine.Kind.FULL, out.get(0).kind);
        assertTrue(pos.atEnd);
        assertFalse("full-only scan must not parse sentence spans",
                snapshot.spans[0].hasSentences());
        assertFalse("full-only scan must not parse sentence spans",
                snapshot.spans[1].hasSentences());
    }

    @Test
    public void disabledSentenceTierScanResumesFromSavedPosition() {
        HistorySnapshot snapshot = snap("aa1", "aa2", "aa3");
        SuggestionEngine.ScanPos pos = new SuggestionEngine.ScanPos();
        Set<String> seen = new HashSet<>();
        List<SuggestionEngine.Candidate> out = new ArrayList<>();

        SuggestionEngine.scan(snapshot, "aa", 0, 2, 0, 2, false, 1, pos, out, seen);
        assertEquals(1, out.size());
        assertEquals("aa1", out.get(0).text);
        assertFalse(pos.atEnd);

        SuggestionEngine.scan(snapshot, "aa", 0, 2, 0, 2, false, 2, pos, out, seen);
        assertEquals(2, out.size());
        assertEquals("aa2", out.get(1).text);
        assertFalse(pos.atEnd);

        SuggestionEngine.scan(snapshot, "aa", 0, 2, 0, 2, false, 4, pos, out, seen);
        assertEquals(3, out.size());
        assertEquals("aa3", out.get(2).text);
        assertTrue(pos.atEnd);
    }

    @Test
    public void disabledSentenceTierDoesNotOfferSentenceOnlyMatches() {
        HistorySnapshot snapshot = snap("x\ngit two");
        List<SuggestionEngine.Candidate> out = new ArrayList<>();
        SuggestionEngine.ScanPos pos = new SuggestionEngine.ScanPos();
        SuggestionEngine.scan(snapshot, "git", 0, 3, 0, 3, false, 4,
                pos, out, new HashSet<String>());

        assertTrue(out.isEmpty());
        assertTrue(pos.atEnd);
        assertFalse(snapshot.spans[0].hasSentences());
    }

    @Test
    public void fullEntryMatchesFirstLine() {
        List<SuggestionEngine.Candidate> out =
                scan(snap("git commit -m", "git status"), "git c", 4);
        assertEquals(1, out.size());
        assertEquals("git commit -m", out.get(0).text);
        assertEquals(SuggestionEngine.Kind.FULL, out.get(0).kind);
    }

    @Test
    public void sentenceAfterFirstLineMatches() {
        List<SuggestionEngine.Candidate> out =
                scan(snap("first line\nsecond command"), "sec", 4);
        assertEquals(1, out.size());
        assertEquals("second command", out.get(0).text);
        assertEquals(SuggestionEngine.Kind.SENTENCE, out.get(0).kind);
    }

    @Test
    public void fullEntriesFillSlotsBeforeSentences() {
        List<SuggestionEngine.Candidate> out =
                scan(snap("git one", "git two\nx\ngit three"), "git", 3);
        assertEquals(3, out.size());
        assertEquals("git one", out.get(0).text);
        assertEquals(SuggestionEngine.Kind.FULL, out.get(0).kind);
        assertEquals("git two\nx\ngit three", out.get(1).text);
        assertEquals(SuggestionEngine.Kind.FULL, out.get(1).kind);
        assertEquals("git three", out.get(2).text);
        assertEquals(SuggestionEngine.Kind.SENTENCE, out.get(2).kind);
    }

    @Test
    public void firstSentenceIsNotOfferedWhenTypingASecondSentence() {
        List<SuggestionEngine.Candidate> out =
                scan(snap("First sentence. Second long sentence."),
                        "First sentence. Sec", 4);
        assertEquals(2, out.size());
        assertEquals("First sentence. Second long sentence.", out.get(0).text);
        assertEquals(SuggestionEngine.Kind.FULL, out.get(0).kind);
        assertEquals("Second long sentence.", out.get(1).text);
        assertEquals(SuggestionEngine.Kind.SENTENCE, out.get(1).kind);
    }

    @Test
    public void firstLineIsNotRepeatedAsSentence() {
        List<SuggestionEngine.Candidate> out = scan(snap("git commit"), "git", 4);
        assertEquals(1, out.size());
        assertEquals("git commit", out.get(0).text);
        assertEquals(SuggestionEngine.Kind.FULL, out.get(0).kind);
    }

    @Test
    public void duplicateSentenceUsesNewestOccurrence() {
        List<SuggestionEngine.Candidate> out =
                scan(snap("a\nsame sentence", "b\nsame sentence"), "same", 4);
        assertEquals(1, out.size());
        assertEquals("same sentence", out.get(0).text);
        assertEquals(0, out.get(0).entryIndex);
    }

    @Test
    public void duplicateSentenceIsCaseInsensitiveAndUsesNewestOccurrence() {
        List<SuggestionEngine.Candidate> out =
                scan(snap("a\nSame sentence", "b\nsame sentence"), "same", 4);
        assertEquals(1, out.size());
        assertEquals("Same sentence", out.get(0).text);
        assertEquals(0, out.get(0).entryIndex);
    }

    @Test
    public void caseVariantFullEntriesUseNewestOccurrence() {
        List<SuggestionEngine.Candidate> out =
                scan(snap("Git status", "git status"), "git", 4);
        assertEquals(1, out.size());
        assertEquals("Git status", out.get(0).text);
        assertEquals(SuggestionEngine.Kind.FULL, out.get(0).kind);
        assertEquals(0, out.get(0).entryIndex);
    }

    @Test
    public void sentenceEqualToFullEntryIsDedupedCaseInsensitively() {
        List<SuggestionEngine.Candidate> out =
                scan(snap("Git status", "a\ngit status"), "git s", 4);
        assertEquals(1, out.size());
        assertEquals("Git status", out.get(0).text);
        assertEquals(SuggestionEngine.Kind.FULL, out.get(0).kind);
    }

    @Test
    public void preloadedFullKeySuppressesTheSameSentenceOnTopUp() {
        final Set<String> seen = new HashSet<>();
        seen.add(SuggestionEngine.foldedKey("git status"));
        final List<SuggestionEngine.Candidate> out =
                scan(snap("a\ngit status"), "git s", 4,
                        new SuggestionEngine.ScanPos(), seen);
        assertTrue(out.isEmpty());
    }

    @Test
    public void exactFullEntryIsExcluded() {
        assertTrue(scan(snap("git status"), "git status", 4).isEmpty());
    }

    @Test
    public void exactSentenceIsExcluded() {
        assertTrue(scan(snap("x\ngit status"), "git status", 4).isEmpty());
    }

    @Test
    public void matchingIsCaseInsensitive() {
        List<SuggestionEngine.Candidate> out = scan(snap("Git Status"), "GIT S", 4);
        assertEquals(1, out.size());
        assertEquals("Git Status", out.get(0).text);
    }

    @Test
    public void strictPrefixHasNoTrailingSlashRelaxation() {
        assertTrue(scan(snap("/usr"), "/usr/", 4).isEmpty());
        assertEquals(1, scan(snap("/usr/bin"), "/usr/", 4).size());
    }

    @Test
    public void additiveFilterDropsNonMatchingCandidates() {
        HistorySnapshot snapshot = snap("git status", "git stash");
        List<SuggestionEngine.Candidate> out = scan(snapshot, "git st", 4);
        assertEquals(2, out.size());

        // "git sta": both still match.
        SuggestionEngine.filter(out, snapshot, 6, 6, 'a');
        assertEquals(2, out.size());

        // "git stat": only "git status".
        SuggestionEngine.filter(out, snapshot, 7, 7, 't');
        assertEquals(1, out.size());
        assertEquals("git status", out.get(0).text);
    }

    @Test
    public void additiveFilterDropsCandidateWhenWindowIsExhausted() {
        HistorySnapshot snapshot = snap("gitx\nnext");
        List<SuggestionEngine.Candidate> out = scan(snapshot, "git", 4);
        assertEquals(1, out.size());

        SuggestionEngine.filter(out, snapshot, 3, 3, 'x');
        assertTrue(out.isEmpty());
    }

    @Test
    public void additiveFilterDropsExactMatchOnTheLastCharacter() {
        HistorySnapshot snapshot = snap("git status");
        List<SuggestionEngine.Candidate> out = scan(snapshot, "git statu", 4);
        assertEquals(1, out.size());

        // The new text is exactly the first line: no remainder, no suggestion.
        SuggestionEngine.filter(out, snapshot, 9, 9, 's');
        assertTrue(out.isEmpty());
    }

    @Test
    public void scanResumesFromSavedPosition() {
        HistorySnapshot snapshot = snap("aa1", "aa2", "aa3");
        SuggestionEngine.ScanPos pos = new SuggestionEngine.ScanPos();
        Set<String> seen = new HashSet<>();

        List<SuggestionEngine.Candidate> out = new ArrayList<>();
        SuggestionEngine.scan(snapshot, "aa", 0, 2, 0, 2, 1, pos, out, seen);
        assertEquals(1, out.size());
        assertEquals("aa1", out.get(0).text);
        assertFalse(pos.atEnd);

        SuggestionEngine.scan(snapshot, "aa", 0, 2, 0, 2, 2, pos, out, seen);
        assertEquals(2, out.size());
        assertEquals("aa2", out.get(1).text);
        assertFalse(pos.atEnd);

        // maxCount 3 would stop exactly on the last full entry, so tier 2 has not
        // been examined yet; ask for one more slot to reach the real end.
        SuggestionEngine.scan(snapshot, "aa", 0, 2, 0, 2, 4, pos, out, seen);
        assertEquals(3, out.size());
        assertEquals("aa3", out.get(2).text);
        assertTrue(pos.atEnd);
    }

    @Test
    public void sentenceTopUpDoesNotRepeatAnAlreadyShownSentence() {
        HistorySnapshot snapshot = snap("a\nfoo bar", "b\nfoo bar");
        SuggestionEngine.ScanPos pos = new SuggestionEngine.ScanPos();
        Set<String> seen = new HashSet<>();

        List<SuggestionEngine.Candidate> out = new ArrayList<>();
        SuggestionEngine.scan(snapshot, "foo", 0, 3, 0, 3, 1, pos, out, seen);
        assertEquals(1, out.size());
        assertEquals(0, out.get(0).entryIndex);
        assertFalse(pos.atEnd);

        SuggestionEngine.scan(snapshot, "foo", 0, 3, 0, 3, 2, pos, out, seen);
        assertEquals(1, out.size());
        assertTrue(pos.atEnd);
    }

    @Test
    public void sentenceWindowDoesNotCrossTheNewline() {
        List<SuggestionEngine.Candidate> out =
                scan(snap("line one\nline two\nline three"), "line t", 4);
        assertEquals(2, out.size());
        assertEquals("line two", out.get(0).text);
        assertEquals("line three", out.get(1).text);
    }

    @Test
    public void sentenceWindowDropsIndentationAfterNewline() {
        List<SuggestionEngine.Candidate> out =
                scan(snap("first\n    git status"), "git", 4);
        assertEquals(1, out.size());
        assertEquals("git status", out.get(0).text);
        assertEquals(SuggestionEngine.Kind.SENTENCE, out.get(0).kind);
        assertEquals(10, out.get(0).windowStart);
    }

    @Test
    public void sentenceWindowDropsSpacesBeforeNewline() {
        List<SuggestionEngine.Candidate> out =
                scan(snap("first\ngit status   \nnext"), "git", 4);
        assertEquals(1, out.size());
        assertEquals("git status", out.get(0).text);
        assertEquals("git status".length(), out.get(0).windowEnd - out.get(0).windowStart);
    }

    @Test
    public void whitespaceOnlyLineIsNotACandidate() {
        List<SuggestionEngine.Candidate> out =
                scan(snap("first\n   \nsecond command"), "sec", 4);
        assertEquals(1, out.size());
        assertEquals("second command", out.get(0).text);
    }

    @Test
    public void leadingSpacesDoNotCreateAFirstSentenceSuggestion() {
        List<SuggestionEngine.Candidate> out =
                scan(snap("  First sentence. Second long sentence."),
                        "First sentence. Sec", 4);
        assertEquals(1, out.size());
        assertEquals("Second long sentence.", out.get(0).text);
    }

    @Test
    public void sentenceIsStillSuggestedAtAnAlreadyTypedSentencePosition() {
        List<SuggestionEngine.Candidate> out = scan(
                snap("First sentence. Second long sentence."),
                "First sentence. Second long sentence. Sec", 4);
        assertEquals(1, out.size());
        assertEquals("Second long sentence.", out.get(0).text);
    }

    @Test
    public void matchingSentenceFromAnotherEntryIsStillSuggested() {
        List<SuggestionEngine.Candidate> out = scan(
                snap("First sentence. Second long sentence.",
                        "First sentence. Third long sentence."),
                "First sentence. Second long sentence. Thi", 4);
        assertEquals(1, out.size());
        assertEquals("Third long sentence.", out.get(0).text);
    }

    @Test
    public void matchingLaterSentenceIsSuggestedEvenWhenEarlierTextDiffers() {
        List<SuggestionEngine.Candidate> out = scan(
                snap("First. Other. Third long sentence."),
                "First. Second. Thi", 4);
        assertEquals(1, out.size());
        assertEquals("Third long sentence.", out.get(0).text);
    }

    @Test
    public void realHistoryEntryDoesNotSuggestAnUnrelatedNextSentence() {
        String typed = HISTORY_ENTRY_2648 + ". текущую реализацию";
        List<SuggestionEngine.Candidate> out = scan(snap(HISTORY_ENTRY_2648), typed, 4);
        assertTrue(out.isEmpty());
    }

    @Test
    public void thirdSentenceIsSuggestedAfterTwoTypedSentences() {
        List<SuggestionEngine.Candidate> out = scan(
                snap("First. Second. Third long sentence."),
                "First. Second. Thi", 4);
        assertEquals(2, out.size());
        assertEquals("First. Second. Third long sentence.", out.get(0).text);
        assertEquals(SuggestionEngine.Kind.FULL, out.get(0).kind);
        assertEquals("Third long sentence.", out.get(1).text);
        assertEquals(SuggestionEngine.Kind.SENTENCE, out.get(1).kind);
    }

    @Test
    public void activeSentenceStartsAfterTheLastNewline() {
        String text = "one\ntwo\nthree";
        assertEquals(8, SuggestionEngine.activeSentenceStart(text, text.length()));
        assertEquals(4, SuggestionEngine.activeSentenceStart(text, 6));
        assertEquals(0, SuggestionEngine.activeSentenceStart(text, 0));
        assertEquals(4, SuggestionEngine.activeSentenceStart("one\n", 4));
    }

    @Test
    public void activeSentenceStartsAfterOrdinaryTerminators() {
        assertEquals(7, SuggestionEngine.activeSentenceStart("First. Sec", 10));
        assertEquals(7, SuggestionEngine.activeSentenceStart("First. ", 7));
        assertEquals(8, SuggestionEngine.activeSentenceStart("First.  Sec", 11));
        assertEquals(7, SuggestionEngine.activeSentenceStart("First.\nSec", 10));
        assertEquals(15, SuggestionEngine.activeSentenceStart("First. Second. Third", 20));
        assertEquals(13, SuggestionEngine.activeSentenceStart("Wow! Really? Yes", 16));
        assertEquals(6, SuggestionEngine.activeSentenceStart("Wait… N", 7));
        assertEquals(6, SuggestionEngine.activeSentenceStart("First.", 6));
        assertEquals(0, SuggestionEngine.activeSentenceStart("First", 5));
    }

    @Test
    public void activeSentenceIndexCountsCompletedSentences() {
        assertEquals(0, SuggestionEngine.activeSentenceIndex("First", 0, 0));
        assertEquals(1, SuggestionEngine.activeSentenceIndex("First. Sec", 0, 7));
        assertEquals(2, SuggestionEngine.activeSentenceIndex("First. Second. Third", 0, 15));
        assertEquals(2, SuggestionEngine.activeSentenceIndex("Wow! Really? Yes", 0, 13));
        assertEquals(1, SuggestionEngine.activeSentenceIndex("Wait… N", 0, 6));
        assertEquals(0, SuggestionEngine.activeSentenceIndex("1.2.3", 0, 0));
    }

    @Test
    public void terminatorsFollowedByNonWhitespaceAreNotBoundaries() {
        assertEquals(0, SuggestionEngine.activeSentenceStart("cd ./pro", 8));
        assertEquals(0, SuggestionEngine.activeSentenceStart("1.2.3", 5));
        assertEquals(0, SuggestionEngine.activeSentenceStart(".git", 4));
        assertEquals(0, SuggestionEngine.activeSentenceStart("./project", 10));
        assertEquals(0, SuggestionEngine.activeSentenceStart("example.com", 11));
    }

    @Test
    public void activeLineStartsAfterTheLastNewline() {
        assertEquals(4, SuggestionEngine.activeLineStart("one\ntwo", 7));
        assertEquals(0, SuggestionEngine.activeLineStart("one\ntwo", 3));
        assertEquals(4, SuggestionEngine.activeLineStart("one\n", 4));
        assertEquals(0, SuggestionEngine.activeLineStart("", 0));
    }

    @Test
    public void sentenceAfterPeriodOnTheSameLineMatches() {
        List<SuggestionEngine.Candidate> out =
                scan(snap("First sentence. Second long sentence."), "First sentence. Sec", 4);
        assertEquals(2, out.size());
        assertEquals("First sentence. Second long sentence.", out.get(0).text);
        assertEquals(SuggestionEngine.Kind.FULL, out.get(0).kind);
        assertEquals("Second long sentence.", out.get(1).text);
        assertEquals(SuggestionEngine.Kind.SENTENCE, out.get(1).kind);
    }

    @Test
    public void sentenceAfterPeriodMatchesAcrossLineBreak() {
        List<SuggestionEngine.Candidate> out =
                scan(snap("First sentence.\nSecond long sentence."), "First sentence. Sec", 4);
        assertEquals(1, out.size());
        assertEquals("Second long sentence.", out.get(0).text);
        assertEquals(SuggestionEngine.Kind.SENTENCE, out.get(0).kind);
    }

    @Test
    public void sentenceAfterPeriodMatchesWhenInputUsesNewline() {
        List<SuggestionEngine.Candidate> out =
                scan(snap("First sentence. Second long sentence."), "First sentence.\nSec", 4);
        assertEquals(1, out.size());
        assertEquals("Second long sentence.", out.get(0).text);
        assertEquals(SuggestionEngine.Kind.SENTENCE, out.get(0).kind);
    }

    @Test
    public void dotInPathIsNotASentenceBoundary() {
        List<SuggestionEngine.Candidate> out = scan(snap("cd ./project"), "cd ./pro", 4);
        assertEquals(1, out.size());
        assertEquals("cd ./project", out.get(0).text);
        assertEquals(SuggestionEngine.Kind.FULL, out.get(0).kind);
    }

    @Test
    public void periodFollowedByDigitIsNotASentenceBoundary() {
        List<SuggestionEngine.Candidate> out =
                scan(snap("version 1.2.3 is out. Next"), "version 1.2", 4);
        assertEquals(1, out.size());
        assertEquals("version 1.2.3 is out. Next", out.get(0).text);
        assertEquals(SuggestionEngine.Kind.FULL, out.get(0).kind);
    }

    @Test
    public void sentenceAfterExclamationAndQuestionMarks() {
        List<SuggestionEngine.Candidate> out = scan(snap("Wow! Really? Yes."), "Wow! Re", 4);
        assertEquals(2, out.size());
        assertEquals("Wow! Really? Yes.", out.get(0).text);
        assertEquals(SuggestionEngine.Kind.FULL, out.get(0).kind);
        assertEquals("Really?", out.get(1).text);
        assertEquals(SuggestionEngine.Kind.SENTENCE, out.get(1).kind);
    }

    @Test
    public void ellipsisIsASentenceBoundary() {
        List<SuggestionEngine.Candidate> out = scan(snap("Wait… Next"), "Wait… N", 4);
        assertEquals(2, out.size());
        assertEquals("Wait… Next", out.get(0).text);
        assertEquals(SuggestionEngine.Kind.FULL, out.get(0).kind);
        assertEquals("Next", out.get(1).text);
        assertEquals(SuggestionEngine.Kind.SENTENCE, out.get(1).kind);
    }

    @Test
    public void sentenceAfterPeriodAtEndOfLine() {
        List<SuggestionEngine.Candidate> out = scan(snap("First.\nSecond"), "First. Sec", 4);
        assertEquals(1, out.size());
        assertEquals("Second", out.get(0).text);
        assertEquals(SuggestionEngine.Kind.SENTENCE, out.get(0).kind);
    }

    @Test
    public void filterDropsExactFullAndSentence() {
        HistorySnapshot snapshot = snap("First. Second");
        List<SuggestionEngine.Candidate> out = scan(snapshot, "First. Sec", 4);
        assertEquals(2, out.size());

        SuggestionEngine.filter(out, snapshot, 10, 3, 'o');
        assertEquals(2, out.size());
        SuggestionEngine.filter(out, snapshot, 11, 4, 'n');
        assertEquals(2, out.size());
        SuggestionEngine.filter(out, snapshot, 12, 5, 'd');
        // Both the full entry and the sentence are now exact matches.
        assertTrue(out.isEmpty());
    }
}
