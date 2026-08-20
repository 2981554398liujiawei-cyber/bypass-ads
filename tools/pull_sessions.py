#!/usr/bin/env python3
"""Pull the bypass session DB from the device and print session metadata.

Matches tools/collect_real_ad_samples.py path conventions.

Privacy (P0-6): the DB is pulled to a system temp file and deleted after
reading; only whitelisted non-content metadata columns are printed (no
candidate text/desc snapshots, no full diagnostic timelines, no SELECT *).
"""
import subprocess
import sys
import sqlite3
import tempfile
from pathlib import Path

SERIAL = sys.argv[1] if len(sys.argv) > 1 else "479901bd"
PKG = "app.bypassads.debug"
DB_REL = "cache/storage/emulated/0/Android/data/{pkg}/files/db/gkd.db".format(pkg=PKG)

# Non-content metadata only. Text-bearing columns (candidate_snapshots,
# matched_rules, diagnostic_timeline) are intentionally excluded.
SELECT_COLS = (
    "session_id, package_name, activity_name, start_time, end_time, "
    "candidate_seen, candidate_type, success, final_failure_reason, "
    "strategy_mode, result, action_attempts, confirmed_latency_ms, "
    "rule_origin, acted_rule_key, acted_group_key, acted_candidate_bounds"
)


def sh(*args):
    return subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True, timeout=30)


def main():
    sh("shell", "run-as", PKG, "cp", DB_REL, "/data/local/tmp/bypass-sessions.db")
    tmp = Path(tempfile.gettempdir()) / "bypass-sessions.db"
    sh("pull", "/data/local/tmp/bypass-sessions.db", str(tmp))
    if not tmp.exists() or tmp.stat().st_size == 0:
        print("NO DB")
        return
    try:
        con = sqlite3.connect(str(tmp))
        cur = con.cursor()
        tables = [r[0] for r in cur.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()]
        print("tables:", tables)
        if "bypass_detection_session" in tables:
            cols = [r[1] for r in cur.execute("PRAGMA table_info(bypass_detection_session)").fetchall()]
            print("columns:", cols)
            rows = cur.execute(
                f"SELECT {SELECT_COLS} FROM bypass_detection_session ORDER BY start_time DESC LIMIT 30"
            ).fetchall()
            print(f"session rows: {len(rows)}")
            for r in rows:
                print(r)
        con.close()
    finally:
        tmp.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
