package com.termux.app.terminal.io.autocomplete;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Pure-JVM invariants of the store: id stability, pending drains, trims and clears. */
public class HistoryStoreTest {

    private static final String HOME = "/home/user";
    private static final String LOG = "/var/log";

    @Test
    public void promotionKeepsTheRecordIdAndMovesItToTheHead() {
        HistoryStore store = new HistoryStore();
        store.addCurrent("one");
        store.addCurrent("two");
        store.addCurrent("three");
        long two = idOf(store.current(), "two");

        assertTrue(store.addCurrent("two"));
        assertEquals("two", store.current().get(0).text);
        assertEquals(two, store.current().get(0).id);
        assertEquals(3, store.current().size());
    }

    @Test
    public void pendingDrainKeepsPendingNewerAndRemapsOldDuplicateIds() {
        HistoryStore store = new HistoryStore();
        store.useDirectory(HOME);
        store.addCurrent("old");
        store.addCurrent("duplicate");
        long oldDuplicate = idOf(store.current(), "duplicate");

        store.usePending();
        store.addCurrent("newest");
        store.addCurrent("duplicate");
        long pendingDuplicate = idOf(store.current(), "duplicate");

        HistoryStore.MergeResult result = store.mergePendingIntoDirectory(HOME);
        assertTrue(result.changed);
        assertEquals(pendingDuplicate, (long) result.idRemap.get(oldDuplicate));
        assertTrue(store.pending().isEmpty());

        List<HistoryEntry> merged = store.directory(HOME);
        assertNotNull(merged);
        assertEquals("duplicate", merged.get(0).text);
        assertEquals(pendingDuplicate, merged.get(0).id);
        assertEquals("newest", merged.get(1).text);
        assertEquals("old", merged.get(2).text);
    }

    @Test
    public void globalDrainKeepsPendingAndRemapsTheOlderDuplicate() {
        HistoryStore store = new HistoryStore();
        store.addCurrent("only-global");
        store.addCurrent("duplicate");
        long globalDuplicate = idOf(store.global(), "duplicate");

        store.usePending();
        store.addCurrent("pending-newer");
        store.addCurrent("duplicate");
        long pendingDuplicate = idOf(store.pending(), "duplicate");

        HistoryStore.MergeResult result = store.mergeGlobalIntoPending();
        assertTrue(result.changed);
        assertEquals(pendingDuplicate, (long) result.idRemap.get(globalDuplicate));
        assertTrue(store.global().isEmpty());
        assertEquals("duplicate", store.pending().get(0).text);
        assertEquals(pendingDuplicate, store.pending().get(0).id);
        assertEquals("pending-newer", store.pending().get(1).text);
        assertEquals("only-global", store.pending().get(2).text);
    }

    @Test
    public void trimAllRemovesEmptyNonCurrentDirectoriesAndKeepsTheAttachedEmptyOne() {
        HistoryStore store = new HistoryStore();
        store.useDirectory(HOME);
        store.addCurrent("home");
        store.useDirectory(LOG);
        store.addCurrent("log");
        store.useDirectory(HOME);

        store.trimAll(0);

        assertTrue(store.current().isEmpty());
        assertTrue("the selected empty directory stays attached", store.directory(HOME).isEmpty());
        assertFalse("an empty background directory is removed", store.directories().containsKey(LOG));
    }

    @Test
    public void clearAllLeavesAnAttachedPendingListThatAcceptsWrites() {
        HistoryStore store = new HistoryStore();
        store.useDirectory(HOME);
        store.addCurrent("one");
        long before = store.nextId();

        store.clearAll();

        assertTrue(store.isCurrentPending());
        assertEquals(".", store.currentCwd());
        assertSame(store.pending(), store.current());
        assertEquals(2, before);

        store.addCurrent("after-clear");
        assertEquals("after-clear", store.pending().get(0).text);
        assertEquals("ids are monotonic and not reset by clear-all",
                before, store.pending().get(0).id);
    }

    @Test
    public void clearingTheCurrentDirectoryKeepsTheSameAttachedList() {
        HistoryStore store = new HistoryStore();
        store.useDirectory(HOME);
        store.addCurrent("one");
        List<HistoryEntry> attached = store.current();

        store.clearCurrent();
        store.addCurrent("two");

        assertSame(attached, store.current());
        assertEquals("two", store.current().get(0).text);
    }

    @Test
    public void absorbingPrefixesRemovesOnlyProperWordPrefixes() {
        HistoryStore store = new HistoryStore();
        store.addCurrent("git");
        store.addCurrent("git status");
        store.addCurrent("github");
        store.addCurrent("git status -s");
        store.addCurrent("cd /data");
        store.addCurrent("cd /data/");
        store.addCurrent("echo foo");
        store.addCurrent("echo foobar");
        store.addCurrent("git status --short");

        List<HistoryEntry> removed = store.removeCurrentPrefixes("git status --short");

        assertEquals(2, removed.size());
        assertEquals("git", removed.get(0).text);
        assertEquals("git status", removed.get(1).text);
        assertEquals("[git status --short, echo foobar, echo foo, cd /data/, cd /data, "
                + "git status -s, github]", String.valueOf(HistoryStore.textView(store.current())));

        removed = store.removeCurrentPrefixes("cd /data/bin");
        assertEquals(2, removed.size());
        assertEquals("[git status --short, echo foobar, echo foo, git status -s, github]",
                String.valueOf(HistoryStore.textView(store.current())));

        removed = store.removeCurrentPrefixes("echo foobar baz");
        assertEquals(1, removed.size());
        assertEquals("echo foobar", removed.get(0).text);

        assertTrue(store.removeCurrentPrefixes("github").isEmpty());
        assertTrue(store.removeCurrentPrefixes("git").isEmpty());
    }

    @Test
    public void directoryPrefixesAreRemovedOnlyInThatDirectory() {
        HistoryStore store = new HistoryStore();
        store.useDirectory(HOME);
        store.addCurrent("git");
        store.useDirectory(LOG);
        store.addCurrent("git");
        store.addCurrent("git status -s");

        List<HistoryEntry> removed = store.removeDirectoryPrefixes(LOG, "git status --short");

        assertEquals(1, removed.size());
        assertEquals("git", removed.get(0).text);
        assertEquals("[git status -s]",
                String.valueOf(HistoryStore.textView(store.directory(LOG))));
        assertEquals("[git]",
                String.valueOf(HistoryStore.textView(store.directory(HOME))));
    }

    @Test
    public void snapshotCopiesListsSoTheWriterNeverSeesMutation() {
        HistoryStore store = new HistoryStore();
        store.useDirectory(HOME);
        store.addCurrent("one");
        HistoryDocument snapshot = store.snapshot();

        store.addCurrent("two");

        assertEquals(1, snapshot.directories.get(HOME).size());
        assertEquals(2, store.directory(HOME).size());
        assertNotNull(snapshot.epoch);
    }

    private static long idOf(List<HistoryEntry> entries, String text) {
        for (HistoryEntry entry : entries) {
            if (entry.text.equals(text)) return entry.id;
        }
        return -1;
    }
}
