package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import com.termux.R;
import com.termux.app.terminal.io.SessionUiStateStore;
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
 *   session count- log the number of live sessions
 *   dumpsess     - log per-session handles in service order (for re-key verification)
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
                case "panel open":
                    activity.runOnUiThread(() -> {
                        activity.setTextInputVisible(true);
                        activity.updateToggleTextInputButtonIcon();
                    });
                    break;
                case "panel close":
                    activity.runOnUiThread(() -> {
                        activity.setTextInputVisible(false);
                        activity.updateToggleTextInputButtonIcon();
                    });
                    break;
                case "focus panel":
                    activity.runOnUiThread(() -> {
                        EditText ti = activity.findViewById(R.id.terminal_toolbar_text_input);
                        if (ti != null) ti.requestFocus();
                    });
                    break;
                case "focus term":
                    activity.runOnUiThread(() -> activity.getTerminalView().requestFocus());
                    break;
                case "ime show":
                    activity.runOnUiThread(() -> {
                        View v = activity.getCurrentFocus();
                        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (v != null && imm != null) imm.showSoftInput(v, 0);
                    });
                    break;
                case "ime hide":
                    activity.runOnUiThread(() -> {
                        View v = activity.getCurrentFocus();
                        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (v != null && imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                    });
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
                        EditText ti = activity.findViewById(R.id.terminal_toolbar_text_input);
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
                        EditText ti2 = activity.findViewById(R.id.terminal_toolbar_text_input);
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
                case "dumpsess": {
                    TermuxService svc2 = activity.getTermuxService();
                    StringBuilder dsb = new StringBuilder("DUMPSESS");
                    if (svc2 != null) {
                        for (int i = 0; i < svc2.getTermuxSessionsSize(); i++) {
                            com.termux.shared.termux.shell.command.runner.terminal.TermuxSession ts =
                                    svc2.getTermuxSession(i);
                            TerminalSession s = ts == null ? null : ts.getTerminalSession();
                            dsb.append(" s").append(i).append("=")
                               .append(s == null ? "null" : String.valueOf(s.mHandle.hashCode()));
                        }
                    }
                    log(dsb.toString());
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
            com.termux.shared.termux.shell.command.runner.terminal.TermuxSession ts =
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
        EditText ti = activity.findViewById(R.id.terminal_toolbar_text_input);
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
        log(sb.toString());
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

    private static void dumpStatus(TermuxActivity activity) {
        StringBuilder sb = new StringBuilder();
        View container = activity.findViewById(R.id.terminal_toolbar_container);
        View slot = activity.findViewById(R.id.terminal_toolbar_slot);
        View panel = activity.findViewById(R.id.terminal_toolbar_text_input_container);
        EditText ti = activity.findViewById(R.id.terminal_toolbar_text_input);
        View term = activity.getTerminalView();
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        sb.append("container=").append(vis(container));
        sb.append(" slot=").append(vis(slot));
        sb.append(" panel=").append(vis(panel));
        if (panel != null) sb.append(" panelBounds=").append(bounds(panel));
        if (ti != null) {
            sb.append(" tiFocus=").append(ti.hasFocus());
            sb.append(" tiBounds=").append(bounds(ti));
            sb.append(" tiText=").append(ti.getText().length()).append("ch");
        }
        if (term != null) sb.append(" termFocus=").append(term.hasFocus());
        // Layout diagnostics: root margin/height + pager + toolbar measured sizes.
        View root = activity.findViewById(R.id.activity_termux_root_view);
        if (root != null && root.getLayoutParams() != null) {
            sb.append(" rootH=").append(root.getHeight());
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

    private static void log(String msg) {
        android.util.Log.i(TAG, msg);
    }
}
