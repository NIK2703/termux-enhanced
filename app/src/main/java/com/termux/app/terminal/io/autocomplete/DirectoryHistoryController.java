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

    /**
     * Path that must never be stored in the history: the configured default working directory.
     * <p>
     * It is the implicit fallback of every "new tab" action (the "+" button, the right-swipe
     * picker's neutral zone), so listing it as a <em>recently visited</em> directory is noise.
     * Worse, in the right-swipe picker it would be indistinguishable from the "no row selected"
     * outcome, which resolves to that very same directory.
     */
    @Nullable
    private String mExcludedDirectory = null;

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

    /**
     * Set the path that must never appear in the history (the default working directory) and purge
     * any entry already stored for it. A trailing '/' is ignored, so {@code /a/b} and {@code /a/b/}
     * are treated as the same directory. Pass null to disable the filter.
     */
    public void setExcludedDirectory(@Nullable String directory) {
        final String normalized = normalize(directory);
        if (normalized == null ? mExcludedDirectory == null : normalized.equals(mExcludedDirectory)) return;
        mExcludedDirectory = normalized;
        boolean removed = false;
        for (int i = mDirectoryHistory.size() - 1; i >= 0; i--) {
            if (isExcluded(mDirectoryHistory.get(i))) {
                mDirectoryHistory.remove(i);
                removed = true;
            }
        }
        if (removed) save();
    }

    /** @return the excluded path, or null when no filter is set. */
    @Nullable
    public String getExcludedDirectory() {
        return mExcludedDirectory;
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
        // The default working directory is never a "recently visited" directory — it is the
        // implicit fallback of every add-tab action, so it must not be offered as a history entry.
        if (isExcluded(directory)) return;
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
                // isExcluded also drops entries persisted before the filter existed.
                if (!TextUtils.isEmpty(s) && !isExcluded(s) && !mDirectoryHistory.contains(s)) {
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

    // ── Path comparison ──

    /**
     * Canonical form for comparison: drop trailing slashes (a lone "/" is kept), null for an empty
     * path. The cwd from {@code /proc/<pid>/cwd} and the configured default working directory can
     * disagree on a trailing slash, so the comparison must not be literal.
     */
    @Nullable
    private static String normalize(@Nullable String path) {
        if (TextUtils.isEmpty(path)) return null;
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    /** True when {@code directory} is the excluded (default working) directory. */
    private boolean isExcluded(@Nullable String directory) {
        if (mExcludedDirectory == null) return false;
        final String normalized = normalize(directory);
        return normalized != null && normalized.equals(mExcludedDirectory);
    }
}
