package com.termux.app.terminal.io.autocomplete;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unified in-memory history store: one global list, one pending per-directory
 * buffer (writes made while the cwd is unknown), one list per directory and one
 * monotonic id counter.
 *
 * <p>{@link #mCurrent} is always a direct reference to one of those lists. A
 * directory or mode switch never copies the list and there is no detached
 * "visible empty" state that could silently lose writes or turn a clear into a
 * no-op.
 */
final class HistoryStore {

    private enum CurrentKind { GLOBAL, PENDING, DIRECTORY }

    @NonNull private final ArrayList<HistoryEntry> mGlobal = new ArrayList<>();
    @NonNull private final ArrayList<HistoryEntry> mPending = new ArrayList<>();
    @NonNull private final LinkedHashMap<String, ArrayList<HistoryEntry>> mDirectories =
            new LinkedHashMap<>();
    @NonNull private ArrayList<HistoryEntry> mCurrent = mGlobal;
    @NonNull private CurrentKind mCurrentKind = CurrentKind.GLOBAL;
    @Nullable private String mCurrentCwd;
    private long mNextId = 1;
    @NonNull private String mEpoch = HistoryDocument.NO_EPOCH;
    private boolean mPerDirInitialized;

    // ── Selection ──

    @NonNull
    List<HistoryEntry> global() {
        return mGlobal;
    }

    @NonNull
    List<HistoryEntry> pending() {
        return mPending;
    }

    @NonNull
    List<HistoryEntry> current() {
        return mCurrent;
    }

    @Nullable
    List<HistoryEntry> directory(@Nullable String cwd) {
        return cwd == null ? null : mDirectories.get(cwd);
    }

    @NonNull
    Map<String, ArrayList<HistoryEntry>> directories() {
        return mDirectories;
    }

    long nextId() {
        return mNextId;
    }

    void setNextId(long nextId) {
        mNextId = Math.max(1, nextId);
    }

    @NonNull
    String epoch() {
        return mEpoch;
    }

    void setEpoch(@NonNull String epoch) {
        mEpoch = epoch;
    }

    boolean isPerDirInitialized() {
        return mPerDirInitialized;
    }

    void setPerDirInitialized(boolean initialized) {
        mPerDirInitialized = initialized;
    }

    /** Null in global mode, {@code "."} for pending writes, the cwd otherwise. */
    @Nullable
    String currentCwd() {
        return mCurrentCwd;
    }

    boolean isCurrentGlobal() {
        return mCurrentKind == CurrentKind.GLOBAL;
    }

    boolean isCurrentPending() {
        return mCurrentKind == CurrentKind.PENDING;
    }

    boolean isCurrentDirectory(@NonNull String cwd) {
        return mCurrentKind == CurrentKind.DIRECTORY && cwd.equals(mCurrentCwd);
    }

    void useGlobal() {
        pruneEmptyCurrentDirectory();
        mCurrent = mGlobal;
        mCurrentKind = CurrentKind.GLOBAL;
        mCurrentCwd = null;
    }

    void usePending() {
        pruneEmptyCurrentDirectory();
        mCurrent = mPending;
        mCurrentKind = CurrentKind.PENDING;
        mCurrentCwd = ".";
    }

    void useDirectory(@NonNull String cwd) {
        pruneEmptyCurrentDirectory();
        mCurrent = directoriesOrCreate(cwd);
        mCurrentKind = CurrentKind.DIRECTORY;
        mCurrentCwd = cwd;
    }

    /**
     * Drop the old current directory when it is empty. Selecting a directory creates
     * an empty list, but that list must not become a permanent empty key after the
     * user leaves it.
     */
    private void pruneEmptyCurrentDirectory() {
        if (mCurrentKind == CurrentKind.DIRECTORY && mCurrent.isEmpty()) {
            ArrayList<HistoryEntry> list = mDirectories.get(mCurrentCwd);
            if (list == mCurrent) mDirectories.remove(mCurrentCwd);
        }
    }

    // ── Mutations ──

    /** Promote an existing text or insert a new record at the head. */
    boolean addCurrent(@NonNull String text) {
        return add(mCurrent, text);
    }

    /** Insert only when absent; an existing text keeps its position. */
    boolean addNewOnTopCurrent(@NonNull String text) {
        if (indexOf(mCurrent, text) >= 0) return false;
        mCurrent.add(0, new HistoryEntry(mNextId++, text));
        return true;
    }

    boolean addToDirectory(@NonNull String cwd, @NonNull String text) {
        return add(directoriesOrCreate(cwd), text);
    }

    @Nullable
    HistoryEntry removeCurrent(@NonNull String text) {
        int index = indexOf(mCurrent, text);
        return index < 0 ? null : mCurrent.remove(index);
    }

    /** @return entries dropped from the tail so callers can prune spans/flags. */
    @NonNull
    List<HistoryEntry> trimCurrent(int max) {
        return trim(mCurrent, max);
    }

    /** @return entries dropped from a directory's tail. */
    @NonNull
    List<HistoryEntry> trimDirectory(@Nullable String cwd, int max) {
        ArrayList<HistoryEntry> list = cwd == null ? null : mDirectories.get(cwd);
        if (list == null) return new ArrayList<>();
        List<HistoryEntry> removed = trim(list, max);
        if (list.isEmpty() && !isCurrentDirectory(cwd)) mDirectories.remove(cwd);
        return removed;
    }

    /**
     * Apply the retention limit to every list, not just the visible one. Empty
     * non-current directories are removed; the selected empty directory stays so
     * the attached-list invariant holds until the next switch.
     */
    @NonNull
    List<HistoryEntry> trimAll(int max) {
        ArrayList<HistoryEntry> removed = new ArrayList<>(trim(mGlobal, max));
        removed.addAll(trim(mPending, max));
        Iterator<Map.Entry<String, ArrayList<HistoryEntry>>> it =
                mDirectories.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ArrayList<HistoryEntry>> entry = it.next();
            ArrayList<HistoryEntry> list = entry.getValue();
            removed.addAll(trim(list, max));
            if (list.isEmpty()
                    && !(mCurrentKind == CurrentKind.DIRECTORY
                    && entry.getKey().equals(mCurrentCwd))) {
                it.remove();
            }
        }
        return removed;
    }

    /** Clear the selected list and return the removed records. */
    @NonNull
    List<HistoryEntry> clearCurrent() {
        ArrayList<HistoryEntry> removed = new ArrayList<>(mCurrent);
        mCurrent.clear();
        return removed;
    }

    /** Clear one directory's list; non-current keys are removed entirely. */
    @NonNull
    List<HistoryEntry> clearDirectory(@Nullable String cwd) {
        ArrayList<HistoryEntry> list = cwd == null ? null : mDirectories.get(cwd);
        if (list == null) return new ArrayList<>();
        ArrayList<HistoryEntry> removed = new ArrayList<>(list);
        if (isCurrentDirectory(cwd)) {
            list.clear();
        } else {
            mDirectories.remove(cwd);
        }
        return removed;
    }

    /** Clear the pending per-directory buffer. */
    @NonNull
    List<HistoryEntry> clearPending() {
        ArrayList<HistoryEntry> removed = new ArrayList<>(mPending);
        mPending.clear();
        return removed;
    }

    /**
     * Clear the global list, the pending buffer and every directory list. The
     * current reference is moved to the pending list, which is still attached.
     */
    @NonNull
    List<HistoryEntry> clearAll() {
        ArrayList<HistoryEntry> removed = new ArrayList<>(mGlobal);
        removed.addAll(mPending);
        for (ArrayList<HistoryEntry> list : mDirectories.values()) removed.addAll(list);
        mGlobal.clear();
        mPending.clear();
        mDirectories.clear();
        mCurrent = mPending;
        mCurrentKind = CurrentKind.PENDING;
        mCurrentCwd = ".";
        return removed;
    }

    // ── Migration / merge ──

    static final class MergeResult {
        static final MergeResult EMPTY =
                new MergeResult(Collections.<Long, Long>emptyMap(), false);

        /** Dropped id -> id of the record that now carries the same text. */
        @NonNull final Map<Long, Long> idRemap;
        final boolean changed;

        MergeResult(@NonNull Map<Long, Long> idRemap, boolean changed) {
            this.idRemap = idRemap;
            this.changed = changed;
        }
    }

    /**
     * Move the one-time legacy/global history into the pending per-directory
     * buffer. On a duplicate text the pending record is newer and stays; the
     * global id is remapped so its dismissal bits transfer to the kept record.
     */
    @NonNull
    MergeResult mergeGlobalIntoPending() {
        if (mGlobal.isEmpty()) return MergeResult.EMPTY;
        HashMap<String, HistoryEntry> pendingByText = new HashMap<>();
        for (HistoryEntry entry : mPending) pendingByText.put(entry.text, entry);
        HashMap<Long, Long> remap = new HashMap<>();
        for (HistoryEntry entry : mGlobal) {
            HistoryEntry kept = pendingByText.get(entry.text);
            if (kept != null) {
                remap.put(entry.id, kept.id);
            } else {
                mPending.add(entry);
                pendingByText.put(entry.text, entry);
            }
        }
        mGlobal.clear();
        return new MergeResult(remap, true);
    }

    /**
     * Move the pending buffer into {@code cwd}. Pending records are newer than the
     * existing directory history, so they stay at the front. On a duplicate text
     * the pending record wins and the old directory id is remapped.
     */
    @NonNull
    MergeResult mergePendingIntoDirectory(@NonNull String cwd) {
        if (mPending.isEmpty()) return MergeResult.EMPTY;
        ArrayList<HistoryEntry> target = directoriesOrCreate(cwd);
        HashMap<String, HistoryEntry> pendingByText = new HashMap<>();
        for (HistoryEntry entry : mPending) pendingByText.put(entry.text, entry);
        HashMap<Long, Long> remap = new HashMap<>();
        ArrayList<HistoryEntry> merged = new ArrayList<>(mPending);
        for (HistoryEntry entry : target) {
            HistoryEntry kept = pendingByText.get(entry.text);
            if (kept != null) {
                remap.put(entry.id, kept.id);
            } else {
                merged.add(entry);
            }
        }
        target.clear();
        target.addAll(merged);
        mPending.clear();
        return new MergeResult(remap, true);
    }

    // ── Document ──

    void replaceWith(@NonNull HistoryDocument document) {
        mGlobal.clear();
        mGlobal.addAll(document.global);
        mPending.clear();
        mPending.addAll(document.pending);
        mDirectories.clear();
        for (Map.Entry<String, ArrayList<HistoryEntry>> entry : document.directories.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                mDirectories.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        long maxId = 0;
        for (HistoryEntry entry : mGlobal) maxId = Math.max(maxId, entry.id);
        for (HistoryEntry entry : mPending) maxId = Math.max(maxId, entry.id);
        for (ArrayList<HistoryEntry> list : mDirectories.values()) {
            for (HistoryEntry entry : list) maxId = Math.max(maxId, entry.id);
        }
        mNextId = Math.max(Math.max(1, document.nextId), maxId + 1);
        mEpoch = document.epoch;
        mPerDirInitialized = document.perDirInitialized;
        mCurrent = mGlobal;
        mCurrentKind = CurrentKind.GLOBAL;
        mCurrentCwd = null;
    }

    @NonNull
    HistoryDocument snapshot() {
        return new HistoryDocument(mGlobal, mPending, mDirectories, mNextId, mEpoch,
                mPerDirInitialized);
    }

    @NonNull
    Set<Long> allIds() {
        HashSet<Long> ids = new HashSet<>();
        for (HistoryEntry entry : mGlobal) ids.add(entry.id);
        for (HistoryEntry entry : mPending) ids.add(entry.id);
        for (ArrayList<HistoryEntry> list : mDirectories.values()) {
            for (HistoryEntry entry : list) ids.add(entry.id);
        }
        return ids;
    }

    int currentIndexOf(@NonNull String text) {
        return indexOf(mCurrent, text);
    }

    // ── Helpers ──

    @NonNull
    private ArrayList<HistoryEntry> directoriesOrCreate(@NonNull String cwd) {
        ArrayList<HistoryEntry> list = mDirectories.get(cwd);
        if (list == null) {
            list = new ArrayList<>();
            mDirectories.put(cwd, list);
        }
        return list;
    }

    private boolean add(@NonNull ArrayList<HistoryEntry> list, @NonNull String text) {
        int index = indexOf(list, text);
        if (index == 0) return false;
        if (index > 0) {
            HistoryEntry existing = list.remove(index);
            list.add(0, existing);
            return true;
        }
        list.add(0, new HistoryEntry(mNextId++, text));
        return true;
    }

    @NonNull
    private static List<HistoryEntry> trim(@NonNull ArrayList<HistoryEntry> list, int max) {
        ArrayList<HistoryEntry> removed = new ArrayList<>();
        while (list.size() > max) removed.add(list.remove(list.size() - 1));
        return removed;
    }

    private static int indexOf(@NonNull List<HistoryEntry> list, @NonNull String text) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).text.equals(text)) return i;
        }
        return -1;
    }

    /**
     * Live, read-only {@code List<String>} view over records. The history popup
     * keeps its string API without a parallel copy of the list.
     */
    @NonNull
    static List<String> textView(@NonNull final List<HistoryEntry> entries) {
        return new AbstractList<String>() {
            @Override
            public String get(int index) {
                return entries.get(index).text;
            }

            @Override
            public int size() {
                return entries.size();
            }
        };
    }
}
