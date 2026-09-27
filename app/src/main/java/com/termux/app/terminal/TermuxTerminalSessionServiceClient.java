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
     * Post a notification a program in the terminal asked for (kitty OSC 99).
     *
     * <p>Implemented here rather than on the activity client because this client is the mux's
     * primary: it exists for the whole lifetime of the service, so a notification still arrives when
     * the user has Termux in the background and no activity is bound. The service context is all
     * that is needed to post.
     */
    @Override
    public void onNotification(@NonNull TerminalSession terminalSession, @NonNull TerminalNotification notification) {
        TermuxTerminalNotificationDispatcher.post(mService, terminalSession, notification);
    }

}
