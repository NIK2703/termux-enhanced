package com.termux.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import android.view.Gravity;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.termux.TermuxConstants;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service that runs a Termux data backup or restore.
 *
 * <p>The (potentially long, multi-minute) tar operation always runs here so it is not killed
 * when the app/activity is destroyed. The UI may observe it in two ways:
 * <ul>
 *   <li><b>Dialog mode</b> (default): a progress dialog in the preferences screen polls
 *       {@link #getProgressCopied()}/{@link #getProgressTotal()}/{@link #isFinished()} while the
 *       app stays on screen.</li>
 *   <li><b>Background mode</b>: once {@link #enterBackground()} is called (by tapping the
 *       dialog's "Background" button, or automatically when the app is minimized), the service
 *       becomes a foreground service and shows a persistent notification with progress. This keeps
 *       the operation alive and visible after the app is backgrounded / the screen is locked.</li>
 * </ul>
 *
 * <p>The SAF document URI is passed via {@link Intent#setData(Uri)} together with
 * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION} / {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION}.
 * Those URI grants are held by the system for our UID and survive the activity being destroyed,
 * which is exactly what makes the operation reliable after the app is minimized.</p>
 */
public final class TermuxBackupService extends Service {

    private static final String LOG_TAG = "TermuxBackupService";

    /** Intent action to perform a backup (write the tar.gz to the given URI). */
    public static final String ACTION_BACKUP = "com.termux.app.TermuxBackupService.ACTION_BACKUP";
    /** Intent action to perform a restore (read the tar.gz from the given URI). */
    public static final String ACTION_RESTORE = "com.termux.app.TermuxBackupService.ACTION_RESTORE";
    /** Intent action to cancel the running operation (sent from the notification's cancel button). */
    public static final String ACTION_CANCEL = "com.termux.app.TermuxBackupService.ACTION_CANCEL";

    private static final String EXTRA_ESTIMATED_SIZE = "com.termux.app.TermuxBackupService.extra_estimated_size";
    private static final String EXTRA_EXCLUDE_TMP = "com.termux.app.TermuxBackupService.extra_exclude_tmp";

    /** Live instance, so the UI dialog can poll progress without binding. */
    private static volatile TermuxBackupService sInstance;
    /**
     * Result of the last finished operation, retained after {@link #onDestroy()} so the UI can
     * read it even if it polls after the service has already torn itself down. Without this a
     * successful-looking poll (svc == null) would force a false "success" when the operation
     * actually failed.
     */
    private static volatile Error sLastResult;

    private volatile boolean mFinished = false;
    private volatile boolean mInForeground = false;
    /** True only after startForeground() has actually succeeded — guards the matching stopForeground(). */
    private volatile boolean mStartedForeground = false;
    private volatile boolean mIsRestore = false;
    private volatile long mProgressCopied = 0;
    private volatile long mProgressTotal = 0;

    /** Progress keys: -2 = nothing posted yet, -1 = indeterminate, 0..100 = determinate percent. */
    private static final int PROGRESS_KEY_NONE = -2;
    private static final int PROGRESS_KEY_INDETERMINATE = -1;
    /**
     * Last progress key actually posted to NotificationManager. Throttles the per-1MB
     * re-post (the app-icon flicker on MIUI/HyperOS) to visible state changes only.
     */
    private volatile int mLastPostedProgressKey = PROGRESS_KEY_NONE;
    private final AtomicReference<Error> mResult = new AtomicReference<>();
    /** Ensures showResult() runs exactly once across worker / enterBackground races. */
    private final AtomicBoolean mResultShown = new AtomicBoolean(false);
    /** Set when the user cancels; the worker checks it and kills the tar process. */
    private final AtomicBoolean mCancelled = new AtomicBoolean(false);
    /** Bumped at the start of every operation; the auto-dismiss timer only cancels "its own" notification. */
    private final AtomicInteger mEpoch = new AtomicInteger();
    private PowerManager.WakeLock mWakeLock;
    private Thread mWorker;

    private Handler mMainHandler;
    private volatile Runnable mAutoDismissRunnable;

    // ---- Public entry points used by the preferences fragment ----

    public static void startBackup(Context context, Uri uri, long estimatedSize, boolean excludeTmp) {
        Intent intent = new Intent(context, TermuxBackupService.class)
            .setAction(ACTION_BACKUP)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .putExtra(EXTRA_ESTIMATED_SIZE, estimatedSize)
            .putExtra(EXTRA_EXCLUDE_TMP, excludeTmp);
        start(context, intent);
    }

    public static void startRestore(Context context, Uri uri, long estimatedSize) {
        Intent intent = new Intent(context, TermuxBackupService.class)
            .setAction(ACTION_RESTORE)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(EXTRA_ESTIMATED_SIZE, estimatedSize);
        start(context, intent);
    }

    private static void start(Context context, Intent intent) {
        // Started as a normal service (not foreground) so we are NOT forced to call
        // startForeground within ~5s. The UI stays on screen with a dialog; we only enter
        // foreground mode when the user taps "Background" or the app is minimized.
        context.startService(intent);
    }

    /** @return the running service instance, or null if no operation is in progress. */
    @Nullable
    public static TermuxBackupService getInstance() {
        return sInstance;
    }

    /** @return the result of the most recently finished operation (survives onDestroy). */
    @Nullable
    public static Error getLastResult() {
        return sLastResult;
    }

    /** @return true once the operation has been cancelled by the user. */
    public boolean isCancelled() { return mCancelled.get(); }

    /** Cancel the running operation: the worker observes this and kills the tar process. */
    public void cancelOperation() {
        mCancelled.set(true);
    }

    // ---- progress observers for the dialog ----

    public boolean isInForeground() { return mInForeground; }
    public boolean isRestore() { return mIsRestore; }

    /**
     * Called when the user taps the notification and the app re-opens the progress dialog.
     * Leaves foreground mode (dropping the notification and cancelling its auto-dismiss timer) so
     * the dialog becomes the single source of truth again — exactly as when the operation was
     * started. If the operation already finished, nothing to do (the result notification is gone
     * or will be replaced by the fragment's result handling).
     */
    public void returnToDialog() {
        if (!mInForeground) return;
        mInForeground = false;
        mStartedForeground = false;
        // Cancel any pending auto-dismiss so it cannot stopSelf() under the reopened dialog.
        final int epoch = mEpoch.incrementAndGet();
        if (mMainHandler != null) {
            mMainHandler.removeCallbacks(mAutoDismissRunnable);
        }
        mAutoDismissRunnable = null;
        NotificationManager nm = NotificationUtils.getNotificationManager(this);
        if (nm != null) nm.cancel(TermuxConstants.TERMUX_BACKUP_NOTIFICATION_ID);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        // The progress notification is gone; a later enterBackground() must re-post the
        // current state instead of being throttled by the stale key.
        mLastPostedProgressKey = PROGRESS_KEY_NONE;
    }
    public boolean isFinished() { return mFinished; }
    public long getProgressCopied() { return mProgressCopied; }
    public long getProgressTotal() { return mProgressTotal; }
    @Nullable
    public Error getResult() { return mResult.get(); }

    /**
     * Switch this service into foreground (notification) mode. Safe to call multiple times.
     * Typically invoked from the dialog's "Background" button or when the app is minimized.
     */
    public void enterBackground() {
        if (mInForeground) return;
        // If the operation already finished (e.g. user minimized at the very end, or pressed
        // Back while the dialog was still up), we still enter foreground mode so the result
        // notification can pop heads-up AND auto-dismiss (the timer stops the service) — exactly
        // like any other background completion. We just skip the live progress notification.
        mInForeground = true;
        setupNotificationChannels();
        // Snapshot the progress ONCE: a concurrent publishProgress() tick (the 1 MB pump
        // fires every ~10-100 ms) between two volatile reads would make the key say
        // "determinate pct" while the shade shows the indeterminate spinner, and the
        // throttle would then suppress re-posts until the next whole percent — minutes
        // on a multi-GB container.
        final long snapshotCopied = mProgressCopied;
        final long snapshotTotal = mProgressTotal;
        Notification notification = mFinished
            ? null
            : buildProgressNotification(mIsRestore, snapshotCopied, snapshotTotal);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(TermuxConstants.TERMUX_BACKUP_NOTIFICATION_ID,
                    notification != null ? notification
                        : buildResultNotification(mIsRestore, mResult.get()),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(TermuxConstants.TERMUX_BACKUP_NOTIFICATION_ID,
                    notification != null ? notification : buildResultNotification(mIsRestore, mResult.get()));
            }
            // Only flag "started" AFTER startForeground() actually succeeded, so the
            // worker-thread showResult() cannot race a stopForeground() before we are foreground.
            mStartedForeground = true;

            // startForeground() just posted this snapshot; record its key so the next
            // publishProgress() tick does not re-post the same visible state (which would
            // re-render the notification row / app icon on MIUI/HyperOS).
            if (!mFinished) {
                mLastPostedProgressKey = calculateProgressKey(snapshotCopied, snapshotTotal);
            }

            if (mFinished) {
                // Already done: surface the result immediately (head-up + auto-dismiss timer).
                showResult(mIsRestore, mResult.get());
            }
        } catch (Exception e) {
            // Foreground promotion refused (e.g. not in a permitted state on API 31+).
            // Stay in dialog-less mode; the operation still runs, just without a notification.
            Logger.logStackTraceWithMessage(LOG_TAG, "startForeground failed", e);
            mInForeground = false;
        }
    }

    // ---- Service lifecycle ----

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // The cancel PendingIntent sets ONLY the action (no data URI), so handle it BEFORE the
        // data/action presence guard below — otherwise it would be treated as a malformed start
        // and the service would stopSelf() without ever cancelling the running operation.
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelOperation();
            // Immediately remove the notification from the shade — the user asked to cancel.
            NotificationManager nm = NotificationUtils.getNotificationManager(this);
            if (nm != null) nm.cancel(TermuxConstants.TERMUX_BACKUP_NOTIFICATION_ID);
            if (mStartedForeground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
                mStartedForeground = false;
            }
            mInForeground = false;
            Toast bottomToast = Toast.makeText(this, R.string.backup_restore_cancelled, Toast.LENGTH_SHORT);
            bottomToast.setGravity(Gravity.BOTTOM, 0, 0);
            bottomToast.show();
            // If no live operation exists, clean up entirely. Otherwise the worker eventually
            // finishes and sees !mInForeground && isCancelled → stopSelf() by itself.
            if (mWorker == null || !mWorker.isAlive()) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (intent == null || intent.getAction() == null || intent.getData() == null) {
            Logger.logError(LOG_TAG, "TermuxBackupService started with missing action/uri");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (mWorker != null && mWorker.isAlive()) {
            // Already running an operation; ignore the duplicate start.
            return START_NOT_STICKY;
        }

        mEpoch.incrementAndGet(); // new operation => invalidate any pending auto-dismiss timer
        // New operation: the previous progress notification (if any) is gone; the next
        // publishProgress() must re-post the current state instead of being throttled.
        mLastPostedProgressKey = PROGRESS_KEY_NONE;
        mIsRestore = ACTION_RESTORE.equals(intent.getAction());
        final boolean isRestore = mIsRestore;
        final Uri uri = intent.getData();
        final long estimatedSize = intent.getLongExtra(EXTRA_ESTIMATED_SIZE, 0);
        final boolean excludeTmp = !isRestore && intent.getBooleanExtra(EXTRA_EXCLUDE_TMP, false);
        final AtomicReference<Error> result = new AtomicReference<>();

        sInstance = this;
        sLastResult = null;
        mMainHandler = new Handler(Looper.getMainLooper());
        final boolean fExcludeTmp = excludeTmp;
        mWorker = new Thread(() -> {
            try {
                if (isRestore) {
                    runRestore(uri, estimatedSize, result);
                } else {
                    runBackup(uri, estimatedSize, fExcludeTmp, result);
                }
            } finally {
                // Publish the result BEFORE flipping the finished flag so any reader that sees
                // mFinished == true also sees the correctly published result (safe-publication).
                mResult.set(result.get());
                sLastResult = result.get();
                mFinished = true;
                releaseWakeLock();
                // Background mode: heads-up result notification (auto-dismiss + stopSelf
                // after 8s). Dialog mode: no stopSelf here — the fragment stops the
                // service, which also avoids racing enterBackground()'s auto-dismiss timer.
                if (mInForeground) {
                    showResult(isRestore, result.get());
                } else if (mCancelled.get()) {
                    // Dialog mode + cancelled: the controller already dismissed the dialog and
                    // showed a toast, but nobody will call finish() / stopService for us after
                    // the worker finishes because the poll loop is dead. Stop the now-idle
                    // service ourselves once cleanup (rollback/delete partial) is complete.
                    stopSelf();
                }
            }
        }, "TermuxBackupWorker");
        try {
            mWorker.start();
            acquireWakeLock(); // acquire only after a successful start (avoid a leak on start() failure)
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start backup worker", t);
            releaseWakeLock();
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        if (mAutoDismissRunnable != null && mMainHandler != null) {
            mMainHandler.removeCallbacks(mAutoDismissRunnable);
        }
        // Drop any foreground state so we don't leave a dangling notification / foreground rank
        // when the service is destroyed while still in foreground mode.
        if (mStartedForeground) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
            } catch (Exception ignored) { }
            mStartedForeground = false;
            mInForeground = false;
        }
        releaseWakeLock();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- Core operations ----

    private void runBackup(Uri uri, long estimatedSize, boolean excludeTmp, AtomicReference<Error> out) {
        OutputStream os = null;
        try {
            os = getContentResolver().openOutputStream(uri);
            if (os == null) {
                out.set(new Error(getString(R.string.backup_error_open_output)));
                return;
            }
            // Run du -sb in a SEPARATE thread so it does not delay the start of the
            // tar process. Until du returns, the estimate stays 0 and the UI shows an
            // indeterminate spinner; once it lands, the progress bar switches to
            // determinate (copied vs the du-based total) without restarting tar.
            final AtomicReference<Long> estimateRef = new AtomicReference<>(0L);
            final Thread duThread = new Thread(() -> {
                long e = TermuxBackupUtils.getEstimatedBackupSize(this, excludeTmp);
                long est = e > 0 ? e : estimatedSize;
                estimateRef.set(est);
                // du just finished: if the operation is already in foreground (notification)
                // mode, push an immediate update so the shade switches from the indeterminate
                // spinner to the determinate bar right away — without waiting for the next
                // 1MB progress tick from the data pump. Harmless if still in dialog mode.
                if (est > 0 && mInForeground && !mFinished) {
                    publishProgress(false, mProgressCopied, est);
                }
            }, "BackupDuEstimate");
            duThread.start();
            final Error[] holder = new Error[1];
            TermuxBackupUtils.backup(this, os, error -> holder[0] = error,
                (copied, total) -> {
                    long est = estimateRef.get();
                    // Pass the pump-provided total through when the du estimate is not
                    // available: the data pump's end-of-stream snap (total = bytesCopied)
                    // then still flips the bar to a determinate 100% instead of leaving
                    // it spinning when du failed or timed out.
                    publishProgress(false, copied, est > 0 ? est : total);
                },
                mCancelled, excludeTmp);
            // The data pump inside TermuxBackupUtils.backup() -> runTar() already closed 'os'.
            // Prevent the finally block from closing it again.
            os = null;
            if (holder[0] != null) {
                // A cancelled or failed backup must not leave a partial destination file behind.
                try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
                out.set(holder[0]);
            }
        } catch (IOException | RuntimeException e) {
            try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
            // Don't overwrite a successful backup result with a close error:
            // the data pump may have already closed 'os', and an IOException on
            // double-close must not be reported as a backup failure.
            if (out.get() == null) {
                out.set(new Error(e.getMessage(), e));
            }
        } finally {
            // Close if backup() never started the data pump (e.g. checkTarHealth failed),
            // or if an exception occurred before os was handed to the data pump.
            if (os != null) {
                try { os.close(); } catch (IOException ignored) { }
            }
        }
    }

    private void runRestore(Uri uri, long estimatedSize, AtomicReference<Error> out) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                out.set(new Error("Failed to open input file"));
                return;
            }
            final Error[] holder = new Error[1];
            TermuxBackupUtils.restore(this, is, error -> holder[0] = error,
                (copied, total) -> publishProgress(true, copied, total > 0 ? total : estimatedSize),
                mCancelled);
            out.set(holder[0]);
        } catch (IOException | RuntimeException e) {
            out.set(new Error(e.getMessage(), e));
        }
    }

    // ---- Notification handling ----

    private void setupNotificationChannels() {
        // Two channels: progress is SILENT/LOW (no heads-up spam during updates),
        // the result channel is HIGH so the completion notification actually pops heads-up.
        NotificationUtils.setupNotificationChannel(this,
            TermuxConstants.TERMUX_BACKUP_PROGRESS_NOTIFICATION_CHANNEL_ID,
            getString(R.string.backup_service_channel_name),
            NotificationManager.IMPORTANCE_LOW);
        NotificationUtils.setupNotificationChannel(this,
            TermuxConstants.TERMUX_BACKUP_RESULT_NOTIFICATION_CHANNEL_ID,
            getString(R.string.backup_service_channel_name),
            NotificationManager.IMPORTANCE_HIGH);
    }

    private PendingIntent contentIntent() {
        Intent launch = new Intent(this, BackupDialogActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    private PendingIntent cancelIntent() {
        Intent cancel = new Intent(this, TermuxBackupService.class)
            .setAction(ACTION_CANCEL);
        return PendingIntent.getService(this, 1, cancel,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    private Notification buildProgressNotification(boolean isRestore, long copied, long total) {
        CharSequence title = getString(isRestore
            ? R.string.backup_service_notification_restore_title
            : R.string.backup_service_notification_title);
        // Show the calculated percentage for both restore and backup. For backup the
        // total comes from the parallel du estimate and may be 0 until du finishes —
        // in that window we show a "calculating" hint instead of an empty text.
        CharSequence text;
        if (total > 0) {
            int pct = (int) Math.min(copied * 100 / total, 100);
            text = getString(R.string.backup_service_notification_progress, pct);
        } else {
            text = getString(R.string.backup_progress_calculating_size);
        }

        Notification.Builder builder = NotificationUtils.geNotificationBuilder(this,
            TermuxConstants.TERMUX_BACKUP_PROGRESS_NOTIFICATION_CHANNEL_ID,
            Notification.PRIORITY_LOW, title, text, text,
            contentIntent(), null, NotificationUtils.NOTIFICATION_MODE_SILENT);
        if (builder == null) {
            builder = new Notification.Builder(this)
                .setChannelId(TermuxConstants.TERMUX_BACKUP_PROGRESS_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title).setContentText(text);
        }
        builder.setSmallIcon(R.drawable.ic_service_notification)
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.backup_service_notification_cancel), cancelIntent());
        if (total > 0) {
            // Determinate bar with known progress (restore: archive size;
            // backup: the parallel du estimate, once it has finished).
            int pct = (int) Math.min(copied * 100 / total, 100);
            builder.setProgress(100, pct, false);
        } else {
            // Total still unknown (du estimate not ready) — indeterminate spinner.
            builder.setProgress(0, 0, true);
        }
        return builder.build();
    }

    /**
     * Map the current copy progress to the notification's visible state key:
     * {@link #PROGRESS_KEY_INDETERMINATE} while the total is unknown (du estimate pending),
     * otherwise the integer percent clamped to 0..100. Used to throttle {@code notify()}
     * calls to actual visible state changes.
     */
    private static int calculateProgressKey(long copied, long total) {
        if (total <= 0) {
            return PROGRESS_KEY_INDETERMINATE;
        }
        long pct = (copied * 100L) / total;
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        return (int) pct;
    }

    private void publishProgress(boolean isRestore, long copied, long total) {
        if (mFinished) return;

        // Progress fields are updated on EVERY tick: the progress dialog polls these
        // directly and must stay smooth even when the notification is throttled.
        mProgressCopied = copied;
        mProgressTotal = total;

        if (!mInForeground) return; // dialog observes these fields directly

        // Re-post the notification only when the visible state actually changed
        // (indeterminate -> determinate, or a whole new percent). Posting on every
        // 1MB tick makes MIUI/HyperOS re-render the whole notification row (including
        // the app icon) hundreds of times per operation — the observed flicker.
        int key = calculateProgressKey(copied, total);
        if (key == mLastPostedProgressKey) return;

        NotificationManager nm = NotificationUtils.getNotificationManager(this);
        if (nm == null) return;

        nm.notify(TermuxConstants.TERMUX_BACKUP_NOTIFICATION_ID,
            buildProgressNotification(isRestore, copied, total));

        mLastPostedProgressKey = key;
    }

    /** Build the final (success / failed / cancelled) heads-up notification, without posting it. */
    private Notification buildResultNotification(boolean isRestore, @Nullable Error error) {
        boolean cancelled = error == TermuxBackupUtils.CANCELLED_ERROR;
        boolean success = error == null;
        CharSequence title = cancelled
            ? getString(R.string.backup_restore_cancelled)
            : success
                ? getString(isRestore ? R.string.backup_service_notification_restore_success
                                      : R.string.backup_service_notification_success)
                : getString(isRestore ? R.string.backup_service_notification_restore_failed
                                      : R.string.backup_service_notification_failed);
        CharSequence text = (success || cancelled) ? null : Error.getMinimalErrorString(error);

        // Result notification: HIGH priority + sound so it pops heads-up. Progress bar cleared.
        Notification.Builder builder = NotificationUtils.geNotificationBuilder(this,
            TermuxConstants.TERMUX_BACKUP_RESULT_NOTIFICATION_CHANNEL_ID,
            Notification.PRIORITY_HIGH,
            title, text, text,
            contentIntent(), null, NotificationUtils.NOTIFICATION_MODE_SOUND);
        if (builder == null) {
            builder = new Notification.Builder(this)
                .setChannelId(TermuxConstants.TERMUX_BACKUP_RESULT_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title).setContentText(text);
        }
        return builder.setSmallIcon(success ? R.drawable.ic_service_notification : R.drawable.ic_error_notification)
            .setOngoing(false)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .build();
    }

    private void showResult(boolean isRestore, @Nullable Error error) {
        // Run at most once (worker and enterBackground() can both race to call this).
        if (!mResultShown.compareAndSet(false, true)) return;

        NotificationManager nm = NotificationUtils.getNotificationManager(this);
        if (nm == null) return;

        // For a restore we intentionally do NOT pop a heads-up completion notification — the
        // bottom Toast (posted below) is the only user-facing result. Just drop the live progress
        // notification so it disappears from the shade the moment the operation ends.
        nm.cancel(TermuxConstants.TERMUX_BACKUP_NOTIFICATION_ID);

        if (!isRestore) {
            // Backup: post the result as a fresh, high-priority heads-up notification. Posting it
            // AFTER cancelling the progress one guarantees the system treats it as a NEW
            // notification and actually pops the heads-up — updating an in-place notification
            // often suppresses it.
            nm.notify(TermuxConstants.TERMUX_BACKUP_NOTIFICATION_ID,
                buildResultNotification(isRestore, error));
        }

        // Leave foreground only if we actually entered it (else stopForeground() would throw).
        if (mStartedForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                stopForeground(STOP_FOREGROUND_DETACH);
            } else {
                stopForeground(false);
            }
        }

        // Safety net: auto-dismiss the result notification if the user does not tap it.
        // The teardown (stopSelf) happens INSIDE this runnable, after the timer fires, so
        // onDestroy() (which cancels the timer) no longer defeats it. An epoch guard makes
        // sure a stale timer cannot cancel a newer operation's notification.
        final int id = TermuxConstants.TERMUX_BACKUP_NOTIFICATION_ID;
        final int epoch = mEpoch.get();
        final Context appCtx = getApplicationContext();
        mAutoDismissRunnable = () -> {
            if (mEpoch.get() != epoch) return; // a newer operation started; leave its notification alone
            NotificationManager later = NotificationUtils.getNotificationManager(appCtx);
            if (later != null) later.cancel(id);
            stopSelf();
        };
        if (mMainHandler != null) {
            mMainHandler.postDelayed(mAutoDismissRunnable, 8000);
        }

        // Also surface the result as a bottom Toast on the service's main looper, so it
        // shows even when no activity is visible. On API 26+ a background Toast may be
        // throttled — the heads-up notification above is the guaranteed fallback.
        final CharSequence toastText = buildResultToastText(isRestore, error);
        if (toastText != null && mMainHandler != null) {
            mMainHandler.post(() -> {
                Toast t = Toast.makeText(getApplicationContext(), toastText, Toast.LENGTH_LONG);
                t.setGravity(Gravity.BOTTOM, 0, 0);
                t.show();
            });
        }
    }

    /** Build the bottom-Toast text for a finished operation, or null if there is nothing to say. */
    @Nullable
    private CharSequence buildResultToastText(boolean isRestore, @Nullable Error error) {
        if (error == TermuxBackupUtils.CANCELLED_ERROR) {
            return getString(R.string.backup_restore_cancelled);
        } else if (error == null) {
            return getString(isRestore
                ? R.string.backup_service_notification_restore_success
                : R.string.backup_service_notification_success);
        } else {
            return getString(isRestore
                    ? R.string.backup_service_notification_restore_failed
                    : R.string.backup_service_notification_failed)
                + ": " + Error.getMinimalErrorString(error);
        }
    }

    // ---- Wake lock ----

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Termux:BackupRestore");
            mWakeLock.acquire(); // no timeout — released explicitly in releaseWakeLock()
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to acquire wake lock", e);
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            try { mWakeLock.release(); } catch (Exception ignored) { }
            mWakeLock = null;
        }
    }
}
