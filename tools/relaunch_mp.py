#!/usr/bin/env python3
"""Relaunch the 星星充电 mini-program repeatedly to trigger its splash ad.

Closes the mini-program (WeChat 关闭 button), re-taps the entry in the
search results, waits for the ad window, and scans the UI for AD-ONLY
evidence. Only used to OBSERVE the real ad; no ad interaction happens here.

Privacy (P0-6): the full uiautomator XML is NEVER saved anywhere (no local
file, no repo file); the device dump is read through a pipe and deleted on
the device right after. Only whitelisted ad-related text/desc/viewId values
are printed, truncated to 40 chars; every other node text is shown as
<redacted>. Bounds/class/clickable are non-content and always shown.
"""

import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path

SERIAL = "479901bd"
DUMP = "/sdcard/bypass_ui_dump.xml"   # device-side scratch, deleted after use
CLOSE = (1101, 216)      # WeChat mini-program 关闭 button
ENTRY = (306, 1092)      # 星星充电 entry in 使用过的小程序

# Whitelisted ad evidence tokens (case-insensitive). Everything else is
# redacted. This is the ONLY page text the tool may print.
AD_TOKENS = [
    "广告", "跳过", "略过", "关闭", "倒计时", "秒", "skip", "ad_close",
    "立即购买", "立即打开", "详情", "瓜子", "GNC", "推广", "sponsored", "ad",
]
MAX_SHOWN = 20


def sh(*args):
    return subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True, timeout=40)


def scan_ad_evidence():
    """Dump the current window, pipe it, delete the device file, and return
    only whitelisted ad evidence (text/bounds/class)."""
    sh("shell", "uiautomator", "dump", DUMP)
    raw = sh("shell", "cat", DUMP).stdout
    sh("shell", "rm", "-f", DUMP)
    try:
        root = ET.fromstring(raw)
    except ET.ParseError:
        return []
    out = []
    for n in root.iter("node"):
        text = (n.get("text") or "").strip()
        desc = (n.get("content-desc") or "").strip()
        vid = (n.get("resource-id") or "").strip()
        bounds = n.get("bounds") or ""
        cls = n.get("class") or ""
        clickable = n.get("clickable") == "true"
        hit = next((t for t in (text, desc, vid) if any(k.lower() in t.lower() for k in AD_TOKENS)), None)
        if hit is None:
            # Non-ad node: show structure only, never the content.
            out.append(("<redacted>", bounds, cls, clickable))
            continue
        shown = hit[:40]
        kind = "AD"
        out.append((shown, bounds, cls, clickable, kind))
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
        nodes = scan_ad_evidence()
        ad_hits = [n for n in nodes if len(n) == 5 and n[4] == "AD"]
        print(f"nodes={len(nodes)} ad_hits={len(ad_hits)}")
        for entry in ad_hits[:MAX_SHOWN]:
            shown, bounds, cls, clickable, _ = entry
            print(f"  AD {shown!r} {bounds} {cls} clickable={clickable}")
        # No per-round files are ever written (privacy: no full dumps).


if __name__ == "__main__":
    main()
