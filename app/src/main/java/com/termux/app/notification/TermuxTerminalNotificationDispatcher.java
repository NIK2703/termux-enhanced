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
 * Turns a {@link TerminalNotification} — a desktop notification a program in a terminal asked for over
 * the kitty OSC 99 protocol — into an Android system notification.
 *
 * <p>Posted directly rather than through Termux:API: the escape sequence is already parsed in this
 * process, so there is nothing to forward, and it cannot fail because a companion app is missing.
 *
 * <p>One notification is one card, and nothing merges two of them — no conversation, no group, no
 * shortcut. {@code MessagingStyle} was the reason: its {@code Person} must be named (the platform
 * renders an anonymous one as the literal word {@code null}) and that name is then drawn as a header
 * above every message, so the style cannot show a title and its content without adding a line that is
 * not the notification's text. The session is named on the card as its subtitle instead, which tells
 * terminals apart without collecting them.
 */
public final class TermuxTerminalNotificationDispatcher {

    private static final String LOG_TAG = "TermuxTerminalNotificationDispatcher";

    /** How many notifications one session may keep on screen; the oldest is withdrawn past this. */
    private static final int MAX_LIVE_NOTIFICATIONS_PER_SESSION = 8;

    /**
     * Window within which a further notification is dropped, when deduplication is on. Measured: one
     * event arrives as a burst 6-11 ms apart, separate events seconds apart. Nothing is ever delayed —
     * a notification is posted at once or dropped at once.
     */
    private static final long DUPLICATE_WINDOW_MS = 100;

    private static final Object sDuplicateWindowLock = new Object();
    /** When a notification was last actually posted, or 0 if none has been. */
    private static long sLastPostedMs = 0;

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

    private static final int REQUEST_CODE_BASE = 0x5400;

    private static volatile boolean sWindowFocused = false;
    private static volatile boolean sWindowVisible = false;

    /** Posted ids, keyed by session handle and the program's own {@code i=}. */
    private static final Map<String, Integer> sLiveIds = new LinkedHashMap<>();

    private TermuxTerminalNotificationDispatcher() {
    }

    /** Publishes the window state used for a notification's {@code o} occasions; from the lifecycle. */
    public static void setWindowState(boolean focused, boolean visible) {
        sWindowFocused = focused;
        sWindowVisible = visible;
    }

    /**
     * A notification the program asked to be silent cannot be silenced on a channel that may sound —
     * the channel decides from API 26 — so it goes on the quiet one instead.
     */
    @NonNull
    private static String channelIdFor(@NonNull TerminalNotification notification) {
        return notification.isSilent()
            ? TermuxConstants.TERMUX_BUBBLE_NOTIFICATION_CHANNEL_ID
            : TermuxConstants.TERMUX_TERMINAL_NOTIFICATION_CHANNEL_ID;
    }

    /** The bubble's channel is IMPORTANCE_LOW, so the audible one has to be created separately. */
    public static void ensureChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            TermuxBubbleManager.createNotificationChannel(context);
        }
        createAlertingChannel(context);
    }

    /** HIGH, not DEFAULT: DEFAULT shows quietly in the shade and never heads up. */
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

        // Checked before any work, so a dropped notification costs a lookup and a comparison.
        boolean deduplicate = TermuxAppSharedPreferences.build(context).isNotificationDeduplicationEnabled(true);
        if (deduplicate && insideDuplicateWindow()) {
            Logger.logDebug(LOG_TAG, "Dropped notification " + notification.getId() + " for "
                + session.mHandle + ": inside the " + DUPLICATE_WINDOW_MS + " ms window");
            return -1;
        }

        // Idempotent, and normally already done at onStart. Kept here so a notification raised before
        // the activity has ever run still lands on a real channel.
        ensureChannel(context);

        int id = acquireId(context, manager, session.mHandle, notification.getId());
        if (id < 0) return -1;

        CharSequence title = notification.getTitle();
        CharSequence body = notification.getBody();
        // The heading: the title if the program sent one, otherwise the body. The content line below
        // gets the body only, so a one-field notification is not printed twice.
        CharSequence summary = title == null ? body : title;
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
        // Only reached by a re-send of the same i=, which is an update of a card already on screen.
        builder.setOnlyAlertOnce(true);
        // No setShortcutId and no setGroup: either would make the platform collect these together.
        CharSequence subText = sessionSubText(session);
        if (subText != null) builder.setSubText(subText);

        // Offered on the user's own say-so, not because a program asked. Read from the multi-process
        // preferences, since the service posts and must see the value as it is on disk now.
        if (TermuxAppSharedPreferences.build(context).areNotificationInlineRepliesEnabled(true)) {
            builder.addAction(buildReplyAction(context, session, id));
        }

        manager.notify(id, builder.build());
        // Stamped only after a real post, so a dropped notification never extends the window.
        if (deduplicate) markPosted();
        Logger.logDebug(LOG_TAG, "Posted notification " + id + " (" + notification.getId()
            + ") for session " + session.mHandle);

        // Raised, never refreshed: re-posting re-asserts auto-expand and un-collapses a bubble the
        // user collapsed on purpose. Termux's own bubble is posted under a fixed id, so one at most.
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
     * The inline reply: the text is typed into the session followed by a carriage return. Offered
     * regardless of the program's {@code a} key, which governs the program's interest in the click,
     * not the user's.
     */
    private static Notification.Action buildReplyAction(@NonNull Context context, @NonNull TerminalSession session,
                                                         int notificationId) {
        // The hint is the field's only text; borrowing the notification's words would look like the
        // field already holds the user's own message.
        RemoteInput remoteInput = new RemoteInput.Builder(context.getString(R.string.notification_terminal_reply_hint))
            .build();

        Intent reply = new Intent(context, TermuxTerminalNotificationReplyReceiver.class)
            .setAction(TermuxConstants.ACTION_TERMINAL_NOTIFICATION_REPLIED)
            .putExtra(TermuxConstants.EXTRA_TERMINAL_SESSION_HANDLE, session.mHandle)
            .putExtra(TermuxConstants.EXTRA_TERMINAL_NOTIFICATION_NUMBER, notificationId);
        // MUST be mutable: the system writes the typed result into this very intent and rejects an
        // immutable one outright, which loses the whole notification rather than just the reply.
        PendingIntent replyIntent = PendingIntent.getBroadcast(context, requestCode(notificationId, 3), reply,
            PendingIntent.FLAG_UPDATE_CURRENT | mutableFlag());

        return new Notification.Action.Builder(android.R.drawable.ic_menu_send,
                context.getString(R.string.notification_action_reply), replyIntent)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build();
    }

    /**
     * The id for one protocol notification, keyed on the session and the program's {@code i=} so that
     * chunks of a notification share a card and a re-send replaces it. The text is never compared:
     * treating two notifications as one event is a guess, and a wrong one either eats something meant
     * to be read or fails to hide a duplicate.
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
     * The card's subtitle: the session's title, else the working directory's name, else nothing. The
     * app's name would be the same on every card and so name nothing at all.
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

    /** The {@code o} key is a disjunction: shown if any requested occasion holds. */
    private static boolean shouldShow(int occasions) {
        if ((occasions & TerminalNotification.OCCASION_ALWAYS) != 0) return true;
        if ((occasions & TerminalNotification.OCCASION_FOCUSED) != 0 && sWindowFocused) return true;
        if ((occasions & TerminalNotification.OCCASION_UNFOCUSED) != 0 && !sWindowFocused) return true;
        if ((occasions & TerminalNotification.OCCASION_INVISIBLE) != 0 && !sWindowFocused && !sWindowVisible) return true;
        return false;
    }

    /**
     * Take one notification off the screen, once the program has been told it was opened. Also how a
     * reply that never got through ends: left alone, the progress indicator spins forever.
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

    /** Cancel one notification by its number, for callers carrying that rather than a protocol id. */
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
     * Take down what a finished session left on screen: it cannot notify again, and a reply field on
     * its card would type into a terminal that is not there.
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
     * A per-notification, per-purpose request code: {@link PendingIntent} ignores extras, so two
     * intents would otherwise collapse onto one entry and the older would win.
     */
    private static int requestCode(int notificationId, int purpose) {
        return REQUEST_CODE_BASE + (notificationId & 0xFF) * 3 + purpose;
    }

    private static int immutableFlag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    /** For the reply's intent only, which the platform writes into; below API 31 all are mutable. */
    private static int mutableFlag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
    }
}
