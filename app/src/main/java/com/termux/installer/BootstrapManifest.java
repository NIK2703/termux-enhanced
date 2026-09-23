package com.termux.installer;

import android.content.Context;

import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class BootstrapManifest {

    public final String variant;
    public final String arch;

    public BootstrapManifest(String variant, String arch) {
        this.variant = variant;
        this.arch = arch;
    }

    public static BootstrapManifest fromZip(Context context, File zipFile) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            ZipEntry entry = zip.getEntry("BOOTSTRAP_INFO");
            if (entry == null) {
                Logger.logInfo("BootstrapManifest", "BOOTSTRAP_INFO not found in zip, skipping manifest validation");
                return null;
            }
            Properties props = new Properties();
            try (InputStream in = zip.getInputStream(entry)) {
                props.load(in);
            }
            String variant = props.getProperty("variant");
            String arch = props.getProperty("arch");
            if (variant == null || variant.isEmpty()) {
                throw new IOException(context.getString(com.termux.R.string.error_bootstrap_manifest_missing_variant));
            }
            if (arch == null || arch.isEmpty()) {
                throw new IOException(context.getString(com.termux.R.string.error_bootstrap_manifest_missing_arch));
            }
            return new BootstrapManifest(variant, arch);
        }
    }
}
