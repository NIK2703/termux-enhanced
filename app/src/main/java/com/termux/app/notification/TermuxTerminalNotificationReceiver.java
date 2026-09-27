package com.termux.app.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalNotification;
import com.termux.terminal.TerminalSession;

/**
 * Receives the activation of a notification that a program in a terminal asked for, and reports the
 * click back to that program.
 *
 * <p>This is the {@code report} action of the kitty OSC 99 protocol. Only built when a notification
 * asks for it, and the intent is explicit, so the receiver is not exported and needs no
 * intent-filter — the same shape as {@code TermuxBubbleReceiver}.
 */
public class TermuxTerminalNotificationReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "TermuxTerminalNotificationReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!TermuxConstants.ACTION_TERMINAL_NOTIFICATION_ACTIVATED.equals(intent.getAction())) return;

        String protocolId = intent.getStringExtra(TermuxConstants.EXTRA_TERMINAL_NOTIFICATION_ID);
        String sessionHandle = intent.getStringExtra(TermuxConstants.EXTRA_TERMINAL_SESSION_HANDLE);
        if (protocolId == null || sessionHandle == null) {
            Logger.logWarn(LOG_TAG, "Ignoring activation broadcast with missing extras");
            return;
        }

        TerminalSession session = findSession(sessionHandle);
        if (session == null) {
            // The terminal died between posting the notification and the user tapping it. There is
            // no longer a pty to write the report to, and the notification is stale anyway.
            Logger.logDebug(LOG_TAG, "No live session " + sessionHandle + " for activation report");
            TermuxTerminalNotificationDispatcher.dismiss(context, sessionHandle, protocolId);
            return;
        }

        session.write(TerminalNotification.buildActivationReport(protocolId));
        Logger.logDebug(LOG_TAG, "Reported activation of " + protocolId + " to session " + sessionHandle);
        TermuxTerminalNotificationDispatcher.dismiss(context, sessionHandle, protocolId);
    }

    /**
     * Resolve a session handle against the live sessions.
     *
     * <p>{@code TermuxShellManager} is the owner of the session list and is reachable statically,
     * which a {@code BroadcastReceiver} needs: it has no bound service. A null manager means no
     * session ever started, so the null return is a normal answer rather than an error.
     */
    @Nullable
    private static TerminalSession findSession(@NonNull String sessionHandle) {
        TermuxShellManager shellManager = TermuxShellManager.getShellManager();
        if (shellManager == null) return null;
        for (TermuxSession termuxSession : shellManager.mTermuxSessions) {
            TerminalSession session = termuxSession.getTerminalSession();
            if (session != null && session.mHandle.equals(sessionHandle)) return session;
        }
        return null;
    }
}
