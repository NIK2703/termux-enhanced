package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

/** Pins the alpha steps used for the faded tail of a truncated tab title. */
public class TermuxSessionTabsControllerTest {

    @Test
    public void lastThreeCharactersFadeFrom75To25Percent() {
        // Counting from the end: the third-to-last character keeps 75%, the second 50%,
        // and the final visible character 25%.
        Assert.assertEquals(191, TextFade.alphaForStep(3));
        Assert.assertEquals(128, TextFade.alphaForStep(2));
        Assert.assertEquals(64, TextFade.alphaForStep(1));
    }

    @Test
    public void charactersBeforeTheTailStayOpaque() {
        Assert.assertEquals(255, TextFade.alphaForStep(4));
        Assert.assertEquals(255, TextFade.alphaForStep(0));
    }

    @Test
    public void truncationMarkersAddedByTheTitleProducerAreRemoved() {
        // OpenCode caps its OSC title at 40 characters and appends U+2026. The renderer has to
        // treat that marker as its own truncation, not as title content.
        Assert.assertEquals("OC | слабые места",
                TermuxSessionTabsController.stripTrailingTruncationMarker("OC | слабые места…"));
        Assert.assertEquals("OC | слабые места",
                TermuxSessionTabsController.stripTrailingTruncationMarker("OC | слабые места ..."));
        Assert.assertEquals("OC | слабые места",
                TermuxSessionTabsController.stripTrailingTruncationMarker("OC | слабые места…   "));
        Assert.assertEquals("OC | слабые места",
                TermuxSessionTabsController.stripTrailingTruncationMarker("OC | слабые места..."));
        Assert.assertEquals("", TermuxSessionTabsController.stripTrailingTruncationMarker("…"));
        // A normal title is returned unchanged.
        Assert.assertEquals("OC | слабые места",
                TermuxSessionTabsController.stripTrailingTruncationMarker("OC | слабые места"));
        Assert.assertEquals("", TermuxSessionTabsController.stripTrailingTruncationMarker(null));
    }
}
