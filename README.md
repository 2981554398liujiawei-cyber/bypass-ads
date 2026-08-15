# LTT Next

A clean-room, offline Android 15+ experiment for safer splash-ad skipping.

> Status: **v0.1 development scaffold**. Shadow mode only; no real clicks are executed yet.

## Product constraints

- Runtime is fully offline. The manifest intentionally does **not** request `android.permission.INTERNET`.
- Android 15+ only (`minSdk 35`).
- Compiles/targets API 37.
- Independent application ID: `app.lttnext` (debug builds use `app.lttnext.debug`), so it can coexist with legacy LiTiaoTiao.
- False positives are treated as more costly than missed ads.
- Black-box diagnostics are local, bounded, and privacy-minimized.
- Open source under MIT (provisional until first public release).

## v0.1 scope

1. Accessibility window/node capture.
2. Burst scans after window transitions: 0 / 60 / 140 / 300 / 600 / 1000 ms.
3. Conservative candidate detector + scorer.
4. Shadow decisions (`WOULD_CLICK`, `TOO_RISKY`, `NO_CANDIDATE`).
5. Local JSONL black box, max 7 days / 20 MB.
6. Small Compose status UI.
7. Pure-JVM decision tests in `:core`.

## Not in v0.1

- No OCR or screenshots.
- No cloud rules or auto-update network calls.
- No real click / gesture actuation.
- No foreground-service "keep alive" hacks.

## Build

The project expects JDK 17+, Android SDK Platform 37, AGP 9.3.1 and Gradle 9.5.1.

The generated source tree includes Gradle wrapper configuration, but this environment could not download `gradle-wrapper.jar`. On a development machine with Gradle installed, run:

```bash
gradle wrapper --gradle-version 9.5.1
./gradlew :core:test :app:assembleDebug
```

See `docs/architecture.md` and `docs/privacy.md`.
