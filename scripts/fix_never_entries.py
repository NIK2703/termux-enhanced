import os, re

BASE = r"E:\projects\termux-enhanced\app\src\main\res"
LOCALES = ["ar", "de", "es", "fr", "hi", "in", "ja", "ko", "pt", "tr", "zh"]

ENTRY_RE = re.compile(
    r'(<string-array name="soft_keyboard_toggle_behaviour_entries">)(.*?)(</string-array>)',
    re.DOTALL)
VALUE_RE = re.compile(
    r'(<string-array name="soft_keyboard_toggle_behaviour_values">)(.*?)(</string-array>)',
    re.DOTALL)

NEVER_ENTRY_ITEM = "        <item>@string/soft_keyboard_toggle_never</item>"
NEVER_VALUE_ITEM = "        <item>never</item>"

for loc in LOCALES:
    path = os.path.join(BASE, f"values-{loc}", "arrays.xml")
    if not os.path.exists(path):
        print("SKIP (no file)", path)
        continue
    with open(path, "r", encoding="utf-8") as f:
        txt = f.read()
    changed = False

    m = ENTRY_RE.search(txt)
    if m and "@string/soft_keyboard_toggle_never" not in m.group(2):
        inner = m.group(2).rstrip("\r\n")
        new_inner = inner + "\n" + NEVER_ENTRY_ITEM + "\n    "
        txt = txt[:m.start()] + m.group(1) + new_inner + m.group(3) + txt[m.end():]
        changed = True
        print(f"  +entries  values-{loc}")

    m2 = VALUE_RE.search(txt)
    if m2 is not None and "<item>never</item>" not in m2.group(2):
        inner = m2.group(2).rstrip("\r\n")
        new_inner = inner + "\n" + NEVER_VALUE_ITEM + "\n    "
        txt = txt[:m2.start()] + m2.group(1) + new_inner + m2.group(3) + txt[m2.end():]
        changed = True
        print(f"  +values   values-{loc}")

    if changed:
        # normalize to CRLF
        txt = txt.replace("\r\n", "\n").replace("\n", "\r\n")
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(txt)
        print("WROTE", path)
    else:
        print("unchanged", path)
