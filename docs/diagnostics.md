# Diagnostics / black box

Each burst scan produces one JSONL record. The file name is the local date, for example `2026-08-15.jsonl`.

Example shape:

```json
{
  "time": 1786780000000,
  "session": 17,
  "scan": 2,
  "package": "com.example",
  "windows": 1,
  "nodes": 143,
  "candidates": 1,
  "decision": "WOULD_CLICK",
  "score": 106,
  "label": "跳过",
  "resourceId": "com.example:id/ad_skip",
  "evidence": [
    {"code":"resource_id_skip","points":36},
    {"code":"label_skip","points":24},
    {"code":"top_right","points":18}
  ],
  "risks": []
}
```

The next diagnostic milestone is an exporter + replay runner so scoring changes can be evaluated against previously observed cases without waiting for the same ad to appear again.

Candidate `features` are deliberately persisted in sanitized form (geometry, resource ID, clickability, temporal counters) so future scorer versions can replay old observations without storing arbitrary screen text.
