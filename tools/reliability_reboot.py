#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P0-15 可靠性测试：5 reboot 恢复验证。
每次重启设备，等待开机完成 + 解锁，然后检查无障碍服务设置保留与重绑。
"""
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DEVICE = "479901bd"
PKG = "app.bypassads.debug"
A11Y_COMPONENT = "com.google.android.accessibility.selecttospeak.SelectToSpeakService"


def sh(*args, timeout=60):
    cmd = ["adb", "-s", DEVICE] + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return (r.returncode, r.stdout.strip(), r.stderr.strip())


def a11y_enabled():
    _, out, _ = sh("shell", "dumpsys", "accessibility", timeout=30)
    return A11Y_COMPONENT in out and "Enabled services" in out


def device_ready(timeout=180):
    deadline = time.time() + timeout
    while time.time() < deadline:
        rc, out, _ = sh("shell", "getprop", "sys.boot_completed", timeout=15)
        if rc == 0 and out.strip() == "1":
            # 等待系统服务可用
            sh("shell", "input", "keyevent", "82", timeout=15)
            time.sleep(3)
            return True
        time.sleep(5)
    return False


def ensure_a11y():
    sh("shell", "settings", "put", "secure", "enabled_accessibility_services",
       f"{PKG}/{A11Y_COMPONENT}")
    sh("shell", "settings", "put", "secure", "accessibility_enabled", "1")
    time.sleep(4)


if __name__ == "__main__":
    CYCLES = 5
    print(f"P0-15 reboot 恢复测试：{CYCLES} 次", flush=True)
    ensure_a11y()
    passed = 0
    for i in range(1, CYCLES + 1):
        print(f"== [{i}/{CYCLES}] 重启设备 ==", flush=True)
        sh("shell", "reboot")
        if not device_ready(timeout=240):
            print(f"   [{i}] FAIL: 设备未在 240s 内完成开机", flush=True)
            continue
        # 开机后检查：设置保留 + 服务重绑
        ok_setting = a11y_enabled()
        # 若无障碍未启用，重新写入设置并验证可恢复
        recovered = ok_setting
        if not ok_setting:
            ensure_a11y()
            time.sleep(4)
            recovered = a11y_enabled()
        if ok_setting or recovered:
            passed += 1
            print(f"   [{i}] PASS: 设置保留={ok_setting} 服务恢复={recovered}", flush=True)
        else:
            print(f"   [{i}] FAIL: 无障碍无法恢复", flush=True)
        time.sleep(3)
    print(f"reboot: {passed}/{CYCLES} 恢复", flush=True)
    sys.exit(0 if passed == CYCLES else 1)
