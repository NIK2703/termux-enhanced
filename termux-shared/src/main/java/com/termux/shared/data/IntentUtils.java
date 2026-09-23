package com.termux.shared.data;

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.Arrays;

public class IntentUtils {

    /**
     * Get a {@link String} extra from an {@link Intent} if it is not {@code null} or empty.
     *
     * @param def default value if the extra is not set.
     * @return the extra if set, otherwise {@code null}.
     */
    public static String getStringExtraIfSet(@NonNull Intent intent, String key, String def) {
        String value = intent.getStringExtra(key);
        if (value == null || value.isEmpty()) {
            if (def != null && !def.isEmpty())
                return def;
            else
                return null;
        }
        return value;
    }

    /**
     * Get an {@link Integer} from a non-empty {@link String} extra of an {@link Intent}.
     *
     * @param def default value if the extra is not set or not parseable.
     * @return the integer if set, otherwise {@code def}.
     */
    public static Integer getIntegerExtraIfSet(@NonNull Intent intent, String key, Integer def) {
        try {
            String value = intent.getStringExtra(key);
            if (value == null || value.isEmpty()) {
                return def;
            }

            return Integer.parseInt(value);
        }
        catch (Exception e) {
            return def;
        }
    }

    /**
     * Get a {@link String[]} extra from an {@link Intent} if it is not {@code null} or empty.
     *
     * @param def default value if the extra is not set.
     * @return the extra if set, otherwise {@code null}.
     */
    public static String[] getStringArrayExtraIfSet(Intent intent, String key, String[] def) {
        String[] value = intent.getStringArrayExtra(key);
        if (value == null || value.length == 0) {
            if (def != null && def.length != 0)
                return def;
            else
                return null;
        }
        return value;
    }

    public static String getIntentString(Intent intent) {
        if (intent == null) return null;

        return intent.toString() + "\n" + getBundleString(intent.getExtras());
    }

    public static String getBundleString(Bundle bundle) {
        if (bundle == null || bundle.size() == 0) return "Bundle[]";

        StringBuilder bundleString = new StringBuilder("Bundle[\n");
        boolean first = true;
        for (String key : bundle.keySet()) {
            if (!first)
                bundleString.append("\n");

            bundleString.append(key).append(": `");

            Object value = bundle.get(key);
            if (value instanceof int[]) {
                bundleString.append(Arrays.toString((int[]) value));
            } else if (value instanceof byte[]) {
                bundleString.append(Arrays.toString((byte[]) value));
            } else if (value instanceof boolean[]) {
                bundleString.append(Arrays.toString((boolean[]) value));
            } else if (value instanceof short[]) {
                bundleString.append(Arrays.toString((short[]) value));
            } else if (value instanceof long[]) {
                bundleString.append(Arrays.toString((long[]) value));
            } else if (value instanceof float[]) {
                bundleString.append(Arrays.toString((float[]) value));
            } else if (value instanceof double[]) {
                bundleString.append(Arrays.toString((double[]) value));
            } else if (value instanceof String[]) {
                bundleString.append(Arrays.toString((String[]) value));
            } else if (value instanceof CharSequence[]) {
                bundleString.append(Arrays.toString((CharSequence[]) value));
            } else if (value instanceof Parcelable[]) {
                bundleString.append(Arrays.toString((Parcelable[]) value));
            } else if (value instanceof Bundle) {
                bundleString.append(getBundleString((Bundle) value));
            } else {
                bundleString.append(value);
            }

            bundleString.append("`");

            first = false;
        }

        bundleString.append("\n]");
        return bundleString.toString();
    }

}
