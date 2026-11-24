#!/usr/bin/env python3
"""Scan the project for constant declarations written in UPPER_SNAKE_CASE.

Usage:
    python3 scripts/scan_constants.py [root] [-o report.txt]

The report lists every declaration with file:line, groups the results by module,
and reports duplicate constant names. Build outputs, VCS data and scratch folders
are skipped so the report only contains real project sources.
"""

import argparse
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

SKIP_DIRS = {
    ".git", ".gradle", ".idea", ".vscode", "build", ".tmp",
    "third_party", "fastlane", "gradle", "art", "outputs",
}

TEXT_SUFFIXES = {
    ".java", ".kt", ".kts", ".c", ".h", ".cc", ".cpp", ".hpp", ".m", ".mm",
    ".xml", ".properties", ".gradle", ".sh", ".py", ".rs", ".toml", ".json",
}

UPPER = r"[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+"

PATTERNS = [
    # Java: static final TYPE NAME = ...
    re.compile(r"\bstatic\s+final\s+(?:[\w<>\[\],.?]+\s+)*(" + UPPER + r")\s*="),
    # Kotlin: const val NAME = ...
    re.compile(r"\bconst\s+val\s+(" + UPPER + r")\s*(?::[^=]+)?="),
    # C/C++: #define NAME ...
    re.compile(r"^\s*#\s*define\s+(" + UPPER + r")\b"),
    # C/C++: const TYPE NAME = ...
    re.compile(r"\bconst\s+(?:[\w:<>,*&]+\s+)*(" + UPPER + r")\s*="),
    # XML resources: <dimen name="UPPER_SNAKE">, etc.
    re.compile(r"<(?:dimen|color|integer|bool|string|item|declare-styleable)"
               r"[^>]*\bname\s*=\s*[\"'](" + UPPER + r")[\"']"),
    # .properties / shell style: NAME=value at the start of a line.
    re.compile(r"^\s*(" + UPPER + r")\s*[=:]"),
]

# Suppress matches inside comments / string literals when the line is mostly prose.
COMMENT_LINE = re.compile(r"^\s*(?://|/\*|\*|#|<!--)")


def find_declarations(line: str):
    text = line.strip()
    if COMMENT_LINE.match(text):
        return []
    found = []
    for pattern in PATTERNS:
        found.extend(pattern.findall(line))
    return sorted(set(found))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", default=".", help="project root")
    parser.add_argument("-o", "--output", help="write the report to this file")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    if not root.is_dir():
        print(f"not a directory: {root}", file=sys.stderr)
        return 2

    declarations = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        if any(part in SKIP_DIRS for part in path.relative_to(root).parts[:-1]):
            continue
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        for number, line in enumerate(lines, 1):
            for name in find_declarations(line):
                declarations.append((path.relative_to(root).as_posix(), number, name, line.strip()))

    by_module = defaultdict(list)
    names = Counter()
    for rel, number, name, line in declarations:
        by_module[rel.split("/", 1)[0]].append((rel, number, name, line))
        names[name] += 1

    out = []
    out.append(f"UPPER_SNAKE_CASE constant declarations: {len(declarations)} "
               f"({len(names)} distinct names)\n")
    for module, items in sorted(by_module.items()):
        out.append(f"\n=== {module} ({len(items)}) ===")
        for rel, number, name, line in items:
            out.append(f"{rel}:{number}: {line}")
    out.append("\n=== Duplicate names (defined in more than one place) ===")
    duplicates = [(name, count) for name, count in sorted(names.items()) if count > 1]
    if duplicates:
        for name, count in duplicates:
            out.append(f"{name}: {count}")
    else:
        out.append("(none)")

    report = "\n".join(out) + "\n"
    if args.output:
        Path(args.output).write_text(report, encoding="utf-8")
        print(f"report written to {args.output}: {len(declarations)} declarations, "
              f"{len(duplicates)} duplicate names")
    else:
        print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
