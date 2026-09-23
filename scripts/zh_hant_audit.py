#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""zh (Simplified, values-zh) vs zh-Hant (values-b+zh+Hant) resource audit.

Answers: "is the Traditional Chinese translation lagging behind the Simplified one?"

Checks per module:
  [1] keys only in values-zh            (Hant missed a new key -> English shown)
  [2] keys only in zh-Hant              (stale key in Hant)
  [3] zh-Hant values still containing Simplified-only characters
      (opencc s2t round-trip changes the text -> not converted)
  [4] zh-Hant values with no CJK at all while zh has CJK (English left in place)
  [5] zh-Hant byte-identical to Simplified
  [6] base strings missing from BOTH zh files (English shown to both audiences)
  [7] base strings missing from zh-Hant only
  [8] arrays.xml coverage

NOTE on the parser: `<string\s` (whitespace after "string") so that
`<string-array>` is NOT matched -- with `<string\b` the regex swallows whole
string-array blocks and silently loses ~30 keys.

Requires: pip install opencc-python-reimplemented
Run: python scripts/zh_hant_audit.py
"""
import os
import re

import opencc

from common_helpers import (
    NAME_RE,
    REPO_ROOT,
    STR_RE,
    TRANS_FALSE_RE as TRANS_FALSE,
    read_xml_without_comments,
)

S2T = opencc.OpenCC("s2t")

SELF_RE = re.compile(r"<string\s([^>]*?)/>", re.S)
ARR_RE = re.compile(r"<(string-array|array)\s([^>]*?)>(.*?)</\1>", re.S)
ITEM_RE = re.compile(r"<item\s*>(.*?)</item>", re.S)
CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]")

MODULES = ["app", "terminal-emulator", "terminal-view", "termux-shared"]


def load_strings(path):
    """name -> (text, attrs). XML comments are stripped first."""
    out = {}
    raw = read_xml_without_comments(path)
    for m in STR_RE.finditer(raw):
        nm = NAME_RE.search(m.group(1))
        if nm:
            out[nm.group(1)] = (m.group(2).strip(), m.group(1))
    for m in SELF_RE.finditer(raw):
        nm = NAME_RE.search(m.group(1))
        if nm:
            out[nm.group(1)] = ("", m.group(1))
    return out


def load_arrays(path):
    out = {}
    raw = read_xml_without_comments(path)
    for m in ARR_RE.finditer(raw):
        nm = NAME_RE.search(m.group(2))
        if nm:
            out[nm.group(1)] = [i.strip() for i in ITEM_RE.findall(m.group(3))]
    return out


def norm(s):
    return re.sub(r"\s+", " ", s).strip()


def audit(mod):
    res = os.path.join(REPO_ROOT, mod, "src", "main", "res")
    if not os.path.isdir(res):
        return 0
    en = load_strings(os.path.join(res, "values", "strings.xml"))
    zh = load_strings(os.path.join(res, "values-zh", "strings.xml"))
    ht = load_strings(os.path.join(res, "values-b+zh+Hant", "strings.xml"))
    tf = {k for k, (v, a) in en.items() if TRANS_FALSE.search(a)}
    visible = {k for k in en if k not in tf}

    print("\n" + "=" * 78)
    print("MODULE: %s" % mod)
    print("=" * 78)
    print("  base(translatable)=%d  values-zh=%d  values-b+zh+Hant=%d"
          % (len(visible), len(zh), len(ht)))

    if not ht:
        print("  [!!] values-b+zh+Hant/strings.xml DOES NOT EXIST")
        print("       -> zh-Hant devices resolve to values-zh: %d strings shown"
              " in Simplified" % len(zh))
        return len(zh)

    issues = 0
    for label, keys in (("[1] only in values-zh", sorted(set(zh) - set(ht))),
                        ("[2] only in zh-Hant", sorted(set(ht) - set(zh)))):
        print("  %-26s: %s" % (label, keys or "none"))
        issues += len(keys)

    both = sorted(set(zh) & set(ht))

    hits = [(k, [(a, b) for a, b in zip(ht[k][0], S2T.convert(ht[k][0])) if a != b])
            for k in both]
    hits = [h for h in hits if h[1]]
    print("  [3] zh-Hant with Simplified-only chars: %d" % len(hits))
    for k, d in hits:
        print("      %-44s %s" % (k, " ".join("%s->%s" % p for p in d)))
    issues += len(hits)

    h4 = [k for k in both if CJK.search(zh[k][0]) and not CJK.search(ht[k][0])]
    print("  [4] zh-Hant left in English (no CJK) : %s" % (h4 or "none"))
    issues += len(h4)

    h5 = [k for k in both if norm(zh[k][0]) == norm(ht[k][0]) and CJK.search(zh[k][0])]
    print("  [5] zh-Hant identical to Simplified  : %d" % len(h5))
    issues += len(h5)

    miss = sorted(visible - set(zh) - set(ht))
    print("  [6] base strings missing from BOTH zh files: %d" % len(miss))
    for k in miss:
        print("      %-46s en: %s" % (k, norm(en[k][0])[:64]))
    issues += len(miss)

    m7 = sorted((visible & set(zh)) - set(ht))
    print("  [7] base strings missing from zh-Hant only : %s" % (m7 or "none"))
    issues += len(m7)

    ae = load_arrays(os.path.join(res, "values", "arrays.xml"))
    az = load_arrays(os.path.join(res, "values-zh", "arrays.xml"))
    ah = load_arrays(os.path.join(res, "values-b+zh+Hant", "arrays.xml"))
    if ae or az or ah:
        exists = os.path.exists(os.path.join(res, "values-b+zh+Hant", "arrays.xml"))
        print("  [8] arrays.xml: values/=%d  values-zh/=%d  values-b+zh+Hant/=%d %s"
              % (len(ae), len(az), len(ah), "" if exists else "(FILE MISSING)"))
        hard = sorted(k for k, v in ae.items()
                      if "entries" in k and any(not i.startswith("@") for i in v))
        print("      'entries' arrays with hardcoded English: %s" % (hard or "none"))

    # wording divergence = how much of Hant is a real independent translation
    diff = sum(1 for k in both if norm(zh[k][0]) != norm(ht[k][0]))
    print("  [i] wording divergence zh vs zh-Hant: %d/%d strings differ" % (diff, len(both)))
    return issues


def main():
    total = 0
    for mod in MODULES:
        total += audit(mod)
    print("\nTOTAL issues: %d" % total)


if __name__ == "__main__":
    main()
