package com.termux.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;

/**
 * Lightweight transparent activity that hosts the backup/restore progress dialog. It is used in two
 * ways:
 *
 * <ul>
 *   <li><b>Re-attach</b> — launched by tapping the backup/restore notification. It shows the live
 *       progress dialog OVER whatever screen the app is currently on (terminal, settings, or
 *       launched from the background) and re-attaches to the running {@link TermuxBackupService}.
 *       It does NOT open the settings screen — the dialog is the only thing it adds.</li>
 *   <li><b>Start</b> — launched with a destination URI by a screen that has no progress UI of its
 *       own (see {@link #startBackup(Context, Uri, boolean)} and
 *       {@link TermuxReportActionHost}): it starts the operation itself and shows its progress
 *       dialog over the current screen.</li>
 * </ul>
 *
 * Once the dialog closes (finished, cancelled, or backgrounded) this activity finishes itself so the
 * previous screen is revealed unchanged underneath.
 */
public final class BackupDialogActivity extends AppCompatActivity {

    /** Extra for {@link #startBackup(Context, Uri, boolean)}: skip {@code usr/tmp/} in the backup. */
    public static final String EXTRA_EXCLUDE_TMP = "exclude_tmp";

    private BackupProgressController mController;

    /**
     * Start a NEW backup of the container into {@code uri} and show its progress dialog over the
     * current screen. Unlike the notification path, the operation is started here rather than
     * re-attached to, so the dialog can never be shown before the operation exists.
     */
    public static void startBackup(@NonNull Context context, @NonNull Uri uri, boolean excludeTmp) {
        Intent intent = new Intent(context, BackupDialogActivity.class)
            .setData(uri)
            .putExtra(EXTRA_EXCLUDE_TMP, excludeTmp);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No content view: this activity is fully transparent and only hosts the progress dialog.
        mController = new BackupProgressController(this, true, this::finish);

        // A destination URI means "start a backup into it"; no URI means "re-attach to the running
        // operation" (the notification's PendingIntent carries no data).
        final Intent intent = getIntent();
        final Uri startUri = intent != null ? intent.getData() : null;
        if (startUri != null) {
            mController.start(R.string.backup_restore_backup_started, 0L, false, false, startUri,
                intent.getBooleanExtra(EXTRA_EXCLUDE_TMP, false));
        } else {
            mController.reopen(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // SingleTop re-delivery: if the activity is reused (e.g. user tapped the notification
        // twice quickly), re-attach to the running operation. The controller's reopen() is
        // idempotent for an already-active dialog and cleanly handles the finished case.
        if (mController != null) mController.reopen(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // After a config change (rotation) or process-bg-kill the activity is recreated or
        // restored. If the operation is still running in background mode, pull it back to the
        // dialog. No-op if the operation already finished — the result was already surfaced
        // as a heads-up notification.
        TermuxBackupService svc = TermuxBackupService.getInstance();
        if (svc != null && svc.isInForeground() && !svc.isFinished()) {
            mController = new BackupProgressController(this, true, this::finish);
            mController.reopen(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // If the dialog is still up when we are paused (e.g. user tapped "Background" or the app
        // was minimized), move the operation to the notification and let this host finish.
        if (mController != null) mController.detach();
    }

    @Override
    protected void onDestroy() {
        if (mController != null) mController.detach();
        super.onDestroy();
    }
}
