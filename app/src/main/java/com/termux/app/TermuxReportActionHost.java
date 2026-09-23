package com.termux.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.termux.R;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.models.ReportAction;
import com.termux.shared.models.ReportInfo;
import com.termux.shared.termux.models.UserAction;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adds the "back up the container" and "back up app settings" buttons to the app's crash report
 * screen — the {@link ReportActivity} that shows the crash log after a crash — so a copy of the
 * container and of the app settings can be taken right from the screen a crash leaves the user on.
 *
 * <p>Registered from {@link TermuxApplication#onCreate()} via
 * {@link ReportActivity#setReportActionHost(ReportActivity.ReportActionHost)}. The button labels are
 * the existing backup/restore settings strings, so the actions read exactly like the ones on the
 * "Backup / restore" settings screen and no new resources are needed.
 *
 * <p>Both actions reuse the existing backup machinery: the container backup is handed to
 * {@link BackupDialogActivity}, which starts {@link TermuxBackupService} and shows its progress
 * dialog over the report screen, and the settings backup runs
 * {@link TermuxSettingsBackupUtils#exportSettings} in the background.
 */
public final class TermuxReportActionHost implements ReportActivity.ReportActionHost {

    private static final String LOG_TAG = "TermuxReportActionHost";

    /** Ids of the actions contributed by this host. */
    private static final String ACTION_BACKUP_CONTAINER = "backup_container";
    private static final String ACTION_BACKUP_SETTINGS = "backup_settings";

    private static final int REQUEST_CODE_BACKUP_CONTAINER = 1101;
    private static final int REQUEST_CODE_BACKUP_SETTINGS = 1102;

    private static final String MIME_TYPE_GZIP = "application/gzip";

    /**
     * Whether the backup started from the crash report screen must skip {@code usr/tmp/}.
     *
     * <p>{@code false} — a backup taken after a crash is a rescue copy, so it contains everything,
     * which is also what the confirmation message promises ("a compressed archive of your data
     * directory"). The "Backup / restore" settings screen asks separately and offers the choice.
     */
    private static final boolean EXCLUDE_TMP_FROM_REPORT_BACKUP = false;

    @Nullable
    @Override
    public List<ReportAction> getReportActions(@NonNull Activity activity, @NonNull ReportInfo reportInfo) {
        // Only the crash report screen gets these buttons; all other reports (About, "report issue
        // from transcript", plugin reports) stay exactly as they were.
        if (!UserAction.CRASH_REPORT.getName().equals(reportInfo.userAction)) return null;

        List<ReportAction> actions = new ArrayList<>(2);
        actions.add(new ReportAction(ACTION_BACKUP_CONTAINER,
            activity.getString(R.string.backup_preference_title)));
        actions.add(new ReportAction(ACTION_BACKUP_SETTINGS,
            activity.getString(R.string.backup_settings_title)));
        return actions;
    }

    @Override
    public void onReportActionClicked(@NonNull Activity activity, @NonNull ReportInfo reportInfo,
                                      @NonNull ReportAction action) {
        if (ACTION_BACKUP_CONTAINER.equals(action.id)) {
            confirm(activity, R.string.backup_restore_warning_backup,
                () -> chooseDestinationFile(activity, REQUEST_CODE_BACKUP_CONTAINER,
                    MIME_TYPE_GZIP, defaultContainerBackupFilename()));
        } else if (ACTION_BACKUP_SETTINGS.equals(action.id)) {
            confirm(activity, R.string.settings_backup_warning,
                () -> chooseDestinationFile(activity, REQUEST_CODE_BACKUP_SETTINGS,
                    TermuxSettingsBackupUtils.MIME_TYPE_ZIP, defaultSettingsBackupFilename()));
        }
    }

    @Override
    public void onReportActionActivityResult(@NonNull Activity activity, @NonNull ReportInfo reportInfo,
                                            int requestCode, int resultCode, @Nullable Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();

        if (requestCode == REQUEST_CODE_BACKUP_CONTAINER) {
            // The report screen has no progress UI of its own: hand the destination over to the
            // transparent BackupDialogActivity, which starts the service and shows its progress
            // dialog over this screen, then finishes itself when the operation ends.
            BackupDialogActivity.startBackup(activity, uri, EXCLUDE_TMP_FROM_REPORT_BACKUP);
        } else if (requestCode == REQUEST_CODE_BACKUP_SETTINGS) {
            exportSettings(activity.getApplicationContext(), uri);
        }
    }

    // ------------------------------------------------------------------
    // Settings export
    // ------------------------------------------------------------------

    /**
     * Export the app settings to {@code uri} on a background thread and report the outcome with
     * toasts. The report screen owns no progress dialog (and the export is small — preferences plus
     * a few config files), so this keeps the whole operation independent of the screen's lifetime.
     */
    private static void exportSettings(@NonNull final Context appContext, @NonNull final Uri uri) {
        Toast.makeText(appContext, appContext.getString(R.string.settings_backup_progress),
            Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            Error error = null;
            try (OutputStream os = appContext.getContentResolver().openOutputStream(uri)) {
                if (os == null) {
                    error = new Error(appContext.getString(R.string.backup_error_open_output));
                } else {
                    final Error[] result = new Error[1];
                    TermuxSettingsBackupUtils.exportSettings(appContext, os,
                        e -> result[0] = e, null, new AtomicBoolean(false));
                    error = result[0];
                }
            } catch (IOException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open settings backup destination", e);
                error = new Error(e.getMessage(), e);
            }

            final Error result = error;
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(appContext,
                result == null
                    ? appContext.getString(R.string.settings_backup_success)
                    : appContext.getString(R.string.settings_backup_failed)
                        + ": " + Error.getMinimalErrorString(result),
                Toast.LENGTH_LONG).show());
        }, "ReportSettingsExport").start();
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /** Show the same warning dialog the "Backup / restore" settings screen shows before an action. */
    private static void confirm(@NonNull Activity activity, int messageRes, @NonNull Runnable onConfirm) {
        new AlertDialog.Builder(activity)
            .setTitle(R.string.backup_restore_dialog_title)
            .setMessage(messageRes)
            .setPositiveButton(android.R.string.ok, (d, which) -> onConfirm.run())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static void chooseDestinationFile(@NonNull Activity activity, int requestCode,
                                              @NonNull String mimeType, @NonNull String defaultFileName) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, defaultFileName);
        activity.startActivityForResult(intent, requestCode);
    }

    /** The same file name the "Backup / restore" settings screen offers for a container backup. */
    private static String defaultContainerBackupFilename() {
        return "termux-backup-" + currentTimestamp() + ".tar.gz";
    }

    /** The same file name the "Backup / restore" settings screen offers for a settings backup. */
    private static String defaultSettingsBackupFilename() {
        return TermuxSettingsBackupUtils.DEFAULT_FILENAME_PREFIX + currentTimestamp() + ".zip";
    }

    private static String currentTimestamp() {
        final Date now = new Date();
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
            + "_" + new SimpleDateFormat("HH-mm", Locale.US).format(now);
    }

}
