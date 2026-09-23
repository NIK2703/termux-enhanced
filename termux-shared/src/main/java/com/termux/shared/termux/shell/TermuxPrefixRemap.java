package com.termux.shared.termux.shell;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Helper for the LD_PRELOAD path-remapping shim (libtermux-prefix-remap.so).
 *
 * The shim intercepts libc file operations (open, stat, execve, etc.) and
 * rewrites hardcoded Termux bootstrap paths (e.g. /data/data/com.termux/files/...)
 * to the actual runtime prefix at runtime.
 *
 * This class also provides loader-wrapping for ELF binaries whose PT_INTERP
 * points to a stale path — the kernel cannot start them directly, so we launch
 * them via the glibc dynamic loader explicitly:
 *
 *   ld-linux-aarch64.so.1 --library-path $PREFIX/lib $PREFIX/bin/binary args...
 *
 * The shim .so itself should be built with a glibc toolchain and placed at
 * $PREFIX/lib/libtermux-prefix-remap.so.  An NDK (bionic) build is also
 * provided for completeness but will not work inside glibc processes.
 */
public final class TermuxPrefixRemap {

    private static final String LOG_TAG = "TermuxPrefixRemap";

    public static final String REMAP_LIB_NAME = "libtermux-prefix-remap.so";

    // Environment variable names
    public static final String ENV_LD_PRELOAD = "LD_PRELOAD";
    public static final String ENV_REMAP_OLD_FILES_DIR = "TERMUX_REMAP_OLD_FILES_DIR";
    public static final String ENV_REMAP_NEW_FILES_DIR = "TERMUX_REMAP_NEW_FILES_DIR";
    public static final String ENV_REMAP_LIBPATH = "TERMUX_REMAP_LIBPATH";
    public static final String ENV_REMAP_LOADER = "TERMUX_REMAP_LOADER";
    public static final String ENV_REMAP_PRESERVE_ARGV0 = "TERMUX_REMAP_PRESERVE_ARGV0";

    public static final String DEFAULT_LOADER_REL = "ld-linux-aarch64.so.1";

    private TermuxPrefixRemap() {}

    // ── Build detection ──

    /** True when the app package name differs from the bootstrap zip target. */
    public static boolean needsSubstitution(@NonNull Context context) {
        return !TermuxConstants.TERMUX_BOOTSTRAP_TARGET_PACKAGE_NAME.equals(context.getPackageName());
    }

    // ── Shim path ──

    /** Full path to the remap library inside the prefix. */
    @NonNull
    public static String getRemapLibPath(@NonNull String libDirPath) {
        return libDirPath + "/" + REMAP_LIB_NAME;
    }

    /** Whether the remap library is readable on disk. */
    public static boolean isRemapLibAvailable(@NonNull String libDirPath) {
        return new File(getRemapLibPath(libDirPath)).canRead();
    }

    /**
     * Resolve the runtime files dir.  Prefers the cached sResolvedHomeDirPath
     * (set from Application context in TermuxShellEnvironment.init()) over
     * direct context.getFilesDir().
     */
    @NonNull
    public static String resolveRuntimeFilesDir(@NonNull Context context) {
        String home = null;
        try {
            java.lang.reflect.Field f = Class.forName("com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment")
                .getDeclaredField("sResolvedHomeDirPath");
            f.setAccessible(true);
            home = (String) f.get(null);
        } catch (Exception ignored) {}

        if (home != null && home.endsWith("/home")) {
            String fd = home.substring(0, home.length() - 5);
            return fd.replaceFirst("^/data/user/0/", "/data/data/");
        }
        return context.getFilesDir().getAbsolutePath()
            .replaceFirst("^/data/user/0/", "/data/data/");
    }

    /**
     * Ensure the remap library exists at {@code libDirPath}.
     *
     * If the shim is shipped inside the bootstrap zip, this is a no-op.
     * Otherwise, attempts to copy from the APK's native library directory
     * (NDK-built, bionic ABI — works only for bionic-based Termux forks).
     */
    public static void ensureInstalled(@NonNull Context context,
                                       @NonNull String libDirPath) {
        File target = new File(getRemapLibPath(libDirPath));
        if (target.canRead()) return;

        // Try copying from the APK's native lib dir (NDK build)
        try {
            String abiDir;
            String arch = System.getProperty("os.arch", "");
            if (arch.contains("aarch64") || arch.contains("arm64"))
                abiDir = "arm64-v8a";
            else if (arch.contains("arm"))
                abiDir = "armeabi-v7a";
            else if (arch.contains("x86_64"))
                abiDir = "x86_64";
            else
                abiDir = "x86";

            String apkLibPath = context.getApplicationInfo().nativeLibraryDir
                + "/" + REMAP_LIB_NAME;
            File apkLib = new File(apkLibPath);
            if (apkLib.canRead()) {
                File libDir = new File(libDirPath);
                if (!libDir.isDirectory()) libDir.mkdirs();
                try (InputStream is = new FileInputStream(apkLib);
                     FileOutputStream os = new FileOutputStream(target)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = is.read(buf)) >= 0)
                        os.write(buf, 0, n);
                }
                target.setExecutable(true, false);
                target.setReadable(true, false);
                Logger.logInfo(LOG_TAG, "Installed " + REMAP_LIB_NAME + " from APK native libs to " + target);
                return;
            }
        } catch (Exception e) {
            Logger.logDebug(LOG_TAG, "Could not install " + REMAP_LIB_NAME + " from APK: " + e.getMessage());
        }

        Logger.logInfo(LOG_TAG, REMAP_LIB_NAME + " not available at " + target
            + " — will attempt LD_PRELOAD at runtime (shim will be inactive until installed)");
    }

    // ── Loader wrapping (handles stale PT_INTERP) ──

    /**
     * The best loader name to look for.  Returns the first one that exists
     * under {@code libDirPath}.
     */
    @Nullable
    public static String findLoader(@NonNull String libDirPath) {
        String[] candidates = {
            libDirPath + "/" + DEFAULT_LOADER_REL,
            libDirPath + "/ld-linux-aarch64.so.1",
            libDirPath + "/ld-linux.so.1",
            libDirPath + "/ld-linux-armhf.so.3",
        };
        for (String c : candidates) {
            if (new File(c).canExecute()) return c;
        }
        return null;
    }

    /**
     * Check whether an ELF binary needs loader wrapping because its
     * PT_INTERP path does not exist on disk.
     *
     * Reads the first few bytes for the ELF magic, then walks the
     * program headers to find PT_INTERP.
     */
    public static boolean elfNeedsLoaderWrap(@NonNull String executable,
                                              @NonNull String libDirPath) {
        // Must be under the runtime prefix
        if (!executable.startsWith(libDirPath.replace("/lib", "/"))) {
            // Hmm, approximate check
        }

        String loader = findLoader(libDirPath);
        if (loader == null) return false;

        try (FileInputStream in = new FileInputStream(executable)) {
            byte[] hdr = new byte[64];
            int n = in.read(hdr);
            if (n < 64) return false;
            if (hdr[0] != 0x7f || hdr[1] != 'E' || hdr[2] != 'L' || hdr[3] != 'F')
                return false; // not ELF

            boolean is64Bit = (hdr[4] == 2); // EI_CLASS = ELFCLASS64
            int e_phoff, e_phentsize, e_phnum;
            if (is64Bit) {
                e_phoff = bytesToInt(hdr, 32, 8); // Elf64_Ehdr.e_phoff
                e_phentsize = bytesToInt(hdr, 54, 2); // Elf64_Ehdr.e_phentsize
                e_phnum = bytesToInt(hdr, 56, 2); // Elf64_Ehdr.e_phnum
            } else {
                e_phoff = bytesToInt(hdr, 28, 4); // Elf32_Ehdr.e_phoff
                e_phentsize = bytesToInt(hdr, 42, 2); // Elf32_Ehdr.e_phentsize
                e_phnum = bytesToInt(hdr, 44, 2); // Elf32_Ehdr.e_phnum
            }

            if (e_phentsize < 32) return false; // sanity

            for (int i = 0; i < e_phnum; i++) {
                byte[] ph = new byte[e_phentsize];
                // Read program header from file offset
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(executable, "r");
                raf.seek(e_phoff + (long) i * e_phentsize);
                raf.readFully(ph);
                raf.close();

                int p_type = bytesToInt(ph, 0, 4); // p_type
                if (p_type == 3) { // PT_INTERP
                    long p_offset = is64Bit ?
                        ((long)bytesToInt(ph, 8, 4) | ((long)bytesToInt(ph, 12, 4) << 32)) :
                        ((long)bytesToInt(ph, 4, 4));
                    long p_filesz = is64Bit ?
                        ((long)bytesToInt(ph, 32, 4) | ((long)bytesToInt(ph, 36, 4) << 32)) :
                        ((long)bytesToInt(ph, 16, 4));

                    if (p_filesz > 4096 || p_filesz <= 0) return false;

                    raf = new java.io.RandomAccessFile(executable, "r");
                    raf.seek(p_offset);
                    byte[] interpBytes = new byte[(int) p_filesz];
                    raf.readFully(interpBytes);
                    raf.close();

                    // Find null terminator
                    int nullPos = 0;
                    while (nullPos < interpBytes.length && interpBytes[nullPos] != 0) nullPos++;
                    String interp = new String(interpBytes, 0, nullPos, java.nio.charset.StandardCharsets.UTF_8);

                    if (interp.startsWith("/") && !new File(interp).exists()) {
                        return true;
                    }
                    return false;
                }
            }
        } catch (IOException e) {
            // Ignore
        }

        return false;
    }

    // ── Wrapping ──

    /**
     * Wrap an argv array so that the ELF binary is launched via the
     * glibc dynamic loader when its PT_INTERP is stale.
     *
     * @return wrapped argv, or the original if wrapping is not needed.
     */
    @NonNull
    public static String[] wrapExecutableIfNeeded(@NonNull String libDirPath,
                                                   @NonNull String[] argv) {
        if (argv.length == 0) return argv;

        String executable = argv[0];

        if (!elfNeedsLoaderWrap(executable, libDirPath))
            return argv;

        String loader = findLoader(libDirPath);
        if (loader == null) return argv;

        Logger.logDebug(LOG_TAG, "Wrapping executable via loader: " + executable
            + " loader=" + loader + " libpath=" + libDirPath);

        ArrayList<String> wrapped = new ArrayList<>();
        wrapped.add(loader);
        wrapped.add("--library-path");
        wrapped.add(libDirPath);
        wrapped.add("--argv0");
        wrapped.add(argv[0]);
        wrapped.add(executable);
        for (int i = 1; i < argv.length; i++)
            wrapped.add(argv[i]);

        return wrapped.toArray(new String[0]);
    }

    // ── helpers ──

    private static int bytesToInt(byte[] buf, int off, int len) {
        int v = 0;
        for (int i = 0; i < len; i++) {
            v |= (buf[off + i] & 0xFF) << (i * 8);
        }
        return v;
    }
}
