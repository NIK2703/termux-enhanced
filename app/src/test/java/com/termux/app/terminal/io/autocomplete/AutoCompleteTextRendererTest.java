package com.termux.app.terminal.io.autocomplete;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.StyleSpan;
import android.util.LruCache;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AutoCompleteTextRendererTest {

    @Test
    public void layoutCacheRetainsLongText() throws Exception {
        LruCache<?, ?> cache = layoutCache();
        cache.evictAll();

        TextPaint paint = new TextPaint();
        paint.setTextSize(42f);
        paint.setAntiAlias(true);

        String text = longText();

        // The key is longer than the old 64-unit budget. Before the sizing fix the
        // LruCache evicted this layout immediately and size() stayed 0.
        AutoCompleteTextRenderer.fitsLines(text, 600, paint, 2);
        assertTrue("layout cache must retain long-text layouts",
                cache.size() > 0);

        // The truncation result must be stable across calls. Robolectric's
        // StaticLayout/Paint shadows do not wrap by width, so the cut itself is a
        // device-level behavior and is not asserted here.
        String first = AutoCompleteTextRenderer.truncateToLines(text, 600, paint, 2);
        String second = AutoCompleteTextRenderer.truncateToLines(text, 600, paint, 2);
        assertEquals(first, second);
    }

    @Test
    public void leadingCutKeepsOnlyTheLastThreeLettersOfTheTypedWord() {
        TextPaint paint = paint();
        SpannableString rendered = AutoCompleteTextRenderer.buildSuggestionSpannable(
                "git checkout main", "git checkout", 0, paint);

        assertEquals("out main", rendered.toString());
        assertFalse(rendered.toString().startsWith("..."));

        StyleSpan bold = boldSpan(rendered);
        assertNotNull(bold);
        assertEquals(0, rendered.getSpanStart(bold));
        assertEquals(3, rendered.getSpanEnd(bold));

        List<FadeSpan> fades = fades(rendered);
        assertEquals(3, fades.size());
        assertFade(fades.get(0), 0, 1, 64);
        assertFade(fades.get(1), 1, 2, 128);
        assertFade(fades.get(2), 2, 3, 191);
    }

    @Test
    public void whileTypingAShortWordThePreviousWordIsShownFadedToItsLeft() {
        TextPaint paint = paint();
        SpannableString rendered = AutoCompleteTextRenderer.buildSuggestionSpannable(
                "git hello", "git he", 0, paint);

        assertEquals("git hello", rendered.toString());
        StyleSpan bold = boldSpan(rendered);
        assertNotNull(bold);
        assertEquals(4, rendered.getSpanStart(bold));
        assertEquals(6, rendered.getSpanEnd(bold));

        List<FadeSpan> fades = fades(rendered);
        assertEquals(3, fades.size());
        assertFade(fades.get(0), 0, 1, 64);
        assertFade(fades.get(1), 1, 2, 128);
        assertFade(fades.get(2), 2, 3, 191);
    }

    @Test
    public void firstWordKeepsItsFullTextAndStaysOpaque() {
        TextPaint paint = paint();
        SpannableString rendered = AutoCompleteTextRenderer.buildSuggestionSpannable(
                "git checkout", "git", 0, paint);

        assertEquals("git checkout", rendered.toString());
        assertTrue(fades(rendered).isEmpty());
    }

    @Test
    public void trailingSeparatorsDoNotConsumeTheKeptLetters() {
        TextPaint paint = paint();
        SpannableString rendered = AutoCompleteTextRenderer.buildSuggestionSpannable(
                "git checkout main", "git checkout ", 0, paint);

        assertEquals("out main", rendered.toString());
    }

    @Test
    public void whileTypingTheNextWordThePreviousWordIsTruncatedToItsLeft() {
        TextPaint paint = paint();
        SpannableString rendered = AutoCompleteTextRenderer.buildSuggestionSpannable(
                "git checkout main", "git checkout ma", 0, paint);

        // "checkout" is reduced to "out" and faded; the typed "ma" stays intact and bold.
        assertEquals("out main", rendered.toString());
        List<FadeSpan> fades = fades(rendered);
        assertEquals(3, fades.size());
        assertFade(fades.get(0), 0, 1, 64);
        assertFade(fades.get(1), 1, 2, 128);
        assertFade(fades.get(2), 2, 3, 191);

        StyleSpan bold = boldSpan(rendered);
        assertNotNull(bold);
        assertEquals(4, rendered.getSpanStart(bold));
        assertEquals(6, rendered.getSpanEnd(bold));
    }

    @Test
    public void whileTypingTheNextWordOneLetterStillShowsThePreviousWord() {
        TextPaint paint = paint();
        SpannableString rendered = AutoCompleteTextRenderer.buildSuggestionSpannable(
                "git checkout main", "git checkout m", 0, paint);

        assertEquals("out main", rendered.toString());
        StyleSpan bold = boldSpan(rendered);
        assertNotNull(bold);
        assertEquals(4, rendered.getSpanStart(bold));
        assertEquals(5, rendered.getSpanEnd(bold));
    }

    @Test
    public void fadeRangeChangeResetsTheRowEvenWhenTextAndBoldMatch() {
        TextPaint paint = paint();
        SpannableString one = AutoCompleteTextRenderer.buildSuggestionSpannable(
                "git hello", "git h", 0, paint);
        SpannableString two = AutoCompleteTextRenderer.buildSuggestionSpannable(
                "git hello", "git he", 0, paint);

        assertEquals(one.toString(), two.toString());
        assertFalse(AutoCompleteTextRenderer.sameRendering(one, two));
    }

    private static TextPaint paint() {
        TextPaint paint = new TextPaint();
        paint.setTextSize(42f);
        paint.setAntiAlias(true);
        return paint;
    }

    private static StyleSpan boldSpan(Spanned text) {
        for (StyleSpan span : text.getSpans(0, text.length(), StyleSpan.class)) {
            if (span.getStyle() == Typeface.BOLD) return span;
        }
        return null;
    }

    private static List<FadeSpan> fades(Spanned text) {
        List<FadeSpan> result = new ArrayList<>();
        for (CharacterStyle span : text.getSpans(0, text.length(), CharacterStyle.class)) {
            TextPaint paint = new TextPaint();
            paint.setAlpha(255);
            span.updateDrawState(paint);
            if (paint.getAlpha() != 255) {
                result.add(new FadeSpan(text.getSpanStart(span), text.getSpanEnd(span),
                        paint.getAlpha()));
            }
        }
        result.sort(Comparator.comparingInt(span -> span.start));
        return result;
    }

    private static void assertFade(FadeSpan span, int start, int end, int alpha) {
        assertEquals(start, span.start);
        assertEquals(end, span.end);
        assertEquals(alpha, span.alpha);
    }

    private static final class FadeSpan {
        final int start;
        final int end;
        final int alpha;

        FadeSpan(int start, int end, int alpha) {
            this.start = start;
            this.end = end;
            this.alpha = alpha;
        }
    }

    private static String longText() {
        StringBuilder sb = new StringBuilder(1400);
        for (int i = 0; i < 24; i++) {
            sb.append("sentence ").append(i)
                    .append(" has some longer words and details. ");
        }
        return sb.toString();
    }

    private static LruCache<?, ?> layoutCache() throws Exception {
        Field field = AutoCompleteTextRenderer.class.getDeclaredField("sLayoutCache");
        field.setAccessible(true);
        LruCache<?, ?> cache = (LruCache<?, ?>) field.get(null);
        assertNotNull(cache);
        return cache;
    }
}
