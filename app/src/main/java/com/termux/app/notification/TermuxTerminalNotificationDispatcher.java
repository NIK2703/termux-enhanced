package com.termux.app.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;

import android.graphics.drawable.Icon;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import com.termux.R;
import com.termux.app.TermuxActivityUtils;
import com.termux.app.bubble.TermuxBubbleActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.termux.notification.TermuxNotificationUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalNotification;
import com.termux.terminal.TerminalSession;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns a {@link TerminalNotification} — a desktop notification a program in a terminal asked for
 * over the kitty OSC 99 protocol — into an Android system notification.
 *
 * <p>Deliberately not routed through Termux:API. The escape sequence is already parsed inside this
 * process ({@code TerminalEmulator}), so there is nothing to forward: posting directly keeps the
 * notification under the Termux app's own name, icon, channel and tap target, costs no IPC, and
 * cannot fail because a companion app happens to be missing or killed.
 *
 * <p>Published as a loud notification on a channel of its own, carrying the inline reply and grouped
 * per session so the shade keeps one terminal's messages together rather than interleaving them with
 * another's. Tapping it opens Termux full-screen on the very session that asked for attention, which
 * is the whole point: the user reads the prompt and answers in the same window, without hunting for
 * the right tab among several.
 *
 * <p>Not a bubble, and that is a measured conclusion rather than a shortcut. A notification raised by
 * a program in a background session cannot be floated on this platform: the app is in the background
 * when it arrives, the platform discards the app's own {@code setAllowBubbles(true)}, and only a
 * per-channel user preference raises a channel — one this ROM does not expose. Termux's own bubble
 * works because Termux creates it itself while still in the foreground, so the option a user has
 * there does not transfer to a notification that appears while the app is away.
 *
 * <p>The window's focus/visibility is published here by {@code TermuxActivity} rather than read at
 * post time, because a notification is posted by the service client (see
 * {@code TermuxTerminalSessionClientMux}) and the service has no window.
 */
public final class TermuxTerminalNotificationDispatcher {

    private static final String LOG_TAG = "TermuxTerminalNotificationDispatcher";

    /** Group all terminal-requested notifications share, so they collapse together in the shade. */
    private static final String NOTIFICATION_GROUP = "termux_terminal_notifications";

    /**
     * How many notifications per terminal are kept before the oldest is withdrawn. A program in a
     * loop could otherwise accumulate notifications without bound, and the interesting ones are
     * always the most recent few. The protocol allows a terminal to impose such a limit.
     */
    private static final int MAX_LIVE_NOTIFICATIONS_PER_SESSION = 8;

    /** PendingIntent request code base, offset per notification and per purpose. */
    private static final int REQUEST_CODE_BASE = 0x5400;

    /** Whether the Termux window currently has keyboard focus. */
    private static volatile boolean sWindowFocused = false;
    /** Whether the Termux window is started and not paused, i.e. nominally on screen. */
    private static volatile boolean sWindowVisible = false;

    /**
     * Notification ids handed out, keyed by session handle and protocol identifier, in creation
     * order. Bounded by {@link #MAX_LIVE_NOTIFICATIONS_PER_SESSION} per session; the entry evicted
     * from here has its notification withdrawn.
     */
    private static final Map<String, Integer> sLiveIds = new LinkedHashMap<>();

    private TermuxTerminalNotificationDispatcher() {
    }

    /**
     * Publish the window state used to evaluate a notification's {@code o} occasions.
     *
     * <p>Called from the activity lifecycle. {@code focused} is window focus (which is what DECSET
     * 1004 means by focus); {@code visible} is "started and not paused".
     */
    public static void setWindowState(boolean focused, boolean visible) {
        sWindowFocused = focused;
        sWindowVisible = visible;
    }

    /**
     * Create the notification channel these notifications go on.
     *
     * <p>Called from the activity on start as well as before every post, because on Android 13+ the
     * system raises the POST_NOTIFICATIONS consent prompt itself the first time a channel is created.
     * Doing it on start puts that one-off prompt in context instead of having it ambush the user over
     * some other app.
     *
     * <p>A channel of its own, and loud. The foreground-service channel is IMPORTANCE_LOW and ongoing,
     * which is the opposite of what an interactive notification wants, and a channel's importance does
     * not reliably stick once the user has seen it.
     *
     * <p>No bubbles. They are not reachable for a notification that arrives while the app is in the
     * background: the platform discards the app's {@code setAllowBubbles(true)}, and only the user's
     * per-channel preference raises a channel, which this ROM does not expose for a build targeting
     * API 28. A program asking for attention from a background session therefore cannot be floated —
     * tapping the notification opens this app on that session instead, which is what the content
     * intent is for.
     */
    public static void ensureChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = NotificationUtils.getNotificationManager(context);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(
            TermuxConstants.TERMUX_TERMINAL_NOTIFICATION_CHANNEL_ID,
            TermuxConstants.TERMUX_TERMINAL_NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT));
    }

    /**
     * Post a notification requested by a program, honouring its occasions, sound and action keys.
     *
     * @return the notification id used, or {@code -1} if nothing was posted.
     */
    public static int post(@NonNull Context context, @NonNull TerminalSession session,
                           @NonNull TerminalNotification notification) {
        if (!shouldShow(notification.getOccasions())) {
            Logger.logDebug(LOG_TAG, "Suppressed notification " + notification.getId()
                + " for " + session.mHandle + ": occasions not satisfied");
            return -1;
        }

        NotificationManager manager = NotificationUtils.getNotificationManager(context);
        if (manager == null) return -1;
        // Covers both "the user turned notifications off" and "consent was never granted".
        if (!manager.areNotificationsEnabled()) return -1;

        // Idempotent, and normally already done at onStart. Kept here so a notification raised
        // before the activity has ever run still lands on a real channel instead of being dropped.
        ensureChannel(context);

        int id = acquireId(context, manager, session.mHandle, notification.getId());
        if (id < 0) return -1;

        CharSequence title = notification.getTitle();
        CharSequence body = notification.getBody();
        // The line that always shows: the title when the program sent one, otherwise the body. The
        // protocol's own substitution, and the only one applied.
        CharSequence summary = title == null ? body : title;
        // The body is expanded text under the title — only when there is a title for it to sit under.
        // A notification that is nothing but a title gets no second line, so the text appears exactly
        // once; a notification that is nothing but a body is already the summary and is likewise not
        // repeated underneath itself.
        CharSequence bigText = title == null || body == null ? null : body;
        // Which session this is — for the shortcut and the group, never for the text.
        CharSequence sessionLabel = sessionLabel(session);

        PendingIntent contentIntent = PendingIntent.getActivity(context, requestCode(id, 0),
            TermuxActivityUtils.newInstance(context)
                .putExtra(TermuxConstants.EXTRA_TERMINAL_SESSION_HANDLE, session.mHandle),
            PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());

        PendingIntent reportIntent = null;
        if (notification.isReportOnActivate()) {
            Intent report = new Intent(context, TermuxTerminalNotificationReceiver.class)
                .setAction(TermuxConstants.ACTION_TERMINAL_NOTIFICATION_ACTIVATED)
                .putExtra(TermuxConstants.EXTRA_TERMINAL_NOTIFICATION_ID, notification.getId())
                .putExtra(TermuxConstants.EXTRA_TERMINAL_SESSION_HANDLE, session.mHandle);
            reportIntent = PendingIntent.getBroadcast(context, requestCode(id, 1), report,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        }

        Notification.Builder builder = NotificationUtils.geNotificationBuilder(context,
            TermuxConstants.TERMUX_TERMINAL_NOTIFICATION_CHANNEL_ID, Notification.PRIORITY_DEFAULT,
            summary, bigText == null ? summary : bigText, bigText, contentIntent, reportIntent,
            notification.isSilent() ? NotificationUtils.NOTIFICATION_MODE_SILENT
                : NotificationUtils.NOTIFICATION_MODE_ALL);
        if (builder == null) return -1;

        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setColor(0xFF607D8B);
        builder.setShowWhen(true);
        builder.setAutoCancel(true);
        builder.setOngoing(false);
        builder.setOnlyAlertOnce(false);
        // Per session, not one bucket for the lot: this is what keeps a session's notifications
        // together in the shade instead of interleaving several terminals' into one unread pile.
        builder.setGroup(NOTIFICATION_GROUP + "." + session.mHandle);

        // One shortcut per session, published before it is referenced. It is what the system groups
        // a session's notifications by and what identifies the session in the shade's conversation
        // list, which is how a wall of notifications from several terminals stays readable.
        String shortcutId = publishSessionShortcut(context, session.mHandle, sessionLabel);
        if (shortcutId != null) builder.setShortcutId(shortcutId);

        // Deliberately no MessagingStyle. Its Person must be named — the platform rejects an anonymous
        // one — and the style then draws that name as a header above every message, including the
        // reply the platform appends when the user answers. So the style cannot produce a notification
        // that is just a title and its content: there is always a third line, and whatever it says, it
        // is not the notification's text. With bubbles off that is no longer a trade worth making.
        builder.addAction(buildReplyAction(context, session, id));

        manager.notify(id, builder.build());
        Logger.logDebug(LOG_TAG, "Posted notification " + id + " (" + notification.getId()
            + ") for session " + session.mHandle);
        return id;
    }

    /**
     * The inline "reply" affordance: type into the notification, and the text is typed into that
     * session followed by a carriage return.
     *
     * <p>Offered regardless of what the program asked for in its {@code a} key. That key governs
     * whether the <em>program</em> wants to hear about the click; an answer from the user is a
     * different thing, and for an agentic terminal the ability to answer "yes" / "go ahead" without
     * opening the app is the whole point of the floating window.
     */
    private static Notification.Action buildReplyAction(@NonNull Context context, @NonNull TerminalSession session,
                                                         int notificationId) {
        // The RemoteInput keeps its own hint: it is the only text in the input field, and borrowing
        // the notification's words for it would leave the reader typing into a box that already
        // appears to hold their own message.
        RemoteInput remoteInput = new RemoteInput.Builder(context.getString(R.string.notification_terminal_reply_hint))
            .build();

        Intent reply = new Intent(context, TermuxTerminalNotificationReplyReceiver.class)
            .setAction(TermuxConstants.ACTION_TERMINAL_NOTIFICATION_REPLIED)
            .putExtra(TermuxConstants.EXTRA_TERMINAL_SESSION_HANDLE, session.mHandle)
            .putExtra(TermuxConstants.EXTRA_TERMINAL_NOTIFICATION_NUMBER, notificationId);
        // MUST be mutable: the system writes the user's typed RemoteInput result into this very
        // intent before delivering it, and NotificationManagerService rejects an immutable one
        // outright — "Not posted. PendingIntents attached to actions with remote inputs must be
        // mutable" — which loses the whole notification, not just the reply.
        PendingIntent replyIntent = PendingIntent.getBroadcast(context, requestCode(notificationId, 3), reply,
            PendingIntent.FLAG_UPDATE_CURRENT | mutableFlag());

        return new Notification.Action.Builder(android.R.drawable.ic_menu_send,
                context.getString(R.string.notification_action_reply), replyIntent)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build();
    }





    /**
     * Name this session, for the places that have to name it: the per-session shortcut, and the
     * label the shade and the launcher show for it.
     *
     * <p>Never used for the notification's text. The session's own title is the best answer when it
     * has one, and the working directory's name is the next best — it tells one terminal from
     * another, which is the thing a notification from a background session most needs to say about
     * itself. The app's own name is only the last resort, when nothing else is known.
     */
    /**
     * Publish the per-session shortcut that ties one session's notifications together as a single
     * conversation, and names that session in the shade.
     *
     * <p>One per session, all of them long-lived dynamic shortcuts. Long-lived here costs nothing in
     * launcher rows — it is a dynamic shortcut, so nothing is pinned and nothing appears in the
     * launcher; the flag only stops it being discarded when the process dies. That matters because the
     * system has to resolve the shortcut to group a session's notifications into a conversation, and a
     * transient one came back unresolvable on the notification record.
     *
     * @return the shortcut id, or {@code null} if it could not be published.
     */
    @Nullable
    private static String publishSessionShortcut(@NonNull Context context, @NonNull String sessionHandle,
                                                 @NonNull CharSequence label) {
        String shortcutId = TermuxConstants.TERMUX_TERMINAL_NOTIFICATION_SHORTCUT_ID_PREFIX + sessionHandle;
        try {
            Intent target = new Intent(context, TermuxBubbleActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(TermuxConstants.EXTRA_TERMINAL_SESSION_HANDLE, sessionHandle);
            ShortcutManagerCompat.pushDynamicShortcut(context, new ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(target)
                .setLongLived(true)
                .build());
            return shortcutId;
        } catch (Exception e) {
            // A missing shortcut costs the per-session conversation grouping, but must not abort the
            // post: the user still gets the notification, the reply and the right session on tap.
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to publish session shortcut", e);
            return null;
        }
    }

    private static CharSequence sessionLabel(@NonNull TerminalSession session) {
        String title = session.getTitle();
        if (title != null && !title.trim().isEmpty()) return title;
        String cwd = session.getCwd();
        if (cwd != null && !cwd.trim().isEmpty()) {
            String path = cwd.endsWith("/") ? cwd.substring(0, cwd.length() - 1) : cwd;
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash + 1 < path.length()) return path.substring(lastSlash + 1);
        }
        return TermuxConstants.TERMUX_APP_NAME;
    }

    /**
     * Decide whether a notification's requested occasions are currently satisfied.
     *
     * <p>The {@code o} key is a disjunction: the notification is shown if any requested occasion
     * holds. {@code always} is unconditional.
     */
    private static boolean shouldShow(int occasions) {
        if ((occasions & TerminalNotification.OCCASION_ALWAYS) != 0) return true;
        if ((occasions & TerminalNotification.OCCASION_FOCUSED) != 0 && sWindowFocused) return true;
        if ((occasions & TerminalNotification.OCCASION_UNFOCUSED) != 0 && !sWindowFocused) return true;
        if ((occasions & TerminalNotification.OCCASION_INVISIBLE) != 0 && !sWindowFocused && !sWindowVisible) return true;
        return false;
    }

    /**
     * Return the notification id for one protocol notification, reusing it while the notification is
     * live so repeated chunks land on the same entry, and withdrawing the oldest of a session once
     * {@link #MAX_LIVE_NOTIFICATIONS_PER_SESSION} is exceeded.
     */
    private static synchronized int acquireId(@NonNull Context context, @NonNull NotificationManager manager,
                                              @NonNull String sessionHandle, @NonNull String protocolId) {
        String key = sessionHandle + ' ' + protocolId;
        Integer existing = sLiveIds.get(key);
        if (existing != null) return existing;

        String oldestKey = null;
        int oldestForSession = 0;
        int countForSession = 0;
        for (Map.Entry<String, Integer> entry : sLiveIds.entrySet()) {
            if (entry.getKey().startsWith(sessionHandle + ' ')) {
                if (countForSession == 0) {
                    oldestKey = entry.getKey();
                    oldestForSession = entry.getValue();
                }
                countForSession++;
            }
        }
        if (countForSession >= MAX_LIVE_NOTIFICATIONS_PER_SESSION && oldestKey != null) {
            manager.cancel(oldestForSession);
            sLiveIds.remove(oldestKey);
            Logger.logDebug(LOG_TAG, "Withdrew notification " + oldestForSession
                + " (limit " + MAX_LIVE_NOTIFICATIONS_PER_SESSION + " per session)");
        }

        int id = TermuxNotificationUtils.getNextNotificationId(context);
        sLiveIds.put(key, id);
        return id;
    }

    /**
     * Take a notification off the screen and drop its bookkeeping entry.
     *
     * <p>Only for the paths where the platform has not already dealt with the notification. On a
     * successful reply the platform appends the typed text to the conversation itself, which both
     * ends the progress indicator and shows the user what was sent — so that path deliberately does
     * nothing here, and this exists for the cases where the text never arrived: an unreadable reply
     * bundle, or a session that is no longer there to type into. Left alone, the field would spin
     * indefinitely with the typed text nowhere to be seen.
     */
    static synchronized void dismiss(@NonNull Context context, @NonNull String sessionHandle,
                                     @Nullable String protocolId) {
        Integer id = sLiveIds.remove(sessionHandle + ' ' + (protocolId == null ? "" : protocolId));
        if (id == null) return;
        NotificationManager manager = NotificationUtils.getNotificationManager(context);
        if (manager == null) return;
        try {
            manager.cancel(id);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to dismiss notification " + id, e);
        }
    }

    /**
     * Cancel one notification by the number it was posted under, for callers that carry that number
     * rather than a protocol identifier.
     */
    static void cancel(@NonNull Context context, int notificationId) {
        NotificationManager manager = NotificationUtils.getNotificationManager(context);
        if (manager == null) return;
        try {
            manager.cancel(notificationId);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to cancel notification " + notificationId, e);
        }
    }

    /**
     * A per-notification, per-purpose request code. {@link PendingIntent} ignores extras, so two
     * notifications (or one notification's content, activation and reply intents) would otherwise
     * collapse onto the same entry and the older one would win.
     */
    private static int requestCode(int notificationId, int purpose) {
        return REQUEST_CODE_BASE + (notificationId & 0xFF) * 3 + purpose;
    }

    private static int immutableFlag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    /**
     * For the one intent the platform itself writes into: the inline reply's. It fills in the typed
     * RemoteInput result by mutating the intent and rejects an immutable one — so this is the
     * documented exception to preferring {@link #immutableFlag()}. Below API 31 pending intents are
     * mutable by default anyway.
     */
    private static int mutableFlag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
    }
}
