package com.termux.app.terminal.io.autocomplete;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.TextView;

import com.termux.app.terminal.TermuxColorSchemeManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Field;
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
    public void changedBoldPrefixResetsRow() {
        history.addToMessageHistory("First sentence. Second long sentence.", HOME);
        type("First sentence. Sec");
        String suggestion = suggestions().get(0);

        CountingTextView row = new CountingTextView(input.getContext());
        row.setTextCalls = 0;
        controller.rebindSuggestionTextView(row, suggestion, "First sentence. Sec");
        assertEquals(1, row.setTextCalls);

        type("First sentence. Seco");
        controller.rebindSuggestionTextView(row, suggestion, "First sentence. Seco");
        assertEquals("a longer bold prefix must reset the row",
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
