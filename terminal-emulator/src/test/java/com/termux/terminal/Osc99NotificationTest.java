package com.termux.terminal;

import java.util.Base64;
import java.util.List;

/** OSC 99 desktop notification protocol, exercised through the emulator: bytes in, callback out,
 * plus the support-query reply written back to the pty. */
public class Osc99NotificationTest extends TerminalTestCase {

    private static final String ST = "\033\\";

    private void enterOsc99(String metadataAndPayload) {
        enterString("\033]99;" + metadataAndPayload + ST);
    }

    private List<TerminalNotification> notifications() {
        return mOutput.notifications;
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void testTitleThenBodyInTwoChunksYieldsOneNotification() {
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=title:d=0;Build");
        assertTrue("title chunk must not complete the notification", notifications().isEmpty());

        enterOsc99("i=1:p=body:d=1;finished");
        assertEquals(1, notifications().size());

        TerminalNotification n = notifications().get(0);
        assertEquals("1", n.getId());
        assertEquals("Build", n.getTitle().toString());
        assertEquals("finished", n.getBody().toString());
    }

    public void testRepeatedChunksOfTheSameFieldConcatenate() {
        // A field may be sent any number of times, so long text can be split around the chunk limit.
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=body:d=0;one ");
        enterOsc99("i=1:p=body:d=0;two ");
        enterOsc99("i=1:p=body:d=1;three");
        assertEquals(1, notifications().size());
        assertEquals("one two three", notifications().get(0).getBody().toString());
    }

    public void testIdentifiersAreIndependent() {
        withTerminalSized(10, 3);
        enterOsc99("i=a:p=body:d=0;first ");
        enterOsc99("i=b:p=body:d=1;second");
        assertEquals("the incomplete 'a' must not be flushed by 'b' completing", 1, notifications().size());
        assertEquals("second", notifications().get(0).getBody().toString());
    }

    public void testMissingIdentifierUsesTheMandatedDefault() {
        withTerminalSized(10, 3);
        enterOsc99("p=body:d=1;hello");
        assertEquals(1, notifications().size());
        assertEquals(TerminalNotification.DEFAULT_ID, notifications().get(0).getId());
    }

    public void testNoMetadataAtAllIsStillANotification() {
        // "The two semi-colons must always be present even when no metadata is present."
        withTerminalSized(10, 3);
        enterOsc99(";plain body");
        assertEquals(1, notifications().size());
        assertEquals("plain body", notifications().get(0).getBody().toString());
        assertNull("no p=body chunk means no title", notifications().get(0).getTitle());
    }

    public void testBodyIsPromotedToTitleWhenNoTitleWasSent() {
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=body:d=1;only the body");
        TerminalNotification n = notifications().get(0);
        assertNull(n.getTitle());
        assertEquals("only the body", n.getBody().toString());
    }

    public void testNotificationWithNeitherTitleNorBodyIsIgnored() {
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=title:d=0;");
        enterOsc99("i=1:p=body:d=1;");
        assertTrue(notifications().isEmpty());
    }

    public void testWhitespaceOnlyNotificationIsIgnored() {
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=body:d=1;   ");
        assertTrue(notifications().isEmpty());
    }

    public void testNonZeroDoneCompletesTheNotification() {
        // "A non-zero value means the notification is done" — not specifically 1.
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=body:d=7;done");
        assertEquals(1, notifications().size());
    }

    public void testUnknownPayloadTypeIsIgnored() {
        // Unknown p values must be ignored so the protocol can grow, and must not become a body.
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=buttons:d=1;ignore me");
        assertTrue(notifications().isEmpty());
    }

    public void testUnknownMetadataKeysAreIgnored() {
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=body:d=1:zz=9:qq;kept");
        assertEquals(1, notifications().size());
        assertEquals("kept", notifications().get(0).getBody().toString());
    }

    public void testEmptyFinalChunkCompletesButDeliversNothing() {
        // "A notification with not title and no body is ignored" — but its d must still be honoured.
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=body:d=0;");
        enterOsc99("i=1:p=body:d=1;");
        assertTrue(notifications().isEmpty());

        // Proof it was completed rather than stranded: the id is reusable immediately.
        enterOsc99("i=1:p=body:d=1;now with text");
        assertEquals(1, notifications().size());
        assertEquals("now with text", notifications().get(0).getBody().toString());
    }

    public void testBase64PayloadsAreDecoded() {
        // e=1 marks the payload base64, which is how a program sends text containing ';'.
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=title:e=1:d=0;" + base64("Semi;colon and \u00e9\u00e8"));
        enterOsc99("i=1:p=body:e=1:d=1;" + base64("Body; with ; separators"));

        TerminalNotification n = notifications().get(0);
        assertEquals("Semi;colon and \u00e9\u00e8", n.getTitle().toString());
        assertEquals("Body; with ; separators", n.getBody().toString());
    }

    public void testAbsentDoneKeyMeansDoneEvenWithBase64() {
        // The metadata table gives d a default of 1 and says nothing about e changing it, so a
        // base64 chunk omitting d is complete. Getting this wrong is silent: it never appears.
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=title:e=1;" + base64("No done key"));
        assertEquals("an absent d must complete the notification, not hold it back", 1, notifications().size());
        assertEquals("No done key", notifications().get(0).getTitle().toString());
    }

    public void testTwoChunksWithoutDoneKeyAreTwoNotifications() {
        // Each chunk is complete on its own, so nothing accumulates: both are delivered, in order.
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=title:e=1;" + base64("First"));
        enterOsc99("i=1:p=body:e=1;" + base64("Second"));
        assertEquals(2, notifications().size());
        assertEquals("First", notifications().get(0).getTitle().toString());
        assertEquals("Second", notifications().get(1).getBody().toString());
    }

    public void testTitleOnlyNotificationReportsNoBody() {
        // One substitution is mandated (no title becomes the body), the reverse is not, so a
        // consumer can tell "title only" from "both".
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=title:d=1;only a title");
        assertEquals(1, notifications().size());
        TerminalNotification n = notifications().get(0);
        assertEquals("only a title", n.getTitle().toString());
        assertNull("a title-only notification must not grow a body", n.getBody());
    }

    public void testBodyOnlyNotificationReportsNoTitle() {
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=body:d=1;only a body");
        TerminalNotification n = notifications().get(0);
        assertNull(n.getTitle());
        assertEquals("only a body", n.getBody().toString());
    }

    public void testWhitespaceOnlyBodyCountsAsAbsent() {
        // A whitespace-only body carries no information, so a title-only notification stays one.
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=title:d=0;kept");
        enterOsc99("i=1:p=body:d=1;   ");
        assertEquals("kept", notifications().get(0).getTitle().toString());
        assertNull(notifications().get(0).getBody());
    }

    public void testBase64WithoutPaddingStillDecodes() {
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=body:e=1:d=1;" + base64("abcde"));
        assertEquals("abcde", notifications().get(0).getBody().toString());
    }

    public void testUndecodableBase64YieldsTheDecodablePrefix() {
        // Lenient on purpose: a truncated chunk should still show what it did carry.
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=body:e=1:d=1;" + base64("hello") + "!!!!");
        assertEquals("hello", notifications().get(0).getBody().toString());
    }

    public void testSupportQueryIsAnsweredInTheConformingForm() {
        withTerminalSized(10, 3);
        enterOsc99("i=abc:p=?;");
        String reply = mOutput.getOutputAndClear();

        // Mandated shape: echo the identifier and p=?, then key=value details including p=title.
        assertTrue("must echo the identifier, got: " + reply, reply.startsWith("\033]99;i=abc:p=?;"));
        assertTrue("must end with ST, got: " + reply, reply.endsWith(ST));
        assertTrue("p must list title, got: " + reply, reply.contains("p=title"));
    }

    public void testSupportQueryWithoutIdentifierEchoesTheMandatedDefault() {
        withTerminalSized(10, 3);
        enterOsc99("p=?;");
        String reply = mOutput.getOutputAndClear();
        assertTrue("got: " + reply, reply.startsWith("\033]99;i=" + TerminalNotification.DEFAULT_ID + ":p=?;"));
    }

    public void testSupportQueryIsNotTreatedAsANotificationChunk() {
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=title:d=0;Build");
        enterOsc99("i=1:p=?;");
        mOutput.getOutputAndClear();

        enterOsc99("i=1:p=body:d=1;done");
        assertEquals("the query must not have completed the pending notification", 1, notifications().size());
        assertEquals("Build", notifications().get(0).getTitle().toString());
    }

    public void testPQueryWithANonEmptyPayloadIsNotAQuery() {
        // Only an empty payload makes it a support query; otherwise it is an unknown p value.
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=?;some text");
        assertEquals("", mOutput.getOutputAndClear());
        assertTrue(notifications().isEmpty());
    }

    public void testReplySatisfiesTheRealOpenTuiProbe() {
        // The exact query a real client sends, captured from a running `opencode mini` under a pty:
        //     ESC ] 99 ; i=opentui-notifications:p=? ; ESC \
        // Its parser needs all four substrings below to accept the reply.
        withTerminalSized(10, 3);
        enterString("\033]99;i=opentui-notifications:p=?;" + ST);
        String reply = mOutput.getOutputAndClear();
        String payload = reply.substring("\033]99;".length());

        assertTrue("identifier not echoed: " + payload, payload.contains("i=opentui-notifications"));
        assertTrue("query not echoed: " + payload, payload.contains("p=?"));
        assertTrue("no supported-payload list: " + payload, payload.contains("p="));
        assertTrue("title support not advertised: " + payload, payload.contains("title"));
    }

    public void testOccasionsAreParsedAndDefaultToAlways() {
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=body:d=1:o=unfocused;x");
        assertEquals(TerminalNotification.OCCASION_UNFOCUSED, notifications().get(0).getOccasions());

        enterOsc99("i=2:p=body:d=1;y");
        assertEquals(TerminalNotification.OCCASION_ALWAYS, notifications().get(1).getOccasions());
    }

    public void testSeveralOccasionsAreAllRecorded() {
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=body:d=1:o=focused,invisible;x");
        int expected = TerminalNotification.OCCASION_FOCUSED | TerminalNotification.OCCASION_INVISIBLE;
        assertEquals(expected, notifications().get(0).getOccasions());
    }

    public void testUnknownOccasionsFallBackToAlways() {
        // Better to over-show than to silently drop: the program asked, we just do not grok it.
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=body:d=1:o=someday;x");
        assertEquals(TerminalNotification.OCCASION_ALWAYS, notifications().get(0).getOccasions());
    }

    public void testActionsDefaultToFocusWithoutReport() {
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=body:d=1;x");
        assertTrue(notifications().get(0).isFocusOnActivate());
        assertFalse(notifications().get(0).isReportOnActivate());
    }

    public void testReportActionIsParsed() {
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=body:d=1:a=focus,report;x");
        assertTrue(notifications().get(0).isFocusOnActivate());
        assertTrue(notifications().get(0).isReportOnActivate());
    }

    public void testNegatedActionsTurnTheDefaultOff() {
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=body:d=1:a=-focus;x");
        assertFalse(notifications().get(0).isFocusOnActivate());
        assertFalse(notifications().get(0).isReportOnActivate());
    }

    public void testSilentSoundIsParsed() {
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=body:d=1:s=silent;x");
        assertTrue(notifications().get(0).isSilent());

        enterOsc99("i=2:p=body:d=1:s=system;y");
        assertFalse(notifications().get(1).isSilent());
    }

    public void testBase64EncodedSilentSoundIsParsed() {
        // The s key is always base64 in a notification; e=1 marks only the payload.
        withTerminalSized(10, 3);
        enterOsc99("i=1:p=body:d=1:s=" + base64("silent") + ";ping");
        assertEquals(1, notifications().size());
        assertTrue(notifications().get(0).isSilent());
    }

    public void testPendingNotificationsAreCapped() {
        // A program that never sends d=1 must not be able to grow the map without bound.
        withTerminalSized(10, 3);
        for (int i = 0; i < 64; i++) {
            enterOsc99("i=n" + i + ":p=body:d=0;x");
        }
        enterOsc99("i=final:p=body:d=1;y");
        assertEquals("only the completed one is delivered", 1, notifications().size());
        assertEquals("final", notifications().get(0).getId());
    }

    public void testLongNotificationIsDeliveredWhole() {
        // No length policy of our own: only notifications still missing their d=1 are discarded.
        withTerminalSized(10, 3);
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < 200; i++) chunk.append("0123456789");
        String piece = chunk.toString();

        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            enterOsc99("i=1:p=body:d=0;" + piece);
            expected.append(piece);
        }
        enterOsc99("i=1:p=body:d=1;" + piece);
        expected.append(piece);

        assertEquals(1, notifications().size());
        assertEquals("nothing may be trimmed", expected.toString(), notifications().get(0).getBody().toString());
    }

    public void testOsc99DoesNotDisturbTitleSequences() {
        withTerminalSized(10, 3);
        enterString("\033]0;My Title" + ST);
        enterOsc99("i=1:p=body:d=1;x");
        assertEquals("My Title", mOutput.titleChanges.get(0).newTitle);
    }

    public void testOsc99IsAcceptedTerminatedByBellToo() {
        withTerminalSized(10, 3);
        enterString("\033]99;i=1:p=body:d=1;ping\007");
        assertEquals(1, notifications().size());
        assertEquals("ping", notifications().get(0).getBody().toString());
    }

    public void testActivationReportUsesTheMandatedForm() {
        assertEquals("\033]99;i=abc;" + ST, TerminalNotification.buildActivationReport("abc"));
    }

    public void testOneNotificationCannotGrowWithoutBound() {
        // The total cap evicts the oldest pending notification, which a growing one is not, so it
        // needs a cap of its own. A program that never sends d=1 must not be able to hold memory.
        withTerminalSized(10, 3);

        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < 4000; i++) chunk.append("x");
        String text = chunk.toString();

        for (int i = 0; i < 5; i++) enterOsc99("i=1:p=body:d=0;" + text);
        assertTrue("an unfinished notification must not be delivered", notifications().isEmpty());

        // Past the cap it is dropped whole rather than truncated, so a later d=1 has nothing to
        // deliver and the next notification starts from clean.
        enterOsc99("i=2:p=body:d=1;after");
        assertEquals(1, notifications().size());
        assertEquals("2", notifications().get(0).getId());
        assertEquals("after", notifications().get(0).getBody().toString());
    }
}
