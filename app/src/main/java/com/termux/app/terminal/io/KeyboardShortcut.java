package com.termux.app.terminal.io;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.extrakeys.KeyCombination;

import java.util.List;

/**
 * A session action bound to a keyboard combination.
 *
 * <p>The combination is stored as the token list the signal picker produces (modifiers followed by
 * the key), so it is matched exactly: pressing {@code Ctrl+Alt+a} runs a binding only when it was
 * configured as {@code CTRL ALT a} and nothing else. Requiring the modifier set to match in full is
 * what keeps a binding from firing while the user is just typing with Shift held.
 */
public class KeyboardShortcut {

    @NonNull
    public final List<String> tokens;
    public final int shortcutAction;

    public KeyboardShortcut(@NonNull List<String> tokens, int shortcutAction) {
        this.tokens = tokens;
        this.shortcutAction = shortcutAction;
    }

    /**
     * True if the pressed combination is exactly this binding.
     *
     * @param key the pressed key: a single character for a character key, or a named token
     *            ({@code F5}, {@code UP}, {@code ESC}, …) for everything else.
     */
    public boolean matches(@Nullable String key, boolean ctrl, boolean alt, boolean shift, boolean fn) {
        if (key == null) return false;
        String target = KeyCombination.keyOf(tokens);
        if (target == null || !keyMatches(target, key)) return false;
        return modifiersMatch(ctrl, alt, shift, fn);
    }

    /**
     * A one-character binding matches in either letter case, so {@code Ctrl+A} and {@code Ctrl+a}
     * are the same binding. Named keys are compared as names.
     */
    private static boolean keyMatches(@NonNull String target, @NonNull String key) {
        if (target.length() == 1) {
            return key.length() == 1
                && Character.toLowerCase(target.charAt(0)) == Character.toLowerCase(key.charAt(0));
        }
        return target.equalsIgnoreCase(key);
    }

    private boolean modifiersMatch(boolean ctrl, boolean alt, boolean shift, boolean fn) {
        boolean wantCtrl = false, wantAlt = false, wantShift = false, wantFn = false;
        for (String token : tokens) {
            if (KeyCombination.CTRL.equals(token)) wantCtrl = true;
            else if (KeyCombination.ALT.equals(token)) wantAlt = true;
            else if (KeyCombination.SHIFT.equals(token)) wantShift = true;
            else if (KeyCombination.FN.equals(token)) wantFn = true;
        }
        return ctrl == wantCtrl && alt == wantAlt && shift == wantShift && fn == wantFn;
    }

}
