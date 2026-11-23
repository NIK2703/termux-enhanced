package com.termux.app.terminal.io.autocomplete;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON boundary for the unified history store.
 *
 * <p>v3 history document:
 * <pre>
 * {"v":3,"epoch":"uuid","nextId":42,"perDirInitialized":true,
 *  "global":[{"id":41,"text":"git status"}],
 *  "pending":[],
 *  "dirs":{"/home/user":[{"id":40,"text":"ls -la"}]}}
 * </pre>
 *
 * <p>v2 documents are read and upgraded by the controller: they have no epoch and
 * no pending buffer. v1 ({@code message_history} array and
 * {@code message_history_per_directory} object of arrays) is read only when the
 * migration marker says it has not been consumed. It has no ids, so they are
 * assigned deterministically: global first, then directory keys in lexicographic
 * order.
 */
final class HistoryJsonCodec {

    static final int VERSION = 3;
    private static final int VERSION_V2 = 2;

    private HistoryJsonCodec() {
    }

    @Nullable
    static String encode(@NonNull HistoryDocument document) {
        if (document.epoch.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject();
            root.put("v", VERSION);
            root.put("epoch", document.epoch);
            root.put("nextId", document.nextId);
            root.put("perDirInitialized", document.perDirInitialized);
            root.put("global", encodeList(document.global));
            root.put("pending", encodeList(document.pending));
            JSONObject dirs = new JSONObject();
            ArrayList<String> dirKeys = new ArrayList<>(document.directories.keySet());
            Collections.sort(dirKeys);
            for (String key : dirKeys) {
                ArrayList<HistoryEntry> list = document.directories.get(key);
                if (list == null || list.isEmpty()) continue;
                dirs.put(key, encodeList(list));
            }
            root.put("dirs", dirs);
            return root.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    @Nullable
    static HistoryDocument decode(@Nullable String json) {
        if (json == null) return null;
        try {
            JSONObject root = new JSONObject(json);
            int version = root.optInt("v", -1);
            if (version != VERSION && version != VERSION_V2) return null;

            Object rawNext = root.opt("nextId");
            if (!(rawNext instanceof Number)) return null;
            IdAllocator ids = new IdAllocator(((Number) rawNext).longValue());

            Object rawGlobal = root.opt("global");
            if (!(rawGlobal instanceof JSONArray)) return null;
            ArrayList<HistoryEntry> global = decodeList((JSONArray) rawGlobal, ids);
            if (global == null) return null;

            Object rawDirs = root.opt("dirs");
            if (!(rawDirs instanceof JSONObject)) return null;
            JSONObject dirsObject = (JSONObject) rawDirs;
            LinkedHashMap<String, ArrayList<HistoryEntry>> dirs = new LinkedHashMap<>();
            for (String key : sortedKeys(dirsObject)) {
                if (key.isEmpty()) return null;
                Object rawArray = dirsObject.opt(key);
                if (!(rawArray instanceof JSONArray)) return null;
                ArrayList<HistoryEntry> list = decodeList((JSONArray) rawArray, ids);
                if (list == null) return null;
                if (!list.isEmpty()) dirs.put(key, list);
            }

            ArrayList<HistoryEntry> pending = new ArrayList<>();
            String epoch = HistoryDocument.NO_EPOCH;
            boolean initialized = !dirs.isEmpty() || global.isEmpty();
            if (version == VERSION) {
                Object rawPending = root.opt("pending");
                if (!(rawPending instanceof JSONArray)) return null;
                pending = decodeList((JSONArray) rawPending, ids);
                if (pending == null) return null;

                Object rawEpoch = root.opt("epoch");
                if (!(rawEpoch instanceof String) || ((String) rawEpoch).isEmpty()) {
                    return null;
                }
                epoch = (String) rawEpoch;

                Object rawInitialized = root.opt("perDirInitialized");
                if (!(rawInitialized instanceof Boolean)) return null;
                initialized = (Boolean) rawInitialized;
            } else {
                Object rawInitialized = root.opt("perDirInitialized");
                if (rawInitialized != null) {
                    if (!(rawInitialized instanceof Boolean)) return null;
                    initialized = (Boolean) rawInitialized;
                }
            }

            return new HistoryDocument(global, pending, dirs, ids.nextId(), epoch, initialized);
        } catch (JSONException | ClassCastException e) {
            return null;
        }
    }

    /**
     * Read the two legacy stores. Never returns null: an unparseable or absent
     * store simply contributes no entries.
     */
    @NonNull
    static HistoryDocument decodeLegacy(@Nullable String globalJson,
            @Nullable String perDirectoryJson) {
        IdAllocator ids = new IdAllocator(1);
        ArrayList<HistoryEntry> global = decodeLegacyList(parseArray(globalJson), ids);

        LinkedHashMap<String, ArrayList<HistoryEntry>> dirs = new LinkedHashMap<>();
        JSONObject perDirectory = parseObject(perDirectoryJson);
        if (perDirectory != null) {
            for (String key : sortedKeys(perDirectory)) {
                JSONArray array = perDirectory.optJSONArray(key);
                if (array == null) continue;
                ArrayList<HistoryEntry> list = decodeLegacyList(array, ids);
                if (!list.isEmpty()) dirs.put(key, list);
            }
        }
        return new HistoryDocument(global, Collections.<HistoryEntry>emptyList(), dirs,
                ids.nextId(), HistoryDocument.NO_EPOCH, false);
    }

    // ── v3 lists ──

    @NonNull
    private static JSONArray encodeList(@NonNull List<HistoryEntry> entries) throws JSONException {
        JSONArray array = new JSONArray();
        for (HistoryEntry entry : entries) {
            JSONObject object = new JSONObject();
            object.put("id", entry.id);
            object.put("text", entry.text);
            array.put(object);
        }
        return array;
    }

    /**
     * @return the decoded list, or null when the array is structurally invalid.
     *         A valid record with a missing/duplicate id is repaired, not rejected.
     */
    @Nullable
    private static ArrayList<HistoryEntry> decodeList(@Nullable JSONArray array,
            @NonNull IdAllocator ids) {
        ArrayList<HistoryEntry> out = new ArrayList<>();
        if (array == null) return out;
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            Object raw = array.opt(i);
            if (!(raw instanceof JSONObject)) return null;
            JSONObject object = (JSONObject) raw;
            Object rawId = object.opt("id");
            Object rawText = object.opt("text");
            if ((rawId != null && !(rawId instanceof Number))
                    || !(rawText instanceof String)) {
                return null;
            }
            long id = rawId == null ? 0 : ((Number) rawId).longValue();
            String text = HistoryText.trim((String) rawText);
            if (text.isEmpty() || !seen.add(text)) {
                // Keep the id reserved even when the record is dropped, so a later
                // record cannot inherit its dismissals.
                ids.reserve(id);
                continue;
            }
            out.add(new HistoryEntry(ids.use(id), text));
        }
        return out;
    }

    // ── v1 lists ──

    @Nullable
    private static JSONArray parseArray(@Nullable String json) {
        if (json == null) return null;
        try {
            return new JSONArray(json);
        } catch (JSONException e) {
            return null;
        }
    }

    @Nullable
    private static JSONObject parseObject(@Nullable String json) {
        if (json == null) return null;
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            return null;
        }
    }

    @NonNull
    private static ArrayList<HistoryEntry> decodeLegacyList(@Nullable JSONArray array,
            @NonNull IdAllocator ids) {
        ArrayList<HistoryEntry> out = new ArrayList<>();
        if (array == null) return out;
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            Object raw = array.opt(i);
            if (!(raw instanceof String)) continue;
            String text = HistoryText.trim((String) raw);
            if (text.isEmpty() || !seen.add(text)) continue;
            out.add(new HistoryEntry(ids.allocate(), text));
        }
        return out;
    }

    @NonNull
    private static ArrayList<String> sortedKeys(@NonNull JSONObject object) {
        ArrayList<String> keys = new ArrayList<>();
        for (Iterator<String> it = object.keys(); it.hasNext(); ) keys.add(it.next());
        Collections.sort(keys);
        return keys;
    }

    /**
     * Allocates ids in document order and repairs duplicate/missing ids found on
     * disk without ever handing out an id that was present in the document.
     */
    private static final class IdAllocator {

        private long mNext;
        @NonNull private final HashSet<Long> mUsed = new HashSet<>();

        IdAllocator(long next) {
            mNext = Math.max(1, next);
        }

        long use(long candidate) {
            if (candidate > 0 && mUsed.add(candidate)) {
                if (candidate >= mNext) mNext = candidate + 1;
                return candidate;
            }
            return allocate();
        }

        void reserve(long candidate) {
            if (candidate <= 0) return;
            mUsed.add(candidate);
            if (candidate >= mNext) mNext = candidate + 1;
        }

        long allocate() {
            while (mUsed.contains(mNext)) mNext++;
            long id = mNext++;
            mUsed.add(id);
            return id;
        }

        long nextId() {
            return mNext;
        }
    }
}
