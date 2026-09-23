#!/usr/bin/env python3
"""Remove the bubble strings that became dead when the bubble started hosting the real window.

The first bubble implementation drew its own cut-down UI (a terminal plus two rows of keys), so it
needed its own key labels, a close label and a "no session" message. The bubble is now
`TermuxBubbleActivity extends TermuxActivity` and inherits the whole window, so none of those
resources are referenced any more. Dead strings are worse than dead code here: they are shipped in
14 locales and get translated by hand.

Still used, and therefore kept:
  action_open_in_bubble, bubble_notification_channel_description,
  bubble_unavailable_message, bubble_open_failed_message

Idempotent: keys that are already gone are reported as SKIP. Run from the repo root:

    python scripts/remove_dead_bubble_strings.py
"""

import os
import sys

BASE = "app/src/main/res"

DEAD_KEYS = [
    "bubble_no_session_message",
    "bubble_action_close",
    "bubble_key_escape",
    "bubble_key_tab",
    "bubble_key_ctrl",
    "bubble_key_alt",
    "bubble_key_left",
    "bubble_key_up",
    "bubble_key_down",
    "bubble_key_right",
]

# Comment blocks that only described the removed key row.
DEAD_COMMENT_MARKERS = [
    "Bubble key row.",
]

def patch(locale: str) -> str:
    path = os.path.join(BASE, locale, "strings.xml")
    if not os.path.isfile(path):
        return f"SKIP  {locale}: no strings.xml"

    with open(path, "r", encoding="utf-8") as handle:
        lines = handle.read().split("\n")

    kept = []
    removed = 0
    skip_comment_block = False
    for line in lines:
        stripped = line.strip()

        # Drop a leading comment block that only talked about the removed key row, plus the
        # `<string ...>` lines that belonged to it.
        if any(marker in line for marker in DEAD_COMMENT_MARKERS):
            skip_comment_block = not stripped.endswith("-->")
            removed += 1
            continue
        if skip_comment_block:
            removed += 1
            if stripped.endswith("-->"):
                skip_comment_block = False
            continue

        if any(f'name="{key}"' in line for key in DEAD_KEYS):
            removed += 1
            continue

        kept.append(line)

    if removed == 0:
        return f"SKIP  {locale}: nothing to remove"

    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(kept))
    return f"OK    {locale}: removed {removed} line(s)"

def main() -> int:
    if not os.path.isdir(BASE):
        print(f"Run this from the repo root; {BASE} not found", file=sys.stderr)
        return 1

    for locale in sorted(os.listdir(BASE)):
        if locale == "values" or locale.startswith("values-"):
            if os.path.isfile(os.path.join(BASE, locale, "strings.xml")):
                print(patch(locale))
    return 0

if __name__ == "__main__":
    sys.exit(main())
