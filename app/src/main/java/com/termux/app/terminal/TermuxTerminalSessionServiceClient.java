package com.termux.app.terminal;

import android.app.Service;

import androidx.annotation.NonNull;

import com.termux.app.TermuxService;
import com.termux.app.notification.TermuxTerminalNotificationDispatcher;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalNotification;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

/** The {@link TerminalSessionClient} implementation that may require a {@link Service} for its interface methods. */
public class TermuxTerminalSessionServiceClient extends TermuxTerminalSessionClientBase {

    private final TermuxService mService;

    public TermuxTerminalSessionServiceClient(TermuxService service) {
        this.mService = service;
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession terminalSession, int pid) {
        TermuxSession termuxSession = mService.getTermuxSessionForTerminalSession(terminalSession);
        if (termuxSession != null)
            termuxSession.getExecutionCommand().mPid = pid;
    }

    /**
     * Post a notification a terminal program asked for (kitty OSC 99). Here rather than on the
     * activity client because this is the mux's primary: it lives as long as the service, so a
     * notification still arrives when Termux is backgrounded with no activity bound.
     */
    @Override
    public void onNotification(@NonNull TerminalSession terminalSession, @NonNull TerminalNotification notification) {
        TermuxTerminalNotificationDispatcher.post(mService, terminalSession, notification);
    }

}
