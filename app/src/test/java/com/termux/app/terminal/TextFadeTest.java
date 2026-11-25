package com.termux.app.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TextFadeTest {

    @Test
    public void lastThreeCharactersFadeFrom75To25Percent() {
        assertEquals(191, TextFade.alphaForStep(3));
        assertEquals(128, TextFade.alphaForStep(2));
        assertEquals(64, TextFade.alphaForStep(1));
    }

    @Test
    public void charactersBeforeTheTailStayOpaque() {
        assertEquals(255, TextFade.alphaForStep(4));
        assertEquals(255, TextFade.alphaForStep(0));
    }

    @Test
    public void tailFadeUsesTheTitleAlphaSteps() {
        final SpannableString text = new SpannableString("abcdef");
        TextFade.fadeTail(text, text.length());

        final List<FadeSpan> fades = fades(text);
        assertEquals(3, fades.size());
        assertFade(fades.get(0), 3, 4, 191);
        assertFade(fades.get(1), 4, 5, 128);
        assertFade(fades.get(2), 5, 6, 64);
    }

    @Test
    public void headFadeUses15To45Percent() {
        assertEquals(38, TextFade.headAlphaForStep(1));
        assertEquals(77, TextFade.headAlphaForStep(2));
        assertEquals(115, TextFade.headAlphaForStep(3));

        final SpannableString text = new SpannableString("abcdef");
        TextFade.fadeHead(text, 0, text.length());

        final List<FadeSpan> fades = fades(text);
        assertEquals(3, fades.size());
        assertFade(fades.get(0), 0, 1, 38);
        assertFade(fades.get(1), 1, 2, 77);
        assertFade(fades.get(2), 2, 3, 115);
    }

    @Test
    public void tailFadeSkipsWhitespaceWhenChoosingTheLastThreeCharacters() {
        final SpannableString faded = new SpannableString("abc de");
        TextFade.fadeTail(faded, faded.length());

        final List<FadeSpan> fades = fades(faded);
        assertEquals(3, fades.size());
        assertFade(fades.get(0), 2, 3, 191);
        assertFade(fades.get(1), 4, 5, 128);
        assertFade(fades.get(2), 5, 6, 64);
    }

    @Test
    public void headFadeSkipsWhitespaceWhenChoosingTheFirstThreeCharacters() {
        final SpannableString faded = new SpannableString("ab cd");
        TextFade.fadeHead(faded, 0, faded.length());

        final List<FadeSpan> fades = fades(faded);
        assertEquals(3, fades.size());
        assertFade(fades.get(0), 0, 1, 38);
        assertFade(fades.get(1), 1, 2, 77);
        assertFade(fades.get(2), 3, 4, 115);
    }

    @Test
    public void sameFadesComparesPositionsAndAlpha() {
        final SpannableString left = new SpannableString("abcdef");
        final SpannableString right = new SpannableString("abcdef");
        TextFade.fadeTail(left, left.length());
        TextFade.fadeTail(right, right.length());
        assertTrue(TextFade.sameFades(left, right));

        TextFade.fadeHead(right, 0, right.length());
        assertFalse(TextFade.sameFades(left, right));
    }

    private static List<FadeSpan> fades(Spanned text) {
        final List<FadeSpan> result = new ArrayList<>();
        for (CharacterStyle span : text.getSpans(0, text.length(), CharacterStyle.class)) {
            final TextPaint paint = new TextPaint();
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
}
