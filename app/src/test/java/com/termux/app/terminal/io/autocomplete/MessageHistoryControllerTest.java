package com.termux.app.terminal.io.autocomplete;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * Per-directory history: what a write from outside the foreground window does to the directory the
 * user is actually in.
 *
 * <p>Two callers reach the controller with a directory that is not the one on screen — a
 * notification reply filed for a background session, and the panel's own send after a {@code cd} the
 * session-change callback has not been told about. Both must leave the foreground directory's
 * entries alone.
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

    /** The controller an activity gets, configured from the prefs the way the activity configures it. */
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

    private JSONArray stored(String dir) {
        try {
            String json = prefs.getString("message_history_per_directory", null);
            if (json == null) return new JSONArray();
            return new JSONObject(json).optJSONArray(dir);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private JSONArray globalStore() {
        try {
            return new JSONArray(prefs.getString("message_history", "[]"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // -------------------------------------------------------------------
    //  A reply for a background session
    // -------------------------------------------------------------------

    @Test
    public void replyIntoAnotherDirectoryKeepsTheForegroundOnesHistory() {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistory("git status", HOME);
        ctrl.save();

        // A notification reply filed for a BACKGROUND session in another directory.
        ctrl.addToMessageHistoryInDirectory("yes", LOG_DIR);
        ctrl.save();

        // The user then sends from the session in front of them.
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

    /** A second reply to the same background session appends rather than replacing. */
    @Test
    public void repeatedRepliesToABackgroundSessionAccumulate() {
        ctrl.addToMessageHistoryInDirectory("yes", LOG_DIR);
        ctrl.addToMessageHistoryInDirectory("no", LOG_DIR);
        ctrl.addToMessageHistoryInDirectory("yes", LOG_DIR);
        ctrl.save();

        assertEquals("[\"yes\",\"no\"]", String.valueOf(stored(LOG_DIR)));
    }

    /** The passive path (a cleared field) after a reply is the same path and must be safe too. */
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

    /**
     * Not notification-specific: the panel's own send does the same after a {@code cd} the
     * session-change callback never reported. The reply is just the most visible way in.
     */
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

        // The user cds back to /etc and sends one command.
        ctrl.addToMessageHistory("df -h", "/etc");
        ctrl.save();

        assertEquals("[\"df -h\",\"uptime\"]", String.valueOf(stored("/etc")));
        assertEquals("[\"git status\",\"ls -la\"]", String.valueOf(stored(HOME)));
    }

    // -------------------------------------------------------------------
    //  A reply that cold-starts the process
    // -------------------------------------------------------------------

    @Test
    public void coldStartReplyGoesToTheStoreTheUserIsUsing() throws Exception {
        prefs.edit()
            .putBoolean("per_directory_message_history", true)
            .putInt("message_history_max", 50)
            .commit();
        // What the user has on disk: a populated per-directory store, global store absent.
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistory("git status", HOME);
        ctrl.save();
        assertTrue("precondition: no global store", prefs.getString("message_history", null) == null);

        // The broadcast started the process: no activity, nothing to configure the controller.
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

        // The broadcast started the process: a write must not fall back to the built-in default.
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

        // One list, as the user configured: the reply belongs in the same store.
        assertEquals("[\"yes\",\"ls -la\"]", String.valueOf(globalStore()));
        assertEquals("per-directory store must stay absent", "null",
            prefs.getString("message_history_per_directory", "null"));
    }

    // -------------------------------------------------------------------
    //  Switching the per-directory mode
    // -------------------------------------------------------------------

    /**
     * Turning per-directory mode off and on again must not lose anything. The activity does exactly
     * this: {@code save()}, flip the flag, {@code load()}.
     */
    @Test
    public void turningPerDirectoryModeOffAndOnKeepsEveryDirectory() throws Exception {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.addToMessageHistoryInDirectory("yes", LOG_DIR);
        ctrl.save();

        // Off.
        ctrl.save();
        ctrl.setPerDirectoryEnabled(false);
        ctrl.load(HOME);

        // On again.
        ctrl.save();
        ctrl.setPerDirectoryEnabled(true);
        ctrl.load(HOME);

        assertEquals("[\"ls -la\"]", String.valueOf(stored(HOME)));
        assertEquals("[\"yes\"]", String.valueOf(stored(LOG_DIR)));
    }

    /**
     * Turning the mode off and on again is lossless, which is the invariant that matters: the
     * per-directory store is left on disk, so the entries come back with the mode.
     */
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

    // -------------------------------------------------------------------
    //  Guards on the fix itself
    // -------------------------------------------------------------------

    /** A reply for the session the user IS looking at takes the ordinary path. */
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

    // -------------------------------------------------------------------
    //  Clearing against a queued persist
    // -------------------------------------------------------------------

    /**
     * A mutation arms a debounced persist that carries a snapshot of the list as it was at that
     * moment. A clear that follows inside the debounce window must not be undone when it lands.
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

    /** The same, for the global store: the queued write must not resurrect a global clear. */
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

    // -------------------------------------------------------------------
    //  After a clear-all the store has to be usable again
    // -------------------------------------------------------------------

    @Test
    public void historyCanBeRecordedAgainAfterClearAll() throws Exception {
        ctrl.addToMessageHistory("ls -la", HOME);
        ctrl.save();
        ctrl.clearAllPerDirectory();

        // The user keeps working: the next command must land somewhere and survive a reload.
        ctrl.addToMessageHistory("pwd", HOME);
        ctrl.save();

        MessageHistoryController reloaded = newController();
        reloaded.load(HOME);
        assertEquals("[pwd]", String.valueOf(reloaded.getHistoryList()));
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

    /** Let the debounce timer fire the way it does on a real looper. */
    private void idleDebounce() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** Wait for the persist executor's write to reach the prefs. */
    private void drain() {
        ctrl.save();
    }
}
