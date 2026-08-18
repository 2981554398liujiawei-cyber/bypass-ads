#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build the Bypass Ads bundled splash-ad subscription.

Reads a user-provided LOCAL GKD subscription (JSON or JSON5) and keeps ONLY
rule groups whose name is "开屏广告" or starts with "开屏广告-". This keeps
the product strictly to splash (开屏) ads: ordinary full-screen ads, popups,
feeds, update prompts, payment suggestions etc. are all excluded.

The generated file is written to app/src/main/assets/bypass_splash_rules.local.json,
which is gitignored. The repository only tracks a small self-owned fixture
(app/src/main/assets/bypass_splash_rules.json); third-party rule bodies are
NEVER committed.

Usage:
    python tools/build_splash_bundle.py <input.json5> [output.json]

The input file must be a GKD subscription (RawSubscription format) with JSON
double-quoted strings. JSON5 comments and trailing commas are stripped by a
simple pre-processor.
"""

import json
import re
import sys
from pathlib import Path

SPLASH_GROUP_PREFIX = "开屏广告"
DEFAULT_OUTPUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/bypass_splash_rules.local.json"


def strip_json5(text: str) -> str:
    """Minimal JSON5 -> JSON cleanup: strip // and /* */ comments, trailing
    commas, and convert single-quoted strings to double-quoted ones (handles
    \\\\ and \\' escapes inside single-quoted strings)."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            # copy double-quoted string verbatim
            out.append(c)
            i += 1
            while i < n:
                out.append(text[i])
                if text[i] == "\\" and i + 1 < n:
                    out.append(text[i + 1])
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if c == "'":
            # convert single-quoted string to double-quoted
            out.append('"')
            i += 1
            while i < n:
                ch = text[i]
                if ch == "\\" and i + 1 < n:
                    nxt = text[i + 1]
                    if nxt == "'":
                        out.append("\\'")
                    else:
                        out.append("\\")
                        out.append(nxt)
                    i += 2
                    continue
                if ch == "'":
                    out.append('"')
                    i += 1
                    break
                out.append(ch)
                i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
            continue
        out.append(c)
        i += 1
    cleaned = "".join(out)
    # remove trailing commas before } or ]
    cleaned = re.sub(r",\s*([}\]])", r"\1", cleaned)
    return cleaned


def is_splash_group(name: str) -> bool:
    return name == SPLASH_GROUP_PREFIX or name.startswith(SPLASH_GROUP_PREFIX + "-")


def load_subscription(raw: str):
    """Parse a GKD subscription that may be JSON or JSON5. Prefers the json5
    library when installed; falls back to the built-in minimal cleaner."""
    try:
        import json5  # type: ignore
        return json5.loads(raw)
    except Exception:
        pass
    return json.loads(strip_json5(raw))


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    src = Path(sys.argv[1])
    dst = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_OUTPUT
    raw = src.read_text(encoding="utf-8")
    try:
        data = load_subscription(raw)
    except Exception as e:
        print(f"ERROR: failed to parse {src}: {e}", file=sys.stderr)
        print("Note: strings must use double quotes; comments/trailing commas are OK.", file=sys.stderr)
        return 1

    apps_in = data.get("apps", [])
    total_groups = 0
    kept_apps = []
    kept_groups = 0
    kept_rules = 0
    for app in apps_in:
        groups = app.get("groups", [])
        total_groups += len(groups)
        splash = [g for g in groups if is_splash_group(g.get("name", ""))]
        if not splash:
            continue
        kept_groups += len(splash)
        kept_rules += sum(len(g.get("rules", [])) for g in splash)
        kept_apps.append({**app, "groups": splash})

    bundle = {
        "id": data.get("id", 100000001),
        "name": "Bypass Ads 开屏规则",
        "version": data.get("version", 1),
        "author": data.get("author", "bypass-ads"),
        "globalGroups": [],
        "categories": [c for c in data.get("categories", []) if c.get("name", "").startswith(SPLASH_GROUP_PREFIX)],
        "apps": kept_apps,
    }

    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(json.dumps(bundle, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"input subscription : {src}")
    print(f"  total apps       : {len(apps_in)}")
    print(f"  total rule groups: {total_groups}")
    print(f"filtered splash only:")
    print(f"  kept apps        : {len(kept_apps)}")
    print(f"  kept groups      : {kept_groups}")
    print(f"  kept rules       : {kept_rules}")
    for want in ("com.tencent.qqmusic", "com.tencent.mm", "com.eg.android.AlipayGphone"):
        hit = next((a for a in kept_apps if a.get("id") == want), None)
        if hit:
            names = [g.get("name") for g in hit["groups"]]
            print(f"  {want:32s} IN BUNDLE  groups={names}")
        else:
            print(f"  {want:32s} absent")
    print(f"output             : {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
