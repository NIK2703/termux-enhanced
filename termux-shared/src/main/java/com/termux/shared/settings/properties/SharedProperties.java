package com.termux.shared.settings.properties;

import android.content.Context;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import com.termux.shared.file.FileUtils;
import com.termux.shared.file.filesystem.FileType;
import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/**
 * An implementation similar to android's {@link android.content.SharedPreferences} interface for
 * reading and writing to and from ".properties" files which also maintains an in-memory cache for
 * the key/value pairs when an instance object is used. Operations are done under
 * synchronization locks and should be thread safe.
 *
 * If {@link SharedProperties} instance object is used, then two types of in-memory cache maps are
 * maintained, one for the literal {@link String} values found in the file for the keys and an
 * additional one that stores (near) primitive {@link Object} values for internal use by the caller.
 *
 * The {@link SharedProperties} also provides static functions that can be used to read properties
 * from files or individual key values or even their internal values. An automatic mapping to a
 * boolean as internal value can also be done. An in-memory cache is not maintained, nor are locks used.
 *
 * This currently only has read support, write support can/will be added later if needed. Check android's
 * SharedPreferencesImpl class for reference implementation.
 *
 * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:frameworks/base/core/java/android/app/SharedPreferencesImpl.java
 */
public class SharedProperties {

    /** Defines the bidirectional map for boolean values and their internal values  */
    public static final ImmutableBiMap<String, Boolean> MAP_GENERIC_BOOLEAN =
        new ImmutableBiMap.Builder<String, Boolean>()
            .put("true", true)
            .put("false", false)
            .build();

    private static final String LOG_TAG = "SharedProperties";

    /**
     * Get the {@link Properties} object for the propertiesFile. A lock is not
     * taken when this function is called.
     *
     * @param context The {@link Context} to use to show a flash if an exception is raised while
     *                reading the file. If context is {@code null}, then flash will not be shown.
     * @param propertiesFile The {@link File} to read the {@link Properties} from.
     * @return Returns the {@link Properties} object. It will be {@code null} if an exception is
     * raised while reading the file.
     */
    public static Properties getPropertiesFromFile(Context context, File propertiesFile, @Nullable SharedPropertiesParser sharedPropertiesParser) {
        Properties properties = new Properties();

        if (propertiesFile == null) {
            Logger.logWarn(LOG_TAG, "Not loading properties since file is null");
            return properties;
        }

        try {
            try (FileInputStream in = new FileInputStream(propertiesFile)) {
                Logger.logVerbose(LOG_TAG, "Loading properties from \"" + propertiesFile.getAbsolutePath() + "\" file");
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            if (context != null)
                Toast.makeText(context, context.getString(com.termux.shared.R.string.error_could_not_open_properties_file, propertiesFile.getAbsolutePath(), e.getMessage()), Toast.LENGTH_LONG).show();
            Logger.logStackTraceWithMessage(LOG_TAG, "Error loading properties file \"" + propertiesFile.getAbsolutePath() + "\"", e);
            return null;
        }

        if (sharedPropertiesParser != null && context != null)
            return sharedPropertiesParser.preProcessPropertiesOnReadFromDisk(context, properties);
        else
            return properties;
    }

    /** Returns the first {@link File} found in
     * {@code propertiesFilePaths} from which app properties can be loaded. If the {@link File} found
     * is not a regular file or is not readable, then {@code null} is returned. Symlinks **will not**
     * be followed for potential security reasons.
     *
     * @param propertiesFilePaths The {@link List<String>} containing properties file paths.
     * @param logTag If log tag to use for logging errors.
     * @return Returns the {@link File} object for Termux:Float app properties.
     */
    public static File getPropertiesFileFromList(List<String> propertiesFilePaths, @NonNull String logTag) {
        if (propertiesFilePaths == null || propertiesFilePaths.size() == 0)
            return null;

        for(String propertiesFilePath : propertiesFilePaths) {
            File propertiesFile = new File(propertiesFilePath);

            // Symlinks **will not** be followed.
            FileType fileType = FileUtils.getFileType(propertiesFilePath, false);
            if (fileType == FileType.REGULAR) {
                if (propertiesFile.canRead())
                    return propertiesFile;
                else
                    Logger.logWarn(logTag, "Ignoring properties file at \"" + propertiesFilePath + "\" since it is not readable");
            } else if (fileType != FileType.NO_EXIST) {
                Logger.logWarn(logTag, "Ignoring properties file at \"" + propertiesFilePath + "\" of type: \"" + fileType.getName() + "\"");
            }
        }

        Logger.logDebug(logTag, "No readable properties file found at: " + propertiesFilePaths);
        return null;
    }

    /**
     * Get the boolean value for the {@link String} value.
     *
     * @param value The {@link String} value to convert.
     * @param def The default {@link boolean} value to return.
     * @param logErrorOnInvalidValue If {@code true}, then an error will be logged if {@code value}
     *                               was not {@code null} and was invalid.
     * @param logTag If log tag to use for logging errors.
     * @return Returns {@code true} or {@code false} if value is the literal string "true" or "false" respectively,
     * regardless of case. Otherwise returns default value.
     */
    public static boolean getBooleanValueForStringValue(String key, String value, boolean def, boolean logErrorOnInvalidValue, String logTag) {
        return (boolean) getDefaultIfNotInMap(key, MAP_GENERIC_BOOLEAN, toLowerCase(value), def, logErrorOnInvalidValue, logTag);
    }

    /**
     * Get the value for the {@code inputValue} {@link Object} key from a {@link BiMap<>}, otherwise
     * default value if key not found in {@code map}.
     *
     * @param key The shared properties {@link String} key value for which the value is being returned.
     * @param map The {@link BiMap<>} value to get the value from.
     * @param inputValue The {@link Object} key value of the map.
     * @param defaultOutputValue The default {@link boolean} value to return if {@code inputValue} not found in map.
     *            The default value must exist as a value in the {@link BiMap<>} passed.
     * @param logErrorOnInvalidValue If {@code true}, then an error will be logged if {@code inputValue}
     *                               was not {@code null} and was not found in the map.
     * @param logTag If log tag to use for logging errors.
     * @return Returns the value for the {@code inputValue} key from the map if it exists. Otherwise
     * returns default value.
     */
    public static Object getDefaultIfNotInMap(String key, @NonNull BiMap<?, ?> map, Object inputValue, Object defaultOutputValue, boolean logErrorOnInvalidValue, String logTag) {
        Object outputValue = map.get(inputValue);
        if (outputValue == null) {
            Object defaultInputValue = map.inverse().get(defaultOutputValue);
            if (defaultInputValue == null)
                Logger.logError(LOG_TAG, "The default output value \"" + defaultOutputValue + "\" for the key \"" + key + "\" does not exist as a value in the BiMap passed to getDefaultIfNotInMap(): " + map.values());

            if (logErrorOnInvalidValue && inputValue != null) {
                if (key != null)
                    Logger.logError(logTag, "The value \"" + inputValue + "\" for the key \"" + key + "\" is invalid. Using default value \"" + defaultInputValue + "\" instead.");
                else
                    Logger.logError(logTag, "The value \"" + inputValue + "\" is invalid. Using default value \"" + defaultInputValue + "\" instead.");
            }

            return defaultOutputValue;
        } else {
            return outputValue;
        }
    }

    /**
     * Covert the {@link String} value to lowercase.
     *
     * @param value The {@link String} value to convert.
     * @return Returns the lowercased value.
     */
    public static String toLowerCase(String value) {
        if (value == null) return null; else return value.toLowerCase();
    }

}
