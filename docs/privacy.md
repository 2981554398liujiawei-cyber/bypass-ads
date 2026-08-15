# Privacy model

Bypass Ads. uses Android Accessibility APIs, which can expose sensitive UI content. The project therefore treats local diagnostics as sensitive by default and minimizes them before persistence.

## Runtime network policy

The application manifest does not request `android.permission.INTERNET`. No analytics, crash reporting, remote configuration, ad SDK, cloud rule service, or runtime network request is included.

## Default black box

The default recorder stores only data needed to diagnose a Shadow decision:

- timestamp, session/scan index, trigger, total latency, and scan work duration
- package name and window/node/candidate counts
- sanitized candidate label (`skip`, `close`, `跳过`, `关闭`, `×`) and resource ID
- candidate geometry, clickability, and clickable-ancestor geometry
- temporal stability, countdown progression, position-drift, and identity-ambiguity booleans
- boolean semantic risk flags, such as whether a CTA-like sibling was observed
- score, named evidence/risk reasons, decision, and rejection reason

It does not store:

- full accessibility-tree text
- arbitrary sibling or CTA text
- screenshots
- typed user content, chat content, or verification codes
- full raw AccessibilityEvent payloads

Retention is bounded to 7 days and 20 MB, whichever constraint is reached first. Data stays in Android app-private storage and can be cleared locally from Diagnostics.

The recorder performs JSONL writes, sync, retention, reads, and clearing on one application-process serial background worker shared by the service and UI. Local queue health counters contain no content, device ID, or network data. It requests no screenshot or gesture capability and subscribes to no `TYPE_VIEW_TEXT_CHANGED` events.

## Future detailed diagnostics

If a full-tree diagnostic mode is ever considered, it must be explicit, time-limited, visibly active, local-only, off by default, and reviewed separately before implementation.
