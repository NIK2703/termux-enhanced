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
import androidx.annotation.RequiresApi;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * How many messages a session's conversation keeps before the oldest is dropped.
     *
     * <p>A program in a loop could otherwise grow the thread without bound. The limit is the protocol
     * speaking — the terminal is free to impose one — and the messages worth having are the recent
     * ones: a conversation about what a program wants now does not need what it wanted an hour ago.
     */
    private static final int MAX_MESSAGES_PER_CONVERSATION = 24;

    /** PendingIntent request code base, offset per notification and per purpose. */
    private static final int REQUEST_CODE_BASE = 0x5400;

    /** Whether the Termux window currently has keyboard focus. */
    private static volatile boolean sWindowFocused = false;
    /** Whether the Termux window is started and not paused, i.e. nominally on screen. */
    private static volatile boolean sWindowVisible = false;

    /**
     * One conversation per session, keyed by session handle.
     *
     * <p>One notification, not a group of them. This is the documented way to show several updates as
     * a single thread: {@code developer.android.com}, "Create a group of notifications", says that a
     * group is for notifications that stand on their own, and that anything else should instead be
     * "updating an existing notification with new information, or creating a messaging-style
     * notification that shows multiple updates in the same conversation". A group was tried first and
     * measured on device to be the wrong shape: with a summary present the newest member still ranked
     * above it, so the group stayed half expanded — two cards loose above, the older ones bundled
     * below, and the summary sitting on its own with nothing in it.
     */
    private static final Map<String, Conversation> sConversations = new LinkedHashMap<>();

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
    private static String channelIdFor(@Nullable TerminalNotification notification) {
        return notification != null && notification.isSilent()
            ? TermuxConstants.TERMUX_BUBBLE_NOTIFICATION_CHANNEL_ID
            : TermuxConstants.TERMUX_TERMINAL_NOTIFICATION_CHANNEL_ID;
    }

    /**
     * Make sure the channels these notifications go on exist.
     *
     * <p>Two of them, and the split is what makes a repeat audible: the bubble's own channel is
     * {@code IMPORTANCE_LOW}, and on API 26 and later the channel alone decides whether a notification
     * sounds, vibrates or heads up — {@code setDefaults()} on the builder is ignored once a channel
     * exists, which is why a card posted there sat in the shade's silent section no matter what the
     * builder asked for. The bubble channel is still created, because it is both the bubble's own
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
     * program asking for attention means. The channel carries no {@code setAllowBubbles}: these cards
     * join the bubble because they reference its shortcut, and asking to float on their own would put
     * every session's card on screen by itself.
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

        // Idempotent, and normally already done at onStart. Kept here so a notification raised
        // before the activity has ever run still lands on a real channel instead of being dropped.
        ensureChannel(context);

        CharSequence title = notification.getTitle();
        CharSequence body = notification.getBody();

        // Fold this into the session's thread, and get the card back to post it on.
        Conversation conversation = conversationFor(context, manager, session, notification);
        if (conversation == null) return -1;

        int id = conversation.id;
        Notification built = buildNotification(context, session, conversation, id, true, true);
        if (built == null) return -1;
        manager.notify(id, built);
        Logger.logDebug(LOG_TAG, "Posted notification " + id + " (" + notification.getId()
            + ") for session " + session.mHandle);


        // Raise Termux's own bubble rather than carrying one of our own: a bubble is made by the system
        // out of a notification, so every notification with its own bubble metadata added another one
        // instead of joining, and Termux's is posted under a fixed id, so at most one can exist.
        //
        // Only ever raised, never refreshed, which is the rule the full-screen window already follows
        // and it is not cosmetic: re-posting the bubble notification re-asserts its auto-expand, so a
        // refresh un-collapses a bubble the user collapsed on purpose, and the bubble is then measured
        // again from a notification that was not posted from the state the bubble was opened in — which
        // is how it came out half the height it was raised at. So a bubble already up is left exactly
        // as the user has it, and its window still lands on the session that asked, because that is
        // recorded above and read when the window opens.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && !TermuxBubbleManager.isBubblePosted(context)
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
     * Drop the conversation shortcut published for a session that is gone.
     *
     * <p>One shortcut per session means one per terminal ever notified, and a dynamic shortcut outlives
     * the process that pushed it — that is what long-lived means here. Left behind, they accumulate for
     * the lifetime of the install, and the platform caps how many a package may have: past the cap,
     * {@link #publishSessionShortcut} starts failing and new sessions lose their conversation
     * altogether. Called when the session exits, which is the point past which it cannot post again.
     *
     * <p>The session's card goes with it. A session that has exited cannot post again, so its thread
     * is about something no longer running, and the reply field on it would type into a terminal that
     * is not there — which is the one answer the user could get wrong silently.
     */
    public static void unpublishSessionConversation(@NonNull Context context, @NonNull String sessionHandle) {
        Conversation conversation = sConversations.remove(sessionHandle);
        if (conversation != null) {
            NotificationManager manager = NotificationUtils.getNotificationManager(context);
            if (manager != null) {
                try {
                    manager.cancel(conversation.id);
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG,
                        "Failed to cancel the card of finished session " + sessionHandle, e);
                }
            }
        }
        try {
            ShortcutManagerCompat.removeDynamicShortcuts(context,
                Collections.singletonList(TermuxConstants.TERMUX_TERMINAL_NOTIFICATION_SHORTCUT_ID_PREFIX
                    + sessionHandle));
        } catch (Exception e) {
            // Nothing depends on this succeeding: a leftover shortcut costs a slot, while failing here
            // would have no visible effect at all.
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to unpublish session conversation shortcut", e);
        }
    }

    /**
     * Append a notification to the session's thread, creating that thread on the first one.
     *
     * <p>The card is identified by the session, not by the protocol's {@code i=} key, so a program
     * sending ten notifications produces one card with ten messages rather than ten cards. That is
     * what "one conversation per session" means on this platform, and it is what the documentation
     * asks for: several updates in a single {@code MessagingStyle} notification.
     *
     * <p>Every notification is a message of its own, including one that repeats the words of the one
     * before it. There used to be a window here that folded an identical repeat arriving within a
     * second into the message already on the card. It is gone, and deliberately: it was a guess at
     * what counts as one event, and a wrong guess either swallows something the user meant to read or
     * fails to catch a duplicate it was meant to hide — both indistinguishable from the outside, since
     * a missing message and a merged one look the same. What the program sent is what the card says.
     */
    @Nullable
    private static synchronized Conversation conversationFor(@NonNull Context context,
                                                             @NonNull NotificationManager manager,
                                                             @NonNull TerminalSession session,
                                                             @NonNull TerminalNotification notification) {
        CharSequence title = notification.getTitle();
        CharSequence body = notification.getBody();
        long now = System.currentTimeMillis();

        Conversation conversation = sConversations.get(session.mHandle);
        if (conversation == null) {
            conversation = new Conversation(TermuxNotificationUtils.getNextNotificationId(context));
            sConversations.put(session.mHandle, conversation);
        }

        Person author = new Person.Builder()
            .setName((title != null ? title : body).toString())
            .setImportant(true)
            .build();
        conversation.add(new Notification.MessagingStyle.Message(
            body != null ? body : title, now, author), author);
        conversation.last = notification;
        return conversation;
    }

    /**
     * Build the card for a session's whole thread, so that the original post and a later rewrite of
     * the same card cannot drift apart.
     *
     * @param withReplyAction whether to offer the inline reply field at all. False for the rewrite
     *     that follows a reply: the field is what the platform hangs its progress indicator on, so a
     *     card that still has one can never finish showing a reply as done.
     * @param alert whether re-posting this card may interrupt the user. True when the program has just
     *     said something, false when the card is only being redrawn under the user's own hand.
     */
    @Nullable
    private static Notification buildNotification(@NonNull Context context, @NonNull TerminalSession session,
                                                  @NonNull Conversation conversation, int id,
                                                  boolean withReplyAction, boolean alert) {
        CharSequence title = conversation.last == null ? null : conversation.last.getTitle();
        CharSequence body = conversation.last == null ? null : conversation.last.getBody();
        // The line the collapsed card shows: the title when the program sent one, otherwise the body.
        // The protocol's own substitution, and the only one applied.
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
        if (conversation.last != null && conversation.last.isReportOnActivate()) {
            Intent report = new Intent(context, TermuxTerminalNotificationReceiver.class)
                .setAction(TermuxConstants.ACTION_TERMINAL_NOTIFICATION_ACTIVATED)
                .putExtra(TermuxConstants.EXTRA_TERMINAL_NOTIFICATION_ID, conversation.last.getId())
                .putExtra(TermuxConstants.EXTRA_TERMINAL_SESSION_HANDLE, session.mHandle);
            reportIntent = PendingIntent.getBroadcast(context, requestCode(id, 1), report,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        }

        // No content title: the style already carries the session's name as the conversation title,
        // and setting both prints the same line twice. The text alone is left for the collapsed view,
        // where the style is not drawn and something has to be there.
        String channelId = channelIdFor(conversation.last);
        boolean silent = conversation.last != null && conversation.last.isSilent();
        // The priority follows the channel rather than being fixed. PRIORITY_DEFAULT is the pre-Oreo
        // hint, ignored once a channel exists, so it said nothing either way; matching the channel is
        // what keeps the pre-Oreo path (where the channel does not exist) consistent with the modern
        // one, where the channel alone decides whether this is an interruption.
        Notification.Builder builder = NotificationUtils.geNotificationBuilder(context, channelId,
            silent ? Notification.PRIORITY_LOW : Notification.PRIORITY_HIGH,
            null, bigText == null ? summary : bigText, null, contentIntent, reportIntent,
            silent ? NotificationUtils.NOTIFICATION_MODE_SILENT : NotificationUtils.NOTIFICATION_MODE_ALL);
        if (builder == null) return null;

        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setColor(0xFF607D8B);
        builder.setShowWhen(true);
        builder.setAutoCancel(true);
        builder.setOngoing(false);
        // Only the redraws are silenced, never a program's own news.
        //
        // One card per session means one notification id per session, so every notification after the
        // first is an *update* to a notification the shade already holds, and setOnlyAlertOnce(true) told
        // the platform not to alert on those. That is the whole of "the repeat is silent": the second
        // and third messages from a session were being drawn into the card with no sound, no vibration
        // and no heads-up, which is the one thing a program raising a notification is asking for. Left
        // false, each update alerts. A reply redraws the card under the user's own hand, so that one
        // asks for silence — otherwise typing an answer would ring.
        builder.setOnlyAlertOnce(!alert);
        // A card replaces itself rather than joining a group. The group key is deliberately gone: a
        // group only collapses when its summary outranks every member, and the newest member always
        // outranked the summary here, so the group sat permanently half expanded with its summary
        // empty and separate. One card per session needs no summary and cannot fail to collapse.

        // One conversation PER SESSION, named after it. A conversation is identified by the shortcut it
        // references, not by the channel — the platform derives a per-conversation channel named after
        // both — so reusing one shared shortcut put every terminal's notifications into a single
        // thread, and a single thread can only carry one name: the header was whichever session's post
        // happened to arrive last. The shortcut id is therefore per session.
        String shortcutId = publishSessionShortcut(context, session.mHandle, sessionLabel(session));
        if (shortcutId != null) builder.setShortcutId(shortcutId);

        // MessagingStyle, and it is not decoration: it is what makes the platform treat this as a
        // conversation at all. Measured on device — with the shortcut set and resolved, and
        // MessagingStyle left out, the record came back with mConversationId=null and no derived
        // channel, so the notifications stayed loose instead of joining a thread.
        //
        // The conversation title is the session's name, so the two printed lines answer two different
        // questions: the header says which terminal is calling, the sender line says what it wants. The
        // other way round the header merely repeated the message below it. The title is set explicitly
        // because left unset the platform fills it with the application's name — a bare "Termux" above
        // every message, naming whoever received the message instead of saying what it is about.
        Person author = conversation.firstAuthor();
        Notification.MessagingStyle style = new Notification.MessagingStyle(author)
            .setConversationTitle(sessionLabel(session));
        for (Notification.MessagingStyle.Message message : conversation.messages) {
            style.addMessage(message);
        }
        builder.setStyle(style);
        if (author != null) builder.addPerson(author);

        // The inline reply field, which types into a shell — so it is offered only when the user has
        // said they want it: not by default, and not because a program asked. Read through the
        // multi-process preferences, since notifications are posted by the service and the value has
        // to be the one on disk right now rather than whatever the UI process cached when it started.
        if (withReplyAction && TermuxAppSharedPreferences.build(context).areNotificationInlineRepliesEnabled(true)) {
            builder.addAction(buildReplyAction(context, session, id));
        }

        return builder.build();
    }

    /**
     * Put a delivered reply on screen and take the reply field away.
     *
     * <p>Replaces the card with the same thread plus the user's own message, which is what ends the
     * reply visibly. The platform's progress indicator lives on the notification that owns the reply
     * field and is cleared only when that notification is cancelled or replaced, so leaving the
     * notification alone after a successful reply — which is what this used to do, on the assumption
     * that the platform would end the indicator by itself — leaves it spinning forever. It does not:
     * measured on device, the record kept its {@code LIFETIME_EXTENDED_BY_DIRECT_REPLY} flag
     * indefinitely and the field kept spinning long after the text had reached the terminal.
     *
     * <p>The reply is added with no sender name on purpose, and not as an unnamed one: a message whose
     * person carries no name is rendered by the platform as the literal word {@code null}, which is
     * what showed up under the first attempt at this. The user's own words need no byline anyway, so
     * the person's name is set to the empty string and only the text is left to be read.
     *
     * <p>The field is dropped rather than kept, so a card that has been answered cannot spin again:
     * there is nothing left to answer with.
     */
    public static void recordReply(@NonNull Context context, @NonNull TerminalSession session,
                                   int postedId, @NonNull CharSequence replyText) {
        NotificationManager manager = NotificationUtils.getNotificationManager(context);
        if (manager == null) return;
        Conversation conversation = findConversationById(postedId);
        if (conversation == null) {
            // Nothing of ours left to rewrite, and whatever indicator is still up has to end somehow.
            cancel(context, postedId);
            return;
        }
        try {
            Person self = new Person.Builder().setName("").setImportant(true).build();
            conversation.add(new Notification.MessagingStyle.Message(
                replyText, System.currentTimeMillis(), self), null);
            Notification rewritten = buildNotification(context, session, conversation, postedId, false,
                false);
            if (rewritten == null) {
                cancel(context, postedId);
                return;
            }
            manager.notify(postedId, rewritten);
            Logger.logDebug(LOG_TAG, "Recorded reply on card " + postedId);
        } catch (Exception e) {
            // Better to lose the acknowledgement than to leave the indicator spinning: cancel, which
            // is the one outcome that is guaranteed to stop it.
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to record reply on " + postedId, e);
            cancel(context, postedId);
        }
    }

    /** The conversation posted under this number, if it is still one of ours. */
    @Nullable
    private static synchronized Conversation findConversationById(int postedId) {
        for (Conversation conversation : sConversations.values()) {
            if (conversation.id == postedId) return conversation;
        }
        return null;
    }

    /**
     * One session's thread: the card it lives on and the messages in it, oldest first.
     *
     * <p>A protocol notification is not kept per message. The thread is the unit: a card carries every
     * message the session has raised, and a reply is just another message on the same card. The last
     * protocol notification is kept only for the two things it alone can say — the line the collapsed
     * card shows, and whether the program wants to hear about the card being opened.
     */
    private static final class Conversation {
        final int id;
        final List<Notification.MessagingStyle.Message> messages = new ArrayList<>();
        @Nullable TerminalNotification last;

        /**
         * Who the card is addressed to: the author of the oldest message still on it.
         *
         * <p>MessagingStyle is built around one person, and a message from anyone else is rendered as
         * incoming with their name attached. Taking the oldest author is what keeps that person the
         * program that is talking, so a later message from the same program carries its name and the
         * user's reply — added with an empty name — does not.
         */
        @Nullable Person first;

        Conversation(int id) {
            this.id = id;
        }

        /**
         * Append a message, trimming the oldest once the thread is full.
         *
         * <p>At least one message is always kept. Letting the thread empty would leave a card with
         * nothing in it, which is exactly the empty summary that made the group approach look broken.
         */
        void add(@NonNull Notification.MessagingStyle.Message message, @Nullable Person author) {
            messages.add(message);
            while (messages.size() > MAX_MESSAGES_PER_CONVERSATION) {
                messages.remove(0);
            }
            if (author != null) first = author;
        }

        @Nullable
        Person firstAuthor() {
            return first;
        }
    }
    /**
     * Publish the long-lived shortcut that makes this session's notifications a conversation.
     *
     * <p>Per the platform's own rules for conversation notifications (developer.android.com,
     * {@code Notification.MessagingStyle} and {@code Builder.setShortcutId}): a notification counts as
     * a conversation notification when it uses {@code MessagingStyle} <em>and</em> references a
     * valid conversation shortcut, which must be a <em>long-lived</em> dynamic or cached sharing
     * shortcut. The conversation <em>is</em> that shortcut: the platform derives a per-conversation
     * channel and a shade section from it, so one shared shortcut means one conversation no matter how
     * many sessions post. Hence one shortcut, and one conversation, per session.
     *
     * <p>Long-lived but still dynamic: nothing is pinned and nothing appears in the launcher, the flag
     * only keeps it from being discarded when the process dies. That is required here — the system has
     * to resolve the shortcut to build the conversation, and a transient one came back unresolvable on
     * the notification record ({@code mShortcutId} resolved to nothing, so no conversation was formed).
     *
     * <p>Re-published on every post, because the label is the session's name: a session that was renamed
     * or moved to another directory has to be shown under its current name, and a label frozen at first
     * post would keep printing the old one. The intent opens that very session, so the shortcut, the
     * conversation it names and the notification inside it all lead to the same terminal.
     *
     * <p>One shortcut per session means one per open terminal, so they are unpublished when their
     * session ends — otherwise the set grows for the lifetime of the process. That is deliberately not
     * done eagerly on every notification: it is a broadcast to the launcher, and posting is the hot
     * path here.
     *
     * @return the shortcut id, or {@code null} if it could not be published.
     */
    @Nullable
    private static String publishSessionShortcut(@NonNull Context context, @NonNull String sessionHandle,
                                                  @NonNull CharSequence label) {
        String shortcutId = TermuxConstants.TERMUX_TERMINAL_NOTIFICATION_SHORTCUT_ID_PREFIX + sessionHandle;
        try {
            ShortcutManagerCompat.pushDynamicShortcut(context, new ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(TermuxActivityUtils.newInstance(context)
                    // The action is not optional: ShortcutInfo.Builder.setIntents() rejects an intent
                    // without one outright ("intent's action must be set"), and that rejection happens
                    // inside pushDynamicShortcut, so the shortcut is never published and the caller
                    // gets no id to reference. Which is exactly what a missing conversation looks like
                    // from the outside: the notifications post, but the platform has no anchor to group
                    // them by, so each one arrives as its own card.
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(TermuxConstants.EXTRA_TERMINAL_SESSION_HANDLE, sessionHandle))
                .setLongLived(true)
                .build());
            return shortcutId;
        } catch (Exception e) {
            // Without the shortcut there is no conversation for these to join and the notification
            // shows as a loose one, which is still worth posting: the user gets it, the reply still
            // works, and a tap still opens the right session. Only the grouping is lost.
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to publish session conversation shortcut", e);
            return null;
        }
    }



    /**
     * The name a session's conversation is known by.
     *
     * <p>The session's own title when it has one, then the working directory's name, and only the
     * application name as a last resort: the directory tells one terminal from another, which is what
     * a conversation header has to say about itself, whereas the app's name would be the same on every
     * one of them and so name nothing at all.
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
     * Take one message out of a session's thread, and the card away with it if that was the last one.
     *
     * <p>Used after the program is told its notification was opened. What is stale then is that one
     * message, not the whole conversation, so only the message goes — the rest of the thread is still
     * what the session has been saying.
     *
     * <p>Also the path that ends a reply which never got through: an unreadable reply bundle, or a
     * session that is no longer there to type into. Left alone, the platform's progress indicator
     * would spin with the typed text nowhere to be seen.
     */
    static void dismiss(@NonNull Context context, @NonNull String sessionHandle,
                        @Nullable String protocolId) {
        Conversation conversation = removeMessage(context, sessionHandle, protocolId);
        if (conversation == null) return;
        NotificationManager manager = NotificationUtils.getNotificationManager(context);
        if (manager == null) return;
        try {
            manager.cancel(conversation.id);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to dismiss card " + conversation.id, e);
        }
    }

    /**
     * Drop the message a protocol notification produced, keeping the thread if anything is left on it.
     *
     * @return the conversation when it has been emptied and its card must go, {@code null} when
     *     nothing was removed.
     */
    @Nullable
    private static synchronized Conversation removeMessage(@NonNull Context context,
                                                            @NonNull String sessionHandle,
                                                            @Nullable String protocolId) {
        Conversation conversation = sConversations.get(sessionHandle);
        if (conversation == null || protocolId == null) return null;
        if (conversation.last == null || !protocolId.equals(conversation.last.getId())) return null;
        conversation.last = null;
        conversation.messages.clear();
        conversation.first = null;
        sConversations.remove(sessionHandle);
        return conversation;
    }
    /**
     * Cancel one notification by the number it was posted under, for callers that carry that number
     * rather than a protocol identifier.
     */
    static void cancel(@NonNull Context context, int notificationId) {
        NotificationManager manager = NotificationUtils.getNotificationManager(context);
        if (manager == null) return;
        forgetConversation(notificationId);
        try {
            manager.cancel(notificationId);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to cancel notification " + notificationId, e);
        }
    }

    /**
     * Forget the thread behind a card that is being taken off the screen.
     *
     * <p>Otherwise the next notification from that session would land on a card the user has already
     * dismissed, and the thread would grow on with nothing on screen to show it.
     */
    private static synchronized void forgetConversation(int notificationId) {
        sConversations.values().removeIf(conversation -> conversation.id == notificationId);
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
