# Changelog

## 1.0.0-rc1 - Unreleased

### Added

- Shadow-only release-candidate documentation, local signing procedure, and
  release artifact provenance requirements.
- Explicit release build guard: an unsigned `assembleRelease` is rejected.
- M1.4 real-device Shadow evidence accepted with privacy-minimized aggregate
  results only.

### Changed

- Version metadata is now `1.0.0-rc1` (`versionCode` 1000001).
- The application About screen and README define the product consistently as
  an Android 15+ offline OFF/Shadow observation and diagnostics tool.

### Safety boundary

- This release candidate does not automatically skip ads. It contains no node
  click, gesture, global action, OCR, screenshot capture, networking,
  analytics, or telemetry capability.
