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

    /** In-memory message history, newest first (index 0 = most recent). */
    private final ArrayList<String> mMessageHistory = new ArrayList<>();

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

    /** Max entries kept in-memory and persisted. */
    private int mMessageHistoryMax = 100;

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

    public MessageHistoryController(@NonNull SharedPreferences prefs) {
        mPrefs = prefs;
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

    public int getMaxSize() {
        return mMessageHistoryMax;
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

    public boolean isEmpty() {
        return mMessageHistory.isEmpty();
    }

    @Nullable
    public String getHistoryCurrentDirectory() {
        return mHistoryCurrentDirectory;
    }

    // ── Clearing ──

    /**
     * Clear history for the current directory (per-directory mode) or globally.
     *
     * @param cwd The current CWD (used as the per-directory key). May be null
     *            (in which case the per-directory map entry is left untouched).
     */
    public void clearCurrent(@Nullable String cwd) {
        if (mPerDirectoryMessageHistory) {
            if (cwd != null) {
                mMessageHistoryPerDirectory.remove(cwd);
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

        snapshotCurrentDirectoryIfChanged(cwd);

        mMessageHistory.remove(message);      // dedup
        mMessageHistory.add(0, message);      // newest first
        trimToMaxSize();
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

        snapshotCurrentDirectoryIfChanged(cwd);

        if (mMessageHistory.indexOf(message) >= 0) return; // already present: keep position

        mMessageHistory.add(0, message);      // newest first
        trimToMaxSize();
        schedulePersist();
        mHistoryVersion++;
    }

    /** Persist the in-memory list under the old CWD and switch to {@code cwd} when it changed. */
    private void snapshotCurrentDirectoryIfChanged(@Nullable String cwd) {
        if (mPerDirectoryMessageHistory && mHistoryCurrentDirectory != null
                && cwd != null && !cwd.equals(mHistoryCurrentDirectory)) {
            mMessageHistoryPerDirectory.put(mHistoryCurrentDirectory, new ArrayList<>(mMessageHistory));
            mMessageHistory.clear();
            mHistoryVersion++;
            mHistoryCurrentDirectory = cwd;
        }
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
