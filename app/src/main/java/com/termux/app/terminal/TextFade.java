package com.termux.app.terminal;

import android.text.Spannable;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.widget.TextView;

import androidx.annotation.NonNull;

/**
 * Shared alpha-fade truncation used by tab titles, auto-complete rows and history popups.
 *
 * <p>Instead of an ellipsis, the characters at a cut edge are drawn with decreasing
 * opacity: the outermost visible character keeps 25%, the next 50%, the third 75%.
 * Everything further in stays opaque.</p>
 */
public final class TextFade {

    /** Number of visible code points that fade at a cut edge. */
    public static final int FADE_CHARS = 3;

    private TextFade() {}

    /**
     * Applies the fade-only truncation contract to a text view: no platform ellipsis may
     * be drawn over text this class has already faded.
     */
    public static void applyFadeContract(@NonNull TextView view) {
        view.setEllipsize(null);
        view.setHorizontallyScrolling(false);
    }

    /** Alpha for a character {@code step} code points from a cut edge (1 = outermost). */
    public static int alphaForStep(int step) {
        switch (step) {
            case 1: return 64;   // 25%
            case 2: return 128;  // 50%
            case 3: return 191;  // 75%
            default: return 255;
        }
    }

    /**
     * Fades up to {@link #FADE_CHARS} visible code points at the end of
     * {@code text[0, endExclusive)}. Whitespace and line breaks are skipped: they neither
     * fade nor consume a fade step.
     */
    public static void fadeTail(@NonNull Spannable text, int endExclusive) {
        int spanEnd = Math.min(endExclusive, text.length());
        int faded = 0;
        while (faded < FADE_CHARS && spanEnd > 0) {
            final int spanStart = Character.offsetByCodePoints(text, spanEnd, -1);
            final char last = text.charAt(spanStart);
            if (last == '\n' || last == '\r') break;
            if (!Character.isWhitespace(last) && !Character.isSpaceChar(last)) {
                faded++;
                text.setSpan(new AlphaSpan(alphaForStep(faded)),
                        spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            spanEnd = spanStart;
        }
    }

    /**
     * Fades up to {@link #FADE_CHARS} visible code points at the start of
     * {@code text[start, endExclusive)}. This is the mirror image of {@link #fadeTail}:
     * the first visible code point is the faintest.
     */
    public static void fadeHead(@NonNull Spannable text, int start, int endExclusive) {
        int spanStart = Math.max(0, start);
        final int limit = Math.min(endExclusive, text.length());
        int faded = 0;
        while (faded < FADE_CHARS && spanStart < limit) {
            final int spanEnd = Character.offsetByCodePoints(text, spanStart, 1);
            final char first = text.charAt(spanStart);
            if (first == '\n' || first == '\r') break;
            if (!Character.isWhitespace(first) && !Character.isSpaceChar(first)) {
                faded++;
                text.setSpan(new AlphaSpan(alphaForStep(faded)),
                        spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            spanStart = spanEnd;
        }
    }

    /** True when both sequences carry the same alpha-fade spans. */
    public static boolean sameFades(@NonNull Spanned left, @NonNull Spanned right) {
        final AlphaSpan[] leftSpans = left.getSpans(0, left.length(), AlphaSpan.class);
        final AlphaSpan[] rightSpans = right.getSpans(0, right.length(), AlphaSpan.class);
        if (leftSpans.length != rightSpans.length) return false;
        final boolean[] matched = new boolean[rightSpans.length];
        for (AlphaSpan leftSpan : leftSpans) {
            boolean found = false;
            for (int i = 0; i < rightSpans.length; i++) {
                if (matched[i]) continue;
                final AlphaSpan rightSpan = rightSpans[i];
                if (left.getSpanStart(leftSpan) == right.getSpanStart(rightSpan)
                        && left.getSpanEnd(leftSpan) == right.getSpanEnd(rightSpan)
                        && leftSpan.mAlpha == rightSpan.mAlpha) {
                    matched[i] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    /** Multiplies paint alpha so the text colour and its other styles remain intact. */
    private static final class AlphaSpan extends CharacterStyle {
        private final int mAlpha;

        AlphaSpan(int alpha) {
            mAlpha = alpha;
        }

        @Override
        public void updateDrawState(TextPaint tp) {
            tp.setAlpha(tp.getAlpha() * mAlpha / 255);
        }
    }
}
