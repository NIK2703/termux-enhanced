#!/usr/bin/env python3
"""Lint apostrophe escaping in Android resource XML.

aapt2 requires a literal apostrophe to be escaped as \' (or the whole
string wrapped in double quotes). Flag any raw (unescaped) apostrophe
inside a <string> value.

Usage: python scripts/check_apostrophes.py
Exits 1 if any issue is found, else 0.
"""
import os
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")


def main():
    bad = 0
    for dirpath, _, files in os.walk(RES):
        for fn in files:
            if not fn.endswith(".xml"):
                continue
            path = os.path.join(dirpath, fn)
            try:
                tree = ET.parse(path)
            except ET.ParseError:
                continue
            for el in tree.getroot().iter("string"):
                text = el.text or ""
                name = el.get("name", "?")
                i = 0
                while i < len(text):
                    if text[i] == "'" and (i == 0 or text[i - 1] != "\\"):
                        line = el.sourceline or "?"
                        print(f"{path}:{line}: raw apostrophe in <string name=\"{name}\">")
                        bad += 1
                        break
                    i += 1
    if bad:
        print(f"\n{bad} apostrophe issue(s) found")
        sys.exit(1)
    print("OK: no apostrophe issues")
    sys.exit(0)


if __name__ == "__main__":
    main()
