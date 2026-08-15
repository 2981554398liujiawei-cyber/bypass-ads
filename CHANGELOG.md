# Changelog

## 1.0.0-rc1 - Unreleased

### Added

- Shadow-only release-candidate documentation, local signing procedure, and
  release artifact provenance requirements.
- Explicit release build guard: an unsigned `assembleRelease` is rejected.
- M1.4 real-device Shadow evidence accepted with privacy-minimized aggregate
  results only.
- M1.5 RC closure record: debug-artifact regression on an API 36 physical
  device (Shadow scan, OFF pause, restore, binding, no crash/ANR, complete
  scan metrics) with debug APK SHA-256 in `docs/release-candidate.md`.

### Changed

- Version metadata is now `1.0.0-rc1` (`versionCode` 1000001).
- The application About screen and README define the product consistently as
  an Android 15+ offline OFF/Shadow observation and diagnostics tool.

### Safety boundary

- This release candidate does not automatically skip ads. It contains no node
  click, gesture, global action, OCR, screenshot capture, networking,
  analytics, or telemetry capability.
