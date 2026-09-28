package com.termux.app.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.termux.R;
import com.termux.app.TermuxActivityUtils;
import com.termux.app.bubble.TermuxBubbleManager;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.termux.notification.TermuxNotificationUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
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
 * <p>One notification is one card. They are not folded into a conversation, a thread or a group, and
 * nothing merges two of them: a program that sends five notifications gets five cards, each with its
 * own id, its own text and its own alert. The earlier design made a session's notifications a single
 * {@code MessagingStyle} conversation, which folded them under one heading — but
 * {@code MessagingStyle} cannot express "a title and its content" on its own. Its {@code Person} has
 * to be named (the platform renders an anonymous one as the literal word {@code null}), and whatever
 * name it is given is then drawn as a header above every message, including the answer the user types.
 * So the style always adds a line that is not the notification's text, and a program sending
 * "Session done" twice could not be shown as two honest cards. Removing the conversation removes the
 * extra line with it.
 *
 * <p>What is kept per session is the name, not the grouping: each card carries the session's own name
 * as its subtitle, so several terminals' cards are still told apart without being bundled. There is
 * no conversation shortcut either, since a shortcut is precisely what makes the platform collect
 * everything referencing it into one conversation — the thing being removed here.
 *
 * <p>Published on a channel of its own that may sound, carrying the inline reply, so tapping a card
 * opens Termux full-screen on the very session that asked for attention and answering it types into
 * that same session. That is the whole point: the user reads the prompt and answers in the same
 * window, without hunting for the right tab among several.
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

    /**
     * How many notifications one session may have on screen at once.
     *
     * <p>A program in a loop would otherwise fill the shade without bound. When the limit is reached
     * the session's oldest notification is withdrawn, which is the protocol speaking rather than this
     * app: the terminal is free to impose a limit, and the cards worth having are the recent ones.
     */
    private static final int MAX_LIVE_NOTIFICATIONS_PER_SESSION = 8;

    /**
     * How close to the last posted notification a second one has to arrive to be dropped, when
     * deduplication is switched on.
     *
     * <p>Hundred milliseconds is short enough to be the width of one burst rather than a gap between
     * events: measured on device, a program raising a single event sends it twice a few milliseconds
     * apart, while anything a person would call a separate event is seconds away. It is a width, not a
     * delay — nothing is ever held back, a notification is either posted at once or dropped at once.
     */
    private static final long DUPLICATE_WINDOW_MS = 100;

    /** Guards {@link #sLastPostedMs}, which is written from the session threads. */
    private static final Object sDuplicateWindowLock = new Object();
    /** When a notification was last actually posted, or 0 if none has been. */
    private static long sLastPostedMs = 0;

    /**
     * Whether this notification lands too close to the last one posted to be worth showing again.
     *
     * <p>Zero means nothing has been posted yet, and the first notification of the process is never
     * inside the window: there is nothing to be a duplicate of.
     */
    private static boolean insideDuplicateWindow() {
        synchronized (sDuplicateWindowLock) {
            return sLastPostedMs != 0 && System.currentTimeMillis() - sLastPostedMs < DUPLICATE_WINDOW_MS;
        }
    }

    private static void markPosted() {
        synchronized (sDuplicateWindowLock) {
            sLastPostedMs = System.currentTimeMillis();
        }
    }

    /** PendingIntent request code base, offset per notification and per purpose. */
    private static final int REQUEST_CODE_BASE = 0x5400;

    /** Whether the Termux window currently has keyboard focus. */
    private static volatile boolean sWindowFocused = false;
    /** Whether the Termux window is started and not paused, i.e. nominally on screen. */
    private static volatile boolean sWindowVisible = false;

    /**
     * The id each live notification was posted under, keyed by session handle and the program's own
     * {@code i=} identifier.
     *
     * <p>Keyed on the protocol identifier rather than on the session, so the chunks of one notification
     * keep landing on the same card and a re-send of the same {@code i=} replaces it, which is what
     * the protocol asks for. A different {@code i=} is a different notification and gets a different
     * card, however close in time and however similar the text.
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
     * The channel a notification is posted on, chosen by whether the program asked for sound.
     *
     * <p>A program that said nothing wants to be heard, and that is the channel it gets. One that sent
     * {@code s=silent} goes on the bubble's {@code IMPORTANCE_LOW} channel instead: there is no way to
     * silence one notification on a channel that may sound, since the channel decides on API 26 and
     * later, so honouring the request means posting somewhere quiet.
     */
    @NonNull
    private static String channelIdFor(@NonNull TerminalNotification notification) {
        return notification.isSilent()
            ? TermuxConstants.TERMUX_BUBBLE_NOTIFICATION_CHANNEL_ID
            : TermuxConstants.TERMUX_TERMINAL_NOTIFICATION_CHANNEL_ID;
    }

    /**
     * Make sure the channels these notifications go on exist.
     *
     * <p>Two of them, and the split is what makes a notification audible at all: the bubble's own
     * channel is {@code IMPORTANCE_LOW}, and on API 26 and later the channel alone decides whether a
     * notification sounds, vibrates or heads up — {@code setDefaults()} on the builder is ignored once
     * a channel exists. The bubble channel is still created, because it is both the bubble's own
     * channel and the quiet one {@link #channelIdFor} sends silent notifications to.
     */
    public static void ensureChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            TermuxBubbleManager.createNotificationChannel(context);
        }
        createAlertingChannel(context);
    }

    /**
     * The channel a program's notification can make a sound on.
     *
     * <p>High importance, so it heads up as well as sounding: at {@code IMPORTANCE_DEFAULT} the
     * notification is shown quietly in the shade and is never an interruption, which is not what a
     * program asking for attention means. The channel carries no {@code setAllowBubbles}: a card that
     * could float on its own would put every session's notification on screen by itself.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private static void createAlertingChannel(@NonNull Context context) {
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        if (notificationManager == null) return;
        NotificationChannel channel = new NotificationChannel(
            TermuxConstants.TERMUX_TERMINAL_NOTIFICATION_CHANNEL_ID,
            TermuxConstants.TERMUX_TERMINAL_NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.terminal_notification_channel_description));
        notificationManager.createNotificationChannel(channel);
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

        // Deduplication, off by default. Read before anything else is done, because a notification
        // that is going to be dropped should cost one preference lookup and one comparison: no id is
        // allocated, no shortcut published, no card built.
        boolean deduplicate = TermuxAppSharedPreferences.build(context).isNotificationDeduplicationEnabled(true);
        if (deduplicate && insideDuplicateWindow()) {
            Logger.logDebug(LOG_TAG, "Dropped notification " + notification.getId() + " for "
                + session.mHandle + ": inside the " + DUPLICATE_WINDOW_MS + " ms window");
            return -1;
        }

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
        boolean silent = notification.isSilent();

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

        // The line under the heading is the body, and nothing else. It used to fall back to the summary
        // when there was no body, which printed a one-field notification twice: with no title the
        // summary IS the body, so the fallback put the same words on the second line. A program sending
        // nothing but text — "OSC 99;;Hello", the form the protocol's own one-liner uses — got its
        // message twice, once as the heading and once below it. Measured on device, and it is what the
        // comment on bigText above has always claimed did not happen.
        //
        // The substitution therefore applies to the summary alone, which is the line that must always
        // carry something, and the content line stays empty when there is no separate body. Every shape
        // then reads once: title and body are two lines, a bare title is one, a bare body is one.
        Notification.Builder builder = NotificationUtils.geNotificationBuilder(context,
            channelIdFor(notification), silent ? Notification.PRIORITY_LOW : Notification.PRIORITY_HIGH,
            summary, body, bigText, contentIntent, reportIntent,
            silent ? NotificationUtils.NOTIFICATION_MODE_SILENT : NotificationUtils.NOTIFICATION_MODE_ALL);
        if (builder == null) return -1;

        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setColor(0xFF607D8B);
        builder.setShowWhen(true);
        builder.setAutoCancel(true);
        builder.setOngoing(false);
        // Each notification has its own id, so this is only ever reached by a re-send of the same
        // protocol notification, which is an update of a card the shade already holds and must not
        // ring a second time for the same event.
        builder.setOnlyAlertOnce(true);
        // No setShortcutId and no setGroup, deliberately. A conversation shortcut is what makes the
        // platform gather everything referencing it into one conversation, and a group is what makes
        // it bundle them behind a summary — both are the folding this is here to avoid. The session
        // is named on the card instead, which tells one terminal from another without collecting
        // anything.
        CharSequence subText = sessionSubText(session);
        if (subText != null) builder.setSubText(subText);

        // The inline reply field, which types into a shell — so it is offered only when the user has
        // said they want it: not by default, and not because a program asked. Read through the
        // multi-process preferences, since notifications are posted by the service and the value has
        // to be the one on disk right now rather than whatever the UI process cached when it started.
        if (TermuxAppSharedPreferences.build(context).areNotificationInlineRepliesEnabled(true)) {
            builder.addAction(buildReplyAction(context, session, id));
        }

        manager.notify(id, builder.build());
        // Stamped only now, and only when it really was posted: the window is measured from the last
        // notification the user was given, so a dropped one must not push it forward. A program sending
        // in bursts is therefore throttled from the first of each burst, not from the last thing
        // rejected.
        if (deduplicate) markPosted();
        Logger.logDebug(LOG_TAG, "Posted notification " + id + " (" + notification.getId()
            + ") for session " + session.mHandle);

        // Raise Termux's own bubble rather than carrying one of our own: a bubble is made by the system
        // out of a notification, so every notification with its own bubble metadata added another one
        // instead of joining, and Termux's is posted under a fixed id, so at most one can exist.
        //
        // Only ever raised, never refreshed, which is the rule the full-screen window already follows
        // and it is not cosmetic: re-posting the bubble notification re-asserts its auto-expand, so a
        // refresh un-collapses a bubble the user collapsed on purpose.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && !TermuxBubbleManager.isBubblePosted(context)
                    && TermuxBubbleManager.areBubblesAvailable(context)) {
                TermuxBubbleManager.showBubble(context, summary);
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
     * The id one protocol notification is posted under, and how many of a session's may be live.
     *
     * <p>Reused while the notification is live, so the chunks of one notification land on the same
     * card and a re-send of the same {@code i=} replaces it rather than adding a second card. A
     * different {@code i=} is a different notification, however close in time and however alike the
     * text: nothing here compares the words, because deciding that two notifications are the same
     * event is a guess, and a wrong guess either eats something meant to be read or fails to hide a
     * duplicate — from the outside a missing notification and a merged one look the same.
     */
    private static synchronized int acquireId(@NonNull Context context, @NonNull NotificationManager manager,
                                              @NonNull String sessionHandle, @NonNull String protocolId) {
        String key = sessionHandle + ' ' + protocolId;
        Integer existing = sLiveIds.get(key);
        if (existing != null) return existing;

        String oldestKey = null;
        int oldestId = 0;
        int liveForSession = 0;
        String prefix = sessionHandle + ' ';
        for (Map.Entry<String, Integer> entry : sLiveIds.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            if (liveForSession == 0) {
                oldestKey = entry.getKey();
                oldestId = entry.getValue();
            }
            liveForSession++;
        }
        if (liveForSession >= MAX_LIVE_NOTIFICATIONS_PER_SESSION && oldestKey != null) {
            sLiveIds.remove(oldestKey);
            try {
                manager.cancel(oldestId);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG,
                    "Failed to withdraw notification " + oldestId, e);
            }
            Logger.logDebug(LOG_TAG, "Withdrew notification " + oldestId + " (limit "
                + MAX_LIVE_NOTIFICATIONS_PER_SESSION + " per session)");
        }

        int id = TermuxNotificationUtils.getNextNotificationId(context);
        sLiveIds.put(key, id);
        return id;
    }

    /**
     * What a card says about which terminal it came from.
     *
     * <p>The session's own title, else the working directory's name, else nothing at all: the
     * directory tells one terminal from another, whereas the application's name would be the same on
     * every card and so name nothing. Returning nothing rather than the app name keeps a bare
     * "Termux" off a card that has nothing better to say — the line exists to identify the session,
     * and where it cannot, it is better absent than wrong.
     */
    @Nullable
    private static CharSequence sessionSubText(@NonNull TerminalSession session) {
        String title = session.getTitle();
        if (title != null && !title.trim().isEmpty()) return title;
        String cwd = session.getCwd();
        if (cwd == null || cwd.trim().isEmpty()) return null;
        String path = cwd.endsWith("/") ? cwd.substring(0, cwd.length() - 1) : cwd;
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash + 1 >= path.length()) return null;
        return path.substring(lastSlash + 1);
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
     * Take one notification off the screen, once the program has been told it was opened.
     *
     * <p>Also the path that ends a reply which never got through: an unreadable reply bundle, or a
     * session that is no longer there to type into. Left alone, the platform's progress indicator
     * would spin with the typed text nowhere to be seen.
     */
    static synchronized void dismiss(@NonNull Context context, @NonNull String sessionHandle,
                                     @Nullable String protocolId) {
        if (protocolId == null) return;
        Integer id = sLiveIds.remove(sessionHandle + ' ' + protocolId);
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
    static synchronized void cancel(@NonNull Context context, int notificationId) {
        NotificationManager manager = NotificationUtils.getNotificationManager(context);
        if (manager == null) return;
        sLiveIds.values().removeIf(id -> id == notificationId);
        try {
            manager.cancel(notificationId);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to cancel notification " + notificationId, e);
        }
    }

    /**
     * Take down everything a finished session left on screen.
     *
     * <p>Called when the session exits, which is the point past which it cannot notify again. Its
     * cards are about something no longer running, and the reply field on one would type into a
     * terminal that is not there — the one answer the user could get wrong silently.
     */
    public static synchronized void forgetSession(@NonNull Context context, @NonNull String sessionHandle) {
        String prefix = sessionHandle + ' ';
        Integer[] ids = sLiveIds.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(prefix))
            .map(Map.Entry::getValue)
            .toArray(Integer[]::new);
        if (ids.length == 0) return;
        for (Map.Entry<String, Integer> entry : new LinkedHashMap<>(sLiveIds).entrySet()) {
            if (entry.getKey().startsWith(prefix)) sLiveIds.remove(entry.getKey());
        }
        NotificationManager manager = NotificationUtils.getNotificationManager(context);
        if (manager == null) return;
        for (Integer id : ids) {
            try {
                manager.cancel(id);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG,
                    "Failed to cancel notification " + id + " of finished session " + sessionHandle, e);
            }
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
