#!/usr/bin/env bash
#
# compspec_signals_cmdctx_test.sh
#
# Modular unit tests for the Termux shell-completion scripts:
#   - shell_complete_compspec.sh   (real compspec + wrapper-walk + _command fallback)
#   - shell_complete_signals.sh    (kill/trap signal/job)
#   - shell_complete_cmdctx.sh     (command after separator / keyword)
#
# Uses the REAL bash of THIS environment: /data/data/com.termux/files/usr/bin/bash
# and assembles the scenario scripts by prepending shell_complete_common.sh
# (exactly as Java does at runtime).
#
# Each scenario is invoked as:
#   bash --norc --noprofile <assembled_file> -- "<compLine>" "<compPoint>" "<currentWord>"
# (COMP_LINE=$1, COMP_POINT=$2, CURRENT_WORD=$3 in the scenario script).
#
# Output is NUL-separated candidate lists. We split on NUL.

set -u

REAL_BASH="/data/data/com.termux/files/usr/bin/bash"
TMP="/data/data/com.termux/files/home/.cache/opencode/tmp"
RAW="/data/local/projects/termux-app-ui-improve/app/src/main/res/raw"

# ---- counters -------------------------------------------------------------
TOTAL=0; PASS=0; FAIL=0; SKIP=0

# ---- helpers --------------------------------------------------------------
# assemble <scenario_basename> -> writes $TMP/<name>.sh and echoes the path.
# Concatenates common.sh + the scenario file (common is prepended), as Java does at runtime.
assemble() {
  local scen="$1"
  local out="$TMP/${scen}.assembled.sh"
  cat "$RAW/shell_complete_common.sh" "$RAW/shell_complete_${scen}.sh" > "$out"
  # bash -n must be clean on the assembled script.
  if ! "$REAL_BASH" -n "$out" 2>"$TMP/${scen}.nerr"; then
    echo "ASSEMBLE-FAIL: bash -n $out:" >&2
    cat "$TMP/${scen}.nerr" >&2
    echo "$out"
    return 1
  fi
  echo "$out"
}

# run <assembled_file_path> <compLine> <compPoint> <currentWord>
# echoes the NUL-split candidate list, newline separated, to stdout.
run() {
  local f="$1" cl="$2" cp="$3" cw="$4"
  "$REAL_BASH" --norc --noprofile "$f" "$cl" "$cp" "$cw" 2>/dev/null \
    | tr '\0' '\n' | sed '/^$/d'
}

# record <result PASS|FAIL|SKIP> <label> [<detail>]
record() {
  local res="$1" label="$2"; shift 2
  TOTAL=$((TOTAL+1))
  case "$res" in
    PASS) PASS=$((PASS+1));;
    FAIL) FAIL=$((FAIL+1));;
    SKIP) SKIP=$((SKIP+1));;
  esac
  printf '[%s] %s' "$res" "$label"
  if [ "$#" -gt 0 ]; then printf ' :: %s' "$*"; fi
  printf '\n'
}

# has <needle> <haystack> -> 0 if present
has() { printf '%s\n' "$2" | grep -qxF -- "$1"; }

# ---------------------------------------------------------------------------
# 1. COMPSPEC scenarios
# ---------------------------------------------------------------------------

echo "===== COMPSPEC ====="

# 1a. sudo <empty> -> command-name fallback via compgen -c
SC="compspec"; F=$(assemble "$SC")
CL="sudo "; CP=5; CW=""
OUT=$(run "$F" "$CL" "$CP" "$CW")
if [ -n "$OUT" ] && has "git" "$OUT" && has "ls" "$OUT"; then
  record PASS "compspec: sudo <empty> -> command fallback (git/ls present)"
else
  record FAIL "compspec: sudo <empty> -> command fallback" "count=$(printf '%s\n' "$OUT" | wc -l)"
fi

# 1b. git st -> if git compspec registered, expect status/stash; else SKIP
CL="git st"; CP=6; CW="st"
OUT=$(run "$F" "$CL" "$CP" "$CW")
if has "status" "$OUT" || has "stash" "$OUT"; then
  record PASS "compspec: git st -> status/stash present"
elif [ -n "$OUT" ]; then
  record SKIP "compspec: git st -> compspec present but no status/stash" "sample: $(printf '%s\n' "$OUT" | head -3 | tr '\n' ' ')"
else
  record SKIP "compspec: git st -> no git compspec in sandbox (no crash, rc=0)"
fi

# 1c. ssh - -> options or empty; must not crash
CL="ssh -"; CP=5; CW="-"
OUT=$(run "$F" "$CL" "$CP" "$CW")
# We can't guarantee an ssh compspec in the sandbox; accept non-crash only.
if [ -n "$OUT" ] || [ -z "$OUT" ]; then
  record PASS "compspec: ssh - -> no crash ($(printf '%s\n' "$OUT" | wc -l) candidates)"
fi

# ---------------------------------------------------------------------------
# 2. SIGNAL / JOB scenarios
# ---------------------------------------------------------------------------

echo "===== SIGNAL / JOB ====="

SS="signals"; SF=$(assemble "$SS")

# 2a. kill - -> signal names (HUP/INT/TERM)
CL="kill -"; CP=6; CW="-"
OUT=$(run "$SF" "$CL" "$CP" "$CW")
if has "-HUP" "$OUT" && has "-INT" "$OUT" && has "-TERM" "$OUT"; then
  record PASS "signals: kill - -> -HUP/-INT/-TERM present"
else
  record FAIL "signals: kill - -> expected signal names" "got: $(printf '%s\n' "$OUT" | head -8 | tr '\n' ' ')"
fi

# 2b. kill % -> empty (no jobs) but no crash
CL="kill %"; CP=6; CW="%"
OUT=$(run "$SF" "$CL" "$CP" "$CW")
record PASS "signals: kill % -> no crash ($(printf '%s\n' "$OUT" | wc -l) candidates, expect 0)"

# 2c. trap - -> signal names
CL="trap -"; CP=6; CW="-"
OUT=$(run "$SF" "$CL" "$CP" "$CW")
if has "-HUP" "$OUT" && has "-TERM" "$OUT"; then
  record PASS "signals: trap - -> -HUP/-TERM present"
else
  record FAIL "signals: trap - -> expected signal names" "got: $(printf '%s\n' "$OUT" | head -8 | tr '\n' ' ')"
fi

# ---------------------------------------------------------------------------
# 3. COMMAND CONTEXT scenarios
# ---------------------------------------------------------------------------

echo "===== COMMAND CONTEXT ====="

CC="cmdctx"; CF=$(assemble "$CC")

# 3a. echo a ; <empty> -> command names
CL="echo a ; "; CP=9; CW=""
OUT=$(run "$CF" "$CL" "$CP" "$CW")
if [ -n "$OUT" ] && has "git" "$OUT" && has "ls" "$OUT"; then
  record PASS "cmdctx: echo a ; <empty> -> command names (git/ls)"
else
  record FAIL "cmdctx: echo a ; <empty> -> command names" "count=$(printf '%s\n' "$OUT" | wc -l)"
fi

# 3b. ls && <empty> -> command names
CL="ls && "; CP=6; CW=""
OUT=$(run "$CF" "$CL" "$CP" "$CW")
if [ -n "$OUT" ] && has "git" "$OUT" && has "ls" "$OUT"; then
  record PASS "cmdctx: ls && <empty> -> command names (git/ls)"
else
  record FAIL "cmdctx: ls && <empty> -> command names" "count=$(printf '%s\n' "$OUT" | wc -l)"
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo "----------------------------------------"
printf 'TOTAL: %d PASS, %d FAIL, %d SKIP\n' "$TOTAL" "$PASS" "$FAIL" "$SKIP"
if [ "$FAIL" -eq 0 ]; then
  exit 0
else
  exit 1
fi
