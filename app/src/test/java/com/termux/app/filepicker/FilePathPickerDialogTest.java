package com.termux.app.filepicker;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class FilePathPickerDialogTest {

    @Test
    public void joinPathsForInsertionUsesSingleSpacesOnly() {
        Assert.assertEquals(
            "/home/u/file /sdcard/My Folder/a.txt /tmp",
            FilePathPickerDialog.joinPathsForInsertion(Arrays.asList(
                "/home/u/file", "/sdcard/My Folder/a.txt", "/tmp")));
    }

    @Test
    public void joinPathsForInsertionDoesNotAddQuotesOrTrailingSpace() {
        Assert.assertEquals(
            "/data/data/com.termux/files/home/a",
            FilePathPickerDialog.joinPathsForInsertion(Collections.singletonList(
                "/data/data/com.termux/files/home/a")));
    }

    @Test
    public void joinPathsForInsertionReturnsEmptyForEmptySelection() {
        Assert.assertEquals(
            "", FilePathPickerDialog.joinPathsForInsertion(Collections.emptyList()));
    }

    @Test
    public void bookmarkNameUsesLastPathSegment() {
        Assert.assertEquals(
            "home",
            FilePathPickerDialog.getBookmarkName("/data/data/com.termux/files/home"));
        Assert.assertEquals(
            "0",
            FilePathPickerDialog.getBookmarkName("/storage/emulated/0/"));
        Assert.assertEquals(
            "/",
            FilePathPickerDialog.getBookmarkName("/"));
    }
}
