#!/usr/bin/env python3
"""Collect real ad samples from a device while the user uses the phone.

Usage:
    python collect_real_ad_samples.py --serial 479901bd [--out samples.json] [--seconds N]

The user simply plays with the phone (open apps, watch ads). The script
polls the Bypass Ads session table on the device (debug build) and records
only the non-content fields per session: package, activity, strategy at
session start, session id, result, action attempts, final candidate type,
confirmed latency, and rule origin. At the end it prints a grouped summary
(WECHAT / AGGRESSIVE / CRAZY ...).

Privacy: no chat content, no input text, no passwords, no SMS, no card
numbers, and no full accessibility trees are collected. Only the session
metadata rows are read.
"""

import argparse
import json
import sqlite3
import subprocess
import sys
import tempfile
import time
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

PKG = "app.bypassads.debug"
DB_REL = "cache/storage/emulated/0/Android/data/{pkg}/files/db/gkd.db".format(pkg=PKG)
TABLE = "bypass_detection_session"

SELECT_COLS = (
    "session_id, package_name, activity_name, strategy_mode, result, "
    "action_attempts, candidate_type, confirmed_latency_ms, rule_origin, "
    "start_time, end_time, diagnostic_timeline"
)


def sh(serial: str, *args: str) -> subprocess.CompletedProcess:
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=30)


def pull_db(serial: str) -> Path:
    """Copy the device DB to a temp file via run-as (app-private path)."""
    sh(serial, "shell", "run-as", PKG, "cp", DB_REL, "/data/local/tmp/bypass-samples.db")
    tmp = Path(tempfile.gettempdir()) / "bypass-samples.db"
    sh(serial, "pull", "/data/local/tmp/bypass-samples.db", str(tmp))
    if not tmp.exists() or tmp.stat().st_size == 0:
        return Path("")
    return tmp


def read_sessions(serial: str, since_ms: int, limit: int = 500) -> list[dict]:
    db = pull_db(serial)
    if not db.exists():
        return []
    try:
        con = sqlite3.connect(str(db))
        con.row_factory = sqlite3.Row
        rows = con.execute(
            f"SELECT {SELECT_COLS} FROM {TABLE} WHERE end_time > ? ORDER BY end_time DESC LIMIT ?",
            (since_ms, limit),
        ).fetchall()
        con.close()
    finally:
        db.unlink(missing_ok=True)
    return [dict(r) for r in rows]


def summarize(samples: list[dict]) -> dict:
    summary: dict[str, Counter] = defaultdict(Counter)
    for s in samples:
        pkg = s.get("package_name", "?")
        host = "WECHAT" if pkg == "com.tencent.mm" else "ALIPAY" if pkg == "com.eg.android.AlipayGphone" else "OTHER"
        summary[host][s.get("result", "?")] += 1
        summary["by_strategy"][s.get("strategy_mode", "?")] += 1
        summary["by_candidate"][s.get("candidate_type") or "?"] += 1
    return {k: dict(v) for k, v in summary.items()}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="479901bd", help="adb serial")
    parser.add_argument("--out", default="real_ad_samples.json", help="output file")
    parser.add_argument("--seconds", type=int, default=0, help="collect for N seconds (0 = until Ctrl+C)")
    args = parser.parse_args()

    samples: list[dict] = []
    last_seen_ms = 0
    print(f"collecting on {args.serial} ... press Ctrl+C to stop (sessions polled every 5s)")
    deadline = time.time() + args.seconds if args.seconds else None
    try:
        while True:
            if deadline and time.time() > deadline:
                break
            for s in read_sessions(args.serial, last_seen_ms):
                samples.append(s)
                last_seen_ms = max(last_seen_ms, s.get("end_time", 0))
            time.sleep(5)
    except KeyboardInterrupt:
        pass

    report = {
        "collectedAt": datetime.now().isoformat(timespec="seconds"),
        "sessionCount": len(samples),
        "samples": samples,
        "summary": summarize(samples),
    }
    out = Path(args.out)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n{len(samples)} sessions -> {args.out}")
    print("summary:", json.dumps(report["summary"], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
