package com.termux.app.notification;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

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
 * Handles the inline reply of a notification that a program in a terminal asked for (kitty OSC 99).
 *
 * <p>Deliberately a replica of the input panel's send path rather than a bare write to the pty, so a
 * reply typed on a notification is indistinguishable from one typed in the panel:
 *
 * <ul>
 *   <li>the raw text — <b>without</b> a trailing newline — goes into the message history, exactly
 *       as the panel stores it, deduplicated and newest-first;</li>
 *   <li>the history is filed under <b>the target session's own working directory</b>, not the
 *       foregrounded window's. The history is per-directory, and a reply to a background session's
 *       agent would otherwise be filed against whatever directory happened to be in front;</li>
 *   <li>a carriage return always follows the text. This is the one deliberate divergence from the
 *       panel, which honours an "Append Enter on send" preference.</li>
 * </ul>
 *
 * <p>Everything here runs to completion inside {@code onReceive}, and it is all cheap: the history is
 * an in-memory list plus a deferred write, and the write to the pty is a message post. The reply is
 * therefore delivered as soon as the user sends it. What the user waits on is not this — it is the
 * platform's progress indicator, which stays up until the notification is cancelled or replaced, so
 * the last thing this receiver does is answer that: on success the notification is replaced with the
 * text that was sent, on failure it is taken down.
 *
 * <p>Explicit intents only, so this stays unexported and needs no intent-filter — the same shape as
 * {@code TermuxBubbleReceiver}.
 */
public class TermuxTerminalNotificationReplyReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "TermuxTerminalNotificationReplyReceiver";

    /**
     * Fallback keys for the typed text, tried after the RemoteInput's own label.
     *
     * <p>Spelled out as literals because the {@code android.jar} this project compiles against does
     * not declare either field — its stubs are incomplete there. Neither is the key this platform
     * actually uses; they stay only so a ROM that does key the old way still works.
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
        // The number the notification was posted under. The platform keeps a progress indicator on the
        // reply field for as long as that notification is on screen, so this is what lets the reply
        // finish visibly: without a number there is nothing to replace or cancel, and the indicator
        // spins indefinitely no matter how quickly the text was actually delivered.
        int postedId = intent.getIntExtra(TermuxConstants.EXTRA_TERMINAL_NOTIFICATION_NUMBER, -1);

        CharSequence typed = readText(context, intent);
        if (typed == null || typed.toString().isEmpty()) {
            // The text was never read, so nothing can be typed anywhere. The notification goes away
            // rather than inviting a second attempt at a reply that cannot be delivered. readText has
            // already logged why.
            cancelIfKnown(context, postedId);
            return;
        }
        String text = typed.toString();

        TerminalSession session = findSession(sessionHandle);
        if (session == null || !session.isRunning()) {
            // The terminal died between the notification arriving and the user answering, so there is
            // no pty left to write into. Worth saying out loud: silently dropping it leaves the field
            // spinning with the typed text nowhere to be seen, which reads as a hung app.
            Logger.logWarn(LOG_TAG, "No live session " + sessionHandle + " for reply");
            cancelIfKnown(context, postedId);
            return;
        }

        // The same store the activity's history controller is bound to, so a reply filed here lands
        // in the very list the panel's history popup reads. Read through getSharedPreferences
        // directly rather than TermuxAppSharedPreferences: the controller wants a plain
        // SharedPreferences, and building the wrapper is only needed for the append-enter setting
        // this path deliberately does not consult.
        SharedPreferences preferences = context.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        // The session's own cwd, read from its shell's /proc entry. This is what makes the
        // per-directory history file the reply under the directory the command will actually run in.
        MessageHistoryController.shared(preferences).addToMessageHistory(text, session.getCwd());

        // Enter is UNCONDITIONAL here, unlike the input panel. That panel's "Append Enter on send"
        // preference exists for pasting partial commands that must not execute yet, and it is
        // honoured above. A notification reply is neither: it is an answer to something that is
        // waiting for an answer, and an answer that is never submitted is indistinguishable from no
        // answer at all — the program would just sit there.
        session.write(text + "\r");
        Logger.logDebug(LOG_TAG, "Delivered reply to session " + sessionHandle);

        // Nothing to do about the notification on the success path: the platform appends the typed
        // text to the conversation on its own, which is what ends the progress indicator and shows
        // the user their own words. Posting an acknowledgement over it as well would replace the
        // conversation with a second, redundant copy of it.
    }

    /** Cancel the notification if its number is known; a missing number leaves nothing to cancel. */
    private static void cancelIfKnown(@NonNull Context context, int postedId) {
        if (postedId < 0) return;
        TermuxTerminalNotificationDispatcher.cancel(context, postedId);
    }

    /**
     * Pull the typed text out of the inline reply, or {@code null} if there is none to be had.
     *
     * <p>The key is the {@link RemoteInput}'s own label, not a constant. Measured on device: the
     * results bundle came back holding exactly one key, and it was the label string
     * {@code notification_terminal_reply_hint} — not {@code InputConnection.EXTRA_TEXT} nor
     * {@code Intent.EXTRA_TEXT}, both of which are present in {@code framework.jar} and both of which
     * this code tried first. A free-form result is keyed by what the RemoteInput is labelled, so the
     * label is the only key that is right by construction.
     *
     * <p>Logs the bundle's real keys if nothing matches, because a wrong key is invisible from the
     * outside: the platform appends the typed text to the conversation either way, so the reply looks
     * delivered while nothing reaches the session.
     */
    @Nullable
    private static CharSequence readText(@NonNull Context context, @NonNull Intent intent) {
        // getResultsFromIntent returns a Bundle, not a RemoteInput: the platform hands the typed
        // characters over as a bundle and the notification is gone by the time this runs.
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
     * Resolve a session handle against the live sessions.
     *
     * <p>{@code TermuxShellManager} owns the session list and is reachable statically, which a
     * {@code BroadcastReceiver} needs: it has no bound service. A null manager means no session ever
     * started, so the null return is a normal answer rather than an error.
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
