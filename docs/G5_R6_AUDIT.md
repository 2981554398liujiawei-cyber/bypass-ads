# G5-R6 Process Audit

This is a process record for G5-R6. It is not a G5 PASS declaration.

## Baseline

- Branch: `codex/gkd-migration`
- Starting remote commit: `606182d`
- Device: Redmi `2509FPN0BC` (`479901bd`), Android 16 / HyperOS
- Evidence date: 2026-08-20 (Asia/Shanghai)
- Existing R5 worktree changes were preserved and extended in place.

## Implementation Audit

| Check | Result | Evidence |
| --- | --- | --- |
| Root information architecture | PASS (source) | `HOME`, `ADS`, `RECORDS`, and `SETTINGS` are `BypassRootTab` states. The Apps root tab is absent. |
| Root-stack separation | PASS (source) | `MainViewModel.selectBypassRootTab` clears secondary details and changes only root-tab state. It does not push previous tabs to `backStack`. |
| State preservation | PASS (source) | Each root content is wrapped by `SaveableStateHolder.SaveableStateProvider(rootTab)` and lists use cached data/stable item keys. |
| Transition behavior | PASS (source) | `NavDisplay` has no page transition spec. Root selection changes state only; `TAB_SWITCH_*` and `DETAIL_NAV_*` traces are emitted after a Compose frame. |
| Application-control ownership | PASS (source) | The product entry is Ads -> Application control. It is not repeated in Rules & subscriptions. |
| Rule/subscription ownership | PASS (source) | Import, restore, subscription management, and advanced rule management are grouped under Ads -> Rules & subscriptions. |
| Records root | PASS (source) | Success/failure filters, summary metrics, failure details, retry, teaching, diagnostics, and a single clear action exist in the Records flow. |
| Accessibility event ownership | PASS (source) | `BypassSwitchRow` has one row-level action and `Switch(onCheckedChange = null)`, preventing a row/switch double toggle. |
| Accessibility control path | PASS (source + device) | Home calls `GkdBypassEngine.setAccessibilityEnabled`, which reuses `setA11yServiceEnabled`; Shizuku and ADB/system-settings fallbacks retain the requested enable action. |
| Lifecycle intent | PASS (source) | `A11yService.onDestroyed` preserves persisted intent when the service remains authorized, and clears it only after genuine deauthorization. |

## Device Evidence

### Root Tabs

- Root tabs tested: Home, Ads, Records, Settings.
- Formal pair switching: 160 switches (20 loops for each required pair), plus 4 positioning/restoration switches.
- Trace: `TAB_SWITCH_START=164`, `TAB_SWITCH_FIRST_FRAME=164`.
- First-frame latency: min 24 ms, average 39 ms, max 95 ms; 0 samples over 300 ms.
- No app crash was observed.

The trace proves frame delivery and timing. It cannot prove the task card's
"0 visible flickers" condition on its own; that still requires a human visual
observation pass and is not represented as passed here.

### Detail Navigation and System Settings

- Ads -> Application control -> Bypass Ads Test detail -> Back: 25 loops.
- Trace: `DETAIL_NAV_START=50`, `DETAIL_NAV_FIRST_FRAME=50`.
- First-frame latency: min 8 ms, average 12.2 ms, max 17 ms; 0 samples over 300 ms.
- Final route was Application control; no fatal exception was observed.
- Background protection opened the HyperOS battery-optimization setting in
  `com.miui.securitycenter`; Android Back returned to the same Background
  protection detail page rather than resetting to Home.

### Accessibility Enhanced Control

- `WRITE_SECURE_SETTINGS` is granted to `app.bypassads.debug`.
- `AccessibilityControlInstrumentedTest` ran on the connected Redmi and
  passed. It used the same product control path to enable the service, waited
  for the startup critical section to settle, disabled it, and restored the
  pre-test state.
- `ExampleInstrumentedTest` also passed with the `app.bypassads.debug`
  package assertion.
- The Home row has only one source of toggle events by source inspection.

Compose UI instrumentation is **not passed**. Even the minimal
`BypassUiTest#switchRow_has_one_event_source` exceeded 60 seconds during
startup on this HyperOS device; the runner ended with `shortMsg=Process
crashed` after the target was stopped. No target exception stack was found.
Logs showed large-scale decoding of HyperOS-customized app icons during
startup, making Compose test idleness unavailable. The device test above is
backend/control-path evidence, not a replacement claim for pointer-driven
Compose UI coverage.

`uiautomator dump` also repeatedly disturbed/rebound the accessibility
service on this HyperOS build. It was therefore not used as a status oracle
for the final accessibility result.

## Build and Policy Evidence

| Check | Result | Evidence |
| --- | --- | --- |
| Self-use rule bundle | PASS | `tools/build_selfuse.ps1 -SubscriptionPath tools/.cache/lin-arm-gkd.json5` generated 745 apps, 1318 groups, and 3211 rules; validator passed. |
| Bundle digest | PASS | SHA-256 `558e634ca1de9985895a07f0c82526615b99e6a47ff8a2a7cf7a80bb2afb1249`. |
| GKD debug APK | PASS | `:app:assembleGkdDebug` completed as part of the self-use build. |
| Android test APK | PASS | `:app:assembleGkdDebugAndroidTest` completed. |
| Full Tools APK | PASS | `:app:assembleFulltoolsDebug --no-daemon` completed. |
| Splash policy regression | PASS | `python tools/test_splash_policy.py` printed `splash policy: PASS`. |
| Default network boundary | PASS | Merged `gkdDebug` manifest has no `android.permission.INTERNET`. |
| Full Tools network boundary | PASS | Merged `fulltoolsDebug` manifest includes `android.permission.INTERNET`. |
| Whitespace validation | PASS | `git diff --check` completed without errors. |

## Incomplete Verification

The following task-card checks are deliberately not called passed:

| Check | Status | Reason |
| --- | --- | --- |
| Human visual flicker count | NOT VERIFIED | Trace timing is strong, but no independent human observation log was captured. |
| Compose interaction suite | NOT PASSED | HyperOS startup/idleness blocker described above. |
| Shizuku authorization flow | NOT VERIFIED | No granted Shizuku session was available on the device. |
| Five reboot recoveries | NOT RUN | Requires five controlled reboots and unlocks. |
| Twenty normal process recoveries | NOT RUN | Not executed as a recorded loop. |
| One hundred app switches | NOT RUN | Not executed as a recorded loop. |
| Ten screen-off/on cycles | NOT RUN | Not executed as a recorded loop. |
| Five real failed-app/mini-program samples | NOT RUN | No user-supplied sample inventory was available during this run. |

Force-stop remains an Android platform boundary: an explicitly force-stopped
application cannot restart itself. No wake-lock loop, periodic alarm loop,
dual-process restart loop, OCR, image matching, continuous screenshots, or
independent accessibility-tree scanner was added.

## Result

R6 implementation, build, rule policy, and the recorded navigation/control
device checks are complete. The unverified items above remain open and this
document does not assert overall G5 completion.
