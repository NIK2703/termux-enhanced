package com.termux.app.terminal.io.autocomplete;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain, detached view of the whole history store: the global list, the pending
 * per-directory buffer, every per-directory list, the shared id counter, the store
 * epoch and the one-time per-directory migration flag.
 *
 * <p>Used at the persistence boundary so the codec never reads a list that is being
 * mutated on the main thread.
 */
final class HistoryDocument {

    /** Empty value means the document predates epochs and must be assigned one on load. */
    static final String NO_EPOCH = "";

    @NonNull final ArrayList<HistoryEntry> global;
    @NonNull final ArrayList<HistoryEntry> pending;
    @NonNull final LinkedHashMap<String, ArrayList<HistoryEntry>> directories;
    final long nextId;
    @NonNull final String epoch;
    final boolean perDirInitialized;

    HistoryDocument(@NonNull List<HistoryEntry> global,
                    @NonNull Map<String, ? extends List<HistoryEntry>> directories,
                    long nextId) {
        this(global, Collections.emptyList(), directories, nextId, NO_EPOCH, false);
    }

    HistoryDocument(@NonNull List<HistoryEntry> global,
                    @NonNull List<HistoryEntry> pending,
                    @NonNull Map<String, ? extends List<HistoryEntry>> directories,
                    long nextId,
                    @NonNull String epoch,
                    boolean perDirInitialized) {
        this.global = new ArrayList<>(global);
        this.pending = new ArrayList<>(pending);
        this.directories = new LinkedHashMap<>(directories.size());
        for (Map.Entry<String, ? extends List<HistoryEntry>> entry : directories.entrySet()) {
            this.directories.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        this.nextId = Math.max(1, nextId);
        this.epoch = epoch;
        this.perDirInitialized = perDirInitialized;
    }

    boolean isEmpty() {
        if (!global.isEmpty()) return false;
        if (!pending.isEmpty()) return false;
        for (ArrayList<HistoryEntry> list : directories.values()) {
            if (!list.isEmpty()) return false;
        }
        return true;
    }
}
