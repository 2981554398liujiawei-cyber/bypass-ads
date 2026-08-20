#!/usr/bin/env python3
"""Minimal uiautomator helper for the real-ad Field Gate.

Usage:
    python ui_auto.py dump [--out ui.xml]          # dump current UI tree
    python ui_auto.py text <text> [--index N]      # tap first node whose text/desc contains <text>
    python ui_auto.py tap <x> <y>                  # raw tap
    python ui_auto.py swipe <x1> <y1> <x2> <y2> [ms]
    python ui_auto.py input <text>                 # type text
    python ui_auto.py key <keycode>                # e.g. 4 = BACK, 66 = ENTER

Privacy: this is test tooling for the field gate; it only reads node
text/desc/bounds needed to navigate, and never uploads anything.
"""

import argparse
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

SERIAL = "479901bd"
DUMP = "/sdcard/ui_fieldgate.xml"
LOCAL = Path(__file__).with_name("ui_fieldgate.xml")


def sh(*args: str, timeout: int = 30) -> str:
    cmd = ["adb", "-s", SERIAL, *args]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout).stdout


def dump_tree() -> ET.Element:
    sh("shell", "uiautomator", "dump", DUMP)
    sh("pull", DUMP, str(LOCAL))
    return ET.parse(str(LOCAL)).getroot()


def all_nodes(root: ET.Element):
    for node in root.iter("node"):
        yield node


def node_text(node: ET.Element) -> str:
    return (node.get("text") or node.get("content-desc") or "").strip()


def find_node(root: ET.Element, needle: str, index: int = 0):
    hits = []
    for node in all_nodes(root):
        if needle.lower() in node_text(node).lower():
            hits.append(node)
    if not hits:
        return None
    return hits[min(index, len(hits) - 1)]


def center(node: ET.Element):
    b = node.get("bounds")  # [x1,y1][x2,y2]
    x1, y1, x2, y2 = map(int, b.replace("][", ",").strip("[]").split(","))
    return (x1 + x2) // 2, (y1 + y2) // 2


def main() -> int:
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("dump")
    d.add_argument("--out", default=None)

    t = sub.add_parser("text")
    t.add_argument("needle")
    t.add_argument("--index", type=int, default=0)
    t.add_argument("--list", action="store_true", help="list matching nodes instead of tapping")

    tp = sub.add_parser("tap")
    tp.add_argument("x", type=int)
    tp.add_argument("y", type=int)

    sw = sub.add_parser("swipe")
    sw.add_argument("x1", type=int)
    sw.add_argument("y1", type=int)
    sw.add_argument("x2", type=int)
    sw.add_argument("y2", type=int)
    sw.add_argument("ms", type=int, nargs="?", default=300)

    inp = sub.add_parser("input")
    inp.add_argument("text")

    k = sub.add_parser("key")
    k.add_argument("keycode", type=int)

    args = p.parse_args()

    if args.cmd == "dump":
        root = dump_tree()
        if args.out:
            Path(args.out).write_text(ET.tostring(root, encoding="unicode"), encoding="utf-8")
        for node in all_nodes(root):
            txt = node_text(node)
            if txt:
                print(f"{txt[:60]!r} {node.get('bounds')} class={node.get('class','')}")
        return 0

    if args.cmd == "text":
        root = dump_tree()
        if args.list:
            for i, node in enumerate(all_nodes(root)):
                if args.needle.lower() in node_text(node).lower():
                    print(f"[{i}] {node_text(node)[:50]!r} {node.get('bounds')}")
            return 0
        node = find_node(root, args.needle, args.index)
        if node is None:
            print(f"NOT FOUND: {args.needle}", file=sys.stderr)
            return 1
        x, y = center(node)
        print(f"tap {x} {y} <- {node_text(node)[:40]!r}")
        sh("shell", "input", "tap", str(x), str(y))
        return 0

    if args.cmd == "tap":
        sh("shell", "input", "tap", str(args.x), str(args.y))
        return 0

    if args.cmd == "swipe":
        sh("shell", "input", "swipe", str(args.x1), str(args.y1), str(args.x2), str(args.y2), str(args.ms))
        return 0

    if args.cmd == "input":
        sh("shell", "input", "text", args.text)
        return 0

    if args.cmd == "key":
        sh("shell", "input", "keyevent", str(args.keycode))
        return 0

    return 0


if __name__ == "__main__":
    sys.exit(main())
