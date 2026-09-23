package com.termux.installer;

import android.content.Context;
import android.os.Build;

import com.termux.app.TermuxInstaller;

import java.util.Arrays;

public final class AbiUtils {

    private AbiUtils() {}

    public static String getDeviceArch() {
        if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
            String primary = Build.SUPPORTED_ABIS[0];
            return abiToBootstrapArch(primary);
        }
        throw new RuntimeException("No supported ABIs on this device");
    }

    public static void validateBootstrapArch(Context context, String manifestArch) throws TermuxInstaller.BootstrapException {
        String deviceArch = getDeviceArch();
        if (!manifestArch.equals(deviceArch)) {
            throw new TermuxInstaller.BootstrapException(
                context.getString(com.termux.R.string.error_abi_arch_mismatch, manifestArch, deviceArch, Arrays.toString(Build.SUPPORTED_ABIS)));
        }
    }

    public static String abiToBootstrapArch(String abi) {
        switch (abi) {
            case "arm64-v8a": return "aarch64";
            case "armeabi-v7a": return "arm";
            case "x86": return "i686";
            case "x86_64": return "x86_64";
            default: throw new IllegalArgumentException("Unknown ABI: " + abi);
        }
    }
}
