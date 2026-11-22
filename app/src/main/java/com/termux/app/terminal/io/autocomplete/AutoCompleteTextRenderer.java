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

/**
 * Pure text-rendering helpers for auto-complete suggestion rows: drawing only
 * (truncation, bold prefix, line-fitting).
 */
final class AutoCompleteTextRenderer {

    private AutoCompleteTextRenderer() {}

    private static final StyleSpan BOLD_SPAN = new StyleSpan(Typeface.BOLD);

    /** Small cache of laid-out rows; each entry costs one slot. */
    private static final int LAYOUT_CACHE_MAX = 64;
    private static final LruCache<String, StaticLayout> sLayoutCache =
            new LruCache<String, StaticLayout>(LAYOUT_CACHE_MAX) {
                @Override protected int sizeOf(String key, StaticLayout value) {
                    return 1;
                }
            };
    private static String layoutKey(@NonNull String text, int w, int maxLines) {
        return text + "\u0000" + w + "\u0000" + maxLines;
    }

    /**
     * Memoization of the (expensive) truncation step in {@link #buildSuggestionSpannable}. The
     * truncated display text depends only on (suggestion, wordStart, availWidth, maxLines), and
     * within a popup session availWidth/maxLines are constant — so a hit skips the whole
     * StaticLayout / binary-search pipeline for every keystroke on every shown row.
     */
    private static final class DisplayKey {
        final String suggestion;
        final int wordStart;
        final int availWidth;
        final int maxLines;
        DisplayKey(@NonNull String suggestion, int wordStart, int availWidth, int maxLines) {
            this.suggestion = suggestion;
            this.wordStart = wordStart;
            this.availWidth = availWidth;
            this.maxLines = maxLines;
        }
        @Override public int hashCode() {
            int h = suggestion.hashCode();
            h = 31 * h + wordStart;
            h = 31 * h + availWidth;
            h = 31 * h + maxLines;
            return h;
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DisplayKey)) return false;
            DisplayKey k = (DisplayKey) o;
            return wordStart == k.wordStart && availWidth == k.availWidth
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
            char c = s.charAt(i - 1);
            if (c != ' ' && c != '/') break;
            i--;
        }
        while (i > 0) {
            char c = s.charAt(i - 1);
            if (c == ' ' || c == '/') return i;
            i--;
        }
        return 0;
    }

    /**
     * Build the display {@link SpannableString} for an auto-complete suggestion: word-based
     * leading truncation (the {@code "... "} prefix when the match starts mid-word), manual
     * end-truncation with a trailing {@code '…'} when it would exceed {@code maxLines}, and the
     * matched input prefix rendered in BOLD.
     *
     * <p>Manual truncation instead of {@code TextView.setEllipsize(END)}: on Android (API 21-28
     * in particular) {@code ellipsize=end} is only reliably honored for single-line text — with
     * {@code setMaxLines(n > 1)} the trailing ellipsis frequently never appears.
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
        String matchStr = input;
        // A full-entry candidate can be multi-line; render it as one logical line
        // so the popup row does not grow to the height of the whole entry.
        String displaySuggestion = normalizedSuggestion(suggestion);
        int wordStart = Math.min(inputWordStart, displaySuggestion.length());
        int boldLen = matchStr.length() - wordStart;
        boolean hasLastWord = boldLen > 0;
        String prefix = (wordStart > 0) ? "... " : "";
        int prefixLen = prefix.length();

        String displayText;
        if (availWidth > 0) {
            DisplayKey key = new DisplayKey(displaySuggestion, wordStart, availWidth, 2);
            String cached = sDisplayCache.get(key);
            if (cached != null) {
                displayText = cached;
            } else {
                String raw = (prefixLen > 0)
                        ? prefix + displaySuggestion.substring(wordStart) : displaySuggestion;
                displayText = truncateToLines(raw, availWidth, paint, 2);
                sDisplayCache.put(key, displayText);
            }
        } else {
            displayText = (prefixLen > 0)
                    ? prefix + displaySuggestion.substring(wordStart) : displaySuggestion;
        }

        SpannableString ss = new SpannableString(displayText);
        int spanEnd = Math.min(prefixLen + boldLen, displayText.length());
        if (hasLastWord && spanEnd > prefixLen
                && displaySuggestion.regionMatches(true, 0, matchStr, 0, matchStr.length())) {
            ss.setSpan(BOLD_SPAN,
                    prefixLen, spanEnd, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    /** True when {@code old} already has {@code next}'s characters and bold range. */
    static boolean sameRendering(@Nullable CharSequence old, @NonNull SpannableString next) {
        if (old == null || old.length() != next.length()) return false;
        for (int i = 0; i < next.length(); i++) {
            if (old.charAt(i) != next.charAt(i)) return false;
        }
        if (!(old instanceof Spanned)) return false;
        Spanned spanned = (Spanned) old;
        return spanned.getSpanStart(BOLD_SPAN) == next.getSpanStart(BOLD_SPAN)
                && spanned.getSpanEnd(BOLD_SPAN) == next.getSpanEnd(BOLD_SPAN);
    }

    /**
     * Truncate {@code text} so it fits within {@code maxLines} lines of width
     * {@code availWidth}. Returns the original text unchanged if it already fits.
     * Otherwise keeps the start and appends a single {@code '…'}.
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

    /** Slice-aware variant: builds layout only for [start, end) (+ "…" when {@code ellipsis}). */
    static boolean fitsLines(@NonNull String text, int start, int end, boolean ellipsis,
            int availWidth, @NonNull TextPaint paint, int maxLines) {
        String key = layoutKey(text, start, end, end, end, ellipsis, availWidth, maxLines);
        StaticLayout layout = sLayoutCache.get(key);
        if (layout == null) {
            String slice = (ellipsis ? text.substring(start, end) + "…" : text.substring(start, end));
            layout = buildLayout(slice, availWidth, paint, maxLines);
            sLayoutCache.put(key, layout);
        }
        return layout.getLineCount() <= maxLines;
    }

    /** True when {@code text} lays out to at most {@code maxLines} lines of {@code availWidth}. */
    static boolean fitsLines(@NonNull String text, int availWidth,
            @NonNull TextPaint paint, int maxLines) {
        String key = layoutKey(text, availWidth, maxLines);
        StaticLayout layout = sLayoutCache.get(key);
        if (layout == null) {
            layout = buildLayout(text, availWidth, paint, maxLines);
            sLayoutCache.put(key, layout);
        }
        return layout.getLineCount() <= maxLines;
    }

    private static String binarySearchTruncate(@NonNull String text, int availWidth,
            @NonNull TextPaint paint, int maxLines) {
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (fitsLines(text, 0, mid, true, availWidth, paint, maxLines)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + "…";
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

    private static String layoutKey(@NonNull String text, int start, int end, int tailStart,
            int tailEnd, boolean ellipsis, int w, int maxLines) {
        return text + "\u0000" + start + "\u0000" + end + "\u0000" + tailStart + "\u0000"
                + tailEnd + "\u0000" + ellipsis + "\u0000" + w + "\u0000" + maxLines;
    }
}
