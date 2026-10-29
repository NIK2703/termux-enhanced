package com.termux.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression tests for the ASCII fast path in {@link TerminalRenderer}.
 *
 * <p>Skipping measurement is only safe for plain text. A bold or italic run can resolve to a
 * different face whose advance differs from {@code widthColumns * mFontWidth}, and drawing it
 * unmeasured squeezes the run into the grid (italics were reported as compressed in omp).</p>
 */
public class TerminalRendererAsciiFastPathTest {

    @Test
    public void plainAsciiRunUsesFastPath() {
        assertTrue(TerminalRenderer.asciiFastPathAllowed(false, false, 10, 10));
    }

    @Test
    public void styledRunsAreMeasured() {
        assertFalse("italic may select a proportional face",
            TerminalRenderer.asciiFastPathAllowed(false, true, 10, 10));
        assertFalse("bold may select a face with a different advance",
            TerminalRenderer.asciiFastPathAllowed(true, false, 10, 10));
        assertFalse(TerminalRenderer.asciiFastPathAllowed(true, true, 10, 10));
    }

    @Test
    public void irregularRunsAreMeasured() {
        assertFalse("wide or combining cells need the cluster path",
            TerminalRenderer.asciiFastPathAllowed(false, false, 4, 8));
        assertFalse("an empty run has nothing to fast-path",
            TerminalRenderer.asciiFastPathAllowed(false, false, 0, 0));
    }
}
