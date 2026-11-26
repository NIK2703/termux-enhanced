package com.termux.app.terminal.io.autocomplete;

import com.termux.app.TermuxAppConstants;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.text.Editable;
import android.text.SpannableString;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;

import com.termux.R;
import com.termux.app.TermuxActivityUtils;
import com.termux.app.terminal.TermuxColorSchemeManager;
import com.termux.app.terminal.TextFade;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants;

/**
 * Drives message-history suggestions: matching, ranking, insertion, and the text/caret
 * listeners. {@link AutoCompletePopupManager} renders the popup, {@link SuggestionEngine}
 * matches and ranks, and {@link HistorySnapshot} caches offsets per history version.
 *
 * <p>A full entry matches the text after the last newline. A sentence matches the active
 * sentence after the last sentence terminator. Full entries fill the first slots;
 * sentences fill the rest.
 */
public final class AutoCompleteController implements AutoCompleteDataProvider {

    /** Match the input-history row delete animation/timing exactly. */

    private final Context mContext;
    private EditText mInputField;
    private final MessageHistoryController mMessageHistoryCtrl;
    private final TermuxColorSchemeManager mColorSchemeManager;
    private final SharedPreferences mPrefs;
    /** Opaque row colour used while a swipe reveals the red delete underlay. */
    private final int mSuggestionNormalBg;
    /** Opaque pressed colour, composited exactly like an input-history row. */
    private final int mSuggestionHighlightBg;
    /** Touch slop of the left-swipe delete gesture. */
    private final int mSuggestionTouchSlop;

    // Suggestion data lives here; AutoCompletePopupManager owns the popup window.
    /** Current list of suggestion strings being displayed (message history). */
    private final java.util.ArrayList<String> mCurrentSuggestions = new java.util.ArrayList<>();
    /** Bumped whenever {@link #mCurrentSuggestions} changes (O(1) popup diff). */
    private int mSuggestionsVersion;
    /** Bumped whenever the typed line/sentence bases change (O(1) popup diff). */
    private int mPrefixVersion;
    /** Cached row prefixes, computed once per update (no per-row substring allocation). */
    @NonNull private String mRowLinePrefix = "";
    @NonNull private String mRowTail = "";
    /** Word starts of the cached prefixes, computed once per update. */
    private int mRowLineWordStart;
    private int mRowTailWordStart;

    /** Maximum suggestions to render ("suggestions_max_count"). */
    private int mDisplayMax = 4;
    /** Minimum typed sentence length before scanning ("autocomplete_min_prefix"). */
    private int mMinPrefix = 1;
    /** Offer individual sentences as well as full entries ("autocomplete_sentences_enabled"). */
    private boolean mSentenceSuggestionsEnabled = true;
    /** Suppress auto-complete during programmatic text changes (suggestion tap, etc.). */
    private boolean mSuppressAutoComplete;
    /** True while a session's saved input is being restored; mutes text-change handling. */
    private boolean mRestoringInput;
    /** Suppress auto-complete during an active swipe-to-select gesture. */
    private boolean mSwipeSuppressed;
    /** Force a full rescan on the next update, e.g. after a swipe replaces text. */
    private boolean mForceFullRescan;
    /** True after a downward swipe hid the popup; it stays hidden until the set grows or a new sentence starts. */
    private boolean mPopupSwipeDismissed;
    /** Candidate count when the swipe hid the popup; the baseline for detecting a later expansion. */
    private int mSwipeDismissedCandidateCount;

    /** Set when the caret returns to the end, to bypass the unchanged-text early skip. */
    private boolean mForceRescanOnNextUpdate;

    /** Last known caret position, used to detect real movement without a selection listener. */
    private int mLastCaretPosition = -1;

    /** Reusable scratch set for case-folded candidate dedup across a scan/top-up. */
    private final HashSet<String> mSeenSet = new HashSet<>();

    /** Candidate metadata aligned by index with {@link #mCurrentSuggestions}. */
    private final ArrayList<SuggestionEngine.Candidate> mCandidates = new ArrayList<>();
    /** History snapshot for {@link #mSnapshotVersion}; rebuilt only when the version changes. */
    @Nullable private HistorySnapshot mSnapshot;
    /** History version of {@link #mSnapshot}. */
    private int mSnapshotVersion = -1;
    /** Resumable scan cursor for tier-2 top-ups. */
    @NonNull private final SuggestionEngine.ScanPos mScanPos = new SuggestionEngine.ScanPos();
    /** True when the last scan examined the whole history; no top-up is possible. */
    private boolean mScannedAll;
    /** Start and length of the typed active sentence in the input field. */
    private int mTypedTailStart;
    private int mTypedTailLen;
    /** Start and length of the typed line prefix (the full-entry match base). */
    private int mTypedLineStart;
    private int mTypedLineLen;
    /**
     * Incremental active-sentence state for the append-only hot path.
     * {@code mPendingTerminator} is the index of a terminator that would become a
     * sentence boundary if followed by whitespace/end; {@code mTailCandidateStart}
     * is the last confirmed sentence start to revert to if it is followed by a
     * non-whitespace character.
     */
    private int mPendingTerminator = -1;
    private int mTailCandidateStart = -1;
    /** True while skipping the whitespace run after a confirmed sentence boundary. */
    private boolean mBoundaryRun;
    /** True when the four {@code mTyped*} base fields describe the current text. */
    private boolean mBasesValid;
    /** Available popup width, cached for one rebuild; invalidated when the field width changes. */
    private int mCachedFieldWidth = -1;
    private int mCachedAvailWidth = -1;

    /** Pre-edit length, or -1 when the change was suppressed (restore, tap, swipe). */
    private int mAutoCompletePrevLen = -1;
    /** Start offset of the pending text change (from {@code onTextChanged}). */
    private int mAutoCompleteChangeStart;
    /** True when the pending change actually altered the text ({@code before != 0 || count != 0}). */
    private boolean mAutoCompleteTextChanged;
    /** Cheap up-front signal: the pending change is an IME composition (after != before). */
    private boolean mComposingChangePending = false;
    /** Count of characters being replaced (from onTextChanged). */
    private int mAutoCompleteChangeBefore;
    /** Count of new characters being inserted (from onTextChanged). */
    private int mAutoCompleteChangeCount;

    /** History version of the last rebuilt suggestion set. */
    private int mLastBuiltHistoryVersion = -1;

    @NonNull private final AutoCompletePopupManager mPopupManager;

    private final android.os.Handler mImeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable mComposingCoalesce = null;

    /**
     * Swipe on a suggestion row appends its next words to the input selection;
     * lifting the finger commits them. Per-gesture state lives in the handler.
     */
    @NonNull private final AutoCompleteSwipeHandler mSwipeHandler;

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
        mPrefs = context.getSharedPreferences(TermuxAppConstants.Preferences.FILE_NAME, Context.MODE_PRIVATE);
        mSuggestionNormalBg = colorSchemeManager.getHistoryPopupBg();
        mSuggestionHighlightBg = TermuxColorSchemeManager.compositeColors(
                mSuggestionNormalBg, colorSchemeManager.getHistoryHighlightFill());
        mSuggestionTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        readPreferences();
        // Keep the history index in the same mode as this controller. The history
        // controller is a process-wide singleton and may have loaded before the
        // activity applied the preference.
        mMessageHistoryCtrl.setSentenceIndexEnabled(mSentenceSuggestionsEnabled);
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
        // Build the popup-window manager with the pre-read resource dimensions.
        mPopupManager = new AutoCompletePopupManager(context, this, colorSchemeManager,
                mPopupCornerRadiusPx, mPopupElevationPx,
                mPopupItemPadHPx, mPopupItemPadVPx,
                mPopupMinWidthPx, mPopupWidthMarginPx,
                mPopupWidthFraction, mPopupXOffsetPx,
                mPopupEdgeMarginPx, mPopupYOffsetPx,
                mPopupMinYPx, mPopupContentAlpha, mPopupShadowAlpha);
        // Swipe-to-select gesture handler: needs the live input field (it may be swapped by
        // tests) plus callbacks to refresh suggestions and set/clear the suppression guard.
        // The swipe uses its OWN suppress flag (distinct from the tap-to-insert guard) so a
        // tap cannot clear a swipe's suppression and vice-versa.
        mSwipeHandler = new AutoCompleteSwipeHandler(mContext,
                () -> mInputField,
                this::refreshAfterSwipe,
                () -> setSwipeSuppress(true),
                () -> setSwipeSuppress(false));
        attachInputListeners();
    }

    private void readPreferences() {
        int maxCount = mPrefs.getInt(TermuxPreferenceConstants.TERMUX_APP.KEY_SUGGESTIONS_MAX_COUNT,
                TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_SUGGESTIONS_MAX_COUNT);
        if (maxCount < TermuxPreferenceConstants.TERMUX_APP.SUGGESTIONS_MAX_COUNT_MIN) {
            maxCount = TermuxPreferenceConstants.TERMUX_APP.SUGGESTIONS_MAX_COUNT_MIN;
        }
        if (maxCount > TermuxPreferenceConstants.TERMUX_APP.SUGGESTIONS_MAX_COUNT_MAX) {
            maxCount = TermuxPreferenceConstants.TERMUX_APP.SUGGESTIONS_MAX_COUNT_MAX;
        }
        mDisplayMax = maxCount;
        int minPrefix = mPrefs.getInt("autocomplete_min_prefix", 1);
        if (minPrefix < 1) minPrefix = 1;
        if (minPrefix > 3) minPrefix = 3;
        mMinPrefix = minPrefix;
        mSentenceSuggestionsEnabled = mPrefs.getBoolean(TermuxAppConstants.Preferences.AUTOCOMPLETE_SENTENCES_ENABLED, true);
    }

    @Override @NonNull public ArrayList<String> getSuggestions() { return mCurrentSuggestions; }
    @Override public int getDisplayMax() { return mDisplayMax; }
    @Override public boolean isSwipeActive() { return mSwipeHandler.isEngaged(); }
    @Override @Nullable public EditText getInputField() { return mInputField; }
    @Override @NonNull public TextView buildSuggestionTextView(@NonNull String suggestion, @NonNull String input) {
        return buildSuggestionTextViewInternal(suggestion, input, findCandidate(suggestion), false);
    }
    @Override @NonNull public TextView buildSuggestionTextView(@NonNull String suggestion,
            @NonNull String input, @Nullable SuggestionEngine.Candidate candidate) {
        return buildSuggestionTextViewInternal(suggestion, input, candidate, true);
    }
    @Override public void rebindSuggestionTextView(@NonNull TextView tv, @NonNull String suggestion, @NonNull String input) {
        rebindSuggestionTextViewInternal(tv, suggestion, input, findCandidate(suggestion), false);
    }
    @Override public void rebindSuggestionTextView(@NonNull TextView tv, @NonNull String suggestion,
            @NonNull String input, @Nullable SuggestionEngine.Candidate candidate) {
        rebindSuggestionTextViewInternal(tv, suggestion, input, candidate, true);
    }
    @Override @Nullable public SuggestionEngine.Candidate getCandidate(int index) {
        if (index < 0 || index >= mCandidates.size()) return null;
        return mCandidates.get(index);
    }
    @Override public int getSuggestionsVersion() { return mSuggestionsVersion; }
    @Override public int getPrefixVersion() { return mPrefixVersion; }
    @Override public void onSuggestionDismissed() {
        mCurrentSuggestions.clear();
        mCandidates.clear();
        mSuggestionsVersion++;
    }

    @Override public void onSuggestionsPopupSwipedDown() {
        if (!isShowing()) return;
        // Capture the baseline before dismissAutoCompleteSuggestions() clears the list.
        mSwipeDismissedCandidateCount = mCandidates.size();
        mPopupSwipeDismissed = true;
        mPopupManager.dismissWithSwipeAnimation();
    }

    /**
     * A swipe-hidden popup may only reappear when this update actually expanded the
     * candidate set (e.g. after backspace). A shrinking or unchanged set keeps it hidden.
     *
     * @return whether the caller may show/update the popup normally.
     */
    private boolean revealAfterSwipeIfExpanded(int countBefore) {
        if (!mPopupSwipeDismissed) return true;
        final int now = mCandidates.size();
        if (now > countBefore) {
            mPopupSwipeDismissed = false;
            mSwipeDismissedCandidateCount = 0;
            return true;
        }
        mSwipeDismissedCandidateCount = now;
        return false;
    }

    /** Reuses the suggestion word-restore swipe for an input-history row; only rightward motion engages. */
    public boolean onHistoryRowSwipe(@NonNull View row, @NonNull MotionEvent event,
            @NonNull String message, @Nullable Runnable onWordsRestored) {
        return mSwipeHandler.onTouch(row, event, message, true, onWordsRestored);
    }

    /** Aborts an in-flight history-row restore. */
    public void cancelHistoryRowSwipe() {
        mSwipeHandler.resetSwipeState();
    }

    /**
     * Release resources. Call from the host's lifecycle teardown.
     */
    public void destroy() {
        mSwipeHandler.resetIfEngaged();
        mSwipeSuppressed = false;
        mSuppressAutoComplete = false;
        mPopupSwipeDismissed = false;
        if (mComposingCoalesce != null) { mImeHandler.removeCallbacks(mComposingCoalesce); mComposingCoalesce = null; }
    }

    /**
     * Mute text handling while restored input is written; drop pending IME work and
     * cached bases.
     */
    public void setRestoringInput(boolean restoring) {
        mRestoringInput = restoring;
        if (restoring && mComposingCoalesce != null) {
            mImeHandler.removeCallbacks(mComposingCoalesce);
            mComposingCoalesce = null;
        }
        mAutoCompletePrevLen = -1;
        mAutoCompleteTextChanged = false;
        mForceRescanOnNextUpdate = true;
        mForceFullRescan = true;
        mLastBuiltHistoryVersion = -1;
        resetScanState();
        dismissAutoCompleteSuggestions();
    }

    /** Set/clear the swipe-gesture auto-complete suppression guard (distinct from the tap guard). */
    void setSwipeSuppress(boolean suppress) {
        mSwipeSuppressed = suppress;
    }

    /** Force a history-version mismatch so the next update takes Path A (full rescan). */
    public void invalidateHistoryVersion() {
        mLastBuiltHistoryVersion = -1;
        mSnapshotVersion = -1;
    }

    /** Re-read the auto-complete settings and discard the current popup state. */
    public void reloadPreferences() {
        readPreferences();
        mMessageHistoryCtrl.setSentenceIndexEnabled(mSentenceSuggestionsEnabled);
        mLastBuiltHistoryVersion = -1;
        mSnapshot = null;
        mSnapshotVersion = -1;
        mForceRescanOnNextUpdate = true;
        mForceFullRescan = true;
        mAutoCompletePrevLen = -1;
        mAutoCompleteTextChanged = false;
        dismiss();
        resetScanState();
    }

    /** Handle a caret move without a text change (tap or arrow key). */
    public void onCaretMoved() {
        if (mInputField == null) return;
        int newCaret = mInputField.getSelectionStart();
        int textLen = mInputField.getText().length();
        mLastCaretPosition = newCaret;
        if (newCaret >= 0) {
            mTypedLineStart = SuggestionEngine.activeLineStart(mInputField.getText(), newCaret);
            mTypedLineLen = newCaret - mTypedLineStart;
            if (mSentenceSuggestionsEnabled) {
                mTypedTailStart = SuggestionEngine.activeSentenceStart(
                        mInputField.getText(), newCaret);
                mTypedTailLen = newCaret - mTypedTailStart;
            } else {
                mTypedTailStart = mTypedLineStart;
                mTypedTailLen = mTypedLineLen;
            }
            mPendingTerminator = -1;
            mTailCandidateStart = -1;
            mBasesValid = true;
        }
        if (newCaret >= 0 && newCaret == textLen && !isShowing()) {
            mForceRescanOnNextUpdate = true;
        }
        updateAutoCompleteSuggestions();
    }

    /** Dismiss and clear the suggestions popup. */
    public void dismiss() {
        mSwipeHandler.resetIfEngaged();
        mSwipeSuppressed = false;
        mPopupSwipeDismissed = false;
        dismissAutoCompleteSuggestions();
    }

    /**
     * Called after the message history was mutated outside autocomplete: a history
     * row was deleted or the history was cleared. Candidates and visible rows refer
     * to history records by position, so drop both immediately instead of waiting
     * for the next text/caret event — a deleted entry must never stay tappable.
     *
     * <p>Must run on the main thread.
     */
    public void onHistoryChanged() {
        dismiss();

        // dismiss() clears the index only when a popup was actually showing
        // (AutoCompletePopupManager returns early when mHistoryPopup is null).
        resetScanState();

        invalidateHistoryVersion();
    }

    /** Whether either the shell or the history suggestion window is currently showing. */
    public boolean isShowing() {
        return mPopupManager.isShowing();
    }

    private void attachInputListeners() {
        mInputField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // The pre-edit length and change offsets identify append/delete/replace
                // without copying the input.
                if (mRestoringInput || mSuppressAutoComplete || mSwipeSuppressed) {
                    mAutoCompletePrevLen = -1;
                    mAutoCompleteTextChanged = false;
                } else {
                    mAutoCompletePrevLen = s == null ? 0 : s.length();
                    mAutoCompleteTextChanged = false;
                }
                // after != count means a range edit, not one committed character.
                mComposingChangePending = (after - count) != 0;
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                mAutoCompleteChangeStart = start;
                mAutoCompleteChangeBefore = before;
                mAutoCompleteChangeCount = count;
                mAutoCompleteTextChanged = (before != 0 || count != 0);
            }
            @Override
            public void afterTextChanged(android.text.Editable s) {
                // The restore's setText() must not trigger a popup.
                if (mRestoringInput) return;
                // Clear swipe suppression left behind by a missed UP/CANCEL.
                if (mSwipeSuppressed && !mSwipeHandler.isEngaged() && !mSwipeHandler.isPointerDown()) {
                    mSwipeHandler.resetIfEngaged();
                }
                final EditText f = mInputField;
                final boolean isDeletion = mComposingChangePending
                        && (mAutoCompleteChangeBefore > mAutoCompleteChangeCount);
                // Only a length-changing edit can carry a composing span.
                final boolean composingAtEvent = f != null && mComposingChangePending
                        && !isDeletion && hasComposingSpan(f);
                if (composingAtEvent) {
                    if (mComposingCoalesce != null) mImeHandler.removeCallbacks(mComposingCoalesce);
                    mComposingCoalesce = () -> {
                        mComposingCoalesce = null;
                        if (mSuppressAutoComplete || mSwipeSuppressed) return;
                        updateAutoCompleteSuggestions();
                    };
                    mImeHandler.post(mComposingCoalesce);
                    return;
                }
                if (mComposingCoalesce != null) { mImeHandler.removeCallbacks(mComposingCoalesce); mComposingCoalesce = null; }
                updateAutoCompleteSuggestions(); // committed input is synchronous
                if (mInputField != null) {
                    mLastCaretPosition = mInputField.getSelectionStart();
                }
            }
        });

        // ACTION_UP catches taps; setOnSelectionChangedListener needs API 29.
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
            return false;
        });

        // Arrow keys move the caret without changing the text.
        mInputField.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP
                    && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                        || keyCode == KeyEvent.KEYCODE_DPAD_UP
                        || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                onCaretMoved();
            }
            return false;
        });
    }

    /** True when the text carries an IME composing span (checked via public span flags). */
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

    /** A swipe can replace arbitrary text, so force a full rescan. */
    private void refreshAfterSwipe() {
        mForceFullRescan = true;
        updateAutoCompleteSuggestions();
    }

    /**
     * Rebuild (Path A) or filter (Path B) the candidate list for the current text.
     * Path C, repositioning, belongs to the popup manager.
     */
    private void updateAutoCompleteSuggestions() {
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

        // A deferred IME update can outlive the restore's afterTextChanged mute.
        if (mRestoringInput) {
            return;
        }

        final int prevLen = mAutoCompletePrevLen;
        final int changeStart = mAutoCompleteChangeStart;
        final int changeBefore = mAutoCompleteChangeBefore;
        final int changeCount = mAutoCompleteChangeCount;
        final boolean textChanged = mAutoCompleteTextChanged;
        mAutoCompleteTextChanged = false;
        // After a swipe dismissal the candidate list was cleared, so compare the new
        // count against the captured baseline instead of the (empty) live list.
        final int candidateCountBefore = mPopupSwipeDismissed
                ? mSwipeDismissedCandidateCount : mCandidates.size();

        final Editable editable = inputField.getText();
        if (editable == null || editable.length() == 0) {
            mAutoCompletePrevLen = 0;
            dismissAutoCompleteSuggestions();
            resetScanState();
            return;
        }
        final CharSequence text = editable;
        final int textLen = text.length();
        mAutoCompletePrevLen = textLen;

        // A deletion can leave the caret inside the composing span; do not dismiss it.
        final boolean deletion = textChanged && prevLen >= 0 && changeCount == 0
                && changeBefore > 0 && changeStart + changeBefore == prevLen;

        // Suggestions only show at the end of the field, except during a swipe that
        // deliberately holds a selection mid-text.
        final int caret = inputField.getSelectionStart();
        final boolean caretAtEnd = (caret == textLen)
                || (deletion && inputField.getSelectionEnd() == textLen);
        if (caret < 0 || !caretAtEnd) {
            if (isSwipeActive()) {
                return;
            }
            if (!deletion && isShowing() && inputField != null && hasComposingSpan(inputField)
                    && suggestionsMatchTail(text, caret)) {
                return;
            }
            dismissAutoCompleteSuggestions();
            resetScanState();
            return;
        }

        final int maxCount = mDisplayMax;
        if (maxCount == 0) {
            dismissAutoCompleteSuggestions();
            resetCandidates();
            return;
        }

        // IME re-compose of the same text.
        final boolean unchanged = !textChanged && prevLen == textLen;
        if (unchanged && !mForceRescanOnNextUpdate) {
            if (mMessageHistoryCtrl.getHistoryVersion() == mLastBuiltHistoryVersion
                    && isShowing()
                    && !mCandidates.isEmpty()
                    && suggestionsMatchTail(text, caret)) {
                return;
            }
        }
        mForceRescanOnNextUpdate = false;

        // Full entries match after the last newline; sentences match after the last
        // sentence boundary. The single-character append path updates both in O(1).
        // With sentence suggestions off, the line is the only base: sentence state
        // and its incremental updates are skipped entirely.
        final boolean endAppendOne = textChanged && prevLen >= 0 && changeBefore == 0
                && changeCount == 1 && changeStart == prevLen;
        final int prevLineStart = mTypedLineStart;
        final int prevLineLen = mTypedLineLen;
        final int prevTailStart = mTypedTailStart;
        final int prevTailLen = mTypedTailLen;
        final int lineStart;
        final int lineLen;
        final int tailStart;
        final int tailLen;
        if (!textChanged && mBasesValid) {
            lineStart = prevLineStart;
            lineLen = prevLineLen;
            tailStart = prevTailStart;
            tailLen = prevTailLen;
        } else if (endAppendOne && mBasesValid) {
            final char c = text.charAt(caret - 1);
            if (c == '\n') {
                lineStart = caret;
                lineLen = 0;
                tailStart = caret;
                tailLen = 0;
                mPendingTerminator = -1;
                mTailCandidateStart = -1;
                mBoundaryRun = false;
            } else {
                lineStart = prevLineStart;
                lineLen = prevLineLen + 1;
                if (!mSentenceSuggestionsEnabled) {
                    tailStart = lineStart;
                    tailLen = lineLen;
                    mPendingTerminator = -1;
                    mTailCandidateStart = -1;
                    mBoundaryRun = false;
                } else if (HistorySnapshot.isSentenceEnd(c)) {
                    mTailCandidateStart = (mPendingTerminator >= 0)
                            ? mTailCandidateStart : prevTailStart;
                    mPendingTerminator = caret - 1;
                    mBoundaryRun = false;
                    tailStart = caret;
                    tailLen = 0;
                } else if (HistorySnapshot.isBoundaryWhitespace(c)) {
                    if (mPendingTerminator >= 0) {
                        mPendingTerminator = -1;
                        mBoundaryRun = true;
                        tailStart = caret;
                        tailLen = 0;
                    } else if (mBoundaryRun) {
                        tailStart = caret;
                        tailLen = 0;
                    } else {
                        tailStart = prevTailStart;
                        tailLen = prevTailLen + 1;
                    }
                } else if (mPendingTerminator >= 0) {
                    tailStart = mTailCandidateStart;
                    tailLen = caret - tailStart;
                    mPendingTerminator = -1;
                    mTailCandidateStart = -1;
                    mBoundaryRun = false;
                } else {
                    tailStart = prevTailStart;
                    tailLen = prevTailLen + 1;
                    mBoundaryRun = false;
                }
            }
        } else {
            lineStart = SuggestionEngine.activeLineStart(text, caret);
            lineLen = caret - lineStart;
            if (mSentenceSuggestionsEnabled) {
                tailStart = SuggestionEngine.activeSentenceStart(text, caret);
                tailLen = caret - tailStart;
            } else {
                tailStart = lineStart;
                tailLen = lineLen;
            }
            mPendingTerminator = -1;
            mTailCandidateStart = -1;
            mBoundaryRun = false;
        }
        mTypedLineStart = lineStart;
        mTypedLineLen = lineLen;
        mTypedTailStart = tailStart;
        mTypedTailLen = tailLen;
        mBasesValid = true;
        // The popup rebinds up to displayMax rows, so compute both prefixes and their
        // word starts once per update.
        mRowLinePrefix = text.subSequence(lineStart, textLen).toString();
        mRowLineWordStart = AutoCompleteTextRenderer.wordStartOffset(mRowLinePrefix);
        if (mSentenceSuggestionsEnabled) {
            mRowTail = text.subSequence(tailStart, textLen).toString();
            mRowTailWordStart = AutoCompleteTextRenderer.wordStartOffset(mRowTail);
        } else {
            // No sentence suggestions: the row highlight follows the whole line.
            mRowTail = mRowLinePrefix;
            mRowTailWordStart = mRowLineWordStart;
        }
        final boolean lineBaseChanged = textChanged
                || lineStart != prevLineStart || lineLen != prevLineLen;
        final boolean tailBaseChanged = mSentenceSuggestionsEnabled
                && (tailStart != prevTailStart || tailLen != prevTailLen);
        if (lineBaseChanged || tailBaseChanged) {
            mPrefixVersion++;
        }
        final int scanLen = mSentenceSuggestionsEnabled ? tailLen : lineLen;
        if (scanLen < mMinPrefix) {
            dismissAutoCompleteSuggestions();
            resetCandidates();
            return;
        }

        // A new line or sentence boundary invalidates the old candidate set.
        final boolean structural = lineStart != prevLineStart
                || (mSentenceSuggestionsEnabled && tailStart != prevTailStart);
        if (structural) {
            resetCandidates();
            dismissAutoCompleteSuggestions();
        }

        // Additive append within the same sentence (or line, when sentences are off).
        boolean additive = false;
        if (!structural && endAppendOne
                && !mCandidates.isEmpty()
                && mSnapshot != null
                && lineStart == prevLineStart && lineLen == prevLineLen + 1
                && (!mSentenceSuggestionsEnabled
                        || (tailStart == prevTailStart && tailLen == prevTailLen + 1))) {
            additive = true;
        }
        // A swipe changes text arbitrarily, so the filter does not apply.
        if (mForceFullRescan) {
            additive = false;
            mForceFullRescan = false;
        }

        // Backspace: rebuild from history; filtering the top-N cache cannot expand it.
        if (!additive && !structural && !mCandidates.isEmpty()
                && deletion
                && lineStart == prevLineStart && lineLen < prevLineLen
                && (!mSentenceSuggestionsEnabled
                        || (tailStart == prevTailStart && tailLen < prevTailLen))) {
            rebuildCandidates(text, lineStart, lineLen, tailStart, tailLen, maxCount);
            mLastBuiltHistoryVersion = mMessageHistoryCtrl.getHistoryVersion();
            if (!revealAfterSwipeIfExpanded(candidateCountBefore)) return;
            if (mCandidates.isEmpty()) {
                dismissAutoCompleteSuggestions();
            } else {
                updatePopupContent(mRowTail, inputField);
            }
            return;
        }

        if (!additive || mMessageHistoryCtrl.getHistoryVersion() != mLastBuiltHistoryVersion) {
            rebuildCandidates(text, lineStart, lineLen, tailStart, tailLen, maxCount);
            mLastBuiltHistoryVersion = mMessageHistoryCtrl.getHistoryVersion();
            if (!revealAfterSwipeIfExpanded(candidateCountBefore)) return;
            if (mCandidates.isEmpty()) {
                dismissAutoCompleteSuggestions();
            } else {
                showAutoCompletePopup(inputField, mRowTail);
            }
            return;
        }

        // Path B: filter the additive change. The longer-prefix candidates are a
        // subset of the current ones, so this costs O(maxCount) with no allocations.
        final char newChar = text.charAt(caret - 1);
        final int beforeFilter = mCandidates.size();
        SuggestionEngine.filter(mCandidates, mSnapshot, prevLineLen,
                mSentenceSuggestionsEnabled ? prevTailLen : prevLineLen, newChar);
        final boolean listChanged = mCandidates.size() != beforeFilter;
        mTypedLineLen = lineLen;
        mTypedTailLen = tailLen;

        // Top up from the saved scan position if the filter left free slots.
        final int beforeTopUp = mCandidates.size();
        if (!mScannedAll && mCandidates.size() < maxCount) {
            mSeenSet.clear();
            // Keep every surviving candidate's folded key so a later sentence with the
            // same text is not added twice.
            for (int i = 0; i < mCandidates.size(); i++) {
                mSeenSet.add(SuggestionEngine.foldedKey(mCandidates.get(i).text));
            }
            if (mSentenceSuggestionsEnabled) {
                SuggestionEngine.scan(mSnapshot, text, lineStart, lineLen, tailStart, tailLen,
                        maxCount, mScanPos, mCandidates, mSeenSet);
            } else {
                SuggestionEngine.scanFullOnly(mSnapshot, text, lineStart, lineLen,
                        maxCount, mScanPos, mCandidates, mSeenSet);
            }
            mScannedAll = mScanPos.atEnd;
        }
        final boolean topUpAdded = mCandidates.size() != beforeTopUp;

        // A prefix-only update keeps the candidate set, so only bump the list version
        // when it actually changed.
        if (listChanged || topUpAdded) {
            rebuildSuggestionStrings();
        }
        mLastBuiltHistoryVersion = mMessageHistoryCtrl.getHistoryVersion();

        if (!revealAfterSwipeIfExpanded(candidateCountBefore)) return;
        if (mCandidates.isEmpty()) {
            dismissAutoCompleteSuggestions();
            return;
        }
        if (!isShowing()) {
            showAutoCompletePopup(inputField, mRowTail);
            return;
        }
        updatePopupContent(mRowTail, inputField);
    }

    /** Rebuild the candidate list from scratch for the line prefix and active sentence. */
    private void rebuildCandidates(@NonNull CharSequence text, int lineStart, int lineLen,
            int tailStart, int tailLen, int maxCount) {
        ensureSnapshot();
        mCandidates.clear();
        mCurrentSuggestions.clear();
        mScanPos.reset();
        mScannedAll = false;
        mTypedLineStart = lineStart;
        mTypedLineLen = lineLen;
        mTypedTailStart = tailStart;
        mTypedTailLen = tailLen;
        mSeenSet.clear();
        if (mSentenceSuggestionsEnabled) {
            SuggestionEngine.scan(mSnapshot, text, lineStart, lineLen, tailStart, tailLen,
                    maxCount, mScanPos, mCandidates, mSeenSet);
        } else {
            SuggestionEngine.scanFullOnly(mSnapshot, text, lineStart, lineLen,
                    maxCount, mScanPos, mCandidates, mSeenSet);
        }
        mScannedAll = mScanPos.atEnd;
        rebuildSuggestionStrings();
    }

    /** Keep the history snapshot in sync with the live history version. */
    private void ensureSnapshot() {
        final int version = mMessageHistoryCtrl.getHistoryVersion();
        if (mSnapshot == null || mSnapshotVersion != version) {
            mSnapshot = HistorySnapshot.build(version, mMessageHistoryCtrl.getHistoryEntries(),
                    mMessageHistoryCtrl.getSpansCache(), mMessageHistoryCtrl.getDismissals());
            mSnapshotVersion = version;
        }
    }

    /** Rebuild the string view consumed by the popup manager. */
    private void rebuildSuggestionStrings() {
        mCurrentSuggestions.clear();
        for (int i = 0; i < mCandidates.size(); i++) {
            mCurrentSuggestions.add(mCandidates.get(i).text);
        }
        mSuggestionsVersion++;
    }

    /** Drop candidates and the resumable scan cursor, keeping the current text bases. */
    private void resetCandidates() {
        // A reset means a new sentence/line, empty input or a fresh setup: the
        // swipe suppression no longer applies.
        mPopupSwipeDismissed = false;
        mCandidates.clear();
        mCurrentSuggestions.clear();
        mScanPos.reset();
        mScannedAll = false;
        mSuggestionsVersion++;
    }

    /** Drop candidates and invalidate the text bases (empty text / caret jump). */
    private void resetScanState() {
        resetCandidates();
        mTypedLineStart = 0;
        mTypedLineLen = 0;
        mTypedTailStart = 0;
        mTypedTailLen = 0;
        mPendingTerminator = -1;
        mTailCandidateStart = -1;
        mBoundaryRun = false;
        mBasesValid = false;
    }

    /**
     * True when every shown candidate still has the active sentence as a strict
     * case-insensitive prefix of its matched window. Used by the early-skip and
     * composition guards so a stale popup is never kept.
     */
    private boolean suggestionsMatchTail(@NonNull CharSequence text, int caret) {
        if (mCandidates.isEmpty() || mSnapshot == null) return false;
        // deleteSuggestion() can rebuild the snapshot for a newer history while
        // mCandidates still carries entryIndex values from the previous one.
        if (mSnapshotVersion != mLastBuiltHistoryVersion
                || mSnapshotVersion != mMessageHistoryCtrl.getHistoryVersion()) {
            return false;
        }
        if (!mSentenceSuggestionsEnabled) {
            // Only full entries are shown, so the line prefix is the whole base.
            final boolean cached = caret == text.length() && mBasesValid;
            final int start = cached
                    ? mTypedLineStart : SuggestionEngine.activeLineStart(text, caret);
            final int len = caret - start;
            if (len < 1) return false;
            final char[] folded = SuggestionEngine.fold(text, start, len);
            for (int i = 0; i < mCandidates.size(); i++) {
                final SuggestionEngine.Candidate c = mCandidates.get(i);
                if (c.kind != SuggestionEngine.Kind.FULL
                        || !SuggestionEngine.matchesFull(
                                mSnapshot, c.entryIndex, folded, len)) {
                    return false;
                }
            }
            return true;
        }
        // At the end of unchanged text the incrementally maintained bases are exact;
        // otherwise (caret inside the text) recompute from the text.
        final boolean useCachedBases = caret == text.length() && mBasesValid;
        final int lineStart = useCachedBases
                ? mTypedLineStart : SuggestionEngine.activeLineStart(text, caret);
        final int lineLen = caret - lineStart;
        final int tailStart = useCachedBases
                ? mTypedTailStart : SuggestionEngine.activeSentenceStart(text, caret);
        final int tailLen = caret - tailStart;
        if (tailLen < 1) return false;
        final char[] foldedLine = SuggestionEngine.fold(text, lineStart, lineLen);
        // The active sentence is a suffix of the active line: reuse the fold.
        final int tailOffset = tailStart - lineStart;
        final char[] foldedTail;
        final int foldedTailStart;
        if (tailOffset >= 0 && tailOffset + tailLen <= lineLen) {
            foldedTail = foldedLine;
            foldedTailStart = tailOffset;
        } else {
            foldedTail = SuggestionEngine.fold(text, tailStart, tailLen);
            foldedTailStart = 0;
        }
        for (int i = 0; i < mCandidates.size(); i++) {
            final SuggestionEngine.Candidate c = mCandidates.get(i);
            final boolean matches;
            if (c.kind == SuggestionEngine.Kind.FULL) {
                matches = SuggestionEngine.matchesFull(mSnapshot, c.entryIndex,
                        foldedLine, lineLen);
            } else {
                matches = c.windowEnd - c.windowStart > tailLen
                        && SuggestionEngine.foldedMatches(mSnapshot.fold(c.entryIndex),
                                c.windowStart, foldedTail, foldedTailStart, tailLen);
            }
            if (!matches) return false;
        }
        return true;
    }

    private void updatePopupContent(@NonNull String newText, @NonNull EditText inputField) {
        mPopupManager.update(newText, inputField);
    }

    /**
     * Create a suggestion row and wire its tap, right-swipe word restore and
     * left-swipe delete handlers.
     */
    private TextView buildSuggestionTextViewInternal(@NonNull String suggestion, @NonNull String input,
            @Nullable SuggestionEngine.Candidate candidate, boolean useCachedPrefix) {
        TextView tv = new TextView(mContext);
        styleSuggestionTextView(tv);
        tv.setClickable(true);
        // Rows are rebound, so the captured suggestion can be stale; read the tag instead.
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
        });

        // Per-row gesture state. A right swipe still restores words; a left swipe
        // deletes the suggestion with the same underlay, icon, threshold, haptics
        // and timings as a left swipe on an input-history row.
        final float[] down = new float[2];
        final boolean[] deleteEngaged = { false };
        final boolean[] deleteRejected = { false };
        final boolean[] deleteArmed = { false };
        // Tag captured on DOWN. A rebuild can reuse this row for different text
        // between events; a swipe must never delete the new text.
        final String[] gestureTag = { null };
        tv.setOnTouchListener((v, event) -> {
            Object tag = v.getTag();
            final String current = (tag instanceof String) ? (String) tag : null;
            final SuggestionEngine.Candidate touchCandidate =
                    current != null ? findCandidate(current) : null;
            // A stale row (already rebuilt) keeps the old word-swipe-only behaviour.
            if (touchCandidate == null) {
                return mSwipeHandler.onTouch(v, event, current, false, null,
                        AutoCompleteSwipeHandler.BASE_SENTENCE);
            }

            final int action = event.getActionMasked();
            final boolean gestureEnd = action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL;
            final SuggestionRowHolder holder =
                    v.getParent() instanceof SuggestionRowHolder
                            ? (SuggestionRowHolder) v.getParent() : null;

            if (action != MotionEvent.ACTION_DOWN
                    && gestureTag[0] != null && !gestureTag[0].equals(current)) {
                // The row was rebound during the gesture. Restore the visual state and
                // let the new row handle its own tap; do not act on the new text.
                gestureTag[0] = null;
                if (deleteEngaged[0]) {
                    v.animate().cancel();
                    v.setTranslationX(0f);
                }
                v.setPressed(false);
                v.setBackgroundColor(Color.TRANSPARENT);
                if (holder != null) holder.hideDeleteUnderlay();
                return false;
            }

            if (action == MotionEvent.ACTION_DOWN) {
                v.animate().cancel();
                v.setTranslationX(0f);
                v.setPressed(true);
                // Opaque, so the red underlay is hidden behind the row until it moves.
                v.setBackgroundColor(mSuggestionHighlightBg);
                down[0] = event.getRawX();
                down[1] = event.getRawY();
                deleteEngaged[0] = false;
                deleteRejected[0] = false;
                deleteArmed[0] = false;
                gestureTag[0] = current;
                if (holder != null) holder.hideDeleteUnderlay();
            }

            // Rightward word restore. rightOnly leaves leftward motion to the delete
            // gesture below.
            if (!deleteEngaged[0] && (!deleteRejected[0] || gestureEnd)) {
                final int baseMode =
                        touchCandidate.kind == SuggestionEngine.Kind.FULL
                                ? AutoCompleteSwipeHandler.BASE_FULL
                                : AutoCompleteSwipeHandler.BASE_SENTENCE;
                if (mSwipeHandler.onTouch(v, event, current, true, null, baseMode)) {
                    if (gestureEnd) {
                        v.setPressed(false);
                        v.setBackgroundColor(Color.TRANSPARENT);
                        if (holder != null) holder.hideDeleteUnderlay();
                    }
                    return true;
                }
            }

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    if (deleteRejected[0]) return false;
                    final float dx = event.getRawX() - down[0];
                    final float dy = event.getRawY() - down[1];
                    if (!deleteEngaged[0]) {
                        if (Math.abs(dy) > mSuggestionTouchSlop
                                && Math.abs(dy) >= Math.abs(dx)) {
                            // Vertical drag: let the popup's ScrollView scroll.
                            deleteRejected[0] = true;
                            v.cancelLongPress();
                            return false;
                        }
                        if (dx >= -mSuggestionTouchSlop || Math.abs(dx) <= Math.abs(dy)) {
                            return false;
                        }
                        deleteEngaged[0] = true;
                        v.cancelLongPress();
                        // The word handler only saw the down; nothing to undo.
                        mSwipeHandler.resetSwipeState();
                        v.setPressed(true);
                        v.setBackgroundColor(mSuggestionHighlightBg);
                        if (holder != null) holder.showDeleteUnderlay();
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    }
                    final float translation = Math.max(-v.getWidth(), Math.min(0f, dx));
                    v.setTranslationX(translation);
                    final boolean nowArmed =
                            -translation >= suggestionDeleteThreshold(v.getWidth());
                    if (nowArmed != deleteArmed[0]) {
                        deleteArmed[0] = nowArmed;
                        if (nowArmed) {
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    if (!deleteEngaged[0]) {
                        // Plain tap or vertical scroll: restore the idle look and let
                        // the row's OnClickListener run.
                        v.setPressed(false);
                        v.setBackgroundColor(Color.TRANSPARENT);
                        if (holder != null) holder.hideDeleteUnderlay();
                        return false;
                    }
                    v.setPressed(false);
                    // Opaque normal row while it slides, so the red is revealed only
                    // to the right of the row (matching the history-row animation).
                    v.setBackgroundColor(mSuggestionNormalBg);
                    final boolean remove = action == MotionEvent.ACTION_UP
                            && gestureTag[0] != null && gestureTag[0].equals(current)
                            && -v.getTranslationX()
                            >= suggestionDeleteThreshold(v.getWidth());
                    if (remove && deleteSuggestion(touchCandidate)) {
                        // Keep the finishing row out of the next rebuild; otherwise it
                        // could be rebound to another candidate before its 140 ms
                        // slide-out ends.
                        if (holder != null) holder.markDeleting();
                        v.setOnTouchListener(null);
                        v.setOnClickListener(null);
                        v.setClickable(false);
                        final View rowToRemove = holder != null ? holder : v;
                        v.animate().translationX(-v.getWidth())
                                .setDuration(TermuxAppConstants.SwipeDelete.ANIM_MS)
                                .withEndAction(() -> finishSuggestionDelete(rowToRemove))
                                .start();
                    } else {
                        // Not far enough (or the source row was already gone): slide
                        // back, then return to the transparent idle background.
                        v.animate().translationX(0f)
                                .setDuration(TermuxAppConstants.SwipeDelete.RESTORE_ANIM_MS)
                                .withEndAction(() -> {
                                    v.setBackgroundColor(Color.TRANSPARENT);
                                    if (holder != null) holder.hideDeleteUnderlay();
                                })
                                .start();
                    }
                    return true;
                }
            }
            return false;
        });
        rebindSuggestionTextViewInternal(tv, suggestion, input, candidate, useCachedPrefix);
        return tv;
    }

    /** Same threshold as the input-history row: 56 dp or 30% of the row width. */
    private int suggestionDeleteThreshold(int rowWidth) {
        return Math.max(TermuxActivityUtils.dpToPx(mContext,
                        mContext.getResources().getDimensionPixelSize(R.dimen.swipe_delete_threshold)),
                Math.round(rowWidth * TermuxAppConstants.SwipeDelete.THRESHOLD_WIDTH_FRACTION));
    }

    /** Drop the finished delete row and refresh the popup from the shrunken index. */
    private void finishSuggestionDelete(@NonNull View rowToRemove) {
        if (rowToRemove.getParent() instanceof ViewGroup) {
            ((ViewGroup) rowToRemove.getParent()).removeView(rowToRemove);
        }
        if (mCandidates.isEmpty()) {
            dismissAutoCompleteSuggestions();
            return;
        }
        final EditText inputField = mInputField;
        if (inputField != null && mPopupManager.isShowing()) {
            // The suggestion version was bumped by deleteSuggestion(), so this
            // rebuilds the remaining rows and shrinks the popup height.
            mPopupManager.update(inputField.getText().toString(), inputField);
        }
    }

    /**
     * Remove one suggestion from the in-memory index and hide every matching window
     * in the current history snapshot.
     *
     * <p>The message history is read-only for autocomplete: a full-entry row and a
     * sentence row are both just index entries, so neither the history entry nor its
     * sentence is edited. The dismissal is persisted by record id, so the suggestion
     * stays hidden across index rebuilds, directory switches and restarts. Deleting
     * by text hides duplicate windows in other records too; only the current
     * snapshot is touched, so the same text in another directory is independent.
     *
     * <p>Package-private so the swipe gesture and tests exercise the same path.
     *
     * @return whether the candidate was still in the index.
     */
    boolean deleteSuggestion(@NonNull SuggestionEngine.Candidate candidate) {
        if (!mCandidates.remove(candidate)) {
            return false;
        }
        // Rebuild the snapshot if the history changed since the gesture began; the
        // dismissal anchors on the candidate's stable record id, not on its position.
        ensureSnapshot();
        if (mSnapshot != null) {
            mMessageHistoryCtrl.dismissSuggestion(
                    candidate, mSnapshot, mSentenceSuggestionsEnabled);
        }
        // The snapshot's BitSets are live, so a later scan over the same history sees
        // the new flags without a rebuild. Keep it: consecutive swipes can still map
        // their candidate record ids.
        rebuildSuggestionStrings();
        return true;
    }

    /** One-time static styling of a suggestion row (colours re-applied on rebind). */
    private void styleSuggestionTextView(@NonNull TextView tv) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(mPopupItemPadHPx, mPopupItemPadVPx, mPopupItemPadHPx, mPopupItemPadVPx);
        tv.setMaxLines(2);
        // The renderer fades the cut edges itself; a platform ellipsis would be a second,
        // different truncation style.
        TextFade.applyFadeContract(tv);
        tv.setClickable(true);
    }

    /** Rebind a row's text, alpha spans, description and colours. */
    private void rebindSuggestionTextViewInternal(@NonNull TextView tv,
            @NonNull String suggestion, @NonNull String input,
            @Nullable SuggestionEngine.Candidate candidate, boolean useCachedPrefix) {
        tv.setTextColor(mColorSchemeManager.getHistoryTextColor());
        int padH = mPopupItemPadHPx;
        // Constant across the row rebinds of one rebuild, so cache the text width.
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

        // TextView's ellipsis is unreliable for maxLines > 1 on API 21-28, so rows
        // cut the text themselves and fade the cut edges instead of appending "...".
        // The hot path uses the cached bases; the 3-arg overload falls back to live text.
        final boolean fullRow = candidate != null && candidate.kind == SuggestionEngine.Kind.FULL;
        final String typed;
        final int wordStart;
        if (useCachedPrefix) {
            typed = fullRow ? mRowLinePrefix : mRowTail;
            wordStart = fullRow ? mRowLineWordStart : mRowTailWordStart;
        } else {
            final CharSequence text = mInputField != null ? mInputField.getText() : null;
            typed = text != null
                    ? typedPrefixFor(candidate, text, mTypedLineStart, mTypedTailStart)
                    : input;
            wordStart = AutoCompleteTextRenderer.wordStartOffset(typed);
        }
        SpannableString ss = AutoCompleteTextRenderer.buildSuggestionSpannable(
                suggestion, typed, wordStart, availWidth, tv.getPaint());
        // Skip setText when the row's characters and alpha spans are unchanged.
        if (!AutoCompleteTextRenderer.sameRendering(tv.getText(), ss)) {
            tv.setText(ss, TextView.BufferType.SPANNABLE);
        }
        // Avoid rebuilding the description when the row's suggestion did not change.
        if (!suggestion.equals(tv.getTag())) {
            tv.setContentDescription("History: " + suggestion);
        }
        tv.setTag(suggestion);
    }

    /** The candidate metadata for a suggestion string, or null when it is stale. */
    @Nullable
    private SuggestionEngine.Candidate findCandidate(@NonNull String suggestion) {
        for (int i = 0; i < mCandidates.size(); i++) {
            final SuggestionEngine.Candidate c = mCandidates.get(i);
            if (suggestion.equals(c.text)) return c;
        }
        return null;
    }

    /** The typed prefix a candidate matches, used by the non-cached render path. */
    @NonNull
    private String typedPrefixFor(@Nullable SuggestionEngine.Candidate candidate,
            @NonNull CharSequence text, int lineStart, int tailStart) {
        final int start = (candidate != null && candidate.kind == SuggestionEngine.Kind.FULL)
                ? lineStart : tailStart;
        return text.subSequence(start, text.length()).toString();
    }

    /**
     * Replace the typed base with the chosen candidate. Full entries replace the line
     * prefix; sentences replace only the active sentence.
     */
    private void insertCandidate(@NonNull String candidate) {
        if (mInputField == null) return;
        Editable editable = mInputField.getText();
        if (editable == null) return;
        final SuggestionEngine.Candidate c = findCandidate(candidate);
        if (c == null) {
            // The row outlived the index entry it was built from (the history
            // changed under it). Never insert text the history no longer backs.
            dismissAutoCompleteSuggestions();
            return;
        }
        int caret = mInputField.getSelectionStart();
        if (caret < 0 || caret > editable.length()) caret = editable.length();
        final boolean lineBase = c.kind == SuggestionEngine.Kind.FULL;
        final int replaceStart = lineBase
                ? SuggestionEngine.activeLineStart(editable, caret)
                : SuggestionEngine.activeSentenceStart(editable, caret);
        final String newText = editable.subSequence(0, replaceStart).toString()
                + candidate
                + editable.subSequence(caret, editable.length());
        mInputField.setText(newText);
        mInputField.setSelection(replaceStart + candidate.length());
        resetScanState();
    }

    private void showAutoCompletePopup(@NonNull EditText inputField, @NonNull String input) {
        mPopupManager.show(inputField, input);
    }

    private void dismissAutoCompleteSuggestions() {
        mPopupManager.dismiss();
    }
}
