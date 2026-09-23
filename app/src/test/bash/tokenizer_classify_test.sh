#!/data/data/com.termux/files/usr/bin/bash
#
# Unit tests for the shell-completion tokenizer + scenario classifier of
# com.termux.app.terminal.io.ShellCompletionProvider.
#
# These bash functions are a faithful port of the pure-Java logic in
# ShellCompletionProvider.java:
#   - splitCommandLine()   -> sp_split
#   - lastWordOf()         -> sp_last_word
#   - stripOuterQuotes()   -> sp_strip_outer_quotes
#   - dequoteLikeBash()    -> sp_dequote_like_bash
#   - classifyScenario()   -> sp_classify (via sp_split)
#
# The tests are run by the REAL device bash
# (/data/data/com.termux/files/usr/bin/bash, an Android port of bash 5.3.9),
# which is what actually performs completion at runtime. This validates that the
# Java word-splitting/classification is self-consistent with — and expressible in
# — the very shell that services completion on this device.
#
# Run:  /data/data/com.termux/files/usr/bin/bash app/src/test/bash/tokenizer_classify_test.sh
# Exit: 0 if every case PASSes, non-zero otherwise.

set -u

# --- Ported tokenizer / classifier (mirrors ShellCompletionProvider.java) ---

# splitCommandLine: fills the global array SP_WORDS.
# Splits on unquoted whitespace; single/double quoted segments keep their quotes
# and stay one word; backslash escapes the next char. ALWAYS appends the trailing
# (possibly empty) token so cword points at the word being typed.
sp_split() {
    local line=$1
    SP_WORDS=()
    local current="" in_single=0 in_double=0
    local i c n=${#line}
    for (( i=0; i<n; i++ )); do
        c=${line:i:1}
        if (( in_single )); then
            current+=$c
            [[ $c == "'" ]] && in_single=0
        elif (( in_double )); then
            current+=$c
            [[ $c == '"' ]] && in_double=0
        elif [[ $c == "'" ]]; then
            current+=$c
            in_single=1
        elif [[ $c == '"' ]]; then
            current+=$c
            in_double=1
        elif [[ $c == '\' ]]; then
            current+=$c
            if (( i+1 < n )); then
                current+=${line:i+1:1}
                (( i++ ))
            fi
        elif [[ $c == ' ' || $c == $'\t' || $c == $'\n' ]]; then
            if [[ -n $current ]]; then
                SP_WORDS+=("$current")
                current=""
            fi
        else
            current+=$c
        fi
    done
    SP_WORDS+=("$current")
}

# lastWordOf: last whitespace-delimited token (no '/' splitting).
sp_last_word() {
    local line=$1
    local i=${#line}
    while (( i > 0 )); do
        local ch=${line:i-1:1}
        [[ $ch == ' ' || $ch == $'\t' || $ch == $'\n' ]] && break
        (( i-- ))
    done
    printf '%s' "${line:i}"
}

# stripOuterQuotes: strip a single layer of surrounding quotes; handle an
# unterminated trailing quote (drop leading quote only).
sp_strip_outer_quotes() {
    local s=$1
    local n=${#s}
    if (( n < 2 )); then printf '%s' "$s"; return; fi
    local first=${s:0:1}
    if [[ $first == '"' || $first == "'" ]]; then
        local last=${s:n-1:1}
        if [[ $last == "$first" ]]; then
            printf '%s' "${s:1:n-2}"
        else
            printf '%s' "${s:1}"
        fi
        return
    fi
    printf '%s' "$s"
}

# dequoteLikeBash: drop ' and " everywhere; resolve backslash escapes.
sp_dequote_like_bash() {
    local s=$1
    local out="" i n=${#s} c
    for (( i=0; i<n; i++ )); do
        c=${s:i:1}
        if [[ $c == "'" ]]; then continue; fi
        if [[ $c == '"' ]]; then continue; fi
        if [[ $c == '\' ]]; then
            if (( i+1 < n )); then out+=${s:i+1:1}; fi
            (( i++ ))
            continue
        fi
        out+=$c
    done
    printf '%s' "$out"
}

# Regex helpers (bash ERE) mirroring the Java String.matches() anchors.
# Java matches() is fully anchored, so we anchor with ^...$.
_re() { [[ $1 =~ ^($2)$ ]]; }

# classifyScenario(words[], cword) — port of the Java switch/if tree.
# Uses the global SP_WORDS; prints the scenario name.
sp_classify_words() {
    local cword=$1
    local n=${#SP_WORDS[@]}
    local last="" prev="" cmd=""
    (( n > 0 )) && last=${SP_WORDS[cword]}
    if (( cword > 0 && n > cword )); then prev=${SP_WORDS[cword-1]}; fi
    (( n > 0 )) && cmd=${SP_WORDS[0]}

    # History expansion
    if [[ ${last:0:1} == "!" && ${#last} -gt 1 && ${last:0:2} != "!{" && ${last:0:3} != "!([" ]]; then
        echo HISTORY_EXPANSION; return
    fi

    # VARIABLE: $VAR/${VAR} forms, or export/unset/... builtins.
    local is_var=0
    if [[ ${last:0:1} == '$' \
          && ${last:0:2} != '$(' \
          && ${last:0:4} != '$(((' \
          && ${last:0:2} != '$[' ]] \
          && ! _re "$last" '\$\{?[A-Za-z_][A-Za-z0-9_]*\[.*'; then
        is_var=1
    fi
    if (( is_var )) || [[ $cmd == export || $cmd == unset || $cmd == readonly \
                          || $cmd == declare || $cmd == typeset ]]; then
        echo VARIABLE; return
    fi

    # First-word NAME=value assignment -> PATH (value completes as path).
    if (( cword == 0 )); then
        local eqpos=${last%%=*}
        if [[ $last == *=* && ${#eqpos} -gt 0 ]] && _re "$last" '[A-Za-z_][A-Za-z0-9_]*=.*'; then
            echo PATH; return
        fi
    fi

    # Redirection operators.
    case $prev in
        '>'|'>>'|'<'|'<<'|'<<<'|'&>'|'&>>') echo REDIRECTION; return ;;
        *) if _re "$prev" '[0-9]+(&?>+|<+|>>)'; then echo REDIRECTION; return; fi ;;
    esac

    # First-word path literal (unless remote-looking) -> PATH.
    local first_remote=0
    if _re "$last" '[A-Za-z0-9._-]+:.*' || _re "$last" '[^/@[:space:]]+@[^/@[:space:]]+:.*' \
       || [[ $last == *"://"* ]]; then first_remote=1; fi
    if (( ! first_remote )) && [[ ${last:0:1} == '/' || ${last:0:1} == '~' || ${last:0:1} == '.' \
            || $last == */* \
            || ( ( ${last:0:1} == "'" || ${last:0:1} == '"' ) && $last == */* ) ]]; then
        echo PATH; return
    fi

    # Array subscript.
    if _re "$last" '\$(\{)?[A-Za-z_][A-Za-z0-9_]*\[.*'; then
        echo ARRAY_INDEX; return
    fi

    if (( cword == 0 )); then echo COMMAND_NAME; return; fi

    # Command context after separator / keyword / process substitution.
    case $prev in
        ';'|'|'|'&'|'&&'|'||'|'|&'|'('|')'|'{'|'<('|'>('|'&>(') echo COMMAND_CONTEXT; return ;;
    esac
    if [[ ${prev:0:2} == '<(' || ${prev:0:2} == '>(' || ${prev:0:3} == '&>(' ]]; then
        echo COMMAND_CONTEXT; return
    fi
    case $prev in
        time|coproc|do|then|else|elif) echo COMMAND_CONTEXT; return ;;
    esac
    if [[ $prev == *=* ]]; then
        local peq=${prev%%=*}
        if [[ ${#peq} -gt 0 ]] && _re "$prev" '[A-Za-z_][A-Za-z0-9_]*=.*'; then
            echo COMMAND_CONTEXT; return
        fi
    fi

    # kill / trap signal-or-job.
    if [[ $cmd == kill || $cmd == trap ]] && [[ ${last:0:1} == '-' || ${last:0:1} == '%' ]]; then
        echo SIGNAL_JOB; return
    fi

    # type/which/command/builtin/enable -> command context.
    case $cmd in
        type|which|command|builtin|enable) echo COMMAND_CONTEXT; return ;;
    esac

    # [ / test unary file operator operand -> PATH.
    if _re "$prev" '-(f|d|e|r|w|x|L|h|S|b|c|p|u|g|k|s)'; then
        echo PATH; return
    fi

    # Empty word.
    if [[ -z $last ]]; then
        if _re "$prev" '-(f|d|e|r|w|x|L|h|S|b|c|p|u|g|k|s)'; then echo PATH; return; fi
        echo COMMAND_NAME; return
    fi

    # bare word after `in` in for/case/select.
    if [[ $prev == in ]] && _re "$cmd" '(for|case|select)'; then
        echo PATH; return
    fi

    # Path-looking token (unless remote).
    local looks_remote=0
    if _re "$last" '[A-Za-z0-9._-]+:.*' || _re "$last" '[^/@[:space:]]+@[^/@[:space:]]+:.*' \
       || [[ $last == *"://"* ]]; then looks_remote=1; fi
    if (( ! looks_remote )) && [[ ${last:0:1} == '/' || ${last:0:1} == '~' || ${last:0:1} == '.' \
            || $last == */* \
            || ( ( ${last:0:1} == '"' || ${last:0:1} == "'" ) \
                 && ( $last == *'*'* || $last == *'?'* || $last == *'['* ) ) ]]; then
        echo PATH; return
    fi

    echo COMPSPEC
}

# classify from a raw line (tokenizes then classifies the last word).
sp_classify() {
    sp_split "$1"
    local cword=$(( ${#SP_WORDS[@]} - 1 ))
    (( cword < 0 )) && cword=0
    sp_classify_words "$cword"
}

# --- Test harness ---
PASS=0
FAIL=0

check() {
    # check <description> <expected> <actual>
    local desc=$1 exp=$2 act=$3
    if [[ $act == "$exp" ]]; then
        printf 'PASS: %s\n' "$desc"
        (( PASS++ ))
    else
        printf 'FAIL: %s (expected [%s], got [%s])\n' "$desc" "$exp" "$act"
        (( FAIL++ ))
    fi
}

# ---- lastWordOf ----
check "lastWordOf 'git status' -> status" "status" "$(sp_last_word 'git status')"
check "lastWordOf 'git' -> git"           "git"    "$(sp_last_word 'git')"
check "lastWordOf 'git ' -> '' (empty)"   ""       "$(sp_last_word 'git ')"
check "lastWordOf 'cd /data/da' -> /data/da" "/data/da" "$(sp_last_word 'cd /data/da')"

# ---- splitCommandLine ----
sp_split 'cp "my file.txt" /tmp/'
check "split 'cp \"my file.txt\" /tmp/' -> 3 words" "3" "${#SP_WORDS[@]}"
check "split keeps quotes inside word[1]" '"my file.txt"' "${SP_WORDS[1]}"
check "split word[0] == cp" "cp" "${SP_WORDS[0]}"
check "split word[2] == /tmp/" "/tmp/" "${SP_WORDS[2]}"

# trailing-space token behaviour
sp_split 'git '
check "split 'git ' -> 2 words (cmd + empty)" "2" "${#SP_WORDS[@]}"
check "split 'git ' trailing word empty" "" "${SP_WORDS[1]}"

# ---- dequoteLikeBash ----
check 'dequote "\"my file\"" -> my file' "my file" "$(sp_dequote_like_bash '"my file"')"
check 'dequote "my\ dir" -> my dir'       "my dir"  "$(sp_dequote_like_bash 'my\ dir')"

# ---- stripOuterQuotes ----
check 'stripOuterQuotes "\"my file\"" -> my file' "my file" "$(sp_strip_outer_quotes '"my file"')"
check "stripOuterQuotes \"'abc'\" -> abc" "abc" "$(sp_strip_outer_quotes "'abc'")"
check 'stripOuterQuotes unterminated "\"abc" -> abc' "abc" "$(sp_strip_outer_quotes '"abc')"

# ---- classifyScenario ----
check "classify 'git' -> COMMAND_NAME"        "COMMAND_NAME"      "$(sp_classify 'git')"
check "classify 'cd /da' -> PATH"             "PATH"             "$(sp_classify 'cd /da')"
check "classify 'cat foo > ' -> REDIRECTION"  "REDIRECTION"      "$(sp_classify 'cat foo > ')"
check "classify 'echo \$HO' -> VARIABLE"      "VARIABLE"         "$(sp_classify 'echo $HO')"
check "classify 'kill -' -> SIGNAL_JOB"       "SIGNAL_JOB"       "$(sp_classify 'kill -')"
check "classify 'echo a ; ' -> COMMAND_CONTEXT" "COMMAND_CONTEXT" "$(sp_classify 'echo a ; ')"
check "classify 'git st' -> COMPSPEC"         "COMPSPEC"         "$(sp_classify 'git st')"
check "classify '!gi' -> HISTORY_EXPANSION"   "HISTORY_EXPANSION" "$(sp_classify '!gi')"

# ---------------------------------------------------------------------------
printf 'TOTAL: %d PASS, %d FAIL\n' "$PASS" "$FAIL"
(( FAIL == 0 ))
