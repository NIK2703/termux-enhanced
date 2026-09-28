package com.termux.app.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.Person;
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
import com.termux.app.bubble.TermuxBubbleManager;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.termux.notification.TermuxNotificationUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.terminal.TerminalNotification;
import com.termux.terminal.TerminalSession;

import java.util.Iterator;
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
     * Make sure the channel these notifications go on exists.
     *
     * <p>It is Termux's own bubble channel, created by its bubble manager. Same channel, same shortcut,
     * same conversation: the platform derives a per-conversation channel from that pair, so posting
     * anywhere else puts these in a neighbouring thread rather than the bubble's own.
     *
     * <p>Cost of sharing it: the channel is IMPORTANCE_LOW, so these notifications are quiet — no
     * heads-up, no sound. That is the price of being in the bubble's conversation rather than next to
     * it; the notification is still shown and still takes a reply.
     */
    public static void ensureChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            TermuxBubbleManager.createNotificationChannel(context);
        }
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

        // No content title: the style already carries the title as the conversation title, and setting
        // both prints the same line twice. The text alone is left for the collapsed view, where the
        // style is not drawn and something has to be there.
        Notification.Builder builder = NotificationUtils.geNotificationBuilder(context,
            TermuxConstants.TERMUX_BUBBLE_NOTIFICATION_CHANNEL_ID, Notification.PRIORITY_DEFAULT,
            null, bigText == null ? summary : bigText, null, contentIntent, reportIntent,
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

        // Every session's notifications join ONE conversation: the one the bubble is anchored to, by
        // reusing its shortcut id. A conversation is identified by the shortcut, not by the channel —
        // the platform derives a per-conversation channel named after both — so keeping our own loud
        // channel does not exclude us from the bubble's conversation, while a per-session shortcut
        // would have split these notifications into one conversation per open terminal.
        String shortcutId = publishConversationShortcut(context, title != null ? title : body);
        if (shortcutId != null) builder.setShortcutId(shortcutId);

        // Where the bubble should go when the user asks for it, however they ask: by tapping the
        // bubble, or by the bubble coming up on its own when the app is minimised.
        TermuxBubbleManager.noteSessionAskedForAttention(session.mHandle);

        // MessagingStyle, and it is not decoration: it is what makes the platform treat this as a
        // conversation at all. Measured on device — with the bubble's own shortcut set and resolved,
        // and MessagingStyle left out, the record came back with mConversationId=null and no derived
        // channel, so the notifications stayed loose instead of joining the bubble's thread. Termux's
        // own bubble notification sets both, and so must this.
        //
        // The Person is named with the session that raised the notification, not with the app: in a
        // conversation shared by every session, that line is the only thing saying which terminal is
        // calling, and an application name would say the same thing on all of them.
        // The conversation title is the notification's own title, set per notification. Left unset, the
        // platform fills it with the application's name — which is what put a bare "Termux" above every
        // message: three lines where two were wanted, the top one naming whoever received the message
        // instead of saying what it is about.
        Person sender = new Person.Builder()
            .setName(sessionLabel(session).toString())
            .setImportant(true)
            .build();
        builder.setStyle(new Notification.MessagingStyle(sender)
            .setConversationTitle(title != null ? title : body)
            .addMessage(new Notification.MessagingStyle.Message(
                body != null ? body : title, System.currentTimeMillis(), sender)));
        builder.addPerson(sender);


        // The inline reply field, which types into a shell — so it is offered only when the user has said
        // they want it: not by default, and not because a program asked. Read through the multi-process
        // preferences, since notifications are posted by the service and the value has to be the one on
        // disk right now rather than whatever the UI process cached when it started.
        if (TermuxAppSharedPreferences.build(context).areNotificationInlineRepliesEnabled(true)) {
            builder.addAction(buildReplyAction(context, session, id));
        }

        manager.notify(id, builder.build());
        Logger.logDebug(LOG_TAG, "Posted notification " + id + " (" + notification.getId()
            + ") for session " + session.mHandle);

        // Raise Termux's own bubble rather than carrying one of our own, for two reasons that were both
        // measured. A bubble is made by the system out of a notification, so every notification carrying
        // its own bubble metadata added another bubble instead of joining one — two per notification
        // with duplicates. And Termux's bubble notification is posted under a fixed id, so refreshing
        // it replaces the bubble already up rather than stacking a second one. Its window falls back to
        // the last session that asked, recorded above, so the bubble lands where the user is being
        // called. A failure here costs the bubble and nothing else: the notification and its reply are
        // already posted.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && TermuxBubbleManager.areBubblesAvailable(context)) {
                TermuxBubbleManager.showBubble(context, title != null ? title : body);
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to raise the bubble", e);
        }
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
     * Publish the shortcut that puts these notifications into the bubble's conversation.
     *
     * <p>It is the bubble's own shortcut id, not a per-session one, so that every session's
     * notifications land in the single conversation the user already knows — and so the bubble has an
     * anchor to resolve. Published here as well because {@code TermuxBubbleManager} only publishes it
     * when it posts its own bubble notification, which need not have happened: a program can ask for
     * attention before the app has ever been minimised.
     *
     * <p>Long-lived, and still dynamic: nothing is pinned and nothing appears in the launcher, the flag
     * only keeps it from being discarded when the process dies. That matters because the system has to
     * resolve the shortcut to build the conversation, and a transient one came back unresolvable on the
     * notification record.
     *
     * @return the shortcut id, or {@code null} if it could not be published.
     */
    @Nullable
    private static String publishConversationShortcut(@NonNull Context context, @NonNull CharSequence label) {
        String shortcutId = TermuxConstants.TERMUX_BUBBLE_SHORTCUT_ID;
        try {
            // Labelled with the notification's own title, and re-published on every post. This label is
            // what the shade prints above a conversation's messages, so a constant one put the
            // application's name there on every notification: a line saying who received the message
            // rather than what it is about, and the reason it survived every change to the
            // notification's own title. Re-published per post, so the conversation carries the newest
            // message's title — the same thing the conversation header shows for a messaging app.
            ShortcutManagerCompat.pushDynamicShortcut(context, new ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(new Intent(context, TermuxBubbleActivity.class)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(TermuxConstants.EXTRA_TERMINAL_SESSION_HANDLE,
                        TermuxBubbleManager.lastNotifiedSession()))
                .setLongLived(true)
                .build());
            return shortcutId;
        } catch (Exception e) {
            // Without the shortcut there is no conversation for these to join, but the notification
            // itself is still worth posting: the user still gets it, the reply still works, and a tap
            // still opens the right session.
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to publish conversation shortcut", e);
            return null;
        }
    }



    /**
     * Name this session for the conversation's sender line.
     *
     * <p>The session's own title when it has one, then the working directory's name, and only the
     * application name as a last resort. In a conversation every session shares, this line is what
     * tells the reader which terminal is calling; the app's name would be identical on all of them and
     * therefore useless.
     */
    @NonNull
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
