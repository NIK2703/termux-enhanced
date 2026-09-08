package com.termux.app.terminal.io.autocomplete;

import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.terminal.TermuxColorSchemeManager;

import java.util.ArrayList;

/**
 * Narrow read-only data + callback surface that {@link AutoCompletePopupManager}
 * uses to build and position the suggestion windows. Keeping this an explicit
 * interface (rather than handing the manager the whole {@code AutoCompleteController})
 * enforces the responsibility split:
 *
 * <ul>
 *   <li>{@code AutoCompleteController} — owns the suggestion data, input handling
 *       and candidate insertion;</li>
 *   <li>{@code AutoCompletePopupManager} — owns the history popup window (build,
 *       show, position, dismiss, content rebuild & bold-span refresh);</li>
 * </ul>
 */
interface AutoCompleteDataProvider {

    /** Current suggestion strings (message-history candidates, newest first). */
    @NonNull ArrayList<String> getSuggestions();

    /** Max number of suggestions to RENDER (user setting). */
    int getDisplayMax();

    /** The EditText the popups are anchored to. */
    @Nullable EditText getInputField();

    /** Colour-scheme manager vending popup colours. */
    @NonNull TermuxColorSchemeManager getColorSchemeManager();

    /** Host window (for the global layout listener), or null if unavailable. */
    @Nullable android.view.Window getWindow();

    /** Build a single suggestion row TextView (wires the tap/swipe handlers). */
    @NonNull TextView buildSuggestionTextView(@NonNull String suggestion, @NonNull String input);

    /**
     * Rebind an existing suggestion row to new data without re-allocating the view.
     * Must restore the same visual state a fresh {@link #buildSuggestionTextView} would.
     */
    void rebindSuggestionTextView(@NonNull TextView tv, @NonNull String suggestion, @NonNull String input);

    /** Current history version (to skip a rebuild when history is unchanged). */
    int getHistoryVersion();

    /** Callback run when the popup is dismissed by a suggestion tap. */
    void onSuggestionDismissed();

    /** True while a swipe-to-select gesture is in progress (popup must survive). */
    boolean isSwipeActive();
}
