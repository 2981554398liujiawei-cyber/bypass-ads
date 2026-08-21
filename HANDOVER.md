# Bypass Ads maintenance handover

Bypass Ads is an offline Android 1.0.0 self-use build based on the GKD
matcher. The product path is `Bypass UI -> BypassEngine -> GKD runtime`.

## Build

- Debug: `.\gradlew.bat :app:assembleGkdDebug`
- FullTools: `.\gradlew.bat :app:assembleFulltoolsDebug`
- Self-use release: `.\tools\build_selfuse.ps1 -SubscriptionPath <path>`

The release script generates the local third-party bundle, runs policy/JVM
gates, builds signed R8 release, and writes `dist/Bypass-Ads-v1.0.0-selfuse.apk`.

## Runtime and safety

Rules are composed as clean bundled base + Local Import + Teach. There is no
OCR, AI vision, second accessibility scanner, cloud service, telemetry, or
default INTERNET permission. Third-party rule bodies remain local-only.

## Maintenance

Common checks are the four `tools/test_*.py` policy tests,
`tools/check_repo_integrity.py`, and `.\gradlew.bat :app:testGkdDebugUnitTest`.
For device work use `tools/field_v1_gate.py` with serial `479901bd`; do not
uninstall or clear data during upgrade checks. The long-term signing key is
kept outside the repository at `%USERPROFILE%\.bypass-ads\signing\`.
