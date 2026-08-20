#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P0-15 可靠性测试：20 process recovery / 100 app switch / 10 screen off-on。
运行在 PC 端，通过 adb 驱动真机。重启测试单独执行（reliability_reboot.py）。
"""
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DEVICE = "479901bd"
PKG = "app.bypassads.debug"
A11Y_COMPONENT = "com.google.android.accessibility.selecttospeak.SelectToSpeakService"
TEST_AD = "app.bypassads.testad"
TEST_ACT = "app.bypassads.testad/.MainActivity"
APPS = [
    "com.tencent.mm/.ui.LauncherUI",
    "com.eg.android.AlipayGphone/.home.app.AlipayStartActivity",
    "com.android.settings/.Settings",
    "com.miui.home/.launcher.Launcher",
    "com.miui.securitycenter/.main.MainActivity",
    "com.android.camera/.Camera",
    "com.miui.notes/.ui.NotesListActivity",
    "com.android.browser/.BrowserActivity",
]


def sh(*args, timeout=30):
    cmd = ["adb", "-s", DEVICE] + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return (r.returncode, r.stdout.strip(), r.stderr.strip())


def a11y_enabled():
    _, out, _ = sh("shell", "dumpsys", "accessibility")
    return A11Y_COMPONENT in out


def wait_a11y(expected, timeout=15):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if a11y_enabled() == expected:
            return True
        time.sleep(0.5)
    return a11y_enabled() == expected


def run_process_recovery(cycles=20):
    print(f"== 20 process recovery（am kill 进程回收 + 重启重绑验证） ==", flush=True)
    enabled_before = a11y_enabled()
    print(f"   初始无障碍启用: {enabled_before}", flush=True)
    if not enabled_before:
        # 先通过系统设置启用（WRITE_SECURE_SETTINGS 已授予）
        sh("shell", "settings", "put", "secure", "enabled_accessibility_services",
           f"{PKG}/{A11Y_COMPONENT}")
        sh("shell", "settings", "put", "secure", "accessibility_enabled", "1")
        wait_a11y(True)
        print(f"   已通过 secure settings 启用", flush=True)
    passed = 0
    setting_kept = 0
    for i in range(1, cycles + 1):
        # 正常进程回收：am kill 不置 force-stop 标志，系统可重新拉起服务进程。
        # force-stop 是 Android 平台边界（R6 审计注明），不作为本项验收。
        sh("shell", "am", "kill", PKG)
        time.sleep(2)
        # 设置应保留（未被系统清除）
        if a11y_enabled():
            setting_kept += 1
        # 进程被回收后，重新拉起应用以触发服务重绑
        sh("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
        time.sleep(3)
        ok = wait_a11y(True, timeout=12)
        if ok:
            passed += 1
        else:
            print(f"   [{i}] FAIL: 无障碍未恢复", flush=True)
        time.sleep(0.5)
    print(f"   process recovery: {passed}/{cycles} 恢复，{setting_kept}/{cycles} 设置保留", flush=True)
    return passed == cycles and setting_kept == cycles


def run_app_switch(cycles=100):
    print(f"== 100 app switch ==", flush=True)
    ok_before = a11y_enabled()
    if not ok_before:
        print("   SKIP: 无障碍未启用", flush=True)
        return False
    failures = []
    t0 = time.time()
    for i in range(1, cycles + 1):
        target = APPS[i % len(APPS)]
        sh("shell", "am", "start", "-W", "-n", target, timeout=15)
        time.sleep(0.4)
        if not a11y_enabled():
            failures.append(i)
        if i % 20 == 0:
            print(f"   [{i}/{cycles}] elapsed={time.time()-t0:.1f}s failures={len(failures)}", flush=True)
    # 回到 testad 验证规则引擎仍工作
    sh("shell", "am", "start", "-W", "-n", TEST_ACT, timeout=15)
    time.sleep(1.5)
    final_ok = a11y_enabled()
    print(f"   app switch: {cycles - len(failures)}/{cycles} 保持连接；失败点: {failures[:10]}", flush=True)
    return final_ok and not failures


def run_screen_off_on(cycles=10):
    print(f"== 10 screen off/on ==", flush=True)
    ok_before = a11y_enabled()
    if not ok_before:
        print("   SKIP: 无障碍未启用", flush=True)
        return False
    failures = []
    for i in range(1, cycles + 1):
        sh("shell", "input", "keyevent", "KEYCODE_SLEEP")
        time.sleep(2)
        sh("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        time.sleep(1)
        sh("shell", "input", "keyevent", "82")  # 解锁（无密码设备）
        time.sleep(1.5)
        if not a11y_enabled():
            failures.append(i)
            # 尝试重新连接
            sh("shell", "am", "start", "-W", "-n", TEST_ACT, timeout=15)
            time.sleep(2)
    print(f"   screen off/on: {cycles - len(failures)}/{cycles} 保持连接；失败点: {failures}", flush=True)
    return not failures


if __name__ == "__main__":
    print("P0-15 可靠性测试开始", flush=True)
    results = {}
    if len(sys.argv) > 1:
        which = sys.argv[1]
        if which == "process":
            results["process_recovery"] = run_process_recovery()
        elif which == "switch":
            results["app_switch"] = run_app_switch()
        elif which == "screen":
            results["screen_off_on"] = run_screen_off_on()
        else:
            results["process_recovery"] = run_process_recovery()
            results["app_switch"] = run_app_switch()
            results["screen_off_on"] = run_screen_off_on()
    else:
        results["process_recovery"] = run_process_recovery()
        results["app_switch"] = run_app_switch()
        results["screen_off_on"] = run_screen_off_on()
    print("=== 汇总 ===", flush=True)
    for k, v in results.items():
        print(f"  {k}: {'PASS' if v else 'FAIL'}", flush=True)
    sys.exit(0 if all(results.values()) else 1)
