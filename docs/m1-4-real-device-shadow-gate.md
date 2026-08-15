# M1.4 Real-device Shadow Gate

Status: **M1.4 Shadow real-device gate passed**. This document records
privacy-minimized real evidence only. This gate alone did not authorize a
release; the separate V1.0 RC remains Shadow-only and does not authorize
automatic actuation.

## Build baseline

| Field | Value |
| --- | --- |
| Tested commit | `03231a102e7952e948676b18439e7188a5d496df` |
| Branch | `codex/m1-4-real-device-gate` |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| APK SHA-256 | `AA4C481529CA6645AEB6EE56A95F0770023A1B84F1B0E801947E5BBD3B4E3508` |
| Application ID | `app.bypassads.debug` |
| minSdk / targetSdk | 35 / 37 |
| Evidence, core and App unit tests; debug APK build | VERIFIED |
| Production source forbidden-API scan | VERIFIED: zero matches |
| Gesture / screenshot configuration | VERIFIED: both false |
| INTERNET permission | VERIFIED: absent |

The verified commands were:

```bat
gradlew.bat :evidence:test :core:test :app:testDebugUnitTest :app:assembleDebug --no-daemon
adb devices -l
```

`CaseTraceValidator` has unit coverage for blank case IDs, session changes,
single terminal records, terminal ordering, termination reasons, complete and
non-negative frame accounting, balanced accounting, scan-index range, and
duplicate scan indexes. Its use as a release-evidence gate requires real JSONL
records to be adapted into its input model.

## Offline trace validation

The `:evidence` JVM runner adapts local JSONL to the existing
`CaseTraceValidator`. It emits count-only JSON and exits non-zero for malformed
JSONL, unadaptable case records, or validator violations. It is intentionally
offline and reads no network or device state:

```bat
gradlew.bat :evidence:run --args="--input C:\private\m1-4.jsonl --from-epoch-ms <collection-start-ms>"
```

The input is a local, temporary copy of the device-private diagnostics file.
Do not commit it. Only the count-only output belongs in this document.

For the five OFF-to-SHADOW cycles, final I/O health, coverage, and stability,
create a separate **local-only** JSON sidecar and pass it with
`--session-sidecar C:\private\m1-4-session.json`. The runner never emits the
mini-program names in its output; it emits only their counts and uniqueness
checks. Do not commit the sidecar.

```json
{
  "collectionStartEpochMs": 1786810506522,
  "collectionEndEpochMs": 1786814999311,
  "controlledRun": {
    "startEpochMs": 1786810506522,
    "endEpochMs": 1786811406522
  },
  "continuousCollectionConfirmed": true,
  "serviceBoundAtEnd": true,
  "offCycles": [
    { "offAtEpochMs": 1786811000000, "shadowRestoredAtEpochMs": 1786811030000 }
  ],
  "finalIoHealth": {
    "pendingIoJobs": 0,
    "maxPendingIoJobs": 0,
    "recordsWritten": 0,
    "writeFailures": 0
  },
  "coverage": {
    "ordinaryAppCount": 0,
    "wechatMiniProgramNames": [],
    "alipayMiniProgramNames": []
  },
  "stability": { "crashCount": 0, "anrCount": 0 },
  "slowReview": {
    "unexplainedSlowAccessibilityCount": 0,
    "repeatableMultiSecondBlockingCount": 0
  }
}
```

The runner computes the controlled-window record count from JSONL rather than a
sidecar claim. The controlled window must be within the collection and last at
least 15 minutes with at least 300 records. The collection itself must last at
least 60 minutes, have `continuousCollectionConfirmed=true`, and end with
`serviceBoundAtEnd=true`. Every OFF interval must be inside the collection,
strictly ordered and non-overlapping, start at one unique real `CASE_END` with
`terminationReason=MODE_OFF`, last at least 30 seconds, contain no `SCAN`, and
have a later `SCAN` before the next OFF. Non-scan window/package/content events
may occur while OFF and do not invalidate the lifecycle. `workerMaxQueueDepth`
refers only to the scan worker; final I/O health comes from the Diagnostics UI.
Metric fields missing from a `SCAN` are reported as missing rather than zero.

`passed` means trace integrity passed and, when provided, the local sidecar
also passed. `releaseEvidenceReady` additionally requires complete scan
metrics plus all independent M1.4 gates: controlled run, soak continuity and
end binding, lifecycle, coverage, I/O, stability, and slow-operation review.
Neither result permits raw JSONL or the local sidecar to be committed.

## Device matrix

| Device | Manufacturer / model | Android / API | Build fingerprint | Result |
| --- | --- | --- | --- | --- |
| Xiaomi 2509FPN0BC | Xiaomi / 2509FPN0BC | Android 16 / API 36 | `Xiaomi/popsicle/popsicle:16/BP2A.250605.031.A3/OS3.0.315.0.WPBCNXM:user/release-keys` | VERIFIED connected physical device; accessibility service is enabled and system-bound (`hasBound=true`) |

## Final aggregate real-device evidence

The formal trace cutoff was `1786810506522`. The local runner processed 27,568
input lines: 18,471 before the cutoff were ignored and 9,097 post-cutoff
records were validated. The JSONL post-cutoff record span was 4,492,485 ms
(approximately 74 minutes 52 seconds); the independently declared collection
window was 4,492,789 ms. The trace contained 1,791 distinct cases, 5,516
`SCAN` records, 1,790 `CASE_END` records, and 2,040 records in the controlled
15-minute window.

`traceIntegrityPassed=true`, `scanMetricsComplete=true`,
`releaseEvidenceReady=true`, and `validatorViolationCount=0`. The runner
reported `openTailCaseCount=1`: exactly one final post-cutoff record was a
non-`SCAN`, non-`CASE_END` entry whose case ID occurred only once. The adapter
excludes only this proven local-copy boundary record before applying the
unchanged `CaseTraceValidator`; an open case anywhere else, a scanned tail, or
a multi-record tail remains a validator failure.

Five distinct real `CASE_END` records with `terminationReason=MODE_OFF`
anchored the OFF-to-SHADOW cycles. Their OFF durations were 48.976, 41.742,
66.857, 53.780, and 76.312 seconds. Each had zero `SCAN` records while OFF and
a recovery `SCAN` before the next OFF. There were six `MODE_OFF` records in
total; no anchor was reused.

All scan metrics were complete. `scanWork` p50/p95/max was 6/57/149 ms;
`windowAcquire` 0/1/5 ms; `snapshot` 5/55/148 ms; `detection` 1/1/8 ms;
`decision` 0/0/1 ms; `rootAcquire` 2/3/124 ms; and `childQuery` 1/2/29 ms.
Every phase's count at or above one second was zero. Capture-budget outcomes
were 139 true and 5,377 false. Scan-worker queue depth p50/p95/max was 1/1/1;
busy drops changed from 31 to 273, with 94 increases and zero resets.

Final Diagnostics UI values were pending I/O jobs 0, maximum pending I/O jobs
188, records written 10,646, and write failures 0. Coverage confirmed 10
ordinary apps and three tester-visible WeChat plus three tester-visible Alipay
mini-programs, with unique names held only in the local sidecar. There were no
post-cutoff Bypass Ads crashes or ANRs, and Android still reported the
Accessibility service as bound at collection end.

## Required real-device evidence

| Gate | Required result | Current evidence |
| --- | --- | --- |
| Device matrix | At least one Android 15+ device; two OEMs strongly preferred | VERIFIED for one Xiaomi Android 16 physical device; second OEM NOT VERIFIED |
| Controlled scanner run | At least 15 minutes and 300 records per tested device | VERIFIED: 2,040 records in 15 minutes |
| Trace integrity | Zero validator violations and malformed JSONL lines | VERIFIED: zero violations; no malformed or unadaptable records; one explicitly bounded open-tail adapter exclusion |
| I/O integrity | `writeFailures = 0`; queues return to idle | VERIFIED: failures 0; final pending jobs 0 |
| Worker-thread behavior | Report timings and explain every Accessibility phase at or above 1 second | VERIFIED: all phase counts at or above one second were zero |
| OFF to SHADOW lifecycle | Five successful `MODE_OFF`-anchored cycles; no SCAN for 30 seconds after OFF | VERIFIED: five distinct anchors, 41.742--76.312 seconds, zero SCAN while OFF, recovery SCAN each time |
| Representative soak | At least 60 continuous minutes | VERIFIED: continuous 74 minutes 52 seconds; service bound at end |
| Coverage | At least 10 ordinary apps, 3 WeChat mini-programs, and 3 Alipay mini-programs | VERIFIED: 10 ordinary apps; 3 plus 3 unique tester-visible mini-programs |
| Stability | Zero crashes and ANRs; AccessibilityService remains functional | VERIFIED: zero post-cutoff crashes and ANRs; service bound at end |

Mini-program identity must be recorded from the tester-visible context. Package
name alone is not sufficient evidence. Do not commit raw diagnostics: publish
only privacy-minimized aggregate metrics, including counts that confirm slow
Accessibility operations were reviewed.

## V1.0 boundary

This Shadow-only build is not a formally usable V1.0 automatic ad skipper.
Static safety and APK build evidence do not prove OEM behavior, lifecycle
correctness, representative coverage, or long-run reliability. An automatic
skipping V1.0 would additionally require a separately audited actuation design,
false-positive evidence, outcome verification, and rollback policy.
