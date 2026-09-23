package com.termux.installer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.termux.app.TermuxInstaller;
import com.termux.services.BootstrapDownloadService;

import java.util.List;

import android.os.Build;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;

public class BootstrapSelectorActivity extends Activity {

    private static final int REQUEST_PICK_ZIP = 1001;
    private static final int REQUEST_POST_NOTIFICATIONS = 1002;

    private BootstrapSource mAutoSelectedSource;
    private Button mDownloadButton;
    private Button mPickZipButton;
    private Button mRetryButton;

    private AlertDialog mProgressDialog;
    private AlertDialog mFailedDialog;
    private boolean mResumed;

    private List<BootstrapSource> mSources;
    private BootstrapDownloadService mService;
    private boolean mBound;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            BootstrapDownloadService.LocalBinder lb = (BootstrapDownloadService.LocalBinder) binder;
            mService = lb.getService();
            mBound = true;
            mService.setListener(mServiceListener);
            updateUiFromState(mService.getStateOrSaved());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (mService != null) {
                mService.setListener(null);
            }
            mService = null;
            mBound = false;
        }
    };

    private final BootstrapDownloadService.Listener mServiceListener = state -> {
        runOnUiThread(() -> updateUiFromState(state));
    };

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        if (mService != null) {
            updateUiFromState(mService.getStateOrSaved());
        }
    }

    @Override
    protected void onPause() {
        mResumed = false;
        super.onPause();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (TermuxInstaller.isBootstrapInstalled(this)) {
            finishWithSuccess();
            return;
        }

        setContentView(com.termux.R.layout.activity_bootstrap_selector);

        mDownloadButton = findViewById(com.termux.R.id.download_button);
        mPickZipButton = findViewById(com.termux.R.id.pick_zip_button);
        mRetryButton = findViewById(com.termux.R.id.retry_button);

        loadSources();

        mDownloadButton.setOnClickListener(v -> startDownload());
        mPickZipButton.setOnClickListener(v -> pickLocalZip());
        mRetryButton.setOnClickListener(v -> {
            mRetryButton.setVisibility(View.GONE);
            startDownload();
        });
    }

    private void bindDownloadService() {
        Intent intent = new Intent(this, BootstrapDownloadService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindDownloadService();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mBound) {
            if (mService != null) mService.setListener(null);
            unbindService(mConnection);
            mBound = false;
            mService = null;
        }
    }

    @Override
    protected void onDestroy() {
        dismissProgressDialog();
        dismissFailedDialog();
        super.onDestroy();
    }

    private void loadSources() {
        try {
            mSources = BootstrapSources.loadFromResources(this);
        } catch (Exception e) {
            return;
        }
        String targetVariant = Build.VERSION.SDK_INT >= 24 ? "apt-android-7" : "apt-android-5";
        for (BootstrapSource s : mSources) {
            if (s.variant.equals(targetVariant)) {
                mAutoSelectedSource = s;
                break;
            }
        }
        if (mAutoSelectedSource == null && !mSources.isEmpty()) {
            mAutoSelectedSource = mSources.get(0);
        }
    }

    private void startDownload() {
        if (mAutoSelectedSource == null) return;
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_POST_NOTIFICATIONS);
            return;
        }
        doDownload();
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void doDownload() {
        if (mAutoSelectedSource == null) return;
        if (hasNotificationPermission()) {
            BootstrapDownloadService.startDownload(this, mAutoSelectedSource);
        } else {
            BootstrapDownloadService.startDownloadBackground(this, mAutoSelectedSource);
        }
        if (!mBound) {
            bindDownloadService();
        }
    }

    private void pickLocalZip() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "application/zip", "application/x-zip-compressed", "application/octet-stream"
        });
        startActivityForResult(intent, REQUEST_PICK_ZIP);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            doDownload();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_ZIP && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) confirmLocalZip(uri);
        }
    }

    private void confirmLocalZip(Uri uri) {
        new AlertDialog.Builder(this)
            .setTitle(com.termux.R.string.bootstrap_selector_install_local_title)
            .setMessage(com.termux.R.string.bootstrap_selector_install_local_message)
            .setPositiveButton(com.termux.R.string.bootstrap_selector_install, (dialog, which) -> {
                takePersistableUriPermission(uri);
                if (hasNotificationPermission()) {
                    BootstrapDownloadService.installLocalUri(BootstrapSelectorActivity.this, uri);
                } else {
                    BootstrapDownloadService.installLocalUriBackground(BootstrapSelectorActivity.this, uri);
                }
            })
            .setNegativeButton(com.termux.R.string.bootstrap_selector_cancel, null)
            .show();
    }

    private void setButtonsEnabled(boolean enabled) {
        mDownloadButton.setEnabled(enabled);
        mPickZipButton.setEnabled(enabled);
        if (enabled) mRetryButton.setVisibility(View.GONE);
    }

    private void takePersistableUriPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
    }

    private void showProgressDialog(String progressMessage,
                                    int percent, boolean indeterminate) {
        if (isFinishing() || !mResumed) return;
        if (mProgressDialog == null) {
            View view = getLayoutInflater().inflate(com.termux.R.layout.dialog_bootstrap_progress, null);
            mProgressDialog = new AlertDialog.Builder(this)
                .setTitle(com.termux.R.string.bootstrap_progress_title)
                .setView(view)
                .setCancelable(false)
                .show();
        }
        ProgressBar bar = mProgressDialog.findViewById(com.termux.R.id.progress_bar);
        TextView text = mProgressDialog.findViewById(com.termux.R.id.progress_text);
        if (bar != null) {
            bar.setIndeterminate(indeterminate);
            bar.setProgress(percent);
        }
        if (text != null) text.setText(progressMessage);
    }

    private void showFailedDialog(String errorMessage) {
        if (isFinishing() || !mResumed) return;
        dismissProgressDialog();
        if (mFailedDialog != null && mFailedDialog.isShowing()) return;
        mFailedDialog = new AlertDialog.Builder(this)
            .setTitle(com.termux.R.string.bootstrap_download_status_failed)
            .setMessage(errorMessage)
            .setPositiveButton(com.termux.R.string.bootstrap_selector_retry, (dialog, which) -> {
                dismissFailedDialog();
                acknowledgeTerminalState();
                mRetryButton.setVisibility(View.VISIBLE);
                setButtonsEnabled(true);
            })
            .setNegativeButton(com.termux.R.string.bootstrap_selector_cancel, (dialog, which) -> {
                dismissFailedDialog();
                acknowledgeTerminalState();
                finish();
            })
            .setCancelable(false)
            .show();
    }

    private void dismissProgressDialog() {
        if (mProgressDialog != null && mProgressDialog.isShowing()) {
            mProgressDialog.dismiss();
        }
        mProgressDialog = null;
    }

    private void dismissFailedDialog() {
        if (mFailedDialog != null) {
            try { mFailedDialog.dismiss(); } catch (RuntimeException ignored) {}
            mFailedDialog = null;
        }
    }

    /** Let the service forget the terminal state and stop itself. */
    private void acknowledgeTerminalState() {
        if (mService != null) {
            mService.acknowledgeTerminalState();
        } else {
            BootstrapDownloadService.clearSavedState();
            stopService(new Intent(this, BootstrapDownloadService.class));
        }
    }

    private void updateUiFromState(BootstrapDownloadService.State state) {
        if (state == null || isFinishing()) return;

        boolean busy = state.isBusy();

        if (state.failed) {
            showFailedDialog(state.progressMessage);
        } else if (busy) {
            showProgressDialog(state.progressMessage,
                state.percent, state.indeterminate);
        } else if (state.success) {
            dismissProgressDialog();
            finishWithSuccess();
        } else {
            dismissProgressDialog();
        }

        mDownloadButton.setEnabled(!busy);
        mPickZipButton.setEnabled(!busy);
        mRetryButton.setVisibility(state.failed ? View.VISIBLE : View.GONE);
    }

    private void finishWithSuccess() {
        acknowledgeTerminalState();
        setResult(RESULT_OK);
        finish();
    }
}
