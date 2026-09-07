package com.termux.shared.termux.extrakeys;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tokenizer for macro bindings that may contain delay tokens ({@code DELAY_100}, {@code SLEEP_500}).
 * <p>
 * Bindings are whitespace-separated sequences of tokens. A token is a delay if it starts with
 * {@code DELAY_} or {@code SLEEP_} (case-insensitive) followed by a positive integer. Legacy
 * {@code SLEEP_} tokens are normalized to {@code DELAY_}.
 * <p>
 * A token may itself contain whitespace when it is a literal piece of text (a custom binding such
 * as {@code ls -la}). Such tokens are written wrapped in double quotes, e.g. {@code "ls -la" ENTER},
 * so that the whitespace belonging to the token is not mistaken for a token separator.
 */
public class BindingTokenizer {

    /** Minimum allowed delay value in milliseconds. */
    public static final int MIN_DELAY_MS = 1;

    /** Maximum allowed delay value in milliseconds. */
    public static final int MAX_DELAY_MS = 1000;

    /** Canonical delay prefix. */
    public static final String DELAY_PREFIX = "DELAY_";

    /** Legacy sleep prefix, normalized to {@link #DELAY_PREFIX} during tokenization. */
    public static final String LEGACY_SLEEP_PREFIX = "SLEEP_";

    /** Character used to quote a literal token that contains whitespace inside a macro. */
    public static final char QUOTE_CHAR = '"';

    /** Character used to escape a quote or a backslash inside a quoted token. */
    private static final char ESCAPE_CHAR = '\\';

    private static final int PARSE_BASE = 10;

    private BindingTokenizer() {
        // utility class
    }

    /**
     * Tokenize a binding string by splitting on whitespace, normalizing delay tokens.
     * <p>
     * Legacy {@code SLEEP_} tokens are converted to the canonical {@code DELAY_} form and the
     * value is clamped to [{@link #MIN_DELAY_MS}, {@link #MAX_DELAY_MS}]. Empty strings resulting
     * from the split are discarded.
     *
     * @param binding the raw binding string (may be {@code null}).
     * @return a non-null list of normalized tokens.
     */
    @NonNull
    public static List<String> tokenize(String binding) {
        List<String> result = new ArrayList<>();
        if (binding == null || binding.isEmpty()) return result;

        String[] parts = binding.split("\\s+");
        for (String part : parts) {
            if (part.isEmpty()) continue;
            result.add(normalize(part));
        }
        return result;
    }

    /**
     * Split a macro binding into tokens, keeping double-quoted spans together.
     * <p>
     * Termux macros are whitespace-separated token sequences, so a literal token that itself
     * contains spaces (a custom binding such as {@code ls -la}) would otherwise be split into
     * several tokens and lose its structure. Such tokens are written wrapped in double quotes,
     * e.g. {@code "ls -la" ENTER}, and are returned here as one token with the quotes stripped.
     * <p>
     * Quoting is only honoured when the quoted span actually contains whitespace. A quoted span
     * without whitespace (as found in hand-written configs, e.g. {@code echo "hi"}) is kept
     * verbatim, so existing layouts keep their current meaning.
     * <p>
     * Unquoted bindings tokenize exactly as before, so this method is backward compatible.
     *
     * @param macro the raw macro string (may be {@code null}).
     * @return a non-null list of normalized tokens.
     */
    @NonNull
    public static List<String> tokenizeMacro(@Nullable String macro) {
        List<String> result = new ArrayList<>();
        if (macro == null || macro.isEmpty()) return result;

        final int len = macro.length();
        int i = 0;
        while (i < len) {
            // Skip the whitespace separating two tokens.
            while (i < len && isWhitespace(macro.charAt(i))) i++;
            if (i >= len) break;

            if (macro.charAt(i) == QUOTE_CHAR) {
                int close = findClosingQuote(macro, i + 1);
                if (close >= 0) {
                    String inner = unescape(macro.substring(i + 1, close));
                    if (hasWhitespace(inner)) {
                        result.add(normalize(inner));
                        i = close + 1;
                        continue;
                    }
                    // No whitespace inside: the quotes are literal characters of the token, so
                    // fall through and read it as a plain token.
                }
                // Unterminated quote: treat it as a literal character too.
            }

            int start = i;
            while (i < len && !isWhitespace(macro.charAt(i))) i++;
            result.add(normalize(macro.substring(start, i)));
        }
        return result;
    }

    /**
     * Join tokens back into a macro string, quoting every token that contains whitespace so that
     * {@link #tokenizeMacro(String)} restores exactly the same token list.
     *
     * @param tokens the token list to serialize.
     * @return the macro string.
     */
    @NonNull
    public static String joinMacro(@NonNull List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token == null) continue;
            if (i > 0) sb.append(' ');
            if (hasWhitespace(token)) {
                sb.append(QUOTE_CHAR).append(escape(token)).append(QUOTE_CHAR);
            } else {
                sb.append(token);
            }
        }
        return sb.toString();
    }

    /**
     * Apply delay normalization to a single token: convert {@code SLEEP_} to {@code DELAY_} and
     * clamp the value. Unlike {@link #tokenize(String)} this never splits the input.
     *
     * @param token the token to normalize.
     * @return the normalized token.
     */
    @NonNull
    public static String normalizeToken(@NonNull String token) {
        return normalize(token);
    }

    /**
     * Returns {@code true} if {@code token} is a valid delay token: starts with {@link #DELAY_PREFIX}
     * followed by a positive integer in [{@link #MIN_DELAY_MS}, {@link #MAX_DELAY_MS}].
     *
     * @param token the token to check.
     * @return {@code true} if the token is a valid delay.
     */
    public static boolean isDelay(String token) {
        if (token == null || !token.startsWith(DELAY_PREFIX)) return false;
        int ms = parseUnsignedIntSuffix(token, DELAY_PREFIX.length());
        return ms >= MIN_DELAY_MS && ms <= MAX_DELAY_MS;
    }

    /**
     * Returns {@code true} if {@code token} starts with {@link #DELAY_PREFIX} or
     * {@link #LEGACY_SLEEP_PREFIX} (case-insensitive), regardless of whether a valid number follows.
     *
     * @param token the token to check.
     * @return {@code true} if the token has a delay prefix.
     */
    public static boolean hasDelayPrefix(String token) {
        if (token == null) return false;
        String upper = token.toUpperCase(Locale.US);
        return upper.startsWith(DELAY_PREFIX) || upper.startsWith(LEGACY_SLEEP_PREFIX);
    }

    /**
     * Index of the quote that closes a quoted span starting at {@code from}, or {@code -1} if the
     * span is never closed. Backslash escapes are honoured.
     */
    private static int findClosingQuote(@NonNull String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ESCAPE_CHAR && i + 1 < s.length()) {
                i++;
                continue;
            }
            if (c == QUOTE_CHAR) return i;
        }
        return -1;
    }

    /** Reverse of {@link #escape(String)}: turns {@code \"} into {@code "} and {@code \\} into {@code \}. */
    @NonNull
    private static String unescape(@NonNull String s) {
        if (s.indexOf(ESCAPE_CHAR) < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ESCAPE_CHAR && i + 1 < s.length()
                    && (s.charAt(i + 1) == QUOTE_CHAR || s.charAt(i + 1) == ESCAPE_CHAR)) {
                sb.append(s.charAt(i + 1));
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Escape quotes and backslashes so a token can be embedded inside a quoted span. */
    @NonNull
    private static String escape(@NonNull String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ESCAPE_CHAR || c == QUOTE_CHAR) sb.append(ESCAPE_CHAR);
            sb.append(c);
        }
        return sb.toString();
    }

    /** True if the string contains any whitespace that would act as a token separator. */
    private static boolean hasWhitespace(@NonNull String s) {
        for (int i = 0; i < s.length(); i++) {
            if (isWhitespace(s.charAt(i))) return true;
        }
        return false;
    }

    /** Same character set as the {@code \s} class used by {@link String#split(String)}. */
    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r';
    }

    /**
     * Parse the delay value (in milliseconds) from a delay token. The value is clamped to
     * [{@link #MIN_DELAY_MS}, {@link #MAX_DELAY_MS}]. Returns {@code 0} if the token is not a
     * valid delay.
     *
     * @param token the delay token.
     * @return the clamped delay value, or {@code 0} if invalid.
     */
    public static int parseDelayMs(String token) {
        if (token == null) return 0;
        String upper = token.toUpperCase(Locale.US);
        int prefixLen;
        if (upper.startsWith(DELAY_PREFIX)) {
            prefixLen = DELAY_PREFIX.length();
        } else if (upper.startsWith(LEGACY_SLEEP_PREFIX)) {
            prefixLen = LEGACY_SLEEP_PREFIX.length();
        } else {
            return 0;
        }
        int ms = parseUnsignedIntSuffix(upper, prefixLen);
        if (ms < 0) return 0;
        return clamp(ms);
    }

    /**
     * Generate the canonical delay token string for the given value.
     *
     * @param ms the delay value in milliseconds.
     * @return {@code "DELAY_" + }{@link #clamp(int) clamp(ms)}.
     */
    @NonNull
    public static String delayToken(int ms) {
        return DELAY_PREFIX + clamp(ms);
    }

    /**
     * Returns {@code true} if any token in the list is a valid delay.
     *
     * @param tokens the list of tokens.
     * @return {@code true} if a delay is present.
     */
    public static boolean containsDelay(@NonNull List<String> tokens) {
        for (String token : tokens) {
            if (isDelay(token)) return true;
        }
        return false;
    }

    /**
     * Compute the sum of all delay values from individual tokens.
     *
     * @param tokens the list of tokens.
     * @return the total delay in milliseconds.
     */
    public static int totalDelayMs(@NonNull List<String> tokens) {
        int total = 0;
        for (String token : tokens) {
            total += parseDelayMs(token);
        }
        return total;
    }

    /**
     * Collapse consecutive delay tokens into a single delay whose value is the sum of the
     * individual delays (clamped to [{@link #MIN_DELAY_MS}, {@link #MAX_DELAY_MS}]).
     * <p>
     * Non-delay tokens are preserved in their original order.
     *
     * @param tokens the input list of tokens.
     * @return a new list with consecutive delays merged.
     */
    @NonNull
    public static List<String> collapseConsecutiveDelays(@NonNull List<String> tokens) {
        List<String> result = new ArrayList<>();
        int pendingDelay = 0;

        for (String token : tokens) {
            int ms = parseDelayMs(token);
            if (ms > 0) {
                pendingDelay += ms;
                while (pendingDelay >= MAX_DELAY_MS) {
                    result.add(delayToken(MAX_DELAY_MS));
                    pendingDelay -= MAX_DELAY_MS;
                }
            } else {
                if (pendingDelay > 0) {
                    result.add(delayToken(pendingDelay));
                    pendingDelay = 0;
                }
                result.add(token);
            }
        }
        if (pendingDelay > 0) {
            result.add(delayToken(pendingDelay));
        }

        return result;
    }

    /**
     * Clamp a value to [{@link #MIN_DELAY_MS}, {@link #MAX_DELAY_MS}].
     *
     * @param ms the value to clamp.
     * @return the clamped value.
     */
    public static int clamp(int ms) {
        if (ms < MIN_DELAY_MS) return MIN_DELAY_MS;
        if (ms > MAX_DELAY_MS) return MAX_DELAY_MS;
        return ms;
    }

    // ---- internal helpers ----

    /**
     * Normalize a single token: convert {@code SLEEP_} to {@code DELAY_}, uppercase prefix, and
     * clamp the value if it is a valid delay.
     */
    @NonNull
    private static String normalize(@NonNull String token) {
        String upper = token.toUpperCase(Locale.US);
        if (upper.startsWith(LEGACY_SLEEP_PREFIX)) {
            String suffix = upper.substring(LEGACY_SLEEP_PREFIX.length());
            int ms = parseUnsignedIntSuffix(suffix, 0);
            if (ms < 0) return token;
            return delayToken(ms);
        }
        if (upper.startsWith(DELAY_PREFIX)) {
            String suffix = upper.substring(DELAY_PREFIX.length());
            int ms = parseUnsignedIntSuffix(suffix, 0);
            if (ms < 0) return token;
            return delayToken(ms);
        }
        return token;
    }

    /**
     * Parse an unsigned integer from the suffix of an already-uppercased string starting at the
     * given offset. Returns -1 if the suffix is not a valid non-negative integer.
     */
    private static int parseUnsignedIntSuffix(@NonNull String s, int offset) {
        if (offset >= s.length()) return -1;
        int value = 0;
        for (int i = offset; i < s.length(); i++) {
            char c = s.charAt(i);
            int digit = Character.digit(c, PARSE_BASE);
            if (digit < 0) return -1;
            int next = value * PARSE_BASE + digit;
            if (next < value) return -1;
            value = next;
        }
        return value;
    }
}
