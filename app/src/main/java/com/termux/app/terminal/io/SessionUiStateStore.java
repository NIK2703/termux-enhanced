package com.termux.app.terminal.io;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single in-memory authority for ALL per-session UI state plus a couple of global
 * UI flags. Supersedes TextInputSessionStateManager (keeps its public API 1:1 and
 * adds scroll position + soft-keyboard intent + (de)serialization).
 *
 * <p>Persistence tiers:
 * <ul>
 * <li>L1 (this object): authoritative while the process is alive, keyed by
 *     {@link TerminalSession#mHandle}.</li>
 * <li>L2 ({@link #saveToBundle(Bundle)} / {@link #restoreFromBundle(Bundle)}):
 *     survives activity recreation. The TermuxService (and therefore the session
 *     handles) survives recreation, so keying by handle stays valid.</li>
 * <li>L3 ({@link #exportToJson(List, int)} / {@link #importFromJson(String, List)}):
 *     survives process death. Handles do NOT survive (sessions are rebuilt from the
 *     snapshot and get fresh handles), so the JSON is indexed by position in the
 *     session list and re-keyed onto the restored sessions in the same order.</li>
 * </ul>
 */
public final class SessionUiStateStore {

    // Bundle keys — legacy keys kept identical so an in-flight recreation after an
    // upgrade still reads previously saved state.
    public static final String ARG_TEXT_INPUT_PER_SESSION = "text_input_per_session";
    public static final String ARG_TEXT_INPUT_VISIBLE_PER_SESSION = "text_input_visible_per_session";
    public static final String ARG_FOCUS_ON_INPUT_PER_SESSION = "focus_on_input_per_session";
    public static final String ARG_TEXT_INPUT_CARET_PER_SESSION = "text_input_caret_per_session";
    // New keys.
    public static final String ARG_SCROLL_TOP_PER_SESSION = "scroll_top_per_session";
    public static final String ARG_SCROLL_ROWS_PER_SESSION = "scroll_rows_per_session";
    public static final String ARG_SOFT_KEYBOARD_VISIBLE = "soft_keyboard_visible";
    public static final String ARG_ACTIVE_SESSION_INDEX = "active_session_index";
    // New keys.
    public static final String ARG_KB_INTENT_PER_SESSION = "kb_intent_per_session";

    /** Hard cap for stored per-session input text so Bundle/JSON can never explode. */
    private static final int MAX_STORED_INPUT_LENGTH = 32_000;

    /** UI state of a single terminal session (keyed by TerminalSession.mHandle). */
    public static final class SessionUiState {
        @Nullable public String textInput;
        public int caret = -1;
        public boolean panelVisible;
        public boolean hasPanelVisible;
        public boolean focusOnInput;
        /** Whether the soft keyboard SHOULD be open for this session (per-session restore intent). */
        public boolean hasKeyboardIntent = false;
        public boolean keyboardIntent = true;
        /** TerminalView.mTopRow at save time (0 == follow-the-bottom). */
        public int scrollTopRow;
        /** activeTranscriptRows() at save time, for proportional remap on restore. */
        public int scrollTranscriptRows;
    }

    private final HashMap<String, SessionUiState> mStates = new HashMap<>();

    /** Global: whether the soft keyboard SHOULD be visible (restore intent). */
    private boolean mSoftKeyboardVisibleIntent = true;
    /** Active session index recorded at save time (Bundle restore path). */
    private int mActiveSessionIndex = -1;
    /** Active index read back from persisted JSON (process-death path). */
    private int mImportedActiveIndex = -1;

    @NonNull
    private SessionUiState state(@NonNull String handle) {
        SessionUiState s = mStates.get(handle);
        if (s == null) {
            s = new SessionUiState();
            mStates.put(handle, s);
        }
        return s;
    }

    // ── Text input content ──────────────────────────────────────────────

    public void saveInput(@NonNull String handle, @Nullable String text) {
        if (text != null && !text.isEmpty()) {
            if (text.length() > MAX_STORED_INPUT_LENGTH) {
                text = text.substring(text.length() - MAX_STORED_INPUT_LENGTH);
            }
            state(handle).textInput = text;
        } else {
            SessionUiState s = mStates.get(handle);
            if (s != null) s.textInput = null;
        }
    }

    @Nullable
    public String getInputText(@NonNull String handle) {
        SessionUiState s = mStates.get(handle);
        return s != null ? s.textInput : null;
    }

    public boolean hasInput(@NonNull String handle) {
        SessionUiState s = mStates.get(handle);
        return s != null && s.textInput != null;
    }

    // ── Panel visibility ────────────────────────────────────────────────

    public void setVisible(@NonNull String handle, boolean visible) {
        SessionUiState s = state(handle);
        s.panelVisible = visible;
        s.hasPanelVisible = true;
    }

    public boolean isVisible(@NonNull String handle) {
        SessionUiState s = mStates.get(handle);
        return s != null && s.panelVisible;
    }

    public boolean hasVisible(@NonNull String handle) {
        SessionUiState s = mStates.get(handle);
        return s != null && s.hasPanelVisible;
    }

    // ── Focus (panel vs terminal) ───────────────────────────────────────

    public void setFocusOnInput(@NonNull String handle, boolean focusOnInput) {
        state(handle).focusOnInput = focusOnInput;
    }

    public void setFocusOnInput(@Nullable TerminalSession session, boolean focusOnInput) {
        if (session != null) state(session.mHandle).focusOnInput = focusOnInput;
    }

    public boolean isFocusOnInput(@NonNull String handle) {
        SessionUiState s = mStates.get(handle);
        return s != null && s.focusOnInput;
    }

    public boolean isFocusOnInput(@Nullable TerminalSession session) {
        if (session == null) return false;
        SessionUiState s = mStates.get(session.mHandle);
        return s != null && s.focusOnInput;
    }

    // ── Caret ───────────────────────────────────────────────────────────

    /** Values < 0 (e.g. the -1 sentinel of EditText.getSelectionStart()) are ignored. */
    public void setCaret(@NonNull String handle, int caret) {
        if (caret < 0) return;
        state(handle).caret = caret;
    }

    /** @return stored caret position, or -1 if none recorded. */
    public int getCaret(@NonNull String handle) {
        SessionUiState s = mStates.get(handle);
        return (s != null) ? s.caret : -1;
    }

    public boolean hasCaret(@NonNull String handle) {
        SessionUiState s = mStates.get(handle);
        return s != null && s.caret >= 0;
    }

    // ── Terminal scroll position (TerminalView.mTopRow) ─────────────────

    /**
     * Save the scroll position of one session's terminal view.
     *
     * @param session        owner session; null is ignored.
     * @param topRow         TerminalView.getTopRow() (0 or negative).
     * @param transcriptRows activeTranscriptRows() at save time; used for the
     *                       proportional remap when the transcript size differs
     *                       at restore time (reflow / more output in between).
     */
    public void setScrollState(@Nullable TerminalSession session, int topRow, int transcriptRows) {
        if (session == null) return;
        SessionUiState s = state(session.mHandle);
        s.scrollTopRow = Math.min(0, topRow);
        s.scrollTranscriptRows = Math.max(0, transcriptRows);
    }

    public int getScrollTopRow(@Nullable TerminalSession session) {
        if (session == null) return 0;
        SessionUiState s = mStates.get(session.mHandle);
        return (s != null) ? s.scrollTopRow : 0;
    }

    public int getScrollTranscriptRows(@Nullable TerminalSession session) {
        if (session == null) return 0;
        SessionUiState s = mStates.get(session.mHandle);
        return (s != null) ? s.scrollTranscriptRows : 0;
    }

    // ── Global state ────────────────────────────────────────────────────

    public void setSoftKeyboardVisibleIntent(boolean visible) {
        mSoftKeyboardVisibleIntent = visible;
    }

    public boolean isSoftKeyboardVisibleIntent() {
        return mSoftKeyboardVisibleIntent;
    }

    // ── Per-session keyboard intent ─────────────────────────────────────

    /**
     * Record whether the soft keyboard should be open for the given session.
     * Only honest user-driven IME transitions (foreground, no restore/switch
     * in progress) reach this.
     */
    public void setSoftKeyboardIntent(@Nullable TerminalSession session, boolean visible) {
        if (session == null) return;
        SessionUiState s = state(session.mHandle);
        s.hasKeyboardIntent = true;
        s.keyboardIntent = visible;
    }

    /**
     * Read the per-session keyboard intent. Falls back to the global intent for
     * sessions that have no recorded one (e.g. freshly created), so a brand-new
     * tab inherits "what the user was doing".
     */
    public boolean isSoftKeyboardIntent(@Nullable TerminalSession session) {
        if (session == null) return mSoftKeyboardVisibleIntent;
        SessionUiState s = mStates.get(session.mHandle);
        if (s == null || !s.hasKeyboardIntent) return mSoftKeyboardVisibleIntent;
        return s.keyboardIntent;
    }

    public void setActiveSessionIndex(int index) {
        mActiveSessionIndex = index;
    }

    public int getActiveSessionIndex() {
        return mActiveSessionIndex;
    }

    /** Active index restored from persisted JSON (process-death path); -1 if none. */
    public int getImportedActiveIndex() {
        return mImportedActiveIndex;
    }

    // ── Cleanup ─────────────────────────────────────────────────────────

    /**
     * Clear only text/caret/focus, KEEPING the recorded visibility flag
     * (used after sending when the panel stays open).
     */
    public void clearInput(@NonNull String handle) {
        SessionUiState s = mStates.get(handle);
        if (s == null) return;
        s.textInput = null;
        s.caret = -1;
        s.focusOnInput = false;
    }

    public void clear(@NonNull String handle) {
        mStates.remove(handle);
    }

    public void clear(@Nullable TerminalSession session) {
        if (session != null) clear(session.mHandle);
    }

    public void clearAll() {
        mStates.clear();
        mActiveSessionIndex = -1;
        mImportedActiveIndex = -1;
    }

    // ── L2: Bundle (activity recreation; handles still valid) ───────────

    public void restoreFromBundle(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        Bundle textBundle = savedInstanceState.getBundle(ARG_TEXT_INPUT_PER_SESSION);
        if (textBundle != null) {
            for (String key : textBundle.keySet()) {
                String value = textBundle.getString(key);
                if (value != null) state(key).textInput = value;
            }
        }
        Bundle visBundle = savedInstanceState.getBundle(ARG_TEXT_INPUT_VISIBLE_PER_SESSION);
        if (visBundle != null) {
            for (String key : visBundle.keySet()) {
                SessionUiState s = state(key);
                s.panelVisible = visBundle.getBoolean(key);
                s.hasPanelVisible = true;
            }
        }
        Bundle focusBundle = savedInstanceState.getBundle(ARG_FOCUS_ON_INPUT_PER_SESSION);
        if (focusBundle != null) {
            for (String key : focusBundle.keySet()) {
                state(key).focusOnInput = focusBundle.getBoolean(key);
            }
        }
        Bundle caretBundle = savedInstanceState.getBundle(ARG_TEXT_INPUT_CARET_PER_SESSION);
        if (caretBundle != null) {
            for (String key : caretBundle.keySet()) {
                state(key).caret = caretBundle.getInt(key);
            }
        }
        Bundle scrollRowBundle = savedInstanceState.getBundle(ARG_SCROLL_TOP_PER_SESSION);
        Bundle scrollRowsBundle = savedInstanceState.getBundle(ARG_SCROLL_ROWS_PER_SESSION);
        if (scrollRowBundle != null) {
            for (String key : scrollRowBundle.keySet()) {
                SessionUiState s = state(key);
                s.scrollTopRow = scrollRowBundle.getInt(key);
                if (scrollRowsBundle != null && scrollRowsBundle.containsKey(key)) {
                    s.scrollTranscriptRows = scrollRowsBundle.getInt(key);
                }
            }
        }
        Bundle kbBundle = savedInstanceState.getBundle(ARG_KB_INTENT_PER_SESSION);
        if (kbBundle != null) {
            for (String key : kbBundle.keySet()) {
                SessionUiState s = state(key);
                s.hasKeyboardIntent = true;
                s.keyboardIntent = kbBundle.getBoolean(key);
            }
        }
        if (savedInstanceState.containsKey(ARG_SOFT_KEYBOARD_VISIBLE)) {
            mSoftKeyboardVisibleIntent = savedInstanceState.getBoolean(ARG_SOFT_KEYBOARD_VISIBLE, true);
        }
        mActiveSessionIndex = savedInstanceState.getInt(ARG_ACTIVE_SESSION_INDEX, -1);
    }

    public void saveToBundle(@NonNull Bundle outState) {
        // Each per-category Bundle is allocated LAZILY, on the first value that actually needs it.
        // The previous version created all seven up front even when every one of them stayed empty
        // (cold start, or a session whose only recorded state is the default) — seven allocations
        // plus seven isEmpty() checks per recreation for nothing.
        Bundle textBundle = null;
        Bundle visBundle = null;
        Bundle focusBundle = null;
        Bundle caretBundle = null;
        Bundle scrollRowBundle = null;
        Bundle scrollRowsBundle = null;
        Bundle kbBundle = null;

        for (Map.Entry<String, SessionUiState> e : mStates.entrySet()) {
            String key = e.getKey();
            SessionUiState s = e.getValue();
            if (s.textInput != null) {
                if (textBundle == null) textBundle = new Bundle();
                textBundle.putString(key, s.textInput);
            }
            if (s.hasPanelVisible) {
                if (visBundle == null) visBundle = new Bundle();
                visBundle.putBoolean(key, s.panelVisible);
            }
            // Only a TRUE focus is written. {@code SessionUiState.focusOnInput} defaults to false
            // and restoreFromBundle() reads it with getBoolean(key) — which also yields false for a
            // missing key — so dropping the false entries is behaviourally identical while keeping
            // the bundle from carrying one entry per session for the overwhelmingly common
            // "focus is on the terminal" case.
            if (s.focusOnInput) {
                if (focusBundle == null) focusBundle = new Bundle();
                focusBundle.putBoolean(key, true);
            }
            if (s.caret >= 0) {
                if (caretBundle == null) caretBundle = new Bundle();
                caretBundle.putInt(key, s.caret);
            }
            if (s.scrollTopRow != 0 || s.scrollTranscriptRows != 0) {
                if (scrollRowBundle == null) scrollRowBundle = new Bundle();
                if (scrollRowsBundle == null) scrollRowsBundle = new Bundle();
                scrollRowBundle.putInt(key, s.scrollTopRow);
                scrollRowsBundle.putInt(key, s.scrollTranscriptRows);
            }
            if (s.hasKeyboardIntent) {
                if (kbBundle == null) kbBundle = new Bundle();
                kbBundle.putBoolean(key, s.keyboardIntent);
            }
        }

        if (textBundle != null) outState.putBundle(ARG_TEXT_INPUT_PER_SESSION, textBundle);
        if (visBundle != null) outState.putBundle(ARG_TEXT_INPUT_VISIBLE_PER_SESSION, visBundle);
        if (focusBundle != null) outState.putBundle(ARG_FOCUS_ON_INPUT_PER_SESSION, focusBundle);
        if (caretBundle != null) outState.putBundle(ARG_TEXT_INPUT_CARET_PER_SESSION, caretBundle);
        if (scrollRowBundle != null) outState.putBundle(ARG_SCROLL_TOP_PER_SESSION, scrollRowBundle);
        if (scrollRowsBundle != null) outState.putBundle(ARG_SCROLL_ROWS_PER_SESSION, scrollRowsBundle);
        if (kbBundle != null) outState.putBundle(ARG_KB_INTENT_PER_SESSION, kbBundle);

        outState.putBoolean(ARG_SOFT_KEYBOARD_VISIBLE, mSoftKeyboardVisibleIntent);
        outState.putInt(ARG_ACTIVE_SESSION_INDEX, mActiveSessionIndex);
    }

    // ── L3: JSON (process death; re-keyed by index) ─────────────────────

    /**
     * Serialize the store keyed by POSITION in the live session list. Session
     * handles are meaningless after a process death, so never put them in here.
     *
     * @param sessionsInOrder live session list in service/snapshot order.
     * @param activeIndex     index of the active session at save time.
     * @return JSON string or null on failure.
     */
    @Nullable
    public String exportToJson(@NonNull List<TerminalSession> sessionsInOrder, int activeIndex) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("activeIndex", activeIndex);
            // NOTE: panel visibility, panel focus and the keyboard intents are
            // deliberately NOT persisted — they are RAM-only (L1) by design and
            // must not survive the app. A fresh run starts with closed panels and
            // the platform-default keyboard state.

            JSONArray arr = new JSONArray();
            for (TerminalSession session : sessionsInOrder) {
                SessionUiState s = (session != null) ? mStates.get(session.mHandle) : null;
                if (s == null) {
                    arr.put(JSONObject.NULL); // keep index alignment
                    continue;
                }
                JSONObject o = new JSONObject();
                if (s.textInput != null) o.put("text", s.textInput);
                o.put("caret", s.caret);
                o.put("scrollRow", s.scrollTopRow);
                o.put("scrollRows", s.scrollTranscriptRows);
                arr.put(o);
            }
            root.put("sessions", arr);
            return root.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Re-key persisted (index-based) state onto freshly restored sessions.
     * {@code sessionsInOrder} MUST be in the same order the snapshot was saved in
     * (the snapshot manager restores sessions in order, so this holds).
     */
    public void importFromJson(@Nullable String json, @NonNull List<TerminalSession> sessionsInOrder) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(json);
            mImportedActiveIndex = root.optInt("activeIndex", -1);
            // NOTE: keyboardVisible / panelVisible / hasPanelVisible / focusOnInput /
            // "kb" are NOT read back: those states are RAM-only and must not survive
            // the app. Old JSON files may still carry the keys — they are ignored.

            JSONArray arr = root.optJSONArray("sessions");
            if (arr == null) return;
            int n = Math.min(arr.length(), sessionsInOrder.size());
            for (int i = 0; i < n; i++) {
                TerminalSession session = sessionsInOrder.get(i);
                JSONObject o = arr.optJSONObject(i);
                if (session == null || o == null) continue;

                SessionUiState s = state(session.mHandle);
                String text = o.optString("text", null);
                s.textInput = (text != null && text.length() > MAX_STORED_INPUT_LENGTH)
                        ? text.substring(text.length() - MAX_STORED_INPUT_LENGTH) : text;
                s.caret = o.optInt("caret", -1);
                s.scrollTopRow = o.optInt("scrollRow", 0);
                s.scrollTranscriptRows = o.optInt("scrollRows", 0);
            }
        } catch (Exception ignored) {
        }
    }
}
