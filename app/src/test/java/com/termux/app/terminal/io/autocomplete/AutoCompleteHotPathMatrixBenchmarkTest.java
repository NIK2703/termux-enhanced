package com.termux.app.terminal.io.autocomplete;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.SpannableString;
import android.text.TextPaint;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.termux.app.terminal.TermuxColorSchemeManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dense hot-path matrix for the history-backed two-tier autocomplete.
 *
 * <p>Run explicitly (skipped in the normal suite):
 * <pre>
 * ./gradlew :app:testDebugUnitTest -PautocompleteBench=true \
 *     --tests '*AutoCompleteHotPathMatrixBenchmarkTest*'
 * </pre>
 *
 * <p>The matrix separates four questions that a single "type a string" number
 * cannot answer:
 *
 * <ol>
 *   <li><b>Engine</b>: how much work is the pure scan/filter/render path doing, and
 *       does the filter stay O(maxCount) as history grows?</li>
 *   <li><b>Dismissals</b>: what does the new id/BitSet layer cost inside the scan,
 *       and does a nearly fully dismissed history degrade the scan?</li>
 *   <li><b>Controller</b>: what is the real per-keystroke cost after adding the
 *       prefix copies, popup row rebinding and geometry update?</li>
 *   <li><b>Snapshot</b>: how much does a history-version rebuild cost, and is the
 *       parsed-span cache doing its job?</li>
 * </ol>
 *
 * <p>The pure JVM sections use the real engine code with synthetic histories of
 * 100, 500 and 1000 entries (the production retention limit). The controller
 * section runs the real {@link AutoCompleteController} under Robolectric, so its
 * absolute numbers include the test {@link EditText}/popup plumbing; they are
 * comparable across sizes and between before/after revisions, not to a device.
 *
 * <p>This is report-only. Do not add wall-clock assertions here: JIT, GC and the
 * Robolectric host make them flaky. Accept an optimization only when the same host
 * shows a repeatable improvement in p50/p95 and, where relevant, allocation.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AutoCompleteHotPathMatrixBenchmarkTest {

    private static final String HOME = "/home/user";
    private static final int MAX = 10;
    private static final int SENTENCES = 6;
    private static final int[] SIZES = {100, 500, 1000};
    private static final int STRESS_SIZE = 5000;
    private static final int CONTROLLER_SAMPLES = 80;

    private AutoCompleteBenchmarkHarness mBench;
    private PrintWriter mTsv;
    private File mTsvFile;

    @Test
    public void benchmarkHotPathMatrix() throws Exception {
        assumeTrue(Boolean.getBoolean("autocomplete.bench"));
        openReport();
        try {
            printHostInfo();
            warmUpHotPaths();
            runEngineMatrix();
            runDismissalMatrix();
            runControllerMatrix();
            System.out.println("(sink=" + AutoCompleteBenchmarkHarness.sink() + ")");
            System.out.println("TSV: " + mTsvFile.getAbsolutePath());
        } finally {
            if (mTsv != null) mTsv.close();
            resetSingleton();
        }
        assertTrue("the benchmark executed operations", AutoCompleteBenchmarkHarness.sink() != 0);
    }

    private void openReport() {
        String requested = System.getProperty("autocomplete.bench.out");
        mTsvFile = requested != null && !requested.isEmpty()
                ? new File(requested)
                : new File("build/reports/autocomplete-hotpath-bench.tsv");
        File parent = mTsvFile.getParentFile();
        if (parent != null) parent.mkdirs();
        try {
            mTsv = new PrintWriter(new FileWriter(mTsvFile));
        } catch (Exception e) {
            throw new AssertionError("cannot open benchmark report " + mTsvFile, e);
        }
        mBench = new AutoCompleteBenchmarkHarness(mTsv, true);
    }

    private static void printHostInfo() {
        System.out.println();
        System.out.println("autocomplete hot-path matrix");
        System.out.println("java=" + System.getProperty("java.version")
                + " os=" + System.getProperty("os.name")
                + " cpus=" + Runtime.getRuntime().availableProcessors()
                + " maxHeapMB=" + (Runtime.getRuntime().maxMemory() >> 20));
        System.out.println("sizes=" + java.util.Arrays.toString(SIZES)
                + " stress=" + STRESS_SIZE + " maxSuggestions=" + MAX);
    }

    // ── Engine / snapshot matrix ──

    /**
     * JIT and Robolectric need a full pass before the first reported row. Without
     * this the N=100 row is measured while C2 is still compiling the scan, which
     * made the smallest history look slower than the 1000-entry one.
     */
    private void warmUpHotPaths() throws Exception {
        final List<String> history = buildHistory(1000, SENTENCES);
        final Map<String, HistorySnapshot.Spans> cache = new HashMap<>();
        final HistorySnapshot snap = HistorySnapshot.build(1, history, cache);
        final List<SuggestionEngine.Candidate> keepBase = scan(snap, "run task: first", MAX);
        final int keepLen = "run task: first".length();
        final String typed = "run task: first sentence number 0. sentence 1";
        final List<SuggestionEngine.Candidate> tailBase = scan(snap, typed, MAX);
        for (int i = 0; i < 100; i++) {
            HistorySnapshot.build(1, history, cache);
            scan(snap, "zzzzzz", MAX);
            scan(snap, "run task: first", MAX);
            scan(snap, typed, MAX);
            SuggestionEngine.filter(keepBase, snap, keepLen, keepLen, ' ');
            matchesTail(tailBase, snap, typed);
            HistorySnapshot.parseSpans(history.get(0));
        }
        // Warm every controller size before the first reported controller row.
        // Otherwise the smallest history is measured while Robolectric and C2 are
        // still warming, which made N=100 look slower than N=1000.
        for (int n : SIZES) {
            Fixture warm = createFixture(n);
            try {
                final String base = "run task: first";
                for (int i = 0; i < 15; i++) {
                    warm.input.setText(base);
                    warm.input.setSelection(base.length());
                    warm.controller.onCaretMoved();
                    settleUi();
                    warm.input.getText().append(' ');
                    warm.controller.onCaretMoved();
                    if (warm.controller.isShowing()) warm.controller.dismiss();
                }
            } finally {
                warm.close();
            }
        }
    }

    private void runEngineMatrix() {
        final TextPaint paint = new TextPaint();
        paint.setTextSize(42f);
        paint.setAntiAlias(true);

        for (int n : SIZES) {
            final List<String> history = buildHistory(n, SENTENCES);
            final Map<String, HistorySnapshot.Spans> cache = new HashMap<>();
            final HistorySnapshot snap = HistorySnapshot.build(1, history, cache);

            mBench.measureBatch("snapshot", "cold", n, 12, 5, 2,
                    () -> HistorySnapshot.build(1, history, new HashMap<String, HistorySnapshot.Spans>())
                            .entries.length);
            mBench.measureBatch("snapshot", "warm", n, 500, 7, 2,
                    () -> HistorySnapshot.build(1, history, cache).entries.length);

            final String typed = "run task: first sentence number 0. sentence 1";
            mBench.measureBatch("syntax", "activeLineStart", n, 20000, 7, 2,
                    () -> SuggestionEngine.activeLineStart(typed, typed.length()));
            mBench.measureBatch("syntax", "activeSentenceStart", n, 20000, 7, 2,
                    () -> SuggestionEngine.activeSentenceStart(typed, typed.length()));
            mBench.measureBatch("syntax", "parseSpans", n, 1000, 7, 2,
                    () -> HistorySnapshot.parseSpans(history.get(0)).firstLineEnd);
            mBench.measureBatch("syntax", "parseSentences", n, 1000, 7, 2,
                    () -> HistorySnapshot.parseSentences(history.get(0)).count);

            final SuggestionEngine.ScanPos pos = new SuggestionEngine.ScanPos();
            final List<SuggestionEngine.Candidate> out = new ArrayList<>();
            final Set<String> seen = new HashSet<>();
            mBench.measureBatch("engine", "scan.noMatch", n, 100, 9, 2, () -> {
                out.clear();
                pos.reset();
                seen.clear();
                scanInto(snap, "zzzzzz", MAX, pos, out, seen);
                return out.size();
            });
            mBench.measureBatch("engine", "scan.full10", n, 100, 7, 2, () -> {
                out.clear();
                pos.reset();
                seen.clear();
                scanInto(snap, "run task: first", MAX, pos, out, seen);
                return out.size();
            });
            mBench.measureBatch("engine", "scan.sentence", n, 50, 7, 2, () -> {
                out.clear();
                pos.reset();
                seen.clear();
                scanInto(snap, typed, MAX, pos, out, seen);
                return out.size();
            });
            mBench.measureBatch("engine", "scan.sentencesOff", n, 50, 7, 2, () -> {
                out.clear();
                pos.reset();
                seen.clear();
                scanInto(snap, typed, false, MAX, pos, out, seen);
                return out.size();
            });

            final List<SuggestionEngine.Candidate> keepBase = scan(snap, "run task: first", MAX);
            final int keepLen = "run task: first".length();
            assertFalse(keepBase.isEmpty());
            mBench.measureBatch("engine", "filter.keep10", n, 20000, 7, 2, () -> {
                SuggestionEngine.filter(keepBase, snap, keepLen, keepLen, ' ');
                return keepBase.size();
            });

            final String topUpBase = "run task: first sentence number 1";
            final List<SuggestionEngine.Candidate> topUpOut = new ArrayList<>();
            final SuggestionEngine.ScanPos topUpPos = new SuggestionEngine.ScanPos();
            scanInto(snap, topUpBase, MAX, topUpPos, topUpOut, new HashSet<String>());
            final String topUpNext = topUpBase + "0";
            final int topUpLineLen = topUpBase.length();
            mBench.measureBatch("engine", "filter.topup", n, 100, 7, 2, () -> {
                List<SuggestionEngine.Candidate> copy = new ArrayList<>(topUpOut);
                SuggestionEngine.ScanPos p = copyPos(topUpPos);
                Set<String> keys = new HashSet<>();
                SuggestionEngine.filter(copy, snap, topUpLineLen, topUpLineLen, '0');
                for (int i = 0; i < copy.size(); i++) {
                    if (copy.get(i).kind == SuggestionEngine.Kind.SENTENCE) {
                        keys.add(copy.get(i).text);
                    }
                }
                scanInto(snap, topUpNext, MAX, p, copy, keys);
                return copy.size();
            });

            final List<SuggestionEngine.Candidate> tailBase = scan(snap, typed, MAX);
            mBench.measureBatch("engine", "matchesTail.10", n, 5000, 7, 2,
                    () -> matchesTail(tailBase, snap, typed) ? 1 : 0);

            final List<String> rows = new ArrayList<>(MAX);
            for (int i = 0; i < MAX; i++) rows.add(history.get(i));
            final String prefix = "run task: first";
            final int wordStart = AutoCompleteTextRenderer.wordStartOffset(prefix);
            mBench.measureBatch("ui", "render.spannable.10rows", n, 500, 7, 2, () -> {
                long acc = 0;
                for (int i = 0; i < rows.size(); i++) {
                    SpannableString ss = AutoCompleteTextRenderer.buildSuggestionSpannable(
                            rows.get(i), prefix, wordStart, 600, paint);
                    acc += ss.length();
                }
                return acc;
            });
        }

        runStressScan();
    }

    private void runStressScan() {
        final int n = STRESS_SIZE;
        final List<String> history = buildHistory(n, SENTENCES);
        final Map<String, HistorySnapshot.Spans> cache = new HashMap<>();
        final HistorySnapshot snap = HistorySnapshot.build(1, history, cache);
        mBench.measureBatch("engine.stress", "snapshot.warm", n, 100, 7, 2,
                () -> HistorySnapshot.build(1, history, cache).entries.length);
        final SuggestionEngine.ScanPos pos = new SuggestionEngine.ScanPos();
        final List<SuggestionEngine.Candidate> out = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        mBench.measureBatch("engine.stress", "scan.noMatch", n, 20, 9, 2, () -> {
            out.clear();
            pos.reset();
            seen.clear();
            scanInto(snap, "zzzzzz", MAX, pos, out, seen);
            return out.size();
        });
        final List<SuggestionEngine.Candidate> keepBase = scan(snap, "run task: first", MAX);
        final int keepLen = "run task: first".length();
        mBench.measureBatch("engine.stress", "filter.keep10", n, 20000, 7, 2, () -> {
            SuggestionEngine.filter(keepBase, snap, keepLen, keepLen, ' ');
            return keepBase.size();
        });
    }

    // ── Dismissal + record snapshot matrix ──

    private void runDismissalMatrix() {
        final int n = 1000;
        final List<String> texts = buildHistory(n, SENTENCES);
        final List<HistoryEntry> records = new ArrayList<>(n);
        for (int i = 0; i < n; i++) records.add(new HistoryEntry(i + 1, texts.get(i)));
        final Map<String, HistorySnapshot.Spans> cache = new HashMap<>();
        final SharedPreferences prefs = RuntimeEnvironment.getApplication()
                .getSharedPreferences("bench_history_prefs", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        final SuggestionDismissals dismissals = new SuggestionDismissals(prefs);
        dismissals.bindEpoch("bench-epoch");

        mBench.measureBatch("dismissal", "snapshot.records.none", n, 100, 7, 2,
                () -> HistorySnapshot.build(1, records, cache, dismissals).entries.length);

        for (int i = 0; i < 10; i++) dismissals.mark(records.get(i).id, 0);
        final HistorySnapshot tenDismissed =
                HistorySnapshot.build(1, records, cache, dismissals);
        mBench.measureBatch("dismissal", "snapshot.records.10full", n, 100, 7, 2,
                () -> HistorySnapshot.build(1, records, cache, dismissals).entries.length);

        final SuggestionEngine.ScanPos pos = new SuggestionEngine.ScanPos();
        final List<SuggestionEngine.Candidate> out = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        mBench.measureBatch("engine", "scan.after10Full", n, 40, 7, 2, () -> {
            out.clear();
            pos.reset();
            seen.clear();
            scanInto(tenDismissed, "run task: first", MAX, pos, out, seen);
            return out.size();
        });

        // Pathological but useful: almost every matching record is hidden, so the
        // scan must visit the whole history and check a dismissal bit at each entry.
        for (int i = 10; i < n - 1; i++) dismissals.mark(records.get(i).id, 0);
        final HistorySnapshot almostAllDismissed =
                HistorySnapshot.build(1, records, cache, dismissals);
        mBench.measureBatch("engine", "scan.after990Full", n, 40, 7, 2, () -> {
            out.clear();
            pos.reset();
            seen.clear();
            scanInto(almostAllDismissed, "run task: first", MAX, pos, out, seen);
            return out.size();
        });

        mBench.measureBatch("dismissal", "isDismissed.miss", n, 100000, 7, 2,
                () -> dismissals.isDismissed(n + 1, 0) ? 1 : 0);
        mBench.measureBatch("dismissal", "isDismissed.full", n, 100000, 7, 2,
                () -> dismissals.isDismissed(1, 0) ? 1 : 0);
        mBench.measureBatch("dismissal", "isDismissed.sentence", n, 100000, 7, 2,
                () -> dismissals.isDismissed(1, 3) ? 1 : 0);
        dismissals.clearAll();
    }

    // ── Real controller per-keystroke matrix ──

    private void runControllerMatrix() throws Exception {
        for (int n : SIZES) {
            Fixture f = createFixture(n);
            try {
                final String base = "run task: first";
                typeSequence(f.input, base);
                assertFalse("controller produced no suggestions", f.controller.getSuggestions().isEmpty());
                assertTrue("the popup should be showing for the hot-path measurement",
                        f.controller.isShowing());

                mBench.measureEach("controller", "append.keep", n,
                        CONTROLLER_SAMPLES, 50,
                        () -> {
                            f.input.getText().append(' ');
                            return f.input.length();
                        },
                        () -> {
                            f.input.setText(base);
                            f.input.setSelection(base.length());
                            f.controller.onCaretMoved();
                            settleUi();
                        });

                mBench.measureEach("controller", "backspace.rebuild", n,
                        CONTROLLER_SAMPLES, 50,
                        () -> {
                            f.input.getText().delete(base.length(), base.length() + 1);
                            return f.input.length();
                        },
                        () -> {
                            f.input.setText(base + "x");
                            f.input.setSelection(base.length() + 1);
                            f.controller.onCaretMoved();
                            settleUi();
                        });

                mBench.measureEach("controller", "append.afterHistoryChange", n,
                        CONTROLLER_SAMPLES, 50,
                        () -> {
                            f.input.getText().append(' ');
                            return f.input.length();
                        },
                        () -> {
                            f.input.setText(base);
                            f.input.setSelection(base.length());
                            f.controller.onCaretMoved();
                            f.controller.invalidateHistoryVersion();
                            settleUi();
                        });

                mBench.measureEach("controller", "caretMoved.fastPath", n,
                        CONTROLLER_SAMPLES, 50,
                        () -> {
                            f.controller.onCaretMoved();
                            return f.input.length();
                        },
                        () -> {
                            f.input.setText(base);
                            f.input.setSelection(base.length());
                            f.controller.onCaretMoved();
                            settleUi();
                        });

                measureRowRebind(f, base);
            } finally {
                f.close();
            }
        }
    }

    private void measureRowRebind(Fixture f, String base) {
        final ArrayList<String> suggestions = new ArrayList<>(f.controller.getSuggestions());
        if (suggestions.isEmpty()) return;
        final TextView[] rows = new TextView[suggestions.size()];
        for (int i = 0; i < suggestions.size(); i++) {
            rows[i] = f.controller.buildSuggestionTextView(
                    suggestions.get(i), base, f.controller.getCandidate(i));
        }
        final String alternate = base + " ";
        final int[] toggle = {0};
        // Same-prefix rebind is the guard path: it builds the SpannableString but
        // must not call TextView.setText() when nothing visible changed.
        mBench.measureBatch("controller.ui", "rebind.samePrefix", suggestions.size(), 500, 7, 2, () -> {
            String input = (toggle[0]++ & 1) == 0 ? base : alternate;
            for (int i = 0; i < rows.length; i++) {
                f.controller.rebindSuggestionTextView(rows[i], suggestions.get(i), input,
                        f.controller.getCandidate(i));
            }
            return rows.length;
        });
        // The popup itself rebinds with a changed bold prefix, so measure the real
        // SpannableString + TextView.setText work, not only the no-op guard.
        final TextPaint paint = new TextPaint();
        paint.setTextSize(42f);
        paint.setAntiAlias(true);
        final int width = 600;
        mBench.measureBatch("controller.ui", "setText.changedPrefix", suggestions.size(),
                200, 7, 2, () -> {
            String typed = (toggle[0]++ & 1) == 0 ? base : alternate;
            int wordStart = AutoCompleteTextRenderer.wordStartOffset(typed);
            long acc = 0;
            for (int i = 0; i < rows.length; i++) {
                SpannableString ss = AutoCompleteTextRenderer.buildSuggestionSpannable(
                        suggestions.get(i), typed, wordStart, width, paint);
                rows[i].setText(ss, TextView.BufferType.SPANNABLE);
                acc += rows[i].length();
            }
            return acc;
        });
        mBench.measureBatch("controller.ui", "build.rows", suggestions.size(), 100, 7, 2, () -> {
            long acc = 0;
            for (int i = 0; i < suggestions.size(); i++) {
                TextView row = f.controller.buildSuggestionTextView(
                        suggestions.get(i), base, f.controller.getCandidate(i));
                acc += row.length();
            }
            return acc;
        });
    }

    // ── Fixture ──

    private static final class Fixture implements AutoCloseable {
        final Activity activity;
        final MessageHistoryController history;
        final EditText input;
        final AutoCompleteController controller;

        Fixture(Activity activity, MessageHistoryController history, EditText input,
                AutoCompleteController controller) {
            this.activity = activity;
            this.history = history;
            this.input = input;
            this.controller = controller;
        }

        @Override
        public void close() throws Exception {
            controller.dismiss();
            controller.destroy();
            history.close();
            activity.finish();
            resetSingleton();
        }
    }

    private static Fixture createFixture(int n) throws Exception {
        resetSingleton();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SharedPreferences prefs = activity.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        prefs.edit()
                .putInt("suggestions_max_count", MAX)
                .putInt("message_history_max", 1000)
                .putBoolean("autocomplete_sentences_enabled", true)
                .commit();
        MessageHistoryController history = MessageHistoryController.shared(prefs);
        history.setPerDirectoryEnabled(true);
        history.setMaxSize(1000);
        history.load(HOME);
        List<String> entries = buildHistory(n, SENTENCES);
        // buildHistory is newest-first; insert oldest first so the list keeps that order.
        for (int i = entries.size() - 1; i >= 0; i--) {
            history.addToMessageHistory(entries.get(i), HOME);
        }
        history.save();

        EditText input = new EditText(activity);
        activity.setContentView(input);
        input.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        input.layout(0, 0, 1080, 200);
        AutoCompleteController controller = new AutoCompleteController(activity, input, history,
                TermuxColorSchemeManager.shared());
        return new Fixture(activity, history, input, controller);
    }

    // ── Fixtures / helpers ──

    /** Newest-first history: entry i has six distinct sentences. */
    private static List<String> buildHistory(int n, int sentences) {
        List<String> history = new ArrayList<>(n);
        for (int i = n - 1; i >= 0; i--) {
            StringBuilder sb = new StringBuilder(192);
            sb.append("run task: first sentence number ").append(i).append('.');
            for (int s = 1; s < sentences; s++) {
                sb.append(" sentence ").append(s)
                        .append(" has some longer words and details for entry ")
                        .append(i).append('.');
            }
            history.add(sb.toString());
        }
        return history;
    }

    private static void scanInto(HistorySnapshot snap, String typed, int max,
            SuggestionEngine.ScanPos pos, List<SuggestionEngine.Candidate> out,
            Set<String> seen) {
        scanInto(snap, typed, true, max, pos, out, seen);
    }

    private static void scanInto(HistorySnapshot snap, String typed, boolean sentences, int max,
            SuggestionEngine.ScanPos pos, List<SuggestionEngine.Candidate> out,
            Set<String> seen) {
        final int caret = typed.length();
        final int lineStart = SuggestionEngine.activeLineStart(typed, caret);
        final int sentenceStart = SuggestionEngine.activeSentenceStart(typed, caret);
        SuggestionEngine.scan(snap, typed, lineStart, caret - lineStart,
                sentenceStart, caret - sentenceStart, sentences, max, pos, out, seen);
    }

    private static List<SuggestionEngine.Candidate> scan(HistorySnapshot snap, String typed, int max) {
        List<SuggestionEngine.Candidate> out = new ArrayList<>();
        scanInto(snap, typed, max, new SuggestionEngine.ScanPos(), out, new HashSet<String>());
        return out;
    }

    private static SuggestionEngine.ScanPos copyPos(SuggestionEngine.ScanPos src) {
        SuggestionEngine.ScanPos p = new SuggestionEngine.ScanPos();
        p.tier = src.tier;
        p.entry = src.entry;
        p.sentence = src.sentence;
        p.atEnd = src.atEnd;
        return p;
    }

    /**
     * Mirrors AutoCompleteController.suggestionsMatchTail for the early-skip /
     * composition guard: fold the active line once, then verify every candidate.
     */
    private static boolean matchesTail(List<SuggestionEngine.Candidate> candidates,
            HistorySnapshot snap, String typed) {
        if (candidates.isEmpty()) return false;
        final int caret = typed.length();
        final int lineStart = SuggestionEngine.activeLineStart(typed, caret);
        final int lineLen = caret - lineStart;
        final int tailStart = SuggestionEngine.activeSentenceStart(typed, caret);
        final int tailLen = caret - tailStart;
        if (tailLen < 1) return false;
        final char[] foldedLine = SuggestionEngine.fold(typed, lineStart, lineLen);
        final int tailOffset = tailStart - lineStart;
        final char[] foldedTail;
        final int foldedTailStart;
        if (tailOffset >= 0 && tailOffset + tailLen <= lineLen) {
            foldedTail = foldedLine;
            foldedTailStart = tailOffset;
        } else {
            foldedTail = SuggestionEngine.fold(typed, tailStart, tailLen);
            foldedTailStart = 0;
        }
        for (int i = 0; i < candidates.size(); i++) {
            final SuggestionEngine.Candidate c = candidates.get(i);
            final boolean matches;
            if (c.kind == SuggestionEngine.Kind.FULL) {
                matches = SuggestionEngine.matchesFull(snap, c.entryIndex, foldedLine, lineLen);
            } else {
                matches = c.windowEnd - c.windowStart > tailLen
                        && SuggestionEngine.foldedMatches(snap.fold(c.entryIndex),
                                c.windowStart, foldedTail, foldedTailStart, tailLen);
            }
            if (!matches) return false;
        }
        return true;
    }

    private static void typeSequence(EditText input, String typed) {
        input.setText("");
        input.setSelection(0);
        for (int i = 0; i < typed.length(); i++) {
            input.getText().append(typed.charAt(i));
        }
    }

    private static void settleUi() {
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
    }

    private static void resetSingleton() throws Exception {
        Field shared = MessageHistoryController.class.getDeclaredField("sShared");
        shared.setAccessible(true);
        shared.set(null, null);
    }
}
