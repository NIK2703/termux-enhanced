package com.termux.app.bubble;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

/**
 * Publishes the floating "bubble" window that hosts a terminal session over other apps.
 *
 * <p>A bubble is not a window owned by this app: it is a window owned by SystemUI, into which the
 * app's activity is embedded through an {@code ActivityView}. All the app does is post a
 * notification carrying a {@link Notification.BubbleMetadata} and, on Android 11+, a long-lived
 * sharing shortcut. Requirements that are easy to miss:
 *
 * <ul>
 *   <li>The bubble activity must declare BOTH {@code android:resizeableActivity="true"} and
 *       {@code android:allowEmbedded="true"}. If either is missing the system silently shows a
 *       plain notification instead — no error anywhere.</li>
 *   <li>On Android 10 the notification must additionally be a "conversation" (MessagingStyle with a
 *       {@link Person}) or be posted while the app is in the foreground. This app targets API 28,
 *       so the conversation requirements that apply to apps targeting API 30+ are not enforced for
 *       us — but both belt-and-braces conditions are satisfied here anyway, because a bubble that
 *       never appears is indistinguishable from a crash to the user.</li>
 *   <li>{@code setSuppressNotification(true)} (post the bubble without a notification in the shade)
 *       requires the app to be in the foreground, and a foreground <em>service</em> does not count.
 *       It is therefore only requested when it cannot cost the user anything: suppression is a
 *       trade — the notification is dropped in exchange for the bubble — and if the platform then
 *       refuses to bubble, the notification is gone with nothing to replace it. So it is asked for
 *       only when the channel itself reports that it can bubble; otherwise the notification stays
 *       visible as a fallback. See {@link #showBubbleInternal}.</li>
 *   <li>The app cannot switch bubbles on for itself. {@code NotificationChannel.setAllowBubbles()}
 *       is overwritten by the platform for a normal (target) app, both when the channel is created
 *       and when it is updated, so the flag that actually decides whether a bubble appears belongs
 *       to the user's per-app bubble setting. {@link #areBubblesAvailable} is therefore the honest
 *       gate for every entry point, and the notification is deliberately a conversation
 *       ({@code MessagingStyle} + {@link Person} + a long-lived shortcut) so that the channel shows
 *       up in the user's bubble settings where it can be enabled.</li>
 * </ul>
 */
public final class TermuxBubbleManager {

    private static final String LOG_TAG = "TermuxBubbleManager";

    /** Action sent when the user dismisses the bubble, so the notification can be cleaned up. */
    public static final String ACTION_BUBBLE_DISMISSED = "com.termux.app.bubble.ACTION_BUBBLE_DISMISSED";

    /**
     * Action sent by the "open in bubble" button on the app's own notification.
     *
     * <p>It is a broadcast rather than an activity intent because the button is tapped from the
     * notification shade, where the app is usually in the background: starting an activity from
     * there would drag the whole UI to the foreground just to post a notification.
     */
    public static final String ACTION_OPEN_BUBBLE = "com.termux.app.bubble.ACTION_OPEN_BUBBLE";

    /**
     * Optional label for the bubble notification, used when the bubble is opened from the
     * notification button — that path has no session at hand, so the text is carried in the intent
     * instead. Purely cosmetic: the bubble window reads its own session title once it is up.
     */
    public static final String EXTRA_BUBBLE_TITLE = "com.termux.app.bubble.EXTRA_BUBBLE_TITLE";

    private static final int REQUEST_CODE_BUBBLE = 99;
    private static final int REQUEST_CODE_CONTENT = 100;
    private static final int REQUEST_CODE_DELETE = 101;

    /**
     * Set while this app has posted a bubble and has not taken it down.
     *
     * <p>The app-side half of {@link #isBubblePosted(Context)}: the system's notification record is
     * the other half, and can be blind to a suppressed bubble notification. Written and read on the
     * main thread only (every caller is an activity, receiver or service callback).
     */
    private static boolean sBubblePosted;

    /**
     * Wall-clock time at which this process started, used to tell this process's own bubble
     * notification apart from one left behind by an earlier one. See {@link #isBubblePosted}.
     */
    private static final long PROCESS_START_TIME_WALL_CLOCK =
        System.currentTimeMillis() - SystemClock.elapsedRealtime();

    private TermuxBubbleManager() {}

    /** Bubbles exist from Android 10 (API 29). Below that the whole feature is inert. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    /**
     * Whether the system will actually show a bubble right now.
     *
     * <p>This is advisory: it gates the "bubble" button on the app's own notification (a button
     * that cannot work is worse than no button) and the automatic path. The posting path itself
     * still tolerates a refusal, because these per-app flags can lag behind a setting the user just
     * changed.
     */
    public static boolean areBubblesAvailable(@NonNull Context context) {
        if (!isSupported()) return false;
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        if (notificationManager == null) return false;
        if (!notificationManager.areNotificationsEnabled()) return false;
        return areBubblesAllowed(notificationManager);
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static boolean areBubblesAllowed(@NonNull NotificationManager notificationManager) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Per-user switch, added in API 30. Supersedes the app-level flag below.
                return notificationManager.areBubblesEnabled();
            }
            return notificationManager.areBubblesAllowed();
        } catch (Exception e) {
            // Never let an advisory check take the feature down; assume allowed and let the post
            // itself decide.
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read bubble settings, assuming allowed", e);
            return true;
        }
    }

    /**
     * Create the notification channel used by bubbles, if it does not exist yet.
     *
     * <p>Kept separate from the foreground-service channel: a bubble channel must be cancellable and
     * not ongoing, while the service notification is ongoing by design.
     *
     * <p><b>{@code setAllowBubbles(true)} below is advisory only.</b> For an app that is not the
     * system, the platform discards it: {@code PreferencesHelper.createNotificationChannel} ends its
     * new-channel branch with
     * {@code channel.setAllowBubbles(existing != null ? existing.getAllowBubbles() : DEFAULT_ALLOW_BUBBLE)},
     * so a freshly created channel always starts at {@code DEFAULT_ALLOW_BUBBLE} (-1). The
     * existing-channel branch does not copy {@code allowBubbles} at all — only the name, description,
     * group, blockable and importance — so a later call cannot raise it either. And because
     * {@code NotificationChannel.canBubble()} is {@code mAllowBubbles == ALLOW_BUBBLE_ON}, a channel
     * left at -1 does <em>not</em> bubble. Confirmed on device: the channel sat at
     * {@code mAllowBubbles=-1} and the notification posted with {@code mAllowBubble=false}.
     *
     * <p>The switch that actually decides this is the user's bubble preference for the app, which is
     * what {@link #areBubblesAvailable} reads. The call is kept because some ROMs do honour it and it
     * costs nothing, but nothing here may rely on it.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    public static void createNotificationChannel(@NonNull Context context) {
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        if (notificationManager == null) return;

        NotificationChannel channel = new NotificationChannel(
            TermuxConstants.TERMUX_BUBBLE_NOTIFICATION_CHANNEL_ID,
            TermuxConstants.TERMUX_BUBBLE_NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.bubble_notification_channel_description));
        // See the class note above: this is a request, not a guarantee.
        channel.setAllowBubbles(true);
        channel.setShowBadge(false);

        notificationManager.createNotificationChannel(channel);
    }

    /**
     * Publish the long-lived sharing shortcut the bubble is associated with, then return its id.
     *
     * <p>Only mandatory for apps targeting API 30+, and this app targets 28 — but SystemUI also
     * uses the shortcut id as the identity of a bubble, so publishing it costs nothing and removes
     * a class of device-specific surprises.
     *
     * <p>The shortcut carries the <em>real</em> bubble intent, not a placeholder: some platforms
     * resolve a bubble's launch intent from its shortcut, so a placeholder would send the bubble to
     * the wrong place (or nowhere).
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private static String publishShortcut(@NonNull Context context, @NonNull CharSequence label,
                                          @NonNull Intent bubbleTarget) {
        String shortcutId = TermuxConstants.TERMUX_BUBBLE_SHORTCUT_ID;
        try {
            ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(bubbleTarget)
                // A cached/long-lived shortcut survives the app being killed, which is what the
                // conversation requirements ask for.
                .setLongLived(true)
                .build();
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut);
        } catch (Exception e) {
            // A missing shortcut degrades the bubble on some ROMs but must not abort the post.
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to publish bubble shortcut", e);
        }
        return shortcutId;
    }

    /**
     * Show (or refresh) the bubble.
     *
     * <p>Takes a plain {@link Context}, not an {@link Activity}, because the callers sit in very
     * different positions in the lifecycle: the automatic path runs from an activity lifecycle
     * callback (the app on its way to the background) and the notification's button from a broadcast
     * receiver (normally with the app already in the background). Posting from the background is
     * allowed here because the notification is a conversation — {@code MessagingStyle} plus a
     * {@link Person} plus the long-lived sharing shortcut published below are exactly what the
     * platform asks for to let a bubble be created without the app being in the foreground. The one
     * thing the background path cannot have is suppression (see {@link #showBubbleInternal}), so it
     * is decided there rather than assumed here.
     *
     * <p>No session is named on purpose. The bubble hosts a second instance of the app's own window
     * ({@link TermuxBubbleActivity}), and that window resolves its own current session the same way
     * the full-screen one does; handing it a session handle would create a second source of truth
     * that the window's own tab handling would immediately fight with.
     *
     * @param sessionTitle human readable title, used for the notification and shortcut label.
     * @return {@code true} if the notification was posted (which is as far as this app can tell;
     *         whether SystemUI renders a bubble or a plain notification is its decision).
     */
    public static boolean showBubble(@NonNull Context context, @NonNull CharSequence sessionTitle) {
        if (!isSupported()) return false;

        logCaller();

        try {
            return showBubbleInternal(context, sessionTitle);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to post bubble notification", e);
            return false;
        }
    }

    /**
     * Log the first stack frame outside this class, so that "who opened the bubble" is answerable
     * from logcat.
     *
     * <p>Three different entry points post a bubble — the notification's button (a broadcast
     * receiver), the automatic path in {@link TermuxActivity} (an activity lifecycle callback) and,
     * in debug builds, an adb hook — and from the outside they are indistinguishable: they all end
     * in the same {@code notify()} call, and a bubble appearing at an unexpected moment otherwise
     * costs a bisect to explain. Posting is rare, so the stack walk is free in practice.
     */
    private static void logCaller() {
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            if (TermuxBubbleManager.class.getName().equals(frame.getClassName())) continue;
            Logger.logInfo(LOG_TAG, "showBubble requested by " + frame.getClassName()
                + "." + frame.getMethodName() + ":" + frame.getLineNumber());
            return;
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static boolean showBubbleInternal(@NonNull Context context,
                                              @NonNull CharSequence sessionTitle) {
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        if (notificationManager == null) return false;
        if (!notificationManager.areNotificationsEnabled()) return false;

        createNotificationChannel(context);

        Intent bubbleTarget = new Intent(context, TermuxBubbleActivity.class)
            .setAction(Intent.ACTION_VIEW);
        // A single PendingIntent for the bubble: there is one bubble per app, and re-posting the
        // notification refreshes it rather than stacking another one. FLAG_UPDATE_CURRENT keeps the
        // intent's contents current when the title changes.
        //
        // This one MUST be mutable, unlike every other PendingIntent in this file. SystemUI fills
        // in the launch parameters (which bubble, where it sits, whether it opens expanded) by
        // mutating this intent, and NotificationManagerService rejects an immutable one outright:
        // "PendingIntents attached to bubbles must be mutable" — thrown from
        // checkDisqualifyingFeatures(), which is a RemoteException from notify(), so the failure
        // surfaces as "nothing happened" unless the caller logs it. This is the documented
        // exception to the "always prefer FLAG_IMMUTABLE" rule. FLAG_MUTABLE only exists from
        // API 31; below that PendingIntents are mutable by default and the flag is not needed.
        int bubbleIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bubbleIntentFlags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent bubbleIntent = PendingIntent.getActivity(context, REQUEST_CODE_BUBBLE,
            bubbleTarget, bubbleIntentFlags);

        // Published after the target intent exists, because the shortcut carries that same intent.
        String shortcutId = publishShortcut(context, sessionTitle, bubbleTarget);

        Intent contentTarget = new Intent(context, TermuxActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(context, REQUEST_CODE_CONTENT,
            contentTarget, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent deleteTarget = new Intent(context, TermuxBubbleReceiver.class)
            .setAction(ACTION_BUBBLE_DISMISSED);
        PendingIntent deleteIntent = PendingIntent.getBroadcast(context, REQUEST_CODE_DELETE,
            deleteTarget, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // The (PendingIntent, Icon) constructor is used rather than the shortcut-id one
        // (Builder(String), API 30, deprecated in 31). That overload makes SystemUI resolve the
        // bubble's launch intent from the shortcut itself and ignore the intent handed to it, which
        // is a behaviour this app has no reason to depend on. The shortcut is still published and
        // referenced from the notification, which is where the long-lived-sharing association is
        // actually read from.
        Notification.BubbleMetadata.Builder bubbleMetadata =
            new Notification.BubbleMetadata.Builder(bubbleIntent,
                Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setDesiredHeight(desiredHeightDp(context))
                .setAutoExpandBubble(true);

        // Suppressing the notification only makes sense when a bubble is guaranteed to replace it —
        // otherwise the user would see nothing at all. Both halves of that condition are checked:
        // the user's app-level bubble preference, and the channel's own flag, because the channel
        // can still refuse (the platform forces DEFAULT_ALLOW_BUBBLE on a target app, and only the
        // user's per-channel toggle raises it). Without the second check, an app-level "bubbles on"
        // with a channel that cannot bubble would silently swallow the notification.
        if (areBubblesAllowed(notificationManager) && channelCanBubble(context)) {
            bubbleMetadata.setSuppressNotification(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+ only. There is deliberately no setShortcutId() here: the builder has no such
            // method, and the shortcut-id constructor above is avoided on purpose.
            bubbleMetadata.setDeleteIntent(deleteIntent);
        }

        Person self = new Person.Builder()
            .setName(TermuxConstants.TERMUX_APP_NAME)
            .setImportant(true)
            .build();

        // MessagingStyle + Person is one of the documented ways for a bubble to be accepted, and it
        // costs nothing here. The session title is used as the message so the fallback notification
        // still says something useful.
        Notification.MessagingStyle style = new Notification.MessagingStyle(self)
            .setConversationTitle(TermuxConstants.TERMUX_APP_NAME)
            .addMessage(sessionTitle, System.currentTimeMillis(), self);

        Notification notification = new Notification.Builder(context,
            TermuxConstants.TERMUX_BUBBLE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setColor(0xFF607D8B)
            .setContentTitle(TermuxConstants.TERMUX_APP_NAME)
            .setContentText(sessionTitle)
            .setStyle(style)
            .setContentIntent(contentIntent)
            .setShortcutId(shortcutId)
            .addPerson(self)
            // Deliberately NOT ongoing: opening a bubble hides its notification, which an ongoing
            // notification cannot do, and the bubble must remain dismissible by the user.
            .setOngoing(false)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setBubbleMetadata(bubbleMetadata.build())
            .build();

        notificationManager.notify(TermuxConstants.TERMUX_BUBBLE_NOTIFICATION_ID, notification);
        Logger.logDebug(LOG_TAG, "Posted bubble notification");

        // Set before the refresh below, because the service rebuilds the notification from this very
        // state: it must already read "a bubble is up" when it decides whether to offer the button.
        sBubblePosted = true;

        // The bubble's own button on the service notification is now redundant (the bubble is up), so
        // that notification has to be rebuilt to drop it. This is the single place every posting path
        // goes through — the automatic one, the notification's button, the debug hook — which is why
        // the refresh lives here rather than at each call site.
        requestServiceNotificationRefresh(context);
        return true;
    }

    /** Remove the bubble (and its notification) if one is showing. */
    public static void cancel(@NonNull Context context) {
        cancel(context, false);
    }

    /**
     * Remove the bubble, optionally forcing the service notification to be rebuilt.
     *
     * @param stateChangedOutside {@code true} when the caller already knows the bubble went away
     *        without this app cancelling it — SystemUI removes the bubble's notification before it
     *        delivers the dismiss intent, so the "was it posted?" test below reads {@code false} in
     *        exactly the case where the service notification most needs rebuilding.
     */
    public static void cancel(@NonNull Context context, boolean stateChangedOutside) {
        if (!isSupported()) return;
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        if (notificationManager == null) return;

        // Whether the state actually changes decides whether the service notification needs rebuilding:
        // the automatic path calls this on every resume of the full-screen window, and re-posting the
        // service notification each time would be pure churn. Read before the cancel, obviously.
        final boolean wasPosted = isBubblePosted(context);

        notificationManager.cancel(TermuxConstants.TERMUX_BUBBLE_NOTIFICATION_ID);
        // Cleared before the refresh below, so the service already reads "no bubble" when it decides
        // whether to offer the button again.
        sBubblePosted = false;
        Logger.logDebug(LOG_TAG, "Cancelled bubble notification");

        // The bubble is gone, so its button belongs back on the service notification.
        if (wasPosted || stateChangedOutside) requestServiceNotificationRefresh(context);
    }

    /**
     * Ask the service to rebuild its notification, so that the "bubble" button on it appears and
     * disappears with the bubble itself.
     *
     * <p>{@code TermuxService} builds that notification once and then only when it has a reason to:
     * a session or task change, or a wake-lock toggle. Posting or dropping the bubble is such a reason
     * but happens outside the service, so the service has to be told. The request carries no
     * description of what changed — the service re-reads the bubble state itself, which is the only
     * way the two cannot drift apart.
     *
     * <p>Sent as a start command because the service is {@code exported="false"}: the app can reach it,
     * nothing else can. The service is already in the foreground (that is where the notification comes
     * from), so this is allowed from the background as well.
     *
     * <p>Never fatal: a missed refresh leaves a stale button, not a broken feature, and this is called
     * from the posting path whose result must not be changed by it.
     */
    private static void requestServiceNotificationRefresh(@NonNull Context context) {
        try {
            context.startService(new Intent(context, TermuxService.class)
                .setAction(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_REFRESH_NOTIFICATION));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to ask the service to refresh its notification", e);
        }
    }

    /**
     * Whether a bubble is currently up, as far as this app can tell.
     *
     * <p>Two sources, OR-ed, because neither is complete on its own:
     *
     * <ul>
     *   <li>the notification record, which is the system's own answer and therefore the only way to
     *       notice a bubble that went away without telling this app (SystemUI took it down, the user
     *       dismissed it and the delete intent never arrived);</li>
     *   <li>{@link #sBubblePosted}, because the bubble notification is posted with
     *       {@code setSuppressNotification(true)} — it is deliberately kept out of the shade — and a
     *       suppressed bubble notification can be filtered out of
     *       {@code NotificationManager.getActiveNotifications()}. Were that the case here, the
     *       record-based answer alone would read {@code false} exactly while a bubble is on screen,
     *       which is the state the "bubble" button on the service notification has to react to.</li>
     * </ul>
     *
     * <p><b>Only records posted by this process count.</b> A notification outlives the process that
     * posted it — measured on device: the bubble's record (id 1342) was still listed after
     * {@code am force-stop} — and nothing in the new process would ever cancel it, so trusting a
     * record from before the process started would keep {@code true} forever and hide the "bubble"
     * button for good. That is exactly the failure this guard exists to prevent. A bubble that is
     * genuinely up was posted by this process, where the flag above already says so.
     *
     * <p>The flag is cleared by every path that takes a bubble down — {@link #cancel}, and through it
     * the dismiss broadcast and the service stopping — and it dies with the process, which takes the
     * bubble with it, so it cannot outlive the thing it describes.
     *
     * <p>Used both to avoid re-posting a bubble that is already up, and to decide whether the
     * service notification still has any business offering its "bubble" button.
     */
    public static boolean isBubblePosted(@NonNull Context context) {
        if (!isSupported()) return false;
        if (sBubblePosted) return true;
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        if (notificationManager == null) return false;
        try {
            for (StatusBarNotification posted : notificationManager.getActiveNotifications()) {
                if (posted.getId() != TermuxConstants.TERMUX_BUBBLE_NOTIFICATION_ID) continue;
                if (posted.getPostTime() < PROCESS_START_TIME_WALL_CLOCK) {
                    Logger.logDebug(LOG_TAG, "Ignoring bubble notification record left by a previous process");
                    continue;
                }
                return true;
            }
        } catch (Exception e) {
            // Advisory only: on failure assume nothing is posted and let notify() refresh.
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to list active notifications", e);
        }
        return false;
    }

    /**
     * Whether the bubble channel itself is allowed to bubble.
     *
     * <p>This is not the same question as {@link #areBubblesAvailable}, which reads the user's
     * app-level preference. The channel carries an independent flag that the app cannot set (the
     * platform overwrites {@code setAllowBubbles} for target apps and never applies it on update),
     * so a channel can read as not-bubblable even with bubbles enabled for the app. That state is
     * exactly the one in which suppression must not be requested, because no bubble would take the
     * notification's place.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private static boolean channelCanBubble(@NonNull Context context) {
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        if (notificationManager == null) return false;
        NotificationChannel channel = notificationManager.getNotificationChannel(
            TermuxConstants.TERMUX_BUBBLE_NOTIFICATION_CHANNEL_ID);
        // canBubble() is `mAllowBubbles == ALLOW_BUBBLE_ON`, so the platform default (-1) reads as
        // false. That is the value a target app's channel gets, hence the check.
        return channel != null && channel.canBubble();
    }

    /**
     * Expanded bubble height. SystemUI clamps this to its own limits; ~60% of the screen keeps a
     * terminal usable without covering everything behind it.
     */
    private static int desiredHeightDp(@NonNull Context context) {
        int screenHeightDp = context.getResources().getConfiguration().screenHeightDp;
        if (screenHeightDp <= 0) return 480;
        return Math.round(screenHeightDp * 0.6f);
    }

    /** @return the bubble activity class name, for diagnostics. */
    @Nullable
    public static String getBubbleActivityClassName() {
        return TermuxBubbleActivity.class.getName();
    }
}
