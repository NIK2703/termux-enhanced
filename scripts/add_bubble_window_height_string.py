#!/usr/bin/env python3
"""Set the `bubble_window_height_title` string in every locale's strings.xml.

Label of the "bubble window height" slider on the Display screen (Window category). There is no
summary string: the value is shown on the slider itself.

RU is the source of truth for this fork; `values/` is the English default. The values came from a
per-locale translator pass and live here so this script stays the only place that knows them. It
upserts, so rewording a translation is a one-command change. Insertion is anchored on
`bubble_on_background_summary`, the row above the slider.

Run from the repo root:

    python scripts/add_bubble_window_height_string.py
"""

import os
import re
import sys

from common_helpers import insert_after_string_anchor, print_results, require_repo_root, write_text

BASE = "app/src/main/res"
ANCHOR = "bubble_on_background_summary"

# locale -> title. Kept short: a slider label, not a sentence, and no period or colon at the end.
TRANSLATIONS = {
    "values": "Bubble window height",
    "values-ru": "Высота окна пузырька",
    "values-ar": "ارتفاع نافذة الفقاعة",
    "values-b+zh+Hant": "氣泡視窗高度",
    "values-de": "Höhe des Blasenfensters",
    "values-es": "Altura de la burbuja",
    "values-fr": "Hauteur de la bulle",
    "values-hi": "बबल विंडो की ऊँचाई",
    "values-in": "Tinggi jendela gelembung",
    "values-ja": "バブルウィンドウの高さ",
    "values-ko": "버블 창 높이",
    "values-pt": "Altura da bolha",
    "values-tr": "Baloncuk yüksekliği",
    "values-zh": "气泡高度",
}

TITLE_KEY = "bubble_window_height_title"

# Characters that would need XML escaping, or that break aapt2.
FORBIDDEN = ["&", "<", ">", '"', "'", "\\"]

PATTERN = re.compile(r'(<string name="' + TITLE_KEY + r'">)(.*?)(</string>)', re.S)


def patch(locale: str, title: str) -> str:
    path = os.path.join(BASE, locale, "strings.xml")
    if not os.path.isfile(path):
        return f"SKIP  {locale}: no strings.xml"

    for bad in FORBIDDEN:
        if bad in title:
            return f"FAIL  {locale}: forbidden character {bad!r} in {title!r}"

    with open(path, "r", encoding="utf-8", newline="") as handle:
        text = handle.read()

    matches = PATTERN.findall(text)
    if len(matches) > 1:
        return f"FAIL  {locale}: {len(matches)} <{TITLE_KEY}>, expected at most one"
    if matches:
        if matches[0][1] == title:
            return f"SKIP  {locale}: already up to date"
        # newline="" above keeps the existing endings verbatim; sub() touches only the value.
        text = PATTERN.sub(lambda m: m.group(1) + title + m.group(3), text, count=1)
        write_text(path, text)
        return f"OK    {locale}: rewrote"

    new_text = insert_after_string_anchor(text, ANCHOR, [
        f'<string name="{TITLE_KEY}">{title}</string>',
    ])
    if new_text is None:
        return f"FAIL  {locale}: anchor {ANCHOR} not found"

    write_text(path, new_text)
    return f"OK    {locale}: inserted"


def main() -> int:
    if not require_repo_root(BASE):
        return 1

    return print_results(
        patch(locale, title)
        for locale, title in TRANSLATIONS.items()
    )


if __name__ == "__main__":
    sys.exit(main())