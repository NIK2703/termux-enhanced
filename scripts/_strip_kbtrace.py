"""One-shot: remove every KBTrace call site (and the class) from the app sources.

The mark is always at the start of its line, either as a single statement
    if (com.termux.app.terminal.io.KBTrace.ENABLED) com.termux.app.terminal.io.KBTrace.i(...);
or as a brace block
    if (com.termux.app.terminal.io.KBTrace.ENABLED) { ... }
so a paren/quote-aware scan to the end of the statement is enough.
"""
import io
import os
import sys

MARK = "if (com.termux.app.terminal.io.KBTrace.ENABLED)"

FILES = [
    "app/src/main/java/com/termux/app/TermuxActivity.java",
    "app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java",
]


def scan_statement(s, i):
    """Return the index just past the statement starting at i."""
    depth = 0
    in_str = False
    esc = False
    while i < len(s):
        c = s[i]
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        elif c == ";" and depth == 0:
            return i + 1
        i += 1
    return -1


def strip(text, path, removed):
    out = []
    pos = 0
    while True:
        k = text.find(MARK, pos)
        if k < 0:
            out.append(text[pos:])
            break
        line_start = text.rfind("\n", 0, k) + 1
        if text[line_start:k].strip():
            raise SystemExit("mark not at line start in %s: %r" % (path, text[line_start:k]))
        j = scan_statement(text, k)
        if j < 0:
            raise SystemExit("unterminated statement in %s at %d" % (path, k))
        # swallow the trailing newline of the last line of the statement
        nl = text.find("\n", j)
        end = len(text) if nl < 0 else nl + 1
        removed.append(text[line_start:end].rstrip("\r\n"))
        out.append(text[pos:line_start])
        pos = end
    return "".join(out)


total = 0
for rel in FILES:
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", rel)
    path = os.path.normpath(path)
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    removed = []
    new = strip(text, rel, removed)
    if new == text:
        print("no change: %s" % rel)
        continue
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(new)
    total += len(removed)
    print("=== %s: removed %d statement(s) ===" % (rel, len(removed)))
    for r in removed:
        print("  " + r.replace("\n", "\n  "))

print("total removed: %d" % total)
