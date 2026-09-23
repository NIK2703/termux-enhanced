package com.termux.app.fragments.settings.termux;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.Preference;
import com.termux.app.fragments.settings.TermuxPreferenceFragmentBase;

import com.termux.R;
import com.termux.app.BackupProgressController;
import com.termux.app.TermuxBackupService;
import com.termux.app.TermuxSettingsBackupUtils;
import com.termux.shared.errors.Error;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

@Keep
public class BackupRestorePreferencesFragment extends TermuxPreferenceFragmentBase {

    private static final String LOG_TAG = "BackupRestorePreferencesFragment";

    private static final int REQUEST_CODE_BACKUP = 1001;
    private static final int REQUEST_CODE_RESTORE = 1002;
    private static final int REQUEST_CODE_BACKUP_SETTINGS = 1003;
    private static final int REQUEST_CODE_RESTORE_SETTINGS = 1004;

    /** Owns the full-data backup/restore progress dialog (shared with BackupDialogActivity via the controller). */
    private BackupProgressController mBackupController;

    /** Settings operation dialog + guard (lightweight, no foreground service). */
    private ProgressDialog mSettingsDialog;
    private boolean mSettingsRunning;

    /** Whether to exclude usr/tmp/ from the full-data backup. */
    private boolean mExcludeTmpFromBackup;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setPreferencesFromResource(R.xml.termux_backup_restore_preferences, rootKey);

        configureBackupPreference();
        configureRestorePreference();
        configureBackupSettingsPreference();
        configureRestoreSettingsPreference();
    }

    // ---- Full-data backup (existing) ----

    private void configureBackupPreference() {
        final Preference pref = findPreference("backup_container");
        if (pref == null) return;
        pref.setOnPreferenceClickListener(preference -> {
            FragmentActivity activity = getActivity();
            if (activity == null) return true;

            android.widget.CheckBox checkBox = new android.widget.CheckBox(activity);
            checkBox.setText(R.string.backup_include_tmp_checkbox);
            checkBox.setChecked(true);

            android.widget.LinearLayout layout = new android.widget.LinearLayout(activity);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.setPadding(
                (int) (activity.getResources().getDisplayMetrics().density * 24),
                0,
                (int) (activity.getResources().getDisplayMetrics().density * 24),
                0);

            android.widget.TextView msg = new android.widget.TextView(activity);
            msg.setText(R.string.backup_restore_warning_backup);
            msg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            msg.setLineSpacing(0, 1.2f);

            layout.addView(msg);
            layout.addView(checkBox);

            new AlertDialog.Builder(activity)
                .setTitle(R.string.backup_restore_dialog_title)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    mExcludeTmpFromBackup = checkBox.isChecked();
                    startBackupFileChooser();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .show();
            return true;
        });
    }

    private void configureRestorePreference() {
        final Preference pref = findPreference("restore_container");
        if (pref == null) return;
        pref.setOnPreferenceClickListener(preference -> {
            FragmentActivity activity = getActivity();
            if (activity == null) return true;

            AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.backup_restore_dialog_title)
                .setMessage(R.string.backup_restore_warning_restore)
                .setPositiveButton(android.R.string.ok, (d, which) -> startRestoreFileChooser())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
            dialog.show();
            return true;
        });
    }

    private void startBackupFileChooser() {
        FragmentActivity activity = getActivity();
        if (activity == null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/gzip");
        intent.putExtra(Intent.EXTRA_TITLE, getDefaultBackupFilename());
        startActivityForResult(intent, REQUEST_CODE_BACKUP);
    }

    private String getDefaultBackupFilename() {
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH-mm", Locale.US);
        Date now = new Date();
        return "termux-backup-" + dateFmt.format(now) + "_" + timeFmt.format(now) + ".tar.gz";
    }

    private void startRestoreFileChooser() {
        FragmentActivity activity = getActivity();
        if (activity == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CODE_RESTORE);
    }

    // ---- Settings backup ----

    private void configureBackupSettingsPreference() {
        final Preference pref = findPreference("backup_settings_container");
        if (pref == null) return;
        pref.setOnPreferenceClickListener(preference -> {
            FragmentActivity activity = getActivity();
            if (activity == null) return true;

            new AlertDialog.Builder(activity)
                .setTitle(R.string.backup_restore_dialog_title)
                .setMessage(R.string.settings_backup_warning)
                .setPositiveButton(android.R.string.ok, (d, which) -> startBackupSettingsFileChooser())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return true;
        });
    }

    private void configureRestoreSettingsPreference() {
        final Preference pref = findPreference("restore_settings_container");
        if (pref == null) return;
        pref.setOnPreferenceClickListener(preference -> {
            FragmentActivity activity = getActivity();
            if (activity == null) return true;

            new AlertDialog.Builder(activity)
                .setTitle(R.string.backup_restore_dialog_title)
                .setMessage(R.string.settings_restore_warning)
                .setPositiveButton(android.R.string.ok, (d, which) -> startRestoreSettingsFileChooser())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return true;
        });
    }

    private void startBackupSettingsFileChooser() {
        FragmentActivity activity = getActivity();
        if (activity == null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(TermuxSettingsBackupUtils.MIME_TYPE_ZIP);
        intent.putExtra(Intent.EXTRA_TITLE, getDefaultSettingsBackupFilename());
        startActivityForResult(intent, REQUEST_CODE_BACKUP_SETTINGS);
    }

    private void startRestoreSettingsFileChooser() {
        FragmentActivity activity = getActivity();
        if (activity == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CODE_RESTORE_SETTINGS);
    }

    private String getDefaultSettingsBackupFilename() {
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH-mm", Locale.US);
        Date now = new Date();
        return TermuxSettingsBackupUtils.DEFAULT_FILENAME_PREFIX
            + dateFmt.format(now) + "_" + timeFmt.format(now) + ".zip";
    }

    // ---- Activity results ----

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != AppCompatActivity.RESULT_OK || data == null || data.getData() == null)
            return;

        Uri uri = data.getData();
        FragmentActivity activity = getActivity();
        if (activity == null) return;

        if (requestCode == REQUEST_CODE_BACKUP) {
            mBackupController = new BackupProgressController(activity, false, null);
            mBackupController.start(R.string.backup_restore_backup_started,
                0L, false, false, uri, mExcludeTmpFromBackup);
        } else if (requestCode == REQUEST_CODE_RESTORE) {
            final long totalBytes = getStreamSize(activity, uri);
            mBackupController = new BackupProgressController(activity, false, null);
            mBackupController.start(R.string.backup_restore_restore_started,
                totalBytes, true, true, uri);
        } else if (requestCode == REQUEST_CODE_BACKUP_SETTINGS) {
            runSettingsExport(activity, uri);
        } else if (requestCode == REQUEST_CODE_RESTORE_SETTINGS) {
            runSettingsImport(activity, uri);
        }
    }

    // ---- Settings export / import (lightweight, no foreground service) ----

    private void runSettingsExport(FragmentActivity activity, Uri uri) {
        if (mSettingsRunning) return;
        mSettingsRunning = true;
        mSettingsDialog = new ProgressDialog(activity);
        mSettingsDialog.setTitle(activity.getString(R.string.settings_backup_progress));
        mSettingsDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        mSettingsDialog.setIndeterminate(true);
        mSettingsDialog.setCancelable(false);
        mSettingsDialog.show();

        final Context appContext = activity.getApplicationContext();
        final String errOpenOutput = appContext.getString(R.string.backup_error_open_output);
        new Thread(() -> {
            final Error[] result = new Error[1];
            try (OutputStream os = appContext.getContentResolver().openOutputStream(uri)) {
                if (os == null) {
                    result[0] = new Error(errOpenOutput);
                } else {
                    TermuxSettingsBackupUtils.exportSettings(appContext, os,
                        error -> result[0] = error, null, new AtomicBoolean(false));
                }
            } catch (IOException e) {
                result[0] = new Error(e.getMessage(), e);
            }

            new Handler(Looper.getMainLooper()).post(this::dismissSettingsDialog);
            new Handler(Looper.getMainLooper()).post(() -> {
                mSettingsRunning = false;
                FragmentActivity a = getActivity();
                if (a == null || a.isFinishing()) return;
                if (result[0] == null) {
                    showToast(a, R.string.settings_backup_success);
                } else if (result[0] == TermuxSettingsBackupUtils.CANCELLED_ERROR) {
                    showToast(a, R.string.backup_restore_cancelled);
                } else {
                    showToast(a, a.getString(R.string.settings_backup_failed)
                        + ": " + com.termux.shared.errors.Error.getMinimalErrorString(result[0]));
                }
            });
        }, "SettingsExport").start();
    }

    private void runSettingsImport(FragmentActivity activity, Uri uri) {
        if (mSettingsRunning) return;
        mSettingsRunning = true;
        mSettingsDialog = new ProgressDialog(activity);
        mSettingsDialog.setTitle(activity.getString(R.string.settings_restore_progress));
        mSettingsDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        mSettingsDialog.setIndeterminate(true);
        mSettingsDialog.setCancelable(false);
        mSettingsDialog.show();

        final Context appContext = activity.getApplicationContext();
        new Thread(() -> {
            final Error[] result = new Error[1];
            try (InputStream is = appContext.getContentResolver().openInputStream(uri)) {
                if (is == null) {
                    result[0] = new Error(appContext.getString(R.string.error_settings_restore_open_input));
                } else {
                    TermuxSettingsBackupUtils.importSettings(appContext, is,
                        error -> result[0] = error, null, new AtomicBoolean(false));
                }
            } catch (IOException e) {
                result[0] = new Error(e.getMessage(), e);
            }

            new Handler(Looper.getMainLooper()).post(this::dismissSettingsDialog);
            new Handler(Looper.getMainLooper()).post(() -> {
                mSettingsRunning = false;
                FragmentActivity a = getActivity();
                if (a == null || a.isFinishing()) return;
                if (result[0] == null) {
                    showToast(a, R.string.settings_restore_success);
                    showToast(a, R.string.settings_restart_required);
                } else if (result[0] == TermuxSettingsBackupUtils.CANCELLED_ERROR) {
                    showToast(a, R.string.backup_restore_cancelled);
                } else {
                    showToast(a, a.getString(R.string.settings_restore_failed)
                        + ": " + com.termux.shared.errors.Error.getMinimalErrorString(result[0]));
                }
            });
        }, "SettingsRestore").start();
    }

    private void dismissSettingsDialog() {
        if (mSettingsDialog != null && mSettingsDialog.isShowing()) {
            mSettingsDialog.dismiss();
            mSettingsDialog = null;
        }
    }

    private void showToast(FragmentActivity activity, int resId) {
        android.widget.Toast.makeText(activity, activity.getString(resId),
            android.widget.Toast.LENGTH_LONG).show();
    }

    private void showToast(FragmentActivity activity, String text) {
        android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_LONG).show();
    }

    // ---- Lifecycle ----

    @Override
    public void onResume() {
        super.onResume();
        TermuxBackupService svc = TermuxBackupService.getInstance();
        FragmentActivity activity = getActivity();
        if (activity != null && svc != null && svc.isInForeground() && !svc.isFinished()) {
            mBackupController = new BackupProgressController(activity, false, null);
            mBackupController.reopen(activity);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mBackupController != null) mBackupController.detach();
        dismissSettingsDialog();
    }

    @Override
    public void onDestroy() {
        if (mBackupController != null) mBackupController.detach();
        dismissSettingsDialog();
        super.onDestroy();
    }

    /** Query the size of a SAF content URI (or -1 if unknown). */
    private static long getStreamSize(Context context, Uri uri) {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd != null) {
                long size = pfd.getStatSize();
                if (size > 0) return size;
            }
        } catch (IOException ignored) { }
        return -1;
    }

}
