package com.termux.installer;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
