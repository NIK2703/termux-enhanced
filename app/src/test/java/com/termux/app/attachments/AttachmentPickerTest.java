package com.termux.app.attachments;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

public class AttachmentPickerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void joinPathsForInsertionQuotesEveryPath() {
        Assert.assertEquals("\"/data/a\" \"/data/b c\"",
            AttachmentPicker.joinPathsForInsertion(
                Arrays.asList("/data/a", "/data/b c")));
    }

    @Test
    public void joinPathsForInsertionEscapesShellSpecialCharacters() {
        Assert.assertEquals("\"/data/a\\\\b\\\"c\\$d\\`e\"",
            AttachmentPicker.joinPathsForInsertion(
                Collections.singletonList("/data/a\\b\"c$d`e")));
    }

    @Test
    public void joinPathsForInsertionHandlesEmptyList() {
        Assert.assertEquals("", AttachmentPicker.joinPathsForInsertion(Collections.emptyList()));
    }

    @Test
    public void buildPathUnderRootMapsRelativePath() throws Exception {
        File root = temporaryFolder.newFolder("root");
        File expected = new File(root, "Download/a b.txt").getCanonicalFile();

        Assert.assertEquals(expected.getPath(),
            AttachmentPicker.buildPathUnderRoot(root, "Download/a b.txt"));
    }

    @Test
    public void buildPathUnderRootRejectsPathTraversal() throws Exception {
        File root = temporaryFolder.newFolder("root");

        Assert.assertNull(AttachmentPicker.buildPathUnderRoot(root, "../escape.txt"));
    }

    @Test
    public void buildPathUnderRootAcceptsEmptyRelativePath() throws Exception {
        File root = temporaryFolder.newFolder("root");

        Assert.assertEquals(root.getCanonicalPath(),
            AttachmentPicker.buildPathUnderRoot(root, ""));
    }

    @Test
    public void readablePathAcceptsFilesAndRejectsDirectories() throws Exception {
        File file = temporaryFolder.newFile("a.txt");
        File directory = temporaryFolder.newFolder("dir");

        Assert.assertEquals(file.getAbsolutePath(),
            AttachmentPicker.readablePath(file.getAbsolutePath()));
        Assert.assertNull(AttachmentPicker.readablePath(directory.getAbsolutePath()));
        Assert.assertNull(AttachmentPicker.readablePath(null));
    }
}
