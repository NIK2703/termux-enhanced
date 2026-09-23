package com.termux.app.terminal.io.autocomplete;

import android.content.Context;
import android.text.Editable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.function.Supplier;

/**
 * Implements the swipe-to-select gesture on a suggestion row.
 *
 * <p>While the finger is down and moving horizontally past the touch slop, each
 * {@link #mSwipeUnitWidthPx} of rightward travel appends the next word of the
 * suggestion into a live selection in the input field; leftward travel removes
 * previously-added words. The selection is highlighted while the finger is down
 * and collapsed (committed) on release. A pure tap never engages the swipe and
 * falls through to the row's {@code OnClickListener} (tap-to-insert), because
 * {@link #onTouch} returns {@code false} until the slop is crossed.
 *
 * <p>All per-gesture state lives here so {@code AutoCompleteController} doesn't
 * carry it; the handler is handed the input field (lazily, since it may be
 * swapped) and a callback to refresh suggestions after a committed swipe.
 */
final class AutoCompleteSwipeHandler {

    /** Words remaining to be inserted from the swiped suggestion (in order). */
    @Nullable private String[] mSwipeWords;
    /** Text kept before any swipe-added words (the user's already-typed prefix / base). */
    @Nullable private String mSwipeBaseText;
    private boolean mSwipeNeedsLeadingSpace;
    /** Selection anchor (start) — stays fixed while the selection end grows/shrinks. */
    private int mSwipeAnchorStart;
    /** Number of words currently appended to the selection. */
    private int mSwipeWordsAdded;
    /** Horizontal pixels of finger travel that add/remove one word. */
    private int mSwipeUnitWidthPx;
    /** Suggestion string the current gesture started on (matched by tag, not index). */
    @Nullable private String mSwipeSuggestion;
    /** Parent whose touch-interception we disabled while engaged, so we can restore it. */
    @Nullable private android.view.ViewParent mSwipeDisallowParent;
    /** Suggestion row held in the pressed state during the swipe, cleared on end. */
    @Nullable private View mSwipePressedView;
    /** Finger-down start coordinates, used to measure horizontal travel. */
    private float mSwipeStartX;
    private float mSwipeStartY;
    private boolean mSwipeEngaged;
    /** Whether a finger is currently pressed on a suggestion row (DOWN without UP/CANCEL). */
    private boolean mSwipePointerDown = false;
    /** Cached touch slop (first access). */
    private int mSwipeTouchSlop = -1;

    @NonNull private final Context mContext;
    @NonNull private final Supplier<EditText> mInputFieldSupplier;
    @NonNull private final Runnable mRefreshCallback;
    /** Back-reference to set the controller's swipe auto-complete suppression guard (on engage). */
    @NonNull private final Runnable mSetSuppress;
    /** Back-reference to clear the controller's swipe auto-complete suppression guard (on end). */
    @NonNull private final Runnable mClearSuppress;

    AutoCompleteSwipeHandler(@NonNull Context context,
                             @NonNull Supplier<EditText> inputFieldSupplier,
                             @NonNull Runnable refreshCallback,
                             @NonNull Runnable setSuppress,
                             @NonNull Runnable clearSuppress) {
        mContext = context;
        mInputFieldSupplier = inputFieldSupplier;
        mRefreshCallback = refreshCallback;
        mSetSuppress = setSuppress;
        mClearSuppress = clearSuppress;
    }

    boolean isEngaged() {
        return mSwipeEngaged;
    }

    boolean isPointerDown() {
        return mSwipePointerDown;
    }

    /** Force-clear all per-gesture state (used when focus is lost / popup dismissed externally). */
    void resetIfEngaged() {
        if (mSwipeEngaged) resetSwipeState();
    }

    @Nullable
    private EditText field() {
        return mInputFieldSupplier.get();
    }

    boolean onTouch(@NonNull View v, @NonNull MotionEvent event) {
        final EditText inputField = field();
        if (inputField == null) return false;

        final int action = event.getActionMasked();
        try {
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                // Guard against a stale engaged state if a previous gesture's
                // UP/CANCEL was never delivered (e.g. app backgrounded mid-swipe).
                if (mSwipeEngaged) resetSwipeState();
                mSwipePointerDown = true;
                mSwipeStartX = event.getRawX();
                mSwipeStartY = event.getRawY();
                mSwipeEngaged = false;
                mSwipeWordsAdded = 0;

                if (mSwipeTouchSlop < 0) {
                    mSwipeTouchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
                }
                // ~24dp of travel per word — a light, responsive swipe.
                float density = mContext.getResources().getDisplayMetrics().density;
                mSwipeUnitWidthPx = Math.max(1, (int) (24 * density));

                Object tag = v.getTag();
                mSwipeSuggestion = (tag instanceof String) ? (String) tag : null;
                if (mSwipeSuggestion == null) return false;

                // The words to insert are the suggestion's remainder past the
                // already-typed prefix, so we never re-append what's already there.
                String base = inputField.getText() != null ? inputField.getText().toString() : "";
                mSwipeBaseText = base;
                boolean isPrefixMatch = !base.isEmpty()
                        && mSwipeSuggestion.length() >= base.length()
                        && mSwipeSuggestion.regionMatches(true, 0, base, 0, base.length());
                String remainder = computeRemainder(mSwipeSuggestion, base);
                mSwipeWords = splitShellWords(remainder);
                // Add a leading space before the first word only when the first
                // word starts a NEW token: i.e. the suggestion is NOT a prefix
                // continuation of what's typed (so the word doesn't complete a
                // half-typed token) AND the base doesn't already end in a
                // separator (space or '/').
                mSwipeNeedsLeadingSpace = !isPrefixMatch
                        && !base.isEmpty()
                        && !isWordSeparator(base.charAt(base.length() - 1));
                mSwipeAnchorStart = base.length();
                // Not consumed yet: allow a tap to become a click.
                return false;
            }

            case MotionEvent.ACTION_MOVE: {
                if (mSwipeWords == null || mSwipeWords.length == 0) {
                    // Single-word (or empty-remainder) suggestion: nothing to add.
                    return mSwipeEngaged;
                }
                float dx = event.getRawX() - mSwipeStartX;
                float dy = event.getRawY() - mSwipeStartY;
                if (!mSwipeEngaged) {
                    if (Math.abs(dx) <= mSwipeTouchSlop) return false;
                    // Ignore vertical-dominant drags so the popup can still scroll
                    // (and diagonal scroll gestures aren't hijacked as word swipes).
                    if (Math.abs(dy) > Math.abs(dx)) return false;
                    // Engage swipe mode and stop the popup ScrollView from stealing
                    // the gesture. Keep the row in the pressed state so it shows the
                    // exact same highlight as a tap for the whole duration of the swipe.
                    mSwipeEngaged = true;
                    // Suppress auto-complete during the gesture so the programmatic
                    // text edits (applyWordSelection) don't dismiss/refresh the popup
                    // behind the swipe. Set AFTER mSwipeEngaged so the two can't
                    // desync (engaged=true always implies suppress=true here).
                    mSetSuppress.run();
                    v.setPressed(true);
                    v.cancelLongPress();
                    mSwipePressedView = v;
                    mSwipeDisallowParent = v.getParent();
                    if (mSwipeDisallowParent != null) {
                        mSwipeDisallowParent.requestDisallowInterceptTouchEvent(true);
                    }
                    // The selection highlight only renders while the field is focused.
                    if (!inputField.isFocused()) inputField.requestFocus();
                }
                int target = (int) (dx / mSwipeUnitWidthPx);
                if (target < 0) target = 0;
                if (target > mSwipeWords.length) target = mSwipeWords.length;
                if (target != mSwipeWordsAdded) {
                    applyWordSelection(target);
                    mSwipeWordsAdded = target;
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                boolean wasEngaged = mSwipeEngaged;
                if (wasEngaged) {
                    // Finger lifted intentionally: collapse the selection to its
                    // end = commit the appended words.
                    int end = inputField.getSelectionEnd();
                    if (end < 0) end = inputField.length();
                    inputField.setSelection(end, end);
                }
                if (wasEngaged) {
                    // Do NOT run the heavy refresh synchronously from onTouch: rebuilding
                    // the popup (showAtLocation/rebuild/views) re-entrantly from the
                    // touch handler crashes. Clear the suppression guard and update the
                    // popup on the next looper step, AFTER the gesture has finished
                    // (the finally below correctly resets the state).
                    final EditText f = inputField;
                    f.post(() -> {
                        mClearSuppress.run();
                        mRefreshCallback.run();
                    });
                }
                // If engaged we consumed the gesture (suppress the click); otherwise
                // return false so a pure tap still triggers tap-to-insert.
                return wasEngaged;
            }

            case MotionEvent.ACTION_CANCEL: {
                boolean wasEngaged = mSwipeEngaged;
                if (wasEngaged) {
                    // Gesture cancelled (not a real lift): revert to the untouched
                    // base text so no words are left committed.
                    applyWordSelection(0);
                    int end = inputField.length();
                    inputField.setSelection(end, end);
                }
                if (wasEngaged) {
                    final EditText f = inputField;
                    f.post(() -> {
                        mClearSuppress.run();
                        mRefreshCallback.run();
                    });
                }
                return wasEngaged;
            }

            default:
                return mSwipeEngaged;
        }
        } finally {
            // Guarantee the per-gesture state is torn down (and the auto-complete
            // suppress guard lifted) on UP/CANCEL even if a branch threw. Without
            // this, a missing UP/CANCEL or an exception would leave mSwipeSuppressed
            // stuck true and silence auto-complete on every subsequent keystroke.
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mSwipePointerDown = false;
                resetSwipeState();
            }
        }
    }

    /**
     * The portion of {@code suggestion} still to insert past the already-typed {@code base}:
     * only the tail when {@code base} is a case-insensitive prefix of the suggestion (matching
     * the popup's own prefix logic), otherwise the whole suggestion.
     */
    @NonNull
    static String computeRemainder(@NonNull String suggestion, @NonNull String base) {
        if (!base.isEmpty()
                && suggestion.length() >= base.length()
                && suggestion.regionMatches(true, 0, base, 0, base.length())) {
            return suggestion.substring(base.length());
        }
        return suggestion;
    }

    /**
     * Split a remainder into "words" delimited by {@link #isWordSeparator}
     * (space and '/'), keeping each word's <b>trailing</b> separator so the
     * original spacing/path structure is restored exactly when re-appended.
     * A leading run of separators is folded into the first word's prefix.
     *
     * <p>Example: {@code "usr/bin/env"} → {@code ["usr/", "bin/", "env"]}
     * (no trailing separator fabricated for the last word);
     * {@code " commit -m"} → {@code [" commit ", "-m"]}.
     */
    @NonNull
    static String[] splitShellWords(@NonNull String s) {
        if (s.isEmpty()) return new String[0];
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        int i = 0;
        int n = s.length();
        // Fold any leading separators into the prefix of the first token so that
        // the boundary between the base text and the first word is preserved.
        int leadStart = i;
        while (i < n && isWordSeparator(s.charAt(i))) i++;
        String lead = s.substring(leadStart, i);
        while (i < n) {
            int wordStart = i;
            while (i < n && !isWordSeparator(s.charAt(i))) i++;
            // Include the run of trailing separators that actually followed this
            // word in the original suggestion (e.g. '/' for paths, one or more
            // spaces for arguments). Nothing is added if the word ended the
            // string, so a separator that wasn't in the original is never fabricated.
            while (i < n && isWordSeparator(s.charAt(i))) i++;
            String token = s.substring(wordStart, i);
            if (!lead.isEmpty()) {
                token = lead + token;
                lead = "";
            }
            out.add(token);
        }
        // A remainder consisting only of separators (rare) still yields the lead.
        if (!lead.isEmpty()) out.add(lead);
        return out.toArray(new String[0]);
    }

    /** True if {@code c} is one of the word separators (space or '/'). */
    static boolean isWordSeparator(char c) {
        return c == ' ' || c == '/';
    }

    /**
     * Rebuild the input field so that exactly {@code n} of {@link #mSwipeWords}
     * are appended after {@link #mSwipeBaseText}, then select the appended range.
     * Deterministic (recomputed from scratch each call) so rapid direction
     * reversals stay correct.
     */
    private void applyWordSelection(int n) {
        final EditText inputField = field();
        if (inputField == null || mSwipeWords == null || mSwipeBaseText == null) return;
        StringBuilder sb = new StringBuilder(mSwipeBaseText);
        for (int i = 0; i < n; i++) {
            if (i == 0 && mSwipeNeedsLeadingSpace) sb.append(' ');
            sb.append(mSwipeWords[i]);
        }
        String newText = sb.toString();
        Editable ed = inputField.getText();
        ed.replace(0, ed.length(), newText);
        int end = newText.length();
        int start = Math.min(mSwipeAnchorStart, end);
        inputField.setSelection(start, end);
    }

    /**
     * Clear all per-gesture swipe state. Lifts the auto-complete suppression guard
     * and the parent touch-interception block UNCONDITIONALLY (wrapped in
     * try/finally) so neither can leak past the end of a gesture, regardless of
     * which exit path ran (UP / CANCEL / forced reset on focus loss).
     */
    void resetSwipeState() {
        try {
            if (mSwipePressedView != null) {
                mSwipePressedView.setPressed(false);
                mSwipePressedView = null;
            }
            if (mSwipeDisallowParent != null) {
                mSwipeDisallowParent.requestDisallowInterceptTouchEvent(false);
                mSwipeDisallowParent = null;
            }
        } finally {
            // Always lift the suppress guard, even if the view bookkeeping above
            // threw — otherwise auto-complete would be permanently disabled.
            mClearSuppress.run();
            mSwipeEngaged = false;
            mSwipeWords = null;
            mSwipeBaseText = null;
            mSwipeSuggestion = null;
            mSwipeWordsAdded = 0;
            mSwipeNeedsLeadingSpace = false;
        }
    }
}
