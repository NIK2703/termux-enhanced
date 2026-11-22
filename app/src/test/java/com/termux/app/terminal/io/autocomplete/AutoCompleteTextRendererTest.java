package com.termux.app.terminal.io.autocomplete;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.text.TextPaint;
import android.util.LruCache;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Field;

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
        // StaticLayout/Paint shadows do not wrap by width, so the '…' itself is a
        // device-level behavior and is not asserted here.
        String first = AutoCompleteTextRenderer.truncateToLines(text, 600, paint, 2);
        String second = AutoCompleteTextRenderer.truncateToLines(text, 600, paint, 2);
        assertEquals(first, second);
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
