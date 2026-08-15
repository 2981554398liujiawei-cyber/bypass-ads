# Architecture

## Design objective

The system is not a keyword autoclicker. It is a risk-controlled decision pipeline:

```text
Accessibility events
  -> burst scheduler
  -> immutable UiSnapshot
  -> CandidateDetector (privacy-safe features)
  -> TemporalCandidateTracker
  -> CandidateScorer
  -> DecisionEngine
  -> Shadow log
  -> (future) SafeActuator
```

## Modules

### `:core`
Pure Kotlin. No Android APIs.

- immutable geometry / UI snapshot models
- candidate detection
- conservative scoring
- decision threshold
- replay-friendly unit tests

### `:app`
Android integration.

- `LttAccessibilityService`: event trigger + burst scheduling
- `NodeSnapshotBuilder`: converts Android accessibility nodes to core snapshots
- `BlackBoxStore`: bounded local JSONL diagnostics
- Compose status UI

## Safety rules

- False positive cost > missed-skip cost.
- A lone `×` is weak evidence, never sufficient by itself.
- A non-clickable label whose closest clickable ancestor occupies >= 20% of the screen is treated as a severe risk.
- Central "close" text is penalized.
- CTA-like sibling content is detected in-memory and persisted only as a boolean risk flag.
- A large clickable ancestor penalizes even a clickable child; temporal/countdown evidence is required to recover confidence.
- Resource ID + conventional label + top-corner geometry + small clickable target is strong evidence.
- Real click execution remains disabled until Shadow data is replay-tested.

## Planned next steps

1. Add replay file import/export around the already persisted candidate features.
2. Add outcome verification model.
3. Add adversarial fixture corpus.
4. Only then implement `SafeActuator` with node click -> constrained ancestor -> constrained gesture fallback.
