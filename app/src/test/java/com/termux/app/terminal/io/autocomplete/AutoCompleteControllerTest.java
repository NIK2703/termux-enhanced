package com.termux.app.terminal.io.autocomplete;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.termux.app.terminal.TermuxColorSchemeManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * End-to-end wiring of the two-tier sentence search into the controller: what the
 * popup receives for a first line, a new sentence, priority, dedup, exact matches,
 * and what a tap inserts.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AutoCompleteControllerTest {

    private static final String HOME = "/home/user";
    private static final String LOG_DIR = "/var/log";

    private SharedPreferences prefs;
    private MessageHistoryController history;
    private EditText input;
    private AutoCompleteController controller;

    @Before
    public void setUp() throws Exception {
        resetSingleton();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        prefs = activity.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        prefs.edit().putInt("suggestions_max_count", 4).commit();
        history = MessageHistoryController.shared(prefs);
        history.load(HOME);
        input = new EditText(activity);
        controller = new AutoCompleteController(activity, input, history,
                TermuxColorSchemeManager.shared());
    }

    private void resetSingleton() throws Exception {
        Field shared = MessageHistoryController.class.getDeclaredField("sShared");
        shared.setAccessible(true);
        shared.set(null, null);
    }

    /** Replace the field text, put the caret at the end, and force one update. */
    private void type(String text) {
        input.setText(text);
        input.setSelection(text.length());
        controller.onCaretMoved();
    }

    private ArrayList<String> suggestions() {
        return controller.getSuggestions();
    }

    @Test
    public void firstLineMatchesAFullEntry() {
        history.addToMessageHistory("git commit -m", HOME);
        type("git c");
        assertEquals(1, suggestions().size());
        assertEquals("git commit -m", suggestions().get(0));
    }

    @Test
    public void newSentenceMatchesASentenceAfterTheFirstLine() {
        history.addToMessageHistory("echo first\ngit status", HOME);
        type("echo first\n");
        assertTrue("an empty new sentence has nothing to complete", suggestions().isEmpty());

        type("echo first\ngit");
        assertEquals(1, suggestions().size());
        assertEquals("git status", suggestions().get(0));
    }

    @Test
    public void indentedHistorySentenceIsSuggestedAndInsertedWithoutIndentation() {
        history.addToMessageHistory("echo first\n    git status", HOME);
        type("echo first\ngit");
        assertEquals(1, suggestions().size());
        assertEquals("git status", suggestions().get(0));

        TextView row = controller.buildSuggestionTextView(suggestions().get(0), "git");
        assertTrue(row.performClick());
        assertEquals("echo first\ngit status", input.getText().toString());
    }

    @Test
    public void historySentenceTrailingSpacesAreNotSuggestedOrInserted() {
        history.addToMessageHistory("echo first\ngit status   \necho last", HOME);
        type("echo first\ngit");
        assertEquals(1, suggestions().size());
        assertEquals("git status", suggestions().get(0));

        TextView row = controller.buildSuggestionTextView(suggestions().get(0), "git");
        assertTrue(row.performClick());
        assertEquals("echo first\ngit status", input.getText().toString());
    }

    @Test
    public void fullEntriesComeBeforeSentences() {
        history.addToMessageHistory("git one", HOME);
        history.addToMessageHistory("x\ngit two", HOME);
        type("git");
        assertEquals(2, suggestions().size());
        assertEquals("git one", suggestions().get(0));
        assertEquals("git two", suggestions().get(1));
    }

    @Test
    public void sentenceSwitchOffKeepsFullEntriesOnly() {
        history.addToMessageHistory("git one", HOME);
        history.addToMessageHistory("x\ngit two", HOME);
        prefs.edit().putBoolean("autocomplete_sentences_enabled", false).commit();
        controller.reloadPreferences();

        type("git");
        assertEquals(1, suggestions().size());
        assertEquals("git one", suggestions().get(0));
    }

    @Test
    public void sentenceSwitchCanBeReEnabledLive() {
        history.addToMessageHistory("x\ngit two", HOME);
        prefs.edit().putBoolean("autocomplete_sentences_enabled", false).commit();
        controller.reloadPreferences();
        type("git");
        assertTrue(suggestions().isEmpty());

        prefs.edit().putBoolean("autocomplete_sentences_enabled", true).commit();
        controller.reloadPreferences();
        type("git");
        assertEquals(1, suggestions().size());
        assertEquals("git two", suggestions().get(0));
    }

    @Test
    public void sentenceSpansAreNotIndexedWhileTheSwitchIsOff() {
        prefs.edit().putBoolean("autocomplete_sentences_enabled", false).commit();
        controller.reloadPreferences();

        history.addToMessageHistory("x\ngit two", HOME);
        HistorySnapshot.Spans spans = history.getSpansCache().get("x\ngit two");
        assertNotNull(spans);
        assertFalse("OFF must not parse sentence offsets", spans.hasSentences());

        prefs.edit().putBoolean("autocomplete_sentences_enabled", true).commit();
        controller.reloadPreferences();
        HistorySnapshot.Spans warmed = history.getSpansCache().get("x\ngit two");
        assertNotNull(warmed);
        assertTrue("ON must warm sentence offsets before typing", warmed.hasSentences());
    }

    @Test
    public void duplicateSentencesUseTheNewestOccurrence() {
        history.addToMessageHistory("a\nsame sentence", HOME);
        history.addToMessageHistory("b\nsame sentence", HOME);
        type("same");
        assertEquals(1, suggestions().size());
        assertEquals("same sentence", suggestions().get(0));
    }

    @Test
    public void duplicateSentencesUseNewestCaseInsensitiveOccurrence() {
        history.addToMessageHistory("a\nsame sentence", HOME);
        history.addToMessageHistory("b\nSame sentence", HOME);
        type("same");
        assertEquals(1, suggestions().size());
        assertEquals("Same sentence", suggestions().get(0));
    }

    @Test
    public void exactMatchIsNotSuggested() {
        history.addToMessageHistory("git status", HOME);
        type("git status");
        assertTrue(suggestions().isEmpty());
    }

    @Test
    public void tapInsertsTheWholeFullEntry() {
        history.addToMessageHistory("git commit -m\nsecond line", HOME);
        type("git com");
        assertEquals(1, suggestions().size());

        TextView row = controller.buildSuggestionTextView(
                suggestions().get(0), "git com");
        assertTrue(row.performClick());
        assertEquals("git commit -m\nsecond line", input.getText().toString());
    }

    @Test
    public void tapReplacesOnlyTheActiveSentence() {
        history.addToMessageHistory("git commit -m", HOME);
        type("echo x\ngit com");
        assertEquals(1, suggestions().size());

        TextView row = controller.buildSuggestionTextView(
                suggestions().get(0), "git com");
        assertTrue(row.performClick());
        assertEquals("echo x\ngit commit -m", input.getText().toString());
    }

    @Test
    public void additiveFilterNarrowsAndBackspaceExpands() {
        history.addToMessageHistory("git status", HOME);
        history.addToMessageHistory("git stash", HOME);
        type("git s");
        assertEquals(2, suggestions().size());

        input.getText().append("t");
        assertEquals(2, suggestions().size());

        input.getText().append("a");
        assertEquals(2, suggestions().size());

        input.getText().append("t");
        assertEquals(1, suggestions().size());
        assertEquals("git status", suggestions().get(0));

        // Backspace: the candidate set must grow back, not stay filtered.
        input.getText().delete(input.length() - 1, input.length());
        assertEquals(2, suggestions().size());
    }

    @Test
    public void incrementalTypingAfterPeriodTracksTheNewSentence() {
        history.addToMessageHistory("First sentence. Second long sentence.", HOME);
        type("First sentence");
        assertFalse(suggestions().isEmpty());

        // The period is a tentative boundary; the active sentence is empty.
        input.getText().append(".");
        assertTrue(suggestions().isEmpty());

        // The space confirms the boundary; still empty.
        input.getText().append(" ");
        assertTrue(suggestions().isEmpty());

        // The second sentence starts: full entry + first-sentence rollback + sentence.
        input.getText().append("S");
        assertEquals(3, suggestions().size());
        assertEquals("First sentence. Second long sentence.", suggestions().get(0));
        assertEquals("First sentence.", suggestions().get(1));
        assertEquals("Second long sentence.", suggestions().get(2));

        input.getText().append("e");
        assertEquals(3, suggestions().size());
        input.getText().append("c");
        assertEquals(3, suggestions().size());
    }

    @Test
    public void newSentenceAfterAFullEntryDoesNotOfferTheFirstSentenceRollback() {
        history.addToMessageHistory("First sentence. Second long sentence.", HOME);
        type("First sentence. Second long sentence. Sec");

        assertFalse("a sentence already typed must not trigger the first-sentence rollback",
                suggestions().contains("First sentence."));
        assertTrue("the active sentence still completes",
                suggestions().contains("Second long sentence."));
    }

    @Test
    public void divergingEntryDoesNotOfferTheRollbackForAMatchingLaterSentence() {
        history.addToMessageHistory("First. Other. Third long sentence.", HOME);
        type("First. Second. Thi");

        assertFalse("an entry that diverges before the active sentence must not roll back",
                suggestions().contains("First."));
        assertTrue("its later sentence still completes the active sentence",
                suggestions().contains("Third long sentence."));
    }

    @Test
    public void typingAfterRestoringShorterTextRecomputesBases() {
        history.addToMessageHistory("echo restored", HOME);

        // The IME moves the caret to the end when committing. Register this before the
        // controller so its afterTextChanged sees the final caret position.
        EditText imeInput = new EditText(input.getContext());
        imeInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                imeInput.setSelection(s.length());
            }
        });
        input = imeInput;
        controller = new AutoCompleteController(input.getContext(), input, history,
                TermuxColorSchemeManager.shared());

        input.setText("aaaaaaaaaa\nbbbbbbbbbb");
        input.setSelection(input.length());
        controller.onCaretMoved();
        assertTrue(suggestions().isEmpty());

        // The restore bypasses the normal update path; old bases could start past its end.
        controller.setRestoringInput(true);
        input.setText("echo ");
        controller.setRestoringInput(false);

        input.getText().append("r");
        assertEquals(1, suggestions().size());
        assertEquals("echo restored", suggestions().get(0));
    }

    @Test
    public void caretAwayFromTheEndDismisses() {
        history.addToMessageHistory("git status", HOME);
        type("git s");
        assertFalse(suggestions().isEmpty());

        input.setSelection(1);
        controller.onCaretMoved();
        assertTrue(suggestions().isEmpty());
    }

    @Test
    public void externalHistoryChangeIsPickedUp() {
        history.addToMessageHistory("git status", HOME);
        type("git s");
        assertEquals(1, suggestions().size());

        history.addToMessageHistory("git switch", HOME);
        type("git sw");
        assertEquals(1, suggestions().size());
        assertEquals("git switch", suggestions().get(0));
    }

    @Test
    public void minPrefixSettingGatesTheFirstScan() {
        prefs.edit().putInt("autocomplete_min_prefix", 2).commit();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        input = new EditText(activity);
        controller = new AutoCompleteController(activity, input, history,
                TermuxColorSchemeManager.shared());

        history.addToMessageHistory("git status", HOME);
        type("g");
        assertTrue(suggestions().isEmpty());

        type("gi");
        assertEquals(1, suggestions().size());
        assertEquals("git status", suggestions().get(0));
    }

    @Test
    public void sentenceAfterPeriodIsSuggestedAndInserted() {
        history.addToMessageHistory("First sentence.\nSecond long sentence.", HOME);
        type("First sentence. Sec");
        assertEquals(2, suggestions().size());
        assertEquals("First sentence.", suggestions().get(0));
        assertEquals("Second long sentence.", suggestions().get(1));

        TextView row = controller.buildSuggestionTextView(suggestions().get(1), "Sec");
        assertTrue(row.performClick());
        assertEquals("First sentence. Second long sentence.", input.getText().toString());
    }

    @Test
    public void firstSentenceRollbackReplacesTheWholeLine() {
        history.addToMessageHistory("First sentence. Second long sentence.", HOME);
        type("First sentence. Sec");
        assertEquals(3, suggestions().size());
        assertEquals("First sentence. Second long sentence.", suggestions().get(0));
        assertEquals("First sentence.", suggestions().get(1));
        assertEquals("Second long sentence.", suggestions().get(2));

        TextView row = controller.buildSuggestionTextView(suggestions().get(1), "Sec");
        assertTrue(row.performClick());
        assertEquals("First sentence.", input.getText().toString());
    }

    @Test
    public void deletedSentenceStaysHiddenAcrossAnIndexRebuild() {
        history.addToMessageHistory("First sentence.\nSecond long sentence.", HOME);
        type("First sentence.\nSec");
        assertEquals(1, suggestions().size());
        assertEquals("Second long sentence.", suggestions().get(0));

        assertTrue(controller.deleteSuggestion(controller.getCandidate(0)));
        assertTrue(suggestions().isEmpty());
        assertEquals("[First sentence.\nSecond long sentence.]",
                String.valueOf(history.getHistoryList()));

        // The history is read-only for autocomplete, but the dismissal is keyed by
        // the record id and survives an index rebuild.
        controller.invalidateHistoryVersion();
        type("First sentence.\nSec");
        assertTrue(suggestions().isEmpty());
    }

    @Test
    public void deletedFullEntryStaysHiddenAcrossAnIndexRebuild() {
        history.addToMessageHistory("git status", HOME);
        type("git s");
        assertEquals(1, suggestions().size());

        assertTrue(controller.deleteSuggestion(controller.getCandidate(0)));
        assertTrue(suggestions().isEmpty());
        assertEquals("[git status]", String.valueOf(history.getHistoryList()));

        controller.invalidateHistoryVersion();
        type("git s");
        assertTrue(suggestions().isEmpty());
    }

    @Test
    public void deletingAWholeEntryAlsoHidesItsOwnSentenceWindows() {
        history.addToMessageHistory("First sentence. Second long sentence.", HOME);
        type("First sentence. Sec");
        assertEquals(3, suggestions().size());
        SuggestionEngine.Candidate full = controller.getCandidate(0);
        assertEquals(SuggestionEngine.Kind.FULL, full.kind);

        assertTrue(controller.deleteSuggestion(full));

        controller.invalidateHistoryVersion();
        type("First sentence. Sec");
        assertTrue("the rollback and sentence windows of the deleted full entry are hidden",
                suggestions().isEmpty());
        assertEquals("[First sentence. Second long sentence.]",
                String.valueOf(history.getHistoryList()));
    }

    @Test
    public void deletedFirstSentenceRollbackStaysHiddenWhileLaterSentenceRemains() {
        history.addToMessageHistory("First sentence. Second long sentence.", HOME);
        type("First sentence. Sec");
        assertEquals(3, suggestions().size());
        SuggestionEngine.Candidate rollback = controller.getCandidate(1);
        assertTrue(rollback.isFirstSentence);

        assertTrue(controller.deleteSuggestion(rollback));
        assertFalse(suggestions().contains("First sentence."));
        assertTrue(suggestions().contains("Second long sentence."));
        assertEquals("[First sentence. Second long sentence.]",
                String.valueOf(history.getHistoryList()));

        controller.invalidateHistoryVersion();
        type("First sentence. Sec");
        assertFalse(suggestions().contains("First sentence."));
        assertTrue(suggestions().contains("Second long sentence."));
    }

    @Test
    public void deletedSuggestionStaysHiddenAfterRestart() throws Exception {
        history.addToMessageHistory("git status", HOME);
        type("git s");
        assertTrue(controller.deleteSuggestion(controller.getCandidate(0)));

        restart();

        type("git s");
        assertTrue(suggestions().isEmpty());
    }

    @Test
    public void deletedSuggestionStaysHiddenAfterRestartInPerDirectoryMode() throws Exception {
        prefs.edit().putBoolean("per_directory_message_history", true).commit();
        history.setPerDirectoryEnabled(true);
        history.load(HOME);
        history.addToMessageHistory("git status", HOME);
        type("git s");
        assertTrue(controller.deleteSuggestion(controller.getCandidate(0)));

        restart();

        type("git s");
        assertTrue(suggestions().isEmpty());
    }

    @Test
    public void consecutiveDeletesBothPersistWithoutARebuild() {
        history.addToMessageHistory("git status", HOME);
        history.addToMessageHistory("git stash", HOME);
        type("git s");
        assertEquals(2, suggestions().size());

        assertTrue(controller.deleteSuggestion(controller.getCandidate(0)));
        assertTrue(controller.deleteSuggestion(controller.getCandidate(0)));

        type("git s");
        assertTrue(suggestions().isEmpty());
    }

    @Test
    public void deletedSuggestionStaysHiddenWhenTheEntryIsPromoted() {        history.addToMessageHistory("git status", HOME);
        history.addToMessageHistory("git commit", HOME);
        type("git s");
        assertTrue(controller.deleteSuggestion(controller.getCandidate(0)));

        // Same record, new position: the id and its dismissal survive.
        history.addToMessageHistory("git status", HOME);
        type("git s");
        assertTrue(suggestions().isEmpty());
    }

    @Test
    public void removingAndReAddingTheEntryMakesItSuggestableAgain() {
        history.addToMessageHistory("git status", HOME);
        type("git s");
        assertTrue(controller.deleteSuggestion(controller.getCandidate(0)));

        assertTrue(history.removeMessage("git status"));
        history.addToMessageHistory("git status", HOME);
        type("git s");
        assertEquals(1, suggestions().size());
        assertEquals("git status", suggestions().get(0));
    }

    @Test
    public void historyChangeHidesPopupAndDropsStaleCandidates() {
        history.addToMessageHistory("git status", HOME);
        history.addToMessageHistory("git stash", HOME);
        type("git s");
        assertEquals(2, suggestions().size());

        assertTrue(history.removeMessage("git stash"));
        controller.onHistoryChanged();
        assertTrue(suggestions().isEmpty());

        // The next caret event rebuilds from the new history version.
        controller.onCaretMoved();
        assertEquals(1, suggestions().size());
        assertEquals("git status", suggestions().get(0));
    }

    @Test
    public void staleRowCannotInsertTextAfterHistoryDeletion() {
        history.addToMessageHistory("git status", HOME);
        history.addToMessageHistory("git stash", HOME);
        type("git s");

        String removed = suggestions().get(0);
        TextView staleRow = controller.buildSuggestionTextView(removed, "git s");

        assertTrue(history.removeMessage(removed));
        controller.onHistoryChanged();
        staleRow.performClick();

        assertEquals("git s", input.getText().toString());
    }

    @Test
    public void staleCandidateIndexIsRejectedAfterSnapshotRebuild() throws Exception {
        history.addToMessageHistory("cmd beta", HOME);
        history.addToMessageHistory("cmd gamma", HOME);
        type("cmd ");
        assertEquals(2, suggestions().size());
        SuggestionEngine.Candidate removed = controller.getCandidate(0);
        SuggestionEngine.Candidate remaining = controller.getCandidate(1);
        assertEquals(1, remaining.entryIndex);

        // Remove the newer entry externally, then let deleteSuggestion() rebuild
        // the snapshot. The remaining candidate keeps its old entryIndex, which is
        // now outside the shorter snapshot.
        assertTrue(history.removeMessage(removed.text));
        assertTrue(controller.deleteSuggestion(removed));
        assertEquals(1, suggestions().size());

        Method match = AutoCompleteController.class.getDeclaredMethod(
                "suggestionsMatchTail", CharSequence.class, int.class);
        match.setAccessible(true);
        assertFalse((Boolean) match.invoke(controller, "cmd ", 4));
    }

    @Test
    public void deletingASentenceHidesTheSameSentenceInAnotherEntry() {
        history.addToMessageHistory("a\nshared tail", HOME);
        history.addToMessageHistory("b\nshared tail", HOME);
        type("a\nshared");
        assertEquals(1, suggestions().size());
        assertTrue(controller.deleteSuggestion(controller.getCandidate(0)));

        // The duplicate window in the other record is hidden too.
        type("b\nshared");
        assertTrue(suggestions().isEmpty());
    }

    @Test
    public void deletingAFullEntryWhileOffStillHidesItsSentences() {
        history.addToMessageHistory("git status\nsecond line", HOME);
        prefs.edit().putBoolean("autocomplete_sentences_enabled", false).commit();
        controller.reloadPreferences();

        type("git s");
        assertEquals(1, suggestions().size());
        assertTrue(controller.deleteSuggestion(controller.getCandidate(0)));

        prefs.edit().putBoolean("autocomplete_sentences_enabled", true).commit();
        controller.reloadPreferences();
        type("git status\nsec");
        assertTrue("full-entry dismissal bit hides every sentence of the record",
                suggestions().isEmpty());
    }

    @Test
    public void deletingAFullEntryHidesTheSameTextAsASentenceElsewhere() {
        history.addToMessageHistory("git status", HOME);
        history.addToMessageHistory("x\ngit status", HOME);
        type("git s");
        assertEquals(1, suggestions().size());
        assertTrue(controller.deleteSuggestion(controller.getCandidate(0)));

        type("x\ngit s");
        assertTrue(suggestions().isEmpty());
    }

    @Test
    public void deletingASentenceDoesNotHideAnotherSentenceOfTheSameEntry() {
        history.addToMessageHistory("a\nfirst tail\nsecond tail", HOME);
        type("a\nfirst");
        assertEquals(1, suggestions().size());
        assertTrue(controller.deleteSuggestion(controller.getCandidate(0)));

        type("a\nsecond");
        assertEquals(1, suggestions().size());
        assertEquals("second tail", suggestions().get(0));
    }

    @Test
    public void deletedSuggestionIsIndependentPerDirectory() {
        history.setPerDirectoryEnabled(true);
        history.load(HOME);
        history.addToMessageHistory("git status", HOME);
        history.addToMessageHistoryInDirectory("git status", LOG_DIR);
        type("git s");
        assertTrue(controller.deleteSuggestion(controller.getCandidate(0)));
        assertTrue(suggestions().isEmpty());

        history.onHistoryDirectoryChanged(HOME, LOG_DIR);
        type("git s");
        assertEquals(1, suggestions().size());
        assertEquals("git status", suggestions().get(0));

        history.onHistoryDirectoryChanged(LOG_DIR, HOME);
        type("git s");
        assertTrue(suggestions().isEmpty());
    }

    /** Simulate a process restart: a fresh singleton store and a fresh controller. */
    private void restart() throws Exception {
        history.save();
        resetSingleton();
        history = MessageHistoryController.shared(prefs);
        history.load(HOME);
        input = new EditText(input.getContext());
        controller = new AutoCompleteController(input.getContext(), input, history,
                TermuxColorSchemeManager.shared());
    }

    @Test
    public void staleSuggestionIsNotRemovedFromARebuiltIndex() {
        history.addToMessageHistory("First sentence.\nSecond long sentence.", HOME);
        type("First sentence.\nSec");
        SuggestionEngine.Candidate stale = controller.getCandidate(0);

        // A rebuild replaces the candidate objects; the old row's index entry is gone.
        controller.invalidateHistoryVersion();
        type("First sentence.\nSec");

        assertFalse(controller.deleteSuggestion(stale));
        assertEquals(1, suggestions().size());
        assertEquals("[First sentence.\nSecond long sentence.]",
                String.valueOf(history.getHistoryList()));
    }

    @Test
    public void leftSwipeDeletesTheSuggestionFromTheIndexAndRemovesItsRow() {
        history.addToMessageHistory("First sentence.\nSecond long sentence.", HOME);
        type("First sentence.\nSec");
        assertEquals(1, suggestions().size());

        SuggestionRowHolder holder =
                measuredSuggestionRow(suggestions().get(0), 1000);
        TextView row = holder.row();

        final long now = SystemClock.uptimeMillis();
        dispatchTouch(row, MotionEvent.ACTION_DOWN, 900f, now);
        dispatchTouch(row, MotionEvent.ACTION_MOVE, 300f, now + 10);
        dispatchTouch(row, MotionEvent.ACTION_UP, 300f, now + 20);

        assertTrue(suggestions().isEmpty());
        assertEquals("[First sentence.\nSecond long sentence.]",
                String.valueOf(history.getHistoryList()));
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        assertTrue("the deleted row is removed after its slide-out",
                holder.getParent() == null);
    }

    private SuggestionRowHolder measuredSuggestionRow(String suggestion, int width) {
        TextView row = controller.buildSuggestionTextView(suggestion, "Sec");
        SuggestionRowHolder holder = new SuggestionRowHolder(input.getContext(), row);
        FrameLayout parent = new FrameLayout(input.getContext());
        parent.addView(holder);
        holder.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        holder.layout(0, 0, width, holder.getMeasuredHeight());
        assertEquals(width, row.getWidth());
        return holder;
    }

    private static void dispatchTouch(TextView row, int action, float x, long time) {
        MotionEvent event = MotionEvent.obtain(time, time, action, x, 50f, 0);
        try {
            row.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    @Test
    public void singleLineHistoryOffersFullEntryAndSentenceAfterPeriod() {
        history.addToMessageHistory("First sentence. Second long sentence.", HOME);
        type("First sentence. Sec");
        assertEquals(3, suggestions().size());
        assertEquals("First sentence. Second long sentence.", suggestions().get(0));
        assertEquals("First sentence.", suggestions().get(1));
        assertEquals("Second long sentence.", suggestions().get(2));

        TextView row = controller.buildSuggestionTextView(suggestions().get(2), "Sec");
        assertTrue(row.performClick());
        assertEquals("First sentence. Second long sentence.", input.getText().toString());
    }

    @Test
    public void fullEntryAfterPeriodInsertsWholeEntry() {
        history.addToMessageHistory("First sentence. Second long sentence.", HOME);
        type("First sentence. Sec");
        assertEquals(3, suggestions().size());

        TextView row = controller.buildSuggestionTextView(
                suggestions().get(0), "First sentence. Sec");
        assertTrue(row.performClick());
        assertEquals("First sentence. Second long sentence.", input.getText().toString());
    }

    @Test
    public void sentenceAfterPeriodWithNewlineInput() {
        history.addToMessageHistory("First sentence. Second long sentence.", HOME);
        type("First sentence.\nSec");
        assertEquals(1, suggestions().size());
        assertEquals("Second long sentence.", suggestions().get(0));

        TextView row = controller.buildSuggestionTextView(suggestions().get(0), "Sec");
        assertTrue(row.performClick());
        assertEquals("First sentence.\nSecond long sentence.", input.getText().toString());
    }

    @Test
    public void dotInPathDoesNotSplit() {
        history.addToMessageHistory("cd ./project", HOME);
        type("cd ./pro");
        assertEquals(1, suggestions().size());
        assertEquals("cd ./project", suggestions().get(0));

        TextView row = controller.buildSuggestionTextView(suggestions().get(0), "cd ./pro");
        assertTrue(row.performClick());
        assertEquals("cd ./project", input.getText().toString());
    }

    @Test
    public void minPrefixUsesTheLineWhenSentencesAreOff() {
        prefs.edit()
                .putInt("autocomplete_min_prefix", 2)
                .putBoolean("autocomplete_sentences_enabled", false)
                .commit();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        input = new EditText(activity);
        controller = new AutoCompleteController(activity, input, history,
                TermuxColorSchemeManager.shared());

        history.addToMessageHistory("First. Second", HOME);
        type("First. S");
        assertEquals("the line prefix is long enough even though the sentence is not",
                1, suggestions().size());
        assertEquals("First. Second", suggestions().get(0));
    }

    @Test
    public void minPrefixGatesTheSentenceAfterPeriod() {
        prefs.edit().putInt("autocomplete_min_prefix", 2).commit();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        input = new EditText(activity);
        controller = new AutoCompleteController(activity, input, history,
                TermuxColorSchemeManager.shared());

        history.addToMessageHistory("First.\nSecond", HOME);
        type("First. S");
        assertTrue(suggestions().isEmpty());

        type("First. Se");
        assertEquals(2, suggestions().size());
        assertEquals("First.", suggestions().get(0));
        assertEquals("Second", suggestions().get(1));
    }

    @Test
    public void unchangedRowIsNotReset() {
        history.addToMessageHistory("First sentence. Second long sentence.", HOME);
        type("First sentence. Sec");
        String suggestion = suggestions().get(0);

        CountingTextView row = new CountingTextView(input.getContext());
        row.setTextCalls = 0;
        controller.rebindSuggestionTextView(row, suggestion, "First sentence. Sec");
        assertEquals(1, row.setTextCalls);

        controller.rebindSuggestionTextView(row, suggestion, "First sentence. Sec");
        assertEquals("an identical rebind must not reset the row",
                1, row.setTextCalls);
    }

    @Test
    public void changedTypedPrefixResetsRow() {
        history.addToMessageHistory("First sentence. Second long sentence.", HOME);
        type("First sentence. Sec");
        String suggestion = suggestions().get(0);

        CountingTextView row = new CountingTextView(input.getContext());
        row.setTextCalls = 0;
        controller.rebindSuggestionTextView(row, suggestion, "First sentence. Sec");
        assertEquals(1, row.setTextCalls);

        type("First sentence. Seco");
        controller.rebindSuggestionTextView(row, suggestion, "First sentence. Seco");
        assertEquals("a longer typed prefix must reset the row",
                2, row.setTextCalls);
    }

    private static final class CountingTextView extends TextView {
        int setTextCalls;

        CountingTextView(Context context) {
            super(context);
        }

        @Override
        public void setText(CharSequence text, TextView.BufferType type) {
            setTextCalls++;
            super.setText(text, type);
        }
    }
}
