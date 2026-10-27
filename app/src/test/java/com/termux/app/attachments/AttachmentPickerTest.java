package com.termux.app.attachments;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class AttachmentPickerTest {

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
    public void sanitizeFileNameRejectsPathSeparatorsAndParentReferences() {
        Assert.assertEquals("a_b", AttachmentPicker.sanitizeFileName("a/b"));
        Assert.assertEquals("a_b", AttachmentPicker.sanitizeFileName("a\\b"));
        Assert.assertEquals("attachment", AttachmentPicker.sanitizeFileName(".."));
        Assert.assertEquals("attachment", AttachmentPicker.sanitizeFileName("."));
        Assert.assertEquals("attachment", AttachmentPicker.sanitizeFileName(""));
    }
}
