package com.termux.app.terminal.io.autocomplete;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.termux.R;
import com.termux.app.terminal.TermuxColorSchemeManager;


/**
 * Self-contained controller that owns the entire message-history auto-complete
 * suggestion popup logic (formerly embedded in TermuxActivity).
 *
 * <p>It tracks the 3-way dispatch (full rescan / additive filter / reposition),
 * the incremental history-version optimization, the popup window, the suggestion
 * views, and history-add-on-submit. It does NOT delegate back to the Activity for
 * any of that — the Activity only wires it up (passing the EditText, the
 * {@link MessageHistoryController}, the {@link TermuxColorSchemeManager} and a
 * couple of optional callbacks) and may call its public entry points
 * ({@link #onTextChanged()}, {@link #dismiss()}, {@link #isShowing()},
 * {@link #onCaretMoved()}, …).
 *
 * <p>Candidate filtering is a single linear {@code regionMatches} scan over the
 * (capped) live history — the prefix trie that once served large histories was
 * removed because {@code message_history_max} can never exceed 100 via the UI, so
 * the trie could never be built and its linear path was already taken in practice.
 */
public final class AutoCompleteController implements AutoCompleteDataProvider {


    private final Context mContext;
    private EditText mInputField;
    private final MessageHistoryController mMessageHistoryCtrl;
    private final TermuxColorSchemeManager mColorSchemeManager;
    private final SharedPreferences mPrefs;

    /** Called when a suggestion is chosen, so the host can dismiss its message-history popup. */
    @NonNull private Runnable mMessageHistoryDismissListener = () -> {};

    /** True while the host is in an invalid state and auto-complete must be suppressed. */
    private boolean mIsInvalidState;

    // ── Auto-complete suggestions popup ──
    // The message-history suggestion candidates are shown in a single popup window
    // floating above the input field.
    //
    // Ownership split: this controller owns the SUGGESTION DATA and the
    // fetch/merge/input pipeline; {@link AutoCompletePopupManager} owns the
    // popup window and all of its rendering/positioning.
    /** Current list of suggestion strings being displayed (message history). */
    private final java.util.ArrayList<String> mCurrentSuggestions = new java.util.ArrayList<>();

    /**
     * Maximum number of suggestions to RENDER in the popup (the user setting
     * "suggestions_max_count").
     */
    private int mDisplayMax = 4;
    /** Suppress auto-complete popup during programmatic text changes (suggestion tap, etc.). */
    private boolean mSuppressAutoComplete;
    /** True while a session's saved input is being restored into the field (tab switch,
     *  panel re-show). Mutes every afterTextChanged event for that restore so the popup is
     *  not triggered by the programmatic setText(). Distinct from mSuppressAutoComplete so
     *  it does not interact with the swipe/compose guards or the live typing path. */
    private boolean mRestoringInput;
    /** Suppress auto-complete popup during an active swipe-to-select gesture. */
    private boolean mSwipeSuppressed;
    /** Force a full rescan (Path A) on the next update — set after a swipe commits
     *  arbitrary text, which the incremental additive filter cannot handle. */
    private boolean mForceFullRescan;

    /** Forced rescan flag set by {@link #onCaretMoved()} when the caret returns
     *  to the end of the text. Overrides the "text unchanged" early-skip guard
     *  in {@link #updateAutoCompleteSuggestions()}, which would otherwise prevent
     *  the popup from re-appearing after it was dismissed by a prior caret move. */
    private boolean mForceRescanOnNextUpdate;

    /** Last known caret position, used to detect actual caret movement
     *  (tap/reposition without text change) without relying on
     *  setOnSelectionChangedListener (API 29+). Updated from
     *  {@link #onCaretMoved()} and {@link #afterTextChanged(Editable)}. */
    private int mLastCaretPosition = -1;

    /** Reusable scratch set to avoid per-keystroke HashSet allocations in the hot path. */
    private final HashSet<String> mSeenSet = new HashSet<>();
    /**
     * P2: availWidth (popup width minus horizontal padding) is constant within a
     * single popup rebuild, so cache it across the per-row rebinds instead of
     * recomputing {@code computePopupWidth} (which reads display metrics) on every
     * one of up to {@code displayMax} rows. Invalidated when the input field width
     * changes.
     */
    private int mCachedFieldWidth = -1;
    private int mCachedAvailWidth = -1;

    // ── Incremental auto-complete optimization fields ──
    /** Previous text (CharSequence reference, no copy) before a change, for additive detection. */
    private CharSequence mAutoCompletePrevText = "";
    /** Cheap up-front signal: the pending change is an IME composition (after != before). */
    private boolean mComposingChangePending = false;
    /** Count of characters being replaced (from onTextChanged). */
    private int mAutoCompleteChangeBefore;
    /** Count of new characters being inserted (from onTextChanged). */
    private int mAutoCompleteChangeCount;

    /**
     * History version captured the last time the suggestion set was rebuilt.
     * Maintained by the rebuild paths ({@link #fullRescanSuggestions}, the
     * backspace path) so the early-skip guard and the additive-filter dispatch
     * (Path B) can detect that the in-memory set matches the live history.
     */
    private int mLastBuiltHistoryVersion = -1;

    /** Owns and renders the suggestion popup window. */
    @NonNull private final AutoCompletePopupManager mPopupManager;

    // ── IME composing coalescing (no delay on committed input) ──
    private final android.os.Handler mImeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable mComposingCoalesce = null;

    // ── Swipe-to-select gesture on suggestion items ──
    /**
     * Horizontal swipe on a suggestion row progressively appends the next
     * word(s) of that suggestion into a live text selection in the input field.
     * Swipe right adds words, swipe left removes them; the selection is dropped
     * (committed) when the finger is lifted. All per-gesture state lives in the
     * handler.
     */
    @NonNull private final AutoCompleteSwipeHandler mSwipeHandler;

    // ── Auto-complete popup dimensions (read once from resources) ──
    private final int mPopupCornerRadiusPx;
    private final int mPopupElevationPx;
    private final int mPopupItemPadHPx;
    private final int mPopupItemPadVPx;
    private final int mPopupMinWidthPx;
    private final int mPopupWidthMarginPx;
    private final float mPopupWidthFraction;
    private final int mPopupXOffsetPx;
    private final int mPopupEdgeMarginPx;
    private final int mPopupYOffsetPx;
    private final int mPopupMinYPx;
    private final float mPopupContentAlpha;
    private final float mPopupShadowAlpha;

    /**
     * @param context              the host Activity/Context (used for resources and the window).
     * @param inputField           the terminal toolbar EditText the suggestions are anchored to.
     * @param messageHistoryCtrl   the message-history controller (history list + version + add/save/load).
     * @param colorSchemeManager   the colour-scheme manager that vends popup colours.
     */
    public AutoCompleteController(@NonNull Context context, @NonNull EditText inputField,
            @NonNull MessageHistoryController messageHistoryCtrl,
            @NonNull TermuxColorSchemeManager colorSchemeManager) {
        mContext = context;
        mInputField = inputField;
        mMessageHistoryCtrl = messageHistoryCtrl;
        mColorSchemeManager = colorSchemeManager;
        mPrefs = context.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        int maxCount = mPrefs.getInt("suggestions_max_count", 4);
        if (maxCount < 0) maxCount = 0;
        if (maxCount > 10) maxCount = 10;
        mDisplayMax = maxCount;
        Resources res = context.getResources();
        mPopupCornerRadiusPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_corner_radius);
        mPopupElevationPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_elevation);
        mPopupItemPadHPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_item_padding_horizontal);
        mPopupItemPadVPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_item_padding_vertical);
        mPopupMinWidthPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_min_width);
        mPopupWidthMarginPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_width_margin);
        mPopupWidthFraction = res.getFraction(R.fraction.autocomplete_popup_width_fraction, 1, 1);
        mPopupXOffsetPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_x_offset);
        mPopupEdgeMarginPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_edge_margin);
        mPopupYOffsetPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_y_offset);
        mPopupMinYPx = res.getDimensionPixelSize(R.dimen.autocomplete_popup_min_y);
        mPopupContentAlpha = res.getFraction(R.fraction.autocomplete_popup_content_alpha, 1, 1);
        mPopupShadowAlpha = res.getFraction(R.fraction.autocomplete_popup_shadow_alpha, 1, 1);
        // Build the popup-window manager, handing it the pre-read resource dimensions.
        mPopupManager = new AutoCompletePopupManager(context, this, colorSchemeManager,
                mPopupCornerRadiusPx, mPopupElevationPx,
                mPopupItemPadHPx, mPopupItemPadVPx,
                mPopupMinWidthPx, mPopupWidthMarginPx,
                mPopupWidthFraction, mPopupXOffsetPx,
                mPopupEdgeMarginPx, mPopupYOffsetPx,
                mPopupMinYPx, mPopupContentAlpha, mPopupShadowAlpha);
        // Swipe-to-select gesture handler: needs the live input field (it may be
        // swapped by tests) plus callbacks to refresh suggestions and to set/clear
        // the auto-complete suppression guard on gesture start/end. The swipe uses
        // its OWN suppress flag (distinct from the tap-to-insert guard) so a tap
        // cannot accidentally clear a swipe's suppression and vice-versa.
        mSwipeHandler = new AutoCompleteSwipeHandler(mContext,
                () -> mInputField,
                this::refreshAfterSwipe,
                () -> setSwipeSuppress(true),
                () -> setSwipeSuppress(false));
        attachInputListeners();
    }

    // ── AutoCompleteDataProvider: expose suggestion data + callbacks to the popup manager ──

    @Override @NonNull public ArrayList<String> getSuggestions() { return mCurrentSuggestions; }
    @Override public int getDisplayMax() { return mDisplayMax; }
    @Override public boolean isSwipeActive() { return mSwipeHandler.isEngaged(); }
    @Override @Nullable public EditText getInputField() { return mInputField; }
    @Override @NonNull public TermuxColorSchemeManager getColorSchemeManager() { return mColorSchemeManager; }
    @Override @Nullable public Window getWindow() { return getWindowInternal(); }
    @Override @NonNull public TextView buildSuggestionTextView(@NonNull String suggestion, @NonNull String input) {
        return buildSuggestionTextViewInternal(suggestion, input);
    }
    @Override public void rebindSuggestionTextView(@NonNull TextView tv, @NonNull String suggestion, @NonNull String input) {
        rebindSuggestionTextViewInternal(tv, suggestion, input);
    }
    @Override public int getHistoryVersion() { return mMessageHistoryCtrl.getHistoryVersion(); }
    @Override public void onSuggestionDismissed() {
        // Clear the suggestion data the controller owns.
        mCurrentSuggestions.clear();
    }

    // ── Wiring callbacks (optional) ───────────────────

    public void setMessageHistoryDismissListener(@NonNull Runnable listener) {
        mMessageHistoryDismissListener = listener;
    }

    public void setInvalidState(boolean invalid) {
        mIsInvalidState = invalid;
    }

    /** Test-only: current merged suggestion list (history only). */
    java.util.ArrayList<String> debugSuggestions() {
        return mCurrentSuggestions;
    }

    /** Test-only: install the text the dispatcher believes preceded this change. */
    void debugSetPrevText(@NonNull String prev) {
        mAutoCompletePrevText = prev;
    }

    /** Test-only: install the change-count (new chars inserted) the dispatcher expects. */
    void debugSetChangeCount(int count) {
        mAutoCompleteChangeCount = count;
    }

    /** Test-only: install the before-count (chars replaced) the dispatcher expects. */
    void debugSetChangeBefore(int before) {
        mAutoCompleteChangeBefore = before;
    }

    /** Test-only: total number of suggestions that will be rendered (capped by maxCount). */
    int debugDisplayCount() {
        return Math.min(mCurrentSuggestions.size(), mDisplayMax);
    }

    /**
     * Release resources. Call from the host's lifecycle teardown.
     */
    public void destroy() {
        mSwipeHandler.resetIfEngaged();
        mSwipeSuppressed = false;
        mSuppressAutoComplete = false;
        if (mComposingCoalesce != null) { mImeHandler.removeCallbacks(mComposingCoalesce); mComposingCoalesce = null; }
    }

    public void setSuppressAutoComplete(boolean suppress) {
        mSuppressAutoComplete = suppress;
    }

    /**
     * Mute all text-change handling while a session's saved input is restored into the
     * field via a programmatic setText(). Set true before setText() and false after.
     *
     * <p>While true, afterTextChanged returns immediately, so no recompute (sync or
     * deferred) is ever queued for the restore — the popup stays dismissed for the
     * restored line and live typing afterwards is unaffected. Crucially this must NOT
     * cancel any pending recompute from real user input (e.g. a backspace taken on the
     * previous tab): the restore's own setText() never reaches the coalesce/post path
     * because it is muted first, so there is nothing of ours to drop, and dropping a
     * user's pending recompute would make the popup appear frozen.
     */
    public void setRestoringInput(boolean restoring) {
        mRestoringInput = restoring;
    }

    /** Set/clear the swipe-gesture auto-complete suppression guard (distinct from the tap guard). */
    void setSwipeSuppress(boolean suppress) {
        mSwipeSuppressed = suppress;
    }

    /** Force a history-version mismatch so the next update takes Path A (full rescan). */
    public void invalidateHistoryVersion() {
        mLastBuiltHistoryVersion = -1;
    }

    // ── Public entry points ───────────────────────────

    /** Called from the host's focus change handler when the input field loses focus. */
    public void onInputFocusLost() {
        // A lost focus mid-gesture means the swipe can never complete; clear its
        // suppression guard so auto-complete is not permanently disabled.
        mSwipeHandler.resetIfEngaged();
        mSwipeSuppressed = false;
        dismissAutoCompleteSuggestions();
    }

    /** Called when the caret position changes without a text change
     *  (e.g. tapping a different position or using arrow keys).
     *  Delegates to {@link #updateAutoCompleteSuggestions()} which
     *  checks the caret position and either shows or dismisses the
     *  popup accordingly. When the caret is at the end and the popup
     *  was previously dismissed, sets {@link #mForceRescanOnNextUpdate}
     *  to bypass the "text unchanged" early-skip guard. */
    public void onCaretMoved() {
        if (mInputField == null) return;
        int newCaret = mInputField.getSelectionStart();
        int textLen = mInputField.getText().length();
        mLastCaretPosition = newCaret;
        if (newCaret >= 0 && newCaret == textLen && !isShowing()) {
            mForceRescanOnNextUpdate = true;
        }
        updateAutoCompleteSuggestions();
    }

    /** Triggered by the host when the input text changes (compatibility entry point). */
    public void onTextChanged() {
        updateAutoCompleteSuggestions();
    }

    /** Dismiss and clear the suggestions popup. */
    public void dismiss() {
        mSwipeHandler.resetIfEngaged();
        mSwipeSuppressed = false;
        dismissAutoCompleteSuggestions();
    }

    /** Whether either the shell or the history suggestion window is currently showing. */
    public boolean isShowing() {
        return mPopupManager.isShowing();
    }

    // ── Internal: listeners ───────────────────────────

    private void attachInputListeners() {
        mInputField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // Snapshot the pre-edit text as an immutable String — but ONLY when the
                // change can actually reach a recompute. The EditText's text is a live,
                // mutable Editable shared by reference with s; a toString() copy freezes
                // the pre-edit value so the later length-delta / prefix regionMatches
                // comparisons (additive vs backspace detection) stay correct.
                //
                // P0: while the popup is suppressed / input is being restored / a swipe
                // guard is latched, the matching afterTextChanged branch bails out WITHOUT
                // recomputing, so the snapshot would be wasted work (a full copy of the
                // entire input string on every keystroke). In those cases we mark prevText
                // invalid (null) instead; updateAutoCompleteSuggestions then forces a full
                // Path A rescan for null prevText — the same safe fallback it already uses
                // for an empty suggestion list. The composing signal below is still always
                // captured, since it gates the deferred-compose path independently.
                if (mRestoringInput || mSuppressAutoComplete || mSwipeSuppressed) {
                    mAutoCompletePrevText = null;
                } else {
                    mAutoCompletePrevText = s == null ? "" : s.toString();
                }
                // Cheap composing signal, captured in advance. after!=count means a
                // range replace/insert/delete (composition, paste, autocorrect,
                // delete) — i.e. NOT a clean committed character (where after==count==1).
                // Committed input is synchronous and instant; for composing events
                // afterTextChanged collapses the chain via mImeHandler.post (NO timer).
                // The hasComposingSpan span-scan is thus never called on the hot path
                // of a committed character.
                mComposingChangePending = (after - count) != 0;
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                mAutoCompleteChangeBefore = before;
                mAutoCompleteChangeCount = count;
            }
            @Override
            public void afterTextChanged(android.text.Editable s) {
                // A session's saved input is being restored into the field (tab switch /
                // panel re-show). Mute the whole event so the popup stays dismissed for the
                // restored line and no recompute is deferred.
                if (mRestoringInput) return;
                // Safety net against a "stuck" swipe suppression: if the gesture's
                // UP/CANCEL was never delivered (e.g. app backgrounded mid-swipe) the
                // suppress guard would stay latched and auto-complete would stay
                // silent on every keystroke. Clear the guard whenever a swipe is no
                // longer genuinely in progress (engaged=false AND pointerDown=false
                // covers a dead gesture whose UP/CANCEL was lost). Without the
                // isPointerDown() check, a live swipe mid-gesture would be incorrectly
                // force-cleared just because an unrelated text change arrived.
                if (mSwipeSuppressed && !mSwipeHandler.isEngaged() && !mSwipeHandler.isPointerDown()) {
                    mSwipeHandler.resetIfEngaged();
                }
                final EditText f = mInputField;
                // P2: scan the composing span set ONCE for this input event and reuse
                // it below (and inside the deferred compose runnable) instead of
                // allocating a fresh Object[] on every branch. hasComposingSpan()
                // walks all spans of the editable.
                final boolean composingAtEvent = f != null && hasComposingSpan(f);
                // A "composing" change (after != count) is ambiguous: it is true for BOTH
                // real IME composition AND for plain delete/insert/paste of a range.
                //
                // CRITICAL: any DELETION (before > after, i.e. characters removed) must be
                // recomputed SYNCHRONOUSLY, never deferred. During IME composition the
                // composing span is still attached to the (now shorter) word, so
                // hasComposingSpan() returns true for a backspace — and deferring it to a
                // posted run makes updateAutoCompleteSuggestions() hit the caret/composition
                // bounce and dismiss or freeze the popup (the reported "popup stops updating
                // on backspace" bug). So we only take the deferred compose path when this is
                // a genuine additive composition (after > before AND a composing span is
                // present). A pure deletion is always treated as a committed edit.
                boolean isDeletion = mComposingChangePending && (mAutoCompleteChangeBefore > mAutoCompleteChangeCount);
                boolean realCompose = f != null && mComposingChangePending && !isDeletion && composingAtEvent;
                if (realCompose) {
                    if (mComposingCoalesce != null) mImeHandler.removeCallbacks(mComposingCoalesce);
                    final CharSequence snapshotPrev = mAutoCompletePrevText;
                    mComposingCoalesce = () -> {
                        mComposingCoalesce = null;
                        if (mSuppressAutoComplete || mSwipeSuppressed) return;
                        // Guard against stale deferred update: the user may have moved the
                        // caret away from end during the deferred window. Without this check,
                        // a delayed composing update could re-show the popup after onCaretMoved()
                        // already dismissed it (race described as Bug #1 in the analysis).
                        if (mInputField != null && !composingAtEvent) {
                            int c = mInputField.getSelectionStart();
                            if (c >= 0 && c != mInputField.getText().length()) return;
                        }
                        updateAutoCompleteSuggestions();
                    };
                    mImeHandler.post(mComposingCoalesce); // collapses the compose chain into 1 recompute, NO timer
                    return;
                }
                if (mComposingCoalesce != null) { mImeHandler.removeCallbacks(mComposingCoalesce); mComposingCoalesce = null; }
                updateAutoCompleteSuggestions(); // a committed character (or a backspace) is computed instantly
                // Sync last known caret after any text change — the caret
                // typically moves to the end of the new text.
                if (mInputField != null) {
                    mLastCaretPosition = mInputField.getSelectionStart();
                }
            }
        });

        // Detect caret-move taps on the EditText. We use ACTION_UP on the
        // EditText because setOnSelectionChangedListener is API 29+ and the
        // project targets API 21. We guard by comparing against the last known
        // caret position so that tapping in the same spot is a no-op.
        mInputField.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                v.post(() -> {
                    if (mInputField == null) return;
                    int newCaret = mInputField.getSelectionStart();
                    if (newCaret < 0 || newCaret == mLastCaretPosition) return;
                    mLastCaretPosition = newCaret;
                    onCaretMoved();
                });
            }
            return false; // don't consume the event, let the EditText handle it
        });

        // Arrow-key navigation also moves the caret without text changes.
        // OnKeyListener fires once per key-up; we call onCaretMoved() which
        // checks the current caret position against the text length.
        // DPAD_UP/DPAD_DOWN are included because in multi-line fields the caret
        // can move to a non-end position without a text change (Bug #4 fix).
        mInputField.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP
                    && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                        || keyCode == KeyEvent.KEYCODE_DPAD_UP
                        || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                onCaretMoved();
            }
            return false; // don't consume, let the EditText handle navigation
        });
    }

    @Nullable
    private Window getWindowInternal() {
        if (mContext instanceof Activity) return ((Activity) mContext).getWindow();
        return null;
    }

    /**
     * True when the input field's text currently carries an IME composing span.
     * Detected via the public {@link android.text.Spanned#SPAN_COMPOSING} flag
     * (the hidden {@code android.text.style.ComposingSpan} class is not part of
     * the public SDK, so we inspect span flags instead). During composition the
     * reported caret may transiently drift off the text end, so callers use this
     * to avoid a spurious dismiss while the user is still additively typing.
     */
    static boolean hasComposingSpan(@Nullable EditText inputField) {
        if (inputField == null) return false;
        CharSequence cs = inputField.getText();
        if (!(cs instanceof android.text.Spanned)) return false;
        android.text.Spanned sp = (android.text.Spanned) cs;
        Object[] spans = sp.getSpans(0, sp.length(), Object.class);
        for (Object span : spans) {
            if ((sp.getSpanFlags(span) & android.text.Spanned.SPAN_COMPOSING) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Three-way dispatcher for the auto-complete popup:
     *
     * Path A (full rebuild) — called when the user deletes, replaces, pastes, or the
     * history has changed externally.  Re-scans the full mMessageHistoryCtrl.getHistoryList() and (re)shows the
     * suggestion popup, reusing the existing PopupWindow when one is already live.
     *
     * Path B (additive filter) — called when the user only types more characters without
     * deleting any text.  Filters mCurrentSuggestions in place (O(maxCount) instead of
     * O(mMessageHistoryCtrl.getHistoryList())), removes non-matching views from the existing popup, top-ups
     * from history if the result is smaller than maxCount, recalculates bold spans, and
     * updates the popup size/position (one IPC instead of two).
     *
     * Path C (reposition only) — not truly a separate path here; when the text hasn't
     * changed w.r.t. the previous call the OnGlobalLayoutListener and OnTouchListener
     * already call repositionAutoCompletePopup() separately.  The dispatcher here always
     * receives a text-change event.
     */

    /**
     * Called after a swipe-to-select gesture commits arbitrary text into the input
     * field. The incremental additive filter cannot handle the resulting text (it is
     * not a pure prefix extension of the pre-swipe line), so we force a full rescan
     * on the next dispatch and run it off the touch path (the caller posts this).
     */
    private void refreshAfterSwipe() {
        mForceFullRescan = true;
        updateAutoCompleteSuggestions();
    }

    private void updateAutoCompleteSuggestions() {
        if (mIsInvalidState) {
            return;
        }
        // Safety net: force-clear stuck swipe suppression if the gesture ended
        // without delivering UP/CANCEL (e.g. app backgrounded mid-swipe).
        if (mSwipeSuppressed && !mSwipeHandler.isEngaged() && !mSwipeHandler.isPointerDown()) {
            mSwipeHandler.resetIfEngaged();
        }
        if (mSuppressAutoComplete || mSwipeSuppressed) {
            return;
        }

        final EditText inputField = mInputField;
        if (inputField == null) {
            return;
        }

        // P2: scan the composing span set ONCE per update and reuse it below
        // (caret-bounce guard + full-rescan) instead of re-scanning on every
        // branch. hasComposingSpan allocates an Object[] each call.
        final boolean composing = hasComposingSpan(inputField);

        final String text = inputField.getText().toString();

        if (TextUtils.isEmpty(text)) {
            dismissAutoCompleteSuggestions();
            return;
        }

        // A deletion (backspace / range delete) is detected by comparing against the
        // pre-edit snapshot. During IME composition the reported caret can transiently
        // sit INSIDE the still-attached composing span (caret != text.length()) even
        // right after a backspace, which would otherwise send us into the caret bounce
        // below and dismiss/freeze the popup. For a deletion we must NOT bounce: the
        // correct behaviour is to shorten the filter and refresh. We accept the deletion
        // whenever the caret (or the selection end) is at the end of the shortened text,
        // which is the normal end-of-line backspace case.
        final CharSequence prevSnapshot = mAutoCompletePrevText;
        final boolean deletion = prevSnapshot != null && prevSnapshot.length() > text.length()
                && TextUtils.regionMatches(prevSnapshot, 0, text, 0, text.length());

        // Only show auto-complete when the caret sits at the end of the input field.
        // When the caret is anywhere else the contextual popup must stay hidden —
        // UNLESS a swipe-to-select gesture is in progress (the gesture deliberately
        // holds a selection inside the text; dismissing mid-swipe would defeat it).
        int caret = inputField.getSelectionStart();
        boolean caretAtEnd = (caret == text.length())
                || (deletion && inputField.getSelectionEnd() == text.length());
        if (caret < 0 || !caretAtEnd) {
            // Do NOT dismiss while an IME composition is in progress: during compose
            // the reported selection can transiently sit inside the composing span
            // (caret != length) even though the user is still additively typing.
            // Treat it like an active swipe — keep the popup and bail out.
            if (isSwipeActive()) {
                return;
            }
            if (composing) {
                if (deletion) {
                    // A backspace during composition: do NOT bounce to bold-only and do
                    // NOT dismiss. Fall through so the deletion handler (below) shortens
                    // the filter and refreshes the popup. Dismissing here is exactly the
                    // "popup stops updating on backspace" bug.
                } else {
                    // Only keep the popup (bold-only refresh, no rebuild/dismiss) when the
                    // shown suggestions still actually match the composing text. A
                    // glide/swipe-typed word that matches no suggestion can leave the
                    // composing span attached until the next word is typed; in that case
                    // the stale, mismatched popup must NOT be kept — fall through so the
                    // recompute below dismisses it (fullRescanSuggestions also refuses to
                    // keep an empty, non-matching result while composing).
                    if (isShowing() && suggestionsMatchText(text)) {
                        return; // popup shows correct suggestions, no bold update needed
                    }
                    // Not a matching composition: do not bail here — let the code below
                    // re-scan and dismiss the now-irrelevant popup.
                }
            }
            dismissAutoCompleteSuggestions();
            return;
        }

        int maxCount = mDisplayMax;
        if (maxCount == 0) {
            dismissAutoCompleteSuggestions();
            return;
        }

        // ── Early skip for IME re-compose of same text ──
        // Gboard (and likely other IMEs) re-composes an unchanged composing span
        // on certain interactions (e.g. after tapping a suggestion candidate in
        // the IME's own bar). The text is identical to prevText, so the additive
        // detection correctly says false (length didn't grow), but Path A would
        // re-scan history and rebuild the popup unnecessarily. Skip the entire
        // update when text is unchanged, the history version is current, the
        // popup is already showing, AND the shown suggestions still actually
        // match the typed text. The last clause is essential: a glide/swipe-typed
        // commit can replace the line with a word matching no suggestion while
        // prevText was rewritten to the same committed text — without the match
        // check the stale, mismatched popup would be kept until the next edit.
        CharSequence prevText = mAutoCompletePrevText;
        boolean unchanged = text.equals(prevText) && mAutoCompleteChangeCount == mAutoCompleteChangeBefore;
        if (unchanged && !mForceRescanOnNextUpdate) {
            if (mMessageHistoryCtrl.getHistoryVersion() == mLastBuiltHistoryVersion
                    && isShowing()
                    && !mCurrentSuggestions.isEmpty()
                    && suggestionsMatchText(text)) {
                return;
            }
        }
        mForceRescanOnNextUpdate = false;

        // ── Detect whether this is an additive (append-only) change ──
        // Language/IME-agnostic: the new text must extend prevText by appending
        // (prevText stays a prefix and text grew). prevText is null when the
        // pre-edit snapshot was skipped (suppressed / restoring / swipe-guarded);
        // in that case treat the change as non-additive so the safe Path A full
        // rescan runs. We do NOT require before == 0,
        // because IME composition (e.g. Gboard Cyrillic) replaces the composing span
        // on every keystroke (before > 0), which made the old check take Path A on
        // each Cyrillic char. The non-empty list guard forces Path A to bootstrap on
        // the first keystroke (Path B on an empty list would wrongly dismiss).
        boolean additive = false;
        if (prevText != null && text.length() > prevText.length()
                && TextUtils.regionMatches(text, 0, prevText, 0, prevText.length())
                && !mCurrentSuggestions.isEmpty()) {
            additive = true; // clean append of characters at the end
        }
        // After a swipe the text changes arbitrarily (not a clean prefix append),
        // so the incremental filter is inapplicable — force a full rescan.
        if (mForceFullRescan) {
            additive = false;
            mForceFullRescan = false;
        }

        // ── Backspace: lightweight path without a full rescan ──
        // If the text is shorter than prevText and prevText starts with text
        // (characters deleted from the end), re-derive the list from the prefix
        // trie instead of taking Path A.
        //
        // CRITICAL: we must NOT just filter mCurrentSuggestions by the new
        // shorter prefix. mCurrentSuggestions is a top-N cache for the LONGER
        // previous prefix (Path B already narrowed and capped it), so filtering
        // it can only shrink it further — the popup would never expand back to
        // the full candidate set after a backspace. The trie is the single
        // source of truth for every prefix: a descent for the new shorter
        // prefix costs O(prefix length) and returns the full, newest-first
        // candidate list, so re-deriving from it is both correct AND cheaper
        // than a full history rescan (the trie is rebuilt only when the
        // history version changes, and no history list scan happens here).
        if (!additive && mCurrentSuggestions != null && !mCurrentSuggestions.isEmpty()
                && prevText != null && prevText.length() > text.length()
                && TextUtils.regionMatches(prevText, 0, text, 0, text.length())) {
            mCurrentSuggestions.clear();
            mSeenSet.clear(); // full re-derive: no pre-existing entries to skip
            collectPrefixCandidates(text, maxCount, mCurrentSuggestions);
            if (mCurrentSuggestions.isEmpty()) {
                // dropped to 0 — full rescan
            } else {
                mLastBuiltHistoryVersion = mMessageHistoryCtrl.getHistoryVersion();
                updatePopupContent(text, inputField);
                return;
            }
        }

        // ── Path A (full rebuild): not additive OR history changed externally ──
        if (!additive || mMessageHistoryCtrl.getHistoryVersion() != mLastBuiltHistoryVersion) {
            fullRescanSuggestions(text, maxCount, composing);
            return;
        }

        // ── Path B (additive filter): only appending characters ──
        // Filter mCurrentSuggestions in-place: drop entries that no longer have
        // the (grown) typed text as a prefix. The candidates for the new prefix
        // are a subset of the old ones (same newest-first order), so the local
        // filter is equivalent to a full rescan — and costs O(maxCount).
        final int preFilterCount = mCurrentSuggestions.size();
        filterSuggestionsByPrefix(text, mCurrentSuggestions);
        int filteredRemoved = preFilterCount - mCurrentSuggestions.size();

        if (mCurrentSuggestions.isEmpty()) {
            // The additive filter emptied the list. This happens when there was
            // nothing to filter to begin with (e.g. the very first keystroke after
            // the field was cleared, so mCurrentSuggestions is still empty rather
            // than a previously-shown popup being filtered down). A plain dismiss
            // here would silently drop a legitimate character. Fall back to a full
            // history rescan: it shows suggestions if any match, otherwise it still
            // dismisses correctly.
            fullRescanSuggestions(text, maxCount, composing);
            return;
        }

        // Top-up from history if the filtered list is smaller than maxCount.
        if (mCurrentSuggestions.size() < maxCount) {
            mSeenSet.clear();
            mSeenSet.addAll(mCurrentSuggestions);
            collectPrefixCandidates(text, maxCount, mCurrentSuggestions);
        }

        // The set now matches the live history version (Path B never consults
        // stale data: a version change dispatches to Path A above).
        mLastBuiltHistoryVersion = mMessageHistoryCtrl.getHistoryVersion();


        // If neither window is showing yet, build them fresh
        if (!isShowing()) {
            showAutoCompletePopup(inputField);
            return;
        }

        // In-place update of the existing popup content
        updatePopupContent(text, inputField);
    }

    /**
     * Collect at most {@code maxCount} history candidates matching {@code text}
     * (case-insensitive whole-line prefix, excluding the exact typed line) into
     * {@code out}, preserving newest-first order. Serves the backspace re-derive
     * path and the Path B top-up.
     *
     * <p>A single linear {@code regionMatches} scan over the live list is used.
     * The history size is capped at {@code message_history_max} (default 20, UI
     * slider max 100), so this is at most ~100 short-prefix comparisons —
     * sub-microsecond and byte-for-byte identical in result to the old
     * prefix-trie path, which could never be built at those sizes anyway (it
     * required {@code TRIE_MIN_HISTORY}=128). The trie and all its plumbing were
     * removed (see analysis P1).
     *
     * <p>{@link #mSeenSet} is consulted (never cleared here) so a top-up caller
     * that preloads it with the surviving suggestions does not re-add them.
     * The backspace caller must clear it first (the list was just emptied).
     */
    private void collectPrefixCandidates(@NonNull String text, int maxCount,
            @NonNull ArrayList<String> out) {
        final int tLen = text.length();
        final ArrayList<String> historyList = mMessageHistoryCtrl.getHistoryList();
        for (int i = 0; i < historyList.size(); i++) {
            String msg = historyList.get(i);
            if (msg == null || msg.length() <= tLen) continue;
            if (mSeenSet.add(msg)
                    && !msg.equals(text) && msg.regionMatches(true, 0, text, 0, tLen)) {
                out.add(msg);
                if (out.size() >= maxCount) break;
            }
        }
    }

    /**
     * Path A: full re-scan of the message history. History suggestions are gathered
     * immediately and the popup is shown (or dismissed if empty).
     *
     * @param composing whether an IME composing span is currently active on the
     *                  input field (computed once by the caller and passed in to
     *                  avoid re-scanning the span set on every call).
     */
    private void fullRescanSuggestions(@NonNull String text, int maxCount, boolean composing) {
        // The user's max setting governs how many items are RENDERED.
        mDisplayMax = maxCount;

        mCurrentSuggestions.clear();

        // Linear scan over the live history (capped at message_history_max, default
        // 20 / UI max 100 — trivially cheap and allocation-free).
        final ArrayList<String> historyList = mMessageHistoryCtrl.getHistoryList();
        final int tLen = text.length();
        for (int i = 0; i < historyList.size(); i++) {
            String msg = historyList.get(i);
            if (msg == null || msg.length() <= tLen) continue;
            if (!msg.equals(text) && msg.regionMatches(true, 0, text, 0, tLen)) {
                mCurrentSuggestions.add(msg);
                if (mCurrentSuggestions.size() >= maxCount) break;
            }
        }

        // The rebuilt set now reflects the live history version. Without this,
        // every keystroke would take Path A instead of the incremental filter.
        mLastBuiltHistoryVersion = mMessageHistoryCtrl.getHistoryVersion();

        if (mCurrentSuggestions.isEmpty()) {
            // During an active composition keep the popup only when it is still
            // showing suggestions that match what the user typed — otherwise a
            // glide/swipe-typed word matching nothing would leave the stale popup
            // stuck until the next edit. If composition is active but the typed
            // text no longer matches the (previously shown) suggestions, dismiss.
            if (composing && suggestionsMatchText(text)) {
                return; // genuinely composing a matching prefix — wait for commit
            }
            dismissAutoCompleteSuggestions();
        } else {
            showAutoCompletePopup(mInputField);
        }
    }

    /**
     * True only when every currently shown suggestion still has {@code text} as a
     * prefix (case-insensitive, matching the prefix logic used elsewhere for
     * filtering). Used by the early-skip guard and the composition dismiss-guard so
     * a popup is kept across an unchanged-text recompose ONLY when it is actually
     * relevant to what the user typed. A glide/swipe commit that leaves prevText
     * equal to the committed word but with stale suggestions must NOT pass this
     * check, which forces a re-scan that dismisses the mismatched popup.
     */
    private boolean suggestionsMatchText(@NonNull String text) {
        if (mCurrentSuggestions.isEmpty()) return false;
        final int len = text.length();
        if (len == 0) return false;
        for (int i = 0; i < mCurrentSuggestions.size(); i++) {
            final String s = mCurrentSuggestions.get(i);
            if (s == null || s.length() < len || !s.regionMatches(true, 0, text, 0, len)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Unified local filter used by every additive (Path B) update. Mutates
     * {@code sugg} in place, dropping entries that no longer match the typed
     * text. History candidates are matched against the WHOLE line. A trailing
     * slash on the typed prefix also accepts candidates equal to the prefix
     * minus that slash.
     */
    void filterSuggestionsByPrefix(@NonNull String text,
            @NonNull List<String> sugg) {
        int tLen = text.length();
        // P2: the trailing-slash rule is invariant across the whole list — hoist
        // the endsWith test out of the per-row loop.
        boolean textEndsWithSlash = text.endsWith("/");
        for (int i = sugg.size() - 1; i >= 0; i--) {
            String s = sugg.get(i);
            int pLen = tLen;
            boolean matches = (pLen == 0)
                    || (s.regionMatches(true, 0, text, 0, pLen)
                        || (textEndsWithSlash
                            && s.regionMatches(true, 0, text, 0, pLen - 1)));
            if (s.length() <= pLen || !matches || s.equals(text)) {
                sugg.remove(i);
            }
        }
    }

    /**
     * Path B: update the existing popup in-place after an additive text change.
     *
     * <p>The controller first top-ups the suggestion list from history (data
     * concern), then hands off to {@link AutoCompletePopupManager} which rebuilds
     * the per-window content, refreshes spans and resizes/positions the
     * popup window.
     */
    private void updatePopupContent(@NonNull String newText, @NonNull EditText inputField) {
        // mCurrentSuggestions is already filtered and top-upped (Path B's
        // filterSuggestionsByPrefix + top-up in updateAutoCompleteSuggestions).
        // Only refresh the popup (mDisplayMax already holds the render cap).
        mPopupManager.update(newText, inputField);
    }

    /**
     * Number of suggestions to actually render (capped at the user's max setting),
     * even though {@link #mCurrentSuggestions} may store more candidates for
     * Path B local filtering.
     */
    // ── Package-private debug hooks for unit tests ──
    // (debugSuggestions is declared earlier.)

    /** Current incremental-change field used by the additive-detection logic. */
    void debugSetChangeState(@NonNull String prevText, int changeCount) {
        mAutoCompletePrevText = prevText;
        mAutoCompleteChangeCount = changeCount;
        mAutoCompleteChangeBefore = 0;
    }

    /**
     * Seed the current suggestion list with history items. Lets merge tests
     * populate the controller state without going through the popup-building
     * code paths.
     */
    void debugSeedHistorySuggestions(@NonNull String... history) {
        mCurrentSuggestions.clear();
        for (String h : history) {
            mCurrentSuggestions.add(h);
        }
    }

    /**
     * Build a single suggestion TextView (reusable helper).
     *
     * <p>Rows are only CREATED here — per-keystroke updates go through
     * {@link #rebindSuggestionTextViewInternal}, which never touches the
     * background. The pressed-state selector is therefore allocated once per
     * row (max {@code displayMax} per popup session) and must stay per-view:
     * a Drawable instance shared across views would make one pressed row
     * highlight them all.
     */
    private TextView buildSuggestionTextViewInternal(@NonNull String suggestion, @NonNull String input) {
        TextView tv = new TextView(mContext);
        styleSuggestionTextView(tv);
        // Solid press highlight matching the message-history popup (per-theme)
        StateListDrawable sel = new StateListDrawable();
        sel.addState(new int[]{android.R.attr.state_pressed},
                new ColorDrawable(mColorSchemeManager.getHistoryHighlightFill()));
        sel.addState(new int[]{},
                new ColorDrawable(Color.TRANSPARENT));
        sel.setEnterFadeDuration(0);
        sel.setExitFadeDuration(0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tv.setBackground(sel);
        } else {
            tv.setBackgroundDrawable(sel);
        }
        tv.setClickable(true);
        // Read the candidate from the view's TAG at click time, NOT from the
        // captured `suggestion` argument: this row is REBOUND to different
        // suggestions on every keystroke (see rebindSuggestionTextViewInternal),
        // so the captured value can be stale — inserting the wrong history entry.
        tv.setOnClickListener(v -> {
            Object tag = v.getTag();
            if (!(tag instanceof String)) return;
            mSuppressAutoComplete = true;
            try {
                if (mInputField != null) {
                    insertCandidate((String) tag);
                }
            } finally {
                mSuppressAutoComplete = false;
            }
            invalidateHistoryVersion();
            dismissAutoCompleteSuggestions();
            mMessageHistoryDismissListener.run();
        });
        tv.setOnTouchListener(mSwipeHandler::onTouch);
        rebindSuggestionTextViewInternal(tv, suggestion, input);
        return tv;
    }

    /** One-time static styling of a suggestion row (colours re-applied on rebind). */
    private void styleSuggestionTextView(@NonNull TextView tv) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(mPopupItemPadHPx, mPopupItemPadVPx, mPopupItemPadHPx, mPopupItemPadVPx);
        tv.setMaxLines(2);
        tv.setEllipsize(TextUtils.TruncateAt.END); // backup; text already fits 2 lines
        tv.setClickable(true);
    }

    /**
     * Rebind an existing suggestion row to new data: text, bold prefix,
     * accessibility description and scheme colours. Restores everything a fresh
     * {@link #buildSuggestionTextViewInternal} would set except the one-time
     * listeners and background.
     */
    private void rebindSuggestionTextViewInternal(@NonNull TextView tv,
            @NonNull String suggestion, @NonNull String input) {
        tv.setTextColor(mColorSchemeManager.getHistoryTextColor());
        int padH = mPopupItemPadHPx;
        // Available text width = popup width minus horizontal padding. Mirrors the
        // width the popup is sized to in showAutoCompletePopup (sumWidth). It is
        // constant across the per-row rebinds of one rebuild, so cache it.
        int fieldWidth = mInputField != null ? mInputField.getWidth() : 0;
        int availWidth;
        if (fieldWidth == mCachedFieldWidth && mCachedAvailWidth >= 0) {
            availWidth = mCachedAvailWidth;
        } else {
            int popupWidth = mPopupManager.computePopupWidth(fieldWidth);
            availWidth = Math.max(0, popupWidth - 2 * padH);
            mCachedFieldWidth = fieldWidth;
            mCachedAvailWidth = availWidth;
        }

        // Word-based leading truncation + manual trailing '…' (TextView's
        // setEllipsize(END) is unreliable for maxLines>1 on API 21-28).
        SpannableString ss = AutoCompleteTextRenderer.buildSuggestionSpannable(
                suggestion, input, availWidth, tv.getPaint());
        tv.setText(ss, TextView.BufferType.SPANNABLE);
        // Accessibility: describe the history candidate. Only rewrite it when the
        // row's suggestion actually changed — the tag already carries the raw
        // suggestion, so a no-op compare skips the per-keystroke string concat.
        if (!suggestion.equals(tv.getTag())) {
            tv.setContentDescription("History: " + suggestion);
        }
        tv.setTag(suggestion);
    }

    /**
     * Insert a chosen suggestion into the input field. The popup only ever shows a
     * candidate that starts with the typed text (history: whole-line prefix; shell:
     * last-word prefix), so we replace the maximal trailing substring of the typed
     * text that is itself a prefix of the candidate. This unifies both cases and
     * avoids duplicating the already-typed leading part — e.g. typing "git com"
     * and picking "git commit -m" yields "git commit -m", not "git git commit -m".
     */
    private void insertCandidate(@NonNull String candidate) {
        if (mInputField == null) return;
        Editable editable = mInputField.getText();
        if (editable == null) return;
        String text = editable.toString();
        int textLen = text.length();

        int caret = mInputField.getSelectionStart();
        if (caret < 0 || caret > textLen) caret = textLen;
        // When the caret sits inside the line, only the text BEFORE the caret can
        // be the typed prefix we replace; anything after the caret is kept intact.
        int caretPos = caret;

        // Longest suffix of text[0..caretPos) that is a prefix of candidate.
        int matchLen = 0;
        int maxK = Math.min(caretPos, candidate.length());
        for (int k = maxK; k >= 1; k--) {
            if (text.regionMatches(true, caretPos - k, candidate, 0, k)) {
                matchLen = k;
                break;
            }
        }

        int replaceStart = caretPos - matchLen;
        int replaceEnd = caretPos;

        String newText = text.substring(0, replaceStart) + candidate + text.substring(replaceEnd);
        mInputField.setText(newText);
        mInputField.setSelection(replaceStart + candidate.length());
    }

    private void showAutoCompletePopup(@NonNull EditText inputField) {
        mPopupManager.show(inputField);
    }

    /** Reposition the auto-complete popups at the current caret position. */
    public void repositionAutoCompletePopup() {
        if (!isShowing()) return;
        // Hide the popups if the caret is no longer at the end of the input field —
        // unless a swipe gesture holds a deliberate selection (keep it alive).
        if (mInputField != null && !isSwipeActive() && !hasComposingSpan(mInputField)) {
            int caret = mInputField.getSelectionStart();
            if (caret < 0 || caret != mInputField.getText().length()) {
                dismissAutoCompleteSuggestions();
                return;
            }
        }
        final EditText inputField = mInputField;
        if (inputField == null) return;
        applyPopupGeometry(inputField);
    }

    private void applyPopupGeometry(@NonNull EditText inputField) {
        mPopupManager.applyGeometry(inputField);
    }

    private void dismissAutoCompleteSuggestions() {
        mPopupManager.dismiss();
    }

    // ── Additional test-only accessors (package-private) ──

    /** Directly inject a history list for deterministic dispatch tests. */
    void debugSetHistory(@NonNull java.util.List<String> history) {
        mMessageHistoryCtrl.clearAllPerDirectory();
        for (String h : history) mMessageHistoryCtrl.addToMessageHistory(h, ".");
    }

    /** Test-only: install a null input field to exercise the mInputField==null guard. */
    void debugSetInputFieldNull() {
        mInputField = null;
    }

    /** Test-only: restore a (non-null) input field after {@link #debugSetInputFieldNull()}. */
    void debugSetInputField(@NonNull EditText field) {
        mInputField = field;
    }

    /** Test-only: the current input field (so tests can mutate its text). */
    @Nullable EditText debugGetInputField() {
        return mInputField;
    }

    /** Test-only: the message-history window, or null when not shown. */
    @Nullable PopupWindow debugHistoryPopup() {
        return mPopupManager.debugHistoryPopup();
    }

    /** Test-only: the history window's LinearLayout content. */
    @Nullable LinearLayout debugHistoryContent() {
        return mPopupManager.debugHistoryContent();
    }

    /** Test-only: last computed Y of the history window. */
    int debugGetHistoryY() {
        return mPopupManager.debugGetHistoryY();
    }

    /** Test-only: directly (re)compute popup geometry for the current input field. */
    void debugApplyPopupGeometry() {
        applyPopupGeometry(debugGetInputField());
    }
}
