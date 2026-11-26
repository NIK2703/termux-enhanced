package com.termux.app.terminal.io.autocomplete;

import com.termux.app.TermuxAppConstants;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure-data controller for the per-directory / global message (command) history.
 *
 * <p>Owns a {@link HistoryStore} (records with stable ids, one id space for global,
 * pending and per-directory lists), the parsed sentence-span cache, and persistence
 * to {@link SharedPreferences}. The activity (or any consumer) reads
 * {@link #getHistoryList()} and {@link #getHistoryVersion()} to build popup UI, and
 * calls {@link #addToMessageHistory(String, String)} after a command is executed.
 *
 * <p>Entries are trimmed at the edges; internal spacing is preserved.
 *
 * <p>Per-directory writes made while the cwd is unknown go to the attached
 * {@code pending} list. The first real cwd drains pending (and, once, the legacy
 * global history) into that directory. {@code perDirInitialized} records that the
 * one-time migration has happened; it is not inferred from the presence of empty
 * directory keys.
 */
public final class MessageHistoryController {

    /** Legacy v1 keys: consumed once, then removed after the v3 document is committed. */
    private static final String PREF_MESSAGE_HISTORY = "message_history";
    private static final String PREF_MESSAGE_HISTORY_PER_DIR = "message_history_per_directory";

    /** Unified document key. v3 now lives here; v2 documents are upgraded on load. */
    private static final String PREF_MESSAGE_HISTORY_V2 = "message_history_v2";
    private static final String PREF_MIGRATION_MARKER = "message_history_migrated_v3";
    private static final String PREF_CORRUPT_BACKUP = "message_history_v3_corrupt";

    /** Settings keys, read here so a write from outside the activity is configured the same way. */

    /** Default when the pref is unset; the activity reads it too. */
    /** Upper bound of the history retention setting. */
    public static final int MESSAGE_HISTORY_MAX_LIMIT = 1000;
    /** Entries rendered in the context history popup; older ones remain available to autocomplete. */

    // Process-scoped singleton: the bubble is a second TermuxActivity instance, and both
    // windows must see the same history. A per-instance controller gave each window its
    // own in-memory list, so a command sent from the bubble was invisible to the full-screen
    // window until it reloaded from disk.
    private static volatile MessageHistoryController sShared;

    @NonNull
    public static MessageHistoryController shared(@NonNull SharedPreferences prefs) {
        if (sShared == null) {
            synchronized (MessageHistoryController.class) {
                if (sShared == null) {
                    sShared = new MessageHistoryController(prefs);
                }
            }
        }
        return sShared;
    }

    /** Records and the selected list. */
    @NonNull private final HistoryStore mStore = new HistoryStore();

    /** Persistent swipe-left flags, keyed by record id. */
    @NonNull private final SuggestionDismissals mDismissals;

    /** Parsed line/sentence offsets keyed by entry text, reused across snapshots. */
    @NonNull
    private final HashMap<String, HistorySnapshot.Spans> mSpansCache = new HashMap<>();

    /** Single writer for the history document. */
    @NonNull private final HistoryPersistence mPersistence;

    /**
     * Whether {@link #load} has populated the in-memory state from disk. Guards the mutators, not
     * the constructor, which does no I/O: a mutator reached without loading first would otherwise
     * persist an empty store and lose every directory's history.
     */
    private boolean mLoaded;

    /**
     * Incremented on every modification so consumers (e.g. the auto-complete popup)
     * can cheaply detect that the data has changed.
     */
    private int mHistoryVersion = 0;

    /** Max entries kept in-memory and persisted. Replaced by {@link #applyStoredSettings} on load. */
    private int mMessageHistoryMax = TermuxAppConstants.History.MESSAGE_MAX_DEFAULT;

    private boolean mPerDirectoryMessageHistory = false;

    /**
     * Whether sentence offsets are materialized for autocomplete. When false, adding
     * and loading history only parses the first line and the folded text.
     */
    private boolean mSentenceIndexEnabled = true;

    private final SharedPreferences mPrefs;

    private MessageHistoryController(@NonNull SharedPreferences prefs) {
        mPrefs = prefs;
        mDismissals = new SuggestionDismissals(prefs);
        mPersistence = new HistoryPersistence(
                (document, sync) -> writeDocument(prefs, document, sync));
        applyStoredSettings();
    }

    /**
     * Read the settings that decide which list a write lands in and how much of it survives, from
     * the same prefs the activity configures the controller from.
     *
     * <p>Done at construction because a notification reply can be the first thing in a process: the
     * broadcast starts the app with no activity, so nothing has applied the user's settings yet.
     */
    private void applyStoredSettings() {
        mMessageHistoryMax = clampMaxSize(mPrefs.getInt(TermuxAppConstants.Preferences.MESSAGE_HISTORY_MAX, TermuxAppConstants.History.MESSAGE_MAX_DEFAULT));
        mPerDirectoryMessageHistory = mPrefs.getBoolean(TermuxAppConstants.Preferences.PER_DIRECTORY_MESSAGE_HISTORY, false);
        mSentenceIndexEnabled = mPrefs.getBoolean(
                TermuxAppConstants.Preferences.AUTOCOMPLETE_SENTENCES_ENABLED, true);
    }

    // ── Persistence ──

    @NonNull
    private HistoryDocument snapshotDocument() {
        return mStore.snapshot();
    }

    private void schedulePersist() {
        mPersistence.schedule(this::snapshotDocument);
    }

    /**
     * Persist the current document now. With {@code sync=true}, wait for the commit;
     * the single writer guarantees that an older in-flight write cannot land after it.
     */
    private void saveNow(boolean syncCommit) {
        mPersistence.saveNow(this::snapshotDocument, syncCommit);
    }

    /**
     * If a debounced persist is pending, run it now (synchronously, on disk). Called
     * by the host from {@code onPause()} / {@code onStop()} (and before mode switches)
     * so history is always on disk before the process can be stopped.
     */
    public void flushPersist() {
        mPersistence.saveNow(this::snapshotDocument, true);
        mDismissals.flush();
    }

    /** Cancel a pending debounced persist without writing (teardown). */
    public void cancelPersist() {
        mPersistence.cancel();
    }

    /** Stop the writer. Used by tests and by process teardown. */
    public void close() {
        cancelPersist();
        mPersistence.close();
    }

    private static boolean writeDocument(@NonNull SharedPreferences prefs,
            @NonNull HistoryDocument document, boolean syncCommit) {
        String json = HistoryJsonCodec.encode(document);
        if (json == null) return false;
        SharedPreferences.Editor editor = prefs.edit().putString(PREF_MESSAGE_HISTORY_V2, json);
        if (syncCommit) return editor.commit();
        editor.apply();
        return true;
    }

    // ── Feature flags ──

    public void setPerDirectoryEnabled(boolean enabled) {
        if (mPerDirectoryMessageHistory == enabled) return;
        mPerDirectoryMessageHistory = enabled;
        if (!mLoaded) return;
        if (!enabled) {
            mStore.useGlobal();
            mHistoryVersion++;
            rebuildSpansCache();
        } else {
            boolean changed = false;
            // Leaving global mode: the entries the user just saw are global history.
            // Move them into the pending buffer so the first real cwd can adopt them;
            // otherwise an initialized per-directory store would hide them.
            if (mStore.isCurrentGlobal() && !mStore.global().isEmpty()) {
                HistoryStore.MergeResult global = mStore.mergeGlobalIntoPending();
                mDismissals.remap(global.idRemap);
                changed = global.changed;
            }
            String cwd = mStore.currentCwd();
            if (cwd != null && !".".equals(cwd)) {
                changed |= selectDirectory(cwd);
            } else {
                changed |= beginPerDirectorySelection(".");
            }
            if (changed) schedulePersist();
            mHistoryVersion++;
            rebuildSpansCache();
        }
    }

    public boolean isPerDirectoryEnabled() {
        return mPerDirectoryMessageHistory;
    }

    /**
     * Enable or disable materializing sentence offsets. Switching modes rebuilds the
     * span cache; the history version is unchanged because entries and dismissals are
     * unchanged.
     */
    public void setSentenceIndexEnabled(boolean enabled) {
        if (mSentenceIndexEnabled == enabled) return;
        mSentenceIndexEnabled = enabled;
        if (mLoaded) rebuildSpansCache();
    }

    public boolean isSentenceIndexEnabled() {
        return mSentenceIndexEnabled;
    }

    /** Set the retention limit; lowering it trims every stored list immediately. */
    public void setMaxSize(int max) {
        mMessageHistoryMax = clampMaxSize(max);
        if (mLoaded && trimAll()) {
            saveNow(false);
            mHistoryVersion++;
        }
    }

    private static int clampMaxSize(int max) {
        return Math.max(0, Math.min(MESSAGE_HISTORY_MAX_LIMIT, max));
    }

    // ── Observers ──

    /**
     * Live, read-only view of the selected list. Callers keep the old string API;
     * mutation throws {@link UnsupportedOperationException}.
     */
    @NonNull
    public List<String> getHistoryList() {
        return HistoryStore.textView(mStore.current());
    }

    /** Records backing {@link #getHistoryList()}, for the snapshot builder. */
    @NonNull
    List<HistoryEntry> getHistoryEntries() {
        return mStore.current();
    }

    /** Persistent dismissal flags consumed by the snapshot/engine. */
    @NonNull
    SuggestionDismissals getDismissals() {
        return mDismissals;
    }

    /**
     * Hide the clicked suggestion window and every duplicate window in the current
     * snapshot. The record id is the primary anchor, because the clicked candidate may
     * come from a snapshot that was rebuilt since the gesture began; text matching is
     * the secondary rule that keeps duplicates from reappearing.
     */
    boolean dismissSuggestion(@NonNull SuggestionEngine.Candidate candidate,
            @NonNull HistorySnapshot snapshot) {
        return dismissSuggestion(candidate, snapshot, true);
    }

    /**
     * Hide a clicked window and its duplicates. With {@code includeSentences = false}
     * the sentence windows are not walked; a full-entry dismissal still hides every
     * sentence of that record through dismissal bit 0.
     */
    boolean dismissSuggestion(@NonNull SuggestionEngine.Candidate candidate,
            @NonNull HistorySnapshot snapshot, boolean includeSentences) {
        int marked = 0;

        // Anchor first: this is the window the user actually swiped.
        if (mDismissals.mark(candidate.entryId, candidate.dismissalBit())) marked++;

        final String folded = SuggestionEngine.foldedKey(candidate.text);
        final boolean scanSentences =
                includeSentences || candidate.kind == SuggestionEngine.Kind.SENTENCE;
        for (int e = 0; e < snapshot.entries.length; e++) {
            final long id = snapshot.entryId(e);
            if (id <= 0) continue;
            final char[] fold = snapshot.fold(e);
            final int entryLen = snapshot.entries[e].length();
            if (entryLen > 0 && SuggestionEngine.foldedEquals(fold, 0, entryLen, folded)
                    && mDismissals.mark(id, 0)) {
                marked++;
            }
            if (!scanSentences) continue;
            final HistorySnapshot.SentenceSpans sentences = snapshot.sentences(e);
            for (int s = 0; s < sentences.count; s++) {
                final int start = sentences.starts[s];
                final int end = sentences.ends[s];
                if (end > start
                        && SuggestionEngine.foldedEquals(fold, start, end - start, folded)
                        && mDismissals.mark(id, s + 1)) {
                    marked++;
                }
            }
        }
        if (marked > 0) mDismissals.persist();
        return marked > 0;
    }

    /** Forget every hidden suggestion. The caller must invalidate its snapshot. */
    public void resetHiddenSuggestions() {
        mDismissals.clearAll();
        mHistoryVersion++;
    }

    /** Whether {@code message}, ignoring edge whitespace, is already in the history. */
    public boolean containsMessage(@Nullable String message) {
        String normalized = trimMessage(message);
        return !normalized.isEmpty() && mStore.currentIndexOf(normalized) >= 0;
    }

    /** Whether {@code message} is null or consists only of whitespace. */
    public static boolean isBlankMessage(@Nullable String message) {
        return HistoryText.isBlank(message);
    }

    /** Compare two history messages, ignoring leading and trailing whitespace. */
    public static boolean messagesEqual(@Nullable String first, @Nullable String second) {
        return trimMessage(first).equals(trimMessage(second));
    }

    /** Strips edge whitespace, preserving internal spacing. */
    @NonNull
    private static String trimMessage(@Nullable String message) {
        return HistoryText.trim(message);
    }

    public int getHistoryVersion() {
        return mHistoryVersion;
    }

    /** Parsed offsets cache consumed by {@link HistorySnapshot}. */
    @NonNull
    Map<String, HistorySnapshot.Spans> getSpansCache() {
        return mSpansCache;
    }

    /**
     * Parse and cache one entry's offsets. Sentence offsets are materialized only
     * while sentence suggestions are enabled; otherwise they stay lazy and unused.
     */
    private void cacheSpans(@NonNull String entry) {
        HistorySnapshot.Spans spans = mSpansCache.get(entry);
        if (spans == null) {
            spans = HistorySnapshot.parseSpans(entry);
            mSpansCache.put(entry, spans);
        }
        if (mSentenceIndexEnabled) spans.sentences(entry);
    }

    /** Re-parse the current list after a load or directory switch. */
    private void rebuildSpansCache() {
        mSpansCache.clear();
        for (HistoryEntry entry : mStore.current()) {
            cacheSpans(entry.text);
        }
    }

    @Nullable
    public String getHistoryCurrentDirectory() {
        return mStore.currentCwd();
    }

    // ── Clearing ──

    /**
     * Clear history for the current directory (per-directory mode) or globally.
     *
     * <p>A null cwd clears the visible attached list. It never detaches the current
     * list, so the clear is persisted instead of being silently discarded.
     */
    public void clearCurrent(@Nullable String cwd) {
        List<HistoryEntry> removed;
        if (mPerDirectoryMessageHistory) {
            String current = mStore.currentCwd();
            if (cwd != null && !cwd.equals(current)) {
                removed = ".".equals(cwd) ? mStore.clearPending() : mStore.clearDirectory(cwd);
            } else {
                removed = mStore.clearCurrent();
            }
        } else {
            removed = mStore.clearCurrent();
        }
        mHistoryVersion++;
        dropSpans(removed);
        mDismissals.retainIds(mStore.allIds());
        // A clear is a durability barrier: the caller expects the removed entries to
        // be gone from disk when this returns.
        saveNow(true);
    }

    /**
     * Clear the entire per-directory history store, including the global fallback
     * and the pending buffer, and leave the store on the attached pending list.
     */
    public void clearAllPerDirectory() {
        List<HistoryEntry> removed = mStore.clearAll();
        mStore.setPerDirInitialized(false);
        mHistoryVersion++;
        dropSpans(removed);
        mDismissals.retainIds(mStore.allIds());
        // A clear is a durability barrier: the caller expects the removed entries to
        // be gone from disk when this returns.
        saveNow(true);
    }

    // ── CWD change ──

    /**
     * Called when the current session's working directory has changed.
     */
    public void onHistoryDirectoryChanged(@NonNull String oldCwd, @NonNull String newCwd) {
        if (!mPerDirectoryMessageHistory) return;
        if (newCwd.equals(mStore.currentCwd())) return;
        selectDirectoryFromUser(newCwd);
    }

    private void selectDirectoryFromUser(@NonNull String cwd) {
        boolean changed;
        if (".".equals(cwd)) {
            changed = beginPerDirectorySelection(".");
        } else {
            changed = selectDirectory(cwd);
        }
        if (changed) schedulePersist();
        mHistoryVersion++;
        rebuildSpansCache();
    }

    /**
     * Select a real directory, performing the one-time migration and draining the
     * pending buffer. Returns whether the stored document changed.
     */
    private boolean selectDirectory(@NonNull String cwd) {
        boolean changed = false;
        if (!mStore.isPerDirInitialized()) {
            HistoryStore.MergeResult legacy = mStore.mergeGlobalIntoPending();
            mDismissals.remap(legacy.idRemap);
            changed |= legacy.changed;
            mStore.setPerDirInitialized(true);
            changed = true;
        }
        HistoryStore.MergeResult pending = mStore.mergePendingIntoDirectory(cwd);
        mDismissals.remap(pending.idRemap);
        changed |= pending.changed;

        mStore.useDirectory(cwd);
        List<HistoryEntry> removed = mStore.trimCurrent(mMessageHistoryMax);
        if (!removed.isEmpty()) {
            dropSpans(removed);
            mDismissals.retainIds(mStore.allIds());
            changed = true;
        }
        return changed;
    }

    /**
     * Select the attached pending list for a {@code "."} fallback. On the first
     * per-directory use, legacy/global history moves into pending but stays pending
     * until a real cwd is known.
     */
    private boolean beginPerDirectorySelection(@NonNull String fallbackCwd) {
        if (!".".equals(fallbackCwd)) return selectDirectory(fallbackCwd);

        boolean changed = false;
        if (!mStore.isPerDirInitialized()) {
            HistoryStore.MergeResult legacy = mStore.mergeGlobalIntoPending();
            mDismissals.remap(legacy.idRemap);
            changed |= legacy.changed;
        }
        mStore.usePending();
        List<HistoryEntry> removed = mStore.trimCurrent(mMessageHistoryMax);
        if (!removed.isEmpty()) {
            dropSpans(removed);
            mDismissals.retainIds(mStore.allIds());
            changed = true;
        }
        return changed;
    }

    // ── Mutations ──

    /**
     * Add a just-sent message to the history.
     *
     * @param message  The command text that was executed.
     * @param cwd      For per-directory mode — the current CWD when the command
     *                 was sent (used for cross-directory detection).
     */
    public void addToMessageHistory(@NonNull String message, @Nullable String cwd) {
        String normalized = trimMessage(message);
        if (normalized.isEmpty()) return;

        ensureLoaded(cwd);
        snapshotCurrentDirectoryIfChanged(cwd);

        boolean changed = mStore.addCurrent(normalized);
        cacheSpans(normalized);
        boolean trimmed = trimCurrent();
        if (!changed && !trimmed) return;

        schedulePersist();
        mHistoryVersion++;
    }

    /**
     * Add a message that belongs to {@code cwd} without assuming {@code cwd} is the
     * directory the user is looking at. A background reply must not repoint the
     * foreground controller or drain its pending buffer.
     */
    public void addToMessageHistoryInDirectory(@NonNull String message, @Nullable String cwd) {
        String normalized = trimMessage(message);
        if (normalized.isEmpty()) return;

        ensureLoaded(cwd);
        if (!mPerDirectoryMessageHistory || cwd == null || cwd.equals(mStore.currentCwd())) {
            addToMessageHistory(normalized, cwd);
            return;
        }

        boolean changed = mStore.addToDirectory(cwd, normalized);
        cacheSpans(normalized);
        boolean trimmed = trimDirectory(cwd);
        if (!changed && !trimmed) return;

        schedulePersist();
        mHistoryVersion++;
    }

    /**
     * Record a passively saved message (cleared from the input field, or the text replaced by a
     * history pick). A brand-new message is inserted at the TOP; a message already in the history
     * keeps its exact position.
     */
    public void addNewOnTop(@NonNull String message, @Nullable String cwd) {
        String normalized = trimMessage(message);
        if (normalized.isEmpty()) return;

        ensureLoaded(cwd);
        snapshotCurrentDirectoryIfChanged(cwd);

        if (!mStore.addNewOnTopCurrent(normalized)) return;

        cacheSpans(normalized);
        trimCurrent();
        schedulePersist();
        mHistoryVersion++;
    }

    /**
     * Remove one entry from the current (global or current-directory) history. Edge whitespace is
     * ignored, matching the add path.
     *
     * @return whether an entry was removed.
     */
    public boolean removeMessage(@Nullable String message) {
        String normalized = trimMessage(message);
        if (normalized.isEmpty()) return false;

        HistoryEntry removed = mStore.removeCurrent(normalized);
        if (removed == null) return false;

        mSpansCache.remove(normalized);
        mDismissals.retainIds(mStore.allIds());
        mHistoryVersion++;
        schedulePersist();
        return true;
    }

    /**
     * Make sure the in-memory state mirrors disk before it is mutated. A no-op once {@link #load}
     * has run, so a caller outside the activity — a notification reply writing into a background
     * session — merges into the stored history instead of replacing it.
     */
    private void ensureLoaded(@Nullable String cwd) {
        if (mLoaded) return;
        String fallback = (cwd == null || cwd.isEmpty()) ? "/" : cwd;
        load(fallback);
    }

    /** Select {@code cwd}'s list when the mutation's directory differs from the current one. */
    private void snapshotCurrentDirectoryIfChanged(@Nullable String cwd) {
        if (!mPerDirectoryMessageHistory || cwd == null || cwd.isEmpty()) return;
        if (cwd.equals(mStore.currentCwd())) return;
        if (".".equals(cwd)) {
            if (beginPerDirectorySelection(".")) schedulePersist();
        } else if (selectDirectory(cwd)) {
            schedulePersist();
        }
        mHistoryVersion++;
        rebuildSpansCache();
    }

    private boolean trimCurrent() {
        List<HistoryEntry> removed = mStore.trimCurrent(mMessageHistoryMax);
        dropSpans(removed);
        if (!removed.isEmpty()) mDismissals.retainIds(mStore.allIds());
        return !removed.isEmpty();
    }

    private boolean trimDirectory(@NonNull String cwd) {
        List<HistoryEntry> removed = mStore.trimDirectory(cwd, mMessageHistoryMax);
        dropSpans(removed);
        if (!removed.isEmpty()) mDismissals.retainIds(mStore.allIds());
        return !removed.isEmpty();
    }

    /** Apply the retention limit to every list. */
    private boolean trimAll() {
        List<HistoryEntry> removed = mStore.trimAll(mMessageHistoryMax);
        dropSpans(removed);
        if (!removed.isEmpty()) mDismissals.retainIds(mStore.allIds());
        return !removed.isEmpty();
    }

    /** Drop cached spans for records that no longer exist. */
    private void dropSpans(@NonNull List<HistoryEntry> removed) {
        for (HistoryEntry entry : removed) mSpansCache.remove(entry.text);
    }

    // ── Load ──

    /** Load persisted history. Uses the per-directory or the global store. */
    public void load(@NonNull String fallbackCwd) {
        if (mLoaded) flushPersist(); else cancelPersist();

        HistoryDocument document = loadDocument();
        mStore.replaceWith(document);
        mDismissals.bindEpoch(mStore.epoch());

        if (mPerDirectoryMessageHistory) {
            beginPerDirectorySelection(fallbackCwd);
        } else {
            mStore.useGlobal();
        }

        if (trimAll()) saveNow(false);
        mDismissals.retainIds(mStore.allIds());
        rebuildSpansCache();
        mHistoryVersion++;
        mLoaded = true;
    }

    /**
     * Read the v3 document. A valid v2 document is upgraded; a corrupt/unsupported v3
     * document is backed up and replaced by an empty store, never by stale v1 data.
     * The v1 keys are consumed once, only when no v3 document and no marker exist.
     */
    @NonNull
    private HistoryDocument loadDocument() {
        String json = mPrefs.getString(PREF_MESSAGE_HISTORY_V2, null);
        if (json != null) {
            HistoryDocument decoded = HistoryJsonCodec.decode(json);
            if (decoded != null) {
                if (!decoded.epoch.isEmpty()) return decoded;
                HistoryDocument migrated = withEpoch(decoded, newEpoch(),
                        decoded.perDirInitialized);
                if (mPersistence.writeNow(migrated, true)) markMigrated();
                return migrated;
            }

            // Preserve the raw document for diagnostics, but never resurrect v1 over it.
            mPrefs.edit().putString(PREF_CORRUPT_BACKUP, json).apply();
            HistoryDocument empty = emptyDocument();
            if (mPersistence.writeNow(empty, true)) markMigrated();
            return empty;
        }

        if (mPrefs.getBoolean(PREF_MIGRATION_MARKER, false)) {
            return emptyDocument();
        }

        HistoryDocument legacy = HistoryJsonCodec.decodeLegacy(
                mPrefs.getString(PREF_MESSAGE_HISTORY, null),
                mPrefs.getString(PREF_MESSAGE_HISTORY_PER_DIR, null));
        HistoryDocument migrated = withEpoch(legacy, newEpoch(), false);
        if (!legacy.isEmpty()) {
            if (mPersistence.writeNow(migrated, true)) {
                markMigrated();
                removeLegacyKeys();
            }
        }
        return migrated;
    }

    @NonNull
    private static HistoryDocument withEpoch(@NonNull HistoryDocument document,
            @NonNull String epoch, boolean perDirInitialized) {
        return new HistoryDocument(document.global, document.pending, document.directories,
                document.nextId, epoch, perDirInitialized);
    }

    @NonNull
    private static HistoryDocument emptyDocument() {
        return new HistoryDocument(new ArrayList<>(), new ArrayList<>(),
                new HashMap<String, ArrayList<HistoryEntry>>(), 1, newEpoch(), false);
    }

    @NonNull
    private static String newEpoch() {
        return UUID.randomUUID().toString();
    }

    private void markMigrated() {
        boolean committed = mPrefs.edit().putBoolean(PREF_MIGRATION_MARKER, true).commit();
        if (committed) removeLegacyKeys();
    }

    private void removeLegacyKeys() {
        mPrefs.edit()
                .remove(PREF_MESSAGE_HISTORY)
                .remove(PREF_MESSAGE_HISTORY_PER_DIR)
                .apply();
    }

    public void save() {
        flushPersist();
    }
}
