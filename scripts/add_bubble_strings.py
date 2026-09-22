#!/usr/bin/env python3
"""Insert the bubble-window strings into every locale's strings.xml.

RU is the source of truth for this fork, so the Russian wording is written first and the other
locales are translated from it. Run from the repo root:

    python scripts/add_bubble_strings.py
"""

import os
import sys

BASE = "app/src/main/res"

# locale dir -> (open_in_bubble, channel_description, unavailable, no_session, close)
TRANSLATIONS = {
    "values": (
        "Open in bubble",
        "Floating terminal window on top of other apps",
        "Bubbles are unavailable. Enable them in the app's notification settings.",
        "No terminal session to show in the bubble.",
        "Close the bubble window",
    ),
    "values-ru": (
        "Открыть в bubble-окне",
        "Плавающее окно терминала поверх других приложений",
        "Bubble-окна недоступны. Включите их в настройках уведомлений приложения.",
        "Нет активной сессии терминала для bubble-окна.",
        "Закрыть bubble-окно",
    ),
    "values-ar": (
        "فتح في فقاعة",
        "نافذة طرفية عائمة فوق التطبيقات الأخرى",
        "الفقاعات غير متاحة. فعّلها في إعدادات إشعارات التطبيق.",
        "لا توجد جلسة طرفية لعرضها في الفقاعة.",
        "إغلاق نافذة الفقاعة",
    ),
    "values-b+zh+Hant": (
        "在懸浮視窗中開啟",
        "浮在其他應用程式上方的終端機視窗",
        "無法使用懸浮視窗。請在應用程式通知設定中啟用。",
        "沒有可顯示於懸浮視窗的終端機工作階段。",
        "關閉懸浮視窗",
    ),
    "values-de": (
        "In Bubble öffnen",
        "Schwebendes Terminalfenster über anderen Apps",
        "Bubbles sind nicht verfügbar. Aktiviere sie in den Benachrichtigungseinstellungen der App.",
        "Keine Terminalsitzung für die Bubble vorhanden.",
        "Bubble schließen",
    ),
    "values-es": (
        "Abrir en burbuja",
        "Ventana de terminal flotante sobre otras aplicaciones",
        "Las burbujas no están disponibles. Actívalas en los ajustes de notificaciones de la aplicación.",
        "No hay ninguna sesión de terminal para mostrar en la burbuja.",
        "Cerrar la burbuja",
    ),
    "values-fr": (
        "Ouvrir dans une bulle",
        "Fenêtre de terminal flottante par-dessus les autres applications",
        "Les bulles ne sont pas disponibles. Activez-les dans les paramètres de notifications de l’application.",
        "Aucune session de terminal à afficher dans la bulle.",
        "Fermer la bulle",
    ),
    "values-hi": (
        "बबल में खोलें",
        "अन्य ऐप्स के ऊपर तैरती टर्मिनल विंडो",
        "बबल उपलब्ध नहीं हैं। ऐप की सूचना सेटिंग में इन्हें चालू करें।",
        "बबल में दिखाने के लिए कोई टर्मिनल सत्र नहीं है।",
        "बबल बंद करें",
    ),
    "values-in": (
        "Buka di balon",
        "Jendela terminal mengambang di atas aplikasi lain",
        "Balon tidak tersedia. Aktifkan di setelan notifikasi aplikasi.",
        "Tidak ada sesi terminal untuk ditampilkan di balon.",
        "Tutup balon",
    ),
    "values-ja": (
        "バブルで開く",
        "他のアプリの上に浮かぶターミナルウィンドウ",
        "バブルを利用できません。アプリの通知設定で有効にしてください。",
        "バブルに表示するターミナルセッションがありません。",
        "バブルを閉じる",
    ),
    "values-ko": (
        "버블로 열기",
        "다른 앱 위에 떠 있는 터미널 창",
        "버블을 사용할 수 없습니다. 앱 알림 설정에서 사용 설정하세요.",
        "버블에 표시할 터미널 세션이 없습니다.",
        "버블 닫기",
    ),
    "values-pt": (
        "Abrir em bolha",
        "Janela de terminal flutuante sobre outros aplicativos",
        "As bolhas não estão disponíveis. Ative-as nas configurações de notificações do aplicativo.",
        "Nenhuma sessão de terminal para mostrar na bolha.",
        "Fechar a bolha",
    ),
    "values-tr": (
        "Baloncukta aç",
        "Diğer uygulamaların üzerinde yüzen terminal penceresi",
        "Baloncuklar kullanılamıyor. Uygulamanın bildirim ayarlarından etkinleştirin.",
        "Baloncukta gösterilecek terminal oturumu yok.",
        "Baloncuğu kapat",
    ),
    "values-zh": (
        "在气泡中打开",
        "浮在其他应用之上的终端窗口",
        "气泡不可用。请在应用通知设置中启用。",
        "没有可在气泡中显示的终端会话。",
        "关闭气泡",
    ),
}

BLOCK = """
    <!-- ===================== Floating bubble window =====================
         Strings for the bubble (a floating terminal window hosted by SystemUI). The menu item is
         only shown where the platform can actually bubble, and the two failure messages explain
         why the item did nothing when it was shown but the system refused. -->
    <string name="action_open_in_bubble">{open_in_bubble}</string>
    <string name="bubble_notification_channel_description">{channel}</string>
    <string name="bubble_unavailable_message">{unavailable}</string>
    <string name="bubble_no_session_message">{no_session}</string>
    <string name="bubble_action_close">{close}</string>
"""

# Key labels are glyphs and abbreviations, identical in every language, so they live only in the
# default locale as non-translatable resources.
KEY_LABELS = """
    <!-- Bubble key row. Abbreviations and arrow glyphs: the same in every language, so they are
         marked non-translatable instead of being copied into 13 locale files. -->
    <string name="bubble_key_escape" translatable="false">ESC</string>
    <string name="bubble_key_tab" translatable="false">TAB</string>
    <string name="bubble_key_ctrl" translatable="false">CTRL</string>
    <string name="bubble_key_alt" translatable="false">ALT</string>
    <string name="bubble_key_left" translatable="false">\u2190</string>
    <string name="bubble_key_up" translatable="false">\u2191</string>
    <string name="bubble_key_down" translatable="false">\u2193</string>
    <string name="bubble_key_right" translatable="false">\u2192</string>
"""

MARKER = "action_open_in_bubble"


def patch(locale: str, values) -> str:
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
        open_in_bubble=values[0],
        channel=values[1],
        unavailable=values[2],
        no_session=values[3],
        close=values[4],
    )
    if locale == "values":
        block += KEY_LABELS

    # Insert before the LAST closing tag; some files carry a nested <resources> in comments.
    index = text.rindex("</resources>")
    text = text[:index] + block + text[index:]

    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)
    return f"OK    {locale}"


def main() -> int:
    if not os.path.isdir(BASE):
        print(f"Run this from the repo root; {BASE} not found", file=sys.stderr)
        return 1

    failures = 0
    for locale, values in TRANSLATIONS.items():
        result = patch(locale, values)
        print(result)
        if result.startswith("FAIL"):
            failures += 1
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
