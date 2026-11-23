package com.termux.app.terminal.io.autocomplete;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Handler;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The writer is single-threaded and generation-checked. A newer save must win even
 * when an older sink call is already in flight.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class HistoryPersistenceTest {

    private static HistoryDocument document(String epoch) {
        return new HistoryDocument(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyMap(), 1, epoch, false);
    }

    @Test
    public void anOlderInFlightWriteCannotLandAfterANewerSave() throws Exception {
        final CountDownLatch oldStarted = new CountDownLatch(1);
        final CountDownLatch releaseOld = new CountDownLatch(1);
        final List<String> writes = Collections.synchronizedList(new ArrayList<String>());
        HistoryPersistence.Sink sink = (doc, sync) -> {
            if ("old".equals(doc.epoch)) {
                oldStarted.countDown();
                try {
                    releaseOld.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            writes.add(doc.epoch);
            return true;
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        HistoryPersistence persistence = new HistoryPersistence(sink,
                new Handler(Looper.getMainLooper()), executor);
        try {
            assertTrue(persistence.writeNow(document("old"), false));
            assertTrue(oldStarted.await(2, TimeUnit.SECONDS));

            persistence.saveNow(() -> document("new"), false);
            releaseOld.countDown();

            // A synchronous save also acts as the drain barrier.
            persistence.saveNow(() -> document("new"), true);
            assertEquals("new", writes.get(writes.size() - 1));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void syncSaveReturnsOnlyAfterTheSinkCompleted() {
        final List<String> writes = Collections.synchronizedList(new ArrayList<String>());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        HistoryPersistence persistence = new HistoryPersistence((doc, sync) -> {
            writes.add(doc.epoch);
            return true;
        }, new Handler(Looper.getMainLooper()), executor);
        try {
            persistence.saveNow(() -> document("one"), true);
            assertEquals(Collections.singletonList("one"), writes);
        } finally {
            executor.shutdownNow();
        }
    }
}
