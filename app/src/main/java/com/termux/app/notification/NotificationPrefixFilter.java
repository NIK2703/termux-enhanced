package com.termux.app.notification;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A blocklist rule that suppresses a terminal notification when the selected field starts with
 * the prefix. Title and body are separate modes so a rule for one field does not affect the other.
 */
public final class NotificationPrefixFilter {

    private static final String JSON_MODE = "mode";
    private static final String JSON_PREFIX = "prefix";
    private static final String MODE_TITLE_KEY = "title";
    private static final String MODE_BODY_KEY = "body";

    /** Which field of the notification the prefix is compared against. */
    public enum Mode {
        TITLE(MODE_TITLE_KEY),
        BODY(MODE_BODY_KEY);

        private final String mKey;

        Mode(String key) {
            mKey = key;
        }
    }

    private final Mode mMode;
    private final String mPrefix;

    public NotificationPrefixFilter(@NonNull Mode mode, @NonNull String prefix) {
        mMode = mode;
        mPrefix = prefix.trim();
    }

    @NonNull
    public Mode getMode() {
        return mMode;
    }

    @NonNull
    public String getPrefix() {
        return mPrefix;
    }

    /** Whether the field selected by the mode starts with the prefix. */
    public boolean matches(@Nullable CharSequence title, @Nullable CharSequence body) {
        if (mPrefix.isEmpty()) return false;
        CharSequence field = mMode == Mode.TITLE ? title : body;
        return field != null && field.toString().startsWith(mPrefix);
    }

    /** Whether any stored rule matches the given notification text. */
    public static boolean matchesAny(@Nullable String serialized,
                                     @Nullable CharSequence title, @Nullable CharSequence body) {
        for (NotificationPrefixFilter filter : parse(serialized)) {
            if (filter.matches(title, body)) return true;
        }
        return false;
    }

    /**
     * Read the stored JSON array. Unreadable data means no filters, so a corrupt preference
     * cannot block notifications.
     */
    @NonNull
    public static List<NotificationPrefixFilter> parse(@Nullable String serialized) {
        if (serialized == null || serialized.isEmpty() || "[]".equals(serialized))
            return Collections.emptyList();

        List<NotificationPrefixFilter> filters = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(serialized);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;

                Mode mode = modeFromKey(object.optString(JSON_MODE));
                String prefix = object.optString(JSON_PREFIX);
                if (mode == null || prefix.trim().isEmpty()) continue;
                filters.add(new NotificationPrefixFilter(mode, prefix));
            }
        } catch (JSONException e) {
            // See the method contract.
        }
        return filters;
    }

    @NonNull
    public static String serialize(@NonNull List<NotificationPrefixFilter> filters) {
        JSONArray array = new JSONArray();
        for (NotificationPrefixFilter filter : filters) {
            try {
                array.put(new JSONObject()
                    .put(JSON_MODE, filter.mMode.mKey)
                    .put(JSON_PREFIX, filter.mPrefix));
            } catch (JSONException e) {
                // put() only rejects non-finite numbers, which this object never holds.
            }
        }
        return array.toString();
    }

    @Nullable
    private static Mode modeFromKey(@Nullable String key) {
        if (MODE_TITLE_KEY.equals(key)) return Mode.TITLE;
        if (MODE_BODY_KEY.equals(key)) return Mode.BODY;
        return null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof NotificationPrefixFilter)) return false;
        NotificationPrefixFilter filter = (NotificationPrefixFilter) other;
        return mMode == filter.mMode && mPrefix.equals(filter.mPrefix);
    }

    @Override
    public int hashCode() {
        return 31 * mMode.hashCode() + mPrefix.hashCode();
    }
}
