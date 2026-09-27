package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Callbacks from {@link TerminalSession} to its client (text/title changes, logs, etc.). */
public interface TerminalSessionClient {

    void onTextChanged(@NonNull TerminalSession changedSession);

    void onTitleChanged(@NonNull TerminalSession changedSession);

    void onSessionFinished(@NonNull TerminalSession finishedSession);

    void onCopyTextToClipboard(@NonNull TerminalSession session, String text);

    void onPasteTextFromClipboard(@Nullable TerminalSession session);

    void onBell(@NonNull TerminalSession session);

    /**
     * A program running in {@code session} requested a desktop notification (kitty OSC 99).
     *
     * <p>Unlike most callbacks here this has a side effect outside the terminal (a notification is
     * posted), so an implementation must not be fanned out to several delegates — see
     * {@code TermuxTerminalSessionClientMux}.
     */
    void onNotification(@NonNull TerminalSession session, @NonNull TerminalNotification notification);

    void onColorsChanged(@NonNull TerminalSession session);

    void onTerminalCursorStateChange(boolean state);

    void setTerminalShellPid(@NonNull TerminalSession session, int pid);

    Integer getTerminalCursorStyle();

    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

}
