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
 * app is currently showing. Both {@link DisplayPreferencesFragment} (settings list) and
 * {@link BackupDialogActivity} (notification tap, re-attaching to a running operation) use this
 * same controller so the behaviour is identical. It owns the dialog + poll loop and the
 * run/finish/cancel/background transitions, but does NOT start the service — that is done by the
 * caller via {@link TermuxBackupService#startBackup} / {@link TermuxBackupService#startRestore}
 * (or, when re-attaching, the service is already running and {@link #reopen(FragmentActivity)}
 * just shows the dialog over it).
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
     * Re-attach to an ALREADY-RUNNING operation and show its dialog over the current screen.
     * The service drops its notification so the dialog is the single source of truth again.
     * If the service is gone or already finished, clean up without spawning a dead dialog.
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
            // Finished (possibly in background, where a heads-up notification already reported
            // the result): stop the idle service and close — do not duplicate the toast here.
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
        // Horizontal bar; indeterminate until the service publishes a non-zero total.
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setIndeterminate(true);
        // Unknown total: show a "calculating size" hint so the empty spinner does not look stuck.
        if (mBackupEstimated <= 0) {
            progress.setMessage(activity.getString(R.string.backup_progress_calculating_size));
        }
        // Hide the number/percent labels while the estimate is unknown — no meaningless "0 of 100".
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
            // Determinate once a non-zero total is known (backup: du estimate; restore: archive
            // size); until then the dialog stays indeterminate.
            if (mBackupDialog != null && mBackupDialog.isShowing()) {
                long copied = svc.getProgressCopied();
                long total = svc.getProgressTotal();
                long effective = total > 0 ? total : mBackupEstimated;
                if (effective > 0) {
                    if (mLastIndeterminate) {
                        mBackupDialog.setIndeterminate(false);
                        mBackupDialog.setMessage(null);
                        // LOLLIPOP_MR1+: clear the "current / max" number format so only the
                        // built-in percent label remains (older versions show it by default).
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
        // Transparent BackupDialogActivity host: finish it so only the notification remains
        // (the fragment host stays on screen).
        notifyClosed();
    }

    /** Cancel the running operation and close the dialog. */
    private void cancel() {
        TermuxBackupService svc = TermuxBackupService.getInstance();
        // Cancel raced the worker ending: report the real result via finish() (reads
        // getLastResult()) so it is not lost and the service does not linger.
        if (svc == null || svc.isFinished()) {
            finish();
            return;
        }
        svc.cancelOperation();
        // Close the dialog but keep the poll dead — the worker is still killing tar and
        // rolling back; once it finishes the service calls stopSelf() itself (mCancelled
        // guard in the worker's finally block).
        dismiss();
        // Give immediate feedback — "Operation cancelled" toast, matching the notification path.
        FragmentActivity activity = mActivityRef.get();
        if (activity != null && !activity.isFinishing()) {
            Toast.makeText(activity, activity.getString(R.string.backup_restore_cancelled),
                Toast.LENGTH_LONG).show();
        }
        notifyClosed();
    }

    /** Tear down the dialog + poll loop but move the operation to the foreground notification so
     *  the service keeps running instead of being killed silently. Called when the host
     *  activity/fragment is paused or destroyed. */
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
        CharSequence toastText = TermuxBackupService.buildResultToastText(activity, mBackupIsRestore, error);
        if (toastText != null) {
            Toast.makeText(activity, toastText, Toast.LENGTH_LONG).show();
        }
        if (error == null && mBackupLaunchTermuxOnSuccess) {
            TermuxActivity.startTermuxActivityWithSessionReset(activity);
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
