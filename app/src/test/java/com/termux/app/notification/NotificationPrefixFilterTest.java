package com.termux.app.notification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class NotificationPrefixFilterTest {

    @Test
    public void titleModeMatchesOnlyTheTitle() {
        NotificationPrefixFilter filter =
            new NotificationPrefixFilter(NotificationPrefixFilter.Mode.TITLE, "Build");

        assertTrue(filter.matches("Build failed", "body"));
        assertFalse(filter.matches("body", "Build failed"));
        assertFalse(filter.matches(null, "Build failed"));
    }

    @Test
    public void bodyModeMatchesOnlyTheBody() {
        NotificationPrefixFilter filter =
            new NotificationPrefixFilter(NotificationPrefixFilter.Mode.BODY, "Build");

        assertTrue(filter.matches("title", "Build failed"));
        assertFalse(filter.matches("Build failed", "title"));
        assertFalse(filter.matches("Build failed", null));
    }

    @Test
    public void prefixHasToBeAtTheStart() {
        NotificationPrefixFilter filter =
            new NotificationPrefixFilter(NotificationPrefixFilter.Mode.TITLE, "Build");

        assertFalse(filter.matches("The Build failed", null));
    }

    @Test
    public void prefixIsTrimmed() {
        NotificationPrefixFilter filter =
            new NotificationPrefixFilter(NotificationPrefixFilter.Mode.TITLE, "  Build  ");

        assertEquals("Build", filter.getPrefix());
        assertTrue(filter.matches("Build failed", null));
    }

    @Test
    public void emptyPrefixMatchesNothing() {
        NotificationPrefixFilter filter =
            new NotificationPrefixFilter(NotificationPrefixFilter.Mode.TITLE, "   ");

        assertFalse(filter.matches("Build failed", "Build failed"));
    }

    @Test
    public void matchesAnyUsesEveryRule() {
        List<NotificationPrefixFilter> filters = Arrays.asList(
            new NotificationPrefixFilter(NotificationPrefixFilter.Mode.TITLE, "Build"),
            new NotificationPrefixFilter(NotificationPrefixFilter.Mode.BODY, "Done"));
        String serialized = NotificationPrefixFilter.serialize(filters);

        assertTrue(NotificationPrefixFilter.matchesAny(serialized, "Build failed", null));
        assertTrue(NotificationPrefixFilter.matchesAny(serialized, "Other", "Done in 3s"));
        assertFalse(NotificationPrefixFilter.matchesAny(serialized, "Other", "Still running"));
        assertFalse(NotificationPrefixFilter.matchesAny(null, "Build failed", null));
        assertFalse(NotificationPrefixFilter.matchesAny("[]", "Build failed", null));
    }

    @Test
    public void serializationRoundTrips() {
        List<NotificationPrefixFilter> filters = Arrays.asList(
            new NotificationPrefixFilter(NotificationPrefixFilter.Mode.TITLE, "Build"),
            new NotificationPrefixFilter(NotificationPrefixFilter.Mode.BODY, "Done"));

        assertEquals(filters, NotificationPrefixFilter.parse(
            NotificationPrefixFilter.serialize(filters)));
    }

    @Test
    public void serializationHandlesSpecialCharacters() {
        List<NotificationPrefixFilter> filters = Arrays.asList(
            new NotificationPrefixFilter(NotificationPrefixFilter.Mode.TITLE, "a\"b\\c\nd\t"));

        assertEquals(filters, NotificationPrefixFilter.parse(
            NotificationPrefixFilter.serialize(filters)));
    }

    @Test
    public void corruptStorageMeansNoFilters() {
        assertTrue(NotificationPrefixFilter.parse("not a json array").isEmpty());
        assertTrue(NotificationPrefixFilter.parse("").isEmpty());
        assertTrue(NotificationPrefixFilter.parse(null).isEmpty());
    }
}
