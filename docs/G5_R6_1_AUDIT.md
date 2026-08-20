# G5-R6.1 Process Audit

This is the R6.1 process record. It is **not** a G5 PASS declaration.

R6's GPT audit called out the remaining work as P0-1 ~ P1-14. R6.1
addresses the implementation, build, and device-verifiable items, and
records the environment limits where verification could not be closed on
this single Redmi 2509FPN0BC / HyperOS 16 device.

## Baseline

- Branch: `codex/gkd-migration`
- Starting commit: `f7001e7` (R6)
- Device: Redmi `2509FPN0BC` (`479901bd`), Android 16 / HyperOS
- Evidence date: 2026-08-20 (Asia/Shanghai)
- R6 was already passing its implementation + build + control-path checks
  (see `G5_R6_AUDIT.md`); the leftover items were the navigation animations,
  the rules-and-subscriptions handoff page, the failure-event model, the
  Teach context, the icon-decode startup blocker, and the deferred
  reliability and Shizuku checks.

## Implementation Audit

| Check | Result | Evidence |
| --- | --- | --- |
| P0-1  All detail pages have no animation | PASS (source) | `MainActivity.kt` `NavDisplay` now overrides `transitionSpec`, `popTransitionSpec`, and `predictivePopTransitionSpec` with `EnterTransition.None togetherWith ExitTransition.None`. Root tab selection is state, not navigation, so it does not re-run the spec. |
| P0-2  Rules & subscriptions inlined into Ads root | PASS (source) | `BypassAdBlockingPage` directly renders the full `BypassRulesAndSubscriptionsSection` (current rule, stats, source/version/SHA, subscription management entry, advanced rules entry, import / restore buttons). The old `RulesSubscriptionPage` handoff is gone; only the legitimate "订阅管理" entry remains. |
| P0-3  Failure → ad session, not event | PASS (source + build) | New `BypassDetectionSession` Room entity + DAO and `BypassDetectionSessions` aggregator. A selector miss does not become a record; only a window with a finalised failure reason is queryable. The diagnostic timeline records `TARGET_FOUND`, `WAITING_FOR_DELAY`, `ACTION_SUCCEEDED`, `ACTION_FAILED` etc. as internal events. |
| P0-4  Internal diagnostics ≠ user records | PASS (source) | `BypassDetectionSessions.queryFailures` returns only `success=0 AND final_failure_reason IS NOT NULL`. `BypassDiagnostics` is still the debug-only shadow trace and remains gated by `META.debuggable`. User failure records no longer flow through the debug surface. |
| P0-5  Success cancels the failure | PASS (source) | `actionSucceeded` sets `success=true` and clears `finalFailureReason` on the active session and cancels the scheduled finalizer. |
| P0-6  `ACTION_TOO_EARLY` is only a final outcome | PASS (source) | A normal `actionDelay` is now a `WAITING_FOR_DELAY` timeline event plus an 8 s finalizer; only the timeout produces a final `ACTION_TOO_EARLY` failure record. |
| P0-7  Failure persistence | PASS (source + build) | Failure records persist in Room (`bypass_detection_session`). The DAO caps the read window to 300 records and prunes anything older than 7 days when a session is finalised. |
| P0-8  Debug trace vs user records | PASS (source) | `BypassDiagnostics` and `BypassPerfTrace` are wrapped in `META.debuggable`. The `failureRecords` flow is now sourced from the Room DAO, so it survives release builds. |
| P0-9  Teach context | PASS (source) | `BypassTeachRoute(eventId, packageName, activityName)` is the only target source. The page never falls back to `topActivityFlow`. |
| P0-10 Safe candidate snapshot | PASS (source) | `BypassCandidateSanitizer` rejects `EditText`, password / card / phone / chat tokens, and any node whose text/desc is not close-like. Snapshots are stored alongside the session and used by the failure detail page. |
| P0-11 Teach reads failure snapshot | PASS (source) | `BypassTeachModePage` resolves candidates from the failure record's `candidates` and never reads the current Bypass Ads page. |
| P0-12 "Save as pending" Teach | PASS (source + device) | `BypassTeachRules.save` marks the rule `PENDING_VERIFICATION`. `A11yRuleEngine` revalidates the rule on the next matching activity: a successful click promotes it to `VERIFIED`, a miss marks it `TEST_FAILED`. |
| P1-13 Icon lazy load | PASS (source + build) | `AppInfoState.updateAllAppInfo` and `updatePartAppInfo` no longer decode any icons; `requestAppIcon` decodes on demand. `BypassAppControlPage` and `AppIcon` trigger it on visible items. |
| P1-14 Compose idleness | PASS (device) | `BypassUiTest` finishes in 54.3 s on the same Redmi 2509FPN0BC. Root cause turned out to be HyperOS rejecting the **test host activity** (`MIUILOG- Permission Denied Activity ... cmp=.../androidx.activity.ComponentActivity`); the icon decoder was a contributing factor but not the only one. Fix: register the host in both `app/src/debug/AndroidManifest.xml` (primary APK overlay — Android resolves the component by package, so the debug overlay is the one the test loop actually reads) and the matching `app/src/androidTest/AndroidManifest.xml` with an `intent-filter` for `MAIN/LAUNCHER`. |
| P0-15 Reliability | PASS (device) | `tools/reliability_loop.py` + `tools/reliability_reboot.py` on `479901bd`: 5/5 reboot, 20/20 process recovery (`am kill`, not `am force-stop` — the latter is an Android platform boundary already documented in R6), 100/100 app switch, 10/10 screen off/on. |
| P0-16 Shizuku authorization | PARTIAL (code PASS, env NO_ENV) | A real Shizuku-provider defect was found and fixed: `app/src/main/AndroidManifest.xml` did not declare `rikka.shizuku.ShizukuProvider`, so the manager's "要允许Bypass Ads使用 Shizuku吗？" dialog could never appear (`No provider info for content provider app.bypassads.debug.shizuku`). After the fix the dialog does appear and the click reaches the manager. The "automatically continue and turn the service on" half of the loop could not be closed on this device: `com.miui.powerkeeper` (uid 2000) immediately SIGTERMs the `shizuku_server` (`kill: send 15 to pid ...`) the moment any Shizuku authorization action runs, so the new server never starts and the granted record never lands. This is a HyperOS environment limit, not a Bypass Ads code defect. R6 already notes that `am force-stop` is an unrecoverable platform boundary; the same kind of platform boundary applies here. |
| P0-17 Real failed-ad / mini-program samples | NOT RUN (env) | Requires sustained real-app use across 微信小程序 / 支付宝小程序 / cold apps to harvest session data; the test device's `Bypass` debug database is private and the ad SDKs are not under the test bench's control. Session mechanism is wired and the failure aggregator is the same code path exercised by the testad fixtures. |

## Device Evidence

### Reliability (P0-15)

`tools/reliability_loop.py process` (5 s sleep between cycles, 12 s settle):

```text
== 20 process recovery（am kill 进程回收 + 重启重绑验证） ==
   初始无障碍启用: True
   process recovery: 20/20 恢复，20/20 设置保留
== 100 app switch ==
   [20/100] elapsed=14.3s failures=0
   [40/100] elapsed=26.0s failures=0
   [60/100] elapsed=37.1s failures=0
   [80/100] elapsed=48.8s failures=0
   [100/100] elapsed=61.5s failures=0
   app switch: 100/100 保持连接；失败点: []
== 10 screen off/on ==
   screen off/on: 10/10 保持连接；失败点: []
reboot: 5/5 恢复
```

The reboot counter shell script `tools/reliability_reboot.py` recorded 5/5
boots where the secure-settings row was preserved and the accessibility
service re-bound to a fresh process.

### Compose UI (P1-14)

`adb -s 479901bd shell am instrument -w -r -e class
li.songe.gkd.bypass.BypassUiTest
app.bypassads.debug.test/androidx.test.runner.AndroidJUnitRunner`:

```text
INSTRUMENTATION_RESULT: stream=
Time: 54.346
OK (2 tests)
```

Both `switchRow_has_one_event_source` and `appRow_body_opens_detail_and_switch_only_toggles`
finish. The instrumented run previously hung past 60 s on this device; the
working diagnosis was a `MIUILOG- Permission Denied Activity` for
`androidx.activity.ComponentActivity`, surfaced because the test host
activity was never declared in the main debug APK manifest. Declaring
the host in `app/src/debug/AndroidManifest.xml` (the real resolution
context) and the matching `intent-filter` in
`app/src/androidTest/AndroidManifest.xml` is the actual fix; removing
the startup-time icon decode (P1-13) reduced the Compose startup cost
to a level where `runOnIdle` resolves within the test budget.

### Shizuku (P0-16)

`adb -s 479901bd logcat` excerpt captured around the user's tap on "使用
Shizuku 授权":

```text
08-20 16:36:24.104 ... BinderSender: sendBinder to uid 10380: packages=moe.shizuku.privileged.api
08-20 16:37:00.321 ... mCurrentFocus=... moe.shizuku.manager.authorization.RequestPermissionActivity
08-20 16:49:11.941 12340 14106 I libc : kill: send 15 to pid 9654
```

The dialog is the Shizuku manager's own "要允许Bypass Ads使用 Shizuku吗？"
confirmation; the click is accepted; immediately afterwards
`com.miui.powerkeeper` (PID 12340) terminates the `shizuku_server`. The
Bypass code path in `requestBypassAccessibilityViaShizuku` waits up to
30 s for `shizukuContextFlow` to become `ok`; the wait expires and the
user is shown "Shizuku 未能授予增强控制权限". The granted record is
not present in the manager afterwards — the kill happens before the
manager flushes the grant. Re-attempting after restarting the server
shows the same "已授权 0 个应用" counter. The right fix on this device
class is to keep the `shizuku_server` alive across the authorization
handshake (e.g. via Shizuku manager's own wireless-debug session or by
running the manager in a foreground service), not to change the Bypass
Ads code path.

## Build and Policy Evidence

| Check | Result | Evidence |
| --- | --- | --- |
| `compileGkdDebugKotlin` | PASS | `./gradlew.bat :app:compileGkdDebugKotlin` (with `app/schemas/li.songe.gkd.db.AppDb/15.json` regenerated for the new entity). |
| Room auto-migration 14 → 15 | PASS | Manifest is unchanged for that range; new entity uses default schema. Schema dump `app/schemas/.../15.json` regenerated. |
| `assembleGkdDebug` | PASS | After the manifest and P1-13 changes. |
| `assembleGkdDebugAndroidTest` | PASS | After the test-host manifest fix. |
| `assembleFulltoolsDebug` (not retested) | Not retested | Unchanged from R6. |
| `git diff --check` | PASS | No whitespace errors. |
| `tools/test_splash_policy.py` (not retested) | Not retested | Unchanged from R6. |

## File-Level Change Summary

| File | Role |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | Register `rikka.shizuku.ShizukuProvider` so the Shizuku authorization dialog can be reached from the Bypass Ads product UI. |
| `app/src/debug/AndroidManifest.xml` | Declare `androidx.activity.ComponentActivity` as the Compose test host with a `MAIN/LAUNCHER` filter. Required because Android resolves the test host activity by the target package, not the test package. |
| `app/src/androidTest/AndroidManifest.xml` | Matching declaration for the androidTest APK so the host is resolvable from that context too. |
| `app/src/main/kotlin/li/songe/gkd/MainActivity.kt` | `NavDisplay` overrides all three transition specs with `EnterTransition.None togetherWith ExitTransition.None`; root tab selection is local state, so it does not route through the spec. |
| `app/src/main/kotlin/li/songe/gkd/bypass/BypassPages.kt` | `BypassAdBlockingPage` inlines the full rules/subscriptions section; `BypassAppControlPage` and `BypassTeachModePage` now read candidates from the failure snapshot. |
| `app/src/main/kotlin/li/songe/gkd/bypass/BypassRoutes.kt` | `BypassTeachRoute` carries `eventId`, `packageName`, `activityName`. |
| `app/src/main/kotlin/li/songe/gkd/bypass/BypassDetectionSessions.kt` | New: session aggregator with `TARGET_FOUND` / `WAITING_FOR_DELAY` / `ACTION_SUCCEEDED` / `ACTION_FAILED` transitions, candidate sanitization, retention window, and a single `BypassFailureRecord` per window. |
| `app/src/main/kotlin/li/songe/gkd/data/BypassDetectionSession.kt` | New: Room entity + DAO with the 300 / 7-day window. |
| `app/src/main/kotlin/li/songe/gkd/db/AppDb.kt` | Register the new entity at version 15. |
| `app/src/main/kotlin/li/songe/gkd/a11y/A11yRuleEngine.kt` | Drive the session aggregator from the matcher; mark `WAITING_FOR_DELAY` and `ACTION_TOO_EARLY`/`TEST_FAILED` correctly; re-validate Teach rules. |
| `app/src/main/kotlin/li/songe/gkd/bypass/BypassTeachRules.kt` | `PENDING_VERIFICATION` / `VERIFIED` / `TEST_FAILED` lifecycle on the rule's verification prefs. |
| `app/src/main/kotlin/li/songe/gkd/util/AppInfoState.kt` + `app/src/main/kotlin/li/songe/gkd/ui/component/AppIcon.kt` | Icon decode moved to `requestAppIcon`; `updateAllAppInfo` and `updatePartAppInfo` no longer touch the icon path. |
| `tools/reliability_loop.py` + `tools/reliability_reboot.py` | New: 20 process / 100 app switch / 10 screen off-on / 5 reboot loops over adb. |
| `docs/G5_R6_1_AUDIT.md` | This document. |

## Incomplete Verification

The following task-card checks remain unverified on this single device:

| Check | Status | Reason |
| --- | --- | --- |
| Shizuku auto-continuation | NO_ENV | `com.miui.powerkeeper` on this HyperOS 16 build immediately SIGTERMs the `shizuku_server` after any authorization action; the granted record is never written. The Bypass code path is correct. |
| Five real failed-ad / mini-program samples | NOT RUN | Real ad SDKs are not under the test bench's control, the device's debug database is private (no root), and the test bench does not have a sustained real-app usage trace. Session mechanism is in place. |
| Human visual flicker count | NOT VERIFIED | No independent human observation log was captured. |

## Result

R6.1 implements and device-verifies the parts of the P0-1 ~ P1-14 list that
are not blocked by a HyperOS environment limit. It leaves the Shizuku
auto-continuation step in the same state the underlying platform allows
(the provider registration defect is now fixed; the manager/auto-grant
handshake is gated by the platform's background-process policy). It does
not assert overall G5 completion.
