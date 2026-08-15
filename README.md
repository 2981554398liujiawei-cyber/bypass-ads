# Bypass Ads.

A clean-room, fully offline Android 15+ tool for observing and diagnosing safer splash-ad skip decisions.

> Status: **Shadow-only 1.0.0-rc1 Release Candidate — formal RC gate PASS**. This is an OFF/Shadow-only observation and diagnostics tool, not an automatic ad skipper. Known Xiaomi/HyperOS compatibility note: the OEM power engine may transiently unbind the accessibility service while the app idles in background; post-idle app launches are still observed (verified 5/5), and the service rebinds on return to foreground.

## V1.0 RC scope (release candidate)

Bypass Ads. supports Android 15+ devices and runs entirely on-device. In
`Shadow`, it observes accessibility windows, evaluates candidates, and records
privacy-minimized local diagnostics. In `Off`, it does not scan. It never
clicks nodes, invokes gestures, opens global actions, or claims to skip ads.

See [release candidate](docs/release-candidate.md) for signing, verification,
known limitations, and artifact provenance.

## Product constraints

- Runtime is fully offline. The manifest intentionally does **not** request `android.permission.INTERNET`.
- Android 15+ only (`minSdk 35`); compilation and target SDK use API 37.
- Release application ID: `app.bypassads`; debug builds use `app.bypassads.debug`, so either can coexist with legacy LiTiaoTiao (`hello.litiaotiao.app`).
- False positives are more costly than missed ads.
- Black-box diagnostics are local, bounded, structured, and privacy-minimized.
- Source is organized for an eventual open-source release under MIT.

## Included behavior

1. Accessibility window/node capture with a 0 / 60 / 140 / 300 / 600 / 1000 ms burst after package/window transitions. Content-change bursts are coalesced per package for 250 ms.
2. Conservative candidate detector, temporal tracker, scorer, and pure-Kotlin decision engine.
3. Shadow decisions (`WOULD_CLICK`, `TOO_RISKY`, `NO_CANDIDATE`) only — never an action.
4. Local JSONL black box with single-worker asynchronous I/O, 7-day / 20-MB retention, details, stats, and manual clearing.
5. Minimal Compose Home, Diagnostics, and Settings screens.
6. Pure-JVM safety regression tests in `:core`.

## Known limitations

- OCR, screenshots, cloud rules, runtime networking, analytics, crash-reporting SaaS, advertising SDKs.
- Active clicking, gestures, an Active-mode switch, foreground-service persistence hacks, `TYPE_VIEW_TEXT_CHANGED`, or Android 14-and-below compatibility.
- Advertising outcome verification and claims of automatic ad skipping. Shadow decisions are diagnostics, not actions.
- Compatibility claims beyond the release smoke matrix. In particular, mini-program behavior is not a general support promise.

## Build

Requires JDK 17+ and Android SDK Platform 37.

```bash
./gradlew :evidence:test :core:test :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is emitted at `app/build/outputs/apk/debug/app-debug.apk`.
For a signed release candidate, follow the local-only signing procedure in
[release candidate](docs/release-candidate.md); never commit a keystore or its
properties file.

## Verification checklist

1. Confirm `app.bypassads` from the release manifest and no `android.permission.INTERNET` using `apkanalyzer` or `aapt`.
2. Install the signed release (`app.bypassads`) or debug build (`app.bypassads.debug`) on Android 15+ beside `hello.litiaotiao.app`.
3. Enable **Bypass Ads. · 开屏观察** in Accessibility settings.
4. Open ordinary apps and confirm `PACKAGE_CHANGED`, `WINDOW_STATE_CHANGED`, `WINDOWS_CHANGED`, `CONTENT_CHANGED`, and `SCAN` records appear in Diagnostics.
5. Confirm decisions and score/risk breakdowns are recorded, while the device never receives an automated click.

See [architecture](docs/architecture.md), [diagnostics](docs/diagnostics.md), and [privacy](docs/privacy.md).
