package com.termux.app.terminal.io.autocomplete;

import static org.junit.Assert.assertFalse;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.SpannableString;
import android.text.TextPaint;
import android.view.View;
import android.widget.EditText;

import com.termux.app.terminal.TermuxColorSchemeManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Hot-path measurements for the two-tier sentence autocomplete.
 *
 * <p>Run explicitly (skipped in the normal suite):
 * <pre>
 * ./gradlew :app:testDebugUnitTest -PautocompleteBench=true \
 *     --tests '*AutoCompleteHotPathBenchmarkTest*'
 * </pre>
 *
 * <p>The pure engine numbers are JVM measurements of the real code paths. The
 * controller number is end-to-end under Robolectric, so it includes the test
 * {@link EditText}/popup plumbing and is only meaningful for scaling comparisons.
 *
 * <p>Method: a full warm-up pass over all sizes, then a measured pass where every
 * operation is run in five batches and the best (minimum) batch average is
 * reported. The minimum filters GC/background noise; the warm-up pass removes
 * class-loading and JIT effects from the first size.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AutoCompleteHotPathBenchmarkTest {

    private static final String HOME = "/home/user";
    private static final int MAX = 10;
    private static final int SENTENCES = 6;
    private static final int[] SIZES = {100, 500, 1000};
    private static final int BATCHES = 5;

    private static long sSink;
    private static boolean sPrintResults;

    private interface Op {
        long run();
    }

    // ── Synthetic history ──

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

    // ── Engine helpers (same calls the controller makes) ──

    private static void scanInto(HistorySnapshot snap, String typed, int max,
            SuggestionEngine.ScanPos pos, List<SuggestionEngine.Candidate> out,
            Set<String> seen) {
        final int caret = typed.length();
        final int lineStart = SuggestionEngine.activeLineStart(typed, caret);
        final int sentenceStart = SuggestionEngine.activeSentenceStart(typed, caret);
        SuggestionEngine.scan(snap, typed, lineStart, caret - lineStart,
                sentenceStart, caret - sentenceStart, max, pos, out, seen);
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

    // ── Measurement ──

    private static void bench(boolean report, String name, int n, int iters, Op op) {
        final int warm = Math.max(3, Math.min(20, iters / 5));
        for (int i = 0; i < warm; i++) sSink += op.run();
        if (!report) return;
        double bestNs = Double.MAX_VALUE;
        for (int b = 0; b < BATCHES; b++) {
            final long t0 = System.nanoTime();
            for (int i = 0; i < iters; i++) sSink += op.run();
            final long dt = System.nanoTime() - t0;
            bestNs = Math.min(bestNs, dt / (double) iters);
        }
        if (sPrintResults) {
            System.out.printf(Locale.US, "%-6d %-28s %8d %12.3f %12.3f%n",
                    n, name, iters, bestNs * iters / 1_000_000.0, bestNs / 1_000.0);
        }
    }

    private static void header() {
        System.out.println();
        System.out.println("=== AutoComplete hot-path benchmark (best of " + BATCHES + " batches) ===");
        System.out.printf(Locale.US, "%-6s %-28s %8s %12s %12s%n",
                "N", "operation", "iters", "total ms", "avg us/op");
    }

    // ── Test ──

    @Test
    public void benchmarkHotPath() throws Exception {
        assumeTrue(Boolean.getBoolean("autocomplete.bench"));
        sPrintResults = false;
        header();

        final TextPaint paint = new TextPaint();
        paint.setTextSize(42f);
        paint.setAntiAlias(true);

        // Warm-up pass over all sizes: class loading + JIT for the engine paths.
        runPass(false, paint);
        // A silent full-batch pass gets the JIT to C2 before anything is reported.
        runPass(true, paint);
        // The controller has a heavy setup; warm its UI classes once.
        benchControllerWarmup();
        // Reported pass: the engine is now warm, so sizes are comparable.
        sPrintResults = true;
        runPass(true, paint);

        System.out.println("(sink=" + sSink + ")");
    }

    private void runPass(boolean report, TextPaint paint) throws Exception {
        for (int n : SIZES) {
            final List<String> history = buildHistory(n, SENTENCES);
            final Map<String, HistorySnapshot.Spans> cache = new HashMap<>();

            // Snapshot build: cold parses every entry; warm hits the spans cache.
            bench(report, "snapshot.cold", n, 20, () ->
                    HistorySnapshot.build(1, history, new HashMap<String, HistorySnapshot.Spans>())
                            .entries.length);
            final HistorySnapshot snap = HistorySnapshot.build(1, history, cache);
            bench(report, "snapshot.warm", n, 200, () ->
                    HistorySnapshot.build(1, history, cache).entries.length);

            // Path A: full scan.
            bench(report, "scan.noMatch", n, 50, () -> scan(snap, "zzzzzz", MAX).size());
            bench(report, "scan.full10", n, 100, () -> scan(snap, "run task: first", MAX).size());
            final String sentenceTyped = "run task: first sentence number 0. sentence 1";
            bench(report, "scan.sentence", n, 100, () -> scan(snap, sentenceTyped, MAX).size());

            // Path B: filter (list stays full, so no top-up scan).
            final List<SuggestionEngine.Candidate> keepBase = scan(snap, "run task: first", MAX);
            final int keepLen = "run task: first".length();
            bench(report, "filter.keep10", n, 5000, () -> {
                List<SuggestionEngine.Candidate> out = new ArrayList<>(keepBase);
                SuggestionEngine.filter(out, snap, keepLen, keepLen, ' ');
                return out.size();
            });

            // Path B: filter drops candidates + resumable top-up scan.
            final String topUpBase = "run task: first sentence number 1";
            final List<SuggestionEngine.Candidate> topUpOut = new ArrayList<>();
            final SuggestionEngine.ScanPos topUpPos = new SuggestionEngine.ScanPos();
            scanInto(snap, topUpBase, MAX, topUpPos, topUpOut, new HashSet<String>());
            final String topUpNext = topUpBase + "0";
            final int topUpLineLen = topUpBase.length();
            bench(report, "filter.topup", n, 100, () -> {
                List<SuggestionEngine.Candidate> out = new ArrayList<>(topUpOut);
                SuggestionEngine.ScanPos pos = copyPos(topUpPos);
                Set<String> seen = new HashSet<>();
                SuggestionEngine.filter(out, snap, topUpLineLen, topUpLineLen, '0');
                for (int i = 0; i < out.size(); i++) {
                    if (out.get(i).kind == SuggestionEngine.Kind.SENTENCE) {
                        seen.add(out.get(i).text);
                    }
                }
                scanInto(snap, topUpNext, MAX, pos, out, seen);
                return out.size();
            });

            // Early-skip / composition guard.
            final List<SuggestionEngine.Candidate> tailBase = scan(snap, sentenceTyped, MAX);
            bench(report, "matchesTail.10", n, 2000, () ->
                    matchesTail(tailBase, snap, sentenceTyped) ? 1 : 0);

            // Popup rows: 10 renderer calls with the caches warm (the hot path).
            final List<String> rows = new ArrayList<>(MAX);
            for (int i = 0; i < MAX; i++) rows.add(history.get(i));
            final String prefix = "run task: first";
            final int wordStart = AutoCompleteTextRenderer.wordStartOffset(prefix);
            for (int i = 0; i < rows.size(); i++) {
                AutoCompleteTextRenderer.buildSuggestionSpannable(
                        rows.get(i), prefix, wordStart, 600, paint);
            }
            bench(report, "render.10rows", n, 200, () -> {
                long acc = 0;
                for (int i = 0; i < rows.size(); i++) {
                    SpannableString ss = AutoCompleteTextRenderer.buildSuggestionSpannable(
                            rows.get(i), prefix, wordStart, 600, paint);
                    acc += ss.length();
                }
                return acc;
            });

            if (report && sPrintResults) {
                benchController(n, history);
            }
        }
    }

    private void benchControllerWarmup() throws Exception {
        List<String> entries = buildHistory(100, SENTENCES);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SharedPreferences prefs = activity.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        prefs.edit()
                .putInt("suggestions_max_count", MAX)
                .putInt("message_history_max", 1000)
                .commit();
        resetSingleton();
        MessageHistoryController history = MessageHistoryController.shared(prefs);
        history.load(HOME);
        for (int i = 0; i < entries.size(); i++) history.addToMessageHistory(entries.get(i), HOME);
        EditText input = new EditText(activity);
        activity.setContentView(input);
        input.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        input.layout(0, 0, 1080, 200);
        AutoCompleteController controller = new AutoCompleteController(activity, input, history,
                TermuxColorSchemeManager.shared());
        typeSequence(input, "run task: first sentence number 0. sentence 1");
        controller.dismiss();
        resetSingleton();
    }

    private void benchController(int n, List<String> entries) throws Exception {
        resetSingleton();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SharedPreferences prefs = activity.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        prefs.edit()
                .putInt("suggestions_max_count", MAX)
                .putInt("message_history_max", 1000)
                .commit();
        MessageHistoryController history = MessageHistoryController.shared(prefs);
        history.load(HOME);
        for (int i = 0; i < entries.size(); i++) {
            history.addToMessageHistory(entries.get(i), HOME);
        }
        EditText input = new EditText(activity);
        activity.setContentView(input);
        input.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        input.layout(0, 0, 1080, 200);
        AutoCompleteController controller = new AutoCompleteController(activity, input, history,
                TermuxColorSchemeManager.shared());

        final String typed = "run task: first sentence number 0. sentence 1";
        typeSequence(input, typed);
        assertFalse("controller produced no suggestions", controller.getSuggestions().isEmpty());
        final boolean popupShowing = controller.isShowing();

        double bestNsPerChar = Double.MAX_VALUE;
        for (int r = 0; r < BATCHES; r++) {
            final long t0 = System.nanoTime();
            typeSequence(input, typed);
            final long dt = System.nanoTime() - t0;
            bestNsPerChar = Math.min(bestNsPerChar, dt / (double) typed.length());
        }
        System.out.printf(Locale.US, "%-6d %-28s %8d %12.3f %12.3f%n",
                n, "controller.type/char", typed.length(),
                bestNsPerChar * typed.length() / 1_000_000.0, bestNsPerChar / 1_000.0);
        System.out.println("       controller popup showing after typing: " + popupShowing);

        controller.dismiss();
        resetSingleton();
    }

    private static void typeSequence(EditText input, String typed) {
        input.setText("");
        input.setSelection(0);
        for (int i = 0; i < typed.length(); i++) {
            input.getText().append(typed.charAt(i));
        }
    }

    private static void resetSingleton() throws Exception {
        Field shared = MessageHistoryController.class.getDeclaredField("sShared");
        shared.setAccessible(true);
        shared.set(null, null);
    }
}
