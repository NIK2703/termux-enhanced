#!/usr/bin/env python3
"""Rewrite the two bubble comments that still describe the removed context-menu entry point.

The bubble used to be reachable from the terminal's "More..." context menu and had two failure
toasts; both were removed, and the comments next to the strings that remain still promise them.

Edits are done on BYTES so the files' CRLF line endings survive untouched, and every file is
checked before and after: no BOM, and every LF is part of a CRLF pair.
"""

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"

LOCALES = [
    "values", "values-ar", "values-b+zh+Hant", "values-de", "values-es", "values-fr",
    "values-hi", "values-in", "values-ja", "values-ko", "values-pt", "values-ru",
    "values-tr", "values-zh",
]

STRING_OLD = (
    b"         Strings for the bubble (a floating terminal window hosted by SystemUI). The menu item is\r\n"
    b"         only shown where the platform can actually bubble, and the two failure messages explain\r\n"
    b"         why the item did nothing when it was shown but the system refused. -->"
)
STRING_NEW = (
    b"         Strings for the bubble (a floating terminal window hosted by SystemUI). The label is only\r\n"
    b"         shown where the platform can actually bubble, and the button on the service notification\r\n"
    b"         is now the single manual entry point: there is no context-menu item any more. -->"
)

PREFS = RES / "xml" / "termux_display_preferences.xml"
PREFS_OLD = (
    b"             terminal's context menu or the notification's button. Off by default, because it changes\r\n"
    b"             what happens on every trip to the background. -->"
)
PREFS_NEW = (
    b"             notification's button. Off by default, because it changes what happens on every trip to\r\n"
    b"             the background. -->"
)


def check(data: bytes, path: pathlib.Path) -> None:
    if data.startswith(b"\xef\xbb\xbf"):
        sys.exit(f"FAIL {path}: has a UTF-8 BOM")
    lf = data.count(b"\n")
    crlf = data.count(b"\r\n")
    if lf != crlf:
        sys.exit(f"FAIL {path}: {lf - crlf} lone LF (expected CRLF everywhere)")


def patch(path: pathlib.Path, old: bytes, new: bytes) -> None:
    data = path.read_bytes()
    check(data, path)
    if data.count(old) != 1:
        sys.exit(f"FAIL {path}: expected exactly 1 match, found {data.count(old)}")
    data = data.replace(old, new)
    check(data, path)
    path.write_bytes(data)
    print(f"ok  {path.relative_to(ROOT)}")


def main() -> None:
    for locale in LOCALES:
        patch(RES / locale / "strings.xml", STRING_OLD, STRING_NEW)
    patch(PREFS, PREFS_OLD, PREFS_NEW)


if __name__ == "__main__":
    main()
