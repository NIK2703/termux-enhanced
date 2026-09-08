package com.termux.app.terminal.io.autocomplete;

import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableString;
import android.text.StaticLayout;
import android.text.Layout;
import android.text.TextPaint;
import android.text.style.StyleSpan;
import android.util.LruCache;

import androidx.annotation.NonNull;

/**
 * Pure text-rendering helpers for auto-complete suggestion rows.
 *
 * <p>Responsibility split:
 * <ul>
 *   <li>{@code AutoCompleteTextRenderer} — how a suggestion STRING is drawn
 *       (word truncation, bold prefix, line-fitting);</li>
 *   <li>{@code AutoCompletePopupManager} — how the popup WINDOW is built,
 *       positioned and shown;</li>
 *   <li>{@code AutoCompleteController} — orchestration (fetch, filter, input
 *       handling, insertion) and ownership of the suggestion data.</li>
 * </ul>
 */
final class AutoCompleteTextRenderer {

    private AutoCompleteTextRenderer() {}

    private static final StyleSpan BOLD_SPAN = new StyleSpan(Typeface.BOLD);

    private static final int LAYOUT_CACHE_MAX = 512;
    private static final LruCache<String, StaticLayout> sLayoutCache =
            new LruCache<String, StaticLayout>(LAYOUT_CACHE_MAX) {
                @Override protected int sizeOf(String key, StaticLayout value) {
                    return value.getLineCount() + 1;
                }
            };
    private static String layoutKey(@NonNull String text, int w, int maxLines) {
        return text + "\u0000" + w + "\u0000" + maxLines;
    }

    /**
     * Index of the start of the last whitespace/slash-delimited word in {@code s},
     * treating a trailing separator as "still finishing word 0". Used to decide
     * where the bold-matched prefix begins.
     */
    static int wordStartOffset(@NonNull String s) {
        int i = s.length();
        // Skip trailing separators so a space at the end of the typed text
        // (e.g. "hello ") is treated as "still finishing word 0", not as a
        // boundary into an empty next word.
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
     * Build the display {@link SpannableString} for an auto-complete suggestion.
     * Applies the word-based leading truncation (the {@code "... "} prefix added
     * when the match starts mid-word) and, when the result would exceed
     * {@code maxLines} lines, manually truncates it and appends a trailing
     * {@code '…'}.
     *
     * <p>Why manual truncation instead of {@code TextView.setEllipsize(END)}:
     * on Android (API 21-28 in particular) {@code ellipsize=end} is only reliably
     * honored for <b>single-line</b> text. With {@code setMaxLines(n)} where
     * {@code n > 1} the framework routes to {@code StaticLayout} but the trailing
     * ellipsis on the last line is unreliable and frequently never appears. We
     * therefore measure and cut the text ourselves so the {@code '…'} is
     * guaranteed for long suggestions/messages regardless of OS version. The
     * matched input prefix is rendered in BOLD on top of the (possibly truncated)
     * display text.
     *
     * @param availWidth available text width in px (popup width minus padding);
     *                   pass {@code 0} to skip truncation (e.g. not yet laid out).
     */
    @NonNull
    static SpannableString buildSuggestionSpannable(@NonNull String suggestion,
            @NonNull String input, int availWidth, @NonNull TextPaint paint) {
        String matchStr = input;
        int wordStart = Math.min(wordStartOffset(matchStr), suggestion.length());
        int boldLen = matchStr.length() - wordStart;
        boolean hasLastWord = boldLen > 0;
        String prefix = (wordStart > 0) ? "... " : "";
        int prefixLen = prefix.length();
        String displayText = (prefixLen > 0) ? prefix + suggestion.substring(wordStart) : suggestion;

        if (availWidth > 0) {
            displayText = truncateToLines(displayText, availWidth, paint, 2);
        }

        SpannableString ss = new SpannableString(displayText);
        int spanEnd = Math.min(prefixLen + boldLen, displayText.length());
        if (hasLastWord && spanEnd > prefixLen
                && suggestion.regionMatches(true, 0, matchStr, 0, matchStr.length())) {
            ss.setSpan(BOLD_SPAN,
                    prefixLen, spanEnd, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    /**
     * Truncate {@code text} so it fits within {@code maxLines} lines of width
     * {@code availWidth}. Returns the original text unchanged if it already fits.
     * Otherwise keeps the start and appends a single {@code '…'}.
     */
    @NonNull
    static String truncateToLines(@NonNull String text, int availWidth,
            @NonNull TextPaint paint, int maxLines) {
        if (text.length() == 0 || fitsLines(text, availWidth, paint, maxLines)) {
            return text;
        }
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

    private static StaticLayout buildLayout(@NonNull String text, int availWidth,
            @NonNull TextPaint paint, int maxLines) {
        StaticLayout layout;
        if (Build.VERSION.SDK_INT >= 23) {
            layout = StaticLayout.Builder.obtain(
                    text, 0, text.length(), paint, availWidth)
                    .setMaxLines(maxLines)
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
