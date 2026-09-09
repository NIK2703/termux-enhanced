package com.termux.shared.termux;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Set;

/**
 * Identifies the bootstrap layout and runtime strategy.
 */
public enum TermuxBootstrapType {

    /** Standard Termux bootstrap: usr/bin, usr/lib, ELF binaries with hardcoded prefix paths. */
    TERMUX("termux"),

    /** Nix-on-Droid bootstrap: nix/store, proot-static launcher, no usr/ directory. */
    NIX("nix");

    private final String value;

    TermuxBootstrapType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * Detect bootstrap type from manifest variant string.
     */
    @NonNull
    public static TermuxBootstrapType fromVariant(@Nullable String variant) {
        if (variant != null && variant.toLowerCase().contains("nix")) {
            return NIX;
        }
        return TERMUX;
    }

    /**
     * Fallback detection when no manifest is present: scan zip entry names.
     */
    @NonNull
    public static TermuxBootstrapType fromZipEntries(@Nullable Set<String> entryNames) {
        if (entryNames == null) return TERMUX;

        boolean hasNixStore = false;
        boolean hasProotStatic = false;
        boolean hasUsrDir = false;

        for (String name : entryNames) {
            if (name.startsWith("nix/store/") || name.contains("/nix/store/")) {
                hasNixStore = true;
            }
            if (name.equals("bin/proot-static") || name.endsWith("/proot-static")) {
                hasProotStatic = true;
            }
            if (name.startsWith("usr/") || name.equals("usr")) {
                hasUsrDir = true;
            }
        }

        if ((hasNixStore || hasProotStatic) && !hasUsrDir) {
            return NIX;
        }

        return TERMUX;
    }

    /**
     * Read the installed bootstrap type from a files directory.
     * Falls back to TERMUX if the marker is absent or unreadable.
     *
     * Cached by the marker's lastModified: this is read several times per session start
     * (env build, bootstrap check, DocumentsProvider) and on every UI path that depends on it.
     */
    private static volatile long sTypeMarkerMtime = Long.MIN_VALUE;
    private static volatile TermuxBootstrapType sCachedType;

    @NonNull
    public static TermuxBootstrapType getInstalledType(@NonNull java.io.File filesDir) {
        java.io.File marker = new java.io.File(filesDir, ".termux-bootstrap-type");
        long mtime = marker.exists() ? marker.lastModified() : Long.MIN_VALUE + 1;
        if (mtime == sTypeMarkerMtime && sCachedType != null) return sCachedType;
        TermuxBootstrapType type = readInstalledType(filesDir, marker);
        sTypeMarkerMtime = mtime;
        sCachedType = type;
        return type;
    }

    @NonNull
    private static TermuxBootstrapType readInstalledType(@NonNull java.io.File filesDir, @NonNull java.io.File marker) {
        if (!marker.exists()) return TERMUX;
        try {
            byte[] buf = new byte[64];
            int n;
            try (java.io.FileInputStream in = new java.io.FileInputStream(marker)) {
                n = in.read(buf);
            }
            if (n <= 0) return TERMUX;
            String value = new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim();
            if ("nix".equals(value)) return NIX;
        } catch (Exception ignored) {}
        return TERMUX;
    }

    /**
     * Validate Nix store path format: /nix/store/<32-base32-chars>-<pkg-name>/...
     */
    public static boolean isValidNixStorePath(@NonNull String target) {
        if (!target.startsWith("/nix/store/")) return false;
        String rest = target.substring("/nix/store/".length());
        if (rest.isEmpty() || rest.equals("/")) return true;
        String[] parts = rest.split("/", 2);
        String hashPart = parts[0];
        return hashPart.length() >= 33 && hashPart.charAt(32) == '-';
    }
}
