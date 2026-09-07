package com.termux.app.terminal;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxActivityUtils;
import com.termux.app.TermuxInstaller;
import com.termux.app.TermuxService;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Owns the {@link TermuxService} {@link ServiceConnection} lifecycle for {@link TermuxActivity}.
 * <p/>
 * The connection used to live inside {@link TermuxActivity} (which implemented {@link ServiceConnection}
 * directly): the field, the {@code bindService}/{@code unbindService} plumbing, the cold-start
 * bootstrap and the session-restore logic all sat there. That responsibility is now centralised here
 * so the activity no longer implements {@link ServiceConnection} and the binding bookkeeping is
 * reusable/testable in isolation.
 * <p/>
 * The manager holds the bound {@link TermuxService} and, once connected, drives the activity through
 * its public API (see {@link TermuxActivity#setTermuxSessionsListView()},
 * {@link TermuxActivity#restoreSessionSnapshot()}, {@link TermuxActivity#syncTerminalPagerToService()},
 * {@link TermuxActivity#setColdStartSessionPending(boolean)}, etc.).
 */
public class TermuxServiceConnectionManager implements ServiceConnection {

    private static final String LOG_TAG = "TermuxServiceConnectionManager";

    /** The {@link TermuxActivity} that owns this connection manager. */
    private final TermuxActivity mActivity;

    /**
     * The connection to the {@link TermuxService}. Requested via
     * {@link #startAndBindService()} and obtained/stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    @Nullable
    private TermuxService mTermuxService;

    public TermuxServiceConnectionManager(@NonNull TermuxActivity activity) {
        mActivity = activity;
    }

    /**
     * @return the bound {@link TermuxService}, or {@code null} if the service has not yet connected
     * or has been unbound.
     */
    @Nullable
    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    /**
     * @return true if the {@link TermuxService} is currently bound.
     */
    public boolean isServiceBound() {
        return mTermuxService != null;
    }

    /**
     * Start the {@link TermuxService} and bind to it. Mirrors the original
     * {@link TermuxActivity#onCreate(Bundle)} logic: the service is started so it keeps running
     * regardless of who is bound, then bound so {@link #onServiceConnected(ComponentName, IBinder)}
     * fires.
     *
     * @return true on success, false if the service could not be started/bound (the caller should
     * mark the activity as invalid and stop).
     */
    public boolean startAndBindService() {
        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            Intent serviceIntent = new Intent(mActivity, TermuxService.class);
            mActivity.startService(serviceIntent);

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!mActivity.bindService(serviceIntent, this, 0))
                throw new RuntimeException(mActivity.getString(com.termux.R.string.error_bind_service));
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "TermuxActivity failed to start TermuxService", e);
            Logger.showToast(mActivity,
                mActivity.getString(e.getMessage() != null && e.getMessage().contains("app is in background") ?
                    R.string.error_termux_service_start_failed_bg : R.string.error_termux_service_start_failed_general),
                true);
            return false;
        }
    }

    /**
     * Unbind from the {@link TermuxService} and clear the reference. Mirrors the original
     * {@link TermuxActivity#onDestroy()} logic: releases the session client so the service no longer
     * holds an activity reference, then unbinds (best-effort).
     */
    public void unbindService() {
        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity.
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }

        try {
            mActivity.unbindService(this);
        } catch (Exception e) {
            // ignore.
        }
    }

    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #startAndBindService()} which will cause a call to this callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;

        mActivity.setTermuxSessionsListView();

        final Intent intent = mActivity.getIntent();
        mActivity.setIntent(null);

        // After a data restore, close all stale sessions and open a fresh one so the user
        // is not left looking at a terminal whose shell/config no longer matches the container.
        boolean resetSessions = intent != null
            && intent.getBooleanExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_RESET_SESSIONS, false);
        if (resetSessions && mTermuxService != null) {
            mTermuxService.removeAllTermuxSessions();
        }

        if (mTermuxService.isTermuxSessionsEmpty()) {
            // Check if bootstrap is installed; if not, show the selector activity.
            if (!TermuxInstaller.isBootstrapInstalled(mActivity)) {
                if (mActivity.isVisible()) {
                    TermuxInstaller.cleanupInterruptedInstall();
                    Intent selectorIntent = new Intent(mActivity, com.termux.installer.BootstrapSelectorActivity.class);
                    mActivity.startActivityForResult(selectorIntent, TermuxActivity.REQUEST_BOOTSTRAP_SETUP);
                } else {
                    TermuxActivityUtils.finishActivityIfNotFinishing(mActivity);
                }
                return;
            }
            if (mActivity.isVisible()) {
                TermuxInstaller.setupBootstrapIfNeeded(mActivity, () -> {
                    if (mTermuxService == null) return; // Activity might have been destroyed.
                    try {
                        boolean launchFailsafe = false;
                        if (intent != null && intent.getExtras() != null) {
                            launchFailsafe = intent.getExtras().getBoolean(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                        }
                        // Reopen the tabs from the last session if the feature is on and a
                        // snapshot exists; otherwise fall back to a single fresh session.
                        if (!launchFailsafe && mActivity.restoreSessionSnapshot()) {
                            // Re-key persisted per-session UI state onto the freshly
                            // restored sessions (process-death restore path). Must run
                            // AFTER the sessions exist and BEFORE the pager sync.
                            mActivity.restorePersistedUiState();
                            // Sessions restored from snapshot, but their emulators are
                            // not yet initialized (no JNI.createSubprocess / fork has
                            // run).  The immediate syncTerminalPagerToService() below
                            // would trigger fork() for every restored session on the UI
                            // thread during the first layout pass, causing the visible
                            // stutter the user reported.
                            // Instead, initialize all restored sessions on a background
                            // thread and defer the pager sync until they are ready.
                            if (!mActivity.isColdStartSessionPending()) {
                                List<TermuxSession> restoredSessions = mTermuxService.getTermuxSessions();
                                if (!restoredSessions.isEmpty()) {
                                    mActivity.setColdStartSessionPending(true);
                                    final TermuxActivity activity = mActivity;
                                    new Thread(() -> {
                                        android.os.Process.setThreadPriority(
                                            android.os.Process.THREAD_PRIORITY_DEFAULT);
                                        for (int i = 0; i < restoredSessions.size(); i++) {
                                            TerminalSession ts = restoredSessions.get(i)
                                                .getTerminalSession();
                                            if (ts != null && ts.getEmulator() == null) {
                                                ts.updateSize(80, 24, 10, 10);
                                            }
                                        }
                                        activity.runOnUiThread(() -> {
                                            if (activity.isFinishing()) return;
                                            mActivity.setColdStartSessionPending(false);
                                            mActivity.syncTerminalPagerToService();
                                        });
                                    }).start();
                                }
                            }
                            return;
                        }
                        mActivity.getTermuxTerminalSessionClient().addNewSession(launchFailsafe, null);
                    } catch (android.view.WindowManager.BadTokenException e) {
                        // Activity finished - ignore.
                    }
                });
            } else {
                // The service connected while not in foreground - just bail out.
                TermuxActivityUtils.finishActivityIfNotFinishing(mActivity);
            }
        } else {
            // If termux was started from launcher "New session" shortcut and activity is recreated,
            // then the original intent will be re-delivered, resulting in a new session being re-added
            // each time.
            if (!mActivity.isActivityRecreated() && intent != null && Intent.ACTION_RUN.equals(intent.getAction())) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                boolean isFailSafe = intent.getBooleanExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                mActivity.getTermuxTerminalSessionClient().addNewSession(isFailSafe, null);
            } else {
                mActivity.getTermuxTerminalSessionClient().setCurrentSession(mActivity.getTermuxTerminalSessionClient().getCurrentStoredSessionOrLast());
            }
        }

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService.setTermuxTerminalSessionClient(mActivity.getTermuxTerminalSessionClient());

        // Re-apply terminal fonts/colors now that the session is bound. This is required when the
        // activity was recreated (e.g. on a system day/night theme change) while the session was
        // not yet attached, so checkForFontAndColors() called earlier had no session to repaint.
        if (mActivity.getTermuxTerminalSessionClient() != null)
            mActivity.getTermuxTerminalSessionClient().checkForFontAndColors();

        // Populate the horizontal pager with the now-available sessions and select the initial
        // page (honouring a pending session requested before the adapter had items, otherwise the
        // stored/last session). Safe to call even if sessions were added asynchronously above —
        // it is a no-op when the list is still empty.
        mActivity.syncTerminalPagerToService();

        // Populate the tab strip with existing sessions after activity recreate (theme change
        // or back-finish + reopen). onStart() ran before the service connected and skipped
        // termuxSessionListNotifyUpdated() because getTermuxService() was null, so the
        // freshly-created tabs controller still has only the (+) button. Without this call
        // the tabs stay empty until a new session is added via the (+) button.
        if (mTermuxService != null && !mTermuxService.isTermuxSessionsEmpty()) {
            mActivity.termuxSessionListNotifyUpdated(-1);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        TermuxActivityUtils.finishActivityIfNotFinishing(mActivity);
    }
}
