# M1.3 Shadow Scanner Correctness and Performance Gate

Baseline: `5201aea` on `codex/m1-2-shadow-concurrency-soak`.

## Implemented

- Each case writes a `CASE_END` record with the case identity, initial trigger, frame accounting, coalesced events, duration, and termination reason. The frame invariant is `executed + cancelled + dropped == scheduled`.
- Same-package window and content events coalesce into the active case. Cross-package events supersede it. Content changes use a 250 ms quiet debounce capped at a 1000 ms absolute wait.
- The main handler schedules immutable requests only. A dedicated single worker fetches windows at scan start; one in-flight request is allowed and extra frames are recorded as dropped. The maximum internal scan queue depth is one.
- Capture preserves the 2000-node and depth-80 limits and adds a cooperative 75 ms deadline. It is checked before windows, roots, nodes, and child queries. It cannot interrupt an Android/OEM query that is already blocking.
- Scan records now include phase timings, traversal/capture limits, worker drops, and queue depth. The current default HOME package is dynamically resolved and excluded.
- BlackBox retention only decrements accounting after a successful deletion and recalculates actual bytes after writes and retention.

## Automated Evidence

- `:core:test` and `:app:testDebugUnitTest`: PASS.
- `test :app:assembleDebug`: PASS.
- APK: `app.bypassads.debug`, minSdk 35, targetSdk 37, no INTERNET permission.
- Source scan: no click, gesture, global action, `FLAG_PREFETCH_UNINTERRUPTIBLE`, or networking APIs.

## Device Evidence

`adb devices -l` reported no connected device at validation time. The required 15-minute, 300-record controlled run and OEM worker-thread behavior are therefore **NOT VERIFIED**. No synthetic performance or lifecycle acceptance figures are claimed.
