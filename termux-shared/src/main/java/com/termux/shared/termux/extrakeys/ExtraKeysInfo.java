package com.termux.shared.termux.extrakeys;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants.EXTRA_KEY_DISPLAY_MAPS;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A {@link Class} that defines the info needed by {@link ExtraKeysView} to display the extra key
 * views.
 *
 * The {@code propertiesInfo} passed to the constructors of this class must be json array of arrays.
 * Each array element of the json array will be considered a separate row of keys.
 * Each key can either be simple string that defines the name of the key or a json dict that defines
 * advance info for the key. The syntax can be `'KEY'` or `{key: 'KEY'}`.
 * For example `HOME` or `{key: 'HOME', ...}.
 *
 * In advance json dict mode, the key can also be a sequence of space separated keys instead of one
 * key. This can be done by replacing `key` key/value pair of the dict with a `macro` key/value pair.
 * The syntax is `{macro: 'KEY COMBINATION'}`. For example {macro: 'HOME RIGHT', ...}.
 *
 * In advance json dict mode, you can define a nested json dict with the `popup` key which will be
 * used as the popup key and will be triggered on swipe up. The syntax can be
 * `{key: 'KEY', popup: 'POPUP_KEY'}` or `{key: 'KEY', popup: {macro: 'KEY COMBINATION', display: 'Key combo'}}`.
 * For example `{key: 'HOME', popup: {KEY: 'END', ...}, ...}`.
 *
 * In advance json dict mode, the key can also have a custom display name that can be used as the
 * text to display on the button by defining the `display` key. The syntax is `{display: 'DISPLAY'}`.
 * For example {display: 'Custom name', ...}.
 *
 * Examples:
 * {@code
 * # Empty:
 * []
 *
 * # Single row:
 * [[ESC, TAB, CTRL, ALT, {key: '-', popup: '|'}, DOWN, UP]]
 *
 * # 2 row:
 * [['ESC','/',{key: '-', popup: '|'},'HOME','UP','END','PGUP'],
 * ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]
 *
 * # Advance:
 * [[
 *   {key: ESC, popup: {macro: "CTRL f d", display: "tmux exit"}},
 *   {key: CTRL, popup: {macro: "CTRL f BKSP", display: "tmux ←"}},
 *   {key: ALT, popup: {macro: "CTRL f TAB", display: "tmux →"}},
 *   {key: TAB, popup: {macro: "ALT a", display: A-a}},
 *   {key: LEFT, popup: HOME},
 *   {key: DOWN, popup: PGDN},
 *   {key: UP, popup: PGUP},
 *   {key: RIGHT, popup: END},
 *   {macro: "ALT j", display: A-j, popup: {macro: "ALT g", display: A-g}},
 *   {key: KEYBOARD, popup: {macro: "CTRL d", display: exit}}
 * ]]
 *
 * }
 *
 * Aliases are also allowed for the keys that you can pass as {@code extraKeyAliasMap}. Check
 * {@link ExtraKeysConstants#CONTROL_CHARS_ALIASES}.
 *
 * Its up to the {@link ExtraKeysView.IExtraKeysView} client on how to handle individual key values
 * of an {@link ExtraKeyButton}. They are sent as is via
 * {@link ExtraKeysView.IExtraKeysView#onExtraKeyButtonClick(View, ExtraKeyButton, MaterialButton)}. The
 * {@link TerminalExtraKeys} which is an implementation of the interface,
 * checks if the key is one of {@link ExtraKeysConstants#PRIMARY_KEY_CODES_FOR_STRINGS} and generates
 * a {@link android.view.KeyEvent} for it, and if its not, then converts the key to code points by
 * calling {@link CharSequence#codePoints()} and passes them to the terminal as literal strings.
 *
 * Examples:
 * {@code
 * "ENTER" will trigger the ENTER keycode
 * "LEFT" will trigger the LEFT keycode and be displayed as "←"
 * "→" will input a "→" character
 * "−" will input a "−" character
 * "-_-" will input the string "-_-"
 * }
 *
 * For more info, check https://wiki.termux.com/wiki/Touch_Keyboard.
 */
public class ExtraKeysInfo {

    /**
     * Matrix of buttons to be displayed in {@link ExtraKeysView}.
     */
    private final ExtraKeyButton[][] mButtons;

    /**
     * Initialize {@link ExtraKeysInfo}.
     *
     * @param propertiesInfo The {@link String} containing the info to create the {@link ExtraKeysInfo}.
     *                       Check the class javadoc for details.
     * @param style The style to pass to {@link #getCharDisplayMapForStyle(String)} to get the
     *              {@link ExtraKeysConstants.ExtraKeyDisplayMap} that defines the display text
     *              mapping for the keys if a custom value is not defined by
     *              {@link ExtraKeyButton#KEY_DISPLAY_NAME} for a key.
     * @param extraKeyAliasMap The {@link ExtraKeysConstants.ExtraKeyDisplayMap} that defines the
     *                           aliases for the actual key names. You can create your own or
     *                           optionally pass {@link ExtraKeysConstants#CONTROL_CHARS_ALIASES}.
     */
    public ExtraKeysInfo(@NonNull String propertiesInfo, String style,
                         @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyAliasMap) throws JSONException {
        mButtons = initExtraKeysInfo(propertiesInfo, getCharDisplayMapForStyle(style), extraKeyAliasMap);
    }

    /**
     * Initialize {@link ExtraKeysInfo}.
     *
     * @param propertiesInfo The {@link String} containing the info to create the {@link ExtraKeysInfo}.
     *                       Check the class javadoc for details.
     * @param extraKeyDisplayMap The {@link ExtraKeysConstants.ExtraKeyDisplayMap} that defines the
     *                           display text mapping for the keys if a custom value is not defined
     *                           by {@link ExtraKeyButton#KEY_DISPLAY_NAME} for a key. You can create
     *                           your own or optionally pass one of the values defined in
     *                           {@link #getCharDisplayMapForStyle(String)}.
     * @param extraKeyAliasMap The {@link ExtraKeysConstants.ExtraKeyDisplayMap} that defines the
     *                           aliases for the actual key names. You can create your own or
     *                           optionally pass {@link ExtraKeysConstants#CONTROL_CHARS_ALIASES}.
     */
    public ExtraKeysInfo(@NonNull String propertiesInfo,
                         @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap,
                         @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyAliasMap) throws JSONException {
        mButtons = initExtraKeysInfo(propertiesInfo, extraKeyDisplayMap, extraKeyAliasMap);
    }

    private ExtraKeyButton[][] initExtraKeysInfo(@NonNull String propertiesInfo,
                                                 @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap,
                                                 @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyAliasMap) throws JSONException {
        // Convert String propertiesInfo to a matrix of buttons directly — no intermediate Object[][] allocation.
        JSONArray arr = new JSONArray(propertiesInfo);
        ExtraKeyButton[][] buttons = new ExtraKeyButton[arr.length()][];
        for (int i = 0; i < arr.length(); i++) {
            JSONArray line = arr.getJSONArray(i);
            buttons[i] = new ExtraKeyButton[line.length()];
            for (int j = 0; j < line.length(); j++) {
                Object key = line.get(j);

                JSONObject jobject = normalizeKeyConfig(key);

                ExtraKeyButton swipeUp = parseSwipe(jobject, ExtraKeyButton.KEY_SWIPE_UP, extraKeyDisplayMap, extraKeyAliasMap);
                if (swipeUp == null) {
                    swipeUp = parseSwipe(jobject, ExtraKeyButton.KEY_POPUP, extraKeyDisplayMap, extraKeyAliasMap);
                }
                ExtraKeyButton swipeDown = parseSwipe(jobject, ExtraKeyButton.KEY_SWIPE_DOWN, extraKeyDisplayMap, extraKeyAliasMap);
                ExtraKeyButton swipeLeft = parseSwipe(jobject, ExtraKeyButton.KEY_SWIPE_LEFT, extraKeyDisplayMap, extraKeyAliasMap);
                ExtraKeyButton swipeRight = parseSwipe(jobject, ExtraKeyButton.KEY_SWIPE_RIGHT, extraKeyDisplayMap, extraKeyAliasMap);

                ExtraKeyButton button = new ExtraKeyButton(jobject, swipeUp, swipeDown, swipeLeft, swipeRight, extraKeyDisplayMap, extraKeyAliasMap);

                buttons[i][j] = button;
            }
        }

        return buttons;
    }

    /**
     * Convert "value" -> {"key": "value"}. Required by
     * {@link ExtraKeyButton#ExtraKeyButton(JSONObject, ExtraKeyButton, ExtraKeysConstants.ExtraKeyDisplayMap, ExtraKeysConstants.ExtraKeyDisplayMap)}.
     */
    private static JSONObject normalizeKeyConfig(Object key) throws JSONException {
        JSONObject jobject;
        if (key instanceof String) {
            jobject = new JSONObject();
            jobject.put(ExtraKeyButton.KEY_KEY_NAME, key);
        } else if (key instanceof JSONObject) {
            jobject = (JSONObject) key;
        } else {
            throw new JSONException("An key in the extra-key matrix must be a string or an object");
        }
        return jobject;
    }

    /**
     * Parse a swipe button from the parent JSON object if the given key exists.
     *
     * @param parent The parent {@link JSONObject} containing the button config.
     * @param key The key name to look up (e.g. "swipeUp", "swipeDown").
     * @param displayMap The display text mapping.
     * @param aliasMap The alias mapping.
     * @return The {@link ExtraKeyButton} or {@code null} if the key is not present or empty.
     */
    @Nullable
    private static ExtraKeyButton parseSwipe(@NonNull JSONObject parent, @NonNull String key,
                                              @NonNull ExtraKeysConstants.ExtraKeyDisplayMap displayMap,
                                              @NonNull ExtraKeysConstants.ExtraKeyDisplayMap aliasMap) throws JSONException {
        if (!parent.has(key)) return null;
        Object value = parent.get(key);
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof String && ((String) value).isEmpty()) return null;
        JSONObject config = normalizeKeyConfig(value);
        return new ExtraKeyButton(config, displayMap, aliasMap);
    }

    public ExtraKeyButton[][] getMatrix() {
        return mButtons;
    }

    @NonNull
    public static ExtraKeysConstants.ExtraKeyDisplayMap getCharDisplayMapForStyle(String style) {
        switch (style) {
            case "arrows-only":
                return EXTRA_KEY_DISPLAY_MAPS.ARROWS_ONLY_CHAR_DISPLAY;
            case "arrows-all":
                return EXTRA_KEY_DISPLAY_MAPS.LOTS_OF_ARROWS_CHAR_DISPLAY;
            case "all":
                return EXTRA_KEY_DISPLAY_MAPS.FULL_ISO_CHAR_DISPLAY;
            case "none":
                return new ExtraKeysConstants.ExtraKeyDisplayMap();
            default:
                return EXTRA_KEY_DISPLAY_MAPS.DEFAULT_CHAR_DISPLAY;
        }
    }

    /**
     * Structural equality check for two layouts. Returns {@code true} if
     * both infos have the same matrix dimensions and each cell's button
     * properties (key, display, macro, popup) are identical.
     *
     * <p>Used to avoid redundant {@link ExtraKeysView#reload} calls (e.g. when re-applying the
     * same layout after a session-name or styling change).</p>
     */
    public static boolean isSameLayout(@NonNull ExtraKeysInfo a, @NonNull ExtraKeysInfo b) {
        ExtraKeyButton[][] ma = a.getMatrix();
        ExtraKeyButton[][] mb = b.getMatrix();
        if (ma.length != mb.length) return false;
        for (int row = 0; row < ma.length; row++) {
            if (ma[row].length != mb[row].length) return false;
            for (int col = 0; col < ma[row].length; col++) {
                if (!buttonsEqual(ma[row][col], mb[row][col])) return false;
            }
        }
        return true;
    }

    private static boolean buttonsEqual(@NonNull ExtraKeyButton a, @NonNull ExtraKeyButton b) {
        if (!eq(a.getKey(), b.getKey())) return false;
        if (!eq(a.getDisplay(), b.getDisplay())) return false;
        if (a.isMacro() != b.isMacro()) return false;
        // Compare popup (swipe-up) button
        ExtraKeyButton ap = a.getPopup();
        ExtraKeyButton bp = b.getPopup();
        if (ap == null && bp == null) return true;
        if (ap == null || bp == null) return false;
        return buttonsEqual(ap, bp);
    }

    private static boolean eq(@Nullable String a, @Nullable String b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
