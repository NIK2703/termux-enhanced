package com.termux.app;

import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.termux.R;
import com.termux.shared.errors.Error;

import java.lang.ref.WeakReference;

/**
 * Drives the backup/restore progress dialog and its polling loop, independent of which screen the
 * app is currently showing. Both {@link DisplayPreferencesFragment} (initial launch from the
 * settings list) and {@link BackupDialogActivity} (tap on the notification, re-attaching to an
 * already-running operation) use the same controller so the behaviour is byte-for-byte identical.
 *
 * <p>The controller owns the dialog + poll loop and the run/finish/cancel/background transitions.
 * It does NOT start the service — that is done by the caller via
 * {@link TermuxBackupService#startBackup} / {@link TermuxBackupService#startRestore} (or, when
 * re-attaching, the service is already running and {@link #reopen(FragmentActivity)} just shows the
 * dialog over it).
 */
public final class BackupProgressController {

    /** Called when the dialog is fully closed (cancelled / finished / dismissed). Lets a host
     *  Activity finish itself without affecting a host Fragment (which stays on screen). */
    public interface OnClosedListener { void onClosed(); }

    private WeakReference<FragmentActivity> mActivityRef;
    private final boolean mFinishHostOnClose;
    @Nullable private final OnClosedListener mOnClosed;

    private ProgressDialog mBackupDialog;
    private Handler mBackupPoll;
    private boolean mBackupIsRestore;
    private boolean mBackupLaunchTermuxOnSuccess;
    private long mBackupEstimated;
    private boolean mLastIndeterminate; // guard to avoid per-tick setIndeterminate view churn

    public BackupProgressController(@NonNull FragmentActivity activity,
                                   boolean finishHostOnClose,
                                   @Nullable OnClosedListener onClosed) {
        mActivityRef = new WeakReference<>(activity);
        mFinishHostOnClose = finishHostOnClose;
        mOnClosed = onClosed;
    }

    /** Start a fresh operation behind the dialog and launch the service. */
    public void start(int titleRes, long totalBytes,
                      boolean launchTermuxOnSuccess, boolean isRestore, android.net.Uri uri) {
        start(titleRes, totalBytes, launchTermuxOnSuccess, isRestore, uri, false);
    }

    /** Start a fresh operation behind the dialog and launch the service. */
    public void start(int titleRes, long totalBytes,
                      boolean launchTermuxOnSuccess, boolean isRestore, android.net.Uri uri,
                      boolean excludeTmp) {
        FragmentActivity activity = mActivityRef.get();
        if (activity == null) return;
        mBackupIsRestore = isRestore;
        mBackupLaunchTermuxOnSuccess = launchTermuxOnSuccess;
        mBackupEstimated = totalBytes;

        if (isRestore) {
            TermuxBackupService.startRestore(activity, uri, totalBytes);
        } else {
            TermuxBackupService.startBackup(activity, uri, totalBytes, excludeTmp);
        }
        showDialog(titleRes);
    }

    /**
     * Re-attach to an ALREADY-RUNNING operation and show its progress dialog over whatever screen
     * is currently visible (terminal, settings, or from the background). The service drops its
     * notification so the dialog becomes the single source of truth again — exactly as when the
     * operation was first started. If the service is gone or already finished, we report/clean up
     * without spawning a dead dialog.
     */
    public void reopen(@NonNull FragmentActivity activity) {
        // The hosting activity may have been recreated (e.g. after a config change / background
        // kill) — refresh the reference so the dialog shows in the current context.
        mActivityRef = new WeakReference<>(activity);
        TermuxBackupService svc = TermuxBackupService.getInstance();
        if (svc == null) {
            // Service already torn down: report whatever result it published (survives onDestroy).
            finish();
            return;
        }
        svc.returnToDialog(); // drop the notification; dialog takes over
        mBackupIsRestore = svc.isRestore();
        mBackupLaunchTermuxOnSuccess = svc.isRestore(); // restore re-opens termux on success
        mBackupEstimated = 0;

        if (svc.isFinished()) {
            // The operation already completed. If it finished in background mode the result was
            // already surfaced as a heads-up notification (and auto-dismissed after 8s); do NOT
            // reproduce a duplicate bottom Toast here. Just stop the now-idle service and close.
            svc.stopSelf();
            dismiss();
            notifyClosed();
            return;
        }

        showDialog(mBackupIsRestore
            ? R.string.backup_service_notification_restore_title
            : R.string.backup_service_notification_title);
    }

    private void showDialog(int titleRes) {
        FragmentActivity activity = mActivityRef.get();
        if (activity == null || activity.isFinishing()) return;

        final ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle(activity.getString(titleRes));
        // Both backup and restore use a horizontal bar.
        // Backup: indeterminate throughout (tar stream, size unknown).
        // Restore: starts indeterminate, switches to determinate once progress is calculated.
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setIndeterminate(true);
        // While the total is unknown (backup: parallel du estimate; restore: SAF size
        // unavailable) show a "calculating size" hint so the empty spinner does not look
        // like the operation is stuck.
        if (mBackupEstimated <= 0) {
            progress.setMessage(activity.getString(R.string.backup_progress_calculating_size));
        }
        // Start indeterminate (du estimate may still be computing). Once the service
        // publishes a non-zero total, the poll loop below flips to a determinate bar
        // for both backup and restore. Hide the percentage labels only while the
        // estimate is unknown so we don't show a meaningless "0% / 0 of 100".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            progress.setProgressNumberFormat(null);
            progress.setProgressPercentFormat(null);
        }
        progress.setCancelable(false);
        progress.setButton(DialogInterface.BUTTON_NEUTRAL,
            activity.getString(R.string.backup_dialog_run_in_background), (d, which) -> goBackground());
        progress.setButton(DialogInterface.BUTTON_NEGATIVE,
            activity.getString(R.string.backup_dialog_cancel), (d, which) -> cancel());
        mBackupDialog = progress;
        progress.show();
        mLastIndeterminate = true;

        mBackupPoll = new Handler(Looper.getMainLooper());
        mBackupPoll.post(mBackupPollRunnable);
    }

    private final Runnable mBackupPollRunnable = new Runnable() {
        @Override
        public void run() {
            FragmentActivity activity = mActivityRef.get();
            TermuxBackupService svc = TermuxBackupService.getInstance();
            if (svc == null || svc.isFinished()) {
                finish();
                return;
            }
            // Both backup and restore can show a determinate bar once a non-zero
            // total is known (backup: du estimate; restore: archive size). Until then
            // the dialog stays indeterminate.
            if (mBackupDialog != null && mBackupDialog.isShowing()) {
                long copied = svc.getProgressCopied();
                long total = svc.getProgressTotal();
                long effective = total > 0 ? total : mBackupEstimated;
                if (effective > 0) {
                    if (mLastIndeterminate) {
                        mBackupDialog.setIndeterminate(false);
                        // Drop the "calculating size" hint once progress is meaningful.
                        mBackupDialog.setMessage(null);
                        // Show a plain percentage (e.g. "10%") once progress is meaningful.
                        // On LOLLIPOP_MR1+ we clear the "current / max" number format so only
                        // the built-in percent label remains; on older versions the dialog shows
                        // the percent label by default.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                            mBackupDialog.setProgressNumberFormat(null);
                            mBackupDialog.setProgressPercentFormat(
                                java.text.NumberFormat.getPercentInstance());
                        }
                        mLastIndeterminate = false;
                    }
                    int pct = (int) Math.min(copied * 100 / effective, 100);
                    mBackupDialog.setProgress(pct);
                } else {
                    if (!mLastIndeterminate) {
                        mBackupDialog.setIndeterminate(true);
                        // Restore the hint if the total becomes unknown again.
                        mBackupDialog.setMessage(mBackupEstimated <= 0
                            ? mBackupDialog.getContext().getString(
                                R.string.backup_progress_calculating_size)
                            : null);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                            mBackupDialog.setProgressNumberFormat(null);
                            mBackupDialog.setProgressPercentFormat(null);
                        }
                        mLastIndeterminate = true;
                    }
                }
            }
            if (mBackupPoll != null) mBackupPoll.postDelayed(this, 300);
        }
    };

    /** Move the running operation into background (notification) mode and close the dialog. */
    private void goBackground() {
        TermuxBackupService svc = TermuxBackupService.getInstance();
        if (svc != null) svc.enterBackground();
        dismiss();
        // If our host is a transparent activity (BackupDialogActivity), finish it so the previous
        // screen is revealed and only the notification remains. The fragment host stays on screen.
        notifyClosed();
    }

    /** Cancel the running operation and close the dialog. */
    private void cancel() {
        TermuxBackupService svc = TermuxBackupService.getInstance();
        if (svc == null || svc.isFinished()) {
            // Race: the operation already ended before the user hit Cancel. Report the real result
            // via the normal finish path (which reads getLastResult()) so we don't silently lose
            // it or leave the service running.
            finish();
            return;
        }
        svc.cancelOperation();
        // Close the dialog immediately but leave the poll dead — the worker is still killing tar
        // and rolling back; once it finishes, the service itself will call stopSelf() (see the
        // mCancelled guard in the worker finally block).
        if (mBackupPoll != null) {
            mBackupPoll.removeCallbacks(mBackupPollRunnable);
            mBackupPoll = null;
        }
        if (mBackupDialog != null && mBackupDialog.isShowing()) {
            mBackupDialog.dismiss();
        }
        mBackupDialog = null;
        // Give immediate feedback — "Operation cancelled" toast, matching the notification path.
        FragmentActivity activity = mActivityRef.get();
        if (activity != null && !activity.isFinishing()) {
            Toast.makeText(activity, activity.getString(R.string.backup_restore_cancelled),
                Toast.LENGTH_LONG).show();
        }
        notifyClosed();
    }

    /** Tear down the dialog + poll loop but leave the service running; moves the operation to the
     *  foreground notification so it keeps running and stays visible (instead of being killed by
     *  the system or finishing silently). Called when the host activity/fragment is paused or
     *  destroyed. */
    public void detach() {
        TermuxBackupService svc = TermuxBackupService.getInstance();
        if (svc != null) svc.enterBackground();
        dismiss();
    }

    private void dismiss() {
        if (mBackupPoll != null) {
            mBackupPoll.removeCallbacks(mBackupPollRunnable);
            mBackupPoll = null;
        }
        if (mBackupDialog != null && mBackupDialog.isShowing()) {
            mBackupDialog.dismiss();
        }
        mBackupDialog = null;
    }

    /** Close the dialog (if any) and report the result via a Toast/Alert, then stop the service. */
    private void finish() {
        dismiss();
        // Read the PERSISTED result (survives the service's onDestroy), so a finished operation is
        // never misreported as success just because svc became null first.
        Error error = TermuxBackupService.getLastResult();
        FragmentActivity activity = mActivityRef.get();
        if (activity == null || activity.isFinishing()) {
            // Activity going away: if the service is still alive, hand it the result so it surfaces
            // via the (foreground) notification instead of being lost silently.
            TermuxBackupService svc = TermuxBackupService.getInstance();
            if (svc != null) svc.enterBackground();
            notifyClosed();
            return;
        }
        if (error == null) {
            Toast.makeText(activity, activity.getString(mBackupIsRestore
                    ? R.string.backup_service_notification_restore_success
                    : R.string.backup_service_notification_success),
                Toast.LENGTH_LONG).show();
            if (mBackupLaunchTermuxOnSuccess) {
                TermuxActivity.startTermuxActivityWithSessionReset(activity);
            }
        } else if (error == TermuxBackupUtils.CANCELLED_ERROR) {
            Toast.makeText(activity, activity.getString(R.string.backup_restore_cancelled),
                Toast.LENGTH_LONG).show();
        } else {
            String failMsg = activity.getString(mBackupIsRestore
                    ? R.string.backup_service_notification_restore_failed
                    : R.string.backup_service_notification_failed)
                + ": " + Error.getMinimalErrorString(error);
            Toast.makeText(activity, failMsg, Toast.LENGTH_LONG).show();
        }
        // Stop the (now idle) service — the bottom Toast already reported the result, matching the
        // upstream commit behaviour.
        activity.stopService(new android.content.Intent(activity, TermuxBackupService.class));
        notifyClosed();
    }

    private void notifyClosed() {
        if (mFinishHostOnClose && mOnClosed != null) mOnClosed.onClosed();
    }
}
