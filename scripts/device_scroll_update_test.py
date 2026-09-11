#!/usr/bin/env python3
"""On-device end-to-end test: does the terminal repaint correctly when it is scrolled up
and when a script changes data at the top?

Run:
    python scripts/device_scroll_update_test.py [--serial HOST:5555]

Why this shape
--------------
The two scenarios under test are:

  A) the view is scrolled up a few lines from the live bottom and output keeps arriving;
  B) a script changes data at the TOP of its screen in place (status line, progress bar).

A regression in either shows up as a *frozen* row, not a crash. Scenario A is the awkward
one to check with screenshots, because its correct result is "nothing on screen changed" —
which is also what a completely frozen app looks like. So the test separates the two:

  * A  is "the visible text does not move while output streams" (measured as a zero diff
       over the text area) AND "the new output is there after scrolling back down";
  * B  is "the top screen row repaints", which has a non-empty expected diff and is
       therefore the discriminating check. It is run twice: at the live bottom (the change
       must land on the topmost visible row) and while scrolled up (it must land further
       down the screen, on the row where that screen row now sits).

Synchronisation is a file handshake, not a wall-clock schedule: screencap + diffing over
wireless adb is far too slow for fixed sleeps to stay aligned. The host drops
/data/local/tmp/goN and /data/local/tmp/stopN; the script only ever reads them (Termux can
read that directory but cannot write to it). Phases therefore start and end when the *host*
decides, not when the shell happens to get there.

Scrolling is driven by keys, not by flings: Shift+PageDown is bound to doScroll(+rows), so
repeating it clamps at the live bottom *pixel-exactly* (verified: 0 changed pixels), which
is what makes "reset to the bottom" trustworthy. The small "a few lines" offset uses an
injected drag whose velocity is allowed to decay to zero before the finger lifts, so no
fling is launched.

Geometry is measured from the captures at runtime (see measure_grid); the row pitch depends
on the session's font size, so nothing is hardcoded. The terminal is left with the soft
keyboard open throughout — that keeps the grid height constant, and every measurement stays
inside a window of rows at the top of the grid, well clear of the extra-keys row.
"""

import argparse
import os
import subprocess
import sys
import tempfile
import time
import zlib
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device_screen import ADB, capture, parse, row_bytes, write_png, changed_bands  # noqa: E402

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT_LOCAL = os.path.join(REPO, "scripts", "devtest_scroll_update.sh")
SCRIPT_REMOTE = "/data/local/tmp/devtest_scroll_update.sh"
TMP = "/data/local/tmp"
GO = [TMP + "/go1", TMP + "/go2", TMP + "/go3"]
STOP = [TMP + "/stop2", TMP + "/stop3", TMP + "/stop4"]

TEXT_X = (0, 1000)          # excludes the scrollbar strip: the thumb's left edge reaches
                            # past x=1000, and a wider window silently counts the thumb's own
                            # repaint as "the text changed"
SCROLLBAR_X = (1000, 1080)
GRID_Y0 = 145               # below the tab bar
GRID_Y1 = 1560              # safely inside the grid (the grid continues below this)
MIN_BAND_PX = 60            # cursor blinks land around 6 px; a real row change is hundreds


class Device:
    def __init__(self, serial, adb=ADB):
        self.serial = serial
        self.adb = adb

    def sh(self, cmd, timeout=60):
        return subprocess.run([self.adb, "-s", self.serial, "shell", cmd],
                              capture_output=True, timeout=timeout)

    def grab(self, path=None, png=True):
        raw = capture(self.serial, self.adb)
        if path:
            with open(path, "wb") as f:
                f.write(raw)
            if png:
                w, h, fmt, bpp, stride, body = parse(raw)
                write_png(path[:-4] + ".png", w, h, fmt, bpp, stride, body)
        return raw

    def text_diff(self, a, b):
        """Changed pixels in the terminal text area, ignoring blink-sized noise bands."""
        bands = [x for x in changed_bands(a, b, GRID_Y0, GRID_Y1, 5, TEXT_X[0], TEXT_X[1])
                 if x[2] >= MIN_BAND_PX]
        return sum(n for _, _, n in bands), bands

    def reset_bottom(self, n=20):
        """Shift+PageDown scrolls a page toward the newest output and clamps there."""
        for _ in range(n):
            self.sh("input keycombination KEYCODE_SHIFT_LEFT KEYCODE_PAGE_DOWN")
            time.sleep(0.2)
        time.sleep(1.2)

    def drag(self, y_from, y_to, steps=5, hold_ms=700):
        """Drag the grid. Finger DOWN moves the view into history.

        The pause before the UP lets the velocity tracker decay, so the gesture ends with
        ~zero velocity and no fling is launched: the resulting offset is the finger travel,
        not an unpredictable momentum carry.
        """
        self.sh("input motionevent DOWN 540 %d" % y_from)
        for k in range(1, steps + 1):
            self.sh("input motionevent MOVE 540 %d" % (y_from + (y_to - y_from) * k // steps))
            time.sleep(0.15)
        time.sleep(hold_ms / 1000.0)
        self.sh("input motionevent UP 540 %d" % y_to)
        time.sleep(1.2)


def ink_bands(raw, min_ink=3):
    """Maximal y-runs containing text, in the terminal grid area."""
    w, h, fmt, bpp, stride, body = parse(raw)
    bands, cur = [], None
    for y in range(GRID_Y0, GRID_Y1):
        rb = row_bytes(body, y, w, bpp, stride)
        n = 0
        for x in range(0, TEXT_X[1], 2):
            i = x * bpp
            if rb[i] < 120 and rb[i + 1] < 120 and rb[i + 2] < 120:
                n += 1
                if n >= min_ink:
                    break
        on = n >= min_ink
        if on and cur is None:
            cur = [y, y]
        elif on:
            cur[1] = y
        elif cur is not None:
            bands.append(tuple(cur))
            cur = None
    if cur is not None:
        bands.append(tuple(cur))
    return bands


def measure_grid(raw):
    """(first text row top, row pitch) measured from a capture, or (None, None)."""
    bands = ink_bands(raw)
    if len(bands) < 4:
        return None, None
    tops = [b[0] for b in bands]
    diffs = [tops[i + 1] - tops[i] for i in range(len(tops) - 1)]
    # Blank rows only ever produce multiples of the pitch, and glyphs with/without ascenders
    # nudge a band top by a pixel, so neither min() nor mean() is stable. The pitch itself is
    # by far the most common gap, so take the mode over a plausible range.
    cands = [d for d in diffs if 18 <= d <= 60]
    if not cands:
        return tops[0], None
    pitch = Counter(cands).most_common(1)[0][0]
    return tops[0], pitch


def row_hashes(raw, y0, y1):
    w, h, fmt, bpp, stride, body = parse(raw)
    return [zlib.crc32(row_bytes(body, y, w, bpp, stride)[:TEXT_X[1] * bpp])
            for y in range(y0, y1)]


def pixel_shift(a, b, y0, y1, max_d=700):
    """How far (in pixels) B's content sits *below* A's, by whole-row correlation.

    Each text row is hashed and the offset with the best match rate wins. Row-level (rather
    than glyph-level) matching needs no font metrics, and a uniform synthetic pitch is
    deliberately avoided: it accumulates rounding drift over a tall window and breaks the
    match even when the content really did move by whole rows.
    """
    ha = row_hashes(a, y0, y1)
    hb = row_hashes(b, y0, y1)
    blank = Counter(ha).most_common(1)[0][0]
    idx = [i for i in range(len(ha)) if ha[i] != blank]
    best_d, best = 0, -1.0
    for d in range(0, max_d):
        tot = hit = 0
        for i in idx:
            j = i + d
            if j >= len(hb):
                break
            tot += 1
            if hb[j] == ha[i]:
                hit += 1
        if tot < 20:
            break
        rate = hit / tot
        if rate > best:
            best, best_d = rate, d
    return best_d, best


def wait_stable(dev, label, stable_polls=2, timeout=120):
    prev = dev.grab()
    stable, start = 0, time.time()
    while time.time() - start < timeout:
        time.sleep(0.8)
        cur = dev.grab()
        n, _ = dev.text_diff(prev, cur)
        prev = cur
        stable = stable + 1 if n == 0 else 0
        if stable >= stable_polls:
            print("   [sync] %s: screen settled" % label)
            return True
    print("   [sync] %s: TIMEOUT" % label)
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="192.168.105.245:5555")
    ap.add_argument("--adb", default=ADB)
    ap.add_argument("--outdir", default=os.path.join(tempfile.gettempdir(), "devtest"))
    ns = ap.parse_args()

    os.makedirs(ns.outdir, exist_ok=True)
    dev = Device(ns.serial, ns.adb)
    results = []

    def check(name, ok, detail=""):
        results.append((name, ok, detail))
        print(("  PASS  " if ok else "  FAIL  ") + name + ("  " + detail if detail else ""))

    def snap(tag):
        return dev.grab(os.path.join(ns.outdir, tag + ".raw"))

    def go(i):
        dev.sh("touch " + GO[i])
        print("   -> %s" % GO[i])

    def stop(i):
        dev.sh("touch " + STOP[i])
        print("   -> %s" % STOP[i])

    def show(a, b, title):
        total, bands = dev.text_diff(a, b)
        print("\n== %s ==" % title)
        for y0, y1, n in bands:
            print("   y=%4d..%-4d  px=%d" % (y0, y1, n))
        return total, bands

    # ---- setup -------------------------------------------------------------------------
    print("pushing the test script ...")
    subprocess.run([ns.adb, "-s", ns.serial, "push", SCRIPT_LOCAL, SCRIPT_REMOTE],
                   capture_output=True, check=True)
    dev.sh("chmod 644 " + SCRIPT_REMOTE)
    dev.sh("rm -f " + " ".join(GO + STOP))

    print("launching Termux ...")
    dev.sh("am start -n com.termux/.app.TermuxActivity")
    time.sleep(2)
    dev.sh("input keyevent 4")      # drop the soft keyboard if it is up
    time.sleep(1)
    dev.sh("input keyevent 66")     # fresh prompt line
    time.sleep(1)

    print("resetting the scroll position to the live bottom ...")
    dev.reset_bottom()

    print("starting the scenario ...")
    dev.sh("input text 'sh%s%s'" % ("%s", SCRIPT_REMOTE))
    dev.sh("input keyevent 66")
    wait_stable(dev, "P1-filled")

    # Geometry is measured *after* the fill, never before: on a freshly (re)installed app the
    # terminal is empty apart from the prompt, so there are no text rows to measure.
    s0 = snap("s0_filled")
    top0, pitch = measure_grid(s0)
    print("   measured grid: first text row top=%s pitch=%s" % (top0, pitch))
    if pitch is None:
        print("FATAL: could not measure the terminal grid (is the terminal empty?)")
        return 2

    # ---- B1: rewrite the TOP line at the live bottom -------------------------------------
    go(0)
    time.sleep(1.0)
    s1 = snap("s1_top_before")
    time.sleep(1.6)
    s2 = snap("s2_top_after")
    total, bands = show(s1, s2, "B1  top screen row rewritten in place, view at the LIVE BOTTOM")
    check("B1 the top line was repainted", total > 200, "changed px=%d" % total)
    check("B1 exactly one row changed", len(bands) == 1, "bands=%d" % len(bands))
    check("B1 the change is on the topmost text row of the screen",
          len(bands) == 1 and abs(bands[0][0] - top0) <= 8,
          "changed top=%s, first text row=%s" % (bands[0][0] if bands else None, top0))
    check("B1 the changed band is one text row tall",
          len(bands) == 1 and (bands[0][1] - bands[0][0]) <= pitch + 8,
          "band height=%s pitch=%s" % (bands[0][1] - bands[0][0] if bands else None, pitch))
    stop(0)
    wait_stable(dev, "P2-done")

    # ---- A: stream while scrolled up ------------------------------------------------------
    print("\nscrolling the view up a few lines (drag, no fling) ...")
    dev.drag(700, 1000)
    s3 = snap("s3_scrolled_up")
    d_px, rate = pixel_shift(s0, s3, top0, GRID_Y1)
    scrolled_rows = int(round(d_px / float(pitch)))
    print("   content moved %d px = %d rows (row-match %.2f)" % (d_px, scrolled_rows, rate))

    total, bands = show(s0, s3, "sanity  the view really is scrolled into history")
    check("the drag scrolled the view up by a few lines (and not a whole page)",
          2 <= scrolled_rows <= 30, "scrolled_rows=%d, changed px=%d" % (scrolled_rows, total))

    go(1)
    time.sleep(1.5)
    s4 = snap("s4_streaming_1")
    time.sleep(1.7)
    s5 = snap("s5_streaming_2")
    total, bands = show(s4, s5, "A  output streaming while the view is scrolled up")
    sb_px = sum(n for _, _, n in
                changed_bands(s4, s5, GRID_Y0, GRID_Y1, 5, SCROLLBAR_X[0], SCROLLBAR_X[1]))
    moved_px, moved_rate = pixel_shift(s4, s5, top0, GRID_Y1)
    print("   scrollbar strip changed px=%d, content shift=%d px (match %.2f)"
          % (sb_px, moved_px, moved_rate))
    check("A  visible text does NOT move while scrolled up and output streams",
          total == 0, "changed text px=%d" % total)
    check("A  the content did not shift by a whole row either",
          moved_px == 0, "shift=%d px" % moved_px)
    # Positive control. A completely frozen app would also report "no text change", so the
    # scrollbar thumb — which must keep tracking the growing transcript — has to have moved.
    # Without this, "the text did not move" cannot be told apart from "nothing repaints".
    check("A  positive control: the scrollbar thumb moved (the app is alive, not frozen)",
          sb_px > 200, "scrollbar px=%d" % sb_px)
    stop(1)
    wait_stable(dev, "P3-done")

    # ---- B2: rewrite the TOP line while scrolled up ---------------------------------------
    print("\nresetting to the bottom, then scrolling up a few lines again ...")
    dev.reset_bottom()
    s6a = snap("s6a_bottom_again")
    dev.drag(700, 1000)
    s6 = snap("s6_scrolled_up_again")
    d_px2, rate2 = pixel_shift(s6a, s6, top0, GRID_Y1)
    rows2 = int(round(d_px2 / float(pitch)))
    print("   content moved %d px = %d rows (row-match %.2f)" % (d_px2, rows2, rate2))
    check("the view is scrolled up a few lines before the top-row rewrite",
          2 <= rows2 <= 30, "scrolled_rows=%d" % rows2)

    go(2)
    time.sleep(1.0)
    s7 = snap("s7_top_b_scrolled")
    time.sleep(1.6)
    s8 = snap("s8_top_b_scrolled_2")
    total, bands = show(s7, s8, "B2  top screen row rewritten in place while SCROLLED UP")
    check("B2 the top line was repainted while scrolled up", total > 200, "changed px=%d" % total)
    check("B2 exactly one row changed", len(bands) == 1, "bands=%d" % len(bands))
    check("B2 the changed band is one text row tall",
          len(bands) == 1 and (bands[0][1] - bands[0][0]) <= pitch + 8,
          "band height=%s pitch=%s" % (bands[0][1] - bands[0][0] if bands else None, pitch))
    if bands:
        changed_row = int(round((bands[0][0] - top0) / float(pitch)))
        print("   the change landed on viewport row %d (scroll offset was %d rows)"
              % (changed_row, rows2))
        check("B2 the change landed on the row the top screen row now occupies",
              abs(changed_row - rows2) <= 2,
              "changed viewport row=%d, expected ~%d" % (changed_row, rows2))
        check("B2 the change is NOT at the bottom of the screen",
              changed_row < 40, "viewport row=%d" % changed_row)
    stop(2)
    wait_stable(dev, "P4-done")

    # ---- back to the bottom ---------------------------------------------------------------
    dev.reset_bottom()
    s9 = snap("s9_back_at_bottom")
    total, bands = show(s8, s9, "back at the live bottom")
    check("the newest output is reachable after scrolling back down", total > 3000,
          "changed px=%d" % total)

    # The keyboard state has to stay put for the whole run, otherwise "the content moved by N
    # rows" and "the viewport row the change landed on" are measured against a grid that has
    # silently changed height. Re-measuring at the very end catches that.
    top9, pitch9 = measure_grid(s9)
    check("the grid geometry stayed stable for the whole run (keyboard unchanged)",
          pitch9 == pitch and top9 == top0,
          "top/pitch %s/%s -> %s/%s" % (top0, pitch, top9, pitch9))

    # ---- crash check ----------------------------------------------------------------------
    log = subprocess.run([ns.adb, "-s", ns.serial, "logcat", "-d", "-t", "800"],
                         capture_output=True, text=True).stdout
    bad = [l for l in log.splitlines()
           if ("FATAL" in l or "AndroidRuntime" in l) and "com.termux" in l]
    check("no fatal crash", not bad, " | ".join(bad[:3]))

    failed = [n for n, ok, _ in results if not ok]
    print("\n" + "=" * 72)
    print("%d/%d checks passed" % (len(results) - len(failed), len(results)))
    if failed:
        print("FAILED: " + ", ".join(failed))
    print("captures in " + ns.outdir)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
