package com.termux.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression tests for the font-width-mismatch scale in {@link TerminalRenderer}.
 *
 * <p>Both factors divide by {@code mes}, the sum of the per-code-point advances the measure cache
 * holds. A run of code points that measure nothing gives {@code mes == 0}, the scale becomes
 * {@code Infinity}, and {@code canvas.scale()} does not reject a non-finite factor, so the canvas
 * is left holding a matrix where every subsequent draw is undefined. The visible symptom was a
 * Devanagari cluster painted at the left edge of the grid on every row that contained a Hindi vowel
 * sign (U+093E/093F/0940 measured ~0 in the fallback font).</p>
 */
public class TerminalRendererMismatchScaleTest {

    @Test
    public void zeroMeasuredWidthIsNotScalable() {
        assertFalse("mes == 0 divides by zero into an infinite scale",
            TerminalRenderer.isMismatchScaleUsable(1, 0f));
        assertFalse("mes == 0 is degenerate for a multi-column run too",
            TerminalRenderer.isMismatchScaleUsable(7, 0f));
    }

    @Test
    public void vanishingMeasuredWidthIsNotScalable() {
        assertFalse("a run measured 1000x narrower than its cells is not a glyph",
            TerminalRenderer.isMismatchScaleUsable(1, 0.001f));
        assertFalse(TerminalRenderer.isMismatchScaleUsable(10, 0.01f));
    }

    @Test
    public void zeroWidthRunIsNotScalable() {
        assertFalse("runWidthColumns == 0 would also make mes / runWidthColumns infinite",
            TerminalRenderer.isMismatchScaleUsable(0, 1f));
    }

    @Test
    public void notANumberIsNotScalable() {
        assertFalse(TerminalRenderer.isMismatchScaleUsable(1, Float.NaN));
        assertFalse(TerminalRenderer.isMismatchScaleUsable(1, Float.POSITIVE_INFINITY));
    }

    /**
     * The ordinary cases must keep working: a box-drawing character or a CJK glyph from a fallback
     * font, drawn at a different advance than its cells, is squeezed by a small factor.
     */
    @Test
    public void ordinaryMismatchesRemainScalable() {
        // 18 px measured for a 16 px cell => one cell measured 1.125 cells.
        assertTrue(TerminalRenderer.isMismatchScaleUsable(1, 1.125f));
        // A wide glyph measured 2.5 cells for its 2 cells.
        assertTrue(TerminalRenderer.isMismatchScaleUsable(2, 2.5f));
        // Measured a third narrower than its cells, which is the squeeze direction.
        assertTrue(TerminalRenderer.isMismatchScaleUsable(3, 2f));
        // Exactly at the bounds is still usable.
        assertTrue(TerminalRenderer.isMismatchScaleUsable(8, 1f));
        assertTrue(TerminalRenderer.isMismatchScaleUsable(1, 8f));
    }

    /** Past a bound the run is simply drawn as it is, inside the row clip. */
    @Test
    public void pastTheBoundsIsNotScalable() {
        assertFalse(TerminalRenderer.isMismatchScaleUsable(9, 1f));    // 9x magnification
        assertFalse(TerminalRenderer.isMismatchScaleUsable(1, 9f));    // 9x squeeze
    }
}
