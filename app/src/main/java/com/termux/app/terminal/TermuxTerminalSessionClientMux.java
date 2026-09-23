package com.termux.app.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link TerminalSessionClient} that fans every callback out to one <em>primary</em> delegate
 * plus any number of <em>secondary</em> delegates.
 *
 * <p>A {@link TerminalSession} holds exactly ONE client — the only channel through which it
 * announces screen changes ({@link TerminalSession#notifyScreenUpdate()} →
 * {@link #onTextChanged}) — so a second surface (the floating bubble window) cannot replace it
 * without freezing the main activity's terminal, and replacing it back would freeze the bubble.
 *
 * <p>Installed once in {@link com.termux.app.TermuxService} and kept for the service's lifetime:
 * the service swaps the <em>primary</em> delegate on activity bind/unbind, secondaries come and
 * go with extra surfaces. Every fan-out is guarded — a throwing delegate is logged and the rest
 * still receive the callback.
 */
public final class TermuxTerminalSessionClientMux extends TermuxTerminalSessionClientBase {

    private static final String LOG_TAG = "TermuxTerminalSessionClientMux";

    @Nullable
    private volatile TermuxTerminalSessionClientBase mPrimary;

    private final CopyOnWriteArrayList<TerminalSessionClient> mSecondaries = new CopyOnWriteArrayList<>();

    /**
     * Set the primary delegate, i.e. the client that owns the "real" UI for the sessions.
     * Never {@code null} in practice: it is the service client while no activity is bound and the
     * activity client while one is.
     */
    public void setPrimary(@Nullable TermuxTerminalSessionClientBase primary) {
        mPrimary = primary;
    }

    @Nullable
    public TermuxTerminalSessionClientBase getPrimary() {
        return mPrimary;
    }

    /** Register an extra surface (e.g. the bubble window) that wants the session callbacks. */
    public void addSecondary(@NonNull TerminalSessionClient client) {
        mSecondaries.addIfAbsent(client);
    }

    /** Unregister an extra surface. Must be called before that surface is destroyed. */
    public void removeSecondary(@NonNull TerminalSessionClient client) {
        mSecondaries.remove(client);
    }

    public boolean hasSecondaries() {
        return !mSecondaries.isEmpty();
    }

    // ── TerminalSessionClient fan-out ──

    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        TermuxTerminalSessionClientBase primary = mPrimary;
        if (primary != null) guard(() -> primary.onTextChanged(changedSession), "onTextChanged");
        for (TerminalSessionClient client : mSecondaries) {
            guard(() -> client.onTextChanged(changedSession), "onTextChanged");
        }
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession updatedSession) {
        TermuxTerminalSessionClientBase primary = mPrimary;
        if (primary != null) guard(() -> primary.onTitleChanged(updatedSession), "onTitleChanged");
        for (TerminalSessionClient client : mSecondaries) {
            guard(() -> client.onTitleChanged(updatedSession), "onTitleChanged");
        }
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        TermuxTerminalSessionClientBase primary = mPrimary;
        if (primary != null) guard(() -> primary.onSessionFinished(finishedSession), "onSessionFinished");
        for (TerminalSessionClient client : mSecondaries) {
            guard(() -> client.onSessionFinished(finishedSession), "onSessionFinished");
        }
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        TermuxTerminalSessionClientBase primary = mPrimary;
        if (primary != null) guard(() -> primary.onCopyTextToClipboard(session, text), "onCopyTextToClipboard");
        for (TerminalSessionClient client : mSecondaries) {
            guard(() -> client.onCopyTextToClipboard(session, text), "onCopyTextToClipboard");
        }
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        TermuxTerminalSessionClientBase primary = mPrimary;
        if (primary != null) guard(() -> primary.onPasteTextFromClipboard(session), "onPasteTextFromClipboard");
        for (TerminalSessionClient client : mSecondaries) {
            guard(() -> client.onPasteTextFromClipboard(session), "onPasteTextFromClipboard");
        }
    }

    @Override
    public void onBell(@NonNull TerminalSession session) {
        TermuxTerminalSessionClientBase primary = mPrimary;
        if (primary != null) guard(() -> primary.onBell(session), "onBell");
        for (TerminalSessionClient client : mSecondaries) {
            guard(() -> client.onBell(session), "onBell");
        }
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession session) {
        TermuxTerminalSessionClientBase primary = mPrimary;
        if (primary != null) guard(() -> primary.onColorsChanged(session), "onColorsChanged");
        for (TerminalSessionClient client : mSecondaries) {
            guard(() -> client.onColorsChanged(session), "onColorsChanged");
        }
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
        TermuxTerminalSessionClientBase primary = mPrimary;
        if (primary != null) guard(() -> primary.onTerminalCursorStateChange(state), "onTerminalCursorStateChange");
        for (TerminalSessionClient client : mSecondaries) {
            guard(() -> client.onTerminalCursorStateChange(state), "onTerminalCursorStateChange");
        }
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession session, int pid) {
        TermuxTerminalSessionClientBase primary = mPrimary;
        if (primary != null) guard(() -> primary.setTerminalShellPid(session, pid), "setTerminalShellPid");
        for (TerminalSessionClient client : mSecondaries) {
            guard(() -> client.setTerminalShellPid(session, pid), "setTerminalShellPid");
        }
    }

    /**
     * Unlike every other callback this one returns a value, so it cannot be fanned out: the
     * primary delegate wins, and secondaries are only consulted if it has no opinion (the bubble
     * window does not set a cursor style of its own).
     */
    @Override
    public Integer getTerminalCursorStyle() {
        TermuxTerminalSessionClientBase primary = mPrimary;
        if (primary != null) {
            Integer style = primary.getTerminalCursorStyle();
            if (style != null) return style;
        }
        for (TerminalSessionClient client : mSecondaries) {
            Integer style = client.getTerminalCursorStyle();
            if (style != null) return style;
        }
        return null;
    }

    // ── Logging: only the primary logs, so the log is not duplicated per surface ──

    @Override
    public void logError(String tag, String message) {
        TermuxTerminalSessionClientBase primary = mPrimary;
        if (primary != null) primary.logError(tag, message);
        else super.logError(tag, message);
    }

    @Override
    public void logWarn(String tag, String message) {
        TermuxTerminalSessionClientBase primary = mPrimary;
        if (primary != null) primary.logWarn(tag, message);
        else super.logWarn(tag, message);
    }

    @Override
    public void logInfo(String tag, String message) {
        TermuxTerminalSessionClientBase primary = mPrimary;
        if (primary != null) primary.logInfo(tag, message);
        else super.logInfo(tag, message);
    }

    @Override
    public void logDebug(String tag, String message) {
        TermuxTerminalSessionClientBase primary = mPrimary;
        if (primary != null) primary.logDebug(tag, message);
        else super.logDebug(tag, message);
    }

    @Override
    public void logVerbose(String tag, String message) {
        TermuxTerminalSessionClientBase primary = mPrimary;
        if (primary != null) primary.logVerbose(tag, message);
        else super.logVerbose(tag, message);
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        TermuxTerminalSessionClientBase primary = mPrimary;
        if (primary != null) primary.logStackTraceWithMessage(tag, message, e);
        else super.logStackTraceWithMessage(tag, message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        TermuxTerminalSessionClientBase primary = mPrimary;
        if (primary != null) primary.logStackTrace(tag, e);
        else super.logStackTrace(tag, e);
    }

    /** Run one fan-out step, isolating a broken delegate from the rest. */
    private static void guard(@NonNull Runnable action, @NonNull String callback) {
        try {
            action.run();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "A delegate threw in " + callback, e);
        }
    }
}
