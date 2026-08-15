# ADR-0002 — Active Safety Contract

- Status: **accepted (M2.0, logic-only)**. No Android actuation capability is
  added in M2.0; this document freezes the *contract* that a future gated
  actuator (M2.1+) must satisfy.
- Scope: pure logic layer in `:core` (`app.bypassads.core.actuation`).
- Applies to: any future `RunMode.ACTIVE` / real `ACTION_CLICK` /
  `dispatchGesture` / global-action capability. None of those exist yet.

## 1. Core principles

1. **A `WOULD_CLICK` decision is a diagnostic opinion, not click
   authorization.** Shadow decides "a human *would* click this skip label";
   it never authorizes actuation by itself.
2. **Actuation must pass an independent `ActuationGate`, fail-closed by
   default.** Detection/scoring and actuation authorization are two separate
   safety boundaries. The gate never inherits the scorer's trust.
3. **Fresh revalidation before any action.** Never click a cached
   `AccessibilityNodeInfo` or a snapshot taken hundreds of milliseconds ago.
   The target must be re-resolved against a freshly acquired window tree and
   re-gated.
4. **Uniqueness is mandatory.** Zero matches → BLOCK; more than one match →
   BLOCK. No "pick the closest similar control" fallback.
5. **Any uncertainty → BLOCK.** The gate is not "try to click". Missing,
   ambiguous, drifted, or changed state blocks with a concrete reason.
6. **At most one actuation attempt per case.** `NO_EFFECT` is not retried;
   `UNCERTAIN` is not retried. This removes the most dangerous failure mode
   ("clicked wrong, now retry aggressively").

## 2. Decision pipeline (target)

```
UiSnapshot
  → Detector / Tracker / Scorer
  → WOULD_CLICK (Shadow)
  → ActionProposal
  → re-acquire latest window tree
  → re-resolve the unique target
  → ActuationGate (fail-closed)
  → ALLOW | BLOCK
```

## 3. ActuationGate conditions

`ActuationGate.evaluate(...)` returns `ALLOW` only when **every** condition
holds; otherwise it returns `BLOCK` with a concrete `ActuationBlockReason`.

| # | Condition | Otherwise BLOCK with |
| --- | --- | --- |
| 1 | Action mode is armed (e.g. `ACTIVE`); `OFF`/`SHADOW` never allow | `MODE_BLOCKED` |
| 2 | The case is still active (not ended / not superseded) | `CASE_STALE` |
| 3 | No actuation attempt has been made on this case yet (one attempt per case, enforced by the gate) | `ATTEMPT_LIMIT_REACHED` |
| 4 | Proposal case generation equals the current generation | `STALE_GENERATION` |
| 5 | Proposal window context token equals the current window token (window identity, not window count) | `WINDOW_CHANGED` |
| 6 | The fresh snapshot is strictly newer than the proposal | `REVALIDATION_STALE` |
| 7 | Re-resolved package matches the proposal package | `PACKAGE_CHANGED` |
| 8 | Exactly one matching target re-resolved | `TARGET_DISAPPEARED` / `MULTIPLE_TARGETS` |
| 9 | Explicit label still present and identical | `LABEL_CHANGED` |
| 10 | Identity unambiguous (resourceId, when present, must still match) | `IDENTITY_AMBIGUOUS` |
| 11 | Geometry still within the active allowed zone — **top-right or top-left only** (right: `x ≥ 0.70 && y ≤ 0.25`; left: `x ≤ 0.30 && y ≤ 0.25`); bottom/side/central positions block | `GEOMETRY_CHANGED` |
| 12 | Target still small-sized (area ratio ≤ small-target bound) | `LARGE_TARGET` |
| 13 | Target still `clickable` / `enabled` / `visibleToUser` | `NOT_CLICKABLE` |
| 14 | No large clickable ancestor | `ANCESTOR_RISK` |
| 15 | No CTA sibling (立即体验 / 立即打开 / 下载 / 查看详情 …) | `CTA_RISK` |
| 16 | No countdown anomaly on revalidation (when a countdown was tracked) | `COUNTDOWN_ANOMALY` |
| 17 | Revalidated score still ≥ 80 | `SCORE_DROPPED` |
| 18 | Revalidated risks still above the severe floor (> −50) | `RISK_BLOCKED` |

`ActuationBlockReason` values (extensible): `MODE_BLOCKED`, `CASE_STALE`,
`STALE_GENERATION`, `WINDOW_CHANGED`, `REVALIDATION_STALE`,
`PACKAGE_CHANGED`, `TARGET_DISAPPEARED`, `MULTIPLE_TARGETS`,
`LABEL_CHANGED`, `IDENTITY_AMBIGUOUS`, `GEOMETRY_CHANGED`, `LARGE_TARGET`,
`NOT_CLICKABLE`, `ANCESTOR_RISK`, `CTA_RISK`, `SCORE_DROPPED`,
`RISK_BLOCKED`, `COUNTDOWN_ANOMALY`, `ATTEMPT_LIMIT_REACHED`.

## 4. Pre-click re-resolution contract

- `resourceId` may be used for identity strengthening, but never alone to
  authorize a click.
- An explicit label must always be preserved and re-verified.
- Without `resourceId`: sanitized label + bounds/geometry + package/window
  context.
- 0 matches → `BLOCK`; > 1 matches → `BLOCK`; identity drift → `BLOCK`.
- No fuzzy "close enough" matching, ever.

## 5. Post-action outcome contract (defined in M2.0, executed in M2.1+)

- `ActuationOutcome` is one of `SUCCESS`, `NO_EFFECT`, `UNCERTAIN`.
- Typical `SUCCESS` signals: original target disappeared; original splash
  window disappeared; window/content visibly entered a non-ad state.
- At most **one** actuation attempt per case
  (`MAX_ACTUATION_ATTEMPTS_PER_CASE = 1`), **enforced by the gate**: the
  caller passes `attemptsAlreadyMade` and the gate returns
  `ATTEMPT_LIMIT_REACHED` once it is ≥ 1.
- `NO_EFFECT` → do not auto-retry. `UNCERTAIN` → do not auto-retry.

## 6. M2.0 boundaries (enforced)

- No `RunMode.ACTIVE`; no `performAction` / `ACTION_CLICK` /
  `dispatchGesture` / global actions; no gesture capability; no new
  permissions; no manifest/network boundary change; RC branch/tag/release
  untouched.
- The gate and outcome classifier are pure Kotlin and are exercised only by
  unit tests in M2.0.
