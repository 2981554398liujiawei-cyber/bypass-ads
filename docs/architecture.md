# Architecture

## Design objective

The system is not a keyword autoclicker. It is a risk-controlled, Shadow-only decision pipeline:

```text
Accessibility events
  -> burst scheduler
  -> immutable UiSnapshot
  -> CandidateDetector (privacy-safe features)
  -> TemporalCandidateTracker
  -> CandidateScorer
  -> DecisionEngine
  -> local black-box log
```

M1 deliberately ends at the decision log. There is no actuator in this code path.

## Modules

### `:core`

Pure Kotlin with no Android APIs:

- immutable geometry and UI snapshot models
- privacy-safe candidate feature extraction
- conservative scoring with named evidence and risks
- temporal stability, countdown, and position-drift tracking
- conflict-aware decision thresholding
- replay-friendly JVM tests

### `:app`

Android integration:

- `BypassAdsAccessibilityService`: package/window trigger and burst scheduling. It observes window-state, windows, and content events only; content events are debounced for 250 ms.
- `NodeSnapshotBuilder`: Android accessibility nodes to core snapshots
- `BlackBoxStore`: client facade over an application-process singleton repository, so Service and UI serialize every JSONL read/write/clear/retention operation through one worker
- Compose Home, Diagnostics, and Settings UI

## Safety rules

- False-positive cost is greater than missed-skip cost.
- A lone `×` is weak evidence and never sufficient.
- Central close text is penalized.
- A non-clickable label whose closest clickable ancestor occupies at least 20% of the screen is a severe risk.
- CTA-like sibling content is detected in memory and persisted only as a boolean risk flag.
- A candidate that moves more than 8% of screen width or height within a burst is a severe risk.
- Spatially separated candidates that both independently cross the trust threshold are treated as a conflict and rejected.
- Resource ID + conventional label + top-corner geometry + small clickable target is strong evidence, not a permission to act.
- The service requests no gesture capability and contains no node action, global action, or gesture call. `RunMode` has only `SHADOW` and `OFF`.

## Deferred work after M1

1. Collect real Shadow records.
2. Build replay import/export from sanitized candidate features.
3. Add outcome verification and an adversarial fixture corpus.
4. Design an actuator only after replay evidence demonstrates an acceptably low false-positive risk.
