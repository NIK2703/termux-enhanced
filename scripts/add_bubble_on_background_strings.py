#!/usr/bin/env python3
"""Add the `bubble_on_background_*` strings to every locale's strings.xml.

These drive the "Bubble on background" switch on the Display settings screen, which makes the app
float itself in a bubble window when the user leaves it and take the bubble down when they come
back. Both halves are one switch because opening a bubble on the way out and leaving it behind on
the way in would be two halves of the same mistake.

RU is the source of truth for this fork; `values/` is the English default. The insert is anchored on
`fullscreen_title` (the switch directly above it in the same settings category), so re-running is a
no-op. Run from the repo root:

    python scripts/add_bubble_on_background_strings.py
"""

import os
import sys

from common_helpers import insert_after_string_anchor, print_results, require_repo_root, write_text

BASE = "app/src/main/res"
ANCHOR = "fullscreen_title"

# locale -> (title, summary). Titles are kept short: they are switch labels, not sentences.
TRANSLATIONS = {
    "values": ("Bubble on background",
               "Open the bubble when you leave the app and close it when you return"),
    "values-ru": ("Пузырёк при уходе в фон",
                  "Открывать пузырёк при уходе из приложения и закрывать при возврате"),
    "values-ar": ("الفقاعة عند الخلفية",
                  "فتح الفقاعة عند مغادرة التطبيق وإغلاقها عند العودة"),
    "values-b+zh+Hant": ("後台氣泡",
                         "離開應用程式時開啟氣泡，返回時關閉"),
    "values-de": ("Blase im Hintergrund",
                  "Blase beim Verlassen der App öffnen und bei Rückkehr schließen"),
    "values-es": ("Burbuja en segundo plano",
                  "Abrir la burbuja al salir de la aplicación y cerrarla al volver"),
    "values-fr": ("Bulle en arrière-plan",
                  "Ouvrir la bulle en quittant l’app et la fermer au retour"),
    "values-hi": ("पृष्ठभूमि में बबल",
                  "ऐप छोड़ने पर बबल खोलें और वापस आने पर बंद करें"),
    "values-in": ("Balon saat latar belakang",
                  "Buka balon saat meninggalkan aplikasi dan tutup saat kembali"),
    "values-ja": ("バックグラウンドでバブル",
                  "アプリを離れるとバブルを開き、戻ると閉じます"),
    "values-ko": ("백그라운드에서 버블",
                  "앱을 나가면 버블을 열고 돌아오면 닫습니다"),
    "values-pt": ("Bolha em segundo plano",
                  "Abrir a bolha ao sair do app e fechá-la ao voltar"),
    "values-tr": ("Arka planda baloncuk",
                  "Uygulamadan çıkarken baloncuğu aç, döndüğünde kapat"),
    "values-zh": ("后台气泡",
                  "离开应用时打开气泡，返回时关闭"),
}

TITLE_KEY = "bubble_on_background_title"
SUMMARY_KEY = "bubble_on_background_summary"


def patch(locale: str, title: str, summary: str) -> str:
    path = os.path.join(BASE, locale, "strings.xml")
    if not os.path.isfile(path):
        return f"SKIP  {locale}: no strings.xml"

    with open(path, "r", encoding="utf-8") as handle:
        text = handle.read()

    if TITLE_KEY in text or SUMMARY_KEY in text:
        return f"SKIP  {locale}: already present"

    # A raw apostrophe in a values/ string makes aapt2 fail; the typographic one does not.
    for value in (title, summary):
        if "'" in value:
            return f"FAIL  {locale}: ASCII apostrophe in {value!r}"

    new_text = insert_after_string_anchor(text, ANCHOR, [
        f'<string name="{TITLE_KEY}">{title}</string>',
        f'<string name="{SUMMARY_KEY}">{summary}</string>',
    ])
    if new_text is None:
        return f"FAIL  {locale}: anchor {ANCHOR} not found"

    write_text(path, new_text)
    return f"OK    {locale}"


def main() -> int:
    if not require_repo_root(BASE):
        return 1

    return print_results(
        patch(locale, title, summary)
        for locale, (title, summary) in TRANSLATIONS.items()
    )


if __name__ == "__main__":
    sys.exit(main())
