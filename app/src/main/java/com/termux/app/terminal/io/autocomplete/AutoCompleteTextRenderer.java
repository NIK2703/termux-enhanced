package com.termux.app.terminal.io.autocomplete;

import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.Layout;
import android.text.TextPaint;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.terminal.TextFade;

/**
 * Pure text-rendering helpers for auto-complete suggestion rows: drawing only
 * (truncation, alpha spans, line-fitting).
 *
 * <p>Truncation uses the same alpha fade as tab titles. A leading cut keeps only the last
 * three letters of the typed word and fades them in; a trailing cut fades the last visible
 * letters instead of appending an ellipsis.</p>
 */
final class AutoCompleteTextRenderer {

    private AutoCompleteTextRenderer() {}

    /** Suggestion rows always use two lines. */
    private static final int MAX_LINES = 2;

    /** Small cache of laid-out rows; each entry costs one slot. */
    private static final int LAYOUT_CACHE_MAX = 64;
    private static final LruCache<String, StaticLayout> sLayoutCache =
            new LruCache<String, StaticLayout>(LAYOUT_CACHE_MAX) {
                @Override protected int sizeOf(String key, StaticLayout value) {
                    return 1;
                }
            };
    private static String layoutKey(@NonNull String text, int start, int end, int w, int maxLines) {
        return text + "\u0000" + start + "\u0000" + end + "\u0000" + w + "\u0000" + maxLines;
    }

    /**
     * Memoization of the (expensive) truncation step in {@link #buildSuggestionSpannable}. The
     * truncated display text depends only on (suggestion, visibleStart, availWidth, maxLines),
     * and within a popup session availWidth/maxLines are constant — so a hit skips the whole
     * StaticLayout / binary-search pipeline for every keystroke on every shown row.
     */
    private static final class DisplayKey {
        final String suggestion;
        final int visibleStart;
        final int availWidth;
        final int maxLines;
        DisplayKey(@NonNull String suggestion, int visibleStart, int availWidth, int maxLines) {
            this.suggestion = suggestion;
            this.visibleStart = visibleStart;
            this.availWidth = availWidth;
            this.maxLines = maxLines;
        }
        @Override public int hashCode() {
            int h = suggestion.hashCode();
            h = 31 * h + visibleStart;
            h = 31 * h + availWidth;
            h = 31 * h + maxLines;
            return h;
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DisplayKey)) return false;
            DisplayKey k = (DisplayKey) o;
            return visibleStart == k.visibleStart && availWidth == k.availWidth
                    && maxLines == k.maxLines && suggestion.equals(k.suggestion);
        }
    }
    private static final int DISPLAY_CACHE_MAX = 64;
    private static final LruCache<DisplayKey, String> sDisplayCache =
            new LruCache<DisplayKey, String>(DISPLAY_CACHE_MAX);

    /** Cache of newline-normalized suggestions, reused across keystrokes. */
    private static final int NORMALIZED_CACHE_MAX = 64;
    private static final LruCache<String, String> sNormalizedCache =
            new LruCache<String, String>(NORMALIZED_CACHE_MAX);

    @NonNull
    private static String normalizedSuggestion(@NonNull String suggestion) {
        String cached = sNormalizedCache.get(suggestion);
        if (cached != null) return cached;
        String normalized = suggestion.indexOf('\n') >= 0
                ? suggestion.replace('\n', ' ') : suggestion;
        sNormalizedCache.put(suggestion, normalized);
        return normalized;
    }

    /**
     * Index of the start of the last whitespace/slash-delimited word in {@code s}, treating a
     * trailing separator as "still finishing word 0".
     */
    static int wordStartOffset(@NonNull String s) {
        int i = s.length();
        // Skip trailing separators: "hello " is still word 0, not an empty next word.
        while (i > 0) {
            if (!isWordSeparator(s.charAt(i - 1))) break;
            i--;
        }
        while (i > 0) {
            if (isWordSeparator(s.charAt(i - 1))) return i;
            i--;
        }
        return 0;
    }

    private static boolean isWordSeparator(char c) {
        return c == ' ' || c == '/';
    }

    /**
     * Build the display {@link SpannableString} for an auto-complete suggestion.
     *
     * <p>When the match starts mid-word, the leading context word is shown in full at a
     * constant 50% opacity instead of an ellipsis. If there is a word before that context
     * word, its last three letters are shown with the softer 15/30/45 head fade. While the
     * next word is being typed, the context word is the previous one. The letters already
     * typed of that word are dimmed to 50% instead of being bold. When the text is too tall
     * for {@code maxLines}, the tail is cut and its
     * last three visible code points fade out, exactly like a truncated tab title.</p>
     *
     * <p>Manual truncation instead of {@code TextView.setEllipsize(END)}: on Android (API 21-28
     * in particular) {@code ellipsize=end} is only reliably honored for single-line text — with
     * {@code setMaxLines(n > 1)} the trailing ellipsis frequently never appears.</p>
     *
     * @param availWidth available text width in px (popup width minus padding);
     *                   pass {@code 0} to skip truncation (e.g. not yet laid out).
     */
    @NonNull
    static SpannableString buildSuggestionSpannable(@NonNull String suggestion,
            @NonNull String input, int availWidth, @NonNull TextPaint paint) {
        return buildSuggestionSpannable(suggestion, input, wordStartOffset(input),
                availWidth, paint);
    }

    /** Variant that reuses a word start computed once for the update. */
    @NonNull
    static SpannableString buildSuggestionSpannable(@NonNull String suggestion,
            @NonNull String input, int inputWordStart, int availWidth,
            @NonNull TextPaint paint) {
        final String matchStr = input;
        // A full-entry candidate can be multi-line; render it as one logical line
        // so the popup row does not grow to the height of the whole entry.
        final String displaySuggestion = normalizedSuggestion(suggestion);
        final int wordStart = Math.min(inputWordStart, displaySuggestion.length());

        // The last typed word ends at the input end, but trailing separators are not
        // part of it.
        int lastWordEnd = Math.min(matchStr.length(), displaySuggestion.length());
        while (lastWordEnd > wordStart
                && isWordSeparator(matchStr.charAt(lastWordEnd - 1))) {
            lastWordEnd--;
        }

        // The word in front of the continuation is shown in full at a constant 50% opacity.
        // While the next word is being typed, that word is the previous one; otherwise it is
        // the last word the user typed.
        int suggestionWordEnd = wordStart;
        while (suggestionWordEnd < displaySuggestion.length()
                && !isWordSeparator(displaySuggestion.charAt(suggestionWordEnd))) {
            suggestionWordEnd++;
        }
        final boolean midWord = suggestionWordEnd > lastWordEnd;

        int contextStart = 0;
        int contextLen = 0;
        int boldStart;
        int boldEnd;
        if (midWord && wordStart > 0) {
            int prevEnd = wordStart;
            while (prevEnd > 0 && isWordSeparator(matchStr.charAt(prevEnd - 1))) {
                prevEnd--;
            }
            final int prevStart = wordStartOffset(matchStr.substring(0, prevEnd));
            if (prevEnd > prevStart) {
                contextStart = prevStart;
                contextLen = prevEnd - prevStart;
            }
            boldStart = wordStart;
            boldEnd = lastWordEnd;
        } else if (wordStart > 0) {
            contextStart = wordStart;
            contextLen = lastWordEnd - wordStart;
            boldStart = wordStart;
            boldEnd = lastWordEnd;
        } else {
            boldStart = 0;
            boldEnd = lastWordEnd;
        }

        // A word before the context word, if there is one, contributes only its last three
        // letters with the softer 15/30/45 head fade.
        int visibleStart;
        int gradientEnd = 0;
        if (contextLen > 0) {
            int beforeEnd = contextStart;
            while (beforeEnd > 0 && isWordSeparator(matchStr.charAt(beforeEnd - 1))) {
                beforeEnd--;
            }
            final int beforeStart = wordStartOffset(matchStr.substring(0, beforeEnd));
            final int beforeLen = beforeEnd - beforeStart;
            if (beforeLen > 0) {
                visibleStart = beforeStart + Math.max(0, beforeLen - TextFade.FADE_CHARS);
                gradientEnd = beforeEnd - visibleStart;
            } else {
                visibleStart = contextStart;
            }
        } else {
            visibleStart = 0;
        }

        final String raw = displaySuggestion.substring(visibleStart);
        final String displayText;
        if (availWidth > 0) {
            DisplayKey key = new DisplayKey(displaySuggestion, visibleStart, availWidth,
                    MAX_LINES);
            String cached = sDisplayCache.get(key);
            if (cached != null) {
                displayText = cached;
            } else {
                displayText = truncateToLines(raw, availWidth, paint, MAX_LINES);
                sDisplayCache.put(key, displayText);
            }
        } else {
            displayText = raw;
        }

        SpannableString ss = new SpannableString(displayText);
        // Optional tail of the word before the context word: 15/30/45 from the left.
        if (gradientEnd > 0) {
            TextFade.fadeHead(ss, 0, Math.min(gradientEnd, displayText.length()));
        }
        // The context word itself is complete but dimmed to a constant 50%.
        final int contextFrom = contextStart - visibleStart;
        final int contextTo = Math.min(contextFrom + contextLen, displayText.length());
        if (contextLen > 0) {
            TextFade.applyAlpha(ss, contextFrom, contextTo, TextFade.HALF_ALPHA);
        }
        // A manual end cut has no ellipsis; its last visible letters fade out.
        if (displayText.length() < raw.length()) {
            TextFade.fadeTail(ss, displayText.length());
        }

        // The letters the user typed of the next word are dimmed too, not bolded.
        final int typedFrom = Math.min(boldStart - visibleStart, displayText.length());
        final int typedTo = Math.min(boldEnd - visibleStart, displayText.length());
        if (typedTo > typedFrom
                && !(typedFrom >= contextFrom && typedTo <= contextTo)
                && displaySuggestion.regionMatches(true, 0, matchStr, 0, matchStr.length())) {
            TextFade.applyAlpha(ss, typedFrom, typedTo, TextFade.HALF_ALPHA);
        }
        return ss;
    }

    /** True when {@code old} already has {@code next}'s characters and alpha spans. */
    static boolean sameRendering(@Nullable CharSequence old, @NonNull SpannableString next) {
        if (old == null || old.length() != next.length()) return false;
        for (int i = 0; i < next.length(); i++) {
            if (old.charAt(i) != next.charAt(i)) return false;
        }
        if (!(old instanceof Spanned)) return false;
        return TextFade.sameFades((Spanned) old, next);
    }

    /**
     * Truncate {@code text} so it fits within {@code maxLines} lines of width
     * {@code availWidth}. Returns the original text unchanged if it already fits.
     * Otherwise keeps the longest prefix that fits; the caller fades its tail.
     */
    @NonNull
    static String truncateToLines(@NonNull String text, int availWidth,
            @NonNull TextPaint paint, int maxLines) {
        if (text.length() == 0) return text;
        // Cheap necessary-check: summed glyph widths already exceed maxLines*availWidth, so the
        // text cannot fit. ("Definitely fits" cannot be proven this cheaply, so fitsLines runs.)
        if (paint.measureText(text) > (long) maxLines * availWidth) {
            return binarySearchTruncate(text, availWidth, paint, maxLines);
        }
        if (fitsLines(text, availWidth, paint, maxLines)) return text;
        return binarySearchTruncate(text, availWidth, paint, maxLines);
    }

    /** Slice-aware variant: builds a layout only for [start, end). */
    static boolean fitsLines(@NonNull String text, int start, int end,
            int availWidth, @NonNull TextPaint paint, int maxLines) {
        String key = layoutKey(text, start, end, availWidth, maxLines);
        StaticLayout layout = sLayoutCache.get(key);
        if (layout == null) {
            layout = buildLayout(text.substring(start, end), availWidth, paint, maxLines);
            sLayoutCache.put(key, layout);
        }
        return layout.getLineCount() <= maxLines;
    }

    /** True when {@code text} lays out to at most {@code maxLines} lines of {@code availWidth}. */
    static boolean fitsLines(@NonNull String text, int availWidth,
            @NonNull TextPaint paint, int maxLines) {
        return fitsLines(text, 0, text.length(), availWidth, paint, maxLines);
    }

    private static String binarySearchTruncate(@NonNull String text, int availWidth,
            @NonNull TextPaint paint, int maxLines) {
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (fitsLines(text, 0, mid, availWidth, paint, maxLines)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        // Never expose half of a surrogate pair.
        if (lo > 0 && lo < text.length()
                && Character.isHighSurrogate(text.charAt(lo - 1))
                && Character.isLowSurrogate(text.charAt(lo))) {
            lo--;
        }
        // The fade marks the cut; trailing whitespace would only add an invisible gap.
        while (lo > 0 && Character.isWhitespace(text.charAt(lo - 1))) {
            lo--;
        }
        return text.substring(0, lo);
    }

    private static StaticLayout buildLayout(@NonNull String text, int availWidth,
            @NonNull TextPaint paint, int maxLines) {
        StaticLayout layout;
        if (Build.VERSION.SDK_INT >= 23) {
            // Build with maxLines+1 so a text that needs strictly more than maxLines lines is
            // detected (getLineCount() would otherwise be capped at maxLines and report "fits").
            layout = StaticLayout.Builder.obtain(
                    text, 0, text.length(), paint, availWidth)
                    .setMaxLines(maxLines + 1)
                    .setEllipsize(null)
                    .build();
        } else {
            layout = new StaticLayout(
                    text, paint, availWidth,
                    Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
        }
        return layout;
    }
}
