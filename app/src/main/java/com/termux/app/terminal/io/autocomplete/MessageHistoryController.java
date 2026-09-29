package com.termux.app.terminal.io.autocomplete;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure-data controller for the per-directory / global message (command) history.
 * <p>
 * Owns the in-memory list (in insertion order, newest first) and all per-directory
 * state, plus persistence to {@link SharedPreferences} via JSON. The activity (or
 * any consumer) reads {@link #getHistoryList()} and {@link #getHistoryVersion()}
 * to build popup UI, and calls {@link #addToMessageHistory(String, String)} after
 * a command is executed.
 * <p>
 * In per-directory mode ({@link #setPerDirectoryEnabled(boolean)}) the controller
 * fragments the history by {@code CWD}, migrating any previously-global entries
 * into the first real directory automatically.
 */
public final class MessageHistoryController {

    private static final String PREF_MESSAGE_HISTORY = "message_history";
    private static final String PREF_MESSAGE_HISTORY_PER_DIR = "message_history_per_directory";

    /** Settings keys, read here so a write from outside the activity is configured the same way. */
    private static final String PREF_MAX = "message_history_max";
    private static final String PREF_PER_DIR = "per_directory_message_history";
    private static final String PREF_SAVE_CLEARED = "save_cleared_to_history";

    /** Default when the pref is unset; the single source, read by the activity too. */
    public static final int MESSAGE_HISTORY_MAX_DEFAULT = 20;

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

    /** In-memory message history, newest first (index 0 = most recent). */
    private final ArrayList<String> mMessageHistory = new ArrayList<>();

    /**
     * Whether {@link #load} has populated the in-memory state from disk. Guards the mutators, not
     * the constructor, which does no I/O: a mutator reached without loading first persists an empty
     * store, rewriting the whole per-directory JSON and losing every other directory's history.
     */
    private boolean mLoaded;

    /**
     * Per-directory history store, keyed by absolute path (CWD).
     * Only populated when {@code mPerDirectoryMessageHistory} is true.
     */
    private final HashMap<String, ArrayList<String>> mMessageHistoryPerDirectory = new HashMap<>();

    /** The CWD that {@link #mMessageHistory} currently represents, or null. */
    @Nullable private String mHistoryCurrentDirectory;

    /**
     * Incremented on every modification so consumers (e.g. the auto-complete popup)
     * can cheaply detect that the data has changed.
     */
    private int mHistoryVersion = 0;

    /** Max entries kept in-memory and persisted. Replaced by {@link #applyStoredSettings} on load. */
    private int mMessageHistoryMax = MESSAGE_HISTORY_MAX_DEFAULT;

    private boolean mPerDirectoryMessageHistory = false;

    private final SharedPreferences mPrefs;

    // ── Debounced persistence ──
    // save()/savePerDirectory()/saveGlobal() serialize the ENTIRE history store to
    // JSON and rewrite the prefs file. Bursts of mutations (a message sent, then
    // the field cleared, then a history pick) would each pay that cost, even though
    // SharedPreferences.apply() is itself async — the in-memory mutation is already
    // visible to readers. Coalescing keeps identical persistence semantics (the
    // last mutation always lands on disk) at a fraction of the CPU/IO cost.
    private static final long PERSIST_DEBOUNCE_MS = 250;
    private final Handler mPersistHandler = new Handler(Looper.getMainLooper());
    @Nullable private Runnable mPersistPending;

    // P2: the (potentially large) JSON serialization + prefs write is moved OFF the
    // main thread onto a single-threaded executor. A generation counter lets a newer
    // persist supersede an older one still queued, so the on-disk state always ends
    // at the latest mutation. flushPersist()/save() block on a synchronous commit so
    // history is guaranteed on disk before onStop / a mode switch.
    private final ExecutorService mPersistExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "message-history-persist");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
    private final AtomicInteger mPersistGeneration = new AtomicInteger();

    private MessageHistoryController(@NonNull SharedPreferences prefs) {
        mPrefs = prefs;
        applyStoredSettings();
    }

    /**
     * Read the settings that decide which store a write lands in and how much of it survives, from
     * the same prefs the activity configures the controller from.
     *
     * <p>Done at construction because a notification reply can be the first thing in a process: the
     * broadcast starts the app with no activity, so nothing has applied the user's settings yet. A
     * write under the built-in defaults would then trim a store the user sized at 500 down to 100,
     * or land in the global store while the user is on the per-directory one — the entry simply
     * never appears, and the next write to that store is a rewrite of what is there.
     */
    private void applyStoredSettings() {
        mMessageHistoryMax = mPrefs.getInt(PREF_MAX, MESSAGE_HISTORY_MAX_DEFAULT);
        mPerDirectoryMessageHistory = mPrefs.getBoolean(PREF_PER_DIR, false);
        mSaveClearedToHistory = mPrefs.getBoolean(PREF_SAVE_CLEARED, true);
    }

    /** Schedule (or re-schedule) the store-wide persist on the main looper. */
    private void schedulePersist() {
        if (mPersistPending != null) mPersistHandler.removeCallbacks(mPersistPending);
        mPersistPending = () -> {
            mPersistPending = null;
            persistAsync(false);
        };
        mPersistHandler.postDelayed(mPersistPending, PERSIST_DEBOUNCE_MS);
    }

    /**
     * If a debounced persist is pending, run it now (synchronously, on disk). Called
     * by the host from {@code onPause()} / {@code onStop()} (and before mode switches)
     * so history is always on disk before the process can be stopped.
     */
    public void flushPersist() {
        if (mPersistPending != null) {
            mPersistHandler.removeCallbacks(mPersistPending);
            mPersistPending = null;
        }
        persistAsync(true);
        drainPersist();
    }

    /** Cancel a pending debounced persist without writing (teardown). */
    public void cancelPersist() {
        if (mPersistPending != null) {
            mPersistHandler.removeCallbacks(mPersistPending);
            mPersistPending = null;
        }
    }

    /**
     * Serialize + write the current store on the persistence executor. The data is
     * snapshotted on the calling (main) thread so the background task only reads
     * immutable copies. {@code syncCommit} makes the final write a synchronous
     * {@code commit()} (used by flushPersist/save) so the caller can be sure the
     * bytes are on disk before returning.
     */
    private void persistAsync(boolean syncCommit) {
        final int gen = mPersistGeneration.incrementAndGet();
        if (mPerDirectoryMessageHistory) {
            final String key = mHistoryCurrentDirectory;
            final ArrayList<String> current = new ArrayList<>(mMessageHistory);
            final HashMap<String, ArrayList<String>> snapshot =
                    new HashMap<>(mMessageHistoryPerDirectory.size());
            for (Map.Entry<String, ArrayList<String>> e : mMessageHistoryPerDirectory.entrySet()) {
                snapshot.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
            if (key != null) snapshot.put(key, current);
            mPersistExecutor.execute(() -> {
                if (gen < mPersistGeneration.get()) return; // a newer persist superseded this
                writePerDirectory(snapshot, syncCommit);
            });
        } else {
            final ArrayList<String> current = new ArrayList<>(mMessageHistory);
            mPersistExecutor.execute(() -> {
                if (gen < mPersistGeneration.get()) return; // a newer persist superseded this
                writeGlobal(current, syncCommit);
            });
        }
    }

    /** Block until every queued persistence task has finished (used by sync flushes). */
    private void drainPersist() {
        try {
            mPersistExecutor.submit(() -> {}).get();
        } catch (InterruptedException | java.util.concurrent.ExecutionException ignored) {
            // Best-effort: if the drain is interrupted we still return; the host's
            // lifecycle pause will have triggered its own flush.
        }
    }

    private void writeGlobal(@NonNull ArrayList<String> list, boolean syncCommit) {
        putPrefsString(PREF_MESSAGE_HISTORY, listToJson(list).toString(), syncCommit);
    }

    private void writePerDirectory(@NonNull HashMap<String, ArrayList<String>> map, boolean syncCommit) {
        JSONObject obj = mapToJson(map);
        if (obj == null) return;
        putPrefsString(PREF_MESSAGE_HISTORY_PER_DIR, obj.toString(), syncCommit);
    }

    private void putPrefsString(@NonNull String key, @NonNull String value, boolean syncCommit) {
        SharedPreferences.Editor ed = mPrefs.edit().putString(key, value);
        if (syncCommit) ed.commit(); else ed.apply();
    }

    @NonNull
    private static JSONArray listToJson(@NonNull Iterable<String> list) {
        JSONArray arr = new JSONArray();
        for (String s : list) arr.put(s);
        return arr;
    }

    @Nullable
    private static JSONObject mapToJson(@NonNull HashMap<String, ArrayList<String>> map) {
        JSONObject obj = new JSONObject();
        try {
            for (Map.Entry<String, ArrayList<String>> e : map.entrySet()) {
                obj.put(e.getKey(), listToJson(e.getValue()));
            }
        } catch (JSONException ignored) {
            return null;
        }
        return obj;
    }

    // ── Feature flags ──

    public void setPerDirectoryEnabled(boolean enabled) {
        mPerDirectoryMessageHistory = enabled;
    }

    public boolean isPerDirectoryEnabled() {
        return mPerDirectoryMessageHistory;
    }


    /** Whether clearing the input field remembers its text in the history first. */
    private boolean mSaveClearedToHistory = true;

    public void setMaxSize(int max) {
        mMessageHistoryMax = max;
    }

    public void setSaveClearedToHistory(boolean enabled) {
        mSaveClearedToHistory = enabled;
    }

    public boolean isSaveClearedToHistory() {
        return mSaveClearedToHistory;
    }

    // ── Observers ──

    @NonNull
    public ArrayList<String> getHistoryList() {
        return mMessageHistory;
    }

    public int getHistoryVersion() {
        return mHistoryVersion;
    }

    @Nullable
    public String getHistoryCurrentDirectory() {
        return mHistoryCurrentDirectory;
    }

    // ── Clearing ──

    /**
     * Clear history for the current directory (per-directory mode) or globally.
     *
     * @param cwd The directory to clear, as the per-directory key. May be null (in which case the
     *            per-directory map entry is left untouched).
     */
    public void clearCurrent(@Nullable String cwd) {
        if (mPerDirectoryMessageHistory) {
            if (cwd != null) {
                mMessageHistoryPerDirectory.remove(cwd);
                // The in-memory list is the CURRENT directory's, and it is what savePerDirectory
                // writes back under that key. Emptying it for some other directory would replace
                // the current one's commands with an empty list.
                if (!cwd.equals(mHistoryCurrentDirectory)) {
                    mHistoryVersion++;
                    savePerDirectory();
                    return;
                }
            } else {
                // D-2: no resolvable CWD — drop the stale current-directory key so its
                // (now-cleared) in-memory list cannot "resurrect" after the next
                // directory switch (onHistoryDirectoryChanged would otherwise save the
                // empty list back under the old key).
                mHistoryCurrentDirectory = null;
            }
            mMessageHistory.clear();
            mHistoryVersion++;
            savePerDirectory();
        } else {
            mMessageHistory.clear();
            mHistoryVersion++;
            mPrefs.edit().remove(PREF_MESSAGE_HISTORY).apply();
        }
    }

    /** Clear the entire per-directory history store (and the in-memory list). */
    public void clearAllPerDirectory() {
        mMessageHistoryPerDirectory.clear();
        mMessageHistory.clear();
        mHistoryCurrentDirectory = null;
        mHistoryVersion++;
        mPrefs.edit().remove(PREF_MESSAGE_HISTORY_PER_DIR).apply();
    }

    // ── CWD change ──

    /**
     * Called when the current session's working directory has changed.
     * Saves the current list under the old CWD key, clears the in-memory list,
     * and loads the history for the new directory (or creates it empty).
     */
    public void onHistoryDirectoryChanged(@NonNull String oldCwd, @NonNull String newCwd) {
        if (!mPerDirectoryMessageHistory) return;

        // Same directory (the common case: switching tabs inside one project):
        // the save/clear/reload round trip below would rebuild the identical list,
        // so keep the in-memory state untouched.
        if (newCwd.equals(oldCwd)) return;

        if (mHistoryCurrentDirectory != null) {
            mMessageHistoryPerDirectory.put(mHistoryCurrentDirectory, new ArrayList<>(mMessageHistory));
        }

        mMessageHistory.clear();
        mHistoryCurrentDirectory = newCwd;

        ArrayList<String> dirHistory = mMessageHistoryPerDirectory.get(newCwd);

        // Lazy migration: if this CWD has no per-dir entries but global
        // history still exists in prefs, migrate it now under this real CWD.
        if (dirHistory == null) {
            String globalJson = mPrefs.getString(PREF_MESSAGE_HISTORY, null);
            if (!TextUtils.isEmpty(globalJson)) {
                migrateGlobalHistory(newCwd, globalJson);
                return;
            }
        }

        if (dirHistory != null) {
            mMessageHistory.addAll(dirHistory);
        }
    }

    /** Migrate global history to per-directory under the given CWD. */
    private void migrateGlobalHistory(@NonNull String cwd, @NonNull String globalJson) {
        ArrayList<String> migrated = parseGlobalJson(globalJson, false);
        if (migrated == null) return;
        mMessageHistoryPerDirectory.put(cwd, migrated);
        mMessageHistory.addAll(migrated);
        // Persist the migration synchronously: this is a one-time
        // destructive move (the global store is dropped below).
        savePerDirectory();
        mPrefs.edit().remove(PREF_MESSAGE_HISTORY).apply();
    }

    /** Parse a global-history JSON array into a deduped list, or null when unparseable. */
    @Nullable
    private ArrayList<String> parseGlobalJson(@NonNull String globalJson, boolean useSeenSet) {
        try {
            JSONArray globalArr = new JSONArray(globalJson);
            return jsonArrayList(globalArr, useSeenSet);
        } catch (JSONException e) {
            return null;
        }
    }

    @NonNull
    private ArrayList<String> jsonArrayList(@NonNull JSONArray arr, boolean useSeenSet) {
        ArrayList<String> out = new ArrayList<>(arr.length());
        HashSet<String> seen = useSeenSet ? new HashSet<>(arr.length()) : null;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, null);
            if (TextUtils.isEmpty(s)) continue;
            if (seen != null) {
                if (seen.add(s)) out.add(s);
            } else if (!out.contains(s)) {
                out.add(s);
            }
        }
        return out;
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
        if (TextUtils.isEmpty(message)) return;

        ensureLoaded(cwd);
        snapshotCurrentDirectoryIfChanged(cwd);

        mMessageHistory.remove(message);      // dedup
        mMessageHistory.add(0, message);      // newest first
        trimToMaxSize();
        schedulePersist();
        mHistoryVersion++;
    }

    /**
     * Add a message that belongs to {@code cwd} without assuming {@code cwd} is the directory the
     * user is looking at.
     *
     * <p>For a write from outside the foreground window — a notification reply into a background
     * session. In per-directory mode {@link #mMessageHistory} is the list of whichever session is in
     * front, so a plain {@link #addToMessageHistory} for a different directory would repoint the
     * whole controller at that session and the window would show the wrong session's history until
     * the next directory sync. This writes the other directory's own entry instead, leaving the
     * foreground list and the current directory alone.
     */
    public void addToMessageHistoryInDirectory(@NonNull String message, @Nullable String cwd) {
        if (TextUtils.isEmpty(message)) return;

        ensureLoaded(cwd);
        if (!mPerDirectoryMessageHistory || cwd == null || cwd.equals(mHistoryCurrentDirectory)) {
            addToMessageHistory(message, cwd);
            return;
        }

        ArrayList<String> dirHistory = mMessageHistoryPerDirectory.get(cwd);
        if (dirHistory == null) {
            dirHistory = new ArrayList<>();
            mMessageHistoryPerDirectory.put(cwd, dirHistory);
        }
        dirHistory.remove(message);            // dedup
        dirHistory.add(0, message);            // newest first
        while (dirHistory.size() > mMessageHistoryMax) {
            dirHistory.remove(dirHistory.size() - 1);
        }
        schedulePersist();
        mHistoryVersion++;
    }

    /**
     * Record a passively saved message (cleared from the input field, or the
     * text replaced by a history pick). A brand-new message is inserted at the
     * TOP (newest — the pre-promote-switch behaviour); a message already in the
     * history keeps its exact position. Only messages actually SENT from the
     * input field are promoted to the top.
     */
    public void addNewOnTop(@NonNull String message, @Nullable String cwd) {
        if (TextUtils.isEmpty(message)) return;

        ensureLoaded(cwd);
        snapshotCurrentDirectoryIfChanged(cwd);

        if (mMessageHistory.indexOf(message) >= 0) return; // already present: keep position

        mMessageHistory.add(0, message);      // newest first
        trimToMaxSize();
        schedulePersist();
        mHistoryVersion++;
    }

    /**
     * Make sure the in-memory state mirrors disk before it is mutated. A no-op once {@link #load}
     * has run, so a caller outside the activity — a notification reply writing into a background
     * session — merges into the stored history instead of replacing it. {@code cwd} is the caller's
     * own directory, not whatever happens to be in front of the user.
     */
    private void ensureLoaded(@Nullable String cwd) {
        if (mLoaded) return;
        String fallback = (cwd == null || cwd.isEmpty()) ? "/" : cwd;
        load(fallback);
    }

    /**
     * Persist the in-memory list under the old CWD and switch to {@code cwd} when it changed.
     *
     * <p>The new directory's own list is loaded back, not left empty: the mutator that follows adds
     * one entry to {@link #mMessageHistory}, and {@link #persistAsync} then writes the whole list
     * over that directory's stored entry. Switching without loading therefore replaced every command
     * of the target directory with that single command.
     *
     * <p>A null {@link #mHistoryCurrentDirectory} adopts {@code cwd} rather than doing nothing. It
     * is null right after a clear, and an unadopted list has no key: {@link #persistAsync} writes the
     * in-memory list under the current key, so with no key the entry was dropped on the floor and
     * the first command after "clear all" never survived a restart.
     */
    private void snapshotCurrentDirectoryIfChanged(@Nullable String cwd) {
        if (!mPerDirectoryMessageHistory || cwd == null || cwd.equals(mHistoryCurrentDirectory)) return;

        if (mHistoryCurrentDirectory == null) {
            mHistoryCurrentDirectory = cwd;
            ArrayList<String> dirHistory = mMessageHistoryPerDirectory.get(cwd);
            if (dirHistory != null) mMessageHistory.addAll(dirHistory);
            mHistoryVersion++;
            return;
        }

        mMessageHistoryPerDirectory.put(mHistoryCurrentDirectory, new ArrayList<>(mMessageHistory));
        mMessageHistory.clear();
        mHistoryCurrentDirectory = cwd;
        ArrayList<String> dirHistory = mMessageHistoryPerDirectory.get(cwd);
        if (dirHistory != null) mMessageHistory.addAll(dirHistory);
        mHistoryVersion++;
    }

    private boolean trimToMaxSize() {
        boolean trimmed = false;
        while (mMessageHistory.size() > mMessageHistoryMax) {
            mMessageHistory.remove(mMessageHistory.size() - 1);
            trimmed = true;
        }
        return trimmed;
    }

    // ── Load / Persist ──

    /** Load persisted history. Uses per-directory or global store based on {@link #mPerDirectoryMessageHistory}. */
    public void load(@NonNull String fallbackCwd) {
        mMessageHistory.clear();
        if (mPerDirectoryMessageHistory) {
            loadPerDirectory(fallbackCwd);
        } else {
            loadGlobal();
        }
        mLoaded = true;
    }

    private void loadGlobal() {
        String json = mPrefs.getString(PREF_MESSAGE_HISTORY, null);
        if (json == null) return;
        // P2: O(N) dedup via a HashSet instead of List.contains (O(N²) on the
        // already-loaded list). Same order, same result.
        ArrayList<String> parsed = parseGlobalJson(json, true);
        if (parsed != null) mMessageHistory.addAll(parsed);
        if (trimToMaxSize()) saveGlobal();
        mHistoryVersion++;
    }

    private void loadPerDirectory(@NonNull String fallbackCwd) {
        mMessageHistoryPerDirectory.clear();
        mHistoryCurrentDirectory = null;

        boolean hadPerDirData = false;
        String json = mPrefs.getString(PREF_MESSAGE_HISTORY_PER_DIR, null);
        if (json != null) {
            try {
                JSONObject obj = new JSONObject(json);
                for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
                    String dir = it.next();
                    JSONArray arr = obj.optJSONArray(dir);
                    if (arr == null) continue;
                    hadPerDirData = true;
                    mMessageHistoryPerDirectory.put(dir, jsonArrayList(arr, true));
                }
            } catch (JSONException ignored) {
            }
        }

        // Migrate global history to per-directory if first time enabling
        if (!hadPerDirData) {
            String globalJson = mPrefs.getString(PREF_MESSAGE_HISTORY, null);
            if (!TextUtils.isEmpty(globalJson)) {
                // Defer if no real CWD yet
                if (".".equals(fallbackCwd)) {
                    mHistoryCurrentDirectory = ".";
                    return;
                }
                ArrayList<String> migrated = parseGlobalJson(globalJson, true);
                if (migrated == null) return;
                mMessageHistoryPerDirectory.put(fallbackCwd, migrated);
                mHistoryCurrentDirectory = fallbackCwd;
                mMessageHistory.clear();
                mMessageHistory.addAll(migrated);
                // Persist the migration synchronously: this is a one-time
                // destructive move (the global store is dropped below).
                savePerDirectory();
                mPrefs.edit().remove(PREF_MESSAGE_HISTORY).apply();
                return;
            }
        }

        // Load the current CWD's history
        mHistoryCurrentDirectory = fallbackCwd;
        ArrayList<String> dirHistory = mMessageHistoryPerDirectory.get(fallbackCwd);
        if (dirHistory != null) {
            mMessageHistory.clear();
            mMessageHistory.addAll(dirHistory);
        }
    }

    public void save() {
        cancelPersist();
        persistAsync(true);
        drainPersist();
    }

    private void saveGlobal() {
        // Bump the generation so any in-flight background persist (scheduled by a
        // prior keystroke) is superseded rather than clobbering this synchronous
        // write — see the generation-counter contract in persistAsync().
        mPersistGeneration.incrementAndGet();
        mPrefs.edit().putString(PREF_MESSAGE_HISTORY,
            listToJson(mMessageHistory).toString()).apply();
    }

    private void savePerDirectory() {
        // Same generation-supersede guard as saveGlobal(): a clear/migrate on the
        // main thread must win over a background write still queued from typing.
        mPersistGeneration.incrementAndGet();
        if (mHistoryCurrentDirectory != null) {
            ArrayList<String> list = new ArrayList<>(mMessageHistory);
            mMessageHistoryPerDirectory.put(mHistoryCurrentDirectory, list);
        }
        JSONObject obj = mapToJson(mMessageHistoryPerDirectory);
        if (obj == null) return;
        mPrefs.edit().putString(PREF_MESSAGE_HISTORY_PER_DIR, obj.toString()).apply();
    }
}
