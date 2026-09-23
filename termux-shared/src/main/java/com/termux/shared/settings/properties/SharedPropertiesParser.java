package com.termux.shared.settings.properties;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Properties;

/** Implemented by callers of {@link SharedProperties}. */
public interface SharedPropertiesParser {

    /**
     * Pre-process properties read from disk before they are stored in the {@link HashMap} cache.
     *
     * @param context context
     * @param properties properties loaded from the file
     */
    @NonNull
    Properties preProcessPropertiesOnReadFromDisk(@NonNull Context context, @NonNull Properties properties);

    /**
     * Map a literal property value to the internal object cached for that key.
     *
     * @param context context
     * @param key property key
     * @param value literal value from the properties file
     * @return the object to store in the {@link HashMap} cache
     */
    Object getInternalPropertyValueFromValue(@NonNull Context context, String key, String value);

}
