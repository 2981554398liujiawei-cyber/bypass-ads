# Privacy model

Bypass Ads. uses Android Accessibility APIs, which can expose sensitive UI content. The project therefore treats local diagnostics as sensitive by default.

## Runtime network policy

The application manifest does not request `android.permission.INTERNET`. No analytics, crash reporting, remote config, ad SDK, or cloud rule service is included.

## Default black box

The default recorder stores only data needed to debug decisions:

- timestamp and session/scan index
- package name
- window/node/candidate counts
- sanitized candidate label (`skip`, `close`, `跳过`, `关闭`, `×`)
- candidate resource ID
- score and named evidence/risk reasons
- boolean semantic risk flags (for example, whether a CTA-like sibling was observed)

It does not store:

- full accessibility tree text
- arbitrary sibling text
- screenshots
- typed user content

Retention is bounded to 7 days and 20 MB, whichever constrains first.

## Future detailed diagnostics

If a full-tree diagnostic mode is added, it must be explicit, time-limited, visibly active, local-only, and off by default.
