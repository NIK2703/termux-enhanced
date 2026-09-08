package com.termux.app.terminal.io.autocomplete;

import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;


/**
 * Pure-data controller for the directory history (visited CWDs).
 * <p>
 * Owns the in-memory list (newest first), handles dedup, trimming,
 * and persistence via {@link SharedPreferences}.
 */
public final class DirectoryHistoryController {

    private static final String PREF_DIRECTORY_HISTORY = "directory_history";

    /** In-memory directory history, newest first (index 0 = most recent). */
    private final ArrayList<String> mDirectoryHistory = new ArrayList<>();

    /** Max entries kept. */
    private int mDirectoryHistoryMax = 50;

    private final SharedPreferences mPrefs;

    public DirectoryHistoryController(@NonNull SharedPreferences prefs) {
        mPrefs = prefs;
    }

    // ── Configuration ──

    public void setMaxSize(int max) {
        mDirectoryHistoryMax = max;
    }

    public int getMaxSize() {
        return mDirectoryHistoryMax;
    }

    // ── Observers ──

    @NonNull
    public ArrayList<String> getHistoryList() {
        return mDirectoryHistory;
    }

    public boolean isEmpty() {
        return mDirectoryHistory.isEmpty();
    }

    // ── Mutations ──

    /**
     * Record the given session's working directory into the history.
     * Returns the recorded path, or null if unavailable.
     */
    @Nullable
    public String recordCurrentDirectory(@Nullable TerminalSession session) {
        if (session == null) return null;
        return recordCurrentDirectory(session.getCwd());
    }

    /** Overload taking an already-resolved cwd so a switch performs ONE /proc read. */
    public String recordCurrentDirectory(@Nullable String cwd) {
        if (TextUtils.isEmpty(cwd)) return null;
        addToDirectoryHistory(cwd);
        return cwd;
    }

    /** Add a visited directory. Deduplicated, newest first. */
    private void addToDirectoryHistory(@NonNull String directory) {
        if (TextUtils.isEmpty(directory)) return;
        // Fast path: already the newest entry — the dedup below would re-insert it at
        // position 0 unchanged, so skip both the list churn AND the (disk) save.
        if (!mDirectoryHistory.isEmpty() && directory.equals(mDirectoryHistory.get(0))) return;
        mDirectoryHistory.remove(directory);
        mDirectoryHistory.add(0, directory);
        while (mDirectoryHistory.size() > mDirectoryHistoryMax) {
            mDirectoryHistory.remove(mDirectoryHistory.size() - 1);
        }
        save();
    }

    // ── Load / Persist ──

    public void load() {
        mDirectoryHistory.clear();
        String json = mPrefs.getString(PREF_DIRECTORY_HISTORY, null);
        if (json == null) return;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, null);
                if (!TextUtils.isEmpty(s) && !mDirectoryHistory.contains(s)) {
                    mDirectoryHistory.add(s);
                }
            }
        } catch (JSONException ignored) {
        }
        boolean trimmed = false;
        while (mDirectoryHistory.size() > mDirectoryHistoryMax) {
            mDirectoryHistory.remove(mDirectoryHistory.size() - 1);
            trimmed = true;
        }
        if (trimmed) save();
    }

    public void save() {
        JSONArray arr = new JSONArray();
        for (String s : mDirectoryHistory) arr.put(s);
        mPrefs.edit().putString(PREF_DIRECTORY_HISTORY, arr.toString()).apply();
    }

    /** Wipe all directory history (in-memory and persisted). */
    public void clear() {
        mDirectoryHistory.clear();
        save();
    }
}
