package com.termux.shared.settings.preferences;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import com.termux.shared.logger.Logger;

import java.util.Set;

public class SharedPreferenceUtils {

    private static final String LOG_TAG = "SharedPreferenceUtils";

    /**
     * Check if a key is present in {@link SharedPreferences}.
     *
     * @return {@code true} if present; {@code false} if absent or prefs are {@code null}.
     */
    public static boolean isKeyPresent(SharedPreferences sharedPreferences, String key) {
        if (sharedPreferences == null)
            return false;
        return sharedPreferences.contains(key);
    }

    /**
     * Get {@link SharedPreferences} for file {@code name} with {@link Context#MODE_PRIVATE}.
     *
     * @param name preferences file basename without extension.
     */
    public static SharedPreferences getPrivateSharedPreferences(Context context, String name) {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    /**
     * Get {@link SharedPreferences} for file {@code name} with {@link Context#MODE_PRIVATE} and
     * {@link Context#MODE_MULTI_PROCESS}.
     *
     * @param name preferences file basename without extension.
     */
    public static SharedPreferences getPrivateAndMultiProcessSharedPreferences(Context context, String name) {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
    }

    /**
     * Get a {@code boolean} from {@link SharedPreferences}.
     *
     * @param def default on missing/null prefs or {@link ClassCastException}.
     * @return the value, otherwise {@code def}.
     */
    public static boolean getBoolean(SharedPreferences sharedPreferences, String key, boolean def) {
        if (sharedPreferences == null)
            return getDefaultOnNullPreferences(key, def, "boolean");

        try {
            return sharedPreferences.getBoolean(key, def);
        }
        catch (ClassCastException e) {
            return getDefaultOnClassCastException(key, def, "boolean", e);
        }
    }

    /**
     * Set a {@code boolean} in {@link SharedPreferences}.
     *
     * @param commitToFile {@code true} commits synchronously (multi-process use-cases); else apply().
     */
    @SuppressLint("ApplySharedPref")
    public static void setBoolean(SharedPreferences sharedPreferences, String key, boolean value, boolean commitToFile) {
        if (sharedPreferences == null) {
            logNullPreferencesOnSet(key, value, "boolean");
            return;
        }

        commitOrApply(sharedPreferences.edit().putBoolean(key, value), commitToFile);
    }

    /**
     * Get a {@code float} from {@link SharedPreferences}.
     *
     * @param def default on missing/null prefs or {@link ClassCastException}.
     * @return the value, otherwise {@code def}.
     */
    public static float getFloat(SharedPreferences sharedPreferences, String key, float def) {
        if (sharedPreferences == null)
            return getDefaultOnNullPreferences(key, def, "float");

        try {
            return sharedPreferences.getFloat(key, def);
        }
        catch (ClassCastException e) {
            return getDefaultOnClassCastException(key, def, "float", e);
        }
    }

    /**
     * Set a {@code float} in {@link SharedPreferences}.
     *
     * @param commitToFile {@code true} commits synchronously (multi-process use-cases); else apply().
     */
    @SuppressLint("ApplySharedPref")
    public static void setFloat(SharedPreferences sharedPreferences, String key, float value, boolean commitToFile) {
        if (sharedPreferences == null) {
            logNullPreferencesOnSet(key, value, "float");
            return;
        }

        commitOrApply(sharedPreferences.edit().putFloat(key, value), commitToFile);
    }

    /**
     * Get an {@code int} from {@link SharedPreferences}.
     *
     * @param def default on missing/null prefs or {@link ClassCastException}.
     * @return the value, otherwise {@code def}.
     */
    public static int getInt(SharedPreferences sharedPreferences, String key, int def) {
        if (sharedPreferences == null)
            return getDefaultOnNullPreferences(key, def, "int");

        try {
            return sharedPreferences.getInt(key, def);
        }
        catch (ClassCastException e) {
            return getDefaultOnClassCastException(key, def, "int", e);
        }
    }

    /**
     * Set an {@code int} in {@link SharedPreferences}.
     *
     * @param commitToFile {@code true} commits synchronously (multi-process use-cases); else apply().
     */
    @SuppressLint("ApplySharedPref")
    public static void setInt(SharedPreferences sharedPreferences, String key, int value, boolean commitToFile) {
        if (sharedPreferences == null) {
            logNullPreferencesOnSet(key, value, "int");
            return;
        }

        commitOrApply(sharedPreferences.edit().putInt(key, value), commitToFile);
    }

    /**
     * Increment an {@code int} in {@link SharedPreferences}, returning the value before increment.
     *
     * @param def default on missing/null prefs.
     * @param commitToFile {@code true} commits synchronously (multi-process use-cases); else apply().
     * @param resetValue if non-null, used when the current/new value would go negative.
     * @return the value before increment, otherwise {@code def}.
     */
    @SuppressLint("ApplySharedPref")
    public static int getAndIncrementInt(SharedPreferences sharedPreferences, String key, int def,
                                         boolean commitToFile, Integer resetValue) {
        if (sharedPreferences == null) {
            Logger.logError(LOG_TAG, "Ignoring incrementing int value for the \"" + key + "\" key into null shared preferences.");
            return def;
        }

        int curValue = getInt(sharedPreferences, key, def);
        if (resetValue != null && (curValue < 0)) curValue = resetValue;

        int newValue = curValue + 1;
        if (resetValue != null && newValue < 0) newValue = resetValue;

        setInt(sharedPreferences, key, newValue, commitToFile);
        return curValue;
    }

    /**
     * Get a {@code long} from {@link SharedPreferences}.
     *
     * @param def default on missing/null prefs or {@link ClassCastException}.
     * @return the value, otherwise {@code def}.
     */
    public static long getLong(SharedPreferences sharedPreferences, String key, long def) {
        if (sharedPreferences == null)
            return getDefaultOnNullPreferences(key, def, "long");

        try {
            return sharedPreferences.getLong(key, def);
        }
        catch (ClassCastException e) {
            return getDefaultOnClassCastException(key, def, "long", e);
        }
    }

    /**
     * Set a {@code long} in {@link SharedPreferences}.
     *
     * @param commitToFile {@code true} commits synchronously (multi-process use-cases); else apply().
     */
    @SuppressLint("ApplySharedPref")
    public static void setLong(SharedPreferences sharedPreferences, String key, long value, boolean commitToFile) {
        if (sharedPreferences == null) {
            logNullPreferencesOnSet(key, value, "long");
            return;
        }

        commitOrApply(sharedPreferences.edit().putLong(key, value), commitToFile);
    }

    /**
     * Get a {@code String} from {@link SharedPreferences}.
     *
     * @param def default on missing/null prefs or {@link ClassCastException}.
     * @param defIfEmpty also return {@code def} when the stored value is empty.
     * @return the value, otherwise {@code def}.
     */
    public static String getString(SharedPreferences sharedPreferences, String key, String def, boolean defIfEmpty) {
        if (sharedPreferences == null)
            return getDefaultOnNullPreferences(key, def, "String");

        try {
            String value = sharedPreferences.getString(key, def);
            if (defIfEmpty && (value == null || value.isEmpty()))
                return def;
            else
                return value;
        }
        catch (ClassCastException e) {
            return getDefaultOnClassCastException(key, def, "String", e);
        }
    }

    /**
     * Set a {@code String} in {@link SharedPreferences}.
     *
     * @param commitToFile {@code true} commits synchronously (multi-process use-cases); else apply().
     */
    @SuppressLint("ApplySharedPref")
    public static void setString(SharedPreferences sharedPreferences, String key, String value, boolean commitToFile) {
        if (sharedPreferences == null) {
            logNullPreferencesOnSet(key, value, "String");
            return;
        }

        commitOrApply(sharedPreferences.edit().putString(key, value), commitToFile);
    }

    /**
     * Get a {@code Set<String>} from {@link SharedPreferences}.
     *
     * @param def default on missing/null prefs or {@link ClassCastException}.
     * @return the value, otherwise {@code def}.
     */
    public static Set<String> getStringSet(SharedPreferences sharedPreferences, String key, Set<String> def) {
        if (sharedPreferences == null)
            return getDefaultOnNullPreferences(key, def, "Set<String>");

        try {
            return sharedPreferences.getStringSet(key, def);
        }
        catch (ClassCastException e) {
            return getDefaultOnClassCastException(key, def, "Set<String>", e);
        }
    }

    /**
     * Set a {@code Set<String>} in {@link SharedPreferences}.
     *
     * @param commitToFile {@code true} commits synchronously (multi-process use-cases); else apply().
     */
    @SuppressLint("ApplySharedPref")
    public static void setStringSet(SharedPreferences sharedPreferences, String key, Set<String> value, boolean commitToFile) {
        if (sharedPreferences == null) {
            logNullPreferencesOnSet(key, value, "Set<String>");
            return;
        }

        commitOrApply(sharedPreferences.edit().putStringSet(key, value), commitToFile);
    }

    /**
     * Get an {@code int} from {@link SharedPreferences} that is stored as a {@link String}.
     *
     * @param def default on missing/null prefs or parse failure.
     * @return the parsed value, otherwise {@code def}.
     */
    public static int getIntStoredAsString(SharedPreferences sharedPreferences, String key, int def) {
        if (sharedPreferences == null)
            return getDefaultOnNullPreferences(key, def, "int");

        String stringValue;
        int intValue;

        try {
            stringValue = sharedPreferences.getString(key, Integer.toString(def));
            if (stringValue != null)
                intValue =  Integer.parseInt(stringValue);
            else
                intValue = def;
        } catch (NumberFormatException | ClassCastException e) {
            intValue = def;
        }

        return intValue;
    }

    /**
     * Set an {@code int} into {@link SharedPreferences} that is stored as a {@link String}.
     *
     * @param commitToFile {@code true} commits synchronously (multi-process use-cases); else apply().
     */
    @SuppressLint("ApplySharedPref")
    public static void setIntStoredAsString(SharedPreferences sharedPreferences, String key, int value, boolean commitToFile) {
        if (sharedPreferences == null) {
            logNullPreferencesOnSet(key, value, "int");
            return;
        }

        commitOrApply(sharedPreferences.edit().putString(key, Integer.toString(value)), commitToFile);
    }

    private static <T> T getDefaultOnNullPreferences(String key, T def, String valueType) {
        Logger.logError(LOG_TAG, "Error getting " + valueType + " value for the \"" + key + "\" key from null shared preferences. Returning default value \"" + def + "\".");
        return def;
    }

    private static <T> T getDefaultOnClassCastException(String key, T def, String valueType, ClassCastException e) {
        Logger.logStackTraceWithMessage(LOG_TAG, "Error getting " + valueType + " value for the \"" + key + "\" key from shared preferences. Returning default value \"" + def + "\".", e);
        return def;
    }

    private static void logNullPreferencesOnSet(String key, Object value, String valueType) {
        Logger.logError(LOG_TAG, "Ignoring setting " + valueType + " value \"" + value + "\" for the \"" + key + "\" key into null shared preferences.");
    }

    @SuppressLint("ApplySharedPref")
    private static void commitOrApply(SharedPreferences.Editor editor, boolean commitToFile) {
        if (commitToFile)
            editor.commit();
        else
            editor.apply();
    }

}
