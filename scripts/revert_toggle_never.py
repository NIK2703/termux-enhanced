"""Undo the 'never' option that was added to soft-keyboard-toggle-behaviour.

That addition was based on a misidentification: the option the user called
"Кнопка переключения панели ввода" is text_input_enabled (ru title of
text_input_show_title), not soft-keyboard-toggle-behaviour. The latter keeps its
original two choices (show/hide, enable/disable).
"""
import os
import re
import glob

BASE = r"E:\projects\termux-enhanced\app\src\main\res"

ENTRY_RE = re.compile(
    r'(<string-array name="soft_keyboard_toggle_behaviour_entries">)(.*?)(</string-array>)',
    re.DOTALL)
VALUE_RE = re.compile(
    r'(<string-array name="soft_keyboard_toggle_behaviour_values">)(.*?)(</string-array>)',
    re.DOTALL)


def drop_matching_lines(body, needle):
    return "\n".join(line for line in body.split("\n") if needle not in line)


def to_crlf(text):
    return text.replace("\r\n", "\n").replace("\n", "\r\n")


# 1. arrays.xml - drop the "never" item from both arrays, in every locale.
for path in sorted(glob.glob(os.path.join(BASE, "values*", "arrays.xml"))):
    txt = open(path, encoding="utf-8").read()
    original = txt

    m = ENTRY_RE.search(txt)
    if m and "@string/soft_keyboard_toggle_never" in m.group(2):
        cleaned = drop_matching_lines(m.group(2), "@string/soft_keyboard_toggle_never")
        txt = txt[:m.start(2)] + cleaned + txt[m.end(2):]

    m = VALUE_RE.search(txt)
    if m and "<item>never</item>" in m.group(2):
        cleaned = drop_matching_lines(m.group(2), "<item>never</item>")
        txt = txt[:m.start(2)] + cleaned + txt[m.end(2):]

    if txt != original:
        open(path, "w", encoding="utf-8", newline="").write(to_crlf(txt))
        print("arrays reverted:", path)

# 2. strings.xml - drop the string itself (it was only referenced by those arrays).
for folder in ("values", "values-ru"):
    path = os.path.join(BASE, folder, "strings.xml")
    txt = open(path, encoding="utf-8").read()
    if 'name="soft_keyboard_toggle_never"' not in txt:
        continue
    cleaned = drop_matching_lines(txt, 'name="soft_keyboard_toggle_never"')
    open(path, "w", encoding="utf-8", newline="").write(to_crlf(cleaned))
    print("string reverted:", path)

print("done")
