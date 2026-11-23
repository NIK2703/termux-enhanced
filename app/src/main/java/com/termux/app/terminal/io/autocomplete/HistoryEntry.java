package com.termux.app.terminal.io.autocomplete;

import androidx.annotation.NonNull;

/**
 * Immutable history record. The id is stable for the lifetime of this record and is
 * never reused, even after the record is removed from a list.
 */
final class HistoryEntry {

    final long id;
    @NonNull final String text;

    HistoryEntry(long id, @NonNull String text) {
        this.id = id;
        this.text = text;
    }

    @Override
    public String toString() {
        return id + ":" + text;
    }
}
