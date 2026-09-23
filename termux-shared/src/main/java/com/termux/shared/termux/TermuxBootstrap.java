package com.termux.shared.termux;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP;

import java.io.File;

public class TermuxBootstrap {

    private static final String LOG_TAG = "TermuxBootstrap";

    /** The field name used by Termux app to store package variant in
     * {@link TERMUX_APP#BUILD_CONFIG_CLASS_NAME} class. */
    public static final String BUILD_CONFIG_FIELD_TERMUX_PACKAGE_VARIANT = "TERMUX_PACKAGE_VARIANT";


    /** The {@link PackageManager} for the bootstrap in the app APK added in app/build.gradle. */
    public static PackageManager TERMUX_APP_PACKAGE_MANAGER;

    /** The {@link PackageVariant} for the bootstrap in the app APK added in app/build.gradle. */
    public static PackageVariant TERMUX_APP_PACKAGE_VARIANT;

    /** Set {@link #TERMUX_APP_PACKAGE_VARIANT} and {@link #TERMUX_APP_PACKAGE_MANAGER} from {@code packageVariantName} passed. */
    public static void setTermuxPackageManagerAndVariant(@Nullable String packageVariantName) {
        TERMUX_APP_PACKAGE_VARIANT = PackageVariant.variantOf(packageVariantName);
        if (TERMUX_APP_PACKAGE_VARIANT == null) {
            throw new RuntimeException("Unsupported TERMUX_APP_PACKAGE_VARIANT \"" + packageVariantName + "\"");
        }

        Logger.logVerbose(LOG_TAG, "Set TERMUX_APP_PACKAGE_VARIANT to \"" + TERMUX_APP_PACKAGE_VARIANT + "\"");

        // Set packageManagerName to substring before first dash "-" in packageVariantName
        int index = packageVariantName.indexOf('-');
        String packageManagerName = (index == -1) ? null : packageVariantName.substring(0, index);
        TERMUX_APP_PACKAGE_MANAGER = PackageManager.managerOf(packageManagerName);
        if (TERMUX_APP_PACKAGE_MANAGER == null) {
            throw new RuntimeException("Unsupported TERMUX_APP_PACKAGE_MANAGER \"" + packageManagerName + "\" with variant \"" + packageVariantName + "\"");
        }

        Logger.logVerbose(LOG_TAG, "Set TERMUX_APP_PACKAGE_MANAGER to \"" + TERMUX_APP_PACKAGE_MANAGER + "\"");
    }

    /**
     * Initialize variant from runtime state (SharedPreferences), falling back to the given
     * {@code defaultVariantName}.
     *
     * @param context Application context.
     * @param defaultVariantName The variant name from BuildConfig (fallback).
     */
    public static void initializeFromRuntime(@NonNull Context context, @Nullable String defaultVariantName) {
        String variant = null;
        try {
            variant = context.getSharedPreferences("termux_bootstrap_state", Context.MODE_PRIVATE)
                .getString("installed_package_variant", null);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to read runtime variant: " + e.getMessage());
        }

        if (variant == null || variant.isEmpty()) {
            // Old install without marker: detect from filesystem.
            File prefixBin = new File(context.getFilesDir(), "usr/bin");
            if (prefixBin.isDirectory()) {
                String[] children = prefixBin.list();
                if (children != null && children.length > 0) {
                    variant = Build.VERSION.SDK_INT >= 26 ? "apt-android-7" : "apt-android-5";
                }
            }
        }

        if (variant == null || variant.isEmpty()) {
            variant = defaultVariantName;
        }

        if (variant != null) {
            setTermuxPackageManagerAndVariant(variant);
        } else {
            Logger.logError(LOG_TAG, "No variant available (neither runtime nor default)");
        }
    }

    /**
     * Get {@link #BUILD_CONFIG_FIELD_TERMUX_PACKAGE_VARIANT} field value from the
     * {@link TERMUX_APP#BUILD_CONFIG_CLASS_NAME} class of the Termux app APK installed on the device.
     * This can only be used by apps that share `sharedUserId` with the Termux app.
     *
     * @param currentPackageContext The context of current package.
     * @return Returns the field value, otherwise {@code null} if an exception was raised or failed
     * to get termux app package context.
     */
    public static String getTermuxAppBuildConfigPackageVariantFromTermuxApp(@NonNull Context currentPackageContext) {
        try {
            return (String) TermuxUtils.getTermuxAppAPKBuildConfigClassField(currentPackageContext, BUILD_CONFIG_FIELD_TERMUX_PACKAGE_VARIANT);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get \"" + BUILD_CONFIG_FIELD_TERMUX_PACKAGE_VARIANT + "\" value from \"" + TERMUX_APP.BUILD_CONFIG_CLASS_NAME + "\" class", e);
            return null;
        }
    }



    /** Is {@link PackageManager#APT} set as {@link #TERMUX_APP_PACKAGE_MANAGER}. */
    public static boolean isAppPackageManagerAPT() {
        return PackageManager.APT.equals(TERMUX_APP_PACKAGE_MANAGER);
    }

    /** Is {@link PackageVariant#APT_ANDROID_5} set as {@link #TERMUX_APP_PACKAGE_VARIANT}. */
    public static boolean isAppPackageVariantAPTAndroid5() {
        return PackageVariant.APT_ANDROID_5.equals(TERMUX_APP_PACKAGE_VARIANT);
    }

    public enum PackageManager {

        /**
         * Advanced Package Tool (APT) for managing debian deb package files.
         * https://wiki.debian.org/Apt
         * https://wiki.debian.org/deb
         */
        APT("apt");

        private final String name;

        PackageManager(final String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        /** Get {@link PackageManager} for {@code name} if found, otherwise {@code null}. */
        @Nullable
        public static PackageManager managerOf(String name) {
            if (name == null || name.isEmpty()) return null;
            for (PackageManager v : PackageManager.values()) {
                if (v.name.equals(name)) {
                    return v;
                }
            }
            return null;
        }

    }



    /** Termux package variant. The substring before first dash "-" must match one of the {@link PackageManager}. */
    public enum PackageVariant {

        /** {@link PackageManager#APT} variant for Android 7+. */
        APT_ANDROID_7("apt-android-7"),

        /** {@link PackageManager#APT} variant for Android 5+. */
        APT_ANDROID_5("apt-android-5");

        private final String name;

        PackageVariant(final String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        /** Get {@link PackageVariant} for {@code name} if found, otherwise {@code null}. */
        @Nullable
        public static PackageVariant variantOf(String name) {
            if (name == null || name.isEmpty()) return null;
            for (PackageVariant v : PackageVariant.values()) {
                if (v.name.equals(name)) {
                    return v;
                }
            }
            return null;
        }

    }

}
