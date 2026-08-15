# M1.2 Shadow soak report (preliminary)

Date: 2026-08-15

## Scope and evidence

This is an on-device Shadow-only sample from Xiaomi 2509FPN0BC (Android API 36).
The debug package `app.bypassads.debug` was installed and its accessibility
service was enabled. `dumpsys accessibility` confirmed the intended event
subscriptions: window-state, windows-changed, and content-changed. The service
has no gesture or screenshot capability.

The evidence was read from the app-private JSONL BlackBox storage with
`adb exec-out run-as`; no diagnostic records were exported from the device or
added to this repository.

## Observed sample

| Metric | Result |
| --- | ---: |
| Observation window | 4.63 minutes |
| JSONL records | 839 |
| Malformed JSONL records | 0 |
| Distinct case IDs | 252 |
| Distinct package names | 16 |
| Scan records | 585 |
| `NO_CANDIDATE` | 569 |
| `TOO_RISKY` | 16 |
| `WOULD_CLICK` | 0 |
| `scanWorkMs` p50 / p95 / max | 6 / 60 / 40,427 ms |
| Scans above 50 ms | 55 |
| Scans at or above 1 s | 2 |

The host packages represented include Zhihu, Meituan, Taobao, WeChat, Alipay,
QQ Music, JD Reader, Bilibili, Settings, and system UI. WeChat and Alipay each
have one host-process case, but neither sample establishes a mini-program
context.

## Safety observations

- All observed decisions were `NO_CANDIDATE` or `TOO_RISKY`; no action was
  performed. Shadow mode does not click, dispatch gestures, or invoke global
  actions.
- Trigger and scan records in a burst shared the same random `caseId`.
- Candidate ambiguity was observed in three records and persisted as a feature.
- Records contain bounded candidate features and decisions, not complete
  accessibility trees or screenshots.

## Performance follow-up

Two scans on `com.miui.home` recorded 13,718 ms and 40,427 ms of scan work,
despite only 531 and 53 nodes respectively. These are outliers rather than a
normal baseline and require investigation before any performance claim. System
UI and the launcher also account for most observed traffic, so the sample is
not representative of user applications.

## M1.2 task-card status

| Requirement | Status |
| --- | --- |
| Android 15+ device and enabled service | VERIFIED |
| Shadow diagnostic writes and JSONL validity | VERIFIED (preliminary sample) |
| At least 100 sessions | VERIFIED (252 case IDs) |
| At least 10 normal apps with meaningful sessions | NOT VERIFIED |
| Three WeChat mini-programs | NOT VERIFIED |
| Three Alipay mini-programs | NOT VERIFIED |
| At least 60 minutes continuous operation | NOT VERIFIED |
| OFF transition stops new scan records for at least 30 seconds | NOT VERIFIED |
| Restore Shadow and observe a fresh burst | NOT VERIFIED |
| Stable scan performance | NOT VERIFIED (two severe outliers) |

## Next collection pass

Run a controlled 60-minute Shadow soak with representative normal apps. For
WeChat and Alipay, enter three distinct mini-programs in each host and record
their context as user-observed test cases; do not infer mini-program identity
from package name alone. Perform the OFF-to-SHADOW lifecycle check from the
app UI, then compare record counts before and after each transition.
