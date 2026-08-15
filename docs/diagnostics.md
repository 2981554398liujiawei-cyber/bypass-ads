# Diagnostics / black box

Black-box files are newline-delimited JSON, stored locally under the app-private `bypass_ads_blackbox/` directory. The file name is the local date, for example `2026-08-15.jsonl`.

A service connection, package/window trigger, and each burst scan are separate records. Scan records include the original trigger and final decision:

```json
{
  "time": 1786780000000,
  "session": 17,
  "scan": 2,
  "package": "com.example",
  "trigger": "SCAN",
  "sourceTrigger": "WINDOW_CHANGED",
  "windows": 1,
  "nodes": 143,
  "candidates": 1,
  "decision": "WOULD_CLICK",
  "score": 106,
  "evidence": [
    {"code":"resource_id_skip","points":36},
    {"code":"label_skip","points":24},
    {"code":"top_right","points":18}
  ],
  "risks": [],
  "features": [
    {
      "label":"跳过",
      "resourceId":"com.example:id/ad_skip",
      "bounds":[900,60,1050,130],
      "clickable":true,
      "hits":2,
      "countdown":4,
      "countdownStep":true,
      "positionDrift":false,
      "ctaSibling":false
    }
  ],
  "latencyMs":141
}
```

Records use append-and-sync writes. A process interruption can at most leave an incomplete final line; the reader skips invalid lines so earlier history remains usable. Retention removes records older than 7 days, then deletes oldest daily files until the total is at most 20 MB.

The Diagnostics screen exposes recent records, score/risk detail, today’s counts, and a confirmed local clear action. It does not upload or export data in M1.

Candidate features are deliberately sanitized to geometry, resource ID, clickability, temporal counters, and boolean risks. This allows future replay work without retaining arbitrary UI text.
