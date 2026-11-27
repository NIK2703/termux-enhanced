package com.termux.app.terminal.io.autocomplete;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Edge-whitespace rules shared by the history store, its codec and the controller.
 * Internal spacing is always preserved.
 */
final class HistoryText {

    private HistoryText() {
    }

    /** Strips edge whitespace; returns an empty string for null. */
    @NonNull
    static String trim(@Nullable String message) {
        if (message == null) return "";
        int start = 0;
        int end = message.length();
        while (start < end && isEdgeWhitespace(message.charAt(start))) start++;
        while (end > start && isEdgeWhitespace(message.charAt(end - 1))) end--;
        return start == 0 && end == message.length() ? message : message.substring(start, end);
    }

    /** Whether the value is null or consists only of edge whitespace. */
    static boolean isBlank(@Nullable String message) {
        if (message == null) return true;
        for (int i = 0; i < message.length(); i++) {
            if (!isEdgeWhitespace(message.charAt(i))) return false;
        }
        return true;
    }

    static boolean isEdgeWhitespace(char c) {
        return HistorySyntax.isWhitespace(c);
    }

    /**
     * Whether {@code shorter} is a proper word-prefix of {@code expanded}, i.e. a
     * short variant that the history may absorb when {@code expanded} is added.
     *
     * <p>The boundary check keeps {@code git} from swallowing {@code github}: the
     * prefix must end at a shell-word boundary. A right swipe on a history row
     * enters words delimited by whitespace and {@code '/'}, so those are the
     * boundaries a shorter variant can legitimately stop at. A prefix that itself
     * ends with {@code '/'} (for example {@code cd /data/}) is a boundary too,
     * because the next path segment continues it.
     */
    static boolean isAbsorbablePrefix(@NonNull String shorter, @NonNull String expanded) {
        if (shorter.isEmpty() || shorter.length() >= expanded.length()) return false;
        if (!expanded.startsWith(shorter)) return false;
        if (isWordBoundary(shorter.charAt(shorter.length() - 1))) return true;
        return isWordBoundary(expanded.charAt(shorter.length()));
    }

    private static boolean isWordBoundary(char c) {
        return c == '/' || HistorySyntax.isWhitespace(c);
    }
}
