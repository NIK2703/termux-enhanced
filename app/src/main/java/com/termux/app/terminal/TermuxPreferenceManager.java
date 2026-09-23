package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralised, single entry point for all {@code termux_prefs} {@link SharedPreferences} access
 * previously spread across {@code TermuxActivity}.
 * <p>
 * Instantiate with a {@link Context} and read/write the message-history, per-directory message
 * history and directory-history preferences through the typed getters/setters below. This keeps the
 * raw preference keys, JSON (de)serialisation and defaults in one place.
 */
public final class TermuxPreferenceManager {

    private static final String LOG_TAG = "TermuxPreferenceManager";

    private static final String PREFS_NAME = "termux_prefs";

    private static final String PREF_MESSAGE_HISTORY_MAX = "message_history_max";
    private static final String PREF_PER_DIRECTORY_MESSAGE_HISTORY = "per_directory_message_history";
    private static final String PREF_DIRECTORY_HISTORY_MAX = "directory_history_max";
    private static final String PREF_MESSAGE_HISTORY = "message_history";
    private static final String PREF_DIRECTORY_HISTORY = "directory_history";

    private static final int MESSAGE_HISTORY_MAX_DEFAULT = 20;
    private static final int DIRECTORY_HISTORY_MAX_DEFAULT = 20;

    private final SharedPreferences mPrefs;

    public TermuxPreferenceManager(@NonNull Context context) {
        mPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ── Message history size ──

    public int getMaxMessageHistorySize() {
        return mPrefs.getInt(PREF_MESSAGE_HISTORY_MAX, MESSAGE_HISTORY_MAX_DEFAULT);
    }

    public void setMaxMessageHistorySize(int max) {
        mPrefs.edit().putInt(PREF_MESSAGE_HISTORY_MAX, max).apply();
    }

    // ── Per-directory message history toggle ──

    public boolean isPerDirectoryMessageHistory() {
        return mPrefs.getBoolean(PREF_PER_DIRECTORY_MESSAGE_HISTORY, false);
    }

    public void setPerDirectoryMessageHistory(boolean enabled) {
        mPrefs.edit().putBoolean(PREF_PER_DIRECTORY_MESSAGE_HISTORY, enabled).apply();
    }

    // ── Directory history size ──

    public int getMaxDirectoryHistorySize() {
        return mPrefs.getInt(PREF_DIRECTORY_HISTORY_MAX, DIRECTORY_HISTORY_MAX_DEFAULT);
    }

    public void setMaxDirectoryHistorySize(int max) {
        mPrefs.edit().putInt(PREF_DIRECTORY_HISTORY_MAX, max).apply();
    }

    // ── Change listener registration ──

    public void registerChangeListener(@NonNull SharedPreferences.OnSharedPreferenceChangeListener listener) {
        mPrefs.registerOnSharedPreferenceChangeListener(listener);
    }

    public void unregisterChangeListener(@NonNull SharedPreferences.OnSharedPreferenceChangeListener listener) {
        mPrefs.unregisterOnSharedPreferenceChangeListener(listener);
    }

    // ── Message history list (global JSON array) ──

    @NonNull
    public List<String> getMessageHistoryList() {
        return readStringList(PREF_MESSAGE_HISTORY, "Failed to parse message history list");
    }

    public void setMessageHistoryList(@Nullable List<String> list) {
        writeStringList(PREF_MESSAGE_HISTORY, list);
    }

    // ── Directory history list (JSON array) ──

    @NonNull
    public List<String> getDirectoryHistoryList() {
        return readStringList(PREF_DIRECTORY_HISTORY, "Failed to parse directory history list");
    }

    public void setDirectoryHistoryList(@Nullable List<String> list) {
        writeStringList(PREF_DIRECTORY_HISTORY, list);
    }

    @NonNull
    private List<String> readStringList(@NonNull String prefKey, @NonNull String logMessage) {
        List<String> list = new ArrayList<>();
        String json = mPrefs.getString(prefKey, null);
        if (json == null) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, null);
                if (s != null && !s.isEmpty() && !list.contains(s)) {
                    list.add(s);
                }
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, logMessage, e);
        }
        return list;
    }

    private void writeStringList(@NonNull String prefKey, @Nullable List<String> list) {
        JSONArray arr = new JSONArray();
        if (list != null) {
            for (String s : list) {
                if (s != null && !s.isEmpty()) arr.put(s);
            }
        }
        mPrefs.edit().putString(prefKey, arr.toString()).apply();
    }
}
