#!/usr/bin/env python3
"""Minimal uiautomator helper for the real-ad Field Gate.

Usage:
    python ui_auto.py dump [--ad-only]            # scan current UI (ad evidence only)
    python ui_auto.py text <text> [--index N]     # tap first node whose text/desc contains <text>
    python ui_auto.py tap <x> <y>                 # raw tap
    python ui_auto.py swipe <x1> <y1> <x2> <y2> [ms]
    python ui_auto.py input <text>                # type text
    python ui_auto.py key <keycode>               # e.g. 4 = BACK, 66 = ENTER

Privacy (P0-6): the full uiautomator XML is NEVER saved locally; the device
dump is read through a pipe and deleted on the device right after. `dump`
prints ONLY whitelisted ad-related text (truncated); every other node text
is <redacted>. Bounds/class/clickable are non-content and always shown.
"""

import argparse
import subprocess
import sys
import xml.etree.ElementTree as ET

SERIAL = "479901bd"
DUMP = "/sdcard/bypass_fieldgate_dump.xml"   # device-side scratch, deleted after use

# Whitelisted ad evidence tokens (case-insensitive). This is the ONLY page
# text the tool may print.
AD_TOKENS = [
    "广告", "跳过", "略过", "关闭", "倒计时", "秒", "skip", "ad_close",
    "立即购买", "立即打开", "详情", "瓜子", "GNC", "推广", "sponsored", "ad",
]
MAX_SHOWN = 20


def sh(*args: str, timeout: int = 30) -> str:
    cmd = ["adb", "-s", SERIAL, *args]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout).stdout


def dump_tree() -> ET.Element:
    """uiautomator dump -> pipe -> delete device file. No local copy."""
    sh("shell", "uiautomator", "dump", DUMP)
    try:
        raw = sh("shell", "cat", DUMP)
        return ET.fromstring(raw)
    finally:
        sh("shell", "rm", "-f", DUMP)


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


def print_node(node: ET.Element):
    text = (node.get("text") or "").strip()
    desc = (node.get("content-desc") or "").strip()
    vid = (node.get("resource-id") or "").strip()
    bounds = node.get("bounds") or ""
    cls = node.get("class") or ""
    clickable = node.get("clickable") == "true"
    hit = next((t for t in (text, desc, vid) if any(k.lower() in t.lower() for k in AD_TOKENS)), None)
    shown = (hit[:40] if hit else "<redacted>")
    kind = "AD" if hit else "   "
    print(f"{kind} {shown!r} {bounds} class={cls} clickable={clickable}")


def main() -> int:
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("dump")
    d.add_argument("--ad-only", action="store_true", help="print only whitelisted ad evidence")

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
        shown = 0
        for node in all_nodes(root):
            text = (node.get("text") or "").strip()
            desc = (node.get("content-desc") or "").strip()
            vid = (node.get("resource-id") or "").strip()
            is_ad = any(k.lower() in (text + desc + vid).lower() for k in AD_TOKENS)
            if args.ad_only and not is_ad:
                continue
            print_node(node)
            shown += 1
            if shown >= MAX_SHOWN:
                print(f"... ({shown} shown of {len(list(all_nodes(root)))} nodes, rest omitted)")
                break
        return 0

    if args.cmd == "text":
        root = dump_tree()
        if args.list:
            for i, node in enumerate(all_nodes(root)):
                if args.needle.lower() in node_text(node).lower():
                    print(f"[{i}] {node_text(node)[:40]!r} {node.get('bounds')}")
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
