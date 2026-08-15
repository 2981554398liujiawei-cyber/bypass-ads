# Changelog

## 1.0.0-rc1 - Release Candidate

> **M1.6 formal RC gate: PASS.** P1-1 post-idle launch availability PASS (5/5:
> after leaving the app and waiting past the OEM idle window, launching other
> apps still produced new Shadow scans), P1-2 OFF/Shadow lifecycle PASS. The
> Xiaomi/HyperOS SmartPower transient unbind is recorded as a documented
> compatibility risk, not a release blocker. Shadow-only — does not skip ads
> automatically. See `docs/release-candidate.md` for the full record.

### Added

- Shadow-only release-candidate documentation, local signing procedure, and
  release artifact provenance requirements.
- Explicit release build guard: an unsigned `assembleRelease` is rejected.
- M1.4 real-device Shadow evidence accepted with privacy-minimized aggregate
  results only.
- M1.5 RC closure record: debug-artifact regression on an API 36 physical
  device (Shadow scan, OFF pause, restore, binding, no crash/ANR, complete
  scan metrics) with debug APK SHA-256 in `docs/release-candidate.md`.
- M1.6 signed RC closure: signed release APK (external keystore, signature
  verification, SHA-256), signed-release smoke on an API 36 physical device,
  API 35 emulator compatibility smoke, and the home-status truthfulness
  verdict, all recorded in `docs/release-candidate.md`.

### Changed

- Version metadata is now `1.0.0-rc1` (`versionCode` 1000001).
- The application About screen and README define the product consistently as
  an Android 15+ offline OFF/Shadow observation and diagnostics tool.

### Safety boundary

- This release candidate does not automatically skip ads. It contains no node
  click, gesture, global action, OCR, screenshot capture, networking,
  analytics, or telemetry capability.
