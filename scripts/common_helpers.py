#!/usr/bin/env python3
"""Shared helpers for the one-shot / lint scripts under scripts/.

Importable as `from common_helpers import ...` when a script is run as
`python scripts/<name>.py` (sys.path[0] is the scripts/ directory).
"""

import io
import os
import re
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP_RES = os.path.join(REPO_ROOT, "app", "src", "main", "res")

STR_RE = re.compile(r"<string\s([^>]*?)>(.*?)</string>", re.S)
NAME_RE = re.compile(r'\bname="([^"]+)"')
TRANS_FALSE_RE = re.compile(r'\btranslatable="false"')
COMMENT_RE = re.compile(r"<!--.*?-->", re.S)


def read_if_exists(path):
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def write_text(path, text):
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)


def insert_after_string_anchor(text, anchor, new_lines):
    """Insert `new_lines` (final order) on the line after the first `name="anchor"` match.

    Indentation is copied from the anchor line. Returns the new text, or None when
    the anchor is absent.
    """
    lines = text.split("\n")
    for index, line in enumerate(lines):
        if f'name="{anchor}"' in line:
            indent = line[: len(line) - len(line.lstrip())]
            for offset, content in enumerate(new_lines):
                lines.insert(index + 1 + offset, f"{indent}{content}")
            return "\n".join(lines)
    return None


def insert_before_resources_end(text, block):
    """Insert `block` immediately before the LAST `</resources>` (some files carry a
    nested one in a comment). Returns the new text, or None when there is no closing tag.
    """
    if "</resources>" not in text:
        return None
    index = text.rindex("</resources>")
    return text[:index] + block + text[index:]


def require_repo_root(base):
    if not os.path.isdir(base):
        print(f"Run this from the repo root; {base} not found", file=sys.stderr)
        return False
    return True


def print_results(results):
    """Print each result line; return a process exit code (1 if any FAIL)."""
    failures = 0
    for result in results:
        print(result)
        if result.startswith("FAIL"):
            failures += 1
    return 1 if failures else 0


def read_xml_without_comments(path):
    if not os.path.isfile(path):
        return ""
    return COMMENT_RE.sub("", io.open(path, encoding="utf-8").read())
