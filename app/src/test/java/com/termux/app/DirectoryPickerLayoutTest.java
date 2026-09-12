package com.termux.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.app.terminal.DirectoryPickerLayout;
import com.termux.app.terminal.DirectoryPickerLayout.Mode;
import com.termux.app.terminal.DirectoryPickerLayout.Result;

import org.junit.Test;

/**
 * Placement model of the right-swipe directory picker.
 *
 * <p>The numbers mirror a real phone page (1080×2400, page height ≈ 2000 px, density 2.75):
 * pad 8 dp, gap 12 dp, hint padding 8 dp, measured hint ≈ 160 px and a fixed 48 dp row.
 */
public class DirectoryPickerLayoutTest {

    private static final float PAGE_H = 2000f;
    private static final float PAD = 24f;
    private static final float GAP = 36f;
    private static final float HINT_PAD = 24f;
    private static final float HINT_H = 160f;
    /** 48 dp at density 2.75 — fixed, never scaled. */
    private static final float ROW_H = 132f;

    private static final float CENTRED_HINT = (PAGE_H - HINT_H) / 2f;

    private static Result layout(float anchorY, int items) {
        return layout(PAGE_H, anchorY, items);
    }

    private static Result layout(float pageHeight, float anchorY, int items) {
        return DirectoryPickerLayout.compute(pageHeight, PAD, GAP, HINT_PAD, anchorY, items,
                HINT_H, ROW_H);
    }

    // ── the list is always above the finger ─────────────────────────────────────────────────

    @Test
    public void listAlwaysEndsOneGapAboveTheFinger() {
        for (float anchorY = 0f; anchorY <= PAGE_H; anchorY += 5f) {
            Result r = layout(anchorY, 10);
            if (!r.hasList()) continue;
            assertEquals("list bottom must sit one gap above the finger (yA=" + anchorY + ")",
                    anchorY - GAP, r.listBottom, 0.01f);
            assertTrue("the whole list must clear the finger", r.listBottom < anchorY);
        }
    }

    @Test
    public void thereIsNeverADownwardPlacement() {
        for (float anchorY = 0f; anchorY <= PAGE_H; anchorY += 5f) {
            Result r = layout(anchorY, 10);
            if (r.mode == Mode.NONE) continue;
            assertEquals(Mode.UP, r.mode);
        }
    }

    // ── truncation, down to nothing ─────────────────────────────────────────────────────────

    @Test
    public void fingerHighEnoughForOneRow_stillShowsTheList() {
        // availUp = 132 at yA = 436 — exactly one row.
        Result r = layout(436f, 10);
        assertEquals(Mode.UP, r.mode);
        assertEquals(1, r.rows);
    }

    @Test
    public void fingerTooHighForEvenOneRow_showsNoListAndCentresTheHint() {
        Result r = layout(435f, 10);
        assertEquals(Mode.NONE, r.mode);
        assertFalse(r.hasList());
        assertEquals(0, r.rows);
        assertEquals("the hint returns to the page centre", CENTRED_HINT, r.hintTop, 0.01f);
    }

    @Test
    public void rowsAreTruncatedToWhatFitsAbove() {
        assertEquals(1, layout(436f, 10).rows);
        assertEquals(5, layout(964f, 10).rows);
        assertEquals(10, layout(1624f, 10).rows);
    }

    @Test
    public void entriesAreNeverMoreNumerousThanOffered() {
        for (int items = 1; items <= 10; items++) {
            for (float anchorY = 0f; anchorY <= PAGE_H; anchorY += 5f) {
                assertTrue("more rows than entries offered (n=" + items + ")",
                        layout(anchorY, items).rows <= items);
            }
        }
    }

    @Test
    public void rowHeightIsAlwaysTheFixedHeight() {
        for (int items = 1; items <= 10; items++) {
            for (float anchorY = 0f; anchorY <= PAGE_H; anchorY += 5f) {
                Result r = layout(anchorY, items);
                if (!r.hasList()) continue;
                assertEquals("rows must never be scaled (yA=" + anchorY + ", n=" + items + ")",
                        ROW_H, r.rowHeight, 0.01f);
            }
        }
    }

    // ── orientation: the newest entry is the one nearest the finger ─────────────────────────

    @Test
    public void newestEntryIsDrawnOnTheRowNearestTheFinger() {
        Result r = layout(1500f, 10);
        assertTrue(r.hasList());
        // The list hangs above the finger, so the last row is the closest one.
        assertEquals("the newest entry goes on the bottom row", 0, r.itemIndexAt(r.rows - 1));
        assertEquals("the oldest offered entry goes on the top row", r.rows - 1, r.itemIndexAt(0));
    }

    @Test
    public void itemMappingCoversEveryRowExactlyOnce() {
        Result r = layout(1500f, 10);
        final boolean[] seen = new boolean[r.rows];
        for (int row = 0; row < r.rows; row++) {
            final int item = r.itemIndexAt(row);
            assertTrue("item index out of range", item >= 0 && item < r.rows);
            assertFalse("item index used twice", seen[item]);
            seen[item] = true;
        }
        assertEquals("no entry may be dropped by the mapping", -1, r.itemIndexAt(r.rows));
        assertEquals(-1, r.itemIndexAt(-1));
    }

    // ── invariants across the whole anchor axis ─────────────────────────────────────────────

    @Test
    public void hintIsAlwaysAboveTheList() {
        for (int items = 1; items <= 10; items++) {
            for (float anchorY = 0f; anchorY <= PAGE_H; anchorY += 5f) {
                Result r = layout(anchorY, items);
                if (!r.hasList()) continue;
                assertTrue("hint must not overlap the list (yA=" + anchorY + ", n=" + items + ")",
                        r.hintTop + r.hintHeight <= r.listTop + 0.01f);
            }
        }
    }

    @Test
    public void hintStaysInsideThePage() {
        for (int items = 1; items <= 10; items++) {
            for (float anchorY = 0f; anchorY <= PAGE_H; anchorY += 5f) {
                Result r = layout(anchorY, items);
                assertTrue("hint top must stay inside the page (yA=" + anchorY + ")",
                        r.hintTop >= PAD - 0.01f);
                assertTrue("hint must not run past the bottom (yA=" + anchorY + ")",
                        r.hintTop + r.hintHeight <= PAGE_H - PAD + 0.01f);
            }
        }
    }

    @Test
    public void listFitsOnThePageForEveryAnchor() {
        for (int items = 1; items <= 10; items++) {
            for (float anchorY = 0f; anchorY <= PAGE_H; anchorY += 5f) {
                Result r = layout(anchorY, items);
                if (!r.hasList()) continue;
                assertTrue("list starts above the top inset (yA=" + anchorY + ", n=" + items + ")",
                        r.listTop >= PAD - 0.01f);
                assertTrue("list runs past the bottom edge (yA=" + anchorY + ", n=" + items + ")",
                        r.listBottom <= PAGE_H - PAD + 0.01f);
            }
        }
    }

    // ── hit-testing ─────────────────────────────────────────────────────────────────────────

    @Test
    public void hitTestSelectsTheRowUnderTheFinger() {
        Result r = layout(1500f, 10);
        assertTrue(r.hasList());
        for (int i = 0; i < r.rows; i++) {
            float centre = r.listTop + (i + 0.5f) * r.rowHeight;
            assertEquals(i, r.indexAt(centre));
        }
    }

    @Test
    public void hitTestExcludesEverythingOutsideTheRows() {
        Result r = layout(1500f, 10);
        assertEquals("above the list", -1, r.indexAt(r.listTop - 1f));
        assertEquals("the gap below the list", -1, r.indexAt(r.listBottom + 1f));
        assertEquals("the neutral zone at the anchor", -1, r.indexAt(1500f));
        assertEquals("the hint band", -1, r.indexAt(r.hintTop + r.hintHeight / 2f));
        assertEquals("the top edge", -1, r.indexAt(0f));
        assertEquals("the bottom edge", -1, r.indexAt(PAGE_H));
    }

    @Test
    public void hitTestIsEmptyWhenNoListIsShown() {
        Result r = layout(100f, 10);
        assertEquals(Mode.NONE, r.mode);
        for (float y = 0f; y <= PAGE_H; y += 25f) {
            assertEquals("nothing is selectable without a list (y=" + y + ")", -1, r.indexAt(y));
        }
    }

    @Test
    public void hitTestOnTheLastRowStopsAtTheListBottom() {
        Result r = layout(1500f, 4);
        assertTrue(r.hasList());
        assertEquals(3, r.indexAt(r.listBottom - 0.5f));
        assertEquals(-1, r.indexAt(r.listBottom + 0.5f));
    }

    @Test
    public void hitTestIgnoresRowsThatWereTruncatedAway() {
        // Only 5 entries are offered, so row 5 does not exist even though the space would continue.
        Result r = layout(964f, 10);
        assertEquals(5, r.rows);
        assertEquals(4, r.indexAt(r.listTop + 4.5f * r.rowHeight));
        assertEquals("past the last offered entry", -1, r.indexAt(r.listTop + 5.5f * r.rowHeight));
    }

    // ── degenerate pages ────────────────────────────────────────────────────────────────────

    @Test
    public void shortPageCentresTheHintAndShowsNoList() {
        Result r = layout(700f, 300f, 10);
        assertEquals(Mode.NONE, r.mode);
        assertEquals((700f - HINT_H) / 2f, r.hintTop, 0.01f);
    }

    @Test
    public void zeroHeightPageDoesNotCrash() {
        Result r = DirectoryPickerLayout.compute(0f, PAD, GAP, HINT_PAD, 0f, 5, HINT_H, ROW_H);
        assertEquals(Mode.NONE, r.mode);
    }

    @Test
    public void noHistoryDoesNotCrash() {
        Result r = DirectoryPickerLayout.compute(PAGE_H, PAD, GAP, HINT_PAD, 1500f, 0, HINT_H,
                ROW_H);
        assertEquals(Mode.NONE, r.mode);
    }
}
