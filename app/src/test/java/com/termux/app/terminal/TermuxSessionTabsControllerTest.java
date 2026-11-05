package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

/** Pins the alpha steps used for the faded tail of a truncated tab title. */
public class TermuxSessionTabsControllerTest {

    @Test
    public void lastThreeCharactersFadeFrom75To25Percent() {
        // Counting from the end: the third-to-last character keeps 75%, the second 50%,
        // and the final visible character 25%.
        Assert.assertEquals(191, TermuxSessionTabsController.fadeAlphaForPosition(3));
        Assert.assertEquals(128, TermuxSessionTabsController.fadeAlphaForPosition(2));
        Assert.assertEquals(64, TermuxSessionTabsController.fadeAlphaForPosition(1));
    }

    @Test
    public void charactersBeforeTheTailStayOpaque() {
        Assert.assertEquals(255, TermuxSessionTabsController.fadeAlphaForPosition(4));
        Assert.assertEquals(255, TermuxSessionTabsController.fadeAlphaForPosition(0));
    }
}
