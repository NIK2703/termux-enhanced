package com.termux.app.terminal.io.autocomplete;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure-JVM tests for the v3 document boundary, strict decoding and v2/v1 upgrades. */
public class HistoryJsonCodecTest {

    private static List<HistoryEntry> entries(HistoryEntry... entries) {
        return new ArrayList<>(Arrays.asList(entries));
    }

    @Test
    public void roundTripPreservesEpochPendingIdsAndInitialization() {
        LinkedHashMap<String, ArrayList<HistoryEntry>> dirs = new LinkedHashMap<>();
        dirs.put("/home", new ArrayList<>(entries(new HistoryEntry(4, "ls -la"))));
        dirs.put("/empty", new ArrayList<HistoryEntry>());
        HistoryDocument source = new HistoryDocument(
                entries(new HistoryEntry(1, "global")),
                entries(new HistoryEntry(2, "pending")),
                dirs, 9, "epoch-1", true);

        String json = HistoryJsonCodec.encode(source);
        assertNotNull(json);
        try {
            JSONObject root = new JSONObject(json);
            assertEquals(3, root.getInt("v"));
            assertEquals("epoch-1", root.getString("epoch"));
            assertTrue(root.getBoolean("perDirInitialized"));
            assertFalse("empty directory keys must not accumulate",
                    root.getJSONObject("dirs").has("/empty"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        HistoryDocument decoded = HistoryJsonCodec.decode(json);
        assertNotNull(decoded);
        assertEquals("epoch-1", decoded.epoch);
        assertTrue(decoded.perDirInitialized);
        assertEquals(9, decoded.nextId);
        assertEquals("global", decoded.global.get(0).text);
        assertEquals(1, decoded.global.get(0).id);
        assertEquals("pending", decoded.pending.get(0).text);
        assertEquals(2, decoded.pending.get(0).id);
        assertEquals("ls -la", decoded.directories.get("/home").get(0).text);
        assertEquals(4, decoded.directories.get("/home").get(0).id);
    }

    @Test
    public void v2DocumentIsAcceptedForOneTimeUpgrade() {
        String v2 = "{\"v\":2,\"nextId\":8,"
                + "\"global\":[{\"id\":7,\"text\":\"global\"}],"
                + "\"dirs\":{\"/home\":[{\"id\":3,\"text\":\"home\"}]}}";

        HistoryDocument decoded = HistoryJsonCodec.decode(v2);
        assertNotNull(decoded);
        assertEquals(HistoryDocument.NO_EPOCH, decoded.epoch);
        assertTrue(decoded.pending.isEmpty());
        assertTrue("a v2 document with directory data is already initialized",
                decoded.perDirInitialized);
        assertEquals(8, decoded.nextId);
    }

    @Test
    public void duplicateAndMissingIdsAreRepairedWithoutCollisions() {
        String v3 = "{\"v\":3,\"epoch\":\"e\",\"nextId\":2,\"perDirInitialized\":true,"
                + "\"pending\":[],\"dirs\":{},"
                + "\"global\":["
                + "{\"id\":2,\"text\":\"a\"},"
                + "{\"id\":2,\"text\":\"b\"},"
                + "{\"text\":\"c\"}]}";

        HistoryDocument decoded = HistoryJsonCodec.decode(v3);
        assertNotNull(decoded);
        assertEquals(3, decoded.global.size());
        assertEquals(2, decoded.global.get(0).id);
        assertTrue(decoded.global.get(1).id > 2);
        assertTrue(decoded.global.get(2).id > decoded.global.get(1).id);
        assertTrue(decoded.nextId > decoded.global.get(2).id);
    }

    @Test
    public void structurallyInvalidV3IsRejectedInsteadOfBecomingEmpty() {
        String[] invalid = {
                "{\"v\":3,\"nextId\":1,\"perDirInitialized\":true,"
                        + "\"pending\":[],\"dirs\":{},\"global\":[]}", // no epoch
                "{\"v\":3,\"epoch\":\"e\",\"nextId\":1,"
                        + "\"pending\":[],\"dirs\":{},\"global\":[]}", // no initialized
                "{\"v\":3,\"epoch\":\"e\",\"nextId\":1,\"perDirInitialized\":true,"
                        + "\"dirs\":{},\"global\":[]}", // no pending
                "{\"v\":3,\"epoch\":\"e\",\"nextId\":1,\"perDirInitialized\":true,"
                        + "\"pending\":[],\"global\":[]}", // no dirs
                "{\"v\":3,\"epoch\":\"e\",\"nextId\":1,\"perDirInitialized\":true,"
                        + "\"pending\":[],\"dirs\":{},\"global\":[{\"id\":1}]}", // no text
                "{\"v\":99,\"epoch\":\"e\",\"nextId\":1,\"perDirInitialized\":true,"
                        + "\"pending\":[],\"dirs\":{},\"global\":[]}", // unknown version
                "not json"
        };
        for (String json : invalid) {
            assertNull(json, HistoryJsonCodec.decode(json));
        }
    }

    @Test
    public void legacyListsAreTrimmedDeduplicatedAndIdStable() {
        String global = "[\"  ls  \",\"ls\",\"   \",\"git  \"]";
        String dirs = "{\"/b\":[\"two\"],\"/a\":[\"one\"],\"/empty\":[]}";

        HistoryDocument decoded = HistoryJsonCodec.decodeLegacy(global, dirs);
        assertEquals(Arrays.asList("ls", "git"), texts(decoded.global));
        assertEquals(Collections.singletonList("one"),
                texts(decoded.directories.get("/a")));
        assertEquals(Collections.singletonList("two"),
                texts(decoded.directories.get("/b")));
        assertFalse(decoded.directories.containsKey("/empty"));
        assertEquals(1, decoded.global.get(0).id);
        assertEquals(2, decoded.global.get(1).id);
        assertTrue(decoded.directories.get("/a").get(0).id > 2);
        assertTrue(decoded.directories.get("/b").get(0).id
                > decoded.directories.get("/a").get(0).id);
    }

    private static List<String> texts(List<HistoryEntry> entries) {
        List<String> out = new ArrayList<>(entries.size());
        for (HistoryEntry entry : entries) out.add(entry.text);
        return out;
    }
}
