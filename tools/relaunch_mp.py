#!/usr/bin/env python3
"""Relaunch the 星星充电 mini-program repeatedly to trigger its splash ad.

Closes the mini-program (WeChat 关闭 button), re-taps the entry in the
search results, waits for the ad window, and dumps the UI for evidence.
Only used to OBSERVE the real ad; no ad interaction happens here.
"""
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

SERIAL = "479901bd"
DUMP = "/sdcard/ui.xml"
LOCAL = Path("tmp_ui.xml")
CLOSE = (1101, 216)      # WeChat mini-program 关闭 button
ENTRY = (306, 1092)      # 星星充电 entry in 使用过的小程序

AD_HINTS = ["广告", "跳过", "关闭", "skip", "ad_close", "立即打开"]


def sh(*args):
    return subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True, timeout=40)


def dump_nodes():
    sh("shell", "uiautomator", "dump", DUMP)
    sh("pull", DUMP, str(LOCAL))
    root = ET.parse(str(LOCAL)).getroot()
    out = []
    for n in root.iter("node"):
        txt = (n.get("text") or n.get("content-desc") or "").strip()
        if txt:
            out.append((txt, n.get("bounds"), n.get("class", "")))
    return out


def tap(x, y):
    sh("shell", "input", "tap", str(x), str(y))


def main():
    rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 4
    for i in range(rounds):
        print(f"=== round {i + 1} ===")
        tap(*CLOSE)
        time.sleep(1.5)
        tap(*ENTRY)
        time.sleep(9)
        nodes = dump_nodes()
        ad_hits = [n for n in nodes if any(h.lower() in n[0].lower() for h in AD_HINTS)]
        print(f"nodes={len(nodes)} ad_hints={len(ad_hits)}")
        for txt, bounds, cls in ad_hits[:15]:
            print(f"  HINT {txt!r} {bounds} {cls}")
        # record a compact listing for evidence
        with open(f"tmp_round_{i + 1}.txt", "w", encoding="utf-8") as f:
            for txt, bounds, cls in nodes:
                f.write(f"{txt!r} {bounds} {cls}\n")


if __name__ == "__main__":
    main()
