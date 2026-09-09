package com.termux.app;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.errors.Error;
import com.termux.shared.file.filesystem.FileTypes;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxBootstrapType;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.TermuxPrefixRemap;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class TermuxBackupUtils {

    private static final String LOG_TAG = "TermuxBackupUtils";
    private static final String SYSTEM_TAR_BINARY = "/system/bin/tar";

    /** Sentinel error returned when the operation is cancelled by the user. */
    public static final Error CANCELLED_ERROR = new Error("__CANCELLED__");

    /** Upper bound on retained tar stderr (chars). Only the first chunk is kept for error reporting. */
    private static final int MAX_STDERR_CHARS = 64 * 1024;

    public interface ResultListener {
        void onResult(@Nullable Error error);
    }

    @FunctionalInterface
    public interface ProgressCallback {
        /**
         * @param bytesCopied bytes transferred so far (compressed stream)
         * @param totalBytes  estimated total (0 = unknown / indeterminate)
         */
        void onProgress(long bytesCopied, long totalBytes);
    }

    private TermuxBackupUtils() {}

    // -----------------------------------------------------------------------
    // Size estimation
    // -----------------------------------------------------------------------

    /**
     * Estimates the total uncompressed size of the Termux data directory
     * ({@code $FILES}) by running {@code du -sb}. This is close to (slightly
     * below) the uncompressed tar stream we measure progress against; the caller
     * adds a small tar-overhead budget so the bar reaches 100% at true EOF.
     * Returns 0 if the estimate cannot be obtained.
     */
    /** Upper bound for a single {@code du -sb} run; on timeout du is killed and the estimate
     * falls back to 0 — the tar data pump's end-of-stream snap then drives the bar to 100%. */
    private static final long DU_ESTIMATE_TIMEOUT_SECONDS = 120;

    /**
     * Estimates the total uncompressed size of the Termux data directory
     * ({@code $FILES}) by running {@code du -sb}. This is close to (slightly
     * below) the uncompressed tar stream we measure progress against; the caller
     * adds a small tar-overhead budget so the bar reaches 100% at true EOF.
     * Returns 0 if the estimate cannot be obtained.
     */
    public static long getEstimatedBackupSize(@NonNull Context context, boolean excludeTmp) {
        Error health = checkTarHealth(context);
        if (health != null) return 0;
        final String filesDir = context.getFilesDir().getAbsolutePath();
        long total = runDuSize(filesDir, filesDir);
        // tar honors --exclude=usr/tmp, but toybox du has no --exclude flag, so subtract
        // the excluded subtree's size explicitly to keep the determinate percentage
        // aligned with the actual tar stream.
        if (excludeTmp && total > 0) {
            total -= runDuSize(filesDir, filesDir + "/usr/tmp");
        }
        return Math.max(total, 0);
    }

    /**
     * Runs a single {@code du -sb} over {@code path} and returns the summary size in bytes,
     * or 0 if it cannot be obtained.
     *
     * <p>Hardened against the failure modes that previously wedged the backup progress on the
     * indeterminate bar forever:
     * <ul>
     *   <li>stderr is drained on a side thread — a flood of "cannot read directory" warnings
     *       (e.g. from root-owned subdirectories inside a container) can no longer fill the
     *       64&nbsp;KiB pipe buffer and block du indefinitely. The old code never read stderr
     *       and had no timeout, so a wedged du made {@code waitFor()} hang and the estimate
     *       (and with it the determinate transition) never arrived.</li>
     *   <li>the wait is bounded; on timeout the process is killed and 0 is returned, so a
     *       pathological tree cannot stall the UI for the whole backup.</li>
     *   <li>the summary line is read fully (not a single 128-byte read).</li>
     * </ul>
     */
    private static long runDuSize(@NonNull String filesDir, @NonNull String path) {
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/du", "-sb", path);
            pb.environment().clear();
            pb.environment().put("PATH", filesDir + "/usr/bin");
            Process proc = pb.start();

            // Drain stderr concurrently so du can never block on a full stderr pipe.
            Thread stderrDrain = new Thread(() -> {
                try (InputStream e = proc.getErrorStream()) {
                    byte[] buf = new byte[4096];
                    while (e.read(buf) > 0) { /* discard */ }
                } catch (IOException ignored) { }
            }, "DuStderrDrain");
            stderrDrain.start();

            boolean exited;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                exited = proc.waitFor(DU_ESTIMATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } else {
                // Pre-O: no bounded waitFor(); stderr is drained so the process still
                // terminates on its own as long as the kernel schedules it.
                proc.waitFor();
                exited = true;
            }
            if (!exited) {
                proc.destroy();
                try { proc.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                return 0;
            }

            // du -sb prints a single short summary line, so reading after waitFor()
            // cannot stall on the pipe.
            String line = null;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.US_ASCII))) {
                line = r.readLine();
            }
            try { stderrDrain.join(1000); } catch (InterruptedException ignored) { }
            if (line != null) {
                String trimmed = line.trim();
                int space = trimmed.indexOf('\t');
                if (space < 0) space = trimmed.indexOf(' ');
                if (space > 0) {
                    return Long.parseLong(trimmed.substring(0, space));
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    // -----------------------------------------------------------------------
    // Tar binary resolution
    // -----------------------------------------------------------------------

    /**
     * Resolve the tar binary to use for backup/restore:
     * - NIX bootstrap has no usr/bin/tar, so the system toybox tar (/system/bin/tar) is used.
     * - TERMUX bootstrap uses $PREFIX/bin/tar resolved against the runtime data dir
     *   (the compile-time TermuxConstants paths point at /data/data/com.termux and do
     *   not exist on forks with a renamed package).
     * Returns null when no usable tar exists.
     */
    @Nullable
    private static String resolveTarBinary(@NonNull Context context) {
        TermuxBootstrapType type = TermuxBootstrapType.getInstalledType(context.getFilesDir());
        if (type == TermuxBootstrapType.NIX) {
            if (new File(SYSTEM_TAR_BINARY).isFile()) return SYSTEM_TAR_BINARY;
            return null;
        }
        String runtimeTar = context.getFilesDir().getAbsolutePath() + "/usr/bin/tar";
        if (new File(runtimeTar).isFile()) return runtimeTar;
        return null;
    }

    private static boolean isSystemTar(@NonNull String tarBinary) {
        return SYSTEM_TAR_BINARY.equals(tarBinary);
    }

    /**
     * Environment for a tar process:
     * - toybox needs /system/bin in PATH (it execs zcat for gzip streams).
     * - prefix tar gets the runtime Termux environment; on forks with a renamed package
     *   (needsSubstitution) the path-remapping shim is exported so the binary's hardcoded
     *   /data/data/com.termux paths resolve to the actual runtime prefix.
     */
    private static void setupTarEnvironment(@NonNull Context context,
                                            @NonNull ProcessBuilder pb,
                                            @NonNull String tarBinary) {
        pb.environment().clear();
        if (isSystemTar(tarBinary)) {
            pb.environment().put("PATH", "/system/bin:/system/xbin");
            return;
        }
        String filesDir = context.getFilesDir().getAbsolutePath();
        String prefixDir = filesDir + "/usr";
        String libDir = prefixDir + "/lib";
        pb.environment().put("PATH", prefixDir + "/bin");
        pb.environment().put("PREFIX", prefixDir);
        pb.environment().put("HOME", filesDir + "/home");
        pb.environment().put("TMPDIR", prefixDir + "/tmp");

        if (TermuxPrefixRemap.needsSubstitution(context)
            && TermuxPrefixRemap.isRemapLibAvailable(libDir)) {
            String oldFilesDir = TermuxConstants.TERMUX_FILES_DIR_PATH;
            String newFilesDir = context.getFilesDir().getAbsolutePath()
                .replaceFirst("^/data/user/0/", "/data/data/");
            pb.environment().put(TermuxPrefixRemap.ENV_LD_PRELOAD,
                TermuxPrefixRemap.getRemapLibPath(libDir));
            pb.environment().put(TermuxPrefixRemap.ENV_REMAP_OLD_FILES_DIR, oldFilesDir);
            pb.environment().put(TermuxPrefixRemap.ENV_REMAP_NEW_FILES_DIR, newFilesDir);
            pb.environment().put(TermuxPrefixRemap.ENV_REMAP_LIBPATH, libDir);
            String loader = TermuxPrefixRemap.findLoader(libDir);
            if (loader != null) {
                pb.environment().put(TermuxPrefixRemap.ENV_REMAP_LOADER, loader);
            }
            pb.environment().put(TermuxPrefixRemap.ENV_REMAP_PRESERVE_ARGV0, "1");
        }
    }

    /**
     * Wrap a prefix binary in the loader shim when its ELF interpreter is hardcoded to
     * /data/data/com.termux (renamed-package forks). Toybox and native-ABI binaries are
     * returned unchanged.
     */
    @NonNull
    private static String[] wrapTarCommand(@NonNull Context context,
                                           @NonNull String tarBinary,
                                           @NonNull String[] argv) {
        if (isSystemTar(tarBinary)) return argv;
        String prefixDir = context.getFilesDir().getAbsolutePath() + "/usr";
        String libDir = prefixDir + "/lib";
        if (!TermuxPrefixRemap.needsSubstitution(context)) return argv;
        if (!TermuxPrefixRemap.isRemapLibAvailable(libDir)) {
            TermuxPrefixRemap.ensureInstalled(context, libDir);
        }
        return TermuxPrefixRemap.wrapExecutableIfNeeded(libDir, argv);
    }

    // -----------------------------------------------------------------------
    // Health check
    // -----------------------------------------------------------------------

    @Nullable
    /** Cached result of the {@code tar --version} probe: it forks a process and blocks, and was
     * being run 2-3 times per backup/restore operation. Keyed by the resolved binary path. */
    private static volatile String sTarHealthBinary;
    private static volatile Error sTarHealthError;

    private static Error checkTarHealth(@NonNull Context context) {
        String tarBinary = resolveTarBinary(context);
        if (tarBinary == null) {
            return new Error(context.getString(R.string.backup_restore_need_termux));
        }
        if (tarBinary.equals(sTarHealthBinary)) return sTarHealthError;
        Error result = runTarHealthCheck(context, tarBinary);
        sTarHealthBinary = tarBinary;
        sTarHealthError = result;
        return result;
    }

    private static Error runTarHealthCheck(@NonNull Context context, @NonNull String tarBinary) {
        String[] argv = wrapTarCommand(context, tarBinary,
            new String[]{tarBinary, "--version"});
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            setupTarEnvironment(context, pb, tarBinary);
            pb.directory(new File(isSystemTar(tarBinary)
                ? context.getFilesDir().getAbsolutePath()
                : context.getFilesDir().getAbsolutePath() + "/usr"));
            int code = pb.start().waitFor();
            if (code != 0) {
                return new Error(context.getString(com.termux.R.string.error_tar_health_check_exit, code));
            }
        } catch (IOException | InterruptedException e) {
            return new Error(context.getString(com.termux.R.string.error_tar_health_check, e.getMessage()));
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Backup
    // -----------------------------------------------------------------------

    public static void backup(@NonNull Context context, @NonNull OutputStream out,
                              @NonNull ResultListener listener,
                              @Nullable ProgressCallback progress,
                              @Nullable java.util.concurrent.atomic.AtomicBoolean cancelled,
                              boolean excludeTmp) {
        Error health = checkTarHealth(context);
        if (health != null) {
            listener.onResult(health);
            return;
        }
        final String tarBinary = resolveTarBinary(context);
        if (tarBinary == null) {
            listener.onResult(new Error(context.getString(R.string.backup_restore_need_termux)));
            return;
        }
        final String filesDir = context.getFilesDir().getAbsolutePath();
        final String parentDir = context.getDataDir().getAbsolutePath();

        String[] cmd;
        if (isSystemTar(tarBinary)) {
            cmd = excludeTmp
                ? new String[]{tarBinary, "-cpf", "-", "--numeric-owner",
                    "--exclude=usr/tmp", "-C", filesDir, "."}
                : new String[]{tarBinary, "-cpf", "-", "--numeric-owner",
                    "-C", filesDir, "."};
        } else if (excludeTmp) {
            cmd = new String[]{tarBinary, "-cpf", "-", "--numeric-owner",
                "--warning=no-file-changed", "--exclude=usr/tmp",
                "-C", filesDir, "."};
        } else {
            cmd = new String[]{tarBinary, "-cpf", "-", "--numeric-owner",
                "--warning=no-file-changed", "-C", filesDir, "."};
        }
        cmd = wrapTarCommand(context, tarBinary, cmd);
        runTar(context, cmd,
            null, out, listener, progress,
            new File(parentDir), 0L, cancelled);
    }

    // -----------------------------------------------------------------------
    // Restore
    // -----------------------------------------------------------------------

    /** Log the on-disk state of a path: existence, type, perms and (for dirs) entry count + size. */
    private static void logDirState(String stage, String path) {
        // The recursive sizeOf() below costs ~2 syscalls per file in $PREFIX and used to run
        // unconditionally — up to 4 full tree walks per restore even at the default log level.
        if (!Logger.isLoggable(Log.INFO)) return;
        File f = new File(path);
        if (!f.exists()) {
            Logger.logInfo(LOG_TAG, "[restore-state] " + stage + ": " + path
                + " => DOES NOT EXIST");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(stage).append(": ").append(path)
            .append(" exists=").append(true)
            .append(" isDir=").append(f.isDirectory())
            .append(" isFile=").append(f.isFile())
            .append(" canRead=").append(f.canRead())
            .append(" canWrite=").append(f.canWrite());
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            int count = (children != null) ? children.length : -1;
            long size = 0;
            if (children != null) for (File c : children) size += sizeOf(c);
            sb.append(" entries=").append(count).append(" sizeBytes=").append(size);
        } else {
            sb.append(" length=").append(f.length());
        }
        Logger.logInfo(LOG_TAG, "[restore-state] " + sb);
    }

    /** Recursive size in bytes. */
    private static long sizeOf(File f) {
        if (f.isFile()) return f.length();
        long total = 0;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) total += sizeOf(c);
        return total;
    }

    /** Remove the *contents* of {@code dir} (not the directory itself) so its inode stays stable.
     * This avoids the "unlinked inode" bug where tar (already chdir'd into dir) keeps writing
     * into a dangling inode after the directory is deleted and recreated. */
    private static void clearDirectoryContents(String label, String dirPath) {
        File dir = new File(dirPath);
        if (!dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            Error err = FileUtils.deleteFile(label, child.getAbsolutePath(), true, false,
                FileTypes.FILE_TYPE_ANY_FLAGS);
            if (err != null) {
                Logger.logError(LOG_TAG, "Failed to delete " + child.getAbsolutePath()
                    + ": " + err.getMinimalErrorString());
            }
        }
    }

    public static void restore(@NonNull Context context, @NonNull InputStream in,
                               @NonNull ResultListener listener,
                               @Nullable ProgressCallback progress,
                               @Nullable java.util.concurrent.atomic.AtomicBoolean cancelled) {
        final String filesDir = context.getFilesDir().getAbsolutePath();
        final String parentDir = context.getDataDir().getAbsolutePath();

        Error health = checkTarHealth(context);
        if (health != null) {
            listener.onResult(health);
            return;
        }
        final String tarBinary = resolveTarBinary(context);
        if (tarBinary == null) {
            listener.onResult(new Error(context.getString(R.string.backup_restore_need_termux)));
            return;
        }

        // Diagnostic: state BEFORE any change.
        logDirState("BEFORE tar-start", filesDir);
        logDirState("BEFORE tar-start (tar binary)", tarBinary);

        try {
            // Start tar FIRST so the binary is loaded into memory before we wipe $FILES.
            // -C <absolute files path> makes tar chdir into the on-disk files/ directory at
            // extraction time, AFTER our wipe+mkdirs below — so it always targets the live
            // directory, not a dangling inode left behind by deleteDirectoryFile().
            // pb.directory(parent) is only the process start dir (a path tar never removes).
            ProcessBuilder pb;
            if (isSystemTar(tarBinary)) {
                pb = new ProcessBuilder(tarBinary, "-xzpf", "-", "-o", "-C", filesDir);
            } else {
                String[] argv = wrapTarCommand(context, tarBinary,
                    new String[]{tarBinary, "-xzpf", "-", "--numeric-owner",
                        "--no-same-owner", "-C", filesDir});
                pb = new ProcessBuilder(argv);
            }
            setupTarEnvironment(context, pb, tarBinary);
            pb.directory(new File(parentDir));
            final Process process = pb.start();

            // Diagnostics: tar has started; its CWD inode.
            logDirState("AFTER tar-start (tar CWD)", filesDir);

            // Wipe the *contents* of files/ (NOT the directory itself, so the inode stays
            // stable and tar keeps writing into the live directory).
            clearDirectoryContents("restore_wipe_files", filesDir);
            logDirState("AFTER wipe-contents", filesDir);

            // The directory itself still exists (same inode) — no mkdirs needed. If it was
            // somehow removed, recreate it, but that is the degenerate path.
            File fd = new File(filesDir);
            if (!fd.exists() && !fd.mkdirs()) {
                process.destroy();
                listener.onResult(new Error(context.getString(com.termux.R.string.error_backup_recreate_dir, filesDir)));
                return;
            }

            final StringBuilder stderr = new StringBuilder();
            final Thread errPump = new Thread(() -> {
                try (InputStream e = process.getErrorStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    // Cap the retained stderr: extracting tens of thousands of files can emit
                    // megabytes of warnings, which previously accumulated in memory for the
                    // whole operation.
                    while ((n = e.read(buf)) > 0) {
                        if (stderr.length() < MAX_STDERR_CHARS)
                            stderr.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) {
                }
            });
            errPump.start();

            final AtomicReference<IOException> pumpError = new AtomicReference<>();
            // totalBytes for restore is unknown here — the caller (fragment) gets
            // the archive size from SAF and passes it via the ProgressCallback if it wants.
            final Thread dataPump = new Thread(() -> {
                long bytesCopied = 0;
                long lastReport = 0;
                try (InputStream i = in; OutputStream p = process.getOutputStream()) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = i.read(buf)) > 0) {
                        p.write(buf, 0, n);
                        bytesCopied += n;
                        if (progress != null && bytesCopied - lastReport >= 1_000_000) {
                            lastReport = bytesCopied;
                            // total = 0 means unknown; the caller (fragment) will provide
                            // total from SAF stat via a wrapping callback if known.
                            progress.onProgress(bytesCopied, 0);
                        }
                        // Honor a cancel request promptly, exactly like the backup path: stop
                        // pumping and kill tar so a large archive does not keep draining for
                        // minutes after the user tapped cancel.
                        if (cancelled != null && cancelled.get()) {
                            process.destroy();
                            return;
                        }
                    }
                } catch (IOException e) {
                    pumpError.set(e);
                    Logger.logStackTraceWithMessage(LOG_TAG, "Error feeding tar input stream", e);
                    process.destroy();
                }
            });
            dataPump.start();

            // If cancellation was requested, kill tar now and report a clean "cancelled".
            if (cancelled != null && cancelled.get()) {
                process.destroy();
                rollbackRestore(filesDir);
                listener.onResult(CANCELLED_ERROR);
                return;
            }

            final int exitCode = process.waitFor();
            errPump.join();
            dataPump.join();

            // Diagnostic: state AFTER extraction.
            logDirState("AFTER extract (exit=" + exitCode + ")", filesDir);
            logDirState("AFTER extract (tar binary)", tarBinary);

            IOException pErr = pumpError.get();
            if (pErr != null) {
                rollbackRestore(filesDir);
                listener.onResult(new Error(context.getString(com.termux.R.string.error_restore_io, pErr.getMessage()), pErr));
            } else if (cancelled != null && cancelled.get()) {
                // Cancelled mid-pump (process.destroy() above): report a clean cancel and roll
                // back the partially-extracted files, exactly like the pre-pump cancel branch.
                rollbackRestore(filesDir);
                listener.onResult(CANCELLED_ERROR);
            } else if (exitCode == 0) {
                listener.onResult(null);
            } else {
                rollbackRestore(filesDir);
                String msg = stderr.toString().trim();
                if (msg.isEmpty()) msg = context.getString(com.termux.R.string.error_tar_exit_code, exitCode);
                listener.onResult(new Error(msg));
            }
        } catch (Exception e) {
            rollbackRestore(filesDir);
            listener.onResult(new Error(e.getMessage(), e));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void rollbackRestore(final String filesDir) {
        logDirState("ROLLBACK before", filesDir);
        // Clear contents (keep the directory inode stable) instead of deleting the dir itself.
        clearDirectoryContents("restore_rollback_partial", filesDir);
        if (!new File(filesDir).exists()) {
            if (!new File(filesDir).mkdirs()) {
                Logger.logError(LOG_TAG, "Rollback: failed to recreate " + filesDir);
            }
        }
        logDirState("ROLLBACK after", filesDir);
    }

    // -----------------------------------------------------------------------
    // Shared runner (used by backup)
    // -----------------------------------------------------------------------

    private static void runTar(@NonNull Context context, String[] command,
                               @Nullable InputStream in, @Nullable OutputStream out,
                               @NonNull ResultListener listener,
                               @Nullable ProgressCallback progress,
                               @Nullable File workingDir,
                               long totalBytes,
                               @Nullable AtomicBoolean cancelled) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            setupTarEnvironment(context, pb, command[0]);
            pb.directory(workingDir != null
                ? workingDir
                : new File(isSystemTar(command[0])
                    ? context.getFilesDir().getAbsolutePath()
                    : context.getFilesDir().getAbsolutePath() + "/usr"));
            final Process process = pb.start();

            final StringBuilder stderr = new StringBuilder();
            final Thread errPump = new Thread(() -> {
                try (InputStream e = process.getErrorStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    // Cap the retained stderr: extracting tens of thousands of files can emit
                    // megabytes of warnings, which previously accumulated in memory for the
                    // whole operation.
                    while ((n = e.read(buf)) > 0) {
                        if (stderr.length() < MAX_STDERR_CHARS)
                            stderr.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) {
                }
            });
            errPump.start();

            final AtomicReference<IOException> pumpError = new AtomicReference<>();
            final Thread dataPump;
            if (out != null) {
                final OutputStream finalOut = out;
                dataPump = new Thread(() -> {
                    long bytesCopied = 0;
                    long lastReport = 0;
                    // GZIPOutputStream's internal DeflaterOutputStream buffer is only 512 bytes:
                    // unbuffered it issued a write() (Binder/SAF transaction) every ~512 bytes.
                    // Wrapping in a 64 KiB BufferedOutputStream cuts syscalls by ~2 orders of
                    // magnitude on a multi-GB backup.
                    try (OutputStream gz = new java.util.zip.GZIPOutputStream(
                             new java.io.BufferedOutputStream(finalOut, 1 << 16));
                         OutputStream o = gz;
                         InputStream p = process.getInputStream()) {
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = p.read(buf)) > 0) {
                            o.write(buf, 0, n);
                            // count the raw (uncompressed) tar bytes so progress lines
                            // up with the du-based estimate and reaches ~100% at the end.
                            bytesCopied += n;
                            if (progress != null && bytesCopied - lastReport >= 1_000_000) {
                                lastReport = bytesCopied;
                                progress.onProgress(bytesCopied, totalBytes);
                            }
                            // Honor a cancel request promptly: stop pumping and kill tar.
                            if (cancelled != null && cancelled.get()) {
                                process.destroy();
                                return;
                            }
                        }
                        // Snap to 100% exactly at true EOF (tar stream fully drained) by
                        // reporting the actual total (= bytesCopied) so the bar reaches 100%
                        // even when the pre-job estimate was slightly too large.
                        if (progress != null) progress.onProgress(bytesCopied, bytesCopied);
                    } catch (IOException e) {
                        pumpError.set(e);
                        Logger.logStackTraceWithMessage(LOG_TAG, "Error reading tar output stream", e);
                        process.destroy();
                    }
                });
            } else {
                final InputStream finalIn = in;
                dataPump = new Thread(() -> {
                    long bytesCopied = 0;
                    long lastReport = 0;
                    try (InputStream i = finalIn; OutputStream p = process.getOutputStream()) {
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = i.read(buf)) > 0) {
                            p.write(buf, 0, n);
                            bytesCopied += n;
                            if (progress != null && bytesCopied - lastReport >= 1_000_000) {
                                lastReport = bytesCopied;
                                progress.onProgress(bytesCopied, totalBytes);
                            }
                        }
                        // Snap to 100% exactly at EOF (the loop only reports every 1MB, leaving
                        // the last partial chunk short of full).
                        if (progress != null) progress.onProgress(bytesCopied, bytesCopied);
                    } catch (IOException e) {
                        pumpError.set(e);
                        Logger.logStackTraceWithMessage(LOG_TAG, "Error feeding tar input stream", e);
                        process.destroy();
                    }
                });
            }
            dataPump.start();

            // If cancellation arrived before tar finished, kill it and report cleanly.
            if (cancelled != null && cancelled.get()) {
                process.destroy();
                listener.onResult(CANCELLED_ERROR);
                return;
            }

            final int exitCode = process.waitFor();
            errPump.join();
            dataPump.join();

            IOException pErr = pumpError.get();
            if (pErr != null) {
                listener.onResult(new Error(context.getString(com.termux.R.string.error_backup_io, pErr.getMessage()), pErr));
            } else if (cancelled != null && cancelled.get()) {
                process.destroy();
                listener.onResult(CANCELLED_ERROR);
            } else if (exitCode == 0) {
                listener.onResult(null);
            } else {
                String msg = stderr.toString().trim();
                if (msg.isEmpty()) msg = context.getString(com.termux.R.string.error_tar_exit_code, exitCode);
                listener.onResult(new Error(msg));
            }
        } catch (Exception e) {
            listener.onResult(new Error(e.getMessage(), e));
        }
    }
}
