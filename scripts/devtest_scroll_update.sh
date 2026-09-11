#!/system/bin/sh
# On-device driver for the "scrolled up / top-line rewrite" repaint test.
#
# Started by scripts/device_scroll_update_test.py. Termux can *read* /data/local/tmp but
# cannot write to it, so the handshake is one-way: the host drops a go/stop file and the
# script waits for it. That makes every step deterministic — no reliance on how long the
# shell takes to run a loop, and the host decides when a phase starts and ends.
#
#   P1  fill the scrollback with identifiable lines              host: wait for the screen to settle, go1
#   P2  rewrite the TOP screen row in place (live bottom)        host: capture S1/S2, stop2
#   P3  stream output at the bottom (host scrolled the view up)  host: capture S4/S5, stop3
#   P4  rewrite the TOP screen row in place (view scrolled up)   host: capture S7/S8, stop4
#
# The top-row rewrites alternate between two very different strings so a pair of captures
# taken at any two moments differs by many cells (a single incremented digit would be close
# to the noise floor).
#
# Lines are kept short so nothing wraps: a wrapped line would break the "one line == one row"
# assumption the host's geometry relies on.

D=/data/local/tmp

waitfor() {
  n=0
  while [ ! -f "$1" ]; do
    sleep 0.1
    n=$((n+1))
    if [ $n -gt 3000 ]; then return 1; fi
  done
  return 0
}

# Rewrite screen row 0 in place, forever, until $1 appears. The cursor is parked on the last
# row afterwards so the blinking cursor stays away from the row under test.
#
# The marker carries a monotonically increasing counter as well as an AAAA/BBBB flip. That is
# deliberate: with a plain two-state alternation the host's captures can alias against the
# loop period (a 1.6 s capture interval against a ~0.32 s half-period lands on the same phase
# every time and reports "nothing changed" even though the row repaints fine). A counter can
# never repeat, so any two captures taken at different moments differ.
top_rewrite_loop() {
  stop=$1
  a=0
  printf '\033[999;1H'
  while [ ! -f "$stop" ]; do
    a=$((a+1))
    if [ $((a % 2)) -eq 0 ]; then
      printf '\033[H\033[2KTOP-%04d-AAAA-%04d' $a $a
    else
      printf '\033[H\033[2KTOP-%04d-BBBB-%04d' $a $a
    fi
    printf '\033[999;1H'
    sleep 0.3
  done
}

printf 'TEST-BEGIN\n'

# ---- P1: fill the scrollback ------------------------------------------------------------
printf '\033[2J\033[H'
i=1
while [ $i -le 300 ]; do
  printf 'LINE-%03d ####\n' $i
  i=$((i+1))
done
printf 'PHASE1-DONE\n'
waitfor $D/go1

# ---- P2: rewrite the TOP screen row in place, view at the live bottom -------------------
top_rewrite_loop $D/stop2
printf 'PHASE2-DONE\n'
waitfor $D/go2

# ---- P3: stream output at the bottom (the host has scrolled the view up) -----------------
i=0
while [ ! -f $D/stop3 ]; do
  i=$((i+1))
  printf 'STREAM-%03d ####\n' $i
  sleep 0.12
done
printf 'PHASE3-DONE\n'
waitfor $D/go3

# ---- P4: rewrite the TOP screen row in place while the view is scrolled up ---------------
top_rewrite_loop $D/stop4
printf 'PHASE4-DONE\n'

echo "TEST-END"
