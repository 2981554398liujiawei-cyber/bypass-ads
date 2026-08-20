#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
R6.2 策略矩阵真机验证。
场景 p..w（testad）x 三档策略，验证候选分类与策略 gate 的真实行为。

通过 storeFlow 配置文件（bypass_rule_metadata prefs 之外的 store）切换策略。
storeFlow 持久化在 filesDir/store 下的 JSON。策略字段 bypassAdStrategyMode。
"""
import json
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DEVICE = "479901bd"
PKG = "app.bypassads.debug"
TESTAD = "app.bypassads.testad"
TEST_ACT = f"{TESTAD}/.MainActivity"
STORE_HOST = "/data/local/tmp/bypass_store.json"

# 预期: 该场景在对应策略下是否应该点击成功（result=true）
# 场景: (保守, 激进, 疯狂)
EXPECT = {
    # A/B/C skip 一直可点（保守也允许）
    "p": (False, True, True),    # D text=关闭
    "q": (False, True, True),    # E desc=关闭广告
    "r": (False, True, True),    # F vid=ad_close
    "s": (False, True, True),    # G text=×
    "t": (False, True, True),    # H 结构 X
    "u": (False, False, True),   # I 坐标-only（仅疯狂）
    "w": (False, False, False),  # L 普通关闭非广告（绝不点击）
}


def sh(*args, timeout=30):
    cmd = ["adb", "-s", DEVICE] + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return (r.returncode, r.stdout.strip(), r.stderr.strip())


def set_strategy(mode_int: int):
    # storeFlow key="store" -> /sdcard/Android/data/<pkg>/files/store/store.json
    # (external files dir; the cache mirror is NOT read by the app)
    remote = f"/sdcard/Android/data/{PKG}/files/store/store.json"
    rc, out, _ = sh("shell", "cat", remote)
    data = {}
    if rc == 0 and out:
        try:
            data = json.loads(out)
        except Exception:
            data = {}
    data["bypassAdStrategyMode"] = mode_int
    payload = json.dumps(data, ensure_ascii=False)
    with open("/tmp/bypass_new_store.json", "w", encoding="utf-8") as f:
        f.write(payload)
    sh("push", "/tmp/bypass_new_store.json", remote)
    # 重启 app 使 store 生效
    sh("shell", "am", "force-stop", PKG)
    time.sleep(1)
    sh("shell", "am", "start", "-n", f"{PKG}/li.songe.gkd.MainActivity")
    time.sleep(4)


def run_scene(scene: str, strategy: int):
    # 设置策略
    set_strategy(strategy)
    # 启动 testad 场景
    sh("shell", "am", "force-stop", TESTAD)
    time.sleep(1)
    sh("shell", "am", "start", "-W", "-n", TEST_ACT, "--es", "scenario", scene)
    time.sleep(3)
    # 读取 testad 前台文本判断结果
    sh("shell", "uiautomator", "dump", "/sdcard/ux.xml")
    sh("shell", "cat", "/sdcard/ux.xml")
    rc, out, _ = sh("shell", "cat", "/sdcard/ux.xml")
    return out


def check_result(xml: str) -> str:
    if "测试已跳过" in xml:
        return "clicked"
    if "不应执行" in xml:
        return "misclick"
    return "noaction"


def main():
    print("R6.2 策略矩阵真机验证", flush=True)
    results = []
    for scene in ["p", "q", "r", "s", "t", "u", "w"]:
        for strategy in range(3):
            xml = run_scene(scene, strategy)
            actual = check_result(xml)
            expected = EXPECT[scene][strategy]
            ok = (actual == "clicked") == expected
            results.append((scene, strategy, expected, actual, ok))
            print(f"  scene={scene} strategy={strategy} expected_click={expected} actual={actual} {'PASS' if ok else 'FAIL'}", flush=True)
    passed = sum(1 for r in results if r[4])
    print(f"=== 汇总: {passed}/{len(results)} PASS ===", flush=True)
    for r in results:
        if not r[4]:
            print(f"  FAIL scene={r[0]} strategy={r[1]} expected={r[2]} actual={r[3]}", flush=True)
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
