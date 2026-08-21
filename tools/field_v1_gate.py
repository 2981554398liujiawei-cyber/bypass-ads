#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""V1.0 device gate: TestAd matrix + HyperOS rebind + backup smoke.

Privacy: never persist a full UI tree. Only ad/result tokens are printed.
"""
from __future__ import annotations

import re
import subprocess
import sys
import time
from typing import Iterable

DEVICE = "479901bd"
PKG = "app.bypassads"
TESTAD = "app.bypassads.testad"
MAIN = f"{PKG}/li.songe.gkd.MainActivity"
TEST_ACT = f"{TESTAD}/.MainActivity"
A11Y = f"{PKG}/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
DUMP = "/data/local/tmp/ui.xml"

# Conservative / Aggressive / Crazy expected click for TestAd scenes.
EXPECT = {
    "a": (True, True, True),     # skip text
    "p": (False, True, True),    # close text
    "q": (False, True, True),    # close desc
    "r": (False, True, True),    # ad_close
    "s": (False, True, True),    # X
    "t": (False, False, True),   # structural
    "w": (False, False, False),  # ordinary close, not ad
    "ac": (False, False, False), # far banner + small ImageView
}

STRATEGIES = [("CONSERVATIVE", 0, "保守"), ("AGGRESSIVE", 1, "激进"), ("CRAZY", 2, "彻底疯狂")]


def sh(*args: str, timeout: int = 30) -> tuple[int, str, str]:
    cmd = ["adb", "-s", DEVICE, *args]
    r = subprocess.run(cmd, capture_output=True, timeout=timeout)
    out = r.stdout.decode("utf-8", "replace")
    err = r.stderr.decode("utf-8", "replace")
    return r.returncode, out, err


def dump_texts() -> str:
    sh("shell", "uiautomator", "dump", DUMP)
    rc, out, _ = sh("exec-out", f"cat {DUMP}")
    texts = re.findall(r'text="([^"]{1,80})"', out)
    return "\n".join(texts)


def click_text(label: str) -> bool:
    sh("shell", "uiautomator", "dump", DUMP)
    rc, xml, _ = sh("exec-out", f"cat {DUMP}")
    # Prefer exact text match with bounds.
    pat = rf'text="{re.escape(label)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    m = re.search(pat, xml)
    if not m:
        pat2 = rf'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="{re.escape(label)}"'
        m = re.search(pat2, xml)
    if not m:
        return False
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    sh("shell", "input", "tap", str(x), str(y))
    time.sleep(0.8)
    return True


def open_bypass() -> None:
    sh("shell", "am", "start", "-n", MAIN)
    time.sleep(1.5)
    click_text("首页")
    time.sleep(0.6)


def set_strategy(label: str) -> bool:
    open_bypass()
    click_text("广告")
    time.sleep(0.8)
    click_text("开屏识别策略")
    time.sleep(1.0)
    texts = dump_texts()
    if "跳过策略" not in texts:
        click_text("开屏识别策略")
        time.sleep(1.0)
        texts = dump_texts()
    ok = click_text(label)
    if label == "彻底疯狂":
        time.sleep(0.6)
        click_text("继续开启")
    time.sleep(0.6)
    # Confirm the ads page reports the selected strategy.
    click_text("‹")
    time.sleep(0.6)
    texts = dump_texts()
    return ok and (label in texts)


def run_scene(scene: str) -> str:
    sh("shell", "am", "force-stop", TESTAD)
    time.sleep(0.4)
    sh("shell", "am", "start", "-W", "-n", TEST_ACT, "--es", "scenario", scene)
    time.sleep(3.2)
    return dump_texts()


def classify(texts: str) -> str:
    if "测试已跳过" in texts:
        return "CLICKED"
    if "错误：发生了不应执行的动作" in texts:
        return "MISCLICK"
    return "NO_ACTION"


def a11y_bound() -> bool:
    rc, out, _ = sh("shell", "dumpsys", "accessibility")
    return "Bound services:{Service[label=Bypass Ads" in out or "label=Bypass Ads" in out


def home_status() -> str:
    open_bypass()
    texts = dump_texts()
    if "正常运行" in texts:
        return "NORMAL"
    if "正在恢复" in texts:
        return "RECOVERING"
    if "需要授权" in texts:
        return "NEED_AUTHORIZATION"
    return "UNKNOWN"


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    print("== V1.0 device gate ==")
    fails: list[str] = []

    # 0. install TestAd
    apk = "testad/build/outputs/apk/debug/testad-debug.apk"
    rc, out, err = sh("install", "-r", apk, timeout=60)
    print("TestAd install:", "OK" if rc == 0 else f"FAIL {err}")
    if rc != 0:
        fails.append("testad_install")

    # 1. TestAd matrix
    print("\n== TestAd matrix ==")
    for name, ordinal, label in STRATEGIES:
        print(f"-- strategy {name}")
        if not set_strategy(label):
            print(f"  WARN could not tap {label}, continuing")
        for scene, expect in EXPECT.items():
            want = expect[ordinal]
            texts = run_scene(scene)
            got = classify(texts)
            clicked = got == "CLICKED"
            ok = (clicked == want) and got != "MISCLICK"
            mark = "PASS" if ok else "FAIL"
            print(f"  {scene:3} {name:12} want={'CLICK' if want else 'NO'} got={got:8} {mark}")
            if not ok:
                fails.append(f"{scene}/{name}")
            if got == "MISCLICK":
                fails.append(f"MISCLICK:{scene}/{name}")

    # 2. HyperOS rebind without killing Bypass Ads
    print("\n== HyperOS rebind ==")
    before = home_status()
    print("before:", before)
    sh("shell", "settings", "put", "secure", "enabled_accessibility_services", "null")
    time.sleep(2)
    sh("shell", "settings", "put", "secure", "enabled_accessibility_services", A11Y)
    sh("shell", "settings", "put", "secure", "accessibility_enabled", "1")
    time.sleep(3)
    bound = a11y_bound()
    after = home_status()
    print("bound:", bound, "after:", after)
    if not bound or after != "NORMAL":
        fails.append(f"rebind bound={bound} ui={after}")

    # 3. Backup UI presence
    print("\n== Backup UI ==")
    open_bypass()
    click_text("设置")
    time.sleep(0.8)
    texts = dump_texts()
    if "导入备份" not in texts and "备份包含" not in texts:
        click_text("备份与恢复")
        time.sleep(0.8)
        texts = dump_texts()
    if "备份包含应用开关" in texts or "导入备份" in texts:
        print("backup page OK")
    else:
        print("backup page texts missing")
        fails.append("backup_ui")

    print("\n== RESULT ==")
    if fails:
        print("DEVICE GATE FAIL")
        for f in fails:
            print(" -", f)
        return 1
    print("DEVICE GATE PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
