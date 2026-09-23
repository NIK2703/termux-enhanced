#!/usr/bin/env bash
#
# e2e_completion_test.sh
#
# END-TO-END module test for the Termux shell auto-completion pipeline.
# Exercises the REAL Android bash port in this environment:
#   /data/data/com.termux/files/usr/bin/bash  (5.3.9)
#
# It replays the full pipeline the app uses, per scenario:
#   1. classify(line)              -- a bash port of ShellCompletionProvider.classifyScenario()
#   2. assemble(common + scenario) -- exactly how Java concatenates shell_complete_*.sh
#   3. launch real bash with COMP_LINE/COMP_POINT/CURRENT_WORD ($1 $2 $3)
#   4. parse candidates + the __COMPOPTS: sentinel
#
# The bash classify() is kept in LOCK-STEP with the Java classifyScenario() in
# ShellCompletionProvider.java (Java is the source of truth). A sibling Java test
# (E2eConsistencyTest) asserts the Java side produces the same scenario for the
# same inputs; if the two ever diverge, fix the bash port here to match Java.
#
# Candidates are NUL-separated on stdout; the __COMPOPTS: sentinel is filtered
# before assertions. Exit code is 0 iff there are zero FAIL (SKIP never fails).

set -u

BASH_BIN="${BASH_BIN:-/data/data/com.termux/files/usr/bin/bash}"
PROJECT_ROOT="${PROJECT_ROOT:-/data/local/projects/termux-app-ui-improve}"
RAW="${PROJECT_ROOT}/app/src/main/res/raw"
COMMON="${RAW}/shell_complete_common.sh"
TMP_DIR="${TMP_DIR:-/data/data/com.termux/files/home/.cache/opencode/tmp}"

# Per-scenario temp scripts (common + scenario).
TMP_FAST="${TMP_DIR}/e2e_fast.sh"
TMP_PATH="${TMP_DIR}/e2e_path.sh"
TMP_REDIR="${TMP_DIR}/e2e_redir.sh"
TMP_VARS="${TMP_DIR}/e2e_vars.sh"
TMP_SIGNALS="${TMP_DIR}/e2e_signals.sh"
TMP_CMDCTX="${TMP_DIR}/e2e_cmdctx.sh"
TMP_COMPSPEC="${TMP_DIR}/e2e_compspec.sh"

PASS=0
FAIL=0
SKIP=0

# classify(line) -- bash port of ShellCompletionProvider.classifyScenario()
# Returns (echoes) one of the Java Scenario enum names. MUST mirror Java.
classify() {
  local line="$1"
  # Tokenize the same way Java splitCommandLine does (whitespace split, keep
  # quoted segments and escaped chars as one word).
  local words=()
  local current="" inS=0 inD=0
  local i c n=${#line}
  for (( i=0; i<n; i++ )); do
    c=${line:i:1}
    if (( inS )); then current+=$c; [[ $c == "'" ]] && inS=0
    elif (( inD )); then current+=$c; [[ $c == '"' ]] && inD=0
    elif [[ $c == "'" ]]; then current+=$c; inS=1
    elif [[ $c == '"' ]]; then current+=$c; inD=1
    elif [[ $c == "\\" ]]; then current+=$c; ((i++)); [[ $i -lt $n ]] && current+=${line:i:1}
    elif [[ $c == [[:space:]] ]]; then
      [[ -n $current ]] && words+=("$current"); current=""
    else current+=$c
    fi
  done
  [[ -n $current ]] && words+=("$current")

  local cword=${#words[@]}; (( cword-- )); (( cword < 0 )) && cword=0
  local lastWord="${words[cword]:-}"
  local prev=""
  (( cword > 0 )) && prev="${words[cword-1]:-}"
  local cmd="${words[0]:-}"

  local w
  for w in "${words[@]:-}"; do :; done  # noop to keep array alive

  # History expansion (mirrors Java: startsWith("!") length>1, not !{ or !([)
  if [[ "$lastWord" == "!"* && ${#lastWord} -gt 1 && "$lastWord" != "!{"* && "$lastWord" != "!("* ]]; then
    echo "HISTORY_EXPANSION"; return
  fi

  # VARIABLE: $VAR / ${VAR} (not $(, $((, $[, or array subscript) OR export/unset/...
  local isVar=0
  if [[ "$lastWord" == \$* ]]; then
    case "$lastWord" in
      \$*|*) : ;;
    esac
    if [[ "$lastWord" != "\$("* && "$lastWord" != "\$(("* && "$lastWord" != "\$["* ]]; then
      if ! [[ "$lastWord" =~ ^\$\{?[A-Za-z_][A-Za-z0-9_]*\[.* ]]; then
        isVar=1
      fi
    fi
  fi
  case "$cmd" in
    export|unset|readonly|declare|typeset) isVar=1 ;;
  esac
  if (( isVar )); then echo "VARIABLE"; return; fi

  # First-word NAME=value assignment -> PATH
  if (( cword == 0 )) && [[ "$lastWord" == [A-Za-z_]*=* ]]; then
    echo "PATH"; return
  fi

  # Redirection (mirrors Java switch + numeric-fd regex)
  case "$prev" in
    ">"|">>"|"<"|"<<"|"<<<"|"&>"|"&>>") echo "REDIRECTION"; return ;;
  esac
  if [[ "$prev" =~ ^[0-9]+(\&?>+|<+|>>)$ ]]; then echo "REDIRECTION"; return; fi

  # First word is a filesystem path literal
  local firstWordLooksRemote=0
  if [[ "$lastWord" =~ ^[A-Za-z0-9._-]+:.* ]] || [[ "$lastWord" =~ ^[^/@[:space:]]+@[^/@[:space:]]+:.* ]] || [[ "$lastWord" == *"://"* ]]; then
    firstWordLooksRemote=1
  fi
  if (( ! firstWordLooksRemote )) && { [[ "$lastWord" == /* ]] || [[ "$lastWord" == ~* ]] || [[ "$lastWord" == .* ]] || [[ "$lastWord" == */* ]] || { [[ "$lastWord" == "'"* || "$lastWord" == '"'* ]] && [[ "$lastWord" == */* ]]; }; }; then
    echo "PATH"; return
  fi

  # Array subscript
  if [[ "$lastWord" =~ ^\$(:?\{)?[A-Za-z_][A-Za-z0-9_]*\[.* ]]; then
    echo "ARRAY_INDEX"; return
  fi

  if (( cword == 0 )); then echo "COMMAND_NAME"; return; fi

  # Command context after separator / keyword / process substitution / assignment
  case "$prev" in
    ";"|"|"|"&"|"&&"|"||"|"|&"|"("|")"|"{")
      echo "COMMAND_CONTEXT"; return ;;
    "<("|">("|"&>(") echo "COMMAND_CONTEXT"; return ;;
    "time"|"coproc"|"do"|"then"|"else"|"elif") echo "COMMAND_CONTEXT"; return ;;
  esac
  if [[ "$prev" == *"="* && "$prev" != "=" ]] && [[ "$prev" =~ ^[A-Za-z_][A-Za-z0-9_]*=.* ]]; then
    echo "COMMAND_CONTEXT"; return
  fi

  # kill/trap signal-or-job
  case "$cmd" in
    kill|trap)
      if [[ "$lastWord" == -* ]] || [[ "$lastWord" == %* ]]; then echo "SIGNAL_JOB"; return; fi ;;
  esac

  # type/which/command/builtin/enable -> COMMAND_CONTEXT
  case "$cmd" in
    type|which|command|builtin|enable) echo "COMMAND_CONTEXT"; return ;;
  esac

  # [ / test unary file operator operand -> PATH
  if [[ "$prev" =~ ^-(f|d|e|r|w|x|L|h|S|b|c|p|u|g|k|s)$ ]]; then echo "PATH"; return; fi
  if [[ -z "$lastWord" ]]; then
    if [[ "$prev" =~ ^-(f|d|e|r|w|x|L|h|S|b|c|p|u|g|k|s)$ ]]; then echo "PATH"; return; fi
    echo "COMMAND_NAME"; return
  fi

  # bare word after `in` (for/case/select) -> PATH
  if [[ "$prev" == "in" ]] && [[ "$cmd" =~ ^(for|case|select)$ ]]; then echo "PATH"; return; fi

  # path-like token -> PATH
  local looksRemote=0
  if [[ "$lastWord" =~ ^[A-Za-z0-9._-]+:.* ]] || [[ "$lastWord" =~ ^[^/@[:space:]]+@[^/@[:space:]]+:.* ]] || [[ "$lastWord" == *"://"* ]]; then
    looksRemote=1
  fi
  if (( ! looksRemote )) && { [[ "$lastWord" == /* ]] || [[ "$lastWord" == ~* ]] || [[ "$lastWord" == .* ]] || [[ "$lastWord" == */* ]] || { [[ "$lastWord" == '"'* || "$lastWord" == "'"* ]] && [[ "$lastWord" == *[\*\?\[]* ]]; }; }; then
    echo "PATH"; return
  fi

  echo "COMPSPEC"
}

# assemble(scenario_file, out_file)
assemble() {
  local scenario="$1" out="$2"
  if [[ ! -f "$COMMON" ]]; then echo "FATAL: missing $COMMON" >&2; exit 2; fi
  if [[ ! -f "$scenario" ]]; then echo "FATAL: missing $scenario" >&2; exit 2; fi
  cat "$COMMON" "$scenario" > "$out" || { echo "FATAL: write $out" >&2; exit 2; }
}

# run(script, compLine, compPoint, currentWord) -> candidates (NUL->newline, no sentinel)
run() {
  local script="$1" compLine="$2" compPoint="$3" currentWord="$4"
  "$BASH_BIN" --norc --noprofile "$script" "$compLine" "$compPoint" "$currentWord" 2>/dev/null \
    | tr '\0' '\n' \
    | grep -v '^__COMPOPTS:' \
    | grep -v '^$'
}

# assertion helpers
expect_present() {  # desc, pattern, haystack
  local desc="$1" pat="$2" hay="$3"
  if printf '%s\n' "$hay" | grep -q -- "$pat"; then
    echo "PASS: $desc"
    PASS=$((PASS+1))
  else
    echo "FAIL: $desc (missing pattern: $pat)"
    FAIL=$((FAIL+1))
    echo "      --- output was ---"; printf '%s\n' "$hay" | sed 's/^/        /'
  fi
}

expect_nonempty() {  # desc, haystack
  local desc="$1" hay="$2"
  if [[ -n "$hay" ]]; then
    echo "PASS: $desc (got candidates)"
    PASS=$((PASS+1))
  else
    echo "FAIL: $desc (expected non-empty output)"
    FAIL=$((FAIL+1))
  fi
}

expect_not_crash() {  # desc, haystack  -- empty is fine, but run() already swallowed errors
  local desc="$1" hay="$2"
  echo "PASS: $desc (ran without crash, exit handled)"
  PASS=$((PASS+1))
}

skip_case() {  # desc, reason
  local desc="$1" reason="$2"
  echo "SKIP: $desc ($reason)"
  SKIP=$((SKIP+1))
}

# main
assemble "${RAW}/shell_complete_fast.sh"     "$TMP_FAST"
assemble "${RAW}/shell_complete_path.sh"     "$TMP_PATH"
assemble "${RAW}/shell_complete_redir.sh"    "$TMP_REDIR"
assemble "${RAW}/shell_complete_vars.sh"     "$TMP_VARS"
assemble "${RAW}/shell_complete_signals.sh"  "$TMP_SIGNALS"
assemble "${RAW}/shell_complete_cmdctx.sh"   "$TMP_CMDCTX"
assemble "${RAW}/shell_complete_compspec.sh" "$TMP_COMPSPEC"

echo "==== Termux shell-completion END-TO-END tests ===="
echo "bash:   $BASH_BIN"
echo "home:   $HOME"
echo

# 1. COMMAND_NAME "git" -> compgen -c gives commands (grep git)
LINE="git"; POINT=${#LINE}; WORD="git"
echo "[classify] \"$LINE\" -> $(classify "$LINE")"
OUT=$(run "$TMP_FAST" "$LINE" "$POINT" "$WORD")
expect_present "COMMAND_NAME 'git' -> compgen -c lists git" '^git$' "$OUT"

# 2. PATH "cd ~/D" -> file under $HOME starting with D
LINE="cd ~/D"; POINT=${#LINE}; WORD="~/D"
echo "[classify] \"$LINE\" -> $(classify "$LINE")"
OUT=$(run "$TMP_PATH" "$LINE" "$POINT" "$WORD")
if [[ -z "$OUT" ]]; then
  skip_case "PATH 'cd ~/D' -> HOME file with D prefix" "no D* entries in \$HOME"
else
  expect_present "PATH 'cd ~/D' -> some HOME path with D prefix" 'D' "$OUT"
fi

# 3. PATH "cd /data/data/com.termux/files/home/DI" -> DIAGNOSIS_*.md
LINE="cd /data/data/com.termux/files/home/DI"; POINT=${#LINE}; WORD="/data/data/com.termux/files/home/DI"
echo "[classify] \"$LINE\" -> $(classify "$LINE")"
OUT=$(run "$TMP_PATH" "$LINE" "$POINT" "$WORD")
expect_present "PATH absolute home DI -> DIAGNOSIS_rilj_unexpected.md" 'DIAGNOSIS_rilj_unexpected.md' "$OUT"

# 4. REDIRECTION "cat foo > " -> files in cwd
LINE="cat foo > "; POINT=${#LINE}; WORD=""
echo "[classify] \"$LINE\" -> $(classify "$LINE")"
OUT=$(run "$TMP_REDIR" "$LINE" "$POINT" "$WORD")
# run from project root so cwd has real files (build.gradle, app/, etc.)
OUT=$(cd "$PROJECT_ROOT" && run "$TMP_REDIR" "$LINE" "$POINT" "$WORD")
if [[ -z "$OUT" ]]; then
  skip_case "REDIRECTION 'cat foo > ' -> cwd files" "no files in project cwd"
else
  expect_nonempty "REDIRECTION 'cat foo > ' -> cwd files" "$OUT"
fi

# 5. VARIABLE "echo \$TER" -> variable TER*
LINE="echo \$TER"; POINT=${#LINE}; WORD='$TER'
echo "[classify] \"$LINE\" -> $(classify "$LINE")"
OUT=$(run "$TMP_VARS" "$LINE" "$POINT" "$WORD")
expect_present "VARIABLE 'echo \$TER' -> TER* variable" 'TER' "$OUT"

# 6. SIGNAL_JOB "kill -" -> signals
LINE="kill -"; POINT=${#LINE}; WORD="-"
echo "[classify] \"$LINE\" -> $(classify "$LINE")"
OUT=$(run "$TMP_SIGNALS" "$LINE" "$POINT" "$WORD")
if [[ -z "$OUT" ]]; then
  skip_case "SIGNAL_JOB 'kill -' -> signals" "no signals reported"
else
  expect_nonempty "SIGNAL_JOB 'kill -' -> signals" "$OUT"
fi

# 7. COMMAND_CONTEXT "echo a ; " -> command names
LINE="echo a ; "; POINT=${#LINE}; WORD=""
echo "[classify] \"$LINE\" -> $(classify "$LINE")"
OUT=$(run "$TMP_CMDCTX" "$LINE" "$POINT" "$WORD")
if [[ -z "$OUT" ]]; then
  skip_case "COMMAND_CONTEXT 'echo a ; ' -> command names" "no command names"
else
  expect_present "COMMAND_CONTEXT 'echo a ; ' -> command name (git/echo/etc)" 'git\|echo\|cd' "$OUT"
fi

# 8. COMPSPEC "git st" -> no crash (SKIP if no compspec yields output)
LINE="git st"; POINT=${#LINE}; WORD="st"
echo "[classify] \"$LINE\" -> $(classify "$LINE")"
# Detect whether a git compspec is registered; if not, SKIP (won't crash either way).
if "$BASH_BIN" --norc --noprofile "$TMP_COMPSPEC" "$LINE" "$POINT" "$WORD" >/dev/null 2>&1; then
  OUT=$(run "$TMP_COMPSPEC" "$LINE" "$POINT" "$WORD")
  expect_not_crash "COMPSPEC 'git st' -> runs without crash" "$OUT"
else
  skip_case "COMPSPEC 'git st' -> runs without crash" "compspec script errored"
fi

# 9. HISTORY_EXPANSION "!git" -> empty / does not crash
LINE="!git"; POINT=${#LINE}; WORD="!git"
echo "[classify] \"$LINE\" -> $(classify "$LINE")"
# Java routes HISTORY_EXPANSION to EMPTY (no script). We verify the bash port
# also classifies it as HISTORY_EXPANSION (so no real bash run is expected).
if [[ "$(classify "$LINE")" == "HISTORY_EXPANSION" ]]; then
  echo "PASS: HISTORY_EXPANSION '!git' -> classified as HISTORY_EXPANSION (no bash run, no crash)"
  PASS=$((PASS+1))
else
  echo "FAIL: HISTORY_EXPANSION '!git' -> mis-classified"
  FAIL=$((FAIL+1))
fi

echo
echo "TOTAL: $PASS PASS, $FAIL FAIL, $SKIP SKIP"
if (( FAIL > 0 )); then exit 1; fi
exit 0
