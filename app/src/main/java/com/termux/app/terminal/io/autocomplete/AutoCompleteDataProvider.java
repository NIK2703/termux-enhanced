package com.termux.app.terminal.io.autocomplete;

import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;

/**
 * Data and callbacks the popup manager needs from the controller. Keeping this narrow
 * separates candidate data and input handling from the popup window.
 */
interface AutoCompleteDataProvider {

    /** Suggestion strings: full entries first, then sentences; newest first per tier. */
    @NonNull ArrayList<String> getSuggestions();

    /** Max number of suggestions to RENDER (user setting). */
    int getDisplayMax();

    /** The EditText the popups are anchored to. */
    @Nullable EditText getInputField();

    /** Create a suggestion row and wire its tap/swipe handlers. */
    @NonNull TextView buildSuggestionTextView(@NonNull String suggestion, @NonNull String input);

    /** Same as above, reusing the caller's candidate metadata. */
    @NonNull TextView buildSuggestionTextView(@NonNull String suggestion, @NonNull String input,
            @Nullable SuggestionEngine.Candidate candidate);

    /** Rebind an existing row without allocating a new view. */
    void rebindSuggestionTextView(@NonNull TextView tv, @NonNull String suggestion, @NonNull String input);

    /** {@link #rebindSuggestionTextView} with the caller's candidate metadata. */
    void rebindSuggestionTextView(@NonNull TextView tv, @NonNull String suggestion,
            @NonNull String input, @Nullable SuggestionEngine.Candidate candidate);

    /** Candidate metadata for the suggestion at {@code index}, or null when stale. */
    @Nullable SuggestionEngine.Candidate getCandidate(int index);

    /** Bumped whenever the suggestion list changes (O(1) popup diff). */
    int getSuggestionsVersion();

    /** Bumped whenever the typed line/sentence bases change (O(1) popup diff). */
    int getPrefixVersion();

    /** Callback run when the popup is dismissed by a suggestion tap. */
    void onSuggestionDismissed();

    /** True while a swipe-to-select gesture is in progress (popup must survive). */
    boolean isSwipeActive();
}
