package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import com.termux.R;
import com.termux.app.TermuxService;
import com.termux.app.bubble.TermuxBubbleManager;
import com.termux.app.terminal.SessionPagerManager;
import com.termux.app.terminal.io.SessionUiStateStore;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.view.KeyboardUtils;
import com.termux.terminal.TerminalSession;

/**
 * Debug-build-only automation hooks (source set src/debug, compiled out of release).
 * Driven from adb two ways:
 *   1. Broadcast (works only when the app is in the foreground on MIUI):
 *        adb shell am broadcast -a com.termux.debug.DEBUG_CMD --es cmd status
 *   2. Activity hook (always works, MIUI-safe):
 *        adb shell am start -n com.termux.debug/com.termux.app.TermuxDebugActivity --es cmd status
 * Commands:
 *   status       - dump panel visibility/focus/IME state to logcat (tag TIPanelCmd)
 *   uistate      - dump the full SessionUiStateStore (per-session text/caret/panel/focus/scroll)
 *                  plus keyboard intent/restore-latch fields (tag TIPanelCmd)
 *   panel open   - open the text input panel (same code path as the toggle button)
 *   panel close  - close the text input panel
 *   focus panel  - move view focus to the panel EditText (without changing visibility)
 *   focus term   - move view focus to the terminal view
 *   ime show     - show the soft keyboard for the currently focused view
 *   ime hide     - hide the soft keyboard
 *   tap toggle   - synthesize a click on the floating toggle button (performClick)
 *   ti text <s>  - set the panel EditText content + caret in the middle (no state writes)
 *   type char <c> - append one character to the panel EditText via insert() (no state writes)
 *   scroll down <n> - scroll the terminal view down n rows (simulates user scroll up in history:
 *                     moves mTopRow AWAY from 0, i.e. negative)
 *   scroll up <n>   - scroll the terminal view back toward the bottom by n rows
 *   switch tab <i>  - switch the pager to session index i (same path as tab click)
 *   new tab      - add a new session (same path as the "+" button)
 *   close tab    - remove the current session (same path as the tab close button)
 *   pagedump     - snapshot of every "which page is active" state (pager item, active index,
 *                   adapter counts, placeholder, per-page bound view, mTerminalView's session,
 *                   highlighted tab) — for the tab-close invariant check (tag TIPanelCmd)
 *   session count- log the number of live sessions
 *   dumpsess     - log per-session handles in service order (for re-key verification)
 *   extrakeys    - snapshot of the extra-keys panel's fold state: stored / effective / actually
 *                  built row count, fold flag and mode, landscape or not, column count and panel
 *                  height. The on-device oracle for the "compact panel in landscape" option, which
 *                  tells "the panel really was rebuilt folded" from "it only looks narrower".
 *   tabs         - snapshot of the tab strip: geometry, scroll position, range, and the visible
 *                  over-drag displacement, plus whatever the last `tabs watch` observed
 *   tabs watch <ms> - sample the tab strip once per animation frame for <ms> (default 3000) and
 *                  log the totals: frames, moved frames, frames pinned at an end, frames pinned
 *                  at an end WHILE stretched (the bounce), and the peak displacement
 *   bubble       - post the bubble notification from wherever the app currently is. Run it after
 *                  KEYCODE_HOME to exercise the background-posting path, which is the one the
 *                  notification button and the automatic option both rely on.
 *   bubble cancel- take the bubble down (same as the main window resuming)
 *   bubble auto <0|1> - set the "bubble on background" preference, so the automatic behaviour can
 *                  be tested without walking the settings UI (1 is the default when omitted)
 *   bubble exit  - send the notification's "Exit" action (a TermuxService intent, which cannot be
 *                  sent from adb because the service is exported="false"). Used to check that the
 *                  bubble is taken down with the sessions instead of being left floating, empty.
 */
public class TermuxDebugCommandReceiver extends BroadcastReceiver {

    private static final String TAG = "TIPanelCmd";

    @Override
    public void onReceive(Context context, Intent intent) {
        execute(TermuxActivity.getInstance(), intent);
    }

    /** Shared command executor used by both the broadcast receiver and the activity hook. */
    public static void execute(TermuxActivity activity, Intent intent) {
        if (activity == null || activity.isFinishing()) {
            log("no-activity: cmd=" + (intent == null ? null : intent.getStringExtra("cmd")));
            return;
        }
        String cmd = intent == null ? null : intent.getStringExtra("cmd");
        if (TextUtils.isEmpty(cmd)) cmd = "status";
        // Test scripts pass multi-word commands as "panel_open" etc. because adb shell
        // re-splits quoted arguments; normalize back to spaces here.
        cmd = cmd.replace('_', ' ');
        log("recv: " + cmd + (intent != null && intent.hasExtra("arg")
                ? " [" + intent.getStringExtra("arg") + "]" : ""));
        try {
            switch (cmd) {
                case "status":
                    dumpStatus(activity);
                    break;
                case "extrakeys":
                    dumpExtraKeys(activity);
                    break;
                case "bubble":
                    // Deliberately routed through the same manager call the notification button and
                    // the automatic path use, so this exercises the real background-posting code
                    // path rather than a shortcut.
                    activity.runOnUiThread(() ->
                        log("bubble posted=" + TermuxBubbleManager.showBubble(activity, "debug")));
                    break;
                case "bubble cancel":
                    activity.runOnUiThread(() -> {
                        TermuxBubbleManager.cancel(activity);
                        log("bubble cancelled");
                    });
                    break;
                case "bubble auto": {
                    final boolean enabled = !"0".equals(
                        intent == null ? null : intent.getStringExtra("arg"));
                    activity.runOnUiThread(() -> {
                        TermuxAppSharedPreferences prefs = activity.getPreferences();
                        if (prefs != null) prefs.setBubbleOnBackgroundEnabled(enabled);
                        log("bubble auto=" + enabled);
                    });
                    break;
                }
                case "bubble exit":
                    // Reproduces the notification's "Exit" action byte for byte: that action is a
                    // service intent (TermuxService.ACTION_STOP_SERVICE), and the service is
                    // exported="false", so it cannot be sent from adb shell — this hook builds the
                    // very same intent from inside the app so the real stop path (and the bubble
                    // cancellation inside it) can be exercised. See TermuxService.buildNotification().
                    activity.runOnUiThread(() -> {
                        activity.startService(new Intent(activity, TermuxService.class)
                            .setAction(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_STOP_SERVICE));
                        log("sent ACTION_STOP_SERVICE");
                    });
                    break;
                case "panel open":
                    activity.runOnUiThread(() -> setPanelVisible(activity, true));
                    break;
                case "panel close":
                    activity.runOnUiThread(() -> setPanelVisible(activity, false));
                    break;
                case "focus panel":
                    activity.runOnUiThread(() -> {
                        EditText ti = panelEditText(activity);
                        if (ti != null) ti.requestFocus();
                    });
                    break;
                case "focus term":
                    activity.runOnUiThread(() -> activity.getTerminalView().requestFocus());
                    break;
                case "ime show":
                    activity.runOnUiThread(() -> showIme(activity));
                    break;
                case "ime hide":
                    activity.runOnUiThread(() -> hideIme(activity));
                    break;
                case "kb toggle":
                    // Same code path as the KEYBOARD extra key (onToggleSoftKeyboardRequest).
                    activity.runOnUiThread(() -> {
                        com.termux.view.TerminalView tv = activity.getTerminalView();
                        if (tv == null) { log("kb toggle: no terminal view"); return; }
                        tv.requestFocus();
                        activity.getTermuxTerminalViewClient().onToggleSoftKeyboardRequest();
                    });
                    break;
                case "kb force-show":
                    // Test helper: make the keyboard visible regardless of the disabled pref.
                    activity.runOnUiThread(() -> {
                        com.termux.view.TerminalView tv2 = activity.getTerminalView();
                        if (tv2 == null) { log("kb force-show: no terminal view"); return; }
                        tv2.requestFocus();
                        KeyboardUtils.clearDisableSoftKeyboardFlags(activity);
                        KeyboardUtils.setSoftInputModeAdjustResize(activity);
                        KeyboardUtils.showSoftKeyboard(activity, tv2);
                        log("kb force-show done");
                    });
                    break;
                case "kb force-hide":
                    activity.runOnUiThread(() -> {
                        com.termux.view.TerminalView tv3 = activity.getTerminalView();
                        View focus = tv3 != null ? tv3 : activity.getCurrentFocus();
                        if (focus != null) KeyboardUtils.hideSoftKeyboard(activity, focus);
                        log("kb force-hide done");
                    });
                    break;
                case "storm": {
                    // Deterministic chaos test for the per-session text-input store.
                    // Runs a scripted sequence of panel/keyboard/tab actions, checking
                    // after every step that each session's remembered text survived.
                    // arg = number of rounds (default 1).
                    final int rounds = parseArg(intent, 1);
                    runStormTest(activity, rounds);
                    break;
                }
                case "tap toggle":
                    activity.runOnUiThread(() -> {
                        View b = activity.findViewById(R.id.toggle_text_input_button);
                        if (b == null) { log("tap toggle: button not found"); return; }
                        // The toggle handles OnTouchListener(ACTION_UP), so performClick()
                        // would be a no-op. Dispatch a real DOWN/UP pair instead.
                        long now = android.os.SystemClock.uptimeMillis();
                        int[] xy = new int[2];
                        b.getLocationOnScreen(xy);
                        float x = xy[0] + b.getWidth() / 2f, y = xy[1] + b.getHeight() / 2f;
                        b.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now,
                                android.view.MotionEvent.ACTION_DOWN, x, y, 0));
                        b.postDelayed(() -> b.dispatchTouchEvent(android.view.MotionEvent.obtain(
                                now, android.os.SystemClock.uptimeMillis(),
                                android.view.MotionEvent.ACTION_UP, x, y, 0)), 60);
                    });
                    break;
                case "uistate":
                    dumpUiState(activity);
                    break;
                case "ti text":
                    // "~" encodes a space: adb shell re-splits quoted args with spaces.
                    final String text = intent.getStringExtra("arg");
                    final String textNorm = text == null ? null : text.replace('~', ' ');
                    activity.runOnUiThread(() -> {
                        EditText ti = panelEditText(activity);
                        if (ti == null) { log("ti text: no edit text"); return; }
                        ti.setText(textNorm == null ? "" : textNorm);
                        if (textNorm != null && textNorm.length() >= 2) {
                            ti.setSelection(textNorm.length() / 2);
                        }
                        log("ti text: set " + (textNorm == null ? 0 : textNorm.length()) + "ch");
                    });
                    break;
                case "type char":
                    final String ch = intent.getStringExtra("arg");
                    activity.runOnUiThread(() -> {
                        EditText ti2 = panelEditText(activity);
                        if (ti2 == null || ch == null) { log("type char: no edit text"); return; }
                        int pos = ti2.getSelectionStart() < 0 ? ti2.length() : ti2.getSelectionStart();
                        ti2.getText().insert(pos, ch);
                        log("type char: inserted '" + ch + "' at " + pos);
                    });
                    break;
                case "scroll down":
                case "scroll up": {
                    final int rows = parseArg(intent, 10);
                    final boolean down = cmd.equals("scroll down");
                    activity.runOnUiThread(() -> {
                        com.termux.view.TerminalView tv = activity.getActiveTerminalView();
                        if (tv == null || tv.mEmulator == null) { log("scroll: no view"); return; }
                        int top = tv.getTopRow();
                        int maxRows = tv.mEmulator.getScreen().getActiveTranscriptRows();
                        int target = top + (down ? -rows : rows);
                        target = Math.max(-maxRows, Math.min(0, target));
                        tv.setTopRow(target);
                        tv.mEmulator.setAutoScrollDisabled(target != 0);
                        tv.invalidate();
                        log("scroll: top " + top + " -> " + target + " (rows=" + maxRows + ")");
                    });
                    break;
                }
                case "switch tab": {
                    final int idx = parseArg(intent, 0);
                    activity.runOnUiThread(() -> {
                        activity.getTermuxTerminalSessionClient().switchToSession(idx);
                        log("switch tab: -> " + idx);
                    });
                    break;
                }
                case "new tab":
                    activity.runOnUiThread(() -> {
                        activity.getTermuxTerminalSessionClient().addNewSession(false, null);
                        log("new tab: added");
                    });
                    break;
                case "close tab":
                    activity.runOnUiThread(() -> {
                        TerminalSession cur = activity.getCurrentSession();
                        if (cur != null) activity.getTermuxTerminalSessionClient().removeFinishedSession(cur);
                        log("close tab: done");
                    });
                    break;
                case "session count": {
                    TermuxService svc = activity.getTermuxService();
                    log("session count: " + (svc == null ? -1 : svc.getTermuxSessionsSize()));
                    break;
                }
                case "pagedump": {
                    // Snapshot of every piece of "which page is active" state. The invariant to
                    // check: active == tv's session == the highlighted tab == a LIVE session.
                    activity.runOnUiThread(() -> {
                        SessionPagerManager pm = activity.getSessionPagerManager();
                        log(pm == null ? "PAGEDUMP no-pager-manager" : pm.dumpPageState());
                    });
                    break;
                }
                case "dumpsess": {
                    TermuxService svc2 = activity.getTermuxService();
                    StringBuilder dsb = new StringBuilder("DUMPSESS");
                    if (svc2 != null) {
                        for (int i = 0; i < svc2.getTermuxSessionsSize(); i++) {
                            TermuxSession ts =
                                    svc2.getTermuxSession(i);
                            TerminalSession s = ts == null ? null : ts.getTerminalSession();
                            dsb.append(" s").append(i).append("=")
                               .append(s == null ? "null" : String.valueOf(s.mHandle.hashCode()));
                        }
                    }
                    log(dsb.toString());
                    break;
                }
                case "tabs": {
                    // Snapshot of the tab strip's scroll geometry plus the elastic over-drag
                    // displacement, all through public View API — see the watcher below for why.
                    activity.runOnUiThread(() -> {
                        final View[] strip = findTabStrip(activity);
                        if (strip == null) { log("TABS no-strip"); return; }
                        final View v = strip[0];
                        final View content = strip[1];
                        final int range = stripRange(v, content);
                        log("TABS cls=" + v.getClass().getSimpleName()
                                + " w=" + v.getWidth()
                                + " scrollX=" + v.getScrollX()
                                + " contentW=" + content.getWidth()
                                + " range=" + range
                                // The displacement the user actually sees: the content child's
                                // translationX is the elastic over-drag, by definition.
                                + " tx=" + content.getTranslationX()
                                + " | lastWatch[" + watchSummary() + "]");
                    });
                    break;
                }
                case "tabs watch": {
                    // Per-frame observation of the strip for a few seconds. Armed here, consumed by
                    // `tabs` — so the sequence is: tabs watch, do the gesture, tabs.
                    final String wa = intent.getStringExtra("arg");
                    long ms = 3000L;
                    try {
                        if (!TextUtils.isEmpty(wa)) ms = Long.parseLong(wa.trim());
                    } catch (NumberFormatException ignored) {
                    }
                    final long windowMs = Math.max(200L, Math.min(15000L, ms));
                    activity.runOnUiThread(() -> {
                        final View[] strip = findTabStrip(activity);
                        if (strip == null) { log("tabs watch: no-strip"); return; }
                        armStripWatch(strip[0], strip[1], stripRange(strip[0], strip[1]), windowMs);
                        log("tabs watch: armed for " + windowMs + "ms");
                    });
                    break;
                }
                case "run": {
                    final String command = intent.getStringExtra("arg");
                    activity.runOnUiThread(() -> {
                        TerminalSession s = activity.getCurrentSession();
                        if (s == null || !s.isRunning()) { log("run: no running session"); return; }
                        s.write((command == null ? "" : command) + "\n");
                        log("run: wrote command");
                    });
                    break;
                }
                case "recreate":
                    activity.runOnUiThread(activity::recreate);
                    break;
            }
        } catch (Throwable t) {
            log("error: " + t);
        }
    }

    private static int parseArg(Intent intent, int def) {
        try {
            return Integer.parseInt(intent.getStringExtra("arg"));
        } catch (Exception e) {
            return def;
        }
    }

    /** The panel's shared EditText, or null before it is inflated. */
    private static EditText panelEditText(TermuxActivity activity) {
        return activity.findViewById(R.id.terminal_toolbar_text_input);
    }

    /** Show or hide the text-input panel and refresh the pencil icon, as the toggle button does. */
    private static void setPanelVisible(TermuxActivity activity, boolean visible) {
        activity.setTextInputVisible(visible);
        activity.updateToggleTextInputButtonIcon();
    }

    /** Show the soft keyboard for whatever view currently has focus. */
    private static void showIme(TermuxActivity activity) {
        View v = activity.getCurrentFocus();
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (v != null && imm != null) imm.showSoftInput(v, 0);
    }

    /** Hide the soft keyboard from whatever view currently has focus. */
    private static void hideIme(TermuxActivity activity) {
        View v = activity.getCurrentFocus();
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (v != null && imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    /** @return {strip, content} of the session tab strip, or null when it is not on screen. */
    private static View[] findTabStrip(TermuxActivity activity) {
        View strip = activity.findViewById(com.termux.R.id.session_tabs_scroll);
        View content = activity.findViewById(com.termux.R.id.session_tabs);
        if (strip == null || content == null) return null;
        return new View[]{strip, content};
    }

    /**
     * Dump the full per-session UI state (SessionUiStateStore) plus live view state as one
     * machine-parseable log line:
     *   UISTATE sessions=N pager=I kb=B kbIntent=B restoringKb=B pendingKb=B
     *            t<i>len=.. t<i>head=.. t<i>caret=.. t<i>panel=.. t<i>focus=.. t<i>top=.. t<i>rows=..
     *            live_top=.. live_rows=.. live_tilen=.. live_ticaret=.. live_panel=..
     *            live_tifocus=.. live_termfocus=.. live_cur=i
     * Text "head" is the first 12 chars, non-alphanumerics mapped to '_' so the token stays
     * space-free and grep-able.
     */
    private static void dumpUiState(TermuxActivity activity) {
        StringBuilder sb = new StringBuilder("UISTATE");
        TermuxService service = activity.getTermuxService();
        SessionUiStateStore store = activity.getTextInputState();
        int sessions = service == null ? 0 : service.getTermuxSessionsSize();
        sb.append(" sessions=").append(sessions);
        sb.append(" pager=").append(activity.getPagerCurrentItem());
        sb.append(" kb=").append(activity.isSoftKeyboardVisible() ? 1 : 0);
        sb.append(" kbIntent=").append(store.isSoftKeyboardVisibleIntent() ? 1 : 0);
        sb.append(" restoringKb=").append(activity.isRestoringKeyboard() ? 1 : 0);
        sb.append(" pendingKb=").append(activity.isPendingKeyboardRestore() ? 1 : 0);
        sb.append(" switchInProg=").append(activity.isTerminalPageSwitchInProgress() ? 1 : 0);
        int current = activity.getPagerCurrentItem();
        TerminalSession currentSession = activity.getCurrentSession();
        for (int i = 0; i < sessions; i++) {
            TermuxSession ts =
                    service.getTermuxSession(i);
            TerminalSession s = ts == null ? null : ts.getTerminalSession();
            if (s == null) continue;
            String text = store.getInputText(s.mHandle);
            String head = sanitizeHead(text);
            sb.append(" t").append(i).append("len=").append(text == null ? -1 : text.length());
            sb.append(" t").append(i).append("head=").append(head);
            sb.append(" t").append(i).append("caret=").append(store.getCaret(s.mHandle));
            sb.append(" t").append(i).append("panel=").append(store.hasVisible(s.mHandle)
                    ? (store.isVisible(s.mHandle) ? 1 : 0) : -1);
            sb.append(" t").append(i).append("focus=").append(store.isFocusOnInput(s) ? 1 : 0);
            sb.append(" t").append(i).append("kb=").append(store.isSoftKeyboardIntent(s) ? 1 : 0);
            sb.append(" t").append(i).append("top=").append(store.getScrollTopRow(s));
            sb.append(" t").append(i).append("rows=").append(store.getScrollTranscriptRows(s));
            if (s == currentSession) current = i;
        }
        sb.append(" live_cur=").append(current);
        com.termux.view.TerminalView tv = activity.getActiveTerminalView();
        if (tv != null) {
            sb.append(" live_top=").append(tv.getTopRow());
            sb.append(" live_rows=").append(tv.getScrollTranscriptRows());
        }
        EditText ti = panelEditText(activity);
        if (ti != null) {
            sb.append(" live_tilen=").append(ti.getText() == null ? 0 : ti.getText().length());
            sb.append(" live_tihead=").append(sanitizeHead(
                    ti.getText() == null ? null : ti.getText().toString()));
            sb.append(" live_ticaret=").append(ti.getSelectionStart());
            sb.append(" live_tifocus=").append(ti.hasFocus() ? 1 : 0);
        }
        sb.append(" live_panel=").append(activity.isTextInputVisible() ? 1 : 0);
        sb.append(" live_termfocus=").append(
                activity.getActiveTerminalView() != null && activity.getActiveTerminalView().hasFocus() ? 1 : 0);
                // Who actually owns the window focus, and whether the activity's cached active view
                // is set. Without this, "no view has focus" and "focus sits on a tab" look identical,
                // and a null cached active view is invisible in every other field (live_termfocus
                // resolves through the pager fallback on purpose, so it stays 1 when the cache is null).
        View focusOwner = activity.getCurrentFocus();
        sb.append(" live_focus=").append(focusOwner == null ? "none"
                : focusOwner.getClass().getSimpleName());
        if (focusOwner != null && focusOwner.getId() != View.NO_ID) {
            try {
                sb.append("#").append(activity.getResources().getResourceEntryName(focusOwner.getId()));
            } catch (Exception ignored) { }
        }
        sb.append(" actview=").append(activity.getTerminalView() == null ? 0 : 1);
        log(sb.toString());
    }

    /**
     * Deterministic storm test for the per-session text-input store. Ensures ≥2 sessions, opens
     * the panel on each, types a unique marker per session ("STORM_<i>_<tag>"), then runs
     * &lt;rounds&gt; rounds of scripted chaos: tab switch, panel close/open, keyboard churn, toggle
     * visibility update, switch back. After every step the full store + live field is verified —
     * every non-"sent" marker must still be in the store, and the LIVE field must show the current
     * session's marker when its panel is open. Any mismatch logs "STORM FAIL …"; success logs
     * "STORM PASS rounds=N".
     */
    private static void runStormTest(TermuxActivity activity, int rounds) {
        // BACKGROUND thread: every step posts to the UI thread and waits (runOnUiThreadSync).
        // Sleeping on the main looper — the previous implementation — starved the posted
        // focus/insets/pager events the test is supposed to exercise, so half the machinery
        // never ran.
        new Thread(() -> {
            try {
                stormBody(activity, rounds);
            } catch (Throwable t) {
                log("STORM FAIL exception: " + t);
            }
        }, "storm-test").start();
    }

    /** Post to the UI thread and WAIT until the action completed (main looper stays live). */
    private static void runOnUiThreadSync(TermuxActivity activity, Runnable action) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action.run();
            return;
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        activity.runOnUiThread(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
    }

    private static void stormBody(TermuxActivity activity, int rounds) {
        TermuxService service = activity.getTermuxService();
        if (service == null) { log("STORM FAIL: no service"); return; }

        String tag = String.valueOf(System.currentTimeMillis() % 100000);
        int n = service.getTermuxSessionsSize();
        // Ensure 2 sessions with panel open + markers typed.
        while (n < 2) {
            final boolean[] added = {false};
            runOnUiThreadSync(activity, () -> {
                activity.getTermuxTerminalSessionClient().addNewSession(false, null);
                added[0] = true;
            });
            n = service.getTermuxSessionsSize();
        }
        sleepUi(400);

        String[] markers = new String[n];
        for (int i = 0; i < n; i++) {
            selectTab(activity, i);
            openPanel(activity);
            setField(activity, "STORM" + i + "_" + tag);
            markers[i] = "STORM" + i + "_" + tag;
            StringBuilder hs = new StringBuilder("STORM typed session idx=" + i);
            for (int j = 0; j < service.getTermuxSessionsSize(); j++) {
                com.termux.shared.termux.shell.command.runner.terminal.TermuxSession ts = service.getTermuxSession(j);
                TerminalSession s = ts == null ? null : ts.getTerminalSession();
                hs.append(" [").append(j).append("]=")
                  .append(s == null ? "null" : Integer.toHexString(System.identityHashCode(s)));
            }
            hs.append(" live=").append(activity.getCurrentSession() == null ? "null"
                    : Integer.toHexString(System.identityHashCode(activity.getCurrentSession())));
            log(hs.toString());
        }

        int cur = activity.getPagerCurrentItem();
        int failures = 0;

        java.util.Random rnd = new java.util.Random(20260908L);
        for (int r = 0; r < rounds; r++) {
            // 1. switch to a random other tab (panel-open -> panel-open)
            int next = (cur + 1 + rnd.nextInt(Math.max(1, n - 1))) % n;
            if (next == cur) next = (cur + 1) % n;
            selectTab(activity, next);
            failures += verifyStoreSync(activity, "r" + r + " after switch->" + next, markers);
            failures += verifyLiveSync(activity, "r" + r + " live after switch->" + next, markers[next]);
            cur = next;

            // 2. panel close
            panelClose(activity);
            failures += verifyStoreSync(activity, "r" + r + " after panel close", markers);

            // 3. kb churn while panel hidden
            kbShow(activity); kbHide(activity);

            // 4. panel open again (same session, must restore marker)
            openPanel(activity);
            failures += verifyStoreSync(activity, "r" + r + " after panel reopen", markers);
            failures += verifyLiveSync(activity, "r" + r + " live after panel reopen", markers[cur]);

            // 5. kb churn while panel open. kbHide is a user action: the panel
            // legitimately auto-closes (per-session flag -> hidden). Reopen it and
            // verify the store survived the churn AND the field shows the marker.
            kbShow(activity); kbHide(activity);
            openPanel(activity);
            failures += verifyStoreSync(activity, "r" + r + " after kb churn (open)", markers);
            failures += verifyLiveSync(activity, "r" + r + " live after kb churn reopen", markers[cur]);

            // 6. toggle-button visibility churn (styling-like path)
            runOnUiThreadSync(activity, () -> activity.updateToggleTextInputButtonVisibility());
            failures += verifyStoreSync(activity, "r" + r + " after toggle visibility churn", markers);
            failures += verifyLiveSync(activity, "r" + r + " live after toggle churn", markers[cur]);

            // 7. switch back to previous tab
            int back = (next == 0) ? n - 1 : next - 1;
            selectTab(activity, back);
            failures += verifyStoreSync(activity, "r" + r + " after switch back->" + back, markers);
            failures += verifyLiveSync(activity, "r" + r + " live after switch back->" + back, markers[back]);
            cur = back;
        }

        if (failures == 0) {
            log("STORM PASS rounds=" + rounds + " sessions=" + n + " tag=" + tag);
        } else {
            log("STORM FAIL total=" + failures + " rounds=" + rounds + " sessions=" + n + " tag=" + tag);
        }
    }

    /** Every non-sent marker must still be in the store. State read on the UI thread. */
    private static int verifyStoreSync(TermuxActivity activity, String step, String[] markers) {
        final int[] failures = {0};
        runOnUiThreadSync(activity, () -> {
            TermuxService service = activity.getTermuxService();
            SessionUiStateStore store = activity.getTextInputState();
            for (int i = 0; i < service.getTermuxSessionsSize() && i < markers.length; i++) {
                TermuxSession ts = service.getTermuxSession(i);
                TerminalSession s = ts == null ? null : ts.getTerminalSession();
                if (s == null) continue;
                String got = store.getInputText(s.mHandle);
                boolean ok = markers[i] != null && markers[i].equals(got);
                if (!ok) {
                    failures[0]++;
                    log("STORM FAIL " + step + " session" + i
                            + " expected=" + markers[i] + " got=" + got);
                }
            }
        });
        return failures[0];
    }

    /** The LIVE field must show the marker of the current session. Read on the UI thread. */
    private static int verifyLiveSync(TermuxActivity activity, String step, String expected) {
        final String[] got = {null};
        runOnUiThreadSync(activity, () -> {
            android.widget.EditText et = panelEditText(activity);
            got[0] = et == null ? null : et.getText().toString();
        });
        boolean ok = expected != null && expected.equals(got[0]);
        if (!ok) {
            log("STORM FAIL " + step + " live expected=" + expected + " got=" + got[0]);
            return 1;
        }
        return 0;
    }

    private static void selectTab(TermuxActivity activity, int idx) {
        runOnUiThreadSync(activity, () -> activity.getTermuxTerminalSessionClient().switchToSession(idx));
        sleepUi(250);   // let pager animation settle
    }

    private static void openPanel(TermuxActivity activity) {
        runOnUiThreadSync(activity, () -> {
            if (!activity.isTextInputVisible()) {
                setPanelVisible(activity, true);
            }
        });
        sleepUi(150);
    }

    private static void panelClose(TermuxActivity activity) {
        runOnUiThreadSync(activity, () -> {
            if (activity.isTextInputVisible()) {
                setPanelVisible(activity, false);
            }
        });
        sleepUi(150);
    }

    private static void setField(TermuxActivity activity, String text) {
        runOnUiThreadSync(activity, () -> {
            android.widget.EditText ti = panelEditText(activity);
            if (ti != null) {
                ti.setText(text);
                ti.setSelection(text.length());
            }
        });
    }

    private static void kbShow(TermuxActivity activity) {
        runOnUiThreadSync(activity, () -> showIme(activity));
        sleepUi(150);
    }

    private static void kbHide(TermuxActivity activity) {
        runOnUiThreadSync(activity, () -> hideIme(activity));
        sleepUi(150);
    }

    private static void sleepUi(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static String sanitizeHead(String text) {
        if (text == null) return "-";
        StringBuilder out = new StringBuilder();
        int n = Math.min(12, text.length());
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            out.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return out.length() == 0 ? "EMPTY" : out.toString();
    }

    /**
     * Dump the extra-keys panel's fold state. Four numbers describe it, and they are the four the
     * option is made of:
     * <ul>
     *   <li>{@code storedRows} — rows in the layout as stored ({@code extra-keys} / session profile);</li>
     *   <li>{@code effectiveRows} — what the height rule sizes the panel for;</li>
     *   <li>{@code builtRows} — rows the grid was really built with by the last reload, i.e. the
     *       ground truth: if this equals {@code storedRows} while the fold is on, the panel was never
     *       rebuilt and the option is not doing anything;</li>
     *   <li>{@code cols} — columns of the built grid, which grows as rows are folded away.</li>
     * </ul>
     * Both the preference and the window orientation are printed next to them, so a mismatch can be
     * attributed to the preference, to the orientation or to the rebuild.
     */
    private static void dumpExtraKeys(TermuxActivity activity) {
        com.termux.shared.termux.extrakeys.ExtraKeysView ekv = activity.getExtraKeysView();
        if (ekv == null) {
            log("extrakeys: no view");
            return;
        }
        StringBuilder sb = new StringBuilder("extrakeys:");

        int storedRows = 0;
        if (activity.getTermuxTerminalExtraKeys() != null
            && activity.getTermuxTerminalExtraKeys().getExtraKeysInfo() != null) {
            storedRows = activity.getTermuxTerminalExtraKeys().getExtraKeysInfo().getMatrix().length;
        }

        final boolean fold = ekv.isLandscapeCompactActive();
        sb.append(" prefCompact=").append(activity.getPreferences().isExtraKeysCompactLandscapeEnabled(activity));
        sb.append(" prefMode=").append(activity.getPreferences().getExtraKeysCompactMode());
        sb.append(" landscape=").append(
            activity.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
        sb.append(" fold=").append(fold);
        sb.append(" mode=").append(ekv.getCompactMode());
        sb.append(" storedRows=").append(storedRows);
        sb.append(" effectiveRows=").append(
            com.termux.shared.termux.extrakeys.ExtraKeysCompaction.effectiveRowCount(storedRows, fold));
        sb.append(" builtRows=").append(ekv.getReloadedRowCount());
        sb.append(" cols=").append(ekv.getColumnCount());
        if (ekv.getLayoutParams() != null)
            sb.append(" panelH=").append(ekv.getLayoutParams().height);
        sb.append(" vis=").append(vis(ekv));

        log(sb.toString());
    }

    private static void dumpStatus(TermuxActivity activity) {
        StringBuilder sb = new StringBuilder();
        View container = activity.findViewById(R.id.terminal_toolbar_container);
        View slot = activity.findViewById(R.id.terminal_toolbar_slot);
        View panel = activity.findViewById(R.id.terminal_toolbar_text_input_container);
        EditText ti = panelEditText(activity);
        View term = activity.getTerminalView();
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        sb.append("container=").append(vis(container));
        sb.append(" slot=").append(vis(slot));
        sb.append(" panel=").append(vis(panel));
        if (panel != null) sb.append(" panelBounds=").append(bounds(panel));
        if (ti != null) {
            sb.append(" tiFocus=").append(ti.hasFocus());
            sb.append(" tiBounds=").append(bounds(ti));
            if (ti.getLayoutParams() != null)
                sb.append(" tiLPH=").append(ti.getLayoutParams().height);
            sb.append(" tiText=").append(ti.getText().length()).append("ch");
        }
        if (term != null) sb.append(" termFocus=").append(term.hasFocus());
        // Layout diagnostics: root margin/height + pager + toolbar measured sizes.
        View root = activity.findViewById(R.id.activity_termux_root_view);
        if (root != null && root.getLayoutParams() != null) {
            sb.append(" rootH=").append(root.getHeight());
            // The input panel's height limit is a quarter of the root view's CONTENT BOX
            // (height minus padding), so print the three numbers the rule is made of: the box,
            // the implied cap, and the field's current layout height (tiLPH above). On-device
            // oracle for TextInputPanelController.applyPanelHeightLimitForContentView — with
            // the keyboard up it shows whether the padding top-up or a real resize reduced the box.
            final int rootContentH = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
            sb.append(" rootPad=").append(root.getPaddingTop()).append("/").append(root.getPaddingBottom());
            sb.append(" panelBoxH=").append(rootContentH);
            sb.append(" panelCapH=").append(
                com.termux.app.terminal.io.TextInputPanelController.maxPanelHeightForContentHeight(rootContentH));
            sb.append(" rootMB=").append(((android.view.ViewGroup.MarginLayoutParams) root.getLayoutParams()).bottomMargin);
        }
        View pager = activity.findViewById(R.id.terminal_view_pager);
        if (pager != null) sb.append(" pagerH=").append(pager.getHeight());
        if (container != null && container.getLayoutParams() != null) {
            sb.append(" toolbarLP=").append(container.getLayoutParams().height);
        }
        View slotV = activity.findViewById(R.id.terminal_toolbar_slot);
        if (slotV != null) sb.append(" slotH=").append(slotV.getHeight());
        sb.append(" focusOnInput=").append(activity.isFocusOnInputForSession(activity.getCurrentSession()));
        sb.append(" panelVisibleFlag=").append(activity.isTextInputVisible());
        sb.append(" resumeRestore=").append(activity.isResumeFocusRestore());
        sb.append(" switchInProgress=").append(activity.isTerminalPageSwitchInProgress());
        if (imm != null) sb.append(" imeAccepting=").append(imm.isAcceptingText());
        log(sb.toString());
    }

    private static String vis(View v) {
        if (v == null) return "null";
        return (v.getVisibility() == View.VISIBLE ? "V" : v.getVisibility() == View.GONE ? "G" : "I")
            + "(h=" + v.getHeight() + ")";
    }

    private static String bounds(View v) {
        return "[" + v.getLeft() + "," + v.getTop() + "][" + v.getRight() + "," + v.getBottom() + "]";
    }

    // ── tab-strip watcher ──────────────────────────────────────────────────────────────────
    //
    // Why this lives here and not inside ElasticHorizontalScrollView: anything in src/main is
    // part of the RELEASE, and R8 does not save us. proguard-rules.pro has -dontobfuscate and
    // the build uses proguard-android.txt (-dontoptimize), so the shrinker removes an uncalled
    // METHOD — dumpOverscrollState() was indeed stripped — but keeps a field that is only ever
    // incremented, because `x++` READS it and the shrinker's reachability analysis counts that
    // as a use. Deleting the increment is an optimization, and optimizations are off.
    //
    // So the strip is observed entirely through public View API. Not a compromise:
    // getChildAt(0).getTranslationX() IS the elastic displacement — the view writes exactly
    // that value — so what is measured here is what the user sees, frame by frame.
    private static View sWatchStrip;
    private static View sWatchContent;
    private static int sWatchRange;
    private static int sWatchLastScrollX = Integer.MIN_VALUE;
    private static long sWatchUntilMs;
    private static int sWatchTicks;
    private static int sWatchMovedTicks;
    private static int sWatchEndTicks;
    private static int sWatchBounceTicks;
    private static float sWatchPeakTx;

    private static final Runnable sWatchTick = new Runnable() {
        @Override
        public void run() {
            final View strip = sWatchStrip;
            final View content = sWatchContent;
            if (strip == null || content == null) return;
            final int scrollX = strip.getScrollX();
            final float tx = content.getTranslationX();
            sWatchTicks++;
            if (scrollX != sWatchLastScrollX) {
                sWatchMovedTicks++;
                sWatchLastScrollX = scrollX;
            }
            if (Math.abs(tx) > sWatchPeakTx) sWatchPeakTx = Math.abs(tx);
            // Pinned at one of the two ends of the range (with range==0 both ends are the same
            // position, which is correct: a strip that cannot scroll is always at an end).
            if (scrollX == 0 || scrollX == sWatchRange) {
                sWatchEndTicks++;
                // ...and displaced while pinned: this is the bounce actually visible on screen.
                if (tx != 0f) sWatchBounceTicks++;
            }
            if (SystemClock.uptimeMillis() < sWatchUntilMs) {
                strip.postOnAnimation(this);
            } else {
                log("WATCH " + watchSummary() + " scrollX=" + scrollX + " tx=" + tx);
                sWatchStrip = null;
                sWatchContent = null;
            }
        }
    };

    private static void armStripWatch(View strip, View content, int range, long windowMs) {
        sWatchStrip = strip;
        sWatchContent = content;
        sWatchRange = range;
        sWatchLastScrollX = Integer.MIN_VALUE;
        sWatchUntilMs = SystemClock.uptimeMillis() + windowMs;
        sWatchTicks = 0;
        sWatchMovedTicks = 0;
        sWatchEndTicks = 0;
        sWatchBounceTicks = 0;
        sWatchPeakTx = 0f;
        strip.removeCallbacks(sWatchTick);
        strip.postOnAnimation(sWatchTick);
    }

    private static String watchSummary() {
        return "ticks=" + sWatchTicks
                + " moved=" + sWatchMovedTicks
                + " atEnd=" + sWatchEndTicks
                + " bounced=" + sWatchBounceTicks
                + " peakTx=" + sWatchPeakTx;
    }

    /** HorizontalScrollView's own range: content width minus the viewport. */
    private static int stripRange(View strip, View content) {
        return Math.max(0, content.getWidth()
                - (strip.getWidth() - strip.getPaddingLeft() - strip.getPaddingRight()));
    }

    private static void log(String msg) {
        android.util.Log.i(TAG, msg);
    }
}
