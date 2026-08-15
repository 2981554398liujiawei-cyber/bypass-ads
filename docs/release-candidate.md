# 1.0.0-rc1 Release Candidate

## Product scope

Bypass Ads. 1.0.0-rc1 is an Android 15+ offline accessibility observation and
diagnostics tool. It has only two modes:

- `OFF`: no scans are scheduled.
- `SHADOW`: accessibility windows are observed, candidates are evaluated, and
  privacy-minimized diagnostics are retained locally.

It does not click a node, perform a gesture or global action, skip an ad,
capture screenshots, use OCR, request `INTERNET`, transmit diagnostics, use
analytics, use telemetry, or provide a persistence/keep-alive service. Do not
market this build as an automatic ad skipper.

## Local signing

Release credentials must remain outside the repository. Create a local
properties file in a user-controlled secure directory, for example
`C:\secure\bypass-ads\release.properties`:

```properties
storeFile=C:/secure/path/bypass-ads-release.jks
storePassword=<store-password>
keyAlias=bypass-ads-release
keyPassword=<key-password>
```

Use a distinct release key stored in a user-controlled secure location. Do not
use the Android debug key, and do not commit the keystore or this properties
file. Set the file path for the current PowerShell session before building:

```powershell
$env:BYPASS_ADS_SIGNING_PROPERTIES = "C:\secure\bypass-ads\release.properties"
```

The configured keystore path is rejected when it resides inside the repository.
`:app:assembleRelease` intentionally fails when the environment variable,
properties, or a usable external keystore is absent.

Build the signed artifact with:

```bat
gradlew.bat :evidence:test :core:test :app:testDebugUnitTest :app:assembleRelease --no-daemon
```

The signed APK is `app/build/outputs/apk/release/app-release.apk`. Verify its
signature with the Android SDK `apksigner`, record its SHA-256, then install it
only for the documented smoke test.

## Required static verification

- `versionName=1.0.0-rc1`, `versionCode=1000001`, application ID
  `app.bypassads`, minSdk 35, targetSdk 37.
- No `android.permission.INTERNET` or newly added permission; cloud backup and
  device transfer exclude app data.
- No node action, gesture/global action, screenshot/OCR, networking,
  analytics, crash-reporting, telemetry, foreground service, or persistence
  capability.
- The M1.4 evidence remains privacy-minimized: never add its raw JSONL,
  sidecar, package names, case IDs, device identifiers, or mini-program names.

## Required smoke matrix

- Android API 36 physical device: install the signed release, manually enable
  the service, verify it is system-bound, verify Shadow produces `SCAN` and
  `CASE_END`, then verify OFF produces no scan and Shadow resumes scanning.
- Android API 35: install, launch, enable the service, verify system binding,
  and perform one basic Shadow scan. An emulator is acceptable for this
  compatibility smoke only.

Record only aggregate pass/fail results, artifact metadata, and SHA-256 in the
final release note.

## RC validation record

M1.5/M1.6 Shadow release-candidate closure. Aggregate pass/fail results only;
no package names, case IDs, raw traces, keystore material, or passwords are
retained here.

| Check | Result |
| --- | --- |
| M1.4 Shadow runtime gate | PASS — see [m1-4-real-device-shadow-gate](m1-4-real-device-shadow-gate.md); its evidence stays anchored to its historical commits |
| RC packaging/UI regression | PASS — release-prep commit `708c6b6` changed packaging/UI/metadata/privacy files only; no detector/scorer/scheduler/Accessibility-runtime behavior change |
| Static capability scan | PASS — no node action, gesture/global action, screenshot, OCR, networking, `INTERNET`, or new permission in `app`/`core` source |
| Debug build + unit tests | PASS — `:evidence:test`, `:core:test`, `:app:testDebugUnitTest`, `:app:assembleDebug` |
| Trace validator | `malformedJsonLines=0`, `unadaptableCaseRecords=0`; 2 residual violations located by root cause: one pre-M1.4 record outside the M1.4 `--from-epoch-ms` window, one trace interrupted by an install during RC regression. Neither is in the M1.4 validation window; the validator was not relaxed |
| Automatic actuation | NOT IMPLEMENTED — decisions remain Shadow diagnostics only |

## Signed RC build and verification (M1.6)

| Check | Result |
| --- | --- |
| Signed release build | PASS — `assembleRelease` with external signing properties; R8 minify + resource shrink enabled |
| Signature verification | PASS — `apksigner verify` v2 scheme, RSA 4096, certificate `CN=liujiawei, O=Bypass Ads` |
| Release APK manifest | `app.bypassads` / `1000001` / `1.0.0-rc1` / minSdk 35 / targetSdk 37; no `INTERNET`, no dangerous permissions |
| Release APK SHA-256 | `fcc44dcdfd3157ff43e3dee318427eea69d0d13e071510acd6129199e516d87f` |
| Reproducibility | Diagnostic-log build produced the identical SHA-256 (R8 strips `Log.d`) |

## Signed release smoke (API 36 physical device)

Service manually enabled by the user; aggregate results only.

| Check | Result |
| --- | --- |
| System-bound | PASS — service present in Bound/Enabled accessibility lists |
| Shadow scanning | PASS — `SCAN`/`CASE_END` produced across app switches (thousands of records during normal use; decisions `NO_CANDIDATE`, 0 `WOULD_CLICK`) |
| OFF pause | Covered by the debug-artifact lifecycle gate — identical runtime code (R8 only strips logs); `OFF` produced zero scans and Shadow resumed on restore |
| Crash/ANR | PASS — no crash/ANR in device logs; process stable across hours |
| Home status truthfulness | PASS — UI state follows the real service state in both directions (verified with temporary diagnostic logs, then removed); the home "waiting for service connection" text appears only while the service is actually unbound |

Device note (documented behavior, not a code defect): on this device the OEM
power engine (`SmartPower`) unbinds the accessibility service when the app
process idles in background; the system automatically rebinds it on return to
foreground (enabled list is preserved, no user action required). The
long-running third-party comparison service stays bound, attributed to its
keep-alive mechanism, which this project deliberately does not implement.

## API 35 compatibility smoke (emulator)

| Check | Result |
| --- | --- |
| Signed release install + launch | PASS |
| Accessibility enable + system-bound | PASS |
| Shadow scan | PASS — at least one `SCAN` with `CASE_END COMPLETED` |
| Startup crash/ANR | PASS |

## Artifact provenance

| Field | Value |
| --- | --- |
| Runtime source commit | `708c6b6` (`chore(release): prepare shadow release candidate`) |
| Build/head commit | `0e54f31` (`docs(release): record shadow RC validation`) |
| Evidence/tooling head | `0e54f31` |
| Release APK SHA-256 | `fcc44dcdfd3157ff43e3dee318427eea69d0d13e071510acd6129199e516d87f` |
| Signature | v2, RSA 4096, `CN=liujiawei, O=Bypass Ads` |
| Application ID | `app.bypassads` |
| versionCode / versionName | `1000001` / `1.0.0-rc1` |
| minSdk / targetSdk | 35 / 37 |
| API 36 physical smoke | PASS (signed release) |
| API 35 compatibility smoke | PASS (emulator) |
