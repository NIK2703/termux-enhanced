package com.termux.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;

import com.termux.app.TermuxInstaller;
import com.termux.installer.AbiUtils;
import com.termux.installer.BootstrapSource;
import com.termux.installer.Sha256;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

public class BootstrapDownloadService extends Service {

    private static final String ACTION_DOWNLOAD = "com.termux.action.DOWNLOAD_BOOTSTRAP";
    private static final String ACTION_INSTALL_LOCAL = "com.termux.action.INSTALL_LOCAL_URI";
    private static final String EXTRA_SOURCE = "source";
    private static final String EXTRA_URI = "uri";
    private static final String EXTRA_NO_FOREGROUND = "no_foreground";

    private static final int NOTIFICATION_ID = 4001;
    private static final String CHANNEL_ID = "termux_bootstrap";
    private static final long MIN_STORAGE_BYTES = 512L * 1024 * 1024;

    public enum Status { IDLE, CONNECTING, DOWNLOADING, VERIFYING, INSTALLING, SUCCESS, FAILED }

    public static final class State {
        public Status status = Status.IDLE;
        public String statusMessage;
        public String progressMessage = "";
        public int percent = 0;
        public boolean indeterminate;
        public boolean success;
        public boolean failed;

        public boolean isBusy() {
            return status == Status.CONNECTING || status == Status.DOWNLOADING
                || status == Status.VERIFYING || status == Status.INSTALLING;
        }

        public State copy() {
            State copy = new State();
            copy.status = status;
            copy.statusMessage = statusMessage;
            copy.progressMessage = progressMessage;
            copy.percent = percent;
            copy.indeterminate = indeterminate;
            copy.success = success;
            copy.failed = failed;
            return copy;
        }
    }

    public interface Listener {
        void onStateChanged(State state);
    }

    /**
     * Terminal state (SUCCESS/FAILED) survives service recreation: the service
     * calls stopSelf() right after finishing a task, and a late bind would
     * otherwise create a fresh service with an IDLE state, losing the result.
     */
    private static final class SavedState {
        final Status status;
        final int percent;
        final String statusMessage;

        SavedState(Status status, int percent, String statusMessage) {
            this.status = status;
            this.percent = percent;
            this.statusMessage = statusMessage;
        }
    }

    private static volatile SavedState sSavedState = new SavedState(Status.IDLE, 0, null);

    public static SavedState getSavedState() {
        return sSavedState;
    }

    public static void clearSavedState() {
        sSavedState = new SavedState(Status.IDLE, 0, null);
    }

    public class LocalBinder extends Binder {
        public BootstrapDownloadService getService() { return BootstrapDownloadService.this; }
    }

    private final IBinder mBinder = new LocalBinder();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Object mStateLock = new Object();
    private State mState = new State();
    private Listener mListener;
    private PowerManager.WakeLock mWakeLock;
    private boolean mForegroundMode = true;

    public static void startDownload(Context context, BootstrapSource source) {
        Intent intent = new Intent(context, BootstrapDownloadService.class);
        intent.setAction(ACTION_DOWNLOAD);
        intent.putExtra(EXTRA_SOURCE, source);
        startServiceSafe(context, intent);
    }

    public static void installLocalUri(Context context, Uri uri) {
        Intent intent = new Intent(context, BootstrapDownloadService.class);
        intent.setAction(ACTION_INSTALL_LOCAL);
        intent.putExtra(EXTRA_URI, uri);
        startServiceSafe(context, intent);
    }

    public static void startDownloadBackground(Context context, BootstrapSource source) {
        Intent intent = new Intent(context, BootstrapDownloadService.class);
        intent.setAction(ACTION_DOWNLOAD);
        intent.putExtra(EXTRA_SOURCE, source);
        intent.putExtra(EXTRA_NO_FOREGROUND, true);
        context.startService(intent);
    }

    public static void installLocalUriBackground(Context context, Uri uri) {
        Intent intent = new Intent(context, BootstrapDownloadService.class);
        intent.setAction(ACTION_INSTALL_LOCAL);
        intent.putExtra(EXTRA_URI, uri);
        intent.putExtra(EXTRA_NO_FOREGROUND, true);
        context.startService(intent);
    }

    private static void startServiceSafe(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mState.statusMessage = "";
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        mForegroundMode = !intent.getBooleanExtra(EXTRA_NO_FOREGROUND, false);
        Logger.i("BootstrapDownloadService", "onStartCommand action=" + intent.getAction()
            + " foreground=" + mForegroundMode);
        if (mForegroundMode) startForegroundWithPermissionCheck();
        String action = intent.getAction();
        if (ACTION_DOWNLOAD.equals(action)) {
            BootstrapSource source = (BootstrapSource) intent.getSerializableExtra(EXTRA_SOURCE);
            Logger.i("BootstrapDownloadService", "ACTION_DOWNLOAD source=" + (source != null ? source.name : "null"));
            if (source != null) startDownloadTask(source);
        } else if (ACTION_INSTALL_LOCAL.equals(action)) {
            Uri uri = intent.getParcelableExtra(EXTRA_URI);
            Logger.i("BootstrapDownloadService", "ACTION_INSTALL_LOCAL uri=" + uri);
            if (uri != null) startLocalInstallTask(uri);
        }
        return START_NOT_STICKY;
    }

    private void startForegroundWithPermissionCheck() {
        try {
            Notification n = buildNotification(
                getString(com.termux.R.string.bootstrap_download_notification_starting),
                "", 0, true);
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (SecurityException e) {
            Logger.i("BootstrapDownloadService", "POST_NOTIFICATIONS not granted, running without notification");
            mForegroundMode = false;
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return mBinder; }

    @Override
    public void onDestroy() {
        Logger.i("BootstrapDownloadService", "onDestroy");
        super.onDestroy();
        releaseWakeLock();
        mExecutor.shutdownNow();
    }

    public void setListener(Listener listener) {
        synchronized (mStateLock) {
            mListener = listener;
        }
        if (listener != null) listener.onStateChanged(getState());
    }

    public State getState() {
        synchronized (mStateLock) {
            return mState.copy();
        }
    }

    /**
     * Like {@link #getState()}, but if the live state is IDLE and a terminal
     * state was saved (e.g. the service was recreated after stopSelf()),
     * returns the saved terminal state instead.
     */
    public State getStateOrSaved() {
        synchronized (mStateLock) {
            if (mState.status != Status.IDLE) {
                return mState.copy();
            }
        }
        SavedState saved = sSavedState;
        if (saved.status == Status.IDLE) {
            return getState();
        }
        State restored = new State();
        restored.status = saved.status;
        restored.percent = saved.percent;
        restored.statusMessage = saved.statusMessage;
        restored.success = saved.status == Status.SUCCESS;
        restored.failed = saved.status == Status.FAILED;
        return restored;
    }

    /** Called by the UI after the user acknowledged success/failure. */
    public void acknowledgeTerminalState() {
        synchronized (mStateLock) {
            if (mState.status == Status.SUCCESS || mState.status == Status.FAILED) {
                mState = new State();
            }
        }
        clearSavedState();
        stopForeground(true);
        stopSelf();
    }

    private void startDownloadTask(BootstrapSource source) {
        clearSavedState();
        acquireWakeLock();
        Logger.i("BootstrapDownloadService", "startDownloadTask: " + source.name);
        setState(Status.CONNECTING,
            getString(com.termux.R.string.bootstrap_download_status_connecting),
            getString(com.termux.R.string.bootstrap_download_progress_preparing),
            0, true, false, false);

        mExecutor.execute(() -> {
            try {
                checkStorageSpace(getFilesDir());

                File downloaded = downloadAndVerify(source);
                Logger.i("BootstrapDownloadService", "downloadAndVerify returned: " + downloaded.getAbsolutePath() + " size=" + downloaded.length());

                setState(Status.INSTALLING,
                    getString(com.termux.R.string.bootstrap_download_status_installing),
                    getString(com.termux.R.string.bootstrap_download_progress_extracting),
                    0, false, false, false);

                TermuxInstaller.installBootstrapFromZipFile(BootstrapDownloadService.this, downloaded,
                    (stage, percent) -> mMainHandler.post(() ->
                        setState(Status.INSTALLING,
                            getString(com.termux.R.string.bootstrap_download_status_installing),
                            stage, percent, false, false, false))
                );

                downloaded.delete();
                reloadBootstrapVariant();
                setState(Status.SUCCESS,
                    getString(com.termux.R.string.bootstrap_download_status_done),
                    getString(com.termux.R.string.bootstrap_download_status_installed),
                    100, false, true, false);
                stopForeground(true);
                stopSelf();
            } catch (Exception e) {
                setState(Status.FAILED,
                    getString(com.termux.R.string.bootstrap_download_status_failed),
                    e.getMessage(), 0, false, false, true);
                showFailedNotification(e.getMessage());
                stopForeground(false);
                stopSelf();
            } finally {
                releaseWakeLock();
            }
        });
    }

    private void startLocalInstallTask(Uri uri) {
        clearSavedState();
        acquireWakeLock();
        Logger.i("BootstrapDownloadService", "startLocalInstallTask: uri=" + uri);
        setState(Status.INSTALLING,
            getString(com.termux.R.string.bootstrap_download_status_installing),
            getString(com.termux.R.string.bootstrap_download_progress_copying_local),
            5, false, false, false);

        mExecutor.execute(() -> {
            try {
                checkStorageSpace(getFilesDir());

                File cacheDir = new File(getCacheDir(), "bootstrap");
                if (!cacheDir.isDirectory() && !cacheDir.mkdirs())
                    throw new IOException(getString(com.termux.R.string.error_bootstrap_download_cache_dir));
                File tempFile = new File(cacheDir, "local_bootstrap.zip");

                setState(Status.INSTALLING,
                    getString(com.termux.R.string.bootstrap_download_status_installing),
                    getString(com.termux.R.string.bootstrap_download_progress_copying_internal),
                    10, false, false, false);

                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(tempFile)) {
                    if (in == null) throw new IOException(getString(com.termux.R.string.error_bootstrap_download_read_uri));
                    byte[] buf = new byte[65536];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        total += n;
                    }
                    Logger.i("BootstrapDownloadService", "Copied local file: " + total + " bytes");
                }

                setState(Status.INSTALLING,
                    getString(com.termux.R.string.bootstrap_download_status_installing),
                    getString(com.termux.R.string.bootstrap_download_progress_extracting),
                    20, false, false, false);

                TermuxInstaller.installBootstrapFromZipFile(BootstrapDownloadService.this, tempFile,
                    (stage, percent) -> mMainHandler.post(() ->
                        setState(Status.INSTALLING,
                            getString(com.termux.R.string.bootstrap_download_status_installing),
                            stage, 20 + (int)(percent * 0.8),
                            false, false, false))
                );

                tempFile.delete();
                reloadBootstrapVariant();
                setState(Status.SUCCESS,
                    getString(com.termux.R.string.bootstrap_download_status_done),
                    getString(com.termux.R.string.bootstrap_download_status_installed),
                    100, false, true, false);
                stopForeground(true);
                stopSelf();
            } catch (Exception e) {
                setState(Status.FAILED,
                    getString(com.termux.R.string.bootstrap_download_status_failed),
                    e.getMessage(), 0, false, false, true);
                showFailedNotification(e.getMessage());
                stopForeground(false);
                stopSelf();
            } finally {
                releaseWakeLock();
            }
        });
    }

    private void checkStorageSpace(File dir) throws IOException {
        StatFs stat = new StatFs(dir.getAbsolutePath());
        long available = (long) stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        if (available < MIN_STORAGE_BYTES) {
            throw new IOException(getString(com.termux.R.string.error_bootstrap_download_storage,
                humanSize(MIN_STORAGE_BYTES), humanSize(available)));
        }
    }

    private void reloadBootstrapVariant() {
        try {
            com.termux.shared.termux.TermuxBootstrap.initializeFromRuntime(
                this, com.termux.BuildConfig.TERMUX_PACKAGE_VARIANT);
        } catch (Exception e) {
            Logger.e("BootstrapDownloadService", "Failed to reload bootstrap variant", e);
        }
    }

    private void showFailedNotification(String msg) {
        if (!mForegroundMode) return;
        Intent intent = new Intent(this, com.termux.installer.BootstrapSelectorActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(com.termux.R.string.bootstrap_download_notification_title_failed))
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, n);
    }

    private File downloadAndVerify(BootstrapSource source) throws Exception {
        String resolvedUrl = source.resolveUrl(AbiUtils.getDeviceArch());
        Logger.i("BootstrapDownloadService", "downloadAndVerify: url=" + resolvedUrl);

        if (!resolvedUrl.startsWith("https://"))
            throw new IOException(getString(com.termux.R.string.error_bootstrap_download_https));

        File cacheDir = new File(getCacheDir(), "bootstrap");
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs())
            throw new IOException(getString(com.termux.R.string.error_bootstrap_download_cache_dir));

        String hashPart = source.sha256 != null ? source.sha256.toLowerCase() : "download";
        File finalFile = new File(cacheDir, hashPart + ".zip");

        if (finalFile.exists()) {
            if (source.sha256 != null) {
                String existing = Sha256.hexOfFile(BootstrapDownloadService.this, finalFile);
                Logger.i("BootstrapDownloadService", "cached file exists, sha256=" + existing);
                if (existing.equalsIgnoreCase(source.sha256)) {
                    Logger.i("BootstrapDownloadService", "cache hit, returning cached file");
                    return finalFile;
                }
            }
            finalFile.delete();
        }

        File tempFile = new File(cacheDir, hashPart + ".part");
        Logger.i("BootstrapDownloadService", "downloading to tempFile=" + tempFile);
        if (tempFile.exists()) tempFile.delete();

        URL url = new URL(resolvedUrl);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Termux/1.0");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.connect();

            int responseCode = conn.getResponseCode();
            Logger.i("BootstrapDownloadService", "HTTP response=" + responseCode + " content-length=" + conn.getContentLengthLong());
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException(getString(com.termux.R.string.error_bootstrap_download_http, responseCode, resolvedUrl));
            }

            long total = conn.getContentLengthLong();
            long downloaded = 0;

            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(tempFile)) {
                byte[] buf = new byte[65536];
                int n;
                long lastUi = 0;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    long now = System.currentTimeMillis();
                    if (now - lastUi > 250) {
                        lastUi = now;
                        final long d = downloaded;
                        final long t = total;
                        mMainHandler.post(() -> setState(Status.DOWNLOADING,
                            getString(com.termux.R.string.bootstrap_download_status_downloading),
                            getString(com.termux.R.string.human_size_progress_format, humanSize(d), humanSize(t)),
                            t > 0 ? (int)(100 * d / t) : 0, t <= 0, false, false));
                    }
                }
            }

            Logger.i("BootstrapDownloadService", "download complete: " + downloaded + " bytes");

            if (source.sha256 != null) {
                setState(Status.VERIFYING,
                    getString(com.termux.R.string.bootstrap_download_status_verifying),
                    getString(com.termux.R.string.bootstrap_download_progress_checking_sha),
                    90, true, false, false);
                String actual = Sha256.hexOfFile(BootstrapDownloadService.this, tempFile);
                Logger.i("BootstrapDownloadService", "SHA-256 expected=" + source.sha256 + " actual=" + actual);
                if (!actual.equalsIgnoreCase(source.sha256)) {
                    tempFile.delete();
                    throw new IOException(getString(com.termux.R.string.error_bootstrap_download_sha_mismatch, source.sha256, actual));
                }
            }

            if (!tempFile.renameTo(finalFile))
                throw new IOException(getString(com.termux.R.string.error_bootstrap_download_rename));

            return finalFile;
        } finally {
            conn.disconnect();
        }
    }

    private void setState(Status status, String statusMsg, String progressMsg,
                          int percent, boolean indeterminate, boolean success, boolean failed) {
        Logger.i("BootstrapDownloadService", "setState: " + status + " pct=" + percent
            + " msg=" + statusMsg + "/" + progressMsg);
        State snapshot;
        Listener listener;
        synchronized (mStateLock) {
            mState.status = status;
            mState.statusMessage = statusMsg;
            mState.progressMessage = progressMsg;
            mState.percent = percent;
            mState.indeterminate = indeterminate;
            mState.success = success;
            mState.failed = failed;
            snapshot = mState.copy();
            listener = mListener;
        }

        if (status == Status.SUCCESS || status == Status.FAILED) {
            sSavedState = new SavedState(status, percent, statusMsg);
        }

        if (mForegroundMode && status != Status.SUCCESS && status != Status.FAILED) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null)
                nm.notify(NOTIFICATION_ID, buildNotification(statusMsg, progressMsg, percent, indeterminate));
        }

        if (listener != null) listener.onStateChanged(snapshot);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    getString(com.termux.R.string.bootstrap_download_channel_name), NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    private Notification buildNotification(String title, String text, int percent, boolean indeterminate) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            b = new Notification.Builder(this, CHANNEL_ID);
        else
            b = new Notification.Builder(this);

        Intent intent = new Intent(this, com.termux.app.TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return b.setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true).setProgress(100, percent, indeterminate)
            .setContentIntent(pi).build();
    }

    private void acquireWakeLock() {
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "termux:bootstrap");
                mWakeLock.setReferenceCounted(false);
                Logger.i("BootstrapDownloadService", "acquireWakeLock: created");
            }
        }
        if (mWakeLock != null && !mWakeLock.isHeld()) {
            mWakeLock.acquire();
            Logger.i("BootstrapDownloadService", "acquireWakeLock: acquired");
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
            Logger.i("BootstrapDownloadService", "releaseWakeLock: released");
        }
    }

    private String humanSize(long bytes) {
        String[] units = getResources().getStringArray(com.termux.R.array.human_size_units);
        double v = bytes;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) { v /= 1024; u++; }
        return String.format(java.util.Locale.US, getString(com.termux.R.string.human_size_format), v, units[u]);
    }

    private static final class Logger {
        static void i(String tag, String msg) { android.util.Log.i(tag, msg); }
        static void e(String tag, String msg, Throwable t) { android.util.Log.e(tag, msg, t); }
    }
}
