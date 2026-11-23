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
}
