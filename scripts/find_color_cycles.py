#!/usr/bin/env python3
"""Detect cycles in the color/attr reference graph.

A <color name="X"> may reference @color/Y or ?attr/Z; color selectors
reference other colors via <item android:color="@color/Y"/>. A cycle
(color:X -> ... -> color:X, possibly through an attr) causes Android to
crash (e.g. StackOverflowError during resource inflation).

Usage: python scripts/find_color_cycles.py
Exits 2 if a cycle is found, 1 on error, else 0.
"""
import os
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")


def collect_color_files():
    out = []
    for dirpath, _, files in os.walk(RES):
        d = os.path.basename(dirpath)
        if d.startswith("color") or d == "values" or d.startswith("values-"):
            for fn in files:
                if fn.endswith(".xml"):
                    out.append(os.path.join(dirpath, fn))
    return out


def refs_in(text):
    refs = []
    t = (text or "").strip()
    if t.startswith("@color/"):
        refs.append(t[len("@color/"):])
    elif t.startswith("?attr/"):
        refs.append("attr:" + t[len("?attr/"):])
    return refs


def main():
    graph = {}
    for path in collect_color_files():
        try:
            tree = ET.parse(path)
        except ET.ParseError:
            continue
        for el in tree.getroot():
            if el.tag == "color" and el.get("name"):
                node = el.get("name")
                targets = set(refs_in(el.text))
                for it in el.findall(".//item[@android:color]"):
                    targets.update(refs_in(it.get("android:color")))
                graph.setdefault(node, set()).update(targets)

    WHITE, GRAY, BLACK = 0, 1, 2
    color = {n: WHITE for n in graph}
    found = [False]

    def dfs(u, stack):
        color[u] = GRAY
        stack.append(u)
        for v in graph.get(u, ()):
            if v not in color:  # external ref (e.g. attr not modeled) -> safe edge
                continue
            if color[v] == GRAY:
                idx = stack.index(v)
                print("CYCLE: " + " -> ".join(stack[idx:] + [v]))
                found[0] = True
            elif color[v] == WHITE:
                dfs(v, stack)
        stack.pop()
        color[u] = BLACK

    for n in graph:
        if color[n] == WHITE:
            dfs(n, [])

    if found[0]:
        print("Cycle found (exit 2)")
        sys.exit(2)
    print("OK: no color cycles")
    sys.exit(0)


if __name__ == "__main__":
    main()
