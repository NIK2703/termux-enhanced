package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;
import android.util.Pair;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxBootstrapType;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.installer.AbiUtils;
import com.termux.installer.BootstrapManifest;
import com.termux.installer.TermuxBootstrapState;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipFile;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;

public final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";
    private static final String DEBUG_LOG_PATH = "/storage/emulated/0/Download/termux-bootstrap-debug.log";

    // ── File-based debug logger ──

    /** Reused formatter — {@code DateFormat.getDateTimeInstance} allocated (and locale-resolved)
     * a brand new instance on every call, and this runs every 200 entries during extraction. */
    private static final java.text.DateFormat DEBUG_DATE_FORMAT =
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM);

    private static void debugLog(String msg) {
        String stamp;
        synchronized (DEBUG_DATE_FORMAT) {
            stamp = DEBUG_DATE_FORMAT.format(new java.util.Date());
        }
        String line = stamp + " [" + LOG_TAG + "] " + msg + "\n";
        try (java.io.FileWriter fw = new java.io.FileWriter(DEBUG_LOG_PATH, true)) {
            fw.write(line);
        } catch (Exception ignored) {}
        Logger.logInfo(LOG_TAG, msg);
    }

    private static void debugLogError(String msg, Throwable t) {
        debugLog(msg + ": " + android.util.Log.getStackTraceString(t));
    }

    private static final long MAX_TOTAL_UNCOMPRESSED_SIZE = 512L * 1024 * 1024;
    private static final int MAX_ENTRIES = 50000;
    private static final long MAX_SINGLE_ENTRY_SIZE = 256L * 1024 * 1024;
    private static final int MAX_COMPRESSION_RATIO = 200;
    private static final int MAX_COMPRESSION_RATIO_NIX = 50000;

    private static final AtomicBoolean sInstallInProgress = new AtomicBoolean(false);

    // ── Home directory helpers ──

    private static void ensureHomeDirectory(Context context) {
        File homeDir = new File(context.getFilesDir(), "home");
        if (!homeDir.isDirectory()) {
            if (!homeDir.mkdirs()) {
                Logger.logError(LOG_TAG, "Failed to create home directory: " + homeDir);
                return;
            }
            Logger.logInfo(LOG_TAG, "Created home directory: " + homeDir);
        }
        File termuxConfigDir = new File(homeDir, ".termux");
        if (!termuxConfigDir.isDirectory()) {
            if (!termuxConfigDir.mkdirs()) {
                Logger.logError(LOG_TAG, "Failed to create .termux config directory: " + termuxConfigDir);
                return;
            }
            Logger.logInfo(LOG_TAG, "Created config directory: " + termuxConfigDir);
        }
    }

    private static void ensureNixRuntimeDirs(Context context) {
        File nixRoot = new File(context.getFilesDir(), "nix-root");
        if (!nixRoot.isDirectory()) return;
        // Proot mount point directories needed inside the rootfs
        for (String name : new String[]{"tmp", "home", "proc", "dev", "sys", "dev/pts", ".l2s"}) {
            File dir = new File(nixRoot, name);
            if (!dir.isDirectory()) {
                if (dir.mkdirs()) {
                    Logger.logInfo(LOG_TAG, "Created Nix runtime dir: " + dir);
                }
            }
        }

        // The zip stores read-only dir modes (dr-xr-xr-x). proot needs a
        // writable temp dir for its glue rootfs. With the official extraction
        // flow (FileOutputStream/mkdirs) modes are already 0755, so these
        // chmods only matter for installs done by older installers.
        chmodWritable(new File(nixRoot, "tmp"), 0777);
        chmodWritable(new File(nixRoot, ".l2s"), 0777);
        chmodWritable(new File(nixRoot, "dev/shm"), 0777);
        chmodWritable(new File(context.getFilesDir(), "home"), 0770);

        // Migration only: old installers applied the zip's read-only modes
        // (dr-xr-xr-x) to nix/store, which breaks nix-env. Fresh installs via
        // the official flow are already owner-writable.
        File storeDir = new File(nixRoot, "nix/store");
        if (storeDir.isDirectory() && !storeDir.canWrite()) {
            Logger.logInfo(LOG_TAG, "nix/store not owner-writable; fixing modes (migration from old installer)");
            chmodRecursive(new File(nixRoot, "nix"), 0775);
        }
    }

    private static void chmodWritable(File dir, int mode) {
        if (dir == null || !dir.isDirectory()) return;
        try {
            Os.chmod(dir.getAbsolutePath(), mode);
        } catch (Exception e) {
            Logger.logDebug(LOG_TAG, "chmodWritable failed for " + dir + ": " + e.getMessage());
        }
    }

    /** Make the subtree owner-writable while keeping the exec/read bits from 0555/0444 dirs. */
    private static void chmodRecursive(File dir, int mode) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            try {
                Os.chmod(child.getAbsolutePath(), mode);
            } catch (Exception ignored) {}
            if (child.isDirectory()) {
                chmodRecursive(child, mode);
            }
        }
    }

    private static final String NIX_PATH_PATCH_BEGIN = "# BEGIN com.termux.debug nix path patch";
    private static final String NIX_PATH_PATCH_END = "# END com.termux.debug nix path patch";
    private static final String LEGACY_NIX_PACKAGE_PREFIX = "/data/data/com.termux.nix";
    private static final java.util.regex.Pattern NIX_PATH_PATCH_BLOCK_PATTERN =
        java.util.regex.Pattern.compile(
            java.util.regex.Pattern.quote(NIX_PATH_PATCH_BEGIN) + ".*?"
                + java.util.regex.Pattern.quote(NIX_PATH_PATCH_END),
            java.util.regex.Pattern.DOTALL);

    private static final String NIX_PROOT_REL_PATH = "bin/proot-static";
    private static final String NIX_PROOT_NEW_REL_PATH = "bin/.proot-static.new";
    private static final String NIX_PROOT_BACKUP_NAME = "proot-static.bootstrap";
    private static final String NIX_PROOT_BACKUP_SHA1_NAME = "proot-static.bootstrap.sha1";
    private static final String PROOT_SELFUPDATE_DISABLE_MARKER = "# self-update disabled (fork)";

    private static String getNixDataDir(String filesDir) {
        return filesDir.endsWith("/files")
            ? filesDir.substring(0, filesDir.length() - "/files".length()) : filesDir;
    }

    /** Order matters: the more specific prefixes must be replaced first. */
    private static String[] getNixPathPairs(String filesDir, String dataDir) {
        return new String[]{
            "/data/data/com.termux.nix/files/usr", filesDir + "/nix-root",
            "/data/data/com.termux.nix/files/home", filesDir + "/home",
            "/data/data/com.termux.nix", dataDir
        };
    }

    private static String replaceNixPaths(String content, String[] pairs) {
        String patched = content;
        for (int i = 0; i < pairs.length; i += 2) {
            patched = patched.replace(pairs[i], pairs[i + 1]);
        }
        return patched;
    }

    /**
     * Nix-on-Droid bootstrap scripts (bin/login, usr/lib/login-inner) hardcode
     * the official package data dir /data/data/com.termux.nix and the official
     * bootstrap root files/usr. Rewrite them to the actual runtime layout
     * (filesDir + "/nix-root") for fork/debug builds so HOME / PROOT_TMP_DIR /
     * .nix-profile / proot binds point at the right place.
     */
    private static void patchNixHardcodedPaths(File tempDir, Context context) {
        File login = new File(tempDir, "bin/login");
        if (login.isFile()) patchNixLogin(login, context);
        File loginInner = new File(tempDir, "usr/lib/login-inner");
        if (loginInner.isFile()) patchLoginInner(loginInner, context);
    }

    /**
     * Re-apply the Nix path patches before every NIX session. nix-on-droid
     * activation replaces bin/login with the official unpatched launcher and
     * stages a fresh usr/lib/.login-inner.new (which login moves into place
     * at the next start), so install-time patching alone is not enough.
     * Idempotent and cheap; safe to call on every session start.
     */
    public static void prepareNixSessionLocked(Context context) {
        try {
            if (TermuxBootstrapType.getInstalledType(context.getFilesDir()) != TermuxBootstrapType.NIX) return;
            File nixRoot = new File(context.getFilesDir(), "nix-root");
            if (!nixRoot.isDirectory()) return;
            enforceWorkingProotStatic(nixRoot, context.getFilesDir());
            File login = new File(nixRoot, "bin/login");
            if (login.isFile()) patchNixLogin(login, context);
            File loginInnerNew = new File(nixRoot, "usr/lib/.login-inner.new");
            if (loginInnerNew.isFile()) patchLoginInner(loginInnerNew, context);
            File loginInner = new File(nixRoot, "usr/lib/login-inner");
            if (loginInner.isFile()) patchLoginInner(loginInner, context);
            ensureNixRuntimeDirs(context);
        } catch (Exception e) {
            debugLogError("prepareNixSessionLocked failed", e);
        }
    }

    /**
     * The upstream login.nix self-updates proot-static on every generation
     * switch ({@code mv bin/.proot-static.new bin/proot-static}). The staged
     * binary from a fresh generation crashes with "proot info: vpid 1:
     * terminated with signal 11" on this fork (Android 7), while the original
     * bootstrap binary works, so before every NIX session we drop the staged
     * .new and restore the known-good bootstrap binary (backed up at install
     * time) whenever bin/proot-static is missing or differs from the backup.
     */
    /** Last verified proot-static identity: {@code path:mtime:size}. */
    private static volatile String sProotVerifiedKey;

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    static void enforceWorkingProotStatic(File nixRoot, File filesDir) {
        try {
            File proot = new File(nixRoot, NIX_PROOT_REL_PATH);
            File prootNew = new File(nixRoot, NIX_PROOT_NEW_REL_PATH);
            if (prootNew.isFile()) {
                FileUtils.deleteFile("staged proot-static.new", prootNew.getAbsolutePath(), true);
                debugLog("removed staged " + NIX_PROOT_NEW_REL_PATH + " (self-update disabled)");
            }
            File backup = new File(filesDir, NIX_PROOT_BACKUP_NAME);
            if (!backup.isFile()) return;

            // Hashing proot-static (several MB) on every session start — while the caller holds
            // the service lock — is pointless when the binary has not changed since the last
            // successful check. Key the cache on (mtime, size).
            if (proot.isFile()) {
                String prootKey = proot.getAbsolutePath() + ":" + proot.lastModified() + ":" + proot.length();
                if (prootKey.equals(sProotVerifiedKey)) return;
                String expectedSha1 = readTrimmed(new File(filesDir, NIX_PROOT_BACKUP_SHA1_NAME));
                String currentSha1 = sha1Hex(proot);
                if (expectedSha1 != null && currentSha1 != null && expectedSha1.equals(currentSha1)) {
                    sProotVerifiedKey = prootKey;
                    return;
                }
            }

            String expectedSha1 = readTrimmed(new File(filesDir, NIX_PROOT_BACKUP_SHA1_NAME));
            String currentSha1 = proot.isFile() ? sha1Hex(proot) : null;
            if (proot.isFile() && expectedSha1 != null && currentSha1 != null
                    && expectedSha1.equals(currentSha1)) {
                sProotVerifiedKey = proot.getAbsolutePath() + ":" + proot.lastModified() + ":" + proot.length();
                return;
            }
            copyFile(backup, proot);
            Os.chmod(proot.getAbsolutePath(), 0700);
            debugLog("restored bootstrap " + NIX_PROOT_REL_PATH
                + " (was " + (currentSha1 == null ? "missing" : currentSha1)
                + ", expected " + expectedSha1 + ")");
        } catch (Exception e) {
            debugLogError("enforceWorkingProotStatic failed", e);
        }
    }

    /** Save the working bootstrap proot-static binary before it can be replaced. */
    private static void backupBootstrapProotStatic(File tempDir, Context context) throws IOException {
        File proot = new File(tempDir, NIX_PROOT_REL_PATH);
        if (!proot.isFile()) {
            debugLog("backupBootstrapProotStatic: no " + NIX_PROOT_REL_PATH + " in bootstrap");
            return;
        }
        File backup = new File(context.getFilesDir(), NIX_PROOT_BACKUP_NAME);
        copyFile(proot, backup);
        try { Os.chmod(backup.getAbsolutePath(), 0700); } catch (Exception ignored) {}
        String sha1 = sha1Hex(backup);
        File shaFile = new File(context.getFilesDir(), NIX_PROOT_BACKUP_SHA1_NAME);
        java.nio.file.Files.write(shaFile.toPath(), (sha1 + "\n").getBytes(StandardCharsets.US_ASCII));
        debugLog("backed up bootstrap proot-static -> " + backup.getName() + " sha1=" + sha1);
    }

    private static String readTrimmed(File file) throws IOException {
        if (!file.isFile()) return null;
        return new String(java.nio.file.Files.readAllBytes(file.toPath()),
            StandardCharsets.US_ASCII).trim();
    }

    private static String sha1Hex(File file) throws IOException {
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-1");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 not available", e);
        }
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder(40);
        for (byte b : md.digest()) {
            sb.append(HEX_CHARS[(b >> 4) & 0xf]).append(HEX_CHARS[b & 0xf]);
        }
        return sb.toString();
    }

    private static void copyFile(File from, File to) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(from);
             java.io.FileOutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** Path substitution for the host launcher bin/login (idempotent). */
    static void patchNixLogin(File login, Context context) {
        String filesDir = context.getFilesDir().getAbsolutePath();
        String[] pairs = getNixPathPairs(filesDir, getNixDataDir(filesDir));
        try {
            String content = new String(java.nio.file.Files.readAllBytes(login.toPath()), StandardCharsets.UTF_8);
            String patched = replaceNixPaths(content, pairs);
            patched = disableProotStaticSelfUpdate(patched);
            if (!patched.equals(content)) {
                java.nio.file.Files.write(login.toPath(), patched.getBytes(StandardCharsets.UTF_8));
                debugLog("patched hardcoded nix dir in bin/login");
            }
            try { Os.chmod(login.getAbsolutePath(), 0700); } catch (Exception ignored) {}
        } catch (Exception e) {
            debugLogError("failed to patch bin/login", e);
        }
    }

    /**
     * Neutralize the upstream proot-static self-update in bin/login. The staged
     * .proot-static.new from a new generation crashes with signal 11 on this
     * fork, so the mv must never run. Idempotent: rewrites the guard to
     * "if false", drops single-line if/mv forms and standalone mv lines.
     */
    static String disableProotStaticSelfUpdate(String content) {
        if (content.contains(PROOT_SELFUPDATE_DISABLE_MARKER)) return content;
        // Multi-line form:  if test -e <path>/.proot-static.new; then / if [ -e ... ]; then
        String patched = content.replaceAll(
            "(?m)^(\\s*)if\\s+(?:test\\s+-e|\\[\\s+-e)\\s+.*\\.proot-static\\.new.*?;?\\s*then\\s*$",
            "$1if false; then " + PROOT_SELFUPDATE_DISABLE_MARKER);
        // Single-line form:  if [ -e X ]; then mv X Y; fi
        patched = patched.replaceAll(
            "(?m)^(\\s*)if\\s+(?:test\\s+-e|\\[\\s+-e)\\s+.*\\.proot-static\\.new.*\\bthen\\b.*;\\s*fi\\s*$",
            "$1# proot-static self-update disabled (fork)");
        // Standalone mv of .proot-static.new (guard-less future formats)
        patched = patched.replaceAll(
            "(?m)^(\\s*)(?:[^\\n\\s]*/)?(?:mv|busybox mv)\\s+\\S*\\.proot-static\\.new\\s+\\S*\\s*$",
            "$1true " + PROOT_SELFUPDATE_DISABLE_MARKER);
        return patched;
    }

    private static String shellSingleQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Function that sources ~/.nix-profile/etc/profile.d/nix-on-droid-session-init.sh
     * with /data/data/com.termux.nix rewritten to this fork's data dir. The
     * script in the nix store is generated by the upstream nix-on-droid module
     * with hardcoded official paths and is recreated on every switch, so it
     * cannot be patched at install time.
     */
    private static String buildSessionInitPatchFunction(String oldPrefix, String newDataDir) {
        return String.join("\n",
            NIX_PATH_PATCH_BEGIN,
            "_nod_source_session_init() {",
            "  _nod_si=\"$HOME/.nix-profile/etc/profile.d/nix-on-droid-session-init.sh\"",
            "  if [ ! -f \"$_nod_si\" ]; then",
            "    return 0",
            "  fi",
            "  _nod_old=" + shellSingleQuote(oldPrefix),
            "  _nod_new=" + shellSingleQuote(newDataDir),
            "  _nod_out=\"${TMPDIR:-/tmp}/nod-session-init.patched.$$\"",
            "  if _nod_content=\"$(<\"$_nod_si\")\"; then",
            "    case \"$_nod_content\" in",
            "      *\"$_nod_old\"*)",
            "        _nod_content=\"${_nod_content//$_nod_old/$_nod_new}\"",
            "        if printf '%s\\n' \"$_nod_content\" > \"$_nod_out\"; then",
            "          . \"$_nod_out\"",
            "          return $?",
            "        fi",
            "        ;;",
            "    esac",
            "  fi",
            "  . \"$_nod_si\"",
            "}",
            "# END com.termux.debug nix path patch",
            "");
    }

    /**
     * Harden usr/lib/login-inner: rewrite hardcoded paths, source the
     * session-init script through the runtime patcher function, and fall back
     * to /bin/sh if /usr/bin/env is missing. Idempotent; safe to re-apply
     * before every session (activation stages .login-inner.new on switch).
     */
    static void patchLoginInner(File loginInner, Context context) {
        String filesDir = context.getFilesDir().getAbsolutePath();
        String dataDir = getNixDataDir(filesDir);
        String home = filesDir + "/home";
        try {
            String content = new String(java.nio.file.Files.readAllBytes(loginInner.toPath()), StandardCharsets.UTF_8);
            if (content.contains(NIX_PATH_PATCH_BEGIN)) return;
            content = replaceNixPaths(content, getNixPathPairs(filesDir, dataDir));

            String function = buildSessionInitPatchFunction("/data/data/com.termux.nix", dataDir);
            content = content.replace("set -eo pipefail", "set -eo pipefail\n" + function);

            // Replace every session-init source line with the patcher function.
            String sessionInit = home + "/.nix-profile/etc/profile.d/nix-on-droid-session-init.sh";
            String guarded = "if [ -f \"" + sessionInit + "\" ]; then\n  . \"" + sessionInit + "\"\nfi";
            content = content.replace(guarded, "_nod_source_session_init");
            content = content.replace(". \"" + sessionInit + "\"", "_nod_source_session_init");
            content = content.replace(". \"$HOME/.nix-profile/etc/profile.d/nix-on-droid-session-init.sh\"",
                "_nod_source_session_init");
            content = content.replace(". \"${config.user.home}/.nix-profile/etc/profile.d/nix-on-droid-session-init.sh\"",
                "_nod_source_session_init");

            content = content.replace("exec /usr/bin/env bash",
                "if [ -x /usr/bin/env ]; then\n  exec /usr/bin/env bash\nelse\n  exec /bin/sh\nfi");

            java.nio.file.Files.write(loginInner.toPath(), content.getBytes(StandardCharsets.UTF_8));
            debugLog("patched login-inner (paths + session-init runtime patch)");
        } catch (Exception e) {
            debugLogError("failed to patch login-inner", e);
        }
    }

    // ── Bootstrap type marker ──

    private static final String BOOTSTRAP_TYPE_MARKER = ".termux-bootstrap-type";

    // ── Bootstrap package-name substitution (fork support) ──

    /** The package name that the bootstrap zip expects (from termux-packages). */
    static final String BOOTSTRAP_TARGET_PKG = TermuxConstants.TERMUX_BOOTSTRAP_TARGET_PACKAGE_NAME;

    /** Whether the actual app package differs from the bootstrap target package. */
    private static boolean needsPackageSubstitution(Context context) {
        return !BOOTSTRAP_TARGET_PKG.equals(context.getPackageName());
    }

    /** Prefix path inside the bootstrap zip (always points to the target package). */
    private static String getBootstrapPrefixPath() {
        return "/data/data/" + BOOTSTRAP_TARGET_PKG + "/files/usr";
    }

    /** Prefix path that the actual app expects at runtime. */
    private static String getActualPrefixPath(Context context) {
        return "/data/data/" + context.getPackageName() + "/files/usr";
    }

    /** Data-dir path inside the bootstrap zip. */
    private static String getBootstrapDataDirPath() {
        return "/data/data/" + BOOTSTRAP_TARGET_PKG;
    }

    /** Data-dir path that the actual app uses. */
    private static String getActualDataDirPath(Context context) {
        return "/data/data/" + context.getPackageName();
    }

    /** Bootstrap files dir, e.g. /data/data/com.termux/files (27 bytes). */
    private static String getBootstrapFilesDirPath() {
        return "/data/data/" + BOOTSTRAP_TARGET_PKG + "/files";
    }

    /** Actual files dir, normalized to /data/data/<pkg>/files. */
    private static String getActualFilesDirPath(Context context) {
        return context.getFilesDir().getAbsolutePath().replaceFirst("^/data/user/0/", "/data/data/");
    }

    /**
     * Same-length compatibility path for ELF patching.
     *
     * For com.termux.debug:
     *   old: /data/data/com.termux/files         27 bytes
     *   new: /data/data/com.termux.debug         27 bytes ← SAME LENGTH
     *
     * Symlinks from the compat dir to the real files dir resolve at runtime.
     *
     * @return same-length path, or null if lengths differ and padding is impossible.
     */
    @Nullable
    private static String getCompatFilesDirPath(Context context) {
        String oldFiles = getBootstrapFilesDirPath();
        String actualData = getActualDataDirPath(context);

        if (actualData.length() == oldFiles.length()) {
            return actualData;
        }

        if (actualData.length() < oldFiles.length()) {
            int needed = oldFiles.length() - actualData.length() - 1;
            if (needed >= 1) {
                return actualData + "/" + "x".repeat(needed);
            }
        }

        return null;
    }

    /**
     * Replace all occurrences of bootstrap paths with actual paths in a string.
     * Uses boundary-aware replacement to prevent partial-substring corruption.
     */
    private static String replacePrefixInString(String s, String oldPrefix, String newPrefix,
            String oldDataDir, String newDataDir) {
        if (s == null || s.isEmpty()) return s;
        s = replacePathPrefix(s, oldPrefix, newPrefix);
        s = replacePathPrefix(s, oldDataDir, newDataDir);
        return s;
    }

    // ── Boundary-aware string replacement ──

    private static boolean isPathBoundaryChar(char c) {
        return c == '/' || c == '\0' || c == ':' || c == ' ' || c == '\t'
            || c == '\r' || c == '\n' || c == '"' || c == '\'' || c == '('
            || c == ')' || c == '[' || c == ']' || c == '<' || c == '>'
            || c == ',' || c == ';';
    }

    private static boolean isPathBoundaryByte(byte b) {
        return b == (byte) '/' || b == (byte) '\0' || b == (byte) ':'
            || b == (byte) ' ' || b == (byte) '\t' || b == (byte) '\r'
            || b == (byte) '\n' || b == (byte) '"' || b == (byte) '\''
            || b == (byte) '(' || b == (byte) ')' || b == (byte) '['
            || b == (byte) ']' || b == (byte) '<' || b == (byte) '>'
            || b == (byte) ',' || b == (byte) ';';
    }

    /**
     * Replace oldPrefix with newPrefix only when oldPrefix ends at a path boundary.
     * Prevents corruption like com.termux.debug → com.termux.debug.debug.
     */
    private static String replacePathPrefix(String text, String oldPrefix, String newPrefix) {
        if (text == null || text.isEmpty() || oldPrefix.equals(newPrefix)) return text;
        StringBuilder sb = new StringBuilder(text.length() + 64);
        int pos = 0;
        while (pos < text.length()) {
            int idx = text.indexOf(oldPrefix, pos);
            if (idx < 0) {
                sb.append(text, pos, text.length());
                break;
            }
            int end = idx + oldPrefix.length();
            boolean boundary = end >= text.length() || isPathBoundaryChar(text.charAt(end));
            if (boundary) {
                sb.append(text, pos, idx);
                sb.append(newPrefix);
                pos = end;
            } else {
                sb.append(text, pos, end);
                pos = end;
            }
        }
        return sb.toString();
    }

    // ── Helpers ──

    private static boolean isSymlink(File file) {
        try {
            return OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Same-length byte-level replacement in a byte array.
     * Only replaces when oldBytes and newBytes have identical length.
     */
    private static byte[] replaceBytesSameLength(byte[] data, byte[] oldBytes, byte[] newBytes) {
        if (oldBytes.length != newBytes.length || oldBytes.length == 0) return data;
        byte[] result = data.clone();
        int matchLen = oldBytes.length;
        for (int i = 0; i <= result.length - matchLen; ) {
            boolean match = true;
            for (int j = 0; j < matchLen; j++) {
                if (result[i + j] != oldBytes[j]) { match = false; break; }
            }
            if (match) {
                System.arraycopy(newBytes, 0, result, i, matchLen);
                i += matchLen;
            } else {
                i++;
            }
        }
        return result;
    }

    /** Heuristic: content without null bytes in first 8 KB is considered text. */
    private static boolean isTextContent(byte[] content) {
        int limit = Math.min(content.length, 8192);
        for (int i = 0; i < limit; i++) {
            if (content[i] == 0) return false;
        }
        return true;
    }

    private static long sPatchFileCount = 0; // counter for debug logging

    /**
     * Post-extraction/retroactive pass: walk the directory tree and patch every regular file.
     * For text files — full String.replace with real runtime paths.
     * For ELF/other binaries — same-length byte-replace (files dir → compat data dir).
     */
    private static void patchPrefixInDirectory(File dir,
                                               String textOldFilesDir, String textNewFilesDir,
                                               String textOldDataDir, String textNewDataDir,
                                               byte[] elfOldFilesDir, byte[] elfNewFilesDir,
                                               int depth) throws IOException {
        if (depth > 20) {
            debugLog("patchPrefixInDirectory: max depth (20) at " + dir.getAbsolutePath());
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            debugLog("patchPrefixInDirectory: cannot list " + dir.getAbsolutePath());
            return;
        }
        for (File f : children) {
            // One lstat instead of isSymlink() + isDirectory() + isFile() (3 syscalls per node)
            // — this walk covers the whole $PREFIX, so the saving is ~2 syscalls x file count.
            StructStat st;
            try {
                st = Os.lstat(f.getAbsolutePath());
            } catch (Exception e) {
                continue;
            }
            if (OsConstants.S_ISLNK(st.st_mode)) continue;
            if (OsConstants.S_ISDIR(st.st_mode)) {
                patchPrefixInDirectory(f, textOldFilesDir, textNewFilesDir,
                    textOldDataDir, textNewDataDir, elfOldFilesDir, elfNewFilesDir, depth + 1);
            } else if (OsConstants.S_ISREG(st.st_mode)) {
                sPatchFileCount++;
                patchFile(f, textOldFilesDir, textNewFilesDir,
                    textOldDataDir, textNewDataDir, elfOldFilesDir, elfNewFilesDir);
                if (sPatchFileCount % 200 == 0) {
                    debugLog("patchPrefixInDirectory: patched " + sPatchFileCount + " files");
                }
            }
        }
    }

    /** Naive byte-array search. Used to reject files that do not contain the prefix at all
     * before paying for a String decode plus three full-content copies in {@link #patchFile}. */
    private static int indexOfBytes(byte[] data, byte[] pattern) {
        if (pattern == null || pattern.length == 0 || data.length < pattern.length) return -1;
        byte first = pattern[0];
        int max = data.length - pattern.length;
        for (int i = 0; i <= max; i++) {
            if (data[i] != first) continue;
            int j = 1;
            while (j < pattern.length && data[i + j] == pattern[j]) j++;
            if (j == pattern.length) return i;
        }
        return -1;
    }

    private static void patchFile(File file,
                                  String textOldFilesDir, String textNewFilesDir,
                                  String textOldDataDir, String textNewDataDir,
                                  byte[] elfOldFilesDir, byte[] elfNewFilesDir) throws IOException {
        long fileLen = file.length();
        if (Logger.isLoggable(Log.DEBUG))
            Logger.logDebug(LOG_TAG, "patchFile: " + file.getAbsolutePath() + " (" + fileLen + " bytes)");
        if (fileLen == 0) return;

        byte[] content;
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream bout = new ByteArrayOutputStream((int) Math.min(fileLen, Integer.MAX_VALUE))) {
            byte[] buf = new byte[8192];
            int n;
            long totalRead = 0;
            while ((n = in.read(buf)) != -1) {
                bout.write(buf, 0, n);
                totalRead += n;
            }
            if (Logger.isLoggable(Log.DEBUG))
                Logger.logDebug(LOG_TAG, "patchFile: read " + totalRead + " bytes from " + file.getAbsolutePath());
            content = bout.toByteArray();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "patchFile: failed to read " + file.getAbsolutePath() + ": " + e.getMessage());
            return;
        }

        boolean isText = isTextContent(content);
        byte[] patched;

        if (isText) {
            // Fast reject: the vast majority of files do not reference the old prefix. Without
            // this each of them paid for a UTF-8 decode plus three full-content String copies.
            byte[] oldFilesBytes = textOldFilesDir == null ? null : textOldFilesDir.getBytes(StandardCharsets.UTF_8);
            byte[] oldDataBytes = textOldDataDir == null ? null : textOldDataDir.getBytes(StandardCharsets.UTF_8);
            boolean hasPattern = (oldFilesBytes != null && oldFilesBytes.length > 0)
                || (oldDataBytes != null && oldDataBytes.length > 0);
            if (hasPattern
                    && indexOfBytes(content, oldFilesBytes) < 0
                    && indexOfBytes(content, oldDataBytes) < 0) {
                return;
            }
            // Text files: use real runtime paths (any length)
            String text = new String(content, StandardCharsets.UTF_8);
            text = replacePathPrefix(text, textOldFilesDir + "/usr", textNewFilesDir + "/usr");
            text = replacePathPrefix(text, textOldFilesDir, textNewFilesDir);
            text = replacePathPrefix(text, textOldDataDir, textNewDataDir);
            patched = text.getBytes(StandardCharsets.UTF_8);
        } else {
            // ELF binaries: same-length byte replacement only
            patched = content;
            if (elfOldFilesDir != null && elfNewFilesDir != null
                    && elfOldFilesDir.length == elfNewFilesDir.length)
                patched = replaceBytesSameLength(patched, elfOldFilesDir, elfNewFilesDir);
        }

        if (!Arrays.equals(content, patched)) {
            debugLog("patchFile: fixed " + file.getAbsolutePath() + " (" + fileLen + " bytes)");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(patched);
            }
        }
    }

    /** Patch absolute symlink targets under dir (recursive). */
    private static void patchSymlinksInDirectory(File dir,
                                                  String oldFilesDir, String newFilesDir,
                                                  String oldDataDir, String newDataDir,
                                                  int depth) throws IOException {
        if (depth > 20) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (isSymlink(f)) {
                try {
                    String target = Os.readlink(f.getAbsolutePath());
                    String newTarget = replacePrefixInString(target,
                        oldFilesDir + "/usr", newFilesDir + "/usr",
                        oldDataDir, newDataDir);
                    newTarget = replacePathPrefix(newTarget, oldFilesDir, newFilesDir);
                    if (!newTarget.equals(target)) {
                        if (!f.delete()) {
                            throw new IOException("Failed to delete symlink for retargeting: " + f);
                        }
                        Os.symlink(newTarget, f.getAbsolutePath());
                        Logger.logDebug(LOG_TAG, "Patched symlink: " + f + " -> " + newTarget);
                    }
                } catch (ErrnoException e) {
                    throw new IOException("Failed to patch symlink: " + f, e);
                }
            } else if (f.isDirectory()) {
                patchSymlinksInDirectory(f, oldFilesDir, newFilesDir, oldDataDir, newDataDir, depth + 1);
            }
        }
    }

    // ── Compatibility symlinks for same-length ELF patching ──

    /**
     * Create symlinks from the compat data dir (same length as bootstrap files dir)
     * to the actual runtime files subdirs.
     *
     * E.g. for com.termux.debug:
     *   /data/data/com.termux.debug/usr  -> /data/data/com.termux.debug/files/usr
     *   /data/data/com.termux.debug/home -> /data/data/com.termux.debug/files/home
     *   /data/data/com.termux.debug/apps -> /data/data/com.termux.debug/files/apps
     */
    public static void ensureCompatSymlinks(Context context) throws IOException {
        if (!needsPackageSubstitution(context)) return;
        String compatRootPath = getCompatFilesDirPath(context);
        if (compatRootPath == null) {
            throw new IOException("Cannot create compat symlinks: no same-length path for "
                + getActualDataDirPath(context) + " vs " + getBootstrapFilesDirPath());
        }
        File compatRoot = new File(compatRootPath);
        if (!compatRoot.exists() && !compatRoot.mkdirs()) {
            throw new IOException("Failed to create compat root: " + compatRoot);
        }
        File filesDir = context.getFilesDir();
        for (String name : new String[]{"usr", "home", "apps"}) {
            File target = new File(filesDir, name);
            if (!target.exists()) continue;
            File link = new File(compatRoot, name);
            try {
                if (isSymlink(link)) {
                    String current = Os.readlink(link.getAbsolutePath());
                    if (target.getAbsolutePath().equals(current)) continue;
                    if (!link.delete()) throw new IOException("Failed to delete stale symlink: " + link);
                } else if (link.exists()) {
                    deleteRecursive(link);
                }
                File parent = link.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Failed to create symlink parent: " + parent);
                }
                Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
                Logger.logInfo(LOG_TAG, "Created compat symlink: " + link + " -> " + target);
            } catch (ErrnoException e) {
                throw new IOException("Failed to create compat symlink: " + link, e);
            }
        }
    }

    // ── Path-patch marker (avoids re-patching on every launch) ──

    private static final int PATH_PATCH_VERSION = 1;

    private static File getPathPatchMarker(Context context) {
        return new File(context.getFilesDir(), ".termux-bootstrap-path-patch");
    }

    public static boolean isBootstrapPathPatchApplied(Context context) {
        try {
            File marker = getPathPatchMarker(context);
            if (!marker.exists()) return false;
            String expected = context.getPackageName() + ":" + PATH_PATCH_VERSION;
            byte[] buf = new byte[(int) Math.min(marker.length(), 4096)];
            int n;
            try (FileInputStream in = new FileInputStream(marker)) {
                n = in.read(buf);
            }
            return expected.equals(new String(buf, 0, n, StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return false;
        }
    }

    private static void writePathPatchMarker(Context context) throws IOException {
        String value = context.getPackageName() + ":" + PATH_PATCH_VERSION;
        try (FileOutputStream out = new FileOutputStream(getPathPatchMarker(context))) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ── Bootstrap installation state ──

    public static boolean isBootstrapInstalled() {
        File prefix = TERMUX_PREFIX_DIR;
        if (!prefix.isDirectory()) return false;
        File binDir = new File(prefix, "bin");
        if (!binDir.isDirectory()) return false;
        String[] children = binDir.list();
        return children != null && children.length > 0;
    }

    /**
     * Check whether bootstrap is installed under the runtime package files dir.
     * Does NOT fall back to the compile-time prefix — that would be wrong on fork builds
     * when the original com.termux is installed on the device.
     */
    /** Cache for {@link #isBootstrapInstalled(Context)}: the check lists $PREFIX/bin (a full
     * readdir of hundreds of entries) plus a file read, and was being run several times per
     * start on the main thread. Invalidated explicitly when an install finishes. */
    private static volatile Boolean sBootstrapInstalled;
    private static volatile long sBootstrapInstalledCheckedAt;
    private static final long BOOTSTRAP_CHECK_TTL_MS = 5000L;

    /** Drop the cached bootstrap-installed result (call after an install/removal). */
    public static void invalidateBootstrapInstalledCache() {
        sBootstrapInstalled = null;
        sBootstrapInstalledCheckedAt = 0L;
    }

    public static boolean isBootstrapInstalled(Context context) {
        if (context == null) return false;

        long now = android.os.SystemClock.elapsedRealtime();
        Boolean cached = sBootstrapInstalled;
        if (cached != null && (now - sBootstrapInstalledCheckedAt) < BOOTSTRAP_CHECK_TTL_MS) {
            return cached;
        }

        boolean result = isBootstrapInstalledUncached(context);
        sBootstrapInstalled = result;
        sBootstrapInstalledCheckedAt = now;
        return result;
    }

    private static boolean isBootstrapInstalledUncached(Context context) {
        TermuxBootstrapType type = getInstalledBootstrapType(context);
        if (type == TermuxBootstrapType.NIX) {
            File nixRoot = new File(context.getFilesDir(), "nix-root");
            if (!nixRoot.isDirectory()) return false;
            File proot = new File(nixRoot, "bin/proot-static");
            File nixStore = new File(nixRoot, "nix/store");
            return proot.isFile() && nixStore.isDirectory();
        }

        File prefix = new File(context.getFilesDir(), "usr");
        if (!prefix.isDirectory()) return false;
        File binDir = new File(prefix, "bin");
        if (!binDir.isDirectory()) return false;
        String[] children = binDir.list();
        return children != null && children.length > 0;
    }

    /** Retroactively patch an already-installed bootstrap. */
    public static void patchExistingBootstrapIfNeeded(Context context) throws IOException {
        if (!needsPackageSubstitution(context)) return;
        if (!isBootstrapInstalled(context)) return;
        ensureHomeDirectory(context);

        TermuxBootstrapType type = getInstalledBootstrapType(context);
        if (type == TermuxBootstrapType.NIX) {
            Logger.logInfo(LOG_TAG, "Nix bootstrap detected; skipping Termux path patching");
            return;
        }

        if (isBootstrapPathPatchApplied(context)) return;

        Logger.logInfo(LOG_TAG, "Patching existing bootstrap paths for package substitution");
        debugLog("patchExistingBootstrapIfNeeded: start");

        String oldFilesDir = getBootstrapFilesDirPath();
        String newFilesDir = getActualFilesDirPath(context);
        String oldDataDir = getBootstrapDataDirPath();
        String newDataDir = getActualDataDirPath(context);
        String compatFilesDir = getCompatFilesDirPath(context);

        byte[] elfOldFilesDir = null;
        byte[] elfNewFilesDir = null;
        if (compatFilesDir == null) {
            Logger.logWarn(LOG_TAG, "No same-length compat path for "
                + getBootstrapFilesDirPath() + "; ELF binary patching will be skipped. "
                + "The LD_PRELOAD path-remapping shim (libtermux-prefix-remap.so) must be "
                + "installed under $PREFIX/lib/ for ELF binaries to work.");
        } else {
            elfOldFilesDir = oldFilesDir.getBytes(StandardCharsets.US_ASCII);
            elfNewFilesDir = compatFilesDir.getBytes(StandardCharsets.US_ASCII);
            if (elfOldFilesDir.length != elfNewFilesDir.length) {
                Logger.logWarn(LOG_TAG, "ELF compat path length mismatch: "
                    + oldFilesDir + " (" + elfOldFilesDir.length + ") vs "
                    + compatFilesDir + " (" + elfNewFilesDir.length + "); ELF patching skipped");
                elfOldFilesDir = null;
                elfNewFilesDir = null;
            }
        }

        ensureCompatSymlinks(context);

        File prefixDir = new File(context.getFilesDir(), "usr");
        if (prefixDir.isDirectory()) {
            sPatchFileCount = 0;
            patchPrefixInDirectory(prefixDir,
                oldFilesDir, newFilesDir,
                oldDataDir, newDataDir,
                elfOldFilesDir, elfNewFilesDir, 0);
            patchSymlinksInDirectory(prefixDir,
                oldFilesDir, newFilesDir,
                oldDataDir, newDataDir, 0);
        }

        File homeDir = new File(context.getFilesDir(), "home");
        if (homeDir.isDirectory()) {
            patchPrefixInDirectory(homeDir,
                oldFilesDir, newFilesDir,
                oldDataDir, newDataDir,
                elfOldFilesDir, elfNewFilesDir, 0);
            patchSymlinksInDirectory(homeDir,
                oldFilesDir, newFilesDir,
                oldDataDir, newDataDir, 0);
        }

        writePathPatchMarker(context);
        TermuxShellEnvironment.writeEnvironmentToFile(context);

        Logger.logInfo(LOG_TAG, "Existing bootstrap path patch complete");
        debugLog("patchExistingBootstrapIfNeeded: done");
    }

    public static boolean isPrefixValid() {
        return isBootstrapInstalled();
    }

    public static void cleanupInterruptedInstall() {
        File filesDir = TERMUX_PREFIX_DIR.getParentFile();
        if (filesDir == null || !filesDir.isDirectory()) return;
        File[] candidates = filesDir.listFiles((d, name) ->
            name.startsWith("usr.tmp-") || name.startsWith("usr.old-") ||
            name.startsWith("nix-root.tmp-") || name.startsWith("nix-root.old-") ||
            name.startsWith("nix-root-staging"));
        if (candidates == null) return;
        for (File f : candidates) {
            Logger.logInfo(LOG_TAG, "Cleaning up stale: " + f.getName());
            deleteRecursive(f);
        }
    }

    // ── Bootstrap type marker ──

    private static void writeBootstrapTypeMarker(Context context, TermuxBootstrapType type) {
        try {
            File marker = new File(context.getFilesDir(), BOOTSTRAP_TYPE_MARKER);
            try (FileOutputStream out = new FileOutputStream(marker)) {
                out.write(type.getValue().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to write bootstrap type marker: " + e.getMessage());
        }
    }

    public static TermuxBootstrapType getInstalledBootstrapType(Context context) {
        try {
            File marker = new File(context.getFilesDir(), BOOTSTRAP_TYPE_MARKER);
            if (!marker.exists()) return TermuxBootstrapType.TERMUX;
            byte[] buf = new byte[64];
            int n;
            try (FileInputStream in = new FileInputStream(marker)) {
                n = in.read(buf);
            }
            if (n <= 0) return TermuxBootstrapType.TERMUX;
            String value = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
            if ("nix".equals(value)) return TermuxBootstrapType.NIX;
            return TermuxBootstrapType.TERMUX;
        } catch (Exception e) {
            return TermuxBootstrapType.TERMUX;
        }
    }

    private static TermuxBootstrapType detectBootstrapTypeFromZip(File zipFile) {
        Set<String> entryNames = new HashSet<>();
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int count = 0;
            while (entries.hasMoreElements() && count < 500) {
                entryNames.add(entries.nextElement().getName());
                count++;
            }
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "detectBootstrapTypeFromZip failed: " + e.getMessage());
        }
        return TermuxBootstrapType.fromZipEntries(entryNames);
    }

    private static TermuxBootstrapType detectBootstrapTypeFromZipBytes(byte[] zipBytes) {
        Set<String> entryNames = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zis.getNextEntry()) != null && count < 500) {
                entryNames.add(entry.getName());
                count++;
            }
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "detectBootstrapTypeFromZipBytes: " + e.getMessage());
        }
        return TermuxBootstrapType.fromZipEntries(entryNames);
    }

    public interface InstallProgressListener {
        void onProgress(String stage, int percent);
    }

    public static void installBootstrapFromZipFile(Context context, File zipFile,
            InstallProgressListener listener) throws IOException, BootstrapException {
        if (!sInstallInProgress.compareAndSet(false, true)) {
            throw new BootstrapException(context.getString(com.termux.R.string.error_bootstrap_install_in_progress));
        }
        try {
            installBootstrapFromZipFileLocked(context, zipFile, listener);
        } finally {
            sInstallInProgress.set(false);
        }
    }

    private static void installBootstrapFromZipFileLocked(Context context, File zipFile,
            InstallProgressListener listener) throws IOException, BootstrapException {
        // Reset debug log
        try { new java.io.FileWriter(DEBUG_LOG_PATH, false).close(); } catch (Exception ignored) {}
        debugLog("=== Bootstrap install start: zip=" + zipFile.getAbsolutePath());

        if (!zipFile.isFile()) {
            throw new IOException(context.getString(com.termux.R.string.error_bootstrap_zip_not_found, zipFile.getAbsolutePath()));
        }

        if (listener != null) listener.onProgress(context.getString(com.termux.R.string.bootstrap_install_progress_verify_manifest), 0);

        BootstrapManifest manifest = BootstrapManifest.fromZip(context, zipFile);
        TermuxBootstrapType bootstrapType;
        if (manifest != null) {
            AbiUtils.validateBootstrapArch(context, manifest.arch);
            bootstrapType = TermuxBootstrapType.fromVariant(manifest.variant);
            debugLog("manifest: arch=" + manifest.arch + " variant=" + manifest.variant
                + " type=" + bootstrapType);
        } else {
            bootstrapType = detectBootstrapTypeFromZip(zipFile);
            debugLog("No BOOTSTRAP_INFO, detected type from entries: " + bootstrapType);
        }

        File filesDir = context.getFilesDir();
        String targetName = (bootstrapType == TermuxBootstrapType.NIX) ? "nix-root" : "usr";
        String tmpName = targetName + ".tmp-" + UUID.randomUUID().toString();
        File tempDir = new File(filesDir, tmpName);
        File backupDir = null;

        try {
            if (!tempDir.mkdirs()) {
                throw new IOException(context.getString(com.termux.R.string.error_bootstrap_create_temp_dir, tempDir));
            }
            debugLog("tempDir=" + tempDir + " targetType=" + bootstrapType);

            if (listener != null) listener.onProgress(context.getString(com.termux.R.string.bootstrap_install_progress_extract), 10);

            if (bootstrapType == TermuxBootstrapType.NIX) {
                // Official nix-on-droid-app installer flow (copied from
                // nix-community/nix-on-droid-app TermuxInstaller.java):
                // zip modes are IGNORED (ZipInputStream -> FileOutputStream /
                // mkdirs give umask modes 0644/0755), then EXECUTABLES.txt ->
                // chmod 0700 and SYMLINKS.txt -> Os.symlink. This avoids the
                // read-only dr-xr-xr-x modes stored in the nix bootstrap zip.
                debugLog("NIX: using official extraction flow");
                extractNixBootstrapZipOfficial(context, zipFile, tempDir, listener);
                if (listener != null) listener.onProgress(context.getString(com.termux.R.string.bootstrap_install_progress_finalize), 92);
                debugLog("NIX: extraction done, setting up executables");
                setupNixExecutables(tempDir);
                backupBootstrapProotStatic(tempDir, context);
                if (listener != null) listener.onProgress(context.getString(com.termux.R.string.bootstrap_install_progress_finalize), 95);
                debugLog("NIX: executables done, setting up symlinks");
                setupNixSymlinks(tempDir);
                if (listener != null) listener.onProgress(context.getString(com.termux.R.string.bootstrap_install_progress_finalize), 97);
                debugLog("NIX: symlinks done, patching hardcoded paths");
                patchNixHardcodedPaths(tempDir, context);
                if (listener != null) listener.onProgress(context.getString(com.termux.R.string.bootstrap_install_progress_finalize), 99);
                verifyNixBootstrap(tempDir);
            } else {
                debugLog("Starting extractZipFile...");
                extractZipFile(context, zipFile, tempDir, listener, bootstrapType);
                debugLog("extractZipFile done");
            }

            if (bootstrapType == TermuxBootstrapType.TERMUX && !BOOTSTRAP_TARGET_PKG.equals(context.getPackageName())) {
                if (listener != null) listener.onProgress(context.getString(com.termux.R.string.bootstrap_install_progress_finalize), 85);
                String oldFilesDir = getBootstrapFilesDirPath();
                String newFilesDir = getActualFilesDirPath(context);
                String oldDataDir = getBootstrapDataDirPath();
                String newDataDir = getActualDataDirPath(context);
                String compatFilesDir = getCompatFilesDirPath(context);
                byte[] elfOld = compatFilesDir == null ? null : oldFilesDir.getBytes(StandardCharsets.US_ASCII);
                byte[] elfNew = compatFilesDir == null ? null : compatFilesDir.getBytes(StandardCharsets.US_ASCII);
                debugLog("Starting package-name substitution (TERMUX type) in " + tempDir.getAbsolutePath()
                    + " | oldFilesDir=" + oldFilesDir + " newFilesDir=" + newFilesDir + " compat=" + compatFilesDir);
                patchPrefixInDirectory(tempDir, oldFilesDir, newFilesDir, oldDataDir, newDataDir, elfOld, elfNew, 0);
                patchSymlinksInDirectory(tempDir, oldFilesDir, newFilesDir, oldDataDir, newDataDir, 0);
                debugLog("package-name substitution done");
            } else if (bootstrapType == TermuxBootstrapType.NIX) {
                debugLog("NIX bootstrap: skipping Termux path substitution (proot handles remapping)");
                patchNixHardcodedPaths(tempDir, context);
            }

            if (listener != null) listener.onProgress(context.getString(com.termux.R.string.bootstrap_install_progress_finalize), 90);
            debugLog("extraction complete, finalizing");

            File targetDir = new File(filesDir, targetName);
            if (targetDir.exists()) {
                String backupName = targetName + ".old-" + UUID.randomUUID().toString();
                backupDir = new File(filesDir, backupName);
                try {
                    renameOrMove(targetDir, backupDir);
                } catch (Exception e) {
                    throw new IOException(context.getString(com.termux.R.string.error_bootstrap_rename_prefix) + ": " + e.getMessage(), e);
                }
                Logger.logInfo(LOG_TAG, "old " + targetName + " renamed to " + backupName);
            }

            debugLog("rename tempDir -> targetDir (" + targetName + ")");
            try {
                renameOrMove(tempDir, targetDir);
            } catch (Exception e) {
                debugLogError("rename tempDir -> targetDir failed", e);
                Logger.logError(LOG_TAG, "rename failed, restoring backup");
                if (backupDir != null && backupDir.exists()) {
                    try { renameOrMove(backupDir, targetDir); } catch (Exception ignored) {}
                }
                throw new IOException(context.getString(com.termux.R.string.error_bootstrap_rename_temp) + ": " + e.getMessage(), e);
            }
            Logger.logInfo(LOG_TAG, "tempDir renamed to " + targetDir);

            if (backupDir != null) {
                deleteRecursive(backupDir);
            }

            Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully (" + bootstrapType + ").");
            debugLog("Bootstrap install SUCCESS");

            ensureHomeDirectory(context);
            if (bootstrapType == TermuxBootstrapType.NIX) {
                ensureNixRuntimeDirs(context);
            }

            if (manifest != null) {
                if (listener != null) listener.onProgress(context.getString(com.termux.R.string.bootstrap_install_progress_write_variant), 95);

                try {
                    TermuxBootstrapState.writeVariantMarker(context, manifest.variant);
                    TermuxBootstrapState.setInstalledVariant(context, manifest.variant);
                    Logger.logInfo(LOG_TAG, "variant marker written: " + manifest.variant);
                } catch (IOException e) {
                    Logger.logError(LOG_TAG, "Failed to write variant marker: " + e.getMessage());
                }
            }

            if (bootstrapType == TermuxBootstrapType.TERMUX) {
                try {
                    ensureCompatSymlinks(context);
                    writePathPatchMarker(context);
                } catch (Exception e) {
                    Logger.logError(LOG_TAG, "Failed to create compat symlinks/marker: " + e.getMessage());
                }
            }
            writeBootstrapTypeMarker(context, bootstrapType);
            invalidateBootstrapInstalledCache();

            if (listener != null) listener.onProgress(context.getString(com.termux.R.string.bootstrap_install_progress_done), 100);

        } catch (Exception e) {
            debugLogError("installBootstrapFromZipFile FAILED", e);
            Logger.logError(LOG_TAG, "installBootstrapFromZipFile failed: " + Log.getStackTraceString(e));
            deleteRecursive(tempDir);
            if (backupDir != null) deleteRecursive(backupDir);
            throw e;
        }
    }

    private static void extractZipFile(Context context, File zipFile, File destDir,
            InstallProgressListener listener, TermuxBootstrapType bootstrapType)
            throws IOException, BootstrapException {
        boolean needsSubstitution = !BOOTSTRAP_TARGET_PKG.equals(context.getPackageName());
        String oldPref = getBootstrapPrefixPath();
        String newPref = getActualPrefixPath(context);
        String oldDataDir = getBootstrapDataDirPath();
        String newDataDir = getActualDataDirPath(context);
        String allowedPrefixPath;
        if (bootstrapType == TermuxBootstrapType.NIX) {
            allowedPrefixPath = null;
        } else {
            allowedPrefixPath = needsSubstitution ? newPref : TERMUX_PREFIX_DIR_PATH;
        }

        ZipFile zip = null;
        try {
            zip = new ZipFile(zipFile);
            int totalEntries = zip.size();
            int doneEntries = 0;
            // Only publish when the whole percent changes: the listener posts to the main thread
            // and rebuilds a Notification, so for a 15-20k entry zip this used to mean as many
            // Binder round-trips, starving the main thread for the whole install.
            final int[] lastPercent = {-1};
            long totalUncompressed = 0;
            List<Pair<String, String>> symlinks = new ArrayList<>();
            Set<String> seenNames = new HashSet<>();

            debugLog("extractZipFile: " + zipFile.getName() + " -> " + destDir.getAbsolutePath()
                + " | " + totalEntries + " entries | needsSubstitution=" + needsSubstitution);

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                // Log every 200 entries to track progress
                if (doneEntries > 0 && doneEntries % 200 == 0) {
                    debugLog("extractZipFile: progress " + doneEntries + "/" + totalEntries);
                }

                if (name.isEmpty() || name.contains("..") || name.startsWith("/")) {
                    throw new SecurityException(context.getString(com.termux.R.string.error_bootstrap_invalid_zip_entry, name));
                }

                if (!seenNames.add(name)) {
                    throw new SecurityException(context.getString(com.termux.R.string.error_bootstrap_duplicate_zip_entry, name));
                }

                if (entry.isDirectory()) {
                    if (name.equals("SYMLINKS.txt") || name.equals("BOOTSTRAP_INFO")) {
                        throw new SecurityException(context.getString(com.termux.R.string.error_bootstrap_directory_metadata_entry, name));
                    }
                    File dir = safeChildFile(context, destDir, name);
                    if (!dir.isDirectory() && !dir.mkdirs()) {
                        throw new IOException(context.getString(com.termux.R.string.error_bootstrap_failed_mkdir, dir));
                    }
                    applyDirectoryMode(dir, entry);
                    doneEntries++;
                    continue;
                }

                if (entry.getSize() > MAX_SINGLE_ENTRY_SIZE) {
                    throw new SecurityException(context.getString(com.termux.R.string.error_bootstrap_entry_too_large, name, entry.getSize()));
                }

                long compressedSize = entry.getCompressedSize();
                long uncompressedSize = entry.getSize();
                if (uncompressedSize > 0 && compressedSize > 0) {
                    int maxRatio = (bootstrapType == TermuxBootstrapType.NIX)
                        ? MAX_COMPRESSION_RATIO_NIX
                        : MAX_COMPRESSION_RATIO;
                    long ratio = uncompressedSize / compressedSize;
                    if (ratio > maxRatio) {
                        throw new SecurityException(context.getString(com.termux.R.string.error_bootstrap_compression_ratio, name));
                    }
                    if (bootstrapType == TermuxBootstrapType.NIX && ratio > MAX_COMPRESSION_RATIO) {
                        Logger.logDebug(LOG_TAG, "NIX high-compression entry (ratio=" + ratio
                            + "): " + name + " (" + uncompressedSize + " -> " + compressedSize + ")");
                    }
                }

                totalUncompressed += uncompressedSize;
                if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_SIZE) {
                    throw new SecurityException(context.getString(com.termux.R.string.error_bootstrap_total_size_exceeded));
                }

                if (doneEntries > MAX_ENTRIES) {
                    throw new SecurityException(context.getString(com.termux.R.string.error_bootstrap_too_many_entries));
                }

                int unixMode = getUnixModeReflective(entry);
                int fileType = unixMode & android.system.OsConstants.S_IFMT;
                if (fileType == android.system.OsConstants.S_IFLNK) {
                    InputStream in = zip.getInputStream(entry);
                    String target;
                    try {
                        byte[] buf = new byte[4096];
                        ByteArrayOutputStream bout = new ByteArrayOutputStream();
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            bout.write(buf, 0, n);
                        }
                        target = new String(bout.toByteArray(), "UTF-8").trim();
                    } finally {
                        in.close();
                    }
                    if (needsSubstitution && bootstrapType == TermuxBootstrapType.TERMUX) {
                        target = replacePrefixInString(target, oldPref, newPref, oldDataDir, newDataDir);
                    }
                    String linkPath = destDir.getAbsolutePath() + "/" + name;
                    File linkFile = new File(linkPath);
                    File parent = linkFile.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException(context.getString(com.termux.R.string.error_bootstrap_symlink_parent_dir, parent));
                    }
                    validateSymlinkTarget(context, destDir, name, target, allowedPrefixPath, bootstrapType);
                    try {
                        Os.symlink(target, linkPath);
                    } catch (Exception e) {
                        throw new IOException(context.getString(com.termux.R.string.error_bootstrap_symlink_create, name, target), e);
                    }
                    doneEntries++;
                    continue;
                }

                if (name.equals("SYMLINKS.txt")) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(zip.getInputStream(entry)));
                    String line;
                    while ((line = r.readLine()) != null) {
                        String[] parts = line.split("←");
                        if (parts.length != 2)
                            throw new BootstrapException(context.getString(com.termux.R.string.error_bootstrap_symlink_line, line));
                        String target = parts[0];
                        if (needsSubstitution && bootstrapType == TermuxBootstrapType.TERMUX) {
                            target = replacePrefixInString(target, oldPref, newPref, oldDataDir, newDataDir);
                        }
                        String linkPath = destDir.getAbsolutePath() + "/" + parts[1];
                        File linkFile = new File(linkPath);
                        File parent = linkFile.getParentFile();
                        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                            throw new IOException(context.getString(com.termux.R.string.error_bootstrap_symlink_parent_dir, parent));
                        }
                        validateSymlinkTarget(context, destDir, parts[1], target, allowedPrefixPath, bootstrapType);
                        symlinks.add(Pair.create(target, linkPath));
                    }
                    doneEntries++;
                    continue;
                }

                if (name.equals("BOOTSTRAP_INFO")) {
                    doneEntries++;
                    continue;
                }

                if (unixMode != 0) {
                    if (fileType != 0 && fileType != android.system.OsConstants.S_IFREG) {
                        throw new SecurityException(context.getString(com.termux.R.string.error_bootstrap_unsupported_file_type, name));
                    }
                }

                File outFile = safeChildFile(context, destDir, name);
                File parent = outFile.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException(context.getString(com.termux.R.string.error_bootstrap_failed_mkdir, parent));
                }
                copyZipEntryToFile(zip, entry, outFile);
                applyPermissions(outFile, entry, name);

                doneEntries++;
                reportExtractProgress(context, listener, totalEntries, doneEntries, lastPercent);
            }

            debugLog("extractZipFile: done, creating " + symlinks.size() + " SYMLINKS.txt symlinks");
            for (Pair<String, String> symlink : symlinks) {
                try {
                    Os.symlink(symlink.first, symlink.second);
                } catch (Exception e) {
                    throw new IOException(context.getString(com.termux.R.string.error_bootstrap_symlink_create, symlink.first, symlink.second), e);
                }
            }
            debugLog("extractZipFile: all symlinks created, total entries=" + doneEntries);
        } finally {
            if (zip != null) {
                try { zip.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ── Official nix-on-droid-app extraction flow (copied from
    //    nix-community/nix-on-droid-app TermuxInstaller.java) ──
    //
    // Mirrors their extraction: ZipInputStream -> FileOutputStream / mkdirs
    // WITHOUT applying ZipEntry unix modes, then EXECUTABLES.txt -> chmod 0700
    // and SYMLINKS.txt -> Os.symlink(target, linkPath). Files/dirs get umask
    // modes (0644/0755) so the read-only 0555/0444 modes stored in the nix
    // bootstrap zip are never applied.

    private static void extractNixBootstrapZipOfficial(Context context, File zipFile, File destDir,
            InstallProgressListener listener) throws IOException, BootstrapException {
        ZipFile zip = null;
        try {
            zip = new ZipFile(zipFile);
            int totalEntries = zip.size();
            int doneEntries = 0;
            long totalUncompressed = 0;
            Set<String> seenNames = new HashSet<>();

            debugLog("extractNixBootstrapZipOfficial: " + zipFile.getName() + " -> "
                + destDir.getAbsolutePath() + " | " + totalEntries + " entries");

            final int[] lastPercent = {-1};

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (doneEntries > 0 && doneEntries % 200 == 0) {
                    debugLog("extractNixBootstrapZipOfficial: progress " + doneEntries + "/" + totalEntries);
                }

                if (name.isEmpty() || name.contains("..") || name.startsWith("/")) {
                    throw new SecurityException(context.getString(com.termux.R.string.error_bootstrap_invalid_zip_entry, name));
                }

                if (!seenNames.add(name)) {
                    throw new SecurityException(context.getString(com.termux.R.string.error_bootstrap_duplicate_zip_entry, name));
                }

                if (entry.isDirectory()) {
                    if (name.equals("SYMLINKS.txt") || name.equals("BOOTSTRAP_INFO")) {
                        throw new SecurityException(context.getString(com.termux.R.string.error_bootstrap_directory_metadata_entry, name));
                    }
                    File dir = safeChildFile(context, destDir, name);
                    if (!dir.isDirectory() && !dir.mkdirs()) {
                        throw new IOException(context.getString(com.termux.R.string.error_bootstrap_failed_mkdir, dir));
                    }
                    doneEntries++;
                    continue;
                }

                if (entry.getSize() > MAX_SINGLE_ENTRY_SIZE) {
                    throw new SecurityException(context.getString(com.termux.R.string.error_bootstrap_entry_too_large, name, entry.getSize()));
                }

                long compressedSize = entry.getCompressedSize();
                long uncompressedSize = entry.getSize();
                if (uncompressedSize > 0 && compressedSize > 0) {
                    long ratio = uncompressedSize / compressedSize;
                    if (ratio > MAX_COMPRESSION_RATIO_NIX) {
                        throw new SecurityException(context.getString(com.termux.R.string.error_bootstrap_compression_ratio, name));
                    }
                }

                totalUncompressed += uncompressedSize;
                if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_SIZE) {
                    throw new SecurityException(context.getString(com.termux.R.string.error_bootstrap_total_size_exceeded));
                }

                if (doneEntries > MAX_ENTRIES) {
                    throw new SecurityException(context.getString(com.termux.R.string.error_bootstrap_too_many_entries));
                }

                // SYMLINKS.txt / EXECUTABLES.txt / BOOTSTRAP_INFO are handled
                // by setupNixExecutables / setupNixSymlinks afterwards; still
                // extract them so the flow matches the official installer.
                File outFile = safeChildFile(context, destDir, name);
                File parent = outFile.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException(context.getString(com.termux.R.string.error_bootstrap_failed_mkdir, parent));
                }
                try (InputStream in = zip.getInputStream(entry); FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                doneEntries++;

                reportExtractProgress(context, listener, totalEntries, doneEntries, lastPercent);
            }
            debugLog("extractNixBootstrapZipOfficial: done, total entries=" + doneEntries);
        } finally {
            if (zip != null) {
                try { zip.close(); } catch (IOException ignored) {}
            }
        }
    }

    /** EXECUTABLES.txt -> chmod 0700 (official flow), skipping symlinks. */
    private static void setupNixExecutables(File destDir) throws IOException {
        File executablesFile = new File(destDir, "EXECUTABLES.txt");
        if (!executablesFile.isFile()) return;

        int count = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(executablesFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                File file = new File(destDir, line);
                if (!file.exists()) continue;
                try {
                    StructStat st = Os.lstat(file.getAbsolutePath());
                    if (OsConstants.S_ISLNK(st.st_mode)) continue;
                } catch (ErrnoException e) {
                    continue;
                }
                try {
                    Os.chmod(file.getAbsolutePath(), 0700);
                    count++;
                } catch (ErrnoException e) {
                    debugLog("setupNixExecutables: chmod failed for " + line + ": " + e.getMessage());
                }
            }
        }

        // Explicitly ensure the two launcher binaries are executable even if
        // EXECUTABLES.txt is missing or stale.
        for (String rel : new String[]{"bin/login", "bin/proot-static"}) {
            File f = new File(destDir, rel);
            if (f.isFile()) {
                try {
                    Os.chmod(f.getAbsolutePath(), 0700);
                } catch (Exception e) {
                    Logger.logDebug(LOG_TAG, "chmod 0700 failed for " + rel + ": " + e.getMessage());
                }
            }
        }
        debugLog("setupNixExecutables: " + count + " files from EXECUTABLES.txt");
    }

    /** SYMLINKS.txt -> Os.symlink(target, staging/link) (official flow). */
    private static void setupNixSymlinks(File destDir) throws IOException, BootstrapException {
        File symlinksFile = new File(destDir, "SYMLINKS.txt");
        if (!symlinksFile.isFile()) return;

        String stagingPath = destDir.getCanonicalPath() + File.separator;
        int count = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(symlinksFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                int idx = line.indexOf('\u2190');
                if (idx <= 0 || idx == line.length() - 1) {
                    throw new BootstrapException("Malformed symlink line: " + line);
                }
                String target = line.substring(0, idx).trim();
                String linkPath = line.substring(idx + 1).trim();

                File linkFile = new File(destDir, linkPath);
                String canonicalLink;
                try {
                    canonicalLink = linkFile.getCanonicalPath();
                } catch (IOException e) {
                    throw new IOException("Unable to canonicalize symlink path: " + linkPath, e);
                }
                if (!canonicalLink.startsWith(stagingPath)) {
                    throw new IOException("Symlink outside staging dir: " + linkPath);
                }

                File parent = linkFile.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Unable to create symlink parent directory: " + parent);
                }

                if (linkFile.exists() || isSymlink(linkFile)) {
                    if (!linkFile.delete()) {
                        throw new IOException("Unable to delete existing symlink/file: " + linkFile);
                    }
                }
                try {
                    Os.symlink(target, linkFile.getAbsolutePath());
                } catch (ErrnoException e) {
                    throw new IOException("Unable to create symlink " + linkPath + " -> " + target, e);
                }
                count++;
            }
        }
        debugLog("setupNixSymlinks: " + count + " symlinks created");
    }

    /**
     * Post-extraction sanity checks (official + our path patching). The
     * runtime patch block in usr/lib/login-inner legitimately contains the
     * legacy prefix (as the old-value variable of the session-init patcher),
     * so the block is stripped before checking.
     */
    private static void verifyNixBootstrap(File destDir) throws IOException {
        String[] requiredFiles = {"bin/login", "bin/proot-static", "usr/lib/login-inner"};
        for (String rel : requiredFiles) {
            File f = new File(destDir, rel);
            if (!f.isFile()) {
                throw new IOException("Nix bootstrap missing required file: " + rel);
            }
        }
        String[] requiredDirs = {"nix/store", "etc", "tmp", ".l2s", "dev/shm"};
        for (String rel : requiredDirs) {
            File dir = new File(destDir, rel);
            if (!dir.isDirectory()) {
                throw new IOException("Nix bootstrap missing required directory: " + rel);
            }
        }
        if (!isSymlink(new File(destDir, "bin/sh"))) {
            throw new IOException("Nix bootstrap bin/sh is not a symlink");
        }

        // bin/login must be fully patched: no runtime patch block, no legacy prefix.
        File login = new File(destDir, "bin/login");
        String loginContent = new String(java.nio.file.Files.readAllBytes(login.toPath()), StandardCharsets.UTF_8);
        if (loginContent.contains(NIX_PATH_PATCH_BEGIN) || loginContent.contains(NIX_PATH_PATCH_END)) {
            throw new IOException("Nix bootstrap bin/login unexpectedly contains runtime patch markers");
        }
        if (loginContent.contains(LEGACY_NIX_PACKAGE_PREFIX)) {
            throw new IOException("Nix bootstrap bin/login still contains hardcoded " + LEGACY_NIX_PACKAGE_PREFIX + " paths");
        }

        // login-inner: legacy prefix allowed only inside our runtime patch block.
        File loginInner = new File(destDir, "usr/lib/login-inner");
        String innerContent = new String(java.nio.file.Files.readAllBytes(loginInner.toPath()), StandardCharsets.UTF_8);
        boolean hasBegin = innerContent.contains(NIX_PATH_PATCH_BEGIN);
        boolean hasEnd = innerContent.contains(NIX_PATH_PATCH_END);
        if (hasBegin != hasEnd) {
            throw new IOException("Nix bootstrap usr/lib/login-inner contains malformed runtime patch block");
        }
        String stripped = NIX_PATH_PATCH_BLOCK_PATTERN.matcher(innerContent).replaceAll("");
        if (stripped.contains(LEGACY_NIX_PACKAGE_PREFIX)) {
            throw new IOException("Nix bootstrap usr/lib/login-inner still contains hardcoded "
                + LEGACY_NIX_PACKAGE_PREFIX + " paths outside runtime patch block");
        }
        if (hasBegin && !stripped.contains("_nod_source_session_init")) {
            throw new IOException("Nix bootstrap usr/lib/login-inner has runtime patch block but does not call _nod_source_session_init");
        }
        Logger.logInfo(LOG_TAG, "Nix bootstrap verification passed for " + destDir);
    }

    private static void validateSymlinkTarget(Context context, File extractRoot, String linkName, String target,
            @Nullable String allowedPrefixPath, TermuxBootstrapType bootstrapType) throws IOException {
        if (target.isEmpty()) {
            throw new SecurityException(context.getString(com.termux.R.string.error_bootstrap_symlink_target_empty, linkName));
        }
        File linkFile = new File(extractRoot, linkName);
        File parent = linkFile.getParentFile();
        if (parent == null) {
            throw new SecurityException(context.getString(com.termux.R.string.error_bootstrap_symlink_no_parent, linkName));
        }

        if (target.startsWith("/")) {
            boolean allowed = false;

            if (bootstrapType == TermuxBootstrapType.NIX) {
                allowed = true;
                if (!target.startsWith("/nix/store/")) {
                    Logger.logDebug(LOG_TAG, "NIX absolute symlink (non-store): "
                        + linkName + " -> " + target);
                }
            } else if (allowedPrefixPath != null) {
                String prefixPath = allowedPrefixPath;
                if (!prefixPath.endsWith("/")) prefixPath += "/";
                if (target.startsWith(prefixPath) || target.equals(allowedPrefixPath)) {
                    allowed = true;
                }
            }

            if (allowed) {
                Logger.logInfo(LOG_TAG, "Allowing absolute symlink (" + bootstrapType + "): "
                    + linkName + " -> " + target);
            } else {
                throw new SecurityException(context.getString(com.termux.R.string.error_bootstrap_symlink_absolute, target));
            }
        } else {
            File resolved = new File(parent, target);
            String rootPath = extractRoot.getCanonicalPath() + File.separator;
            String resolvedPath = resolved.getCanonicalPath();
            if (!resolvedPath.startsWith(rootPath)) {
                throw new SecurityException(context.getString(com.termux.R.string.error_bootstrap_symlink_escapes_root, linkName, target));
            }
        }
    }

    /** Last {@code base -> canonical path} pair resolved by {@link #safeChildFile}. Install passes
     * the same destination directory for every entry, so this turns 2 realpath() calls per entry
     * (each an lstat of every path component) into a single lookup after the first one. */
    private static volatile File sSafeChildBase;
    private static volatile String sSafeChildBasePath;

    private static File safeChildFile(Context context, File base, String name) throws IOException {
        File child = new File(base, name);
        String basePath;
        if (sSafeChildBase == base && sSafeChildBasePath != null) {
            basePath = sSafeChildBasePath;
        } else {
            basePath = base.getCanonicalPath() + File.separator;
            sSafeChildBase = base;
            sSafeChildBasePath = basePath;
        }
        String childPath = child.getCanonicalPath();
        if (!childPath.startsWith(basePath)) {
            throw new IOException(context.getString(com.termux.R.string.error_bootstrap_path_traversal, name));
        }
        return child;
    }

    private static void copyZipEntryToFile(ZipFile zip, ZipEntry entry, File outFile) throws IOException {
        try (InputStream in = new BufferedInputStream(zip.getInputStream(entry));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            out.flush();
        }
    }

    private static void applyPermissions(File file, ZipEntry entry, String name) {
        int mode = getUnixModeReflective(entry) & 0777;
        mode &= ~(android.system.OsConstants.S_ISUID | android.system.OsConstants.S_ISGID);
        if (mode == 0) {
            mode = (name.startsWith("bin/") || name.startsWith("libexec/")
                || name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods/"))
                ? 0755 : 0644;
        }
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        } catch (Exception ignored) {}
    }

    private static void applyDirectoryMode(File dir, ZipEntry entry) {
        int mode = getUnixModeReflective(entry) & 0777;
        if (mode == 0) mode = 0755;
        try {
            Os.chmod(dir.getAbsolutePath(), mode);
        } catch (Exception ignored) {}
    }

    /** {@code ZipEntry.getUnixMode} is a hidden/removed Android API. Resolving the {@link Method}
     * once matters: the old code called getMethod() per entry, and when the method does not exist
     * (always on modern Android) each call also built a NoSuchMethodException with a stack trace —
     * two per zip entry, ~30k throwaways per install. */
    private static Method sGetUnixModeMethod;
    private static volatile boolean sGetUnixModeResolved;

    private static int getUnixModeReflective(ZipEntry entry) {
        if (!sGetUnixModeResolved) {
            try {
                sGetUnixModeMethod = ZipEntry.class.getMethod("getUnixMode");
            } catch (Throwable ignored) {
                sGetUnixModeMethod = null;
            }
            sGetUnixModeResolved = true;
        }
        Method method = sGetUnixModeMethod;
        if (method == null) return 0;
        try {
            return (int) method.invoke(entry);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Publish extraction progress only when the whole percent changes. */
    private static void reportExtractProgress(Context context, InstallProgressListener listener,
                                              int totalEntries, int doneEntries, int[] lastPercent) {
        if (listener == null || totalEntries <= 0) return;
        int percent = 10 + (int) (80L * doneEntries / totalEntries);
        if (percent == lastPercent[0]) return;
        lastPercent[0] = percent;
        listener.onProgress(context.getString(com.termux.R.string.bootstrap_install_progress_extract), percent);
    }

    public static class BootstrapException extends Exception {
        public BootstrapException(String message) { super(message); }
        public BootstrapException(String message, Throwable cause) { super(message, cause); }
    }

    public static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        if (isBootstrapInstalled(activity)) {
            TermuxBootstrapType installedType = getInstalledBootstrapType(activity);
            if (installedType == TermuxBootstrapType.NIX) {
                ensureHomeDirectory(activity);
                whenDone.run();
                return;
            }
            if (needsPackageSubstitution(activity) && !isBootstrapPathPatchApplied(activity)) {
                final ProgressDialog patchProgress = ProgressDialog.show(activity, null,
                    activity.getString(R.string.bootstrap_installer_body), true, false);
                new Thread() {
                    @Override
                    public void run() {
                        try {
                            patchExistingBootstrapIfNeeded(activity);
                            activity.runOnUiThread(whenDone);
                        } catch (final Exception e) {
                            debugLogError("Existing bootstrap patch FAILED", e);
                            showBootstrapErrorDialog(activity, whenDone,
                                Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));
                        } finally {
                            activity.runOnUiThread(() -> {
                                try { patchProgress.dismiss(); } catch (RuntimeException ignored) {}
                            });
                        }
                    }
                }.start();
            } else {
                whenDone.run();
            }
            return;
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null,
            activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");
                    debugLog("=== Embedded bootstrap install start ===");

                    final byte[] zipBytes = loadZipBytes(activity);
                    final TermuxBootstrapType embeddedType = detectBootstrapTypeFromZipBytes(zipBytes);
                    Logger.logInfo(LOG_TAG, "Embedded bootstrap type: " + embeddedType);

                    String runtimeFilesDir = activity.getFilesDir().getAbsolutePath();
                    final String targetDirName = embeddedType == TermuxBootstrapType.NIX ? "nix-root" : "usr";
                    final String stagingDirName = targetDirName + "-staging";
                    String runtimeStagingPrefixPath = runtimeFilesDir + "/" + stagingDirName;
                    String runtimePrefixPath = runtimeFilesDir + "/" + targetDirName;
                    File runtimeStagingPrefixDir = new File(runtimeStagingPrefixPath);
                    File runtimePrefixDir = new File(runtimePrefixPath);

                    Error error;

                    error = FileUtils.deleteFile("termux prefix staging directory", runtimeStagingPrefixPath, true);
                    if (error != null) { showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error)); return; }

                    error = FileUtils.deleteFile("termux prefix directory", runtimePrefixPath, true);
                    if (error != null) { showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error)); return; }

                    if (!new File(runtimeFilesDir).isDirectory() && !new File(runtimeFilesDir).mkdirs()) {
                        showBootstrapErrorDialog(activity, whenDone, "Failed to create files directory: " + runtimeFilesDir);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + runtimeStagingPrefixPath + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                final boolean needsSub = needsPackageSubstitution(activity)
                                    && embeddedType == TermuxBootstrapType.TERMUX;
                                final String embOldPref = getBootstrapPrefixPath();
                                final String embNewPref = getActualPrefixPath(activity);
                                final String embOldData = getBootstrapDataDirPath();
                                final String embNewData = getActualDataDirPath(activity);
                                BufferedReader r = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = r.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2) throw new RuntimeException(activity.getString(com.termux.R.string.error_bootstrap_symlink_line, line));
                                    String oldPath = parts[0];
                                    if (needsSub) {
                                        oldPath = replacePrefixInString(oldPath, embOldPref, embNewPref, embOldData, embNewData);
                                    }
                                    String newPath = runtimeStagingPrefixPath + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));
                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) { showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error)); return; }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(runtimeStagingPrefixPath, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();
                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) { showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error)); return; }
                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    if (embeddedType == TermuxBootstrapType.TERMUX && symlinks.isEmpty()) {
                        throw new RuntimeException(activity.getString(com.termux.R.string.error_bootstrap_no_symlinks));
                    }
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    if (embeddedType == TermuxBootstrapType.TERMUX && needsPackageSubstitution(activity)) {
                        String oldFilesDir = getBootstrapFilesDirPath();
                        String newFilesDir = getActualFilesDirPath(activity);
                        String oldDataDir = getBootstrapDataDirPath();
                        String newDataDir = getActualDataDirPath(activity);
                        String compatFilesDir = getCompatFilesDirPath(activity);
                        byte[] elfOld = compatFilesDir == null ? null : oldFilesDir.getBytes(StandardCharsets.US_ASCII);
                        byte[] elfNew = compatFilesDir == null ? null : compatFilesDir.getBytes(StandardCharsets.US_ASCII);
                        Logger.logInfo(LOG_TAG, "Starting embedded bootstrap substitution in " + runtimeStagingPrefixDir.getAbsolutePath());
                        patchPrefixInDirectory(runtimeStagingPrefixDir,
                            oldFilesDir, newFilesDir,
                            oldDataDir, newDataDir,
                            elfOld, elfNew, 0);
                        patchSymlinksInDirectory(runtimeStagingPrefixDir,
                            oldFilesDir, newFilesDir,
                            oldDataDir, newDataDir, 0);
                        Logger.logInfo(LOG_TAG, "embedded bootstrap package-name substitution: " + BOOTSTRAP_TARGET_PKG + " -> " + activity.getPackageName());
                    } else if (embeddedType == TermuxBootstrapType.NIX) {
                        Logger.logInfo(LOG_TAG, "NIX embedded bootstrap: skipping path substitution");
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");
                    try {
                        renameOrMove(runtimeStagingPrefixDir, runtimePrefixDir);
                    } catch (Exception e) {
                        throw new RuntimeException(activity.getString(com.termux.R.string.error_bootstrap_move_prefix) + ": " + e.getMessage(), e);
                    }
                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");
                    debugLog("Embedded bootstrap install SUCCESS");
                    ensureHomeDirectory(activity);
                    if (embeddedType == TermuxBootstrapType.NIX) {
                        ensureNixRuntimeDirs(activity);
                    }
                    if (embeddedType == TermuxBootstrapType.TERMUX && needsPackageSubstitution(activity)) {
                        try {
                            ensureCompatSymlinks(activity);
                            writePathPatchMarker(activity);
                        } catch (Exception e) {
                            Logger.logError(LOG_TAG, "Failed to create compat symlinks/marker: " + e.getMessage());
                        }
                    }
                    writeBootstrapTypeMarker(activity, embeddedType);
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);
                    activity.runOnUiThread(whenDone);
                } catch (final Exception e) {
                    debugLogError("Embedded bootstrap install FAILED", e);
                    showBootstrapErrorDialog(activity, whenDone,
                        Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));
                } finally {
                    activity.runOnUiThread(() -> {
                        try { progress.dismiss(); } catch (RuntimeException ignored) {}
                    });
                }
            }
        }.start();
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);
        sendBootstrapCrashReportNotification(activity, message);
        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity)
                    .setTitle(R.string.bootstrap_error_title)
                    .setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (d, which) -> { d.dismiss(); activity.finish(); })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (d, which) -> {
                        d.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).create().show();
            } catch (WindowManager.BadTokenException ignored) {}
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            activity.getString(com.termux.R.string.notification_bootstrap_crash_title), null,
            activity.getString(com.termux.R.string.notification_bootstrap_crash_body, message),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String tag = "termux-storage";
        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;
                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, tag, error.getMessage());
                        return;
                    }
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());
                    Os.symlink(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());
                    Os.symlink(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());
                    Os.symlink(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());
                    Os.symlink(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());
                    Os.symlink(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());
                    Os.symlink(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());
                    Os.symlink(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS).getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Os.symlink(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS).getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null) {
                        for (int i = 0; i < dirs.length; i++) {
                            if (dirs[i] == null) continue;
                            Os.symlink(dirs[i].getAbsolutePath(), new File(storageDir, "external-" + i).getAbsolutePath());
                        }
                    }
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null) {
                        for (int i = 0; i < dirs.length; i++) {
                            if (dirs[i] == null) continue;
                            Os.symlink(dirs[i].getAbsolutePath(), new File(storageDir, "media-" + i).getAbsolutePath());
                        }
                    }
                    Logger.logInfo(tag, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, tag, e.getMessage());
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    private static boolean sNativeAvailable;
    static {
        try {
            System.loadLibrary("termux-bootstrap");
            sNativeAvailable = true;
        } catch (Throwable t) {
            sNativeAvailable = false;
        }
    }

    private static byte[] loadZipBytes(Context context) {
        if (!sNativeAvailable) {
            throw new RuntimeException(context.getString(com.termux.R.string.error_bootstrap_embedded_lib));
        }
        return getZip();
    }

    private static native byte[] getZip();

    private static void renameOrMove(File src, File dst) throws IOException {
        try {
            Os.rename(src.getAbsolutePath(), dst.getAbsolutePath());
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.EXDEV) {
                Logger.logInfo(LOG_TAG, "EXDEV rename, falling back to copy+delete: " + src + " -> " + dst);
                copyRecursive(src, dst);
                deleteRecursive(src);
            } else {
                throw new IOException("rename failed: " + e.getMessage(), e);
            }
        }
    }

    private static void copyRecursive(File src, File dest) throws IOException {
        try {
            StructStat st = Os.lstat(src.getAbsolutePath());
            if (OsConstants.S_ISLNK(st.st_mode)) {
                String target = Os.readlink(src.getAbsolutePath());
                Os.symlink(target, dest.getAbsolutePath());
                return;
            }
        } catch (ErrnoException e) {
        }

        if (src.isDirectory()) {
            if (!dest.isDirectory() && !dest.mkdirs()) {
                throw new IOException("Failed to create directory: " + dest);
            }
            File[] children = src.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursive(child, new File(dest, child.getName()));
                }
            }
        } else if (src.isFile()) {
            File parent = dest.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Failed to create directory: " + parent);
            }
            try (InputStream in = new BufferedInputStream(new FileInputStream(src));
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            try {
                StructStat st = Os.stat(src.getAbsolutePath());
                Os.chmod(dest.getAbsolutePath(), st.st_mode & 0777);
            } catch (Exception ignored) {}
        } else if (src.exists()) {
            throw new IOException("Unsupported file type: " + src);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        try {
            StructStat st = Os.lstat(f.getAbsolutePath());
            if (OsConstants.S_ISLNK(st.st_mode)) {
                f.delete();
                return;
            }
        } catch (Exception ignored) {}
        if (f.isDirectory()) {
            // Old installers left read-only dir modes (dr-xr-xr-x) which block
            // deleting children; make the dir owner-writable first.
            try {
                Os.chmod(f.getAbsolutePath(), 0700);
            } catch (Exception ignored) {}
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        f.delete();
    }
}
