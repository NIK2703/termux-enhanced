#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""Quality gate for a generated values-b+zh+Hant/strings.xml.

Checks, per module:
  [1] key set / key ORDER identical to the values-zh sibling
  [2] structure preserved: every non-CJK character sequence is byte-identical
      to the sibling (escapes \n, leading indentation, \" quotes, entities,
      %1$s placeholders) -- only CJK may differ
  [3] s2t round-trip: no Simplified-only character survived
  [4] Mainland-only vocabulary blacklist: 0 hits
  [5] house-style census: the Taiwan terms the app module uses
  [6] no value left byte-identical to the English base

Run: python scripts/check_zh_hant_quality.py
"""
import io
import os
import re
import sys

import opencc

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S2T = opencc.OpenCC("s2t")

STR_RE = re.compile(r"<string\s([^>]*?)>(.*?)</string>", re.S)
NAME_RE = re.compile(r'\bname="([^"]+)"')
TRANS_FALSE = re.compile(r'\btranslatable="false"')
COMMENT = re.compile(r"<!--.*?-->", re.S)
CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]")

# Simplified-only or Mainland-only vocabulary that must not appear in a
# Taiwan-oriented Traditional file. (CJK-composed, so the s2t gate misses them.)
MAINLAND_TERMS = [
    "信息", "文件夾", "文件夹", "软件", "硬件", "内存", "鼠标", "用户", "默认",
    "设置", "保存", "打开", "创建", "运行", "网络", "链接", "数据", "存储",
    "图标", "字体", "文本", "日志", "调试", "崩溃", "插件", "组件", "设备",
    "异常", "获取", "试图", "临时", "签名", "仓库", "脚本", "界面", "高级",
    "后台", "视频", "許可權", "訪問", "級別", "當前", "會話", "日誌", "除錯",
    "崩潰", "高階", "後臺", "禁用", "獲取", "試圖", "臨時", "簽名", "倉庫",
    "封禁", "酌情", "詳情", "截圖", "清單", "程式碼", "釋出", "提交", "發布",
]

# Taiwan terms the app module's hand-written values-b+zh+Hant uses (census 2026-09-19)
HOUSE_TERMS = ["檔案", "目錄", "儲存", "分享", "設定", "應用程式", "權限", "當機",
               "外掛", "終端機", "詳細", "偵錯", "停用", "啟用", "文字輸入", "字型",
               "儲存空間", "輕觸", "預設", "資訊", "背景", "開啟", "關閉", "建立",
               "複製", "貼上", "元件", "裝置", "網路", "連結", "取得", "例外",
               "工作階段", "記錄", "層級", "目前", "存取", "進階", "資訊清單"]

WHITELIST = {"群": "羣"}


def load(path):
    out = {}
    if not os.path.exists(path):
        return out
    raw = COMMENT.sub("", io.open(path, encoding="utf-8").read())
    for m in STR_RE.finditer(raw):
        nm = NAME_RE.search(m.group(1))
        if nm:
            out[nm.group(1)] = m.group(2)
    return out


def mask(text):
    """Blank out every CJK/CJK-punctuation char -> what remains must be identical."""
    return CJK.sub("\x00", text)


def check(module):
    res = os.path.join(ROOT, module, "src", "main", "res")
    zh = load(os.path.join(res, "values-zh", "strings.xml"))
    ht = load(os.path.join(res, "values-b+zh+Hant", "strings.xml"))
    en = load(os.path.join(res, "values", "strings.xml"))
    tf = {k for k, v in en.items() if TRANS_FALSE.search(v)}

    print("\n" + "=" * 74)
    print("MODULE %s   zh=%d  zh-Hant=%d  en=%d" % (module, len(zh), len(ht), len(en)))
    print("=" * 74)
    fails = 0

    # [1] key set and ORDER
    if list(zh) != list(ht):
        fails += 1
        print("  [1] FAIL key order/set differs")
        print("      only zh:", [k for k in zh if k not in ht])
        print("      only ht:", [k for k in ht if k not in zh])
    else:
        print("  [1] OK  key set + order identical (%d keys)" % len(ht))

    # [2] structure preserved
    bad = [k for k in zh if k in ht and mask(zh[k]) != mask(ht[k])]
    if bad:
        fails += 1
        print("  [2] FAIL %d values changed a non-CJK character:" % len(bad))
        for k in bad[:10]:
            print("      %s\n        zh: %r\n        ht: %r" % (k, zh[k], ht[k]))
    else:
        print("  [2] OK  escapes/indent/quotes/entities/placeholders untouched")

    # [3] s2t round-trip
    left = []
    for k, v in ht.items():
        for a, b in zip(v, S2T.convert(v)):
            if a != b and WHITELIST.get(a) != b:
                left.append((k, a, b))
    if left:
        fails += 1
        print("  [3] FAIL Simplified leftovers: %s" % left[:10])
    else:
        print("  [3] OK  no Simplified-only character (whitelist: 群)")

    # [4] Mainland vocabulary
    hits = []
    for k, v in ht.items():
        for t in MAINLAND_TERMS:
            if t in v:
                hits.append((k, t))
    if hits:
        fails += 1
        print("  [4] FAIL Mainland vocabulary: %s" % hits)
    else:
        print("  [4] OK  no Mainland-only term from the blacklist")

    # [5] house-style census
    body = "".join(ht.values())
    used = [(t, body.count(t)) for t in HOUSE_TERMS if t in body]
    print("  [5] house-style terms present: %s" % ", ".join("%s×%d" % t for t in used))

    # [6] untranslated vs English
    same = [k for k, v in ht.items()
            if k in en and k not in tf and v.strip() == en[k].strip() and CJK.search(zh.get(k, ""))]
    if same:
        fails += 1
        print("  [6] FAIL identical to English: %s" % same)
    else:
        print("  [6] OK  no value left in English")

    print("  --> %s" % ("ALL CHECKS PASSED" if not fails else "%d CHECK(S) FAILED" % fails))
    return fails


def main():
    total = 0
    for m in ("terminal-view", "termux-shared", "app"):
        total += check(m)
    print("\nTOTAL FAILURES: %d" % total)
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
