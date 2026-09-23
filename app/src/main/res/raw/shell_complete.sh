# shell_complete.sh
#
# PERSISTENT completion dispatcher. ONE long-lived bash process serves every
# completion request for the life of the provider, eliminating the per-request
# spawn + bash-completion load + .bashrc scan that dominated the old design
# (~160-280ms each). The Java side lazy-starts this once via
#   bash --norc --noprofile shell_complete.sh
# and drives it over stdin/stdout.
#
# WIRE PROTOCOL
#   Request  (one line, \n-terminated, fields split by \x01):
#       TYPE \x01 WORD \x01 CWD[\x01 COMP_LINE]
#     TYPE is one of: CMD PATH REDIR VAR SIGNAL COMPSPEC
#     WORD is the exact completable token (already sigil/prefix-shaped by Java;
#          e.g. VAR arrives WITHOUT the leading '$', SIGNAL arrives as SIGxxx).
#     CWD  is the working directory for file/path resolution (may be empty).
#     COMP_LINE is the OPTIONAL full command line, present ONLY for COMPSPEC.
#       The Java side omits it for CMD/PATH/REDIR/VAR/SIGNAL to keep the wire
#       minimal; the dispatcher accepts either 3 or 4 fields.
#   Response (to stdout):
#       zero or more NUL-terminated candidates
#       at most one  "__COMPOPTS:opt,opt,...\0"  side-channel record
#       a literal line  "__END__\n"  marking end-of-response
#
# SPEED CONTRACT: the main loop does NOT re-source bash-completion per request and does NOT
# scan .bashrc. All string shaping (VAR sigils, SIGNAL SIG-prefix, COMPSPEC
# wrapper-skip, NAME= assignment strip) is done in Java; bash runs exactly ONE
# compgen/glob per request. COMPSPEC is best-effort: it runs a compspec already
# registered in THIS session, lazy-loading bash-completion at most once per process
# for an unknown command (see __ensure_bc); if that yields nothing, Java falls back
# locally. This is the deliberate speed-over-faithfulness trade-off.

export LC_ALL=${LC_ALL:-C.UTF-8}
export LANG=${LANG:-C.UTF-8}

# NOTE: no `set -m` here. Java starts this bash via `setsid` (see
# ShellCompletionProvider.ensurePersistent), which makes bash the leader of its
# own session/process group, so Java's killpg(-pid) already targets only our
# process tree. `set -m` would be redundant.
set +H 2>/dev/null                      # disable histexpand ('!' -> history)
shopt -s extglob 2>/dev/null
shopt -s nullglob dotglob 2>/dev/null

__MAX_CANDIDATES="${__MAX_CANDIDATES:-300}"

# Default word-break set. A -F programmable completer (e.g. git) reads
# COMP_WORDS/COMP_CWORD and expects readline tokenization (spaces AND the
# word-break metacharacters produce their own word slots). Matching the
# interactive COMP_WORDBREAKS is what makes `git commit --am` yield nothing
# (as bash does) instead of a spurious `--amend`.
if [[ -z ${COMP_WORDBREAKS:-} ]]; then
  COMP_WORDBREAKS=$' \t\n'"'"'()<>;|&=:@~'
fi

# Word-break predicate, hoisted to script scope so it is defined ONCE, not
# re-declared inside __termux_tokenize on every completion request.
__is_wb() { [[ "${COMP_WORDBREAKS:-$' \t\n'"'"'()<>;|&=:@~'}" == *"$1"* ]]; }

# ---- Readline-faithful tokenizer: split COMP_LINE into COMP_WORDS / COMP_CWORD
# honoring quotes, escaped whitespace AND COMP_WORDBREAKS. Ported behaviourally
# from the old shell_complete_common.sh so a -F completer sees the exact
# COMP_WORDS/COMP_CWORD it did before the persistent migration.
__termux_tokenize() {
  local line=$1 point=$2
  COMP_WORDS=(); COMP_CWORD=0
  local WB=${COMP_WORDBREAKS:-$' \t\n"'"'"'()<>;|&=:@~'}
  local i=0 n=${#line} inq=0 indq=0 tok=""
  while (( i < n )); do
    local c=${line:i:1}
    if (( inq )); then
      tok+=$c; (( i++ ));
      [[ $c == "'" ]] && inq=0
    elif (( indq )); then
      tok+=$c; (( i++ ));
      [[ $c == '"' ]] && indq=0
    else
      case $c in
        "'") inq=1; tok+=$c; (( i++ ));;
        '"') indq=1; tok+=$c; (( i++ ));;
        "\\") tok+=$c; (( i++ )); if (( i < n )); then tok+=${line:i:1}; (( i++ )); fi;;
        *) if __is_wb "$c"; then
             if [[ "$c" == "~" && $((i+1)) -lt $n && ${line:i+1:1} == / ]]; then
               tok+="$c/"; (( i+=2 ));
             elif [[ "$c" == [=:@~\(\[\<\>] ]]; then
               [[ -n $tok ]] && COMP_WORDS+=("$tok"); tok=""
               COMP_WORDS+=("$c")
             else
               if [[ -n $tok ]]; then COMP_WORDS+=("$tok"); tok=""; fi
               local j=$i; while (( j < n )) && __is_wb "${line:j:1}" \
                                  && [[ ${line:j:1} != [=:@~\(\[\<\>] ]]; do (( j++ )); done
               COMP_WORDS+=(""); i=$j
             fi
           else tok+=$c; (( i++ )); fi;;
      esac
    fi
    if (( i <= point )) && (( ${#tok} > 0 || ${#COMP_WORDS[@]} > 0 )); then
      COMP_CWORD=${#COMP_WORDS[@]}
    fi
  done
  if [[ -n $tok ]]; then COMP_WORDS+=("$tok"); fi
  if (( point >= n )) && [[ -z $tok ]]; then COMP_CWORD=${#COMP_WORDS[@]}; fi
}

# Emit resolved -o comp-options as a side-channel sentinel the Java reader parses.
__emit_opts() {
  local o acc=""
  for o in "$@"; do acc+="$o,"; done
  [[ -n $acc ]] && printf '__COMPOPTS:%s\0' "${acc%,}"
}

# ---- File/dir fallback (tilde/hidden aware). $1 = word token, $2 = cwd.
# Ported verbatim (behaviourally) from the old shell_complete_common.sh so the
# candidate set + trailing-slash directory marking stays identical.
# Cache ~user -> home dir (getent is an expensive fork per lookup).
# Keyed by bare user name; value is the home dir (or empty if unknown, in which
# case we fall back to $HOME at the call site).
declare -A __HOME_CACHE=()
__home_of() {
  local __u=$1 __h
  [[ -n ${__HOME_CACHE[$__u]+x} ]] && { printf '%s' "${__HOME_CACHE[$__u]}"; return; }
  __h=$(getent passwd "$__u" 2>/dev/null)
  [[ -n $__h ]] && { IFS=: read -r _ _ _ _ _ __h _ <<<"$__h"; } || __h=""
  __HOME_CACHE[$__u]=${__h:-}
  printf '%s' "${__HOME_CACHE[$__u]}"
}

__file_fallback() {
  __emit_opts filenames
  local __w=$1 __cwd=$2
  case "$__w" in
    \~)   __w=$HOME ;;
    \~/*) __w=${HOME}${__w:1} ;;
    \~*)  local __u=${__w#\~}; __u=${__u%%/*}
          local __h; __h=$(__home_of "$__u")
          [[ -z $__h ]] && __h=$HOME; __w=$__h${__w:1+${#__u}+1} ;;
  esac
  local __dir=${__w%/*}
  if [[ -z $__dir ]]; then
    if [[ "$__w" == /* ]]; then __dir=/; else __dir=.; fi
  fi
  local __prefix=${__w##*/}
  # A relative parent must resolve against the request CWD, not this process's
  # own cwd (which never changes). Absolute/tilde-expanded parents are used as-is.
  local __base="$__dir"
  if [[ "$__dir" != /* && -n "$__cwd" ]]; then
    __base="${__cwd%/}/$__dir"
  fi
  local __glob="${__base}/${__prefix}*"
  local __n=0 __p __rel
  while IFS= read -r __p; do
    [[ -z "$__p" ]] && continue
    [[ $__n -ge ${__MAX_CANDIDATES} ]] && break
    # Re-derive the token the user actually typed (relative to __dir, not __base)
    # so the Java-side prefix match against the typed word holds.
    if [[ "$__dir" == /* || -z "$__cwd" ]]; then
      __rel="$__p"
    else
      __rel="${__p#${__cwd%/}/}"
    fi
    if [[ -d "$__p" ]]; then
      printf '%s/\0' "$__rel"
    else
      printf '%s\0' "$__rel"
    fi
    __n=$((__n+1))
  done < <(compgen -G "$__glob" 2>/dev/null)
}

# ---- CMD: command names with type classification.
__do_cmd() {
  local __word=$1 __c __n=0
  # Exactly ONE fork (compgen -c). Per-candidate classification (alias/builtin/
  # function) previously used `$(type -t ...)` — a command-substitution subshell
  # that forked once PER CANDIDATE (hundreds of forks for `git <prefix>`). We
  # drop it here: the Java side already treats a bare name (no "\tcat" suffix)
  # as a COMMAND candidate, so classification is preserved for insertion. This
  # keeps CMD at a single external process per completion request.
  while IFS= read -r __c; do
    [[ -z "$__c" ]] && continue
    [[ $__n -ge ${__MAX_CANDIDATES} ]] && break
    printf '%s\0' "$__c"
    __n=$((__n+1))
  done < <(compgen -c -- "$__word" 2>/dev/null)
  __emit_opts nospace nosort noquote
}

# ---- VAR: variable names. WORD arrives already stripped of its $ / ${ sigil by
# Java, which also re-wraps the emitted candidates, so here we emit bare names.
__do_var() {
  local __word=$1 __v __n=0
  while IFS= read -r __v; do
    [[ -z "$__v" ]] && continue
    [[ $__n -ge ${__MAX_CANDIDATES} ]] && break
    printf '%s\0' "$__v"
    __n=$((__n+1))
  done < <(compgen -v -- "$__word" 2>/dev/null)
  __emit_opts noquote nospace nosort
}

# ---- SIGNAL: WORD arrives as a SIGxxx prefix (Java maps -USR1 -> SIGUSR1).
# We emit the matching signal names WITHOUT the SIG prefix; Java re-prepends '-'.
__do_signal() {
  local __word=$1 __s __n=0
  while IFS= read -r __s; do
    [[ -z "$__s" ]] && continue
    [[ $__n -ge ${__MAX_CANDIDATES} ]] && break
    printf '%s\0' "${__s#SIG}"
    __n=$((__n+1))
  done < <(compgen -A signal -- "$__word" 2>/dev/null)
  __emit_opts noquote nospace nosort
}

# ---- Lazy, one-time source of the bash-completion helper stack. Only invoked
# from the COMPSPEC path when a command has NO compspec registered yet, so the
# common CMD/PATH/VAR/SIGNAL requests never pay this cost. After the first call
# the loader (_completion_loader) and any autoloaded compspecs stay resident for
# the life of this persistent process, so subsequent COMPSPEC requests are fast.
__BC_LOADED=0
__ensure_bc() {
  [[ $__BC_LOADED -eq 1 ]] && return 0
  __BC_LOADED=1
  local __bc
  for __bc in "${TERMUX_PREFIX:-/data/data/com.termux/files/usr}/share/bash-completion/bash_completion" \
             "/usr/share/bash-completion/bash_completion"; do
    [[ -f "$__bc" ]] && { source "$__bc" 2>/dev/null; break; }
  done
}

# ---- COMPSPEC: WORD = real command's current word; the COMP_LINE first token is
# the (already wrapper-resolved) command name. Behaviour: see SPEED CONTRACT.
# __tried: commands whose compspec lookup already failed, so the expensive
# _completion_loader is not re-run for the same unknown command every request.
declare -A __tried=()

__do_compspec() {
  local __word=$1 __cwd=$2 __line=$3
  # Resolve through command wrappers (sudo/xargs/env/time/…) and any inline
  # NAME=value assignments, so `sudo systemctl sta` runs systemctl's compspec
  # (S3/S4/S5). Mirrors the original compspec template's __wrappers loop.
  local __wrappers=" env sudo xargs time nohup nice exec stdbuf timeout ionice setsid "
  local __words=($__line)
  local __cmd=${__words[0]:-}
  local __i=0
  while [[ -n $__cmd && " $__wrappers " == *" $__cmd "* ]]; do
    __i=$((__i + 1))
    __cmd=${__words[__i]:-}
    [[ -z $__cmd ]] && break
    # skip an inline NAME=value assignment that follows the wrapper
    while [[ $__cmd == [A-Za-z_]*=* ]]; do
      __i=$((__i + 1))
      __cmd=${__words[__i]:-}
      [[ -z $__cmd ]] && break 2
    done
  done

  COMPREPLY=()
  local __spec
  __spec=$(complete -p -- "$__cmd" 2>/dev/null)
  # No compspec yet: lazy-load via _completion_loader (as the original compspec
  # template did) so a real -F completer such as `git --` can yield its options.
  # Sourced at most once per process (see __ensure_bc); no-op if bash-completion
  # is not installed, in which case the command-name fallback below still applies.
  if [[ -z $__spec && -n $__cmd && -z ${__tried[$__cmd]+x} ]]; then
    __ensure_bc
    if type -t _completion_loader >/dev/null 2>&1; then
      _completion_loader "$__cmd" 2>/dev/null
      __spec=$(complete -p -- "$__cmd" 2>/dev/null)
    fi
    [[ -z $__spec ]] && __tried[$__cmd]=1
  fi
  if [[ -n $__spec ]]; then
    # Parse the compspec line WITHOUT awk: walk fields, capture the option
    # string after "-o" and the function name after "-F".
    local __opts="" __func=""
    local __f __prev=""
    local __capture_func=0
    for __f in $__spec; do
      case "$__prev" in
        -o) __opts+=",$__f" ;;
      esac
      if (( __capture_func )); then
        __func=$__f
        __capture_func=0
      fi
      case "$__f" in
        -F) __capture_func=1 ;;
        *)  ;;
      esac
      __prev=$__f
    done
    if [[ -n $__func ]] && type -t "$__func" >/dev/null 2>&1; then
      # Populate the vars a -F completer reads with readline-faithful tokenization.
      # NOTE: COMP_WORDS / COMP_LINE / COMP_POINT are passed purely via environment,
      # NOT via stdin. Redirect the function's stdin from /dev/null so a stray `read`
      # inside it cannot swallow the dispatcher's request channel and hang until
      # timeout. CRITICAL: the `exec 0</dev/null` MUST run inside a subshell — a bare
      # one in this (parent) process would permanently close the dispatcher's stdin
      # and the whole persistent process would exit after the first -F compspec
      # (e.g. git). The subshell also isolates the COMPREPLY array the -F function
      # populates, so emit it from inside the subshell (one entry per NUL-terminated
      # field) and read it back into the parent's COMPREPLY.
      COMP_LINE="$__line"; COMP_POINT=${#__line}
      __termux_tokenize "$__line" "$COMP_POINT"
      local __reply
      while IFS= read -r -d '' __reply; do
        COMPREPLY+=("$__reply")
      done < <( exec 0</dev/null; "$__func" 2>/dev/null
                for __c in "${COMPREPLY[@]}"; do printf '%s\0' "$__c"; done )
      __opts=""
    else
      local __rest=${__spec#complete }
      __rest=${__rest% [^ ]*}
      COMPREPLY=($(eval "compgen $__rest -- '$__word'" 2>/dev/null))
    fi
    if [[ -n $__opts ]]; then
      # De-dup + sort unique option tokens (replaces the old awk|tr|sort|paste).
      local __seen="" __o __acc=""
      local IFS=,
      for __o in ${__opts#,}; do
        [[ -z $__o ]] && continue
        case ",$__seen," in
          *",$__o,"*) continue ;;
        esac
        __seen+="$__o,"; __acc+="$__o,"
      done
      __acc=${__acc%,}
      [[ -n $__acc ]] && printf '__COMPOPTS:%s\0' "$__acc"
    fi
  fi

  # Command-name fallback (S9): when the compspec produced nothing AND the FIRST
  # token of the line is a wrapper (sudo/env/xargs/time/…), the wrapper's argument
  # is itself a command name → compgen -c. It must NOT be an `elif` on "$__spec":
  # the lazy loader may register a minimal placeholder compspec (-F
  # _comp_complete_minimal) for an unknown argument like `gi`, which yields nothing
  # yet made $__spec non-empty; the wrapper fallback still has to run. Never emit
  # for path-like or assignment-like words.
  if [[ ${#COMPREPLY[@]} -eq 0 ]]; then
    local __first=${__line%% *}
    if [[ " $__wrappers " == *" $__cmd "* || " $__wrappers " == *" $__first "* ]]; then
      case "$__word" in
        /*|~*|.*|*/*) ;;
        *[!A-Za-z0-9_.,+-]*) ;;
        *=*) ;;
        *) COMPREPLY=($(compgen -c -- "$__word" 2>/dev/null)) ;;
      esac
    fi
  fi
  local __n=0 __c
  for __c in "${COMPREPLY[@]}"; do
    [[ $__n -ge ${__MAX_CANDIDATES} ]] && break
    printf '%s\0' "$__c"; __n=$((__n+1))
  done
}

# ---- Main dispatch loop. Read one \x01-delimited request per line.
while IFS= read -r __req; do
  # Split on \x01 into TYPE / WORD / CWD / COMP_LINE.
  IFS=$'\x01' read -r __type __word __cwd __line <<<"$__req"
  case "$__type" in
    CMD)      __do_cmd "$__word" ;;
    # REDIR is handled identically to PATH (plain file/dir fallback); Java now
    # sends PATH for both scenarios, so no separate REDIR arm is needed here.
    PATH)     __file_fallback "$__word" "$__cwd" ;;
    VAR)      __do_var "$__word" ;;
    SIGNAL)   __do_signal "$__word" ;;
    COMPSPEC) __do_compspec "$__word" "$__cwd" "$__line" ;;
    *)        : ;;  # unknown TYPE -> empty response
  esac
  # A leading newline guarantees __END__ lands on its OWN line even though the
  # candidate records above are NUL-terminated (no trailing newline), so the
  # Java reader's readLine()-based framing sees an exact "__END__" line.
  printf '\n__END__\n'
done
