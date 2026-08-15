# ADR-0001: Product baseline

Status: Accepted for Shadow-only V1.0 RC

## Decisions

- Runtime networking: prohibited by manifest-level absence of INTERNET permission.
- Minimum Android version: Android 15 / API 35.
- Compile and target SDK: API 37.
- UI: Jetpack Compose with a small custom visual system.
- Coexistence: independent application ID (`app.bypassads`).
- Release mode: Shadow-only; `RunMode` is limited to `SHADOW` and `OFF`, with no actuation in V1.0 RC.
- Diagnostics: local bounded black box.
- Licensing: MIT.

## Rationale

These constraints reduce compatibility work, minimize privacy surface, enable safe A/B observation beside legacy LiTiaoTiao, and keep the core scoring system testable outside Android UI code.
