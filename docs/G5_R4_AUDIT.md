# G5-R4 Process Audit

Date: 2026-08-19

This document is evidence for the G5-R4 implementation and regression run. It
is deliberately **not** a G5-R4 PASS declaration.

## Scope and root-cause correction

The per-application Bypass switch did not suppress the Bypass bundle's global
splash rule. An early-return fix would have also stopped unrelated GKD
subscriptions for that application, which is not the intended product
behavior.

`A11yState` now caches Bypass `AppConfig` values and exposes
`isBypassAppEnabled(appId)`. `A11yRuleEngine` skips only rules belonging to
`BYPASS_SPLASH_SUBS_ID` when that app is disabled. The same restriction applies
when scheduling Bypass match-delay work. Non-Bypass subscriptions are still
eligible to run for a Bypass-disabled app.

## Physical-device evidence

Device: Xiaomi 2509FPN0BC, Android 16, serial `479901bd`.

The tested package was `app.bypassads.debug`; its accessibility component was
enabled throughout the final run. The deterministic test package was
`app.bypassads.testad`.

| Case | Result | Evidence |
| --- | --- | --- |
| A exact rule | BLOCKED | HyperOS rejected installation of the exact-rule APK with `INSTALL_FAILED_USER_RESTRICTED`; this case is not marked PASS. |
| B content-description skip | PASS | `T0->T1=9ms`, `T1->T3=2ms`. |
| C view-id skip | PASS | `T0->T1=10ms`, `T1->T3=2ms`. |
| D clickable ancestor | PASS | Samples: `7ms / 0ms` and `14ms / 1ms` for `T0->T1 / T1->T3`. |
| E safe gesture fallback | PASS | Valid action samples had `T1->T3 <= 1ms`. |
| F `NEXT` | PASS | No false action. |
| G `跳过片头` | PASS | No false action. |
| H master switch OFF | PASS | No action and no `BypassPerfTrace`; master switch was restored ON. |
| I app switch OFF | PASS | Application switch read as `checked=false`; scene I remained on the skip control and produced no `BypassPerfTrace`. The switch was restored ON. |
| J generic splash protection OFF | PASS | Policy switch read as `checked=false`; scene J remained visible and produced no `BypassPerfTrace`. The switch was restored ON. |

For every traceable no-delay action above, `T1->T3` is below the required
300ms threshold. `T0->T1` is recorded separately so application rendering time
is not attributed to Bypass.

Final device-state check: master switch ON; SPLASH, IN_APP_FULLSCREEN, and
MARKETING_POPUP enabled; OTHER_CLOSABLE disabled; accessibility service bound.

## Cold-application observation

Earlier in this device run, JD (`com.jingdong.app.mall`) reached
`MainFrameActivity` and emitted a real-app action for `全屏广告-弹窗广告`:
`T0->T1=50ms`, `T1->T3=7ms`.

WeChat (`com.tencent.mm`) and Douyin (`com.ss.android.ugc.aweme`) exposed no
ad sample during the observation window. They are recorded as `NO_SAMPLE`, not
as PASS.

## Rules, builds, and package audit

`tools/build_selfuse.ps1 -SubscriptionPath tools/.cache/lin-arm-gkd.json5`
completed its bundle generation and validation:

| Item | Value |
| --- | --- |
| Source-global groups retained | `1 / 1` |
| Source-global reinforcements | `2` |
| Generic fallback | not used |
| Output applications / groups / rules | `745 / 1318 / 3211` |
| Categories | `SPLASH=173`, `IN_APP_FULLSCREEN=574`, `MARKETING_POPUP=94`, `OTHER_CLOSABLE=476` |
| Bundle size | `1,405,691` bytes |
| Bundle SHA-256 | `e073d6a607fa60cf13313b29a28e4b820145a2f9a9c43b2fed9959223c71f3ca` |

The policy test (`python -B tools/test_splash_policy.py`) passed. Both
`:app:assembleGkdDebug` (via the self-use build) and
`:app:assembleFulltoolsDebug` completed successfully. `git diff --check` also
passed.

| APK | Package | Size | SHA-256 | INTERNET |
| --- | --- | ---: | --- | --- |
| `app-gkd-debug.apk` | `app.bypassads.debug` | `28,506,097` bytes | `3cd71637ba7850b9aa4c88b8c8b3bdfe9ed9a8f0c37e2fae8ecf5b06a646a75d` | absent |
| `app-fulltools-debug.apk` | `app.bypassads.fulltools.debug` | `28,506,209` bytes | `8ad5daad18d6d6f4ea1fccefca2d6c649ba171b2f954b096c90d61896603c87b7` | present |

`aapt` confirmed the default APK contains
`assets/bypass_splash_rules.local.json` and declares
`application-icon=ic_launcher` in all density buckets. This confirms the
restored launcher icon is packaged without redesigning it.

## Audit boundary

The exact-rule device case remains blocked by device installation policy, and
the two real applications without an exposed ad remain `NO_SAMPLE`. Those gaps
are intentionally retained in this audit; they prevent this document from
claiming an overall G5-R4 PASS.
