#!/usr/bin/env python3
"""Add `bubble_open_failed_message` to every locale's strings.xml.

Why this string exists: `TermuxBubbleManager.showBubble()` returns false both when bubbles are
unavailable AND when the system refuses the post for other reasons (e.g. IllegalArgumentException
from NotificationManagerService.checkDisqualifyingFeatures arriving as a RemoteException). Mapping
both onto "bubbles are unavailable" sent debugging down the wrong path, so the second case now
says what actually happened.

RU is the source of truth for this fork. The insert is anchored on `bubble_no_session_message`,
so re-running is a no-op. Run from the repo root:

    python scripts/add_bubble_open_failed_string.py
"""

import os
import sys

from common_helpers import insert_after_string_anchor, print_results, require_repo_root, write_text

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

    # Insert immediately after the anchor line, preserving indentation.
    new_text = insert_after_string_anchor(text, ANCHOR, [
        f'<string name="{KEY}">{text_value}</string>',
    ])
    if new_text is None:
        return f"FAIL  {locale}: anchor {ANCHOR} not found"

    write_text(path, new_text)
    return f"OK    {locale}"


def main() -> int:
    if not require_repo_root(BASE):
        return 1

    return print_results(patch(locale, value) for locale, value in TRANSLATIONS.items())


if __name__ == "__main__":
    sys.exit(main())
