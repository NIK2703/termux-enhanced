package com.termux.shared.termux.extrakeys;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ExtraKeyButton {

    /** The key name for the name of the extra key if using a dict to define the extra key. {key: name, ...} */
    public static final String KEY_KEY_NAME = "key";

    /** The key name for the macro value of the extra key if using a dict to define the extra key. {macro: value, ...} */
    public static final String KEY_MACRO = "macro";

    /** The key name for the alternate display name of the extra key if using a dict to define the extra key. {display: name, ...} */
    public static final String KEY_DISPLAY_NAME = "display";

    /** The key name for the nested dict to define popup extra key info if using a dict to define the extra key. {popup: {key: name, ...}, ...} */
    public static final String KEY_POPUP = "popup";

    /** The key name for the nested dict to define swipe up extra key info. */
    public static final String KEY_SWIPE_UP = "swipeUp";

    /** The key name for the nested dict to define swipe down extra key info. */
    public static final String KEY_SWIPE_DOWN = "swipeDown";

    /** The key name for the nested dict to define swipe left extra key info. */
    public static final String KEY_SWIPE_LEFT = "swipeLeft";

    /** The key name for the nested dict to define swipe right extra key info. */
    public static final String KEY_SWIPE_RIGHT = "swipeRight";


    /**
     * The key that will be sent to the terminal, either a control character, like defined in
     * {@link ExtraKeysConstants#PRIMARY_KEY_CODES_FOR_STRINGS} (LEFT, RIGHT, PGUP...) or some text.
     */
    private final String key;

    /**
     * If the key is a macro, i.e. a sequence of keys separated by space.
     */
    private final boolean macro;

    /**
     * The text that will be displayed on the button.
     */
    private final String display;

    /**
     * The {@link ExtraKeyButton} containing the information of the popup button (triggered by swipe up).
     */
    @Nullable
    private final ExtraKeyButton popup;

    /**
     * The {@link ExtraKeyButton} containing the information of the swipe up button.
     */
    @Nullable
    private final ExtraKeyButton swipeUp;

    /**
     * The {@link ExtraKeyButton} containing the information of the swipe down button.
     */
    @Nullable
    private final ExtraKeyButton swipeDown;

    /**
     * The {@link ExtraKeyButton} containing the information of the swipe left button.
     */
    @Nullable
    private final ExtraKeyButton swipeLeft;

    /**
     * The {@link ExtraKeyButton} containing the information of the swipe right button.
     */
    @Nullable
    private final ExtraKeyButton swipeRight;

    private List<String> mParsedTokens;
    private boolean mHasDelay;

    /**
     * Initialize a {@link ExtraKeyButton}.
     *
     * @param config The {@link JSONObject} containing the info to create the {@link ExtraKeyButton}.
     * @param extraKeyDisplayMap The {@link ExtraKeysConstants.ExtraKeyDisplayMap} that defines the
     *                           display text mapping for the keys if a custom value is not defined
     *                           by {@link #KEY_DISPLAY_NAME}.
     * @param extraKeyAliasMap The {@link ExtraKeysConstants.ExtraKeyDisplayMap} that defines the
     *                           aliases for the actual key names.
     */
    public ExtraKeyButton(@NonNull JSONObject config,
                          @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap,
                          @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyAliasMap) throws JSONException {
        this(config, null, null, null, null, extraKeyDisplayMap, extraKeyAliasMap);
    }

    /**
     * Initialize a {@link ExtraKeyButton}.
     *
     * @param config The {@link JSONObject} containing the info to create the {@link ExtraKeyButton}.
     * @param popup The {@link ExtraKeyButton} optional {@link #popup} button.
     * @param extraKeyDisplayMap The {@link ExtraKeysConstants.ExtraKeyDisplayMap} that defines the
     *                           display text mapping for the keys if a custom value is not defined
     *                           by {@link #KEY_DISPLAY_NAME}.
     * @param extraKeyAliasMap The {@link ExtraKeysConstants.ExtraKeyDisplayMap} that defines the
     *                           aliases for the actual key names.
     */
    public ExtraKeyButton(@NonNull JSONObject config, @Nullable ExtraKeyButton popup,
                          @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap,
                          @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyAliasMap) throws JSONException {
        this(config, popup, null, null, null, extraKeyDisplayMap, extraKeyAliasMap);
    }

    /**
     * Initialize a {@link ExtraKeyButton}.
     *
     * @param config The {@link JSONObject} containing the info to create the {@link ExtraKeyButton}.
     * @param swipeUp The {@link ExtraKeyButton} optional swipe up button.
     * @param swipeDown The {@link ExtraKeyButton} optional swipe down button.
     * @param swipeLeft The {@link ExtraKeyButton} optional swipe left button.
     * @param swipeRight The {@link ExtraKeyButton} optional swipe right button.
     * @param extraKeyDisplayMap The {@link ExtraKeysConstants.ExtraKeyDisplayMap} that defines the
     *                           display text mapping for the keys if a custom value is not defined
     *                           by {@link #KEY_DISPLAY_NAME}.
     * @param extraKeyAliasMap The {@link ExtraKeysConstants.ExtraKeyDisplayMap} that defines the
     *                           aliases for the actual key names.
     */
    public ExtraKeyButton(@NonNull JSONObject config,
                          @Nullable ExtraKeyButton swipeUp,
                          @Nullable ExtraKeyButton swipeDown,
                          @Nullable ExtraKeyButton swipeLeft,
                          @Nullable ExtraKeyButton swipeRight,
                          @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap,
                          @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyAliasMap) throws JSONException {
        String keyFromConfig = getStringFromJson(config, KEY_KEY_NAME);
        String macroFromConfig = getStringFromJson(config, KEY_MACRO);
        List<String> keys;
        if (keyFromConfig != null && macroFromConfig != null) {
            throw new JSONException("Both key and macro can't be set for the same key. key: \"" + keyFromConfig + "\", macro: \"" + macroFromConfig + "\"");
        } else if (keyFromConfig != null) {
            keys = new ArrayList<>();
            keys.add(keyFromConfig);
            this.macro = false;
        } else if (macroFromConfig != null) {
            // Quote-aware: a literal token containing whitespace (e.g. "ls -la" ENTER) stays one token.
            keys = BindingTokenizer.tokenizeMacro(macroFromConfig);
            if (keys.isEmpty()) keys.add("");
            this.macro = true;
        } else if (swipeUp != null || swipeDown != null || swipeLeft != null || swipeRight != null) {
            keys = new ArrayList<>();
            keys.add("");
            this.macro = false;
        } else {
            throw new JSONException("All keys have to specify either key or macro");
        }

        for (int i = 0; i < keys.size(); i++) {
            keys.set(i, replaceAlias(extraKeyAliasMap, keys.get(i)));
        }

        this.key = TextUtils.join(" ", keys);
        // Normalize the already-parsed tokens instead of re-splitting the joined key: a macro token
        // may legitimately contain spaces (a quoted literal), and splitting again would undo it.
        mParsedTokens = new ArrayList<>(keys.size());
        for (String token : keys) {
            mParsedTokens.add(BindingTokenizer.normalizeToken(token));
        }
        mHasDelay = BindingTokenizer.containsDelay(mParsedTokens);

        String displayFromConfig = getStringFromJson(config, KEY_DISPLAY_NAME);
        if (displayFromConfig != null) {
            this.display = displayFromConfig;
        } else {
            this.display = keys.stream()
                .map(key -> extraKeyDisplayMap.get(key, key))
                .collect(Collectors.joining(" "));
        }

        this.popup = swipeUp;
        this.swipeUp = swipeUp;
        this.swipeDown = swipeDown;
        this.swipeLeft = swipeLeft;
        this.swipeRight = swipeRight;
    }

    public String getStringFromJson(@NonNull JSONObject config, @NonNull String key) {
        try {
            return config.getString(key);
        } catch (JSONException e) {
            return null;
        }
    }

    /** Get {@link #key}. */
    public String getKey() {
        return key;
    }

    /** Check whether a {@link #macro} is defined or not. */
    public boolean isMacro() {
        return macro;
    }

    /** Get {@link #display}. */
    public String getDisplay() {
        return display;
    }

    /** Get {@link #popup}. */
    @Deprecated
    @Nullable
    public ExtraKeyButton getPopup() {
        return getSwipeUp();
    }

    /** Get {@link #swipeUp}. */
    @Nullable
    public ExtraKeyButton getSwipeUp() {
        return swipeUp;
    }

    /** Get {@link #swipeDown}. */
    @Nullable
    public ExtraKeyButton getSwipeDown() {
        return swipeDown;
    }

    /** Get {@link #swipeLeft}. */
    @Nullable
    public ExtraKeyButton getSwipeLeft() {
        return swipeLeft;
    }

    /** Get {@link #swipeRight}. */
    @Nullable
    public ExtraKeyButton getSwipeRight() {
        return swipeRight;
    }

    public List<String> getParsedTokens() {
        return mParsedTokens;
    }

    public boolean hasDelay() {
        return mHasDelay;
    }

    /**
     * Replace the alias with its actual key name if found in extraKeyAliasMap.
     */
    public static String replaceAlias(@NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyAliasMap, String key) {
        return extraKeyAliasMap.get(key, key);
    }

}
