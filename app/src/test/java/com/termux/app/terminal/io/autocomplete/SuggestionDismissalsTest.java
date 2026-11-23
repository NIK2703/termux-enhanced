package com.termux.app.terminal.io.autocomplete;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Field;
import java.util.Collections;

/** Dismissal identity: full-bit coverage, merge remap, retain and epoch binding. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SuggestionDismissalsTest {

    private SharedPreferences prefs;
    private SuggestionDismissals dismissals;

    @Before
    public void setUp() throws Exception {
        resetSingleton();
        prefs = RuntimeEnvironment.getApplication()
                .getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        dismissals = new SuggestionDismissals(prefs);
        dismissals.bindEpoch("epoch-1");
    }

    @Test
    public void fullBitCoversEverySentenceWindow() {
        assertTrue(dismissals.mark(7, 0));
        assertTrue(dismissals.isDismissed(7, 0));
        assertTrue(dismissals.isDismissed(7, 1));
        assertTrue(dismissals.isDismissed(7, 9));
        assertFalse(dismissals.isDismissed(8, 0));
    }

    @Test
    public void remapOrsBitsIntoTheSurvivingRecordAndRemovesTheOldId() {
        dismissals.mark(1, 0);
        dismissals.mark(2, 3);

        dismissals.remap(Collections.singletonMap(1L, 2L));

        assertFalse(dismissals.isDismissed(1, 0));
        assertTrue(dismissals.isDismissed(2, 0));
        assertTrue(dismissals.isDismissed(2, 3));
    }

    @Test
    public void retainIdsDropsOnlyRecordsThatNoLongerExist() {
        dismissals.mark(1, 0);
        dismissals.mark(2, 1);

        dismissals.retainIds(Collections.singleton(2L));

        assertFalse(dismissals.isDismissed(1, 0));
        assertTrue(dismissals.isDismissed(2, 1));
    }

    @Test
    public void flagsRoundTripUnderTheSameEpoch() {
        dismissals.dismiss(3, 0);
        dismissals.dismiss(3, 4);

        SuggestionDismissals reloaded = new SuggestionDismissals(prefs);
        reloaded.bindEpoch("epoch-1");
        assertTrue(reloaded.isDismissed(3, 0));
        assertTrue(reloaded.isDismissed(3, 4));
    }

    @Test
    public void bindingADifferentEpochDiscardsOldIds() {
        dismissals.dismiss(3, 0);

        SuggestionDismissals reloaded = new SuggestionDismissals(prefs);
        reloaded.bindEpoch("epoch-2");

        assertFalse(reloaded.isDismissed(3, 0));
        assertTrue("stale flags are removed from disk",
                prefs.getString("autocomplete_dismissed_suggestions_v2", null) == null);
    }

    @Test
    public void corruptBitIndicesAreIgnored() {
        prefs.edit().putString("autocomplete_dismissed_suggestions_v2",
                "{\"v\":2,\"epoch\":\"epoch-1\",\"sentenceModel\":"
                        + SuggestionDismissals.SENTENCE_MODEL
                        + ",\"dismissed\":{\"1\":[-5,999999]}}").commit();

        SuggestionDismissals reloaded = new SuggestionDismissals(prefs);
        reloaded.bindEpoch("epoch-1");
        assertFalse(reloaded.isDismissed(1, 0));
        assertFalse(reloaded.isDismissed(1, 999999));
    }

    private static void resetSingleton() throws Exception {
        Field shared = MessageHistoryController.class.getDeclaredField("sShared");
        shared.setAccessible(true);
        shared.set(null, null);
    }
}
