package com.termux.app.terminal.io.autocomplete;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Field;

/**
 * Per-directory history: what a write for a directory that is not the one on screen does to the
 * directory the user is actually in.
 *
 * <p>Two callers do that — a notification reply filed for a background session, and the panel's
 * own send after a {@code cd} the session-change callback has not been told about — and both must
 * leave the foreground directory's entries alone.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MessageHistoryControllerTest {

    private static final String HOME = "/home/user";
    private static final String LOG_DIR = "/var/log";

    private SharedPreferences prefs;
    private MessageHistoryController ctrl;

    @Before
    public void setUp() throws Exception {
        resetSingleton();
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        ctrl = newController();
        ctrl.load(HOME);
    }

    /** The controller an activity gets, configured the way the activity configures it. */
    private MessageHistoryController newController() throws Exception {
        MessageHistoryController controller = rawController();
        controller.setPerDirectoryEnabled(true);
        controller.setMaxSize(50);
        return controller;
    }

    /** The controller a broadcast-started process gets: the singleton, configured by nobody. */
    private MessageHistoryController rawController() throws Exception {
        resetSingleton();
        return MessageHistoryController.shared(prefs);
    }

    private void resetSingleton() throws Exception {
        Field shared = MessageHistoryController.class.getDeclaredField("sShared");
        shared.setAccessible(true);
        shared.set(null, null);
    }

    /** Texts stored under {@code dir} in the v2 document. */
    private JSONArray stored(String dir) {
        return textsOf(storedEntries(dir));
    }

    private JSONArray storedEntries(String dir) {
        try {
            String json = prefs.getString("message_history_v2", null);
            if (json == null) return new JSONArray();
            JSONObject dirs = new JSONObject(json).optJSONObject("dirs");
            if (dirs == null) return new JSONArray();
            JSONArray entries = dirs.optJSONArray(dir);
            return entries == null ? new JSONArray() : entries;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Texts stored in the v2 global list. */
    private JSONArray globalStore() {
        try {
            String json = prefs.getString("message_history_v2", null);
            if (json == null) return new JSONArray();
            JSONArray entries = new JSONObject(json).optJSONArray("global");
            return textsOf(entries == null ? new JSONArray() : entries);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static JSONArray textsOf(JSONArray entries) {
        JSONArray texts = new JSONArray();
        for (int i = 0; i < entries.length(); i++) {
            texts.put(entries.optJSONObject(i).optString("text", ""));
        }
        return texts;
    }

    // A reply for a background session

    @Test
    public void replyIntoAnotherDirectoryKeepsTheForegroundOnesHistory() {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistory("git status", HOME);
        ctrl.save();

        ctrl.addToMessageHistoryInDirectory("yes", LOG_DIR);
        ctrl.save();

        ctrl.addToMessageHistory("pwd", HOME);
        ctrl.save();

        assertEquals("[\"pwd\",\"git status\",\"ls -la\"]", String.valueOf(stored(HOME)));
        assertEquals("[\"yes\"]", String.valueOf(stored(LOG_DIR)));
    }

    @Test
    public void replyIntoAnotherDirectoryDoesNotEmptyTheVisibleList() {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistory("git status", HOME);

        ctrl.addToMessageHistoryInDirectory("yes", LOG_DIR);

        assertEquals("the window's own history, untouched: " + ctrl.getHistoryList(),
            "[git status, ls -la]", String.valueOf(ctrl.getHistoryList()));
        assertEquals("and the window is still on its own session", HOME,
            ctrl.getHistoryCurrentDirectory());
    }

    @Test
    public void repeatedRepliesToABackgroundSessionAccumulate() {
        ctrl.addToMessageHistoryInDirectory("yes", LOG_DIR);
        ctrl.addToMessageHistoryInDirectory("no", LOG_DIR);
        ctrl.addToMessageHistoryInDirectory("yes", LOG_DIR);
        ctrl.save();

        assertEquals("[\"yes\",\"no\"]", String.valueOf(stored(LOG_DIR)));
    }

    @Test
    public void clearedTextAfterAReplyKeepsTheForegroundDirectory() {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistory("git status", HOME);
        ctrl.save();

        ctrl.addToMessageHistoryInDirectory("yes", LOG_DIR);
        ctrl.save();

        ctrl.addNewOnTop("half typed", HOME);
        ctrl.save();

        assertEquals("[\"half typed\",\"git status\",\"ls -la\"]", String.valueOf(stored(HOME)));
    }

    /** Not notification-specific: the panel's own send does the same after an unreported {@code cd}. */
    @Test
    public void plainCdThenSendKeepsBothDirectories() {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistory("git status", HOME);
        ctrl.save();

        // /etc has its own history from an earlier visit.
        ctrl.onHistoryDirectoryChanged(HOME, "/etc");
        ctrl.addToMessageHistory("uptime", "/etc");
        ctrl.save();
        ctrl.onHistoryDirectoryChanged("/etc", HOME);

        ctrl.addToMessageHistory("df -h", "/etc");
        ctrl.save();

        assertEquals("[\"df -h\",\"uptime\"]", String.valueOf(stored("/etc")));
        assertEquals("[\"git status\",\"ls -la\"]", String.valueOf(stored(HOME)));
    }

    // Edge whitespace

    @Test
    public void savingTrimsEdgeWhitespaceAndDeduplicatesOnTheTrimmedValue() {
        ctrl.addToMessageHistory("  ls -la  ", HOME);
        ctrl.addToMessageHistory("git status", HOME);
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addNewOnTop("  pwd  ", HOME);
        ctrl.addNewOnTop("pwd", HOME);
        ctrl.save();

        assertEquals("[pwd, ls -la, git status]", String.valueOf(ctrl.getHistoryList()));
        assertEquals("[\"pwd\",\"ls -la\",\"git status\"]", String.valueOf(stored(HOME)));
    }

    @Test
    public void replyIntoAnotherDirectoryTrimsEdgeWhitespace() {
        ctrl.addToMessageHistoryInDirectory("  yes  ", LOG_DIR);
        ctrl.addToMessageHistoryInDirectory("yes", LOG_DIR);
        ctrl.save();

        assertEquals("[\"yes\"]", String.valueOf(stored(LOG_DIR)));
    }

    @Test
    public void reSendingNewestMessageDoesNotChangeOrderOrVersion() {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistory("git status", HOME);
        int version = ctrl.getHistoryVersion();

        ctrl.addToMessageHistory("  git status  ", HOME);
        assertEquals("[git status, ls -la]", String.valueOf(ctrl.getHistoryList()));
        assertEquals(version, ctrl.getHistoryVersion());

        ctrl.save();
        assertEquals("[\"git status\",\"ls -la\"]", String.valueOf(stored(HOME)));
    }

    @Test
    public void repeatedReplyOfNewestEntryDoesNotBumpVersion() {
        ctrl.addToMessageHistoryInDirectory("yes", LOG_DIR);
        int version = ctrl.getHistoryVersion();

        ctrl.addToMessageHistoryInDirectory("  yes  ", LOG_DIR);
        assertEquals(version, ctrl.getHistoryVersion());

        ctrl.save();
        assertEquals("[\"yes\"]", String.valueOf(stored(LOG_DIR)));
    }

    @Test
    public void reSendingNewestEntryStillEnforcesALoweredLimit() {
        ctrl.setMaxSize(3);
        ctrl.addToMessageHistory("one", HOME);
        ctrl.addToMessageHistory("two", HOME);
        ctrl.addToMessageHistory("three", HOME);
        ctrl.setMaxSize(1);

        ctrl.addToMessageHistory("three", HOME);

        assertEquals("[three]", String.valueOf(ctrl.getHistoryList()));
    }

    @Test
    public void loweringTheLimitTrimsTheCurrentListImmediately() {
        ctrl.setMaxSize(3);
        ctrl.addToMessageHistory("one", HOME);
        ctrl.addToMessageHistory("two", HOME);
        ctrl.addToMessageHistory("three", HOME);
        int version = ctrl.getHistoryVersion();

        ctrl.setMaxSize(1);

        assertEquals("[three]", String.valueOf(ctrl.getHistoryList()));
        assertTrue(ctrl.getHistoryVersion() > version);
    }

    @Test
    public void zeroLimitDisablesHistory() {
        ctrl.addToMessageHistory("one", HOME);

        ctrl.setMaxSize(0);

        assertTrue(ctrl.getHistoryList().isEmpty());
        ctrl.addToMessageHistory("two", HOME);
        assertTrue(ctrl.getHistoryList().isEmpty());
    }

    @Test
    public void blankMessageDetectionUsesUnicodeWhitespace() {
        assertTrue(MessageHistoryController.isBlankMessage(null));
        assertTrue(MessageHistoryController.isBlankMessage(""));
        assertTrue(MessageHistoryController.isBlankMessage(" \t\n\u00A0"));
        assertFalse(MessageHistoryController.isBlankMessage(" ls "));
        assertFalse(MessageHistoryController.isBlankMessage("a b"));
    }

    @Test
    public void loadTrimsLegacyEntriesAndDeduplicatesThem() throws Exception {
        String store = new JSONObject()
            .put(HOME, new JSONArray().put("  ls  ").put("ls").put("   ").put("git  "))
            .toString();
        prefs.edit().putString("message_history_per_directory", store).commit();

        MessageHistoryController reloaded = newController();
        reloaded.load(HOME);

        assertEquals("[ls, git]", String.valueOf(reloaded.getHistoryList()));
    }

    @Test
    public void containsAndEqualityIgnoreEdgeWhitespaceButNotInternalSpacing() {
        ctrl.addToMessageHistory("  git status  ", HOME);

        assertTrue(ctrl.containsMessage("git status"));
        assertTrue(ctrl.containsMessage("\tgit status \n"));
        assertTrue(ctrl.containsMessage("\u00A0git status\u00A0"));
        assertFalse(ctrl.containsMessage("git  status"));
        assertFalse(ctrl.containsMessage("   "));

        assertTrue(MessageHistoryController.messagesEqual("  git status  ", "git status"));
        assertFalse(MessageHistoryController.messagesEqual("git  status", "git status"));
    }

    @Test
    public void whitespaceOnlyMessagesAreIgnored() {
        ctrl.addToMessageHistory(" \t\n ", HOME);
        ctrl.addNewOnTop("   ", HOME);
        ctrl.addToMessageHistoryInDirectory("  ", LOG_DIR);

        assertEquals("[]", String.valueOf(ctrl.getHistoryList()));
    }

    // A reply that cold-starts the process

    @Test
    public void coldStartReplyGoesToTheStoreTheUserIsUsing() throws Exception {
        prefs.edit()
            .putBoolean("per_directory_message_history", true)
            .putInt("message_history_max", 50)
            .commit();
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistory("git status", HOME);
        ctrl.save();
        assertTrue("precondition: no global store", prefs.getString("message_history", null) == null);

        // The broadcast started the process: no activity, nothing to configure a controller.
        MessageHistoryController cold = rawController();
        cold.addToMessageHistoryInDirectory("yes", LOG_DIR);
        cold.save();

        assertEquals("the reply landed in the per-directory store: " + stored(LOG_DIR),
            "[\"yes\"]", String.valueOf(stored(LOG_DIR)));
        assertEquals("and not in the store the user is not on: "
                + prefs.getString("message_history", "null"),
            "null", prefs.getString("message_history", "null"));
    }

    @Test
    public void coldStartReplyKeepsTheUsersConfiguredLimit() throws Exception {
        prefs.edit()
            .putBoolean("per_directory_message_history", false)
            .putInt("message_history_max", 500)
            .commit();
        ctrl.setPerDirectoryEnabled(false);
        ctrl.setMaxSize(500);
        for (int i = 0; i < 300; i++) ctrl.addToMessageHistory("cmd" + i, null);
        ctrl.save();
        assertEquals(300, ctrl.getHistoryList().size());

        // The broadcast started the process: nothing has configured a controller, so a write must
        // not fall back to the built-in default.
        MessageHistoryController cold = rawController();
        cold.addToMessageHistory("yes", null);
        cold.save();

        JSONArray onDisk = globalStore();
        assertEquals("entries left after one reply, against a configured limit of 500",
            301, onDisk.length());
        assertTrue("and the oldest are still there", onDisk.toString().contains("cmd0\""));
    }

    @Test
    public void coldStartReplyHonoursTheGlobalModeTheUserChose() throws Exception {
        prefs.edit()
            .putBoolean("per_directory_message_history", false)
            .putInt("message_history_max", 50)
            .commit();
        ctrl.setPerDirectoryEnabled(false);
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.save();

        MessageHistoryController cold = rawController();
        cold.addToMessageHistoryInDirectory("yes", LOG_DIR);
        cold.save();

        assertEquals("[\"yes\",\"ls -la\"]", String.valueOf(globalStore()));
        assertEquals("per-directory store must stay absent", "null",
            prefs.getString("message_history_per_directory", "null"));
    }

    // Switching the per-directory mode

    /** The activity's own sequence: {@code save()}, flip the flag, {@code load()}. */
    @Test
    public void turningPerDirectoryModeOffAndOnKeepsEveryDirectory() throws Exception {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistoryInDirectory("yes", LOG_DIR);
        ctrl.save();

        ctrl.save();
        ctrl.setPerDirectoryEnabled(false);
        ctrl.load(HOME);

        ctrl.save();
        ctrl.setPerDirectoryEnabled(true);
        ctrl.load(HOME);

        assertEquals("[\"ls -la\"]", String.valueOf(stored(HOME)));
        assertEquals("[\"yes\"]", String.valueOf(stored(LOG_DIR)));
    }

    /** The per-directory store is left on disk, so the entries come back with the mode. */
    @Test
    public void turningPerDirectoryModeOffLeavesThePerDirectoryStoreIntact() throws Exception {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistoryInDirectory("yes", LOG_DIR);
        ctrl.save();

        ctrl.save();
        ctrl.setPerDirectoryEnabled(false);
        ctrl.load(HOME);

        assertEquals("nothing destroyed by the switch: " + stored(HOME),
            "[\"ls -la\"]", String.valueOf(stored(HOME)));
        assertEquals("[\"yes\"]", String.valueOf(stored(LOG_DIR)));
    }

    // Guards on the fix itself

    @Test
    public void replyForTheForegroundSessionGoesThroughTheOrdinaryPath() {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistoryInDirectory("yes", HOME);
        ctrl.save();

        assertEquals("[\"yes\",\"ls -la\"]", String.valueOf(stored(HOME)));
        assertEquals("[yes, ls -la]", String.valueOf(ctrl.getHistoryList()));
    }

    @Test
    public void clearingOneDirectoryLeavesTheOthersAlone() {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistoryInDirectory("yes", LOG_DIR);
        ctrl.save();

        ctrl.clearCurrent(LOG_DIR);

        assertEquals("[\"ls -la\"]", String.valueOf(stored(HOME)));
        assertFalse("the other directory is cleared too",
            String.valueOf(stored(LOG_DIR)).contains("yes"));
    }

    // Removing one entry (swipe-left in the popup)

    @Test
    public void removeMessageDropsOnlyThatEntryAndPersists() {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistory("git status", HOME);
        ctrl.addToMessageHistory("pwd", HOME);
        ctrl.save();

        int version = ctrl.getHistoryVersion();
        assertTrue(ctrl.removeMessage("  git status  "));

        assertEquals("[pwd, ls -la]", String.valueOf(ctrl.getHistoryList()));
        assertTrue(ctrl.getHistoryVersion() > version);

        ctrl.save();
        assertEquals("[\"pwd\",\"ls -la\"]", String.valueOf(stored(HOME)));
    }

    @Test
    public void removeMessageLeavesOtherDirectoriesAlone() {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistory("git status", HOME);
        ctrl.addToMessageHistoryInDirectory("yes", LOG_DIR);
        ctrl.save();

        assertTrue(ctrl.removeMessage("ls -la"));
        ctrl.save();

        assertEquals("[\"git status\"]", String.valueOf(stored(HOME)));
        assertEquals("[\"yes\"]", String.valueOf(stored(LOG_DIR)));
    }

    @Test
    public void removeMessageCanEmptyTheCurrentDirectory() {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.save();

        assertTrue(ctrl.removeMessage("ls -la"));
        assertEquals("[]", String.valueOf(ctrl.getHistoryList()));

        ctrl.save();
        assertEquals("[]", String.valueOf(stored(HOME)));
    }

    @Test
    public void removeUnknownOrBlankMessageIsANoOp() {
        ctrl.addToMessageHistory("ls -la", HOME);
        int version = ctrl.getHistoryVersion();

        assertFalse(ctrl.removeMessage("pwd"));
        assertFalse(ctrl.removeMessage("   "));
        assertFalse(ctrl.removeMessage(null));

        assertEquals("[ls -la]", String.valueOf(ctrl.getHistoryList()));
        assertEquals(version, ctrl.getHistoryVersion());
    }

    @Test
    public void removeMessagePersistsInGlobalMode() {
        ctrl.setPerDirectoryEnabled(false);
        ctrl.load(HOME);
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistory("git status", HOME);
        ctrl.save();

        assertTrue(ctrl.removeMessage("ls -la"));
        ctrl.save();

        assertEquals("[\"git status\"]", String.valueOf(globalStore()));
    }

    // Clearing against a queued persist

    /**
     * A mutation arms a debounced persist carrying a snapshot from that moment, so a clear inside
     * the debounce window must not be undone when it lands.
     */
    @Test
    public void clearIsNotUndoneByAPersistQueuedBeforeIt() {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistory("git status", HOME);
        // No save(): the persist is still sitting in the debounce window.

        ctrl.clearCurrent(HOME);
        idleDebounce();
        drain();

        assertFalse("the queued persist brought the cleared history back",
            String.valueOf(stored(HOME)).contains("git status"));
    }

    @Test
    public void clearAllIsNotUndoneByAPersistQueuedBeforeIt() {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistoryInDirectory("yes", LOG_DIR);

        ctrl.clearAllPerDirectory();
        idleDebounce();
        drain();

        assertFalse("the queued persist brought the cleared directory back",
            String.valueOf(stored(HOME)).contains("ls -la"));
        assertFalse("nor the other one", String.valueOf(stored(LOG_DIR)).contains("yes"));
    }

    @Test
    public void globalClearIsNotUndoneByAPersistQueuedBeforeIt() {
        ctrl.setPerDirectoryEnabled(false);
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistory("git status", HOME);

        ctrl.clearCurrent(HOME);
        idleDebounce();
        drain();

        assertEquals("[]", String.valueOf(globalStore()));
    }

    // After a clear-all the store has to be usable again

    @Test
    public void historyCanBeRecordedAgainAfterClearAll() throws Exception {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.save();
        ctrl.clearAllPerDirectory();

        ctrl.addToMessageHistory("pwd", HOME);
        ctrl.save();

        MessageHistoryController reloaded = newController();
        reloaded.load(HOME);
        assertEquals("[pwd]", String.valueOf(reloaded.getHistoryList()));
    }

    @Test
    public void clearAllAlsoDropsTheGlobalFallback() throws Exception {
        ctrl.setPerDirectoryEnabled(false);
        ctrl.addToMessageHistory("global cmd", HOME);
        ctrl.setPerDirectoryEnabled(true); // current list stays the global one
        ctrl.addToMessageHistoryInDirectory("dir cmd", LOG_DIR);

        ctrl.clearAllPerDirectory();
        ctrl.setPerDirectoryEnabled(false);
        assertTrue("global history is cleared too", ctrl.getHistoryList().isEmpty());

        ctrl.save();
        MessageHistoryController reloaded = newController();
        reloaded.load(HOME);
        assertTrue("the global fallback cannot resurrect a cleared history",
                reloaded.getHistoryList().isEmpty());
    }

    @Test
    public void historyCanBeRecordedAgainAfterClearingTheCurrentDirectory() throws Exception {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.save();
        ctrl.clearCurrent(HOME);

        ctrl.addToMessageHistory("pwd", HOME);
        ctrl.save();

        MessageHistoryController reloaded = newController();
        reloaded.load(HOME);
        assertEquals("[pwd]", String.valueOf(reloaded.getHistoryList()));
    }

    @Test
    public void spansAreParsedOnceAndReusedBySnapshots() {
        ctrl.addToMessageHistory("First sentence. Second one", HOME);
        assertTrue(ctrl.getSpansCache().containsKey("First sentence. Second one"));

        HistorySnapshot first = HistorySnapshot.build(ctrl.getHistoryVersion(),
                ctrl.getHistoryList(), ctrl.getSpansCache());
        HistorySnapshot.Spans spans = first.spans[0];
        HistorySnapshot second = HistorySnapshot.build(ctrl.getHistoryVersion(),
                ctrl.getHistoryList(), ctrl.getSpansCache());
        assertSame(spans, second.spans[0]);
    }

    @Test
    public void removingAnEntryDropsItsSpans() {
        ctrl.addToMessageHistory("a. b", HOME);
        assertTrue(ctrl.getSpansCache().containsKey("a. b"));
        ctrl.removeMessage("a. b");
        assertFalse(ctrl.getSpansCache().containsKey("a. b"));
    }

    @Test
    public void sentenceSpansStayLazyWhileSentenceSuggestionsAreOff() {
        ctrl.setSentenceIndexEnabled(false);
        ctrl.addToMessageHistory("First sentence. Second one", HOME);

        HistorySnapshot.Spans spans = ctrl.getSpansCache().get("First sentence. Second one");
        assertNotNull(spans);
        assertFalse(spans.hasSentences());

        int version = ctrl.getHistoryVersion();
        ctrl.setSentenceIndexEnabled(true);
        assertEquals("toggling the index mode must not change history",
                version, ctrl.getHistoryVersion());
        HistorySnapshot.Spans warmed = ctrl.getSpansCache().get("First sentence. Second one");
        assertNotNull(warmed);
        assertTrue(warmed.hasSentences());
    }

    @Test
    public void sentenceIndexPreferenceIsAppliedOnConstruction() throws Exception {
        prefs.edit().putBoolean("autocomplete_sentences_enabled", false).commit();
        MessageHistoryController off = newController();
        off.load(HOME);
        assertFalse(off.isSentenceIndexEnabled());

        off.addToMessageHistory("First sentence. Second one", HOME);
        HistorySnapshot.Spans spans = off.getSpansCache().get("First sentence. Second one");
        assertNotNull(spans);
        assertFalse(spans.hasSentences());
    }

    // Record identity

    @Test
    public void idsAreStableAcrossPromotionAndUniquePerDirectory() {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistory("git status", HOME);
        ctrl.addToMessageHistoryInDirectory("ls -la", LOG_DIR);

        long homeLs = idOfCurrent("ls -la");
        long homeGit = idOfCurrent("git status");
        assertTrue(homeLs > 0);
        assertTrue(homeGit > 0);
        assertNotEquals(homeLs, homeGit);

        ctrl.onHistoryDirectoryChanged(HOME, LOG_DIR);
        long logLs = idOfCurrent("ls -la");
        assertTrue(logLs > 0);
        assertNotEquals("same text in another directory is a separate record",
                homeLs, logLs);

        ctrl.onHistoryDirectoryChanged(LOG_DIR, HOME);
        ctrl.addToMessageHistory("ls -la", HOME); // promote
        assertEquals("promotion keeps the record id", homeLs, idOfCurrent("ls -la"));
    }

    @Test
    public void idsSurviveSaveAndReload() throws Exception {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistory("git status", HOME);
        long ls = idOfCurrent("ls -la");
        long git = idOfCurrent("git status");
        ctrl.save();

        MessageHistoryController reloaded = newController();
        reloaded.load(HOME);
        assertEquals(ls, idOf(reloaded, "ls -la"));
        assertEquals(git, idOf(reloaded, "git status"));
    }

    @Test
    public void legacyMigrationAssignsPositiveUniqueIds() throws Exception {
        String store = new JSONObject()
            .put(HOME, new JSONArray().put("ls").put("git"))
            .toString();
        prefs.edit().putString("message_history_per_directory", store).commit();

        MessageHistoryController reloaded = newController();
        reloaded.load(HOME);

        long first = -1;
        for (HistoryEntry entry : reloaded.getHistoryEntries()) {
            assertTrue(entry.id > 0);
            if (first < 0) {
                first = entry.id;
            } else {
                assertNotEquals(first, entry.id);
            }
        }
    }

    @Test
    public void dismissalFlagsPersistByRecordId() throws Exception {
        ctrl.addToMessageHistory("git status", HOME);
        ctrl.save();
        long id = idOfCurrent("git status");
        assertFalse(ctrl.getDismissals().isDismissed(id, 0));

        assertTrue(ctrl.getDismissals().dismiss(id, 0));
        assertTrue(ctrl.getDismissals().isDismissed(id, 0));

        MessageHistoryController reloaded = newController();
        reloaded.load(HOME);
        assertTrue(reloaded.getDismissals().isDismissed(id, 0));
    }

    @Test
    public void removedRecordDropsItsDismissalFlags() {
        ctrl.addToMessageHistory("git status", HOME);
        long id = idOfCurrent("git status");
        assertTrue(ctrl.getDismissals().dismiss(id, 0));

        assertTrue(ctrl.removeMessage("git status"));
        assertFalse(ctrl.getDismissals().isDismissed(id, 0));
    }

    @Test
    public void sentenceModelMismatchDiscardsStoredFlags() throws Exception {
        prefs.edit().putString("autocomplete_dismissed_suggestions_v2",
                "{\"v\":1,\"sentenceModel\":999,\"dismissed\":{\"1\":[0]}}").commit();

        MessageHistoryController reloaded = newController();
        reloaded.load(HOME);

        assertFalse(reloaded.getDismissals().isDismissed(1, 0));
        assertTrue("stale flags are removed", prefs.getString(
                "autocomplete_dismissed_suggestions_v2", null) == null);
    }

    @Test
    public void pendingWritesDrainIntoTheFirstRealDirectoryWithoutLosingIds() throws Exception {
        ctrl = newController();
        ctrl.load(".");
        assertEquals(".", ctrl.getHistoryCurrentDirectory());

        ctrl.addToMessageHistory("one", ".");
        long one = idOfCurrent("one");
        ctrl.addToMessageHistory("two", HOME); // first real cwd drains pending
        long two = idOfCurrent("two");
        ctrl.save();

        assertEquals("[two, one]", String.valueOf(ctrl.getHistoryList()));
        MessageHistoryController reloaded = newController();
        reloaded.load(HOME);
        assertEquals("[two, one]", String.valueOf(reloaded.getHistoryList()));
        assertEquals(one, idOf(reloaded, "one"));
        assertEquals(two, idOf(reloaded, "two"));
    }

    @Test
    public void switchingPerDirectoryModeMovesGlobalHistoryIntoPendingWithoutLoss() throws Exception {
        ctrl.setPerDirectoryEnabled(false);
        ctrl.load(HOME);
        ctrl.addToMessageHistory("global", HOME);
        ctrl.save();

        ctrl.setPerDirectoryEnabled(true);
        assertEquals(".", ctrl.getHistoryCurrentDirectory());
        assertEquals("[global]", String.valueOf(ctrl.getHistoryList()));

        ctrl.addToMessageHistory("home", HOME);
        ctrl.save();
        assertEquals("[home, global]", String.valueOf(ctrl.getHistoryList()));
        assertEquals("[\"home\",\"global\"]", String.valueOf(stored(HOME)));
    }

    @Test
    public void corruptV3DoesNotFallBackToStaleV1() throws Exception {
        prefs.edit()
                .putString("message_history", new JSONArray().put("stale").toString())
                .putString("message_history_v2", "{not-json")
                .commit();

        MessageHistoryController reloaded = newController();
        reloaded.load(HOME);

        assertTrue("corrupt v3 must start empty", reloaded.getHistoryList().isEmpty());
        assertEquals("{not-json", prefs.getString("message_history_v3_corrupt", null));
        assertTrue("the migration marker prevents a later v1 fallback",
                prefs.getBoolean("message_history_migrated_v3", false));
        assertNull("v1 is consumed after the v3 barrier",
                prefs.getString("message_history", null));
    }

    @Test
    public void validV2IsUpgradedWithoutReadingStaleV1() throws Exception {
        String v2 = new JSONObject()
                .put("v", 2)
                .put("nextId", 7)
                .put("global", new JSONArray())
                .put("dirs", new JSONObject().put(HOME,
                        new JSONArray().put(new JSONObject().put("id", 3).put("text", "v2"))))
                .toString();
        prefs.edit()
                .putString("message_history", new JSONArray().put("stale").toString())
                .putString("message_history_v2", v2)
                .commit();

        MessageHistoryController reloaded = newController();
        reloaded.load(HOME);

        assertEquals("[v2]", String.valueOf(reloaded.getHistoryList()));
        assertEquals(3, idOf(reloaded, "v2"));
        assertTrue("the v3 document has a fresh epoch",
                new JSONObject(prefs.getString("message_history_v2", null))
                        .getString("epoch").length() > 0);
        assertTrue(prefs.getBoolean("message_history_migrated_v3", false));
        assertNull(prefs.getString("message_history", null));
    }

    private long idOfCurrent(String text) {
        return idOf(ctrl, text);
    }

    private static long idOf(MessageHistoryController controller, String text) {
        for (HistoryEntry entry : controller.getHistoryEntries()) {
            if (entry.text.equals(text)) return entry.id;
        }
        return -1;
    }

    /** Let the debounce timer fire the way it does on a real looper. */
    private void idleDebounce() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** {@code save()} writes and drains the persist executor, so the bytes are on disk on return. */
    private void drain() {
        ctrl.save();
    }
}
