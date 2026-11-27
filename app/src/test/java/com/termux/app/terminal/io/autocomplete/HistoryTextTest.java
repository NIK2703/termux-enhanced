package com.termux.app.terminal.io.autocomplete;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Word-boundary rules for the "absorb prefixes" history option. */
public class HistoryTextTest {

    @Test
    public void wordPrefixesAreAbsorbable() {
        assertTrue(HistoryText.isAbsorbablePrefix("git", "git status"));
        assertTrue(HistoryText.isAbsorbablePrefix("git status", "git status --short"));
        assertTrue(HistoryText.isAbsorbablePrefix("echo foo", "echo foo bar"));
        assertTrue(HistoryText.isAbsorbablePrefix("line one", "line one\nline two"));
    }

    @Test
    public void pathPrefixesAreAbsorbableAtSlashBoundaries() {
        assertTrue(HistoryText.isAbsorbablePrefix("git", "git/status"));
        assertTrue(HistoryText.isAbsorbablePrefix("cd /data", "cd /data/bin"));
        assertTrue(HistoryText.isAbsorbablePrefix("cd /data/", "cd /data/bin"));
    }

    @Test
    public void midWordAndEqualTextsAreNotAbsorbable() {
        assertFalse(HistoryText.isAbsorbablePrefix("git", "github"));
        assertFalse(HistoryText.isAbsorbablePrefix("echo foo", "echo foobar"));
        assertFalse(HistoryText.isAbsorbablePrefix("git status", "git status"));
        assertFalse(HistoryText.isAbsorbablePrefix("", "git status"));
        assertFalse(HistoryText.isAbsorbablePrefix("git status --short", "git status"));
    }
}
