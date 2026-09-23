package com.termux.installer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class TermuxBootstrapState {

    private static final String PREFS_NAME = "termux_bootstrap_state";
    private static final String KEY_VARIANT = "installed_package_variant";

    private TermuxBootstrapState() {}

    public static void setInstalledVariant(Context context, String variant) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_VARIANT, variant).apply();
    }

    public static String getInstalledVariant(Context context) {
        String fromMarker = readVariantMarker(context);
        if (fromMarker != null) return fromMarker;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String fromPrefs = prefs.getString(KEY_VARIANT, null);
        if (fromPrefs != null) return fromPrefs;

        // Old install without marker: detect from filesystem.
        File prefixBin = new File(context.getFilesDir(), "usr/bin");
        if (prefixBin.isDirectory()) {
            String[] children = prefixBin.list();
            if (children != null && children.length > 0) {
                return Build.VERSION.SDK_INT >= 26 ? "apt-android-7" : "apt-android-5";
            }
        }

        return null;
    }

    private static String readVariantMarker(Context context) {
        File prefix = new File(context.getFilesDir(), "usr");
        File marker = new File(prefix, "etc/termux/bootstrap_variant");
        if (!marker.isFile()) return null;

        try (InputStream in = new FileInputStream(marker);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }

    public static void writeVariantMarker(Context context, String variant) throws IOException {
        File marker = new File(context.getFilesDir(), "usr/etc/termux/bootstrap_variant");
        File parent = marker.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException(context.getString(com.termux.R.string.error_bootstrap_state_create_dir));
        }
        try (OutputStream out = new FileOutputStream(marker)) {
            out.write(variant.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();
        }
    }
}
