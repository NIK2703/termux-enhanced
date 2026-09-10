#!/usr/bin/env python3
"""Find missing and untranslated string resources across locales.

For each locale values-<code>/strings.xml and arrays.xml, report:
  - keys present in the base (values/) but missing in the locale
  - keys whose translated value is identical (verbatim) to the English base value

Usage: python scripts/find_untranslated.py
Exits 1 if any missing/untranslated entries are found, else 0.
"""
import os
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")


def load(path):
    """Return dict name -> normalized text for <string> and <string-array>/<array>."""
    if not os.path.exists(path):
        return {}
    tree = ET.parse(path)
    out = {}
    for el in tree.getroot():
        name = el.get("name")
        if name is None:
            continue
        if el.tag == "string":
            out[name] = (el.text or "").strip()
        elif el.tag in ("string-array", "array"):
            items = [(it.text or "").strip() for it in el.findall("item")]
            out[name] = "\x01".join(items)
    return out


def main():
    base_strings = load(os.path.join(RES, "values", "strings.xml"))
    base_arrays = load(os.path.join(RES, "values", "arrays.xml"))
    locales = sorted(
        d for d in os.listdir(RES)
        if d.startswith("values-") and os.path.isdir(os.path.join(RES, d))
    )
    total_missing = total_same = 0
    for loc in locales:
        s = load(os.path.join(RES, loc, "strings.xml"))
        a = load(os.path.join(RES, loc, "arrays.xml"))
        missing = [k for k in base_strings if k not in s]
        same = [k for k in base_strings if k in s and s[k] and s[k] == base_strings[k]]
        miss_a = [k for k in base_arrays if k not in a]
        same_a = [k for k in base_arrays if k in a and a[k] and a[k] == base_arrays[k]]
        if missing or same or miss_a or same_a:
            print(f"== {loc} ==")
            for k in missing:
                print(f"  MISSING string: {k}")
            for k in same:
                print(f"  UNTRANSLATED string: {k}")
            for k in miss_a:
                print(f"  MISSING array: {k}")
            for k in same_a:
                print(f"  UNTRANSLATED array: {k}")
            total_missing += len(missing) + len(miss_a)
            total_same += len(same) + len(same_a)
    print(f"\nSummary: {total_missing} missing, {total_same} identical-to-English")
    sys.exit(1 if (total_missing or total_same) else 0)


if __name__ == "__main__":
    main()
