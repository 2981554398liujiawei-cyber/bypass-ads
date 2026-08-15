# Bypass Ads.

A clean-room, fully offline Android 15+ Shadow-mode tool for observing safer splash-ad skip decisions.

> Status: **M1 development**. The app records candidate decisions locally but performs no node click, gesture, OCR, screenshot capture, or network request.

## Product constraints

- Runtime is fully offline. The manifest intentionally does **not** request `android.permission.INTERNET`.
- Android 15+ only (`minSdk 35`); compilation and target SDK use API 37.
- Release application ID: `app.bypassads`; debug builds use `app.bypassads.debug`, so either can coexist with legacy LiTiaoTiao (`hello.litiaotiao.app`).
- False positives are more costly than missed ads.
- Black-box diagnostics are local, bounded, structured, and privacy-minimized.
- Source is organized for an eventual open-source release under MIT.

## M1 scope

1. Accessibility window/node capture with a 0 / 60 / 140 / 300 / 600 / 1000 ms burst after package/window transitions.
2. Conservative candidate detector, temporal tracker, scorer, and pure-Kotlin decision engine.
3. Shadow decisions (`WOULD_CLICK`, `TOO_RISKY`, `NO_CANDIDATE`) only — never an action.
4. Local JSONL black box with 7-day / 20-MB retention, details, stats, and manual clearing.
5. Minimal Compose Home, Diagnostics, and Settings screens.
6. Pure-JVM safety regression tests in `:core`.

## Explicitly out of scope

- OCR, screenshots, cloud rules, runtime networking, analytics, crash-reporting SaaS, advertising SDKs.
- Active clicking, gestures, an Active-mode switch, foreground-service persistence hacks, or Android 14-and-below compatibility.

## Build

Requires JDK 17+ and Android SDK Platform 37.

```bash
./gradlew clean test assembleDebug
```

The debug APK is emitted at `app/build/outputs/apk/debug/app-debug.apk`.

## Verification checklist

1. Confirm `app.bypassads` from the release manifest and no `android.permission.INTERNET` using `apkanalyzer` or `aapt`.
2. Install on Android 15+ beside `hello.litiaotiao.app`.
3. Enable **Bypass Ads. · 开屏观察** in Accessibility settings.
4. Open ordinary apps and confirm `PACKAGE_CHANGED`, `WINDOW_CHANGED`, and `SCAN` records appear in Diagnostics.
5. Confirm decisions and score/risk breakdowns are recorded, while the device never receives an automated click.

See [architecture](docs/architecture.md), [diagnostics](docs/diagnostics.md), and [privacy](docs/privacy.md).
