#!/usr/bin/env python3
"""Reword ``bubble_on_background_summary`` in every locale.

The old wording promised something the code no longer does: it said the bubble is
*closed when you return*, which reads as if that half were a setting. It is not --
returning to the app always dismisses the bubble. The new wording describes only
what the switch actually controls: showing the bubble on the way out, so the
terminal stays one tap away on top of other apps.

RU is the source of truth; the other 13 locales are kept in sync.

Byte-level edit: every res/values*/strings.xml in this project is CRLF, so the
files are read and written as bytes and the line-ending census is asserted before
and after each write.
"""

import re
import sys

RES = "app/src/main/res"

KEY = "bubble_on_background_summary"

# locale directory -> new value. RU first: it is the source of truth.
NEW = {
    "values-ru": "При уходе в фон показывать пузырёк для быстрого доступа к терминалу поверх других приложений",
    "values": "Show a bubble when the app goes to the background for quick access to the terminal over other apps",
    "values-ar": "إظهار الفقاعة عند الانتقال إلى الخلفية للوصول السريع إلى الطرفية فوق التطبيقات الأخرى",
    "values-b+zh+Hant": "進入背景時顯示氣泡，以便在其他應用程式之上快速存取終端機",
    "values-de": "Beim Wechsel in den Hintergrund eine Blase für schnellen Zugriff auf das Terminal über anderen Apps anzeigen",
    "values-es": "Mostrar la burbuja al pasar a segundo plano para acceder rápido a la terminal sobre otras apps",
    "values-fr": "Afficher la bulle en arrière-plan pour accéder rapidement au terminal par-dessus les autres applis",
    "values-hi": "पृष्ठभूमि में जाने पर अन्य ऐप्स के ऊपर टर्मिनल तक त्वरित पहुँच के लिए बबल दिखाएँ",
    "values-in": "Tampilkan balon saat masuk ke latar belakang untuk akses cepat ke terminal di atas aplikasi lain",
    "values-ja": "バックグラウンドに移ると、他のアプリの上からターミナルにすばやくアクセスできるようバブルを表示します",
    "values-ko": "백그라운드로 전환하면 다른 앱 위에서 터미널에 빠르게 접근할 수 있도록 버블을 표시합니다",
    "values-pt": "Mostrar a bolha ao ir para segundo plano para acesso rápido ao terminal sobre outros apps",
    "values-tr": "Arka plana geçince diğer uygulamaların üzerinde terminale hızlı erişim için baloncuk göster",
    "values-zh": "进入后台时显示气泡，以便在其他应用之上快速访问终端",
}

# The element is matched by its name attribute so the old text never has to be
# repeated here; the match count is asserted to be exactly one.
PATTERN = re.compile(
    r'(<string name="' + KEY + r'">)(.*?)(</string>)'
)

# Characters that would need XML escaping, or that the i18n procedure forbids.
FORBIDDEN = ["&", "<", ">", '"', "'", "\\"]


def census(raw):
    """Return (CRLF count, lone-LF count, has-BOM) for a byte string."""
    return raw.count(b"\r\n"), raw.count(b"\n") - raw.count(b"\r\n"), raw[:3] == b"\xef\xbb\xbf"


def check(path, raw, label):
    crlf, lone_lf, bom = census(raw)
    if lone_lf != 0:
        sys.exit("FAIL %s (%s): lone LF = %d" % (path, label, lone_lf))
    if bom:
        sys.exit("FAIL %s (%s): BOM present" % (path, label))
    return crlf


def main():
    failures = []
    for locale, value in NEW.items():
        for bad in FORBIDDEN:
            if bad in value:
                sys.exit("FAIL new value for %s contains %r" % (locale, bad))

        path = "%s/%s/strings.xml" % (RES, locale)
        raw_before = open(path, "rb").read()
        crlf_before = check(path, raw_before, "before")

        text = raw_before.decode("utf-8")
        matches = PATTERN.findall(text)
        if len(matches) != 1:
            sys.exit("FAIL %s: expected exactly 1 <%s>, found %d" % (path, KEY, len(matches)))
        old_value = matches[0][1]

        text = PATTERN.sub(lambda m: m.group(1) + value + m.group(3), text, count=1)
        raw_after = text.encode("utf-8")

        crlf_after = check(path, raw_after, "after")
        if crlf_after != crlf_before:
            sys.exit(
                "FAIL %s: CRLF count changed %d -> %d" % (path, crlf_before, crlf_after)
            )

        open(path, "wb").write(raw_after)

        # Re-read from disk: the gate must hold for what is actually stored.
        check(path, open(path, "rb").read(), "on disk")

        print("%-18s CRLF=%-4d ok" % (locale, crlf_after))
        print("    old: %s" % old_value)
        print("    new: %s" % value)

    if failures:
        sys.exit("FAILURES: %s" % failures)
    print("\nall %d locales rewritten" % len(NEW))


if __name__ == "__main__":
    main()
