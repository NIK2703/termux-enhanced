#!/usr/bin/env python3
"""Insert the compact-extra-keys-panel strings into every locale's strings.xml, plus the two mode
arrays into the default arrays.xml.

RU is the source of truth for this fork, so the Russian wording is written first and the other
locales are translated from it. Run from the repo root:

    python scripts/add_compact_extra_keys_strings.py

The mode arrays are defined ONLY in the default `values/arrays.xml`: their items are `@string/...`
references, and resource references are resolved against the configuration the app runs in, so a
zh device picks up the zh strings from `values-zh/strings.xml` even though the array itself is
inherited from the default locale. Only arrays that carry literal text (see
`extra_keys_compact_mode_values`, and e.g. the back-key-behaviour arrays) need a copy per locale.
"""

import os
import sys

BASE = "app/src/main/res"

# locale dir -> (title, summary, mode_title, mode_rows, mode_columns)
TRANSLATIONS = {
    "values": (
        "Compact panel in landscape",
        "In landscape, 2–3 rows fold into one and 4 rows into two.",
        "Fold order",
        "By rows",
        "By columns",
    ),
    "values-ru": (
        "Компактная панель в ландшафтном режиме",
        "В ландшафте 2–3 строки сворачиваются в одну, а 4 — в две.",
        "Порядок сворачивания",
        "По строкам",
        "По столбцам",
    ),
    "values-ar": (
        "لوحة مضغوطة في الوضع الأفقي",
        "في الوضع الأفقي تُدمج 2–3 صفوف في صف واحد، و4 صفوف في صفين.",
        "ترتيب الدمج",
        "حسب الصفوف",
        "حسب الأعمدة",
    ),
    "values-b+zh+Hant": (
        "橫向時的緊湊面板",
        "橫向時，2–3 行會合併為一行，4 行合併為兩行。",
        "合併順序",
        "依行",
        "依欄",
    ),
    "values-de": (
        "Kompakte Leiste im Querformat",
        "Im Querformat werden 2–3 Zeilen zu einer und 4 Zeilen zu zwei zusammengefasst.",
        "Faltreihenfolge",
        "Nach Zeilen",
        "Nach Spalten",
    ),
    "values-es": (
        "Panel compacto en horizontal",
        "En horizontal, 2–3 filas se pliegan en una y 4 filas en dos.",
        "Orden de plegado",
        "Por filas",
        "Por columnas",
    ),
    "values-fr": (
        "Panneau compact en paysage",
        "En paysage, 2–3 rangées se replient en une et 4 rangées en deux.",
        "Ordre de repli",
        "Par rangées",
        "Par colonnes",
    ),
    "values-hi": (
        "लैंडस्केप में संक्षिप्त पैनल",
        "लैंडस्केप में 2–3 पंक्तियाँ एक में और 4 पंक्तियाँ दो में मोड़ दी जाती हैं।",
        "मोड़ने का क्रम",
        "पंक्तियों के अनुसार",
        "स्तंभों के अनुसार",
    ),
    "values-in": (
        "Panel ringkas dalam lanskap",
        "Dalam lanskap, 2–3 baris dilipat menjadi satu dan 4 baris menjadi dua.",
        "Urutan lipatan",
        "Menurut baris",
        "Menurut kolom",
    ),
    "values-ja": (
        "横向きのコンパクトパネル",
        "横向きでは2〜3行が1行に、4行が2行に折りたたまれます。",
        "折りたたみ順",
        "行順",
        "列順",
    ),
    "values-ko": (
        "가로 모드의 간략 패널",
        "가로 모드에서는 2~3줄이 한 줄로, 4줄이 두 줄로 접힙니다.",
        "접는 순서",
        "행 기준",
        "열 기준",
    ),
    "values-pt": (
        "Painel compacto em paisagem",
        "Em paisagem, 2–3 linhas são dobradas em uma e 4 linhas em duas.",
        "Ordem de dobra",
        "Por linhas",
        "Por colunas",
    ),
    "values-tr": (
        "Yatay modda kompakt panel",
        "Yatay modda 2–3 satır bire, 4 satır ikiye katlanır.",
        "Katlama sırası",
        "Satırlara göre",
        "Sütunlara göre",
    ),
    "values-zh": (
        "横屏时的紧凑面板",
        "横屏时，2–3 行会合并为一行，4 行合并为两行。",
        "合并顺序",
        "按行",
        "按列",
    ),
}

BLOCK = """
    <!-- ===================== Compact extra-keys panel =====================
         The panel folds its rows when the window is in landscape. The fold is a derived layout, so
         these strings describe the behaviour and the order of the keys inside a folded row; the
         stored layout is never rewritten. -->
    <string name="extra_keys_compact_landscape_title">{title}</string>
    <string name="extra_keys_compact_landscape_summary">{summary}</string>
    <string name="extra_keys_compact_mode_title">{mode_title}</string>
    <string name="extra_keys_compact_mode_rows">{mode_rows}</string>
    <string name="extra_keys_compact_mode_columns">{mode_columns}</string>
"""

ARRAYS = """
    <!-- Fold order of the compact extra-keys panel. The entries are @string references, so every
         locale renders them in its own language without a copy of this array: a resource reference
         is resolved against the configuration the app runs in, not the one the array was taken
         from. The values below are preference values and are never shown, hence not localised. -->
    <string-array name="extra_keys_compact_mode_entries">
        <item>@string/extra_keys_compact_mode_rows</item>
        <item>@string/extra_keys_compact_mode_columns</item>
    </string-array>
    <string-array name="extra_keys_compact_mode_values">
        <item>rows</item>
        <item>columns</item>
    </string-array>
"""

MARKER = "extra_keys_compact_landscape_title"


def patch_strings(locale: str, values) -> str:
    path = os.path.join(BASE, locale, "strings.xml")
    if not os.path.isfile(path):
        return f"SKIP  {locale}: no strings.xml"

    with open(path, "r", encoding="utf-8") as handle:
        text = handle.read()

    if MARKER in text:
        return f"SKIP  {locale}: already patched"

    if "</resources>" not in text:
        return f"FAIL  {locale}: no closing </resources>"

    block = BLOCK.format(
        title=values[0],
        summary=values[1],
        mode_title=values[2],
        mode_rows=values[3],
        mode_columns=values[4],
    )

    # Insert before the LAST closing tag; some files carry a nested <resources> in comments.
    index = text.rindex("</resources>")
    text = text[:index] + block + text[index:]

    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)
    return f"OK    {locale}"


def patch_arrays() -> str:
    path = os.path.join(BASE, "values", "arrays.xml")
    if not os.path.isfile(path):
        return "FAIL  values: no arrays.xml"

    with open(path, "r", encoding="utf-8") as handle:
        text = handle.read()

    if "extra_keys_compact_mode_entries" in text:
        return "SKIP  values/arrays.xml: already patched"

    index = text.rindex("</resources>")
    text = text[:index] + ARRAYS + text[index:]

    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)
    return "OK    values/arrays.xml"


def main() -> int:
    if not os.path.isdir(BASE):
        print(f"Run this from the repo root; {BASE} not found", file=sys.stderr)
        return 1

    failures = 0
    for locale, values in TRANSLATIONS.items():
        result = patch_strings(locale, values)
        print(result)
        if result.startswith("FAIL"):
            failures += 1

    result = patch_arrays()
    print(result)
    if result.startswith("FAIL"):
        failures += 1

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
