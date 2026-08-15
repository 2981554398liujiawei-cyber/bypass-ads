# M1.4 Real-device Shadow Gate

Status: **NOT READY FOR V1.0**. This document records real evidence only. An
item marked `NOT VERIFIED` has not been executed and is not a pass.

## Build baseline

| Field | Value |
| --- | --- |
| Tested commit | `03231a102e7952e948676b18439e7188a5d496df` |
| Branch | `codex/m1-4-real-device-gate` |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| APK SHA-256 | `7EFC3D81E3A0C895127A3DB8BCEBE724F38B3B6AA7F91F679902892D395AD30B` |
| Application ID | `app.bypassads.debug` |
| minSdk / targetSdk | 35 / 37 |
| Clean build, core and App unit tests | VERIFIED |
| Root tests and debug APK build | VERIFIED |
| Production source forbidden-API scan | VERIFIED: zero matches |
| Gesture / screenshot configuration | VERIFIED: both false |
| INTERNET permission | VERIFIED: absent |

The verified commands were:

```bat
gradlew.bat clean :core:test :app:testDebugUnitTest :app:assembleDebug --no-daemon
gradlew.bat test :app:assembleDebug --no-daemon
adb devices -l
```

`CaseTraceValidator` has unit coverage for blank case IDs, session changes,
single terminal records, terminal ordering, termination reasons, complete and
non-negative frame accounting, balanced accounting, scan-index range, and
duplicate scan indexes. Its use as a release-evidence gate requires real JSONL
records to be adapted into its input model.

## Device matrix

| Device | Manufacturer / model | Android / API | Build fingerprint | Result |
| --- | --- | --- | --- | --- |
| None attached at baseline | NOT VERIFIED | NOT VERIFIED | NOT VERIFIED | `adb devices -l` reported no devices |

## Required real-device evidence

| Gate | Required result | Current evidence |
| --- | --- | --- |
| Device matrix | At least one Android 15+ device; two OEMs strongly preferred | NOT VERIFIED |
| Controlled scanner run | At least 15 minutes and 300 records per tested device | NOT VERIFIED |
| Trace integrity | Zero validator violations and malformed JSONL lines | NOT VERIFIED |
| I/O integrity | `writeFailures = 0`; queues return to idle | NOT VERIFIED |
| Worker-thread behavior | Report timings and explain every Accessibility phase at or above 1 second | NOT VERIFIED |
| OFF to SHADOW lifecycle | Five successful cycles; no new case or SCAN for 30 seconds after OFF | NOT VERIFIED |
| Representative soak | At least 60 continuous minutes | NOT VERIFIED |
| Coverage | At least 10 ordinary apps, 3 WeChat mini-programs, and 3 Alipay mini-programs | NOT VERIFIED |
| Stability | Zero crashes and ANRs; AccessibilityService remains functional | NOT VERIFIED |

Mini-program identity must be recorded from the tester-visible context. Package
name alone is not sufficient evidence. Do not commit raw diagnostics: publish
only privacy-minimized aggregate metrics and the individual timing records
needed to explain any slow Accessibility operation.

## V1.0 boundary

This Shadow-only build is not a formally usable V1.0 automatic ad skipper.
Static safety and APK build evidence do not prove OEM behavior, lifecycle
correctness, representative coverage, or long-run reliability. An automatic
skipping V1.0 would additionally require a separately audited actuation design,
false-positive evidence, outcome verification, and rollback policy.
