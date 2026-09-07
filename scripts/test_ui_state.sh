#!/data/user/0/com.termux/files/usr/bin/bash
# ============================================================================
# UI-state save/restore test suite for com.termux.debug (run on-device).
# Drives the app via TermuxDebugActivity / DEBUG_CMD broadcasts (flag
# 0x01000000 = FLAG_RECEIVER_INCLUDE_BACKGROUND, works with app backgrounded),
# reads state from logcat (tag TIPanelCmd), asserts every scenario.
#
# Usage:  ./test_ui_state.sh            # all tests
#         ./test_ui_state.sh T_name ... # subset
# ============================================================================
set -u
PKG="com.termux.debug"
ACT="$PKG/com.termux.app.TermuxDebugActivity"
LOGTAG="TIPanelCmd"
PASS=0; FAIL=0; FAILED_TESTS=()
UI=""

# ── helpers ─────────────────────────────────────────────────────────────────
say() { echo; echo "── $* ──"; }

cmd() { adb shell am broadcast -f 0x01000000 -a com.termux.debug.DEBUG_CMD --es cmd "$*" >/dev/null 2>&1; sleep 0.5; }

cmda() { adb shell am broadcast -f 0x01000000 -a com.termux.debug.DEBUG_CMD --es cmd "$1" --es arg "$2" >/dev/null 2>&1; sleep 0.5; }

uistate() { adb logcat -c 2>/dev/null; sleep 0.2; cmd uistate; UI=$(adb logcat -d -s "$LOGTAG" 2>/dev/null | grep "UISTATE" | tail -1); }

session_count() {
  adb logcat -c 2>/dev/null
  cmd session_count
  adb logcat -d -s "$LOGTAG" 2>/dev/null | grep "session count" | tail -1 | grep -oE "[0-9]+$"
}

field() { echo "$UI" | tr ' ' '\n' | grep -E "^$1=" | tail -1 | cut -d= -f2-; }

assert_eq() {
  if [ "$2" == "$3" ]; then PASS=$((PASS+1)); echo "  ok   $1 (= $2)"
  else FAIL=$((FAIL+1)); FAILED_TESTS+=("$1"); echo "  FAIL $1 (want $2, got $3)"; fi
}

assert() {
  if eval "$2"; then PASS=$((PASS+1)); echo "  ok   $1"
  else FAIL=$((FAIL+1)); FAILED_TESTS+=("$1"); echo "  FAIL $1"; fi
}

launch_app() { adb shell am start -n "$PKG/com.termux.app.TermuxActivity" >/dev/null 2>&1; sleep 2; }
home()       { adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1; sleep 1.2; }
back()       { adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 1.0; }
app_pid()    { adb shell pidof "$PKG" | tr -d '\r'; }

ime_visible() { adb shell dumpsys input_method 2>/dev/null | grep -q "mInputShown=true" && echo 1 || echo 0; }

wait_ime() { # wait_ime <expected 0|1> [timeout_s]
  local want="$1" t="${2:-6}" i=0
  while [ $i -lt $((t*2)) ]; do
    [ "$(ime_visible)" == "$want" ] && return 0
    sleep 0.5; i=$((i+1))
  done
  return 1
}

gen_output() { # generate terminal history so scroll has something to scroll
  cmda run "seq 1 80"
  sleep 1.8
}

# Normalize: exactly 1 session, panel closed, kb hidden, focus on terminal.
reset_state() {
  launch_app
  local n
  n=$(session_count)
  if [ -z "$n" ] || [ "$n" -eq 0 ]; then cmd new_tab; sleep 1.5; n=$(session_count); fi
  for i in $(seq 1 10); do
    [ "${n:-1}" -le 1 ] && break
    cmd close_tab; sleep 1.0
    n=$(session_count)
  done
  cmd panel_close
  cmd ime_hide
  wait_ime 0 4
  cmd focus_term
  sleep 0.3
}

# ============================================================================
# Tests
# ============================================================================

T_home_return_panel_focus_kb() {
  say "T1: Home→return: panel + focus + caret + keyboard restored"
  cmd panel_open
  cmda ti_text "echo~hello"          # len 10, caret -> 5
  sleep 0.4
  uistate
  assert_eq "panel open"            1 "$(field live_panel)"
  assert_eq "focus on input"        1 "$(field live_tifocus)"
  assert_eq "caret mid"             5 "$(field live_ticaret)"
  home
  launch_app
  wait_ime 1 6
  uistate
  assert_eq "panel still open"      1 "$(field live_panel)"
  assert_eq "focus restored"        1 "$(field live_tifocus)"
  assert_eq "text kept"             "echo_hello" "$(field live_tihead)"
  assert_eq "caret kept"            5 "$(field live_ticaret)"
  assert_eq "IME visible"           1 "$(ime_visible)"
  assert_eq "kbIntent preserved"    1 "$(field kbIntent)"
  cmd panel_close
}

T_home_return_term_focus_no_kb() {
  say "T2: Home→return: terminal focus, keyboard must NOT pop"
  cmd panel_close
  cmd focus_term
  cmd ime_hide
  sleep 0.8
  uistate
  assert_eq "kbIntent=0 (user hid)"  0 "$(field kbIntent)"
  assert_eq "IME hidden before"      0 "$(ime_visible)"
  home
  launch_app
  sleep 2.0
  uistate
  assert_eq "panel closed"           0 "$(field live_panel)"
  assert_eq "focus on terminal"      1 "$(field live_termfocus)"
  assert_eq "IME stays hidden"       0 "$(ime_visible)"
  assert_eq "kbIntent unchanged"     0 "$(field kbIntent)"
}

T_settings_roundtrip() {
  say "T3: Settings→return: keyboard restore via onWindowFocusChanged"
  cmd panel_open
  cmda ti_text "round~trip"
  cmd ime_show
  wait_ime 1 5
  uistate
  assert_eq "IME shown before"       1 "$(ime_visible)"
  adb shell am start -a android.settings.SETTINGS >/dev/null 2>&1
  sleep 2
  back
  sleep 1.5
  uistate
  assert_eq "panel open after back"  1 "$(field live_panel)"
  assert_eq "focus on input"         1 "$(field live_tifocus)"
  assert_eq "text kept"              "round_trip" "$(field live_tihead)"
  wait_ime 1 6
  assert_eq "IME visible"            1 "$(ime_visible)"
  cmd panel_close
}

T_tabs_isolation() {
  say "T4: tabs: per-tab text/caret/panel/scroll isolated"
  gen_output
  cmda ti_text "alpha~text~for~tab~zero"   # len 24, caret -> 12
  cmd scroll_down 15
  sleep 0.4
  cmd new_tab; sleep 1.8
  uistate
  assert_eq "now on tab 1"           1 "$(field live_cur)"
  assert_eq "tab1 panel default off" 0 "$(field live_panel)"
  assert_eq "tab1 fresh text"        "EMPTY" "$(field live_tihead)"
  cmd panel_open
  cmda ti_text "beta~tab~one"
  cmd scroll_down 8
  sleep 0.4
  cmd switch_tab 0; sleep 1.2
  uistate
  assert_eq "back on tab 0"          0 "$(field live_cur)"
  assert_eq "tab0 text restored"     "alpha_text_" "$(field live_tihead)"
  assert_eq "tab0 caret restored"    12 "$(field live_ticaret)"
  assert_eq "tab0 panel restored"    1 "$(field live_panel)"
  assert "tab0 scroll restored (live=$(field live_top) saved=$(field t0top))" \
         "[ \$(field live_top) -eq \$(field t0top) ]"
  assert "tab0 scrolled up"          "[ \$(field live_top) -lt 0 ]"
  cmd switch_tab 1; sleep 1.2
  uistate
  assert_eq "tab1 text restored"     "beta_tab_on" "$(field live_tihead)"
  assert "tab1 scroll restored"      "[ \$(field live_top) -eq \$(field t1top) ]"
  assert_eq "tab1 caret restored"    "$(field t1caret)" "$(field live_ticaret)"
  cmd close_tab; sleep 1.2
  uistate
  assert_eq "1 session after close"  1 "$(field sessions)"
}

T_scroll_preserved_across_home() {
  say "T5: scroll preserved across Home/return; no snap from new output"
  gen_output
  cmd scroll_down 20
  sleep 0.4
  uistate
  local top_before; top_before=$(field live_top)
  assert "scrolled up (top=$top_before)" "[ $top_before -lt 0 ]"
  home
  launch_app
  sleep 1.5
  uistate
  assert_eq "scroll kept"            "$top_before" "$(field live_top)"
  cmda run "echo new-output-line"
  sleep 1.2
  uistate
  assert_eq "no snap to bottom"      "$top_before" "$(field live_top)"
  cmd scroll_up 40
}

T_recreate_preserves_all() {
  say "T6: recreate(): Bundle path — panel, text, caret, focus, scroll, IME"
  gen_output
  cmd panel_open
  cmda ti_text "rotate~me~please"  # len 15, caret -> 7
  cmd scroll_down 10
  sleep 0.4
  uistate
  local top_before; top_before=$(field live_top)
  local pid_before; pid_before=$(app_pid)
  cmd recreate
  sleep 3.0
  uistate
  assert "process survived"          "[ '$(app_pid)' == '$pid_before' ]"
  assert_eq "panel open"             1 "$(field live_panel)"
  assert_eq "text kept"              "rotate_me_p" "$(field live_tihead)"
  assert_eq "caret kept"             7 "$(field live_ticaret)"
  assert_eq "focus on input"         1 "$(field live_tifocus)"
  assert_eq "scroll kept"            "$top_before" "$(field live_top)"
  wait_ime 1 6
  assert_eq "IME restored"           1 "$(ime_visible)"
  cmd panel_close
  cmd scroll_up 30
}

T_process_death() {
  say "T7: force-stop (process death): tabs/text/panel from JSON snapshot"
  gen_output
  cmda ti_text "kill~test~tab~zero"
  cmd new_tab; sleep 1.8
  uistate
  assert_eq "on tab 1"               1 "$(field live_cur)"
  cmd panel_open
  cmda ti_text "kill~test~tab~one"
  sleep 0.5
  local pid_before; pid_before=$(app_pid)
  home
  adb shell am force-stop "$PKG" >/dev/null 2>&1
  sleep 1.5
  assert "process killed"            "[ -z '$(app_pid)' ]"
  launch_app
  sleep 3.0
  uistate
  assert "process restarted"         "[ '$(app_pid)' != '$pid_before' ]"
  assert_eq "tabs restored"          2 "$(field sessions)"
  assert_eq "active tab restored"    1 "$(field live_cur)"
  assert_eq "tab1 text from JSON"    "kill_test_t" "$(field live_tihead)"
  assert_eq "panel restored open"    1 "$(field live_panel)"
  wait_ime 1 6
  assert_eq "IME restored (kbIntent)" 1 "$(ime_visible)"
  cmd panel_close
  cmd close_tab
}

T_close_tab_no_leak() {
  say "T8: close tab: no state leak into the replacement tab"
  gen_output
  cmda ti_text "stale~content~here"
  cmd scroll_down 12
  cmd panel_open
  cmd new_tab; sleep 1.8
  cmd switch_tab 0; sleep 1.2
  cmd close_tab; sleep 1.2
  uistate
  assert_eq "1 session"              1 "$(field sessions)"
  assert_eq "panel closed"           0 "$(field live_panel)"
  assert_eq "text empty"             "EMPTY" "$(field live_tihead)"
  assert_eq "scroll at bottom"       0 "$(field live_top)"
  assert_eq "no saved scroll"        0 "$(field t0top)"
}

T_back_closes_panel() {
  say "T9: Back closes the panel first; app stays alive"
  cmd panel_open
  cmda ti_text "back~test"
  back
  uistate
  assert_eq "panel closed by Back"   0 "$(field live_panel)"
  assert "app alive"                 "[ -n '$(app_pid)' ]"
}

T_toggle_roundtrip() {
  say "T10: toggle open/close/open keeps per-session text"
  cmd tap_toggle; sleep 1.0
  uistate
  assert_eq "panel opened"           1 "$(field live_panel)"
  cmda ti_text "toggle~text"
  cmd tap_toggle; sleep 1.0
  uistate
  assert_eq "panel closed"           0 "$(field live_panel)"
  cmd tap_toggle; sleep 1.0
  uistate
  assert_eq "panel reopened"         1 "$(field live_panel)"
  assert_eq "text kept"              "toggle_text" "$(field live_tihead)"
  cmd panel_close
}

# ============================================================================
# main
# ============================================================================
adb logcat -c 2>/dev/null
ALL_TESTS=(T_home_return_panel_focus_kb T_home_return_term_focus_no_kb
  T_settings_roundtrip T_tabs_isolation T_scroll_preserved_across_home
  T_recreate_preserves_all T_process_death T_close_tab_no_leak
  T_back_closes_panel T_toggle_roundtrip)

if [ $# -gt 0 ]; then ALL_TESTS=("$@"); fi

for t in "${ALL_TESTS[@]}"; do
  reset_state
  $t
done

reset_state
echo
say "RESULTS: PASS=$PASS FAIL=$FAIL"
if [ $FAIL -gt 0 ]; then
  printf 'failed: %s\n' "${FAILED_TESTS[@]}"
  exit 1
fi
exit 0
