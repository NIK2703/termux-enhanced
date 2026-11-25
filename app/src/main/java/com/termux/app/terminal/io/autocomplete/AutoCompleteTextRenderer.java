package com.termux.app.terminal.io.autocomplete;

import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.Layout;
import android.text.TextPaint;
import android.text.style.StyleSpan;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.terminal.TextFade;

/**
 * Pure text-rendering helpers for auto-complete suggestion rows: drawing only
 * (truncation, bold prefix, line-fitting).
 *
 * <p>Truncation uses the same alpha fade as tab titles. A leading cut keeps only the last
 * three letters of the typed word and fades them in; a trailing cut fades the last visible
 * letters instead of appending an ellipsis.</p>
 */
final class AutoCompleteTextRenderer {

    private AutoCompleteTextRenderer() {}

    private static final StyleSpan BOLD_SPAN = new StyleSpan(Typeface.BOLD);

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
     * <p>When the match starts mid-word, only the last three letters of the truncated word
     * are kept and faded in; the omitted words are not replaced by {@code "... "}. While the
     * next word is being typed, that word stays readable and the previous word is the one
     * reduced to its last three letters. When the text is too tall for {@code maxLines}, the
     * tail is cut and its last three visible code points fade out, exactly like a truncated
     * tab title.</p>
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

        // If the suggestion keeps going inside the word the user is typing, that word must
        // stay readable: fade the tail of the previous word instead and put the typed word
        // to its right. A completed word is truncated the usual way.
        int suggestionWordEnd = wordStart;
        while (suggestionWordEnd < displaySuggestion.length()
                && !isWordSeparator(displaySuggestion.charAt(suggestionWordEnd))) {
            suggestionWordEnd++;
        }
        final boolean midWord = suggestionWordEnd > lastWordEnd;

        int visibleStart;
        int fadeEnd;
        int boldStart;
        int boldEnd;
        if (midWord && wordStart > 0) {
            int prevEnd = wordStart;
            while (prevEnd > 0 && isWordSeparator(matchStr.charAt(prevEnd - 1))) {
                prevEnd--;
            }
            final int prevStart = wordStartOffset(matchStr.substring(0, prevEnd));
            final int prevLen = prevEnd - prevStart;
            if (prevLen > 0) {
                visibleStart = prevStart + Math.max(0, prevLen - TextFade.FADE_CHARS);
                fadeEnd = prevEnd - visibleStart;
                boldStart = wordStart - visibleStart;
                boldEnd = lastWordEnd - visibleStart;
            } else {
                visibleStart = 0;
                fadeEnd = 0;
                boldStart = 0;
                boldEnd = lastWordEnd;
            }
        } else if (wordStart > 0) {
            final int lastWordLen = lastWordEnd - wordStart;
            visibleStart = wordStart + Math.max(0, lastWordLen - TextFade.FADE_CHARS);
            fadeEnd = lastWordEnd - visibleStart;
            boldStart = 0;
            boldEnd = fadeEnd;
        } else {
            visibleStart = 0;
            fadeEnd = 0;
            boldStart = 0;
            boldEnd = lastWordEnd;
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
        // The kept tail of the truncated word fades in from the left, mirroring the faded
        // tail of a truncated tab title.
        if (fadeEnd > 0) {
            TextFade.fadeHead(ss, 0, Math.min(fadeEnd, displayText.length()));
        }
        // A manual end cut has no ellipsis; its last visible letters fade out.
        if (displayText.length() < raw.length()) {
            TextFade.fadeTail(ss, displayText.length());
        }

        final int boldFrom = Math.min(boldStart, displayText.length());
        final int boldTo = Math.min(boldEnd, displayText.length());
        if (boldTo > boldFrom
                && displaySuggestion.regionMatches(true, 0, matchStr, 0, matchStr.length())) {
            ss.setSpan(BOLD_SPAN, boldFrom, boldTo, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    /** True when {@code old} already has {@code next}'s characters, bold range and fades. */
    static boolean sameRendering(@Nullable CharSequence old, @NonNull SpannableString next) {
        if (old == null || old.length() != next.length()) return false;
        for (int i = 0; i < next.length(); i++) {
            if (old.charAt(i) != next.charAt(i)) return false;
        }
        if (!(old instanceof Spanned)) return false;
        Spanned spanned = (Spanned) old;
        return spanned.getSpanStart(BOLD_SPAN) == next.getSpanStart(BOLD_SPAN)
                && spanned.getSpanEnd(BOLD_SPAN) == next.getSpanEnd(BOLD_SPAN)
                && TextFade.sameFades(spanned, next);
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
