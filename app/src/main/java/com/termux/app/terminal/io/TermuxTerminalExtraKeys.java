package com.termux.app.terminal.io;

import android.util.LruCache;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxActivity;

import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;
import com.termux.view.TerminalView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class TermuxTerminalExtraKeys extends TerminalExtraKeys {

    private ExtraKeysInfo mExtraKeysInfo;

    final TermuxActivity mActivity;
    final TermuxTerminalViewClient mTermuxTerminalViewClient;
    final TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    private static final String LOG_TAG = "TermuxTerminalExtraKeys";

    /** Session-name prefix → extra-keys JSON string, from the "extra-keys-session" property. */
    private Map<String, String> mSessionLayouts = new HashMap<>();
    /** The session profile currently logically active (null = default layout). */
    @Nullable private String mCurrentSessionContext;
    /** Last known active session name (used to re-assert the profile after a process context ends). */
    @Nullable private String mLastSessionName;

    /** Raw JSON of the layout currently applied to the view (null until first apply). */
    @Nullable private String mCurrentJson;
    /** Style string of the layout currently applied to the view. */
    @Nullable private String mCurrentStyle;
    /** Memoizes parsed session layouts keyed by "json|style" to avoid re-parsing on tab switches. */
    private final LruCache<String, ExtraKeysInfo> mLayoutCache = new LruCache<>(8);

    public TermuxTerminalExtraKeys(TermuxActivity activity, @NonNull TerminalView terminalView,
                                    TermuxTerminalViewClient termuxTerminalViewClient,
                                    TermuxTerminalSessionActivityClient termuxTerminalSessionActivityClient) {
        super(terminalView);

        mActivity = activity;
        mTermuxTerminalViewClient = termuxTerminalViewClient;
        mTermuxTerminalSessionActivityClient = termuxTerminalSessionActivityClient;

        setExtraKeys();
        parseSessionMap();
    }

    /**
     * Re-read the {@code extra-keys-session} property into {@link #mSessionLayouts} without
     * touching the currently displayed layout or {@link #mCurrentSessionContext}.
     *
     * Recovery path for profiles saved while this activity was stopped and the
     * ACTION_RELOAD_STYLE broadcast was not delivered. Re-parsing right before session-name
     * resolution guarantees the freshest profile set on the next tab switch.
     */
    public void reloadSessionMap() {
        parseSessionMap();
    }

    /**
     * Re-read the {@code extra-keys} and {@code extra-keys-style} properties from disk and rebuild
     * the {@link ExtraKeysInfo}. Call this after those properties change at runtime so the panel
     * updates without restarting the app.
     *
     * <p>Unlike the original implementation, the logically active session profile (or the profile
     * matching the last known session name) is re-asserted AFTER the reload instead of being
     * permanently dropped — otherwise any styling reload (theme change, returning from settings,
     * {@code reload_style} broadcast) would silently reset the panel to the default layout until
     * the next manual tab switch.</p>
     */
    /**
     * @return {@code true} if a session-specific layout was (re)applied to the view by this call,
     *         {@code false} if the default layout should be reloaded by the caller.
     */
    public boolean reloadExtraKeys() {
        setExtraKeys();
        parseSessionMap();
        String target = (mCurrentSessionContext != null
            && mSessionLayouts.containsKey(mCurrentSessionContext))
            ? mCurrentSessionContext : resolveSessionForPrefix(mLastSessionName);
        if (target != null) {
            applySessionLayout(target, mLastSessionName);
            return true;
        } else {
            mCurrentSessionContext = null;
            return false;
        }
    }

    /**
     * Route extra-key input to the live active pager page rather than the cached
     * {@code mTerminalView}. The cached pointer is normally kept in sync by
     * {@link TermuxActivity#onTerminalPageSelected(int)}, but it can stay {@code null} in a window
     * where {@code onPageSelected} is not re-fired (e.g. the very first session being added to an
     * otherwise-empty pager: {@code setCurrentItem(0, false)} is a no-op and does not emit a page
     * change). Without this fallback every extra-keys press would hit the {@code null} guard and be
     * silently dropped — the "buttons don't send" symptom. Falling back to the activity's active
     * page resolves the correct, bound view instead.
     */
    @Override
    @Nullable
    protected TerminalView getTerminalViewForInput() {
        TerminalView active = mActivity.getActiveTerminalView();
        return active != null ? active : super.getTerminalViewForInput();
    }


    /**
     * Set the terminal extra keys and style.
     */
    private void setExtraKeys() {
        mExtraKeysInfo = null;
        String extrakeys = (String) mActivity.getProperties().getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS, true);
        String extraKeysStyle = (String) mActivity.getProperties().getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE, true);

        try {
            ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap = ExtraKeysInfo.getCharDisplayMapForStyle(extraKeysStyle);
            if (ExtraKeysConstants.EXTRA_KEY_DISPLAY_MAPS.DEFAULT_CHAR_DISPLAY.equals(extraKeyDisplayMap) && !TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE.equals(extraKeysStyle)) {
                Logger.logError(LOG_TAG, "The style \"" + extraKeysStyle + "\" for the key \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS + "\" is invalid. Using default style instead.");
                extraKeysStyle = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE;
            }

            mExtraKeysInfo = new ExtraKeysInfo(extrakeys, extraKeysStyle, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        } catch (JSONException e) {
            Logger.showToast(mActivity, mActivity.getString(com.termux.R.string.msg_extra_keys_load_failed, TermuxPropertyConstants.KEY_EXTRA_KEYS, e.toString()), true);
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not load and set the \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS + "\" property from the properties file: ", e);

            try {
                mExtraKeysInfo = new ExtraKeysInfo(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
                extrakeys = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS;
                extraKeysStyle = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE;
            } catch (JSONException e2) {
                Logger.showToast(mActivity, mActivity.getString(com.termux.R.string.msg_extra_keys_create_failed), true);
                Logger.logStackTraceWithMessage(LOG_TAG, "Could create default extra keys: ", e);
                mExtraKeysInfo = null;
            }
        }

        // Track the raw JSON + style of the layout we just built so applySessionLayout can skip
        // re-parsing when the same layout is re-applied (C3).
        mCurrentJson = (mExtraKeysInfo != null) ? extrakeys : null;
        mCurrentStyle = extraKeysStyle;
    }

    public ExtraKeysInfo getExtraKeysInfo() {
        return mExtraKeysInfo;
    }

    @Override
    public void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
        if ("KEYBOARD".equals(key)) {
            if(mTermuxTerminalViewClient != null)
                mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
        } else if ("PASTE".equals(key)) {
            if(mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onPasteTextFromClipboard(null);
        }  else if ("SCROLL".equals(key)) {
            TerminalView terminalView = mTermuxTerminalViewClient.getActivity().getActiveTerminalView();
            if (terminalView != null && terminalView.mEmulator != null)
                terminalView.mEmulator.toggleAutoScrollDisabled();
        } else {
            super.onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown);
        }
    }

    /**
     * Parse the {@code extra-keys-session} property into a session-name-prefix → JSON layout map.
     *
     * Two formats are supported:
     * <ul>
     *   <li>New: {@code { "ProfileName": { "prefixes": ["dev:", "ssh"], "layout": "[[...]]" } }}</li>
     *   <li>Legacy: {@code { "prefix": "[[...]]" }}</li>
     * </ul>
     * Both flatten to the same prefix → layout map used by {@link #resolveSessionForPrefix}.
     */
    private void parseSessionMap() {
        mSessionLayouts.clear();
        try {
            Object raw = mActivity.getProperties().getInternalPropertyValue(
                    TermuxPropertyConstants.KEY_EXTRA_KEYS_SESSION, true);
            if (raw == null) return;
            String rawStr = raw.toString();
            if (rawStr.isEmpty()) return;

            JSONObject json = new JSONObject(rawStr);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = json.opt(key);

                if (value instanceof JSONObject) {
                    // New format: key = profile name,
                    // value = { "prefixes": [...], "layout": "..." }
                    JSONObject profile = (JSONObject) value;
                    String layout = profile.optString("layout", null);
                    JSONArray prefixes = profile.optJSONArray("prefixes");
                    if (layout == null || layout.isEmpty() || prefixes == null) continue;
                    for (int i = 0; i < prefixes.length(); i++) {
                        String prefix = prefixes.optString(i, "");
                        if (!prefix.isEmpty()) {
                            mSessionLayouts.put(prefix.toLowerCase(), layout);
                        }
                    }
                } else if (value instanceof String) {
                    // Legacy format: key = prefix, value = layout
                    String layout = (String) value;
                    if (!key.isEmpty() && !layout.isEmpty()) {
                        mSessionLayouts.put(key.toLowerCase(), layout);
                    }
                }
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG,
                    "Failed to parse extra-keys-session property: ", e);
            mSessionLayouts.clear();
        }
    }


    /**
     * Session-based trigger: called when the active session is switched or renamed.
     * Resolves the session name against the configured prefixes and applies the matching
     * profile, or reverts to the default layout when nothing matches.
     *
     * @param sessionName the (possibly null) name of the newly active session
     */
    public void onSessionNameChanged(@Nullable String sessionName) {
        mLastSessionName = sessionName;
        if (!isSessionSwitchingEnabled()) return;

        String sessionContext = resolveSessionForPrefix(sessionName);


        if (sessionContext == null) {
            // No prefix match — revert to the default layout if a session profile was shown.
            if (mCurrentSessionContext != null) {
                reloadDefaultLayout();
                mCurrentSessionContext = null;
            }
            return;
        }

        if (!sessionContext.equals(mCurrentSessionContext)) {
            applySessionLayout(sessionContext, sessionName);
        }
    }

    /**
     * Find the session profile whose key is a prefix of the given session name.
     * Longest matching prefix wins. Matching is case-insensitive.
     *
     * @return the profile key, or {@code null} if no prefix matches
     */
    @Nullable
    private String resolveSessionForPrefix(@Nullable String sessionName) {
        if (sessionName == null || sessionName.isEmpty() || mSessionLayouts.isEmpty()) return null;
        String lower = sessionName.toLowerCase();
        String bestMatch = null;
        for (String prefix : mSessionLayouts.keySet()) {
            if (lower.startsWith(prefix)) {
                if (bestMatch == null || prefix.length() > bestMatch.length()) {
                    bestMatch = prefix;
                }
            }
        }
        return bestMatch;
    }



    /**
     * Load and apply the ExtraKeys layout for the given session profile name.
     * Avoids a redundant reload via {@link ExtraKeysInfo#isSameLayout(ExtraKeysInfo, ExtraKeysInfo)}.
     */
    private void applySessionLayout(@NonNull String profileName, @Nullable String sessionName) {
        String sessionLayoutJson = mSessionLayouts.get(profileName);
        if (sessionLayoutJson == null) return;

        String extraKeysStyle = (String) mActivity.getProperties().getInternalPropertyValue(
                TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE, true);

        // Skip entirely if the exact same layout (raw JSON + style) is already applied — this
        // avoids re-parsing the JSON on every tab switch (C3).
        if (sessionLayoutJson.equals(mCurrentJson) && extraKeysStyle.equals(mCurrentStyle)
                && mExtraKeysInfo != null) {
            mCurrentSessionContext = profileName;
            return;
        }

        String cacheKey = sessionLayoutJson + "|" + extraKeysStyle;
        ExtraKeysInfo newInfo = mLayoutCache.get(cacheKey);
        if (newInfo == null) {
            try {
                newInfo = new ExtraKeysInfo(sessionLayoutJson, extraKeysStyle,
                        ExtraKeysConstants.CONTROL_CHARS_ALIASES);
            } catch (JSONException e) {
                Logger.logStackTraceWithMessage(LOG_TAG,
                        "Failed to create ExtraKeysInfo for session profile \"" + profileName + "\": ", e);
                mCurrentSessionContext = null;
                return;
            }
            mLayoutCache.put(cacheKey, newInfo);
        }

        mExtraKeysInfo = newInfo;
        mCurrentJson = sessionLayoutJson;
        mCurrentStyle = extraKeysStyle;
        mCurrentSessionContext = profileName;
        mActivity.setTerminalToolbarHeight();
        if (mActivity.getExtraKeysView() != null) {
            mActivity.getExtraKeysView().reload(newInfo,
                    mActivity.getTerminalToolbarDefaultHeight());
        }
    }

    /**
     * Core: reload the default "extra-keys" property layout into the view (no state guard).
     */
    private void reloadDefaultLayout() {
        ExtraKeysInfo previousInfo = mExtraKeysInfo;
        setExtraKeys(); // Re-reads the "extra-keys" property from disk
        if (mExtraKeysInfo == null || !ExtraKeysInfo.isSameLayout(previousInfo, mExtraKeysInfo)) {
            mActivity.setTerminalToolbarHeight();
            if (mActivity.getExtraKeysView() != null && mExtraKeysInfo != null) {
                mActivity.getExtraKeysView().reload(mExtraKeysInfo,
                        mActivity.getTerminalToolbarDefaultHeight());
            }
        }
    }


    /** @return {@code true} if session-based switching is configured and non-empty. */
    public boolean isSessionSwitchingEnabled() {
        return !mSessionLayouts.isEmpty();
    }
    /** @return unmodifiable view of the prefix→layout map (for testing/debugging). */
    @NonNull
    public Map<String, String> getSessionLayouts() {
        return mSessionLayouts;
    }

    /** @return the currently active session profile name, or {@code null} for default. */
    @Nullable
    public String getCurrentSessionContext() {
        return mCurrentSessionContext;
    }
}
