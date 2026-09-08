package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.terminal.TerminalSession;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Persists and restores the set of open terminal tabs (working directory, name,
 * failsafe flag) plus the active tab index across app restarts, so a cold start
 * (e.g. after the {@code TermuxService} was killed) can reopen the same tabs.
 * <p>
 * The snapshot is stored in {@code termux_prefs} as a JSON string. Saving happens
 * on {@code onStop} (and whenever sessions are alive) and restoring on
 * {@code onServiceConnected} when the service has no live sessions.
 */
public class TermuxSessionSnapshotManager {

    private static final String LOG_TAG = "TermuxSessionSnapshotManager";

    private static final String PREF_RESTORE_SESSIONS = "restore_sessions";
    private static final String PREF_SESSION_SNAPSHOT = "session_snapshot";

    /** Owns the service binding, current session and properties we read from. */
    private final TermuxActivity mActivity;

    /**
     * Last JSON string this manager actually handed to {@link SharedPreferences}.
     * <p>
     * {@code SharedPreferences.Editor.apply()} always serialises the WHOLE preferences file,
     * even when the value is identical, and on API 28+ {@code QueuedWork.waitToFinish()} blocks
     * the UI thread in {@code onStop} until every queued write hits the disk. Remembering the
     * last written value turns the common "background with nothing changed" case into a no-op
     * (and it also skips the N {@code /proc/<pid>/cwd} readlinks that building the JSON costs).
     */
    private String mLastWrittenSnapshotJson;

    public TermuxSessionSnapshotManager(final TermuxActivity activity) {
        mActivity = activity;
    }

    /**
     * Get the {@code termux_prefs} SharedPreferences. The manager only ever
     * reads/writes the snapshot-related key and the restore toggle.
     */
    private SharedPreferences getPrefs() {
        return mActivity.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
    }

    /** Whether restoring open tabs on launch is enabled (default: on). */
    public boolean isRestoreSessionsEnabled() {
        return getPrefs().getBoolean(PREF_RESTORE_SESSIONS, true);
    }

    /**
     * Snapshot the currently open tabs (working directory, name, failsafe flag)
     * plus the index of the active tab, and persist it as JSON. Called on onStop
     * so a later cold start (service killed) can reopen the same tabs. When the
     * feature is off, the stored snapshot is cleared instead.
     */
    public void saveSessionSnapshot() {
        final SharedPreferences prefs = getPrefs();
        if (!isRestoreSessionsEnabled()) {
            // Only clear once: repeated removes would rewrite the file for nothing.
            if (mLastWrittenSnapshotJson != null || prefs.contains(PREF_SESSION_SNAPSHOT)) {
                prefs.edit().remove(PREF_SESSION_SNAPSHOT).apply();
                mLastWrittenSnapshotJson = null;
            }
            return;
        }
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        // While the service is shutting down (e.g. the notification Exit action
        // kills sessions one-by-one, each firing termuxSessionListNotifyUpdated
        // which calls this method) skip saving so the last full snapshot (taken
        // while all tabs were alive) is preserved instead of being shrunk to the
        // last surviving tab.
        if (service.isWantsToStop()) return;
        List<TermuxSession> sessions = service.getTermuxSessions();
        // An empty list means the sessions were already killed (e.g. the Exit
        // notification action fires before onStop). Do NOT wipe the snapshot in
        // that case: keep the last non-empty snapshot so it can be restored.
        if (sessions == null || sessions.isEmpty()) return;

        TerminalSession current = mActivity.getCurrentSession();
        JSONArray tabs = new JSONArray();
        int activeIndex = 0;
        for (int i = 0; i < sessions.size(); i++) {
            TermuxSession ts = sessions.get(i);
            TerminalSession terminal = ts.getTerminalSession();
            ExecutionCommand cmd = ts.getExecutionCommand();
            try {
                JSONObject tab = new JSONObject();
                String cwd = terminal != null ? terminal.getCwd() : null;
                if (TextUtils.isEmpty(cwd) && cmd != null) cwd = cmd.workingDirectory;
                tab.put("cwd", cwd == null ? "" : cwd);
                tab.put("name", cmd != null && cmd.shellName != null ? cmd.shellName : "");
                tab.put("failsafe", cmd != null && cmd.isFailsafe);
                tabs.put(tab);
            } catch (JSONException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to snapshot session", e);
            }
            if (current != null && terminal == current) activeIndex = i;
        }

        try {
            JSONObject snapshot = new JSONObject();
            snapshot.put("tabs", tabs);
            snapshot.put("active", activeIndex);
            final String json = snapshot.toString();
            // Skip the write when the snapshot is byte-identical to what is already on disk:
            // apply() would still serialise the entire termux_prefs file for nothing.
            if (json.equals(mLastWrittenSnapshotJson)) return;
            prefs.edit().putString(PREF_SESSION_SNAPSHOT, json).apply();
            mLastWrittenSnapshotJson = json;
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to persist session snapshot", e);
        }
    }

    /**
     * Forget the "already written" memo so the next {@link #saveSessionSnapshot()} really writes.
     * Called after a restore, so a rebuilt session list is never skipped as unchanged, and on
     * activity (re)creation, where the in-memory memo must not outlive the object it describes.
     */
    public void invalidateWrittenSnapshotMemo() {
        mLastWrittenSnapshotJson = null;
    }

    /**
     * Reopen the tabs saved by {@link #saveSessionSnapshot()} in the same order,
     * directories and names, then select the previously-active tab. Returns true
     * if at least one tab was restored. Called from onServiceConnected only when
     * the service has no live sessions (i.e. a genuine cold start).
     */
    public boolean restoreSessionSnapshot() {
        if (!isRestoreSessionsEnabled()) return false;
        String json = getPrefs().getString(PREF_SESSION_SNAPSHOT, null);
        if (TextUtils.isEmpty(json)) return false;

        TermuxService service = mActivity.getTermuxService();
        if (service == null) return false;

        // Sessions are about to be rebuilt from the snapshot — the memo now describes a session
        // list that no longer exists, so the next save must really write.
        invalidateWrittenSnapshotMemo();

        TermuxAppSharedProperties properties = mActivity.getProperties();
        TermuxTerminalSessionActivityClient sessionClient = mActivity.getTermuxTerminalSessionClient();

        int active = 0;
        int restored = 0;
        try {
            JSONObject snapshot = new JSONObject(json);
            active = snapshot.optInt("active", 0);
            JSONArray tabs = snapshot.optJSONArray("tabs");
            if (tabs == null || tabs.length() == 0) return false;
            for (int i = 0; i < tabs.length(); i++) {
                JSONObject tab = tabs.optJSONObject(i);
                if (tab == null) continue;
                String cwd = tab.optString("cwd", "");
                String name = tab.optString("name", "");
                boolean failsafe = tab.optBoolean("failsafe", false);
                String workingDirectory = TextUtils.isEmpty(cwd)
                        ? properties.getDefaultWorkingDirectory() : cwd;
                workingDirectory = TermuxShellEnvironment.sanitizeWorkingDirectory(mActivity, workingDirectory);
                TermuxSession session = service.createTermuxSession(null, null, null,
                        workingDirectory, failsafe, TextUtils.isEmpty(name) ? null : name);
                if (session == null) continue;
                restored++;
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to restore session snapshot", e);
            return false;
        }

        if (restored == 0) return false;

        List<TermuxSession> sessions = service.getTermuxSessions();
        if (sessions != null && !sessions.isEmpty()) {
            int idx = Math.max(0, Math.min(active, sessions.size() - 1));
            TerminalSession session = sessions.get(idx).getTerminalSession();
            sessionClient.setCurrentSession(session);
            // Persist the handle so that getCurrentStoredSessionOrLast() (called
            // from a subsequent syncTerminalPagerToService() on the background-init
            // thread) finds it instead of falling back to getLastTermuxSession()
            // (the rightmost tab). Without this, a cold-start restore would first
            // select the correct tab via setCurrentSession -> mPendingInitialSession,
            // but after that flag is consumed by the pager sync in
            // termuxSessionListNotifyUpdated(), the deferred background-thread
            // syncTerminalPagerToService() would override the selection with the
            // last tab.
            mActivity.getPreferences().setCurrentSession(session.mHandle);
        }
        return true;
    }
}
