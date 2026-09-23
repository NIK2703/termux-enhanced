#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""Generate values-b+zh+Hant/strings.xml for a module that only has values-zh.

Approach: transform the *Simplified sibling* value-for-value (opencc s2twp +
a house-style fix map), never retype from English. That preserves every
escape (\n), the leading indentation of multi-line values and the entities
(&TERMUX_APP_NAME;) byte for byte -- only the CJK characters change.

The fix map aligns opencc's generic Taiwan output with the terminology the
app module's hand-written values-b+zh+Hant already uses (the project authority).

Usage: python scripts/make_zh_hant.py <module> [--write]
"""
import io
import os
import re
import sys

import opencc

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S2TWP = opencc.OpenCC("s2twp")
S2T = opencc.OpenCC("s2t")

# opencc s2twp output -> termux-enhanced Hant house style (census over
# app/src/main/res/values-b+zh+Hant/strings.xml, 2026-09-19).
# ORDER MATTERS: later rules see the output of earlier ones.
FIX = [
    # --- terminology s2twp does not get right for this project ---
    ("許可權", "權限"),            # app: 權限 x3, 許可權 x0 (許可權 = "licence")
    ("訪問", "存取"),              # app: 存取 x1, 訪問 x0
    ("不可存取", "無法存取"),
    ("級別", "層級"),              # app: 記錄層級
    ("當前", "目前"),              # app: 目前 x4, 當前 x0
    ("會話", "工作階段"),          # app: 工作階段 x26, 會話 x0
    ("日誌", "記錄"),              # logging: app uses 記錄層級 / Logcat 記錄
    ("除錯", "偵錯"),              # app: 偵錯 x8, 除錯 x0
    ("崩潰", "當機"),              # app: 當機報告
    ("高階", "進階"),              # Android zh-rTW: 進階
    ("後臺", "背景"),              # app: 背景 x8
    ("禁用", "停用"),              # app: 停用 x9
    ("異常", "例外"),              # app: 例外
    ("獲取", "取得"),              # app: 取得
    ("試圖", "嘗試"),
    ("臨時", "暫時"),
    ("簽名", "簽章"),
    ("封禁", "封鎖"),
    ("倉庫", "儲存庫"),
    ("界面", "介面"),
    ("文本", "文字"),
    ("查看", "檢視"),
    ("請檢視", "請參閱"),
    ("發布", "張貼"),
    ("釋出", "張貼"),              # s2twp renders 发布 as 釋出, not 發布
    ("提交", "張貼"),
    ("酌情", "斟酌"),
    ("了解", "瞭解"),
    ("存儲", "儲存"),
    ("操作詳情", "操作細節"),      # must precede 詳情 -> 詳細資訊
    ("詳情", "詳細資訊"),
    ("截圖", "螢幕截圖"),
    ("清單", "資訊清單"),          # app: 套件資訊清單 (runs after 應用 -> 應用程式)
    ("應用", "應用程式"),
    ("應用程式包", "應用程式套件"),  # after 應用 -> 應用程式
    ("包的", "套件的"),
    ("包上下文", "套件上下文"),
    # --- targeted rewrites ---
    ("應請求授予", "依要求授予"),
    ("請求", "要求"),              # app: 請求 survives only in the HTTP sense
    ("程式碼", "代碼"),            # 請求程式碼 -> 要求代碼
    ("儲存權限", "儲存空間權限"),    # app: 儲存空間
    ("執行在 Android SDK", "執行於 Android SDK"),
    ("在 Android >= 10 的背景啟動", "在 Android >= 10 上從背景啟動"),
    ("請在 Android 設定 -> 應用程式", "請從 Android 設定 -> 應用程式"),
    ("記錄層級設定為", "記錄層級設為"),
    ("可執行檔案", "可執行檔"),
    ("啟動器圖示將啟用", "啟動器圖示將被啟用"),
    # --- wording fixes found by the manual read-through (2026-09-19) ---
    ("請授予要求的權限", "請授予所要求的權限"),
    ("使用要求代碼", "以要求代碼"),
    ("請將其張貼到", "請張貼到"),
    ("我們不提供任何駭客相關工具/指令碼的支援。", "我們不支援任何與駭客相關的工具/指令碼。"),
    ("&TERMUX_APP_NAME; 應用程式的 $PREFIX 目錄對於 %1$s 應用程式無法存取。",
     "%1$s 應用程式無法存取 &TERMUX_APP_NAME; 應用程式的 $PREFIX 目錄。"),
    ("選擇字型（.ttf）", "選擇字型 (.ttf)"),   # app module's same key uses halfwidth
]

# characters the s2t round-trip flags but that are correct Taiwan forms
GATE_WHITELIST = {"群": "羣"}   # 群 is the normal Taiwan form; 羣 is a variant

def convert(text):
    out = S2TWP.convert(text)
    for a, b in FIX:
        out = out.replace(a, b)
        alt = S2TWP.convert(a)
        if alt != a:          # only if the rule key is itself Simplified
            out = out.replace(alt, b)
    return out

def main():
    module = sys.argv[1]
    write = "--write" in sys.argv
    res = os.path.join(ROOT, module, "src", "main", "res")
    src = os.path.join(res, "values-zh", "strings.xml")
    dst_dir = os.path.join(res, "values-b+zh+Hant")
    dst = os.path.join(dst_dir, "strings.xml")

    raw = open(src, "rb").read().decode("utf-8")
    if os.path.exists(dst):
        print("!! %s already exists -- refusing" % dst)
        return 1

    pat = re.compile(r'(<string\s[^>]*?>)(.*?)(</string>)', re.S)
    n = [0]

    def repl(m):
        n[0] += 1
        return m.group(1) + convert(m.group(2)) + m.group(3)

    out = pat.sub(repl, raw)
    print("module=%s  strings converted=%d  bytes %d -> %d"
          % (module, n[0], len(raw.encode("utf-8")), len(out.encode("utf-8"))))

    # gate 1: no Simplified-only character may survive
    bad = [(a, b) for a, b in zip(out, S2T.convert(out))
           if a != b and GATE_WHITELIST.get(a) != b]
    print("gate s2t round-trip: %s" % ("CLEAN" if not bad else "LEFTOVERS %s" % bad))
    if bad:
        return 1

    if write:
        os.makedirs(dst_dir, exist_ok=True)
        open(dst, "wb").write(out.encode("utf-8"))
        print("wrote %s" % dst)
    else:
        sys.stdout.reconfigure(encoding="utf-8")
        print(out)
    return 0

if __name__ == "__main__":
    sys.exit(main())
