#!/usr/bin/env python3
"""Add `bubble_open_failed_message` to every locale's strings.xml.

Why this string exists: `TermuxBubbleManager.showBubble()` returns false both when bubbles are
genuinely unavailable AND when the system refuses the post for some other reason (the platform
rejects a bubble notification with an IllegalArgumentException from
NotificationManagerService.checkDisqualifyingFeatures, which arrives as a RemoteException). Mapping
both onto "bubbles are unavailable, enable them in notification settings" sent at least one
debugging session down the wrong path, so the second case now says what actually happened.

RU is the source of truth for this fork. The insert is anchored on `bubble_no_session_message`, so
re-running is a no-op. Run from the repo root:

    python scripts/add_bubble_open_failed_string.py
"""

import os
import sys

BASE = "app/src/main/res"
ANCHOR = "bubble_no_session_message"
KEY = "bubble_open_failed_message"

TRANSLATIONS = {
    "values": "Could not open the bubble window.",
    "values-ru": "Не удалось открыть bubble-окно.",
    "values-ar": "تعذّر فتح نافذة الفقاعة.",
    "values-b+zh+Hant": "無法開啟懸浮視窗。",
    "values-de": "Das Bubble-Fenster konnte nicht geöffnet werden.",
    "values-es": "No se pudo abrir la ventana de burbuja.",
    "values-fr": "Impossible d’ouvrir la fenêtre de bulle.",
    "values-hi": "बबल विंडो नहीं खोली जा सकी।",
    "values-in": "Tidak dapat membuka jendela balon.",
    "values-ja": "バブルウィンドウを開けませんでした。",
    "values-ko": "버블 창을 열 수 없습니다.",
    "values-pt": "Não foi possível abrir a janela de bolha.",
    "values-tr": "Baloncuk penceresi açılamadı.",
    "values-zh": "无法打开气泡窗口。",
}


def patch(locale: str, text_value: str) -> str:
    path = os.path.join(BASE, locale, "strings.xml")
    if not os.path.isfile(path):
        return f"SKIP  {locale}: no strings.xml"

    with open(path, "r", encoding="utf-8") as handle:
        text = handle.read()

    if KEY in text:
        return f"SKIP  {locale}: already present"

    # Find the anchor line and insert immediately after it, preserving indentation.
    lines = text.split("\n")
    for index, line in enumerate(lines):
        if f'name="{ANCHOR}"' in line:
            indent = line[: len(line) - len(line.lstrip())]
            lines.insert(index + 1, f'{indent}<string name="{KEY}">{text_value}</string>')
            with open(path, "w", encoding="utf-8") as handle:
                handle.write("\n".join(lines))
            return f"OK    {locale}"

    return f"FAIL  {locale}: anchor {ANCHOR} not found"


def main() -> int:
    if not os.path.isdir(BASE):
        print(f"Run this from the repo root; {BASE} not found", file=sys.stderr)
        return 1

    failures = 0
    for locale, text_value in TRANSLATIONS.items():
        result = patch(locale, text_value)
        print(result)
        if result.startswith("FAIL"):
            failures += 1
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
