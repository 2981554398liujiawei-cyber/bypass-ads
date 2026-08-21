# Bypass Ads project state

Version: 1.0.0
Application ID: `app.bypassads`

## Runtime

Bypass Ads uses the existing GKD-based runtime:

`Bypass UI -> BypassEngine -> GKD matcher / selector / action runtime`

Rules are layered as clean bundled base + Local Import + Teach. Supported
strategies are Conservative, Aggressive, and Crazy.

## Build and release

- Default `gkd` build is offline and has no `INTERNET` permission.
- `fulltools` is optional and intended for local advanced tooling.
- The self-use release is signed, R8-minified, and built by
  `tools/build_selfuse.ps1`.
- Full local bundles are generated from a local third-party subscription and
  are gitignored. They are for local self-use only and must not be publicly
  redistributed without permission.

## Security boundaries

The product has no OCR, AI vision, second accessibility scanner, cloud rule
service, telemetry, or automatic upload of UI trees. Diagnostics are local,
bounded, and privacy-safe. The repository tracks only the small self-written
baseline fixture, safety exclusions, tests, and build tooling.

## Verification

Run the Python policy gates, `tools/check_repo_integrity.py`, and
`:app:testGkdDebugUnitTest`. Device checks use `tools/field_v1_gate.py` and
must distinguish synthetic TestAd coverage from real-app field samples.
