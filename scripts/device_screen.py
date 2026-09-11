#!/usr/bin/env python3
"""Screen capture + pixel diff helper for the on-device scroll/update test.

Talks to a device over adb. Uses the *raw* screencap stream (12-byte header + pixels)
so no image library is required, and writes PNGs with zlib for visual inspection.

Commands
--------
  grab  <serial> <out.raw>          capture the screen
  png   <in.raw> <out.png>          convert a capture to PNG
  info  <in.raw>                    print width/height/format
  bands <a.raw> <b.raw> [--y0 N] [--y1 N] [--min-px N]
                                    print the y-ranges whose pixels changed
  diff  <a.raw> <b.raw>             print a one-line summary of the difference
"""

import argparse
import struct
import subprocess
import sys
import zlib

ADB = r"C:/Users/Nikita/AppData/Local/Android/Sdk/platform-tools/adb.exe"
BPP = {1: 4, 2: 4, 3: 3, 4: 2}
FMT_NAME = {1: "RGBA_8888", 2: "RGBX_8888", 3: "RGB_888", 4: "RGB_565"}


def capture(serial: str, adb: str = ADB) -> bytes:
    out = subprocess.run([adb, "-s", serial, "exec-out", "screencap"],
                         capture_output=True, check=True)
    raw = out.stdout
    if len(raw) < 12:
        raise RuntimeError("screencap returned %d bytes (stderr=%r)" % (len(raw), out.stderr[:200]))
    return raw


def parse(raw: bytes):
    w, h, fmt = struct.unpack_from("<III", raw, 0)
    bpp = BPP.get(fmt)
    if bpp is None:
        raise RuntimeError("unsupported pixel format %d" % fmt)
    body = raw[12:]
    stride_px = len(body) // (h * bpp) if h else w
    if stride_px < w:
        stride_px = w
    return w, h, fmt, bpp, stride_px, body


def row_bytes(body: bytes, y: int, w: int, bpp: int, stride_px: int) -> bytes:
    off = y * stride_px * bpp
    return body[off:off + w * bpp]


def changed_bands(a: bytes, b: bytes, y0: int = 0, y1: int = None, min_px: int = 0,
                  x0: int = 0, x1: int = None):
    """Return [(y_start, y_end, changed_pixels)] of maximal changed y-runs, in x=[x0,x1)."""
    wa, ha, _, bpp_a, sa, ba = parse(a)
    wb, hb, _, bpp_b, sb, bb = parse(b)
    if (wa, ha) != (wb, hb):
        raise RuntimeError("size mismatch: %dx%d vs %dx%d" % (wa, ha, wb, hb))
    if bpp_a != bpp_b:
        raise RuntimeError("format mismatch")
    if y1 is None:
        y1 = ha
    if x1 is None:
        x1 = wa
    x0 = max(0, x0)
    x1 = min(wa, x1)
    bands = []
    cur = None
    for y in range(max(0, y0), min(ha, y1)):
        ra = row_bytes(ba, y, wa, bpp_a, sa)
        rb = row_bytes(bb, y, wb, bpp_b, sb)
        if ra == rb:
            n = 0
        else:
            n = 0
            for x in range(x0, x1):
                i = x * bpp_a
                if ra[i:i + bpp_a] != rb[i:i + bpp_a]:
                    n += 1
        if n > min_px:
            if cur is None:
                cur = [y, y, 0]
            cur[1] = y
            cur[2] += n
        else:
            if cur is not None:
                bands.append(tuple(cur))
                cur = None
    if cur is not None:
        bands.append(tuple(cur))
    return bands


def write_png(path: str, w: int, h: int, fmt: int, bpp: int, stride_px: int, body: bytes):
    rows = []
    for y in range(h):
        rb = row_bytes(body, y, w, bpp, stride_px)
        if bpp == 4:
            rgb = bytearray()
            for i in range(0, len(rb), 4):
                rgb += bytes((rb[i], rb[i + 1], rb[i + 2]))
        elif bpp == 3:
            rgb = bytearray(rb)
        else:  # RGB_565
            rgb = bytearray()
            for i in range(0, len(rb), 2):
                v = rb[i] | (rb[i + 1] << 8)
                rgb += bytes((((v >> 11) & 0x1F) * 255 // 31,
                              ((v >> 5) & 0x3F) * 255 // 63,
                              (v & 0x1F) * 255 // 31))
        rows.append(b"\x00" + bytes(rgb))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(b"".join(rows), 6))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def read_raw(path: str) -> bytes:
    if path.endswith(".png"):
        raise SystemExit("pass the .raw capture, not a .png")
    with open(path, "rb") as f:
        return f.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["grab", "png", "info", "bands", "diff"])
    ap.add_argument("args", nargs="*")
    ap.add_argument("--y0", type=int, default=0)
    ap.add_argument("--y1", type=int, default=None)
    ap.add_argument("--x0", type=int, default=0)
    ap.add_argument("--x1", type=int, default=None)
    ap.add_argument("--min-px", type=int, default=0)
    ap.add_argument("--adb", default=ADB)
    ns = ap.parse_args()

    if ns.cmd == "grab":
        serial, out = ns.args
        raw = capture(serial, ns.adb)
        with open(out, "wb") as f:
            f.write(raw)
        w, h, fmt, _, _, _ = parse(raw)
        print("captured %dx%d %s -> %s" % (w, h, FMT_NAME.get(fmt, fmt), out))

    elif ns.cmd == "png":
        src, dst = ns.args
        raw = read_raw(src)
        w, h, fmt, bpp, stride, body = parse(raw)
        write_png(dst, w, h, fmt, bpp, stride, body)
        print("wrote %s (%dx%d)" % (dst, w, h))

    elif ns.cmd == "info":
        raw = read_raw(ns.args[0])
        w, h, fmt, bpp, stride, body = parse(raw)
        print("%dx%d %s bpp=%d stride=%d body=%d" % (w, h, FMT_NAME.get(fmt, fmt), bpp, stride, len(body)))

    elif ns.cmd == "bands":
        a, b = read_raw(ns.args[0]), read_raw(ns.args[1])
        bands = changed_bands(a, b, ns.y0, ns.y1, ns.min_px, ns.x0, ns.x1)
        if not bands:
            print("NO CHANGED PIXELS in y=[%d,%s) x=[%d,%s)" % (ns.y0, ns.y1, ns.x0, ns.x1))
        for y0, y1, n in bands:
            print("y=%4d..%-4d  rows=%-4d  px=%d" % (y0, y1, y1 - y0 + 1, n))

    elif ns.cmd == "diff":
        a, b = read_raw(ns.args[0]), read_raw(ns.args[1])
        bands = changed_bands(a, b, ns.y0, ns.y1, ns.min_px, ns.x0, ns.x1)
        total = sum(n for _, _, n in bands)
        print("changed bands=%d total_px=%d" % (len(bands), total))
        for y0, y1, n in bands:
            print("  y=%4d..%-4d px=%d" % (y0, y1, n))


if __name__ == "__main__":
    main()
