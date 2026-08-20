# G5-R5 Process Audit

This document records repeatable evidence collected while implementing G5-R5.
It is not a G5 PASS declaration.

## Baseline

- Branch: `codex/gkd-migration`
- Base commit: `606182d`
- Test device: Redmi `2509FPN0BC`, Android 16, 1200x2608
- Evidence date: 2026-08-20 (Asia/Shanghai)

## Source and Build Checks

| Check | Result | Evidence |
| --- | --- | --- |
| Kotlin APK build | PASS | `:app:assembleGkdDebug` and `:app:assembleFulltoolsDebug` completed. |
| Android test APK compilation | PASS | `:app:assembleGkdDebugAndroidTest` completed. |
| Whitespace validation | PASS | `git diff --check` completed without errors. |
| Splash policy regression | PASS | `python tools/test_splash_policy.py` printed `splash policy: PASS`. |
| Default APK network boundary | PASS | `aapt dump permissions` found no `android.permission.INTERNET` in `app.bypassads.debug`. |
| Full Tools network boundary | PASS | `aapt dump permissions` found `android.permission.INTERNET` in `app.bypassads.fulltools.debug`. |

## Multi-source Fixture Audit

Command:

```powershell
python tools/build_splash_bundle.py tools/fixtures/source_a.json $env:TEMP/bypass-multisource-fixture.json --additional tools/fixtures/source_b.json --conflict-report $env:TEMP/bypass-multisource-report.json
```

Observed result:

- Coverage diff: primary-only `fixture.app1`, shared `fixture.shared`, secondary-only `fixture.app3`.
- A same-name group conflict was reported and retained the primary source; it was not automatically merged.
- The build exited non-zero because fixtures deliberately lack the required QQ Music and Alipay coverage. This is an expected release guard and proves a fixture cannot silently produce a self-use bundle.

## Device Audit

- ADB detected `479901bd` as an authorized device.
- The main APK and `app.bypassads.debug.test` were installed after the device-side USB installer confirmation.
- Home screenshot/hierarchy shows `运行状态`, the only `自动跳过广告` switch, and `运行保障` rows for accessibility, notification management, battery optimization, and background protection.
- Ads screenshot/hierarchy shows `开屏广告` as a read-only summary (`由首页主开关控制`), with no second splash master switch. It exposes the intended `开屏识别策略`, `规则包管理`, `高级规则管理`, and `未跳过诊断` routes.
- `广告 -> 规则包管理 -> Back` was executed with Android Back. The rule-package page had no root tabs and Back returned to Ads, not Home.
- Apps screenshot/hierarchy shows `搜索应用或包名`, `筛选`, `排序`, and `刷新`. App-body and switch hit regions are structurally separate in the hierarchy.
- Settings screenshot/hierarchy shows only prompt settings, backup/recovery, and advanced tools in its primary areas. No duplicated accessibility, notification, battery optimization, or trigger-log entry was observed.
- The installed instrumentation runner started `BypassUiTest` but did not complete its first Compose test before the target app was terminated during an earlier concurrent manual-launch attempt. This is inconclusive, not a passed test. A later attempt to automate Apps -> App detail was preempted by the device launcher and is also inconclusive.

## Outstanding Verification

- Re-run `BypassUiTest` in isolation and resolve its device-runner hang before treating UI interaction tests as passed.
- Capture the remaining required subpage screenshots.
- Run the remaining Android back-stack matrix on-device, including the app-detail source chain.
- Run reboot, process-kill, app-switching, and screen-off recovery loops. Do not claim these as passed without recorded runs.
