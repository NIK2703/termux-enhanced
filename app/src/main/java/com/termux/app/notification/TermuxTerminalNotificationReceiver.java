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
 * Reports a click on a notification a terminal program asked for back to that program, using the
 * {@code report} action of kitty OSC 99. Not exported and has no intent-filter: it is only ever
 * built with an explicit intent, the same shape as {@code TermuxBubbleReceiver}.
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
            // The terminal died between posting and tap: no pty left to report to.
            Logger.logDebug(LOG_TAG, "No live session " + sessionHandle + " for activation report");
            TermuxTerminalNotificationDispatcher.dismiss(context, sessionHandle, protocolId);
            return;
        }

        session.write(TerminalNotification.buildActivationReport(protocolId));
        Logger.logDebug(LOG_TAG, "Reported activation of " + protocolId + " to session " + sessionHandle);
        TermuxTerminalNotificationDispatcher.dismiss(context, sessionHandle, protocolId);
    }

    /**
     * Resolve a session handle against the live sessions, which {@code TermuxShellManager} owns and
     * a {@code BroadcastReceiver} can only reach statically; a null manager means none ever started.
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
