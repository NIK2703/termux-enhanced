package com.termux.shared.termux.shell.command.environment;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.environment.AndroidShellEnvironment;
import com.termux.shared.shell.command.environment.ShellEnvironmentUtils;
import com.termux.shared.termux.TermuxBootstrap;
import com.termux.shared.termux.TermuxBootstrapType;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.TermuxPrefixRemap;
import com.termux.shared.termux.shell.TermuxShellUtils;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Environment for Termux.
 */
public class TermuxShellEnvironment extends AndroidShellEnvironment {

    private static final String LOG_TAG = "TermuxShellEnvironment";

    /** Environment variable for the termux {@link TermuxConstants#TERMUX_PREFIX_DIR_PATH}. */
    public static final String ENV_PREFIX = "PREFIX";

    /** Cached runtime home dir path, resolved at init(). Falls back to compile-time constant. */
    private static String sResolvedHomeDirPath;

    /** Cached runtime bin dir path, resolved at init(). Falls back to compile-time constant. */
    private static String sResolvedBinDirPath;

    /** Application context stored at init(), used for needsSubstitution checks. */
    private static Context sInitContext;

    public TermuxShellEnvironment() {
        super();
        shellCommandShellEnvironment = new TermuxShellCommandShellEnvironment();
    }


    /** Init {@link TermuxShellEnvironment} constants and caches. */
    public synchronized static void init(@NonNull Context currentPackageContext) {
        String filesDir = currentPackageContext.getFilesDir().getAbsolutePath();
        sResolvedHomeDirPath = filesDir + "/home";
        sInitContext = currentPackageContext;

        // Defensively ensure home dir exists — covers Nix bootstrap that doesn't create it
        File homeDir = new File(sResolvedHomeDirPath);
        if (!homeDir.isDirectory()) {
            if (homeDir.mkdirs()) {
                Logger.logInfo(LOG_TAG, "Created missing home directory: " + sResolvedHomeDirPath);
            } else {
                Logger.logError(LOG_TAG, "Failed to create home directory: " + sResolvedHomeDirPath);
            }
        }

        TermuxBootstrapType type = TermuxBootstrapType.getInstalledType(currentPackageContext.getFilesDir());
        if (type == TermuxBootstrapType.NIX) {
            sResolvedBinDirPath = filesDir + "/nix-root/bin";
        } else {
            sResolvedBinDirPath = filesDir + "/usr/bin";
            TermuxPrefixRemap.ensureInstalled(currentPackageContext, filesDir + "/usr/lib");
        }

        TermuxAppShellEnvironment.setTermuxAppEnvironment(currentPackageContext);
    }

    /**
     * Resolve the actual $PREFIX path at runtime.
     * Prefers the cached sResolvedHomeDirPath (set from Application context in
     * {@link #init(Context)}) over context.getFilesDir(), because the passed context
     * may point to another package (e.g. com.termux) on debug/fork builds.
     * Falls back to the compile-time constant only when neither source helps.
     */
    static String resolvePrefixDirPath(@NonNull Context context) {
        TermuxBootstrapType type = TermuxBootstrapType.getInstalledType(context.getFilesDir());
        if (type == TermuxBootstrapType.NIX) {
            return context.getFilesDir().getAbsolutePath() + "/nix-root";
        }

        String defaultPrefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String filesDir;

        if (sResolvedHomeDirPath != null) {
            // Derive from the cached runtime home dir (set in init() from Application context).
            // sResolvedHomeDirPath = filesDir + "/home", so strip 5 chars for the files dir.
            if (sResolvedHomeDirPath.endsWith("/home")) {
                filesDir = sResolvedHomeDirPath.substring(0, sResolvedHomeDirPath.length() - 5);
                String runtimePrefix = filesDir + "/usr";
                if (!runtimePrefix.equals(defaultPrefix)) {
                    Logger.logDebug("TermuxShellEnvironment: runtime PREFIX=" + runtimePrefix + " (from sResolvedHomeDirPath, compile-time=" + defaultPrefix + ")");
                    return runtimePrefix;
                }
            }
        }

        // Fall back to the passed context (may or may not differ from compile-time).
        String runtimePrefix = context.getFilesDir().getAbsolutePath() + "/usr";
        if (!runtimePrefix.equals(defaultPrefix)) {
            Logger.logDebug("TermuxShellEnvironment: runtime PREFIX=" + runtimePrefix + " (from context, compile-time=" + defaultPrefix + ")");
            return runtimePrefix;
        }
        return defaultPrefix;
    }

    static String resolveEnvFilePath(@NonNull Context context) {
        TermuxBootstrapType type = TermuxBootstrapType.getInstalledType(context.getFilesDir());
        if (type == TermuxBootstrapType.NIX) {
            return context.getFilesDir().getAbsolutePath() + "/nix-root/etc/termux/termux.env";
        }
        return resolvePrefixDirPath(context) + "/etc/termux/termux.env";
    }

    static String resolveEnvTempFilePath(@NonNull Context context) {
        TermuxBootstrapType type = TermuxBootstrapType.getInstalledType(context.getFilesDir());
        if (type == TermuxBootstrapType.NIX) {
            return context.getFilesDir().getAbsolutePath() + "/nix-root/etc/termux/termux.env.tmp";
        }
        return resolvePrefixDirPath(context) + "/etc/termux/termux.env.tmp";
    }

    /** Background writer handle for {@link #writeEnvironmentToFileAsync}. */
    private static volatile Thread sEnvWriteThread;

    /**
     * Same as {@link #writeEnvironmentToFile(Context)}, but off the calling thread.
     *
     * Building the environment costs four PackageManager/ActivityManager Binder calls plus two
     * file writes, and was running synchronously inside {@code TermuxApplication.onCreate} on
     * every cold start.
     *
     * The very first run (no env file on disk) still writes synchronously: a plugin firing a
     * command right after boot must not be able to observe a missing environment file.
     */
    public static void writeEnvironmentToFileAsync(@NonNull Context currentPackageContext) {
        String envFilePath = resolveEnvFilePath(currentPackageContext);
        if (envFilePath == null || !new File(envFilePath).isFile()) {
            writeEnvironmentToFile(currentPackageContext);
            return;
        }
        Thread existing = sEnvWriteThread;
        if (existing != null && existing.isAlive()) return;
        Thread thread = new Thread(() -> writeEnvironmentToFile(currentPackageContext),
            "writeEnvironmentToFile");
        thread.setDaemon(true);
        sEnvWriteThread = thread;
        thread.start();
    }

    /** Init {@link TermuxShellEnvironment} constants and caches. */
    public synchronized static void writeEnvironmentToFile(@NonNull Context currentPackageContext) {
        HashMap<String, String> environmentMap = new TermuxShellEnvironment().getEnvironment(currentPackageContext, false);
        String environmentString = ShellEnvironmentUtils.convertEnvironmentToDotEnvFile(environmentMap);

        String envFilePath = resolveEnvFilePath(currentPackageContext);
        String envTempPath = resolveEnvTempFilePath(currentPackageContext);

        // Ensure parent directory exists
        File envDir = new File(envFilePath).getParentFile();
        if (envDir != null && !envDir.isDirectory()) {
            if (!envDir.mkdirs()) {
                Logger.logError(LOG_TAG, "Failed to create env dir: " + envDir);
                return;
            }
        }
        Error error = FileUtils.writeTextToFile("termux.env.tmp", envTempPath,
            Charset.defaultCharset(), environmentString, false);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString());
            return;
        }

        error = FileUtils.moveRegularFile("termux.env.tmp", envTempPath, envFilePath, true);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString());
        }
    }

    /** Get shell environment for Termux. */
    @NonNull
    @Override
    public HashMap<String, String> getEnvironment(@NonNull Context currentPackageContext, boolean isFailSafe) {

        // Termux environment builds upon the Android environment
        HashMap<String, String> environment = super.getEnvironment(currentPackageContext, isFailSafe);

        HashMap<String, String> termuxAppEnvironment = TermuxAppShellEnvironment.getEnvironment(currentPackageContext);
        if (termuxAppEnvironment != null)
            environment.putAll(termuxAppEnvironment);

        HashMap<String, String> termuxApiAppEnvironment = TermuxAPIShellEnvironment.getEnvironment(currentPackageContext);
        if (termuxApiAppEnvironment != null)
            environment.putAll(termuxApiAppEnvironment);

        // Check installed bootstrap type before computing prefix — Nix needs the real filesDir
        TermuxBootstrapType bootstrapType = TermuxBootstrapType.getInstalledType(currentPackageContext.getFilesDir());
        if (bootstrapType == TermuxBootstrapType.NIX) {
            String realFilesDir = currentPackageContext.getFilesDir().getAbsolutePath();
            return getNixEnvironment(currentPackageContext, environment, isFailSafe, realFilesDir);
        }

        String prefixDirPath = resolvePrefixDirPath(currentPackageContext);
        String filesDir = prefixDirPath.substring(0, prefixDirPath.length() - 4); // strip "/usr"
        String homeDirPath = filesDir + "/home";
        String tmpDirPath = prefixDirPath + "/tmp";
        String binDirPath = prefixDirPath + "/bin";
        String libDirPath = prefixDirPath + "/lib";

        environment.put(ENV_HOME, homeDirPath);
        environment.put(ENV_PREFIX, prefixDirPath);

        // If failsafe is not enabled, then we keep default PATH and TMPDIR so that system binaries can be used
        if (!isFailSafe) {
            environment.put(ENV_TMPDIR, tmpDirPath);
            boolean needsSubstitution = !TermuxConstants.TERMUX_BOOTSTRAP_TARGET_PACKAGE_NAME.equals(currentPackageContext.getPackageName());
            if (TermuxBootstrap.isAppPackageVariantAPTAndroid5()) {
                // Termux in android 5/6 era shipped busybox binaries in applets directory
                environment.put(ENV_PATH, binDirPath + ":" + binDirPath + "/applets");
                environment.put(ENV_LD_LIBRARY_PATH, libDirPath);
            } else if (needsSubstitution) {
                environment.put(ENV_PATH, binDirPath);
                environment.put(ENV_LD_LIBRARY_PATH, libDirPath);

                // Export LD_PRELOAD and remap env vars for the path-remapping shim.
                // The shim (libtermux-prefix-remap.so) intercepts libc file operations
                // and rewrites hardcoded bootstrap paths to the actual runtime prefix.
                String remapLib = libDirPath + "/" + TermuxPrefixRemap.REMAP_LIB_NAME;
                if (new File(remapLib).canRead()) {
                    String oldFilesDir = TermuxConstants.TERMUX_FILES_DIR_PATH;
                    String newFilesDir = currentPackageContext.getFilesDir().getAbsolutePath()
                        .replaceFirst("^/data/user/0/", "/data/data/");

                    String existingPreload = environment.get(TermuxPrefixRemap.ENV_LD_PRELOAD);
                    if (existingPreload == null || existingPreload.isEmpty()) {
                        environment.put(TermuxPrefixRemap.ENV_LD_PRELOAD, remapLib);
                    } else {
                        environment.put(TermuxPrefixRemap.ENV_LD_PRELOAD, remapLib + ":" + existingPreload);
                    }

                    environment.put(TermuxPrefixRemap.ENV_REMAP_OLD_FILES_DIR, oldFilesDir);
                    environment.put(TermuxPrefixRemap.ENV_REMAP_NEW_FILES_DIR, newFilesDir);
                    environment.put(TermuxPrefixRemap.ENV_REMAP_LIBPATH, libDirPath);
                    environment.put(TermuxPrefixRemap.ENV_REMAP_LOADER,
                        libDirPath + "/ld-linux-aarch64.so.1");
                    environment.put(TermuxPrefixRemap.ENV_REMAP_PRESERVE_ARGV0, "1");
                }
            } else {
                // Termux binaries on Android 7+ rely on DT_RUNPATH, so LD_LIBRARY_PATH should be unset by default
                environment.put(ENV_PATH, binDirPath);
                environment.remove(ENV_LD_LIBRARY_PATH);
                environment.remove(TermuxPrefixRemap.ENV_LD_PRELOAD);
                environment.remove(TermuxPrefixRemap.ENV_REMAP_OLD_FILES_DIR);
                environment.remove(TermuxPrefixRemap.ENV_REMAP_NEW_FILES_DIR);
                environment.remove(TermuxPrefixRemap.ENV_REMAP_LIBPATH);
                environment.remove(TermuxPrefixRemap.ENV_REMAP_LOADER);
                environment.remove(TermuxPrefixRemap.ENV_REMAP_PRESERVE_ARGV0);
            }
        }

        return environment;
    }


    /** Expose for debug logging. */
    public static String getResolvedHomeDirPath() {
        return sResolvedHomeDirPath;
    }

    /**
     * Sanitize a working directory path: if it points under the compile-time
     * Termux data dir (/data/data/com.termux/), remap it to the runtime package
     * data dir.  Also falls back to the runtime home dir when the path is null,
     * empty, or does not exist on disk.
     *
     * @param context     The calling package context (must be the actual app, not
     *                    a created package context for com.termux).
     * @param workingDir  The working directory to sanitize.
     * @return A safe absolute path under the runtime package files dir.
     */
    @NonNull
    public static String sanitizeWorkingDirectory(@NonNull Context context,
                                                  @Nullable String workingDir) {
        String runtimeFilesDir = context.getFilesDir().getAbsolutePath();
        String runtimeHome = runtimeFilesDir + "/home";
        String compileTimeFiles = TermuxConstants.TERMUX_FILES_DIR_PATH; // /data/data/com.termux/files
        String compileTimeUserFiles = "/data/user/0/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files";

        // Nix fallback
        TermuxBootstrapType type = TermuxBootstrapType.getInstalledType(context.getFilesDir());
        String nixRoot = runtimeFilesDir + "/nix-root";

        if (workingDir == null || workingDir.trim().isEmpty()) {
            Logger.logInfo(LOG_TAG, "sanitizeWorkingDirectory: null/empty -> " + runtimeHome);
            return runtimeHome;
        }

        String wd = new File(workingDir).getAbsolutePath();

        // Replace /data/data/com.termux/files/... with runtime files dir
        if (wd.startsWith(compileTimeFiles + "/") || wd.equals(compileTimeFiles)) {
            wd = runtimeFilesDir + wd.substring(compileTimeFiles.length());
            Logger.logInfo(LOG_TAG, "sanitizeWorkingDirectory: remapped /data/data/ path -> " + wd);
        }

        // Replace /data/user/0/com.termux/files/... with runtime files dir
        if (wd.startsWith(compileTimeUserFiles + "/") || wd.equals(compileTimeUserFiles)) {
            wd = runtimeFilesDir + wd.substring(compileTimeUserFiles.length());
            Logger.logInfo(LOG_TAG, "sanitizeWorkingDirectory: remapped /data/user/0/ path -> " + wd);
        }

        // Replace /data/user/0/<current-pkg>/files/... with /data/data/ equivalent
        String currentUserFiles = "/data/user/0/" + context.getPackageName() + "/files";
        if (wd.startsWith(currentUserFiles + "/") || wd.equals(currentUserFiles)) {
            wd = runtimeFilesDir + wd.substring(currentUserFiles.length());
            Logger.logInfo(LOG_TAG, "sanitizeWorkingDirectory: normalized /data/user/0/ -> " + wd);
        }

        // Verify the result is accessible; fall back to runtime home if not
        File dir = new File(wd);
        if (dir.exists() && dir.isDirectory()) {
            // For Nix, never start inside the bootstrap root — home is safer
            // (official launcher sets HOME under files/, not the rootfs)
            if (type == TermuxBootstrapType.NIX
                && (wd.equals(nixRoot) || wd.startsWith(nixRoot + "/"))) {
                Logger.logInfo(LOG_TAG, "sanitizeWorkingDirectory: Nix bootstrap root as cwd -> " + runtimeHome);
                return runtimeHome;
            }
            return wd;
        }

        Logger.logInfo(LOG_TAG, "sanitizeWorkingDirectory: \"" + wd + "\" not accessible -> fallback " + runtimeHome);
        return runtimeHome;
    }

    @NonNull
    @Override
    public String getDefaultWorkingDirectoryPath() {
        if (sResolvedHomeDirPath != null) return sResolvedHomeDirPath;
        return TermuxConstants.TERMUX_HOME_DIR_PATH;
    }

    @NonNull
    @Override
    public String getDefaultBinPath() {
        if (sResolvedBinDirPath != null) return sResolvedBinDirPath;
        return TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
    }

    @NonNull
    @Override
    public String[] setupShellCommandArguments(@NonNull String executable, String[] arguments) {
        String[] argv = TermuxShellUtils.setupShellCommandArguments(executable, arguments);
        if (sInitContext != null) {
            TermuxBootstrapType type = TermuxBootstrapType.getInstalledType(sInitContext.getFilesDir());
            if (type == TermuxBootstrapType.NIX) {
                argv = setupNixLoginCommand(argv);
            } else if (type == TermuxBootstrapType.TERMUX && TermuxPrefixRemap.needsSubstitution(sInitContext)) {
                String prefixDirPath = resolvePrefixDirPath(sInitContext);
                String libDirPath = prefixDirPath + "/lib";
                argv = TermuxPrefixRemap.wrapExecutableIfNeeded(libDirPath, argv);
            }
        }
        return argv;
    }

    /**
     * Nix-on-Droid session command. Always starts with the official host
     * launcher nixRoot/bin/login (shebang #!/system/bin/sh, interpreted by
     * mksh on the host). The login script itself exports USER/HOME/
     * PROOT_TMP_DIR/PROOT_L2S_DIR, handles the fake /proc/stat|uptime binds
     * and execs proot-static — the same as the official nix-on-droid-app fork,
     * which does not replicate proot in Java.
     */
    private String[] setupNixLoginCommand(String[] argv) {
        String filesDir = canonicalFilesDir();
        if (filesDir == null) return argv;
        String nixRoot = filesDir + "/nix-root";
        File login = new File(nixRoot, "bin/login");
        if (!login.isFile()) {
            Logger.logError(LOG_TAG, "setupNixLoginCommand: bin/login missing: " + login);
            return argv;
        }

        List<String> args = new ArrayList<>();
        if (login.canExecute()) {
            args.add(login.getAbsolutePath());
        } else {
            // bin/login has a #!/system/bin/sh shebang; run it through mksh
            // explicitly if the exec bit was lost.
            args.add("/system/bin/sh");
            args.add(login.getAbsolutePath());
        }
        for (int i = 1; i < argv.length; i++) {
            args.add(argv[i]);
        }
        Logger.logDebug(LOG_TAG, "setupNixLoginCommand: " + String.join(" ", args));
        return args.toArray(new String[0]);
    }

    /**
     * Environment for Nix-on-Droid bootstrap. The shell is launched through
     * the official bin/login launcher which exports USER/HOME/PROOT_TMP_DIR/
     * PROOT_L2S_DIR itself and execs proot-static. This method only provides
     * a clean base environment — no LD_PRELOAD/LD_LIBRARY_PATH, no Android
     * system vars that confuse Nix/glibc.
     */
    @NonNull
    private HashMap<String, String> getNixEnvironment(@NonNull Context context,
            HashMap<String, String> environment, boolean isFailSafe, String filesDir) {
        filesDir = canonicalize(filesDir);
        String nixRoot = filesDir + "/nix-root";

        // Outer environment for the proot process. The inner command
        // (usr/lib/login-inner) sources the Nix profile and sets up its own
        // clean environment inside proot.
        environment.put(ENV_HOME, filesDir + "/home");
        environment.put(ENV_PREFIX, nixRoot);

        if (!isFailSafe) {
            // Guest paths for programs inside proot: /bin is bound to
            // nixRoot/bin and /usr/bin is bound from the bootstrap. The
            // explicit nixRoot/bin entry also covers the host side (visible
            // inside proot since / is bound to /android) before the first
            // nix-on-droid switch has populated /usr/bin.
            environment.put(ENV_TMPDIR, "/tmp");
            environment.put(ENV_PATH, nixRoot + "/bin:/usr/bin:/bin");

            // Used by login-inner for nix-env --switch-profile per-user path
            // (bin/login also sets it; kept here for consistency).
            environment.put("USER", "nix-on-droid");

            // Remove Termux-specific vars that could leak into proot
            environment.remove(ENV_LD_LIBRARY_PATH);
            environment.remove(TermuxPrefixRemap.ENV_LD_PRELOAD);
            environment.remove(TermuxPrefixRemap.ENV_REMAP_OLD_FILES_DIR);
            environment.remove(TermuxPrefixRemap.ENV_REMAP_NEW_FILES_DIR);
            environment.remove(TermuxPrefixRemap.ENV_REMAP_LIBPATH);
            environment.remove(TermuxPrefixRemap.ENV_REMAP_LOADER);
            environment.remove(TermuxPrefixRemap.ENV_REMAP_PRESERVE_ARGV0);

            // Remove Android system env vars that confuse Nix/glibc
            environment.remove("BOOTCLASSPATH");
            environment.remove("DEX2OAT_BOOTCLASSPATH");
            environment.remove("SYSTEMSERVERCLASSPATH");
            environment.remove("ANDROID_ROOT");
            environment.remove("ANDROID_DATA");
            environment.remove("ANDROID_STORAGE");
            environment.remove("ANDROID_RUNTIME_ROOT");
            environment.remove("ANDROID_TZDATA_ROOT");
            environment.remove("ANDROID_I18N_ROOT");
            environment.remove("ANDROID_ART_ROOT");
            environment.remove("ANDROID_DYN_CODE_PATH");
            environment.remove("EXTERNAL_STORAGE");
        }
        return environment;
    }

    /**
     * Canonical files dir — avoids /data/data vs /data/user/0 mismatches
     * between Java-side paths and the patched bootstrap scripts.
     */
    private static String canonicalFilesDir() {
        if (sInitContext == null) return null;
        return canonicalize(sInitContext.getFilesDir().getAbsolutePath());
    }

    private static String canonicalize(String path) {
        try {
            return new File(path).getCanonicalPath();
        } catch (Exception e) {
            return path;
        }
    }

}
