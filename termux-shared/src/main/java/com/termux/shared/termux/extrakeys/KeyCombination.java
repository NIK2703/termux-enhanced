package com.termux.shared.termux.extrakeys;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A keyboard combination binding: an ordered token list whose modifiers (CTRL/ALT/SHIFT/FN) are
 * followed by the key they modify, e.g. {@code ["CTRL", "ALT", "a"]} or {@code ["CTRL", "F5"]}.
 *
 * <p>This is the same token vocabulary the extra-keys editor uses, so a binding picked with the
 * signal picker can be stored and re-read verbatim. Two spellings are accepted when parsing:
 * the token form ({@code "CTRL ALT a"}) and the legacy {@code "ctrl+a"} form that older versions
 * of the app wrote, so existing values keep working.
 *
 * <p>Only the modifiers are required to come first: {@link #keyOf(List)} takes the last
 * non-modifier token as the key, so {@code ["CTRL", "a", "ALT"]} is read as {@code CTRL+ALT+a}.
 */
public final class KeyCombination {

    public static final String CTRL = "CTRL";
    public static final String ALT = "ALT";
    public static final String SHIFT = "SHIFT";
    public static final String FN = "FN";

    private KeyCombination() {}

    /** True if the token is one of the four modifier keys, in any letter case. */
    public static boolean isModifier(@Nullable String token) {
        if (token == null) return false;
        String upper = token.toUpperCase(Locale.US);
        return CTRL.equals(upper) || ALT.equals(upper) || SHIFT.equals(upper) || FN.equals(upper);
    }

    /**
     * Canonical spelling of a token: modifiers and named keys (the ones
     * {@link ExtraKeysConstants#PRIMARY_KEY_CODES_FOR_STRINGS} knows) are upper-cased, everything
     * else — a character or a piece of custom text — is kept exactly as typed.
     */
    @NonNull
    public static String canonicalize(@NonNull String token) {
        String upper = token.toUpperCase(Locale.US);
        if (isModifier(upper) || ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS.containsKey(upper))
            return upper;
        return token;
    }

    /**
     * Parse a stored binding into its canonical tokens. Returns an empty list for a null, empty or
     * unparsable value, which callers read as "no binding".
     */
    @NonNull
    public static List<String> parse(@Nullable String stored) {
        List<String> tokens = new ArrayList<>();
        if (stored == null) return tokens;
        String value = stored.trim();
        if (value.isEmpty()) return tokens;

        List<String> raw = BindingTokenizer.tokenizeMacro(value);
        // Legacy single token like "ctrl+a" — split it into modifiers and key.
        if (raw.size() == 1 && raw.get(0).indexOf('+') >= 0) {
            raw = new ArrayList<>();
            for (String part : value.split("\\+")) {
                String part1 = part.trim();
                if (!part1.isEmpty()) raw.add(part1);
            }
        }

        for (String token : raw) {
            if (!token.isEmpty()) tokens.add(canonicalize(token));
        }
        return tokens;
    }

    /** Serialize tokens for storage. Whitespace inside a token is quoted, so the list round-trips. */
    @NonNull
    public static String format(@NonNull List<String> tokens) {
        return BindingTokenizer.joinMacro(tokens);
    }

    /** True if the combination starts with a modifier. */
    public static boolean hasLeadingModifier(@NonNull List<String> tokens) {
        return !tokens.isEmpty() && isModifier(tokens.get(0));
    }

    /** True if the combination contains a key (a non-modifier token) for the modifiers to apply to. */
    public static boolean hasKey(@NonNull List<String> tokens) {
        for (String token : tokens) {
            if (!isModifier(token)) return true;
        }
        return false;
    }

    /** The key of the combination: its last non-modifier token, or null if there is none. */
    @Nullable
    public static String keyOf(@NonNull List<String> tokens) {
        for (int i = tokens.size() - 1; i >= 0; i--) {
            if (!isModifier(tokens.get(i))) return tokens.get(i);
        }
        return null;
    }

    /**
     * Human-readable form for a settings summary, e.g. {@code Ctrl + Alt + a}. Modifier names are
     * spelled the way the surrounding UI writes them; key names are left alone.
     */
    @NonNull
    public static String toDisplayString(@NonNull List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(displayToken(token));
        }
        return sb.toString();
    }

    @NonNull
    private static String displayToken(@NonNull String token) {
        String upper = token.toUpperCase(Locale.US);
        if (CTRL.equals(upper)) return "Ctrl";
        if (ALT.equals(upper)) return "Alt";
        if (SHIFT.equals(upper)) return "Shift";
        if (FN.equals(upper)) return "Fn";
        return token;
    }
}
