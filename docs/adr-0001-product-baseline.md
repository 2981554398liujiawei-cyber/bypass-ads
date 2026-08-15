# ADR-0001: Product baseline

Status: Accepted for v0.1

## Decisions

- Runtime networking: prohibited by manifest-level absence of INTERNET permission.
- Minimum Android version: Android 15 / API 35.
- Compile and target SDK: API 37.
- UI: Jetpack Compose with a small custom visual system.
- Coexistence: independent application ID (`app.bypassads`).
- Development mode: Shadow-first; no actuation in v0.1.
- Diagnostics: local bounded black box.
- Licensing: MIT provisionally, to be confirmed before the first public release.

## Rationale

These constraints reduce compatibility work, minimize privacy surface, enable safe A/B observation beside legacy LiTiaoTiao, and keep the core scoring system testable outside Android UI code.
