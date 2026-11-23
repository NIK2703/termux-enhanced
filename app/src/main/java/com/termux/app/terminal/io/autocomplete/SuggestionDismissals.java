package com.termux.app.terminal.io.autocomplete;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Persistent set of suggestion windows removed by the swipe-left gesture.
 *
 * <p>Flags are keyed by {@link HistoryEntry#id}, not by text or offsets: the id is
 * stable across reorders, directory switches and index rebuilds, and a record that
 * is removed and later re-added gets a new id, so it can be suggested again.
 *
 * <p>Bit layout: bit {@code 0} hides the full-entry suggestion; bit {@code n + 1}
 * hides sentence {@code n}. A full-entry bit covers every sentence window of the
 * record, so deleting a multi-line command cannot leave one of its sentences behind.
 *
 * <p>Flags are bound to the history document's {@code epoch}. A new store, a legacy
 * migration or a format/sentence-model change invalidates them instead of applying
 * old bits to reused ids.
 */
final class SuggestionDismissals {

    /** Bump when sentence splitting or whitespace rules change meaning. */
    static final int SENTENCE_MODEL = 2;

    private static final String PREF_KEY = "autocomplete_dismissed_suggestions_v2";
    private static final String KEY_VERSION = "v";
    private static final String KEY_EPOCH = "epoch";
    private static final String KEY_SENTENCE_MODEL = "sentenceModel";
    private static final String KEY_DISMISSED = "dismissed";
    private static final int FORMAT_VERSION = 2;
    /** A corrupt document must not make us allocate an unbounded BitSet. */
    private static final int MAX_BIT = 4096;

    @NonNull private final SharedPreferences mPrefs;
    @NonNull private final HashMap<Long, BitSet> mBits = new HashMap<>();
    @Nullable private String mEpoch;
    private boolean mLoaded;
    private boolean mDirty;

    SuggestionDismissals(@NonNull SharedPreferences prefs) {
        mPrefs = prefs;
    }

    /**
     * Attach the flags to the current history epoch. Flags from another document
     * are discarded before any id lookup can use them.
     */
    void bindEpoch(@NonNull String epoch) {
        ensureLoaded();
        if (!epoch.equals(mEpoch)) {
            mBits.clear();
            mDirty = false;
            mPrefs.edit().remove(PREF_KEY).apply();
        }
        mEpoch = epoch;
    }

    /**
     * Canonical bits for one record. The returned object is owned by this store and
     * is intentionally shared with snapshots, so marking a bit is visible to an
     * already-built snapshot without a rebuild.
     */
    @NonNull
    BitSet bitsFor(long id) {
        ensureLoaded();
        BitSet bits = mBits.get(id);
        if (bits == null) {
            bits = new BitSet();
            mBits.put(id, bits);
        }
        return bits;
    }

    boolean isDismissed(long id, int bit) {
        if (id <= 0 || bit < 0) return false;
        ensureLoaded();
        BitSet bits = mBits.get(id);
        if (bits == null) return false;
        return bits.get(0) || (bit > 0 && bits.get(bit));
    }

    /** Mark one window without persisting; the caller batches and calls {@link #persist()}. */
    boolean mark(long id, int bit) {
        if (id <= 0 || bit < 0 || bit > MAX_BIT) return false;
        ensureLoaded();
        BitSet bits = mBits.get(id);
        if (bits == null) {
            bits = new BitSet();
            mBits.put(id, bits);
        }
        if (bits.get(bit)) return false;
        bits.set(bit);
        mDirty = true;
        return true;
    }

    /** Mark one window and persist immediately (used by small callers and tests). */
    boolean dismiss(long id, int bit) {
        if (!mark(id, bit)) return false;
        persist();
        return true;
    }

    /**
     * Drop flags for records that no longer exist. Ids are never reused, so this
     * cannot hide a later record that happens to carry the same text.
     */
    void retainIds(@NonNull Set<Long> ids) {
        ensureLoaded();
        boolean changed = false;
        Iterator<Map.Entry<Long, BitSet>> it = mBits.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, BitSet> entry = it.next();
            if (!ids.contains(entry.getKey())) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            mDirty = true;
            persist();
        }
    }

    /**
     * Move dismissal bits from ids that a history merge removed to the ids that
     * now carry the same text. Without this a merge could make a hidden suggestion
     * visible under the surviving record.
     */
    void remap(@NonNull Map<Long, Long> idRemap) {
        if (idRemap.isEmpty()) return;
        ensureLoaded();
        boolean changed = false;
        for (Map.Entry<Long, Long> entry : idRemap.entrySet()) {
            long oldId = entry.getKey();
            long newId = entry.getValue();
            if (oldId == newId) continue;
            BitSet oldBits = mBits.remove(oldId);
            if (oldBits == null || oldBits.isEmpty()) continue;
            BitSet newBits = mBits.get(newId);
            if (newBits == null) {
                newBits = new BitSet();
                mBits.put(newId, newBits);
            }
            newBits.or(oldBits);
            changed = true;
        }
        if (changed) {
            mDirty = true;
            persist();
        }
    }

    /** Forget every hidden suggestion without changing the history or its ids. */
    void clearAll() {
        ensureLoaded();
        mBits.clear();
        mDirty = false;
        mPrefs.edit().remove(PREF_KEY).apply();
    }

    boolean isEmpty() {
        ensureLoaded();
        for (BitSet bits : mBits.values()) {
            if (!bits.isEmpty()) return false;
        }
        return true;
    }

    void persist() {
        persist(false);
    }

    void persist(boolean syncCommit) {
        ensureLoaded();
        mDirty = false;
        try {
            JSONObject dismissed = new JSONObject();
            for (Map.Entry<Long, BitSet> entry : mBits.entrySet()) {
                BitSet bits = entry.getValue();
                if (bits.isEmpty()) continue;
                JSONArray array = new JSONArray();
                for (int bit = bits.nextSetBit(0); bit >= 0; bit = bits.nextSetBit(bit + 1)) {
                    array.put(bit);
                }
                dismissed.put(Long.toString(entry.getKey()), array);
            }
            SharedPreferences.Editor editor = mPrefs.edit();
            if (dismissed.length() == 0) {
                editor.remove(PREF_KEY);
            } else {
                JSONObject root = new JSONObject();
                root.put(KEY_VERSION, FORMAT_VERSION);
                root.put(KEY_EPOCH, mEpoch == null ? "" : mEpoch);
                root.put(KEY_SENTENCE_MODEL, SENTENCE_MODEL);
                root.put(KEY_DISMISSED, dismissed);
                editor.putString(PREF_KEY, root.toString());
            }
            if (syncCommit) editor.commit(); else editor.apply();
        } catch (JSONException ignored) {
            // Values are numbers and strings only; a failure here is not recoverable
            // and must not take the suggestion path down.
        }
    }

    /** Commit pending flags before the process can be stopped. */
    void flush() {
        ensureLoaded();
        if (mDirty) persist(true);
    }

    private void ensureLoaded() {
        if (mLoaded) return;
        mLoaded = true;
        String json = mPrefs.getString(PREF_KEY, null);
        if (json == null) return;
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt(KEY_VERSION, 0) != FORMAT_VERSION
                    || root.optInt(KEY_SENTENCE_MODEL, 0) != SENTENCE_MODEL) {
                mPrefs.edit().remove(PREF_KEY).apply();
                return;
            }
            Object rawEpoch = root.opt(KEY_EPOCH);
            if (!(rawEpoch instanceof String) || ((String) rawEpoch).isEmpty()) {
                mPrefs.edit().remove(PREF_KEY).apply();
                return;
            }
            mEpoch = (String) rawEpoch;
            JSONObject dismissed = root.optJSONObject(KEY_DISMISSED);
            if (dismissed == null) return;
            ArrayList<String> keys = new ArrayList<>();
            for (Iterator<String> it = dismissed.keys(); it.hasNext(); ) keys.add(it.next());
            for (String key : keys) {
                long id;
                try {
                    id = Long.parseLong(key);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (id <= 0) continue;
                JSONArray array = dismissed.optJSONArray(key);
                if (array == null) continue;
                BitSet bits = new BitSet();
                for (int i = 0; i < array.length(); i++) {
                    int bit = array.optInt(i, -1);
                    if (bit >= 0 && bit <= MAX_BIT) bits.set(bit);
                }
                if (!bits.isEmpty()) mBits.put(id, bits);
            }
        } catch (JSONException ignored) {
            // A corrupt flags document hides nothing rather than blocking autocomplete.
        }
    }
}
