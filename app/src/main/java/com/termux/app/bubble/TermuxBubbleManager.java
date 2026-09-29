package com.termux.app.bubble;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
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
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.lang.reflect.Method;

/**
 * Posts the floating "bubble" notification that embeds a terminal session via SystemUI's
 * {@code ActivityView}. Easy-to-miss requirements:
 *
 * <ul>
 *   <li>Bubble activity needs BOTH {@code resizeableActivity="true"} and
 *       {@code allowEmbedded="true"}, or the system silently shows a plain notification.</li>
 *   <li>On Android 10 the notification must be a conversation (MessagingStyle + {@link Person})
 *       or be posted from the foreground; this app targets API 28 so the API-30+ conversation
 *       rules are not enforced, but both belt-and-braces conditions are met anyway.</li>
 *   <li>{@code setSuppressNotification(true)} needs the app in the <em>foreground</em> (a
 *       foreground service does not count) and trades the shade notification for the bubble —
 *       only requested when the channel already reports it can bubble, so a refused bubble never
 *       loses the notification. See {@link #showBubbleInternal}.</li>
 *   <li>{@code setAllowBubbles()} is overwritten by the platform for target apps; the real gate
 *       is the user's per-app bubble setting, read via {@link #areBubblesAvailable}. The
 *       conversation style keeps the channel visible in that settings screen.</li>
 *   <li>Feature existence is {@link #isSupported(Context)} (API level, framework API, non-low-RAM);
 *       the notification's "bubble" button and the Settings switch both hang off it.</li>
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
     * <p>Single source of truth: every transition is reported to us, so nothing has to be asked of
     * the system. We post it here, the user's dismissal arrives at {@code ACTION_BUBBLE_DISMISSED},
     * and {@link #cancel} clears it. A record left by an earlier process is deliberately not counted
     * — see {@link #isBubblePosted}.
     */
    private static volatile boolean sBubblePosted;

    /**
     * Set once the bubble channel has been created. Channels are idempotent and never change, and
     * this is on the path of every notification posted, where the call is a binder round trip.
     */
    private static volatile boolean sBubbleChannelCreated;

    /**
     * Session whose program most recently raised a notification, or {@code null}. The bubble is one
     * shared window over every session, so it has no session of its own to show; not part of the
     * bubble's lifecycle, since dismissing the bubble leaves that session still asking.
     */
    @Nullable
    private static volatile String sLastNotifiedSessionHandle;

    /**
     * Record that a session's program asked for the user's attention; the handle is the session's
     * {@code TerminalSession#mHandle}.
     */
    public static void noteSessionAskedForAttention(@NonNull String sessionHandle) {
        sLastNotifiedSessionHandle = sessionHandle;
    }

    @Nullable
    public static String lastNotifiedSession() {
        return sLastNotifiedSessionHandle;
    }

    private TermuxBubbleManager() {}

    /**
     * Framework call that reads the user's bubble preference, resolved once per process.
     * Tries {@code areBubblesEnabled()} (API 31) then {@code areBubblesAllowed()} (API 29).
     *
     * <p><b>Why reflection, not {@code SDK_INT}.</b> The old version table wrongly treated
     * {@code areBubblesEnabled()} as an API 30 method, so on Android 11 (API 30, method absent)
     * an {@code SDK_INT >= R} branch threw {@link NoSuchMethodError} from the service's
     * {@code onCreate} and took the app down at startup. Asking the running framework which call
     * it has cannot go stale that way. {@code null} = neither call exists — see {@link #isSupported}.
     */
    @Nullable
    private static final Method BUBBLE_PREFERENCE_METHOD = resolveBubblePreferenceMethod();

    @Nullable
    private static Method resolveBubblePreferenceMethod() {
        for (String name : new String[]{"areBubblesEnabled", "areBubblesAllowed"}) {
            try {
                return NotificationManager.class.getMethod(name);
            } catch (NoSuchMethodException e) {
                // This one is not in the running framework. Absence is a normal answer here, so the
                // next candidate is tried instead of the failure being logged as a problem.
            }
        }
        return null;
    }

    /**
     * Whether this device can show a bubble at all: API 29+, {@link #BUBBLE_PREFERENCE_METHOD}
     * present, and not a low-RAM device (the platform refuses to bubble there — see
     * {@link #isLowRamDevice}). Gate for the notification's "bubble" button and the Settings switch.
     */
    public static boolean isSupported(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        if (BUBBLE_PREFERENCE_METHOD == null) return false;
        return !isLowRamDevice(context);
    }

    /**
     * Whether this is a low-RAM (Android Go) device. The platform's own bubble test in
     * {@code BubbleExtractor.process()} includes {@code !mActivityManager.isLowRamDevice()};
     * when false it strips bubble metadata before ranking. {@code isLowRamDevice()} is the
     * documented predicate ({@code ro.config.low_ram}). Advisory: a failed read assumes a
     * normal device and leaves the decision to the post.
     */
    private static boolean isLowRamDevice(@NonNull Context context) {
        try {
            ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            return activityManager != null && activityManager.isLowRamDevice();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read low-RAM state, assuming normal device", e);
            return false;
        }
    }

    /**
     * Whether the system will actually show a bubble right now. Advisory: gates the
     * notification's "bubble" button and the automatic path; the post itself still tolerates
     * a refusal because per-app flags can lag a just-changed setting.
     *
     * <p>A self-gating entry point, and deliberately the only version check here: every caller
     * (the activity's background path, the service notification's action, the post itself) reaches
     * it without one of its own, because {@link #isSupported} is what makes the API-23/24/29 calls
     * below safe. {@code SuppressLint} because lint cannot follow a gate that lives inside the
     * method — it is not a licence to call anything new here without a version of its own.
     */
    @SuppressLint("NewApi")
    public static boolean areBubblesAvailable(@NonNull Context context) {
        if (!isSupported(context)) return false;
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        if (notificationManager == null) return false;
        if (!notificationManager.areNotificationsEnabled()) return false;
        return areBubblesAllowed(notificationManager);
    }

    /**
     * Read the user's bubble preference through whichever call this framework has. Neither call
     * present → {@code false} (no feature; callers rarely get here because {@link #isSupported}
     * already refused). A runtime failure is treated as "allowed" so an advisory check can never
     * cost the user a bubble.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private static boolean areBubblesAllowed(@NonNull NotificationManager notificationManager) {
        final Method method = BUBBLE_PREFERENCE_METHOD;
        if (method == null) return false;
        try {
            Object allowed = method.invoke(notificationManager);
            return allowed instanceof Boolean && (Boolean) allowed;
        } catch (Exception e) {
            // Never let an advisory check take the feature down; assume allowed and let the post
            // itself decide.
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read bubble settings, assuming allowed", e);
            return true;
        }
    }

    /**
     * Create the notification channel used by bubbles, if missing. Separate from the
     * foreground-service channel (bubble channels must be cancellable, not ongoing).
     *
     * <p><b>{@code setAllowBubbles(true)} is advisory only.</b> The platform discards it for
     * non-system apps: {@code PreferencesHelper.createNotificationChannel} always leaves a fresh
     * channel at {@code DEFAULT_ALLOW_BUBBLE} (-1), and {@code canBubble()} only returns true for
     * {@code ALLOW_BUBBLE_ON} — so a -1 channel does <em>not</em> bubble (confirmed on device).
     * The real switch is the user's per-app preference, read by {@link #areBubblesAvailable};
     * the call is kept only because some ROMs honour it.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    public static void createNotificationChannel(@NonNull Context context) {
        if (sBubbleChannelCreated) return;
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
        sBubbleChannelCreated = true;
    }

    /**
     * Publish the long-lived sharing shortcut the bubble is associated with, then return its id.
     * Mandatory only for API 30+ targets (this app targets 28), but SystemUI also uses the shortcut
     * id as the bubble identity, and some platforms resolve the bubble's launch intent from its
     * shortcut — so the shortcut carries the real bubble intent, not a placeholder.
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
     * <p>Takes a plain {@link Context}: callers sit in different lifecycle positions (activity
     * callback vs. broadcast receiver). Background posting works because the notification is a
     * conversation; suppression is decided in {@link #showBubbleInternal} since the background path
     * cannot use it. No session is named on purpose — the bubble hosts a second
     * {@link TermuxBubbleActivity} that resolves its own session like the full-screen window, and a
     * handed-over session handle would fight the window's tab handling.
     *
     * @param sessionTitle human readable title, used for the notification and shortcut label.
     * @return {@code true} if the notification was posted (SystemUI still decides bubble vs. plain).
     *
     * <p>The other self-gating entry point, for the same reason as {@link #areBubblesAvailable}:
     * the {@link #isSupported} test below is the version check, the callers that have none of their
     * own (the bubble button's receiver, the debug hook) depend on it, and lint cannot follow a gate
     * that lives inside the method.
     */
    @SuppressLint("NewApi")
    public static boolean showBubble(@NonNull Context context, @NonNull CharSequence sessionTitle) {
        if (!isSupported(context)) return false;

        logCaller();

        try {
            return showBubbleInternal(context, sessionTitle);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to post bubble notification", e);
            return false;
        }
    }

    /**
     * {@link PendingIntent#FLAG_IMMUTABLE} from API 23, and 0 below it, where every intent is
     * mutable by default anyway. Used for both this class's own intents and to match the
     * notification dispatcher's, so the two do not answer the mutability question differently on an
     * old device.
     */
    private static int immutableFlag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    /**
     * Log the first stack frame outside this class, so "who opened the bubble" is answerable from
     * logcat. Three entry points (notification button, activity lifecycle, debug adb hook) all end
     * in the same {@code notify()} and are otherwise indistinguishable; posting is rare, so the
     * stack walk is free in practice.
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
        // Single PendingIntent: one bubble per app; re-posting refreshes rather than stacks.
        // FLAG_UPDATE_CURRENT keeps the intent current when the title changes.
        //
        // This one MUST be mutable (documented exception to FLAG_IMMUTABLE): SystemUI fills in the
        // launch parameters by mutating the intent, and NotificationManagerService rejects an
        // immutable one outright ("PendingIntents attached to bubbles must be mutable" from
        // checkDisqualifyingFeatures → RemoteException → "nothing happened" unless logged).
        // FLAG_MUTABLE only exists from API 31; below that PendingIntents are mutable by default.
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
            contentTarget, PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());

        Intent deleteTarget = new Intent(context, TermuxBubbleReceiver.class)
            .setAction(ACTION_BUBBLE_DISMISSED);
        PendingIntent deleteIntent = PendingIntent.getBroadcast(context, REQUEST_CODE_DELETE,
            deleteTarget, PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());

        // Intent-based builder (not Builder(String), API 30/deprecated 31): that overload makes
        // SystemUI resolve the launch intent from the shortcut and ignore the intent handed to it.
        // The shortcut is still published and referenced — that is where the long-lived-sharing
        // association is read from.
        //
        // Two shapes exist and only the older one is on API 29: verified in AOSP, android-10's
        // BubbleMetadata.Builder has only the no-arg constructor + setIntent()/setIcon();
        // Builder(PendingIntent, Icon) first appears in android-11, so the two-arg call would throw
        // NoSuchMethodError on Android 10 — the same failure class as areBubblesEnabled(). The
        // no-arg form is deprecated from API 30 but not removed, and both set the same fields.
        final Icon bubbleIcon = Icon.createWithResource(context, R.mipmap.ic_launcher);
        Notification.BubbleMetadata.Builder bubbleMetadata;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bubbleMetadata = new Notification.BubbleMetadata.Builder(bubbleIntent, bubbleIcon);
        } else {
            bubbleMetadata = new Notification.BubbleMetadata.Builder()
                .setIntent(bubbleIntent)
                .setIcon(bubbleIcon);
        }
        bubbleMetadata
            .setDesiredHeight(desiredHeightDp(context))
            .setAutoExpandBubble(true);

        // Suppress only when a bubble is guaranteed to replace the notification, or the user sees
        // nothing: check both the app-level preference and the channel flag (the platform forces
        // DEFAULT_ALLOW_BUBBLE on target apps; only the per-channel user toggle raises it).
        if (areBubblesAllowed(notificationManager) && channelCanBubble(context)) {
            bubbleMetadata.setSuppressNotification(true);
        }
        // No guard: this whole method is @RequiresApi(Q) and setDeleteIntent is API 29 (verified in
        // android-10's api-versions, where Notification$BubbleMetadata$Builder carries no `since`
        // for it). It used to sit behind an SDK_INT >= R check, which is why Android 10 never
        // reported a dismissal — sBubblePosted then stayed set for the life of the process, and the
        // dispatcher raised no further bubble.
        bubbleMetadata.setDeleteIntent(deleteIntent);

        Person self = new Person.Builder()
            .setName(TermuxConstants.TERMUX_APP_NAME)
            .setImportant(true)
            .build();

        // MessagingStyle + Person is one of the documented ways for a bubble to be accepted, and it
        // costs nothing here. The session title is used as the message so the fallback notification
        // still says something useful.
        // A named user and no title or message: MessagingStyle is what makes the platform treat this
        // as a conversation, and hence what lets it bubble. The shade entry only has to exist — what
        // the user reads is the floating window it opens, which already shows the session.
        Notification.MessagingStyle style = new Notification.MessagingStyle(self);

        Notification notification = new Notification.Builder(context,
            TermuxConstants.TERMUX_BUBBLE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setColor(0xFF607D8B)
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
     * Remove the bubble, optionally forcing the service notification rebuild.
     *
     * @param stateChangedOutside {@code true} when the bubble went away without this app
     *        cancelling it — SystemUI removes the bubble's notification before delivering the
     *        dismiss intent, so {@code isBubblePosted} reads {@code false} in exactly the case
     *        where the service notification most needs rebuilding.
     */
    public static void cancel(@NonNull Context context, boolean stateChangedOutside) {
        if (!isSupported(context)) return;
        // Only rebuild the service notification when the state actually changes: the automatic
        // path calls this on every resume, and re-posting each time would be pure churn.
        if (detachBubble(context) || stateChangedOutside) requestServiceNotificationRefresh(context);
    }

    /**
     * Remove the bubble as part of the service shutting down, asking for no notification rebuild.
     *
     * <p>The notification the rebuild would restore is being torn down on this same path, and the
     * rebuild is delivered asynchronously — so it landed after {@code stopForeground()}, re-posting
     * a notification whose sessions had already been killed.
     */
    public static void cancelForShutdown(@NonNull Context context) {
        if (!isSupported(context)) return;
        detachBubble(context);
    }

    /**
     * Cancel the bubble notification and forget the posted state, reporting whether one was up.
     * Whether the service notification is then rebuilt is the caller's decision.
     *
     * <p>Private, and reached only from callers that have already passed {@link #isSupported},
     * which is what makes the typed {@code getSystemService} below safe. SuppressLint for the same
     * reason as the two public entry points: the gate is a caller's, and cannot be seen from here.
     */
    @SuppressLint("NewApi")
    private static boolean detachBubble(@NonNull Context context) {
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        if (notificationManager == null) return false;

        // Read before the cancel, obviously.
        final boolean wasPosted = isBubblePosted(context);

        notificationManager.cancel(TermuxConstants.TERMUX_BUBBLE_NOTIFICATION_ID);
        // Cleared before any refresh, so the service already reads "no bubble" when it decides
        // whether to offer the button again.
        sBubblePosted = false;
        Logger.logDebug(LOG_TAG, "Cancelled bubble notification");

        return wasPosted;
    }

    /**
     * Ask the service to rebuild its notification so the "bubble" button tracks the bubble.
     * Sent as a start command (service is {@code exported="false"}; already in the foreground, so
     * allowed from background). Carries no state — the service re-reads bubble state itself so the
     * two cannot drift. Never fatal: a missed refresh leaves a stale button, not a broken feature.
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
     * Whether a bubble is currently up.
     *
     * <p>The flag alone is enough, where {@code getActiveNotifications()} used to be consulted too:
     * a bubble SystemUI took down without telling us arrives as {@code ACTION_BUBBLE_DISMISSED},
     * which reaches {@link #cancel}, and records from an earlier process are ignored on purpose —
     * so the query could only ever confirm what the flag already knows, at the cost of a binder round
     * trip on the terminal's input thread for every notification posted.
     *
     * <p>The flag dies with the process, which takes the bubble with it. Used to avoid re-posting an
     * already-up bubble and to decide whether the service notification still offers its bubble button.
     */
    public static boolean isBubblePosted(@NonNull Context context) {
        return sBubblePosted;
    }

    /**
     * Whether the bubble channel itself is allowed to bubble. Not the same as
     * {@link #areBubblesAvailable} (app-level preference): the channel carries an independent flag
     * the app cannot set (platform overwrites {@code setAllowBubbles} for target apps and never
     * applies it on update), so a channel can read non-bubblable with app bubbles enabled — exactly
     * the state where suppression must not be requested.
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

}
