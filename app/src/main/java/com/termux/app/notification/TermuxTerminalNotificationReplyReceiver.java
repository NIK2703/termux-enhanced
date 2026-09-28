package com.termux.app.notification;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.terminal.io.autocomplete.MessageHistoryController;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

/**
 * Handles the inline reply of a notification a program in a terminal asked for (kitty OSC 99).
 *
 * <p>A replica of the input panel's send path, so a reply is indistinguishable from one typed in the
 * panel: the text is filed in the history under <em>the target session's own</em> working directory
 * rather than the foregrounded window's, and a carriage return always follows — the one divergence
 * from the panel, which honours an "Append Enter on send" preference, because an answer that is
 * never submitted is indistinguishable from no answer.
 *
 * <p>Ordered so the answer never waits on bookkeeping: the pty write, then the card, then the
 * history. The card is cancelled rather than left to the platform, which would append the typed text
 * to it, leave the thread on screen, and keep the reply's progress indicator spinning.
 *
 * <p>Explicit intents only, so this stays unexported and needs no intent-filter.
 */
public class TermuxTerminalNotificationReplyReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "TermuxTerminalNotificationReplyReceiver";

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /**
     * Fallback keys for the typed text, tried after the RemoteInput's own label. Spelled out as
     * literals because the android.jar this compiles against declares neither field, and neither is
     * the key this platform uses — they stay only for a ROM that keys the old way.
     */
    private static final String[] FALLBACK_REPLY_TEXT_KEYS = {
        "android.text",              // InputConnection.EXTRA_TEXT
        "android.intent.extra.TEXT",  // Intent.EXTRA_TEXT
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!TermuxConstants.ACTION_TERMINAL_NOTIFICATION_REPLIED.equals(intent.getAction())) return;

        String sessionHandle = intent.getStringExtra(TermuxConstants.EXTRA_TERMINAL_SESSION_HANDLE);
        if (sessionHandle == null) {
            Logger.logWarn(LOG_TAG, "Ignoring reply with no session handle");
            return;
        }
        // The progress indicator lives on the notification until it is cancelled, so a failed reply
        // needs this number to come off screen.
        int postedId = intent.getIntExtra(TermuxConstants.EXTRA_TERMINAL_NOTIFICATION_NUMBER, -1);

        CharSequence typed = readText(context, intent);
        if (typed == null || typed.toString().isEmpty()) {
            cancelIfKnown(context, postedId);
            return;
        }
        String text = typed.toString();

        TerminalSession session = findSession(sessionHandle);
        if (session == null || !session.isRunning()) {
            Logger.logWarn(LOG_TAG, "No live session " + sessionHandle + " for reply");
            cancelIfKnown(context, postedId);
            return;
        }

        // SystemUI calls a receiver on its own thread and waits for it to return before dismissing
        // the reply, so everything in here is time the user spends watching a notification that has
        // not gone yet. Held open for the history write below, which is off that path.
        final PendingResult pending = goAsync();

        session.write(text + "\r");
        Logger.logDebug(LOG_TAG, "Delivered reply to session " + sessionHandle);

        cancelIfKnown(context, postedId);

        // Bookkeeping nobody is waiting for: the session's cwd is a read of /proc, and the first call
        // in a process loads the per-directory file. Still on the main thread, since the controller
        // is not thread-safe and the activity shares it.
        final SharedPreferences preferences =
            context.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        MAIN_HANDLER.post(() -> {
            try {
                MessageHistoryController.shared(preferences)
                    .addToMessageHistory(text, session.getCwd());
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to file reply in history", e);
            } finally {
                pending.finish();
            }
        });
    }

    /** Cancel the notification if its number is known; a missing number leaves nothing to cancel. */
    private static void cancelIfKnown(@NonNull Context context, int postedId) {
        if (postedId < 0) return;
        TermuxTerminalNotificationDispatcher.cancel(context, postedId);
    }

    /**
     * Pull the typed text out of the inline reply, or {@code null} if there is none to be had.
     *
     * <p>The key is the {@link RemoteInput}'s own label, not a constant. Logs the bundle's real keys
     * when nothing matches: a wrong key is otherwise invisible, since the platform appends the text
     * to the notification either way and the reply looks delivered while nothing reaches the session.
     */
    @Nullable
    private static CharSequence readText(@NonNull Context context, @NonNull Intent intent) {
        // A Bundle, not a RemoteInput: the notification is gone by the time this runs.
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results == null) {
            Logger.logWarn(LOG_TAG, "Reply carried no results bundle at all");
            return null;
        }
        CharSequence text = results.getCharSequence(context.getString(R.string.notification_terminal_reply_hint));
        if (text == null || text.length() == 0) {
            for (String key : FALLBACK_REPLY_TEXT_KEYS) {
                text = results.getCharSequence(key);
                if (text != null && text.length() > 0) return text;
            }
        } else {
            return text;
        }
        Logger.logWarn(LOG_TAG, "Reply results bundle held no recognised text; keys=" + results.keySet());
        return null;
    }

    /**
     * Resolve a session handle against the live sessions. {@code TermuxShellManager} is reachable
     * statically, which a BroadcastReceiver needs; a null manager means no session ever started.
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
