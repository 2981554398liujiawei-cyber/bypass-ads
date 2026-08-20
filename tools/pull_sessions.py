#!/usr/bin/env python3
"""Pull the bypass session DB from the device and print session rows.

Matches tools/collect_real_ad_samples.py path conventions.
"""
import subprocess
import sys
import sqlite3
from pathlib import Path

SERIAL = sys.argv[1] if len(sys.argv) > 1 else "479901bd"
PKG = "app.bypassads.debug"
DB_REL = "cache/storage/emulated/0/Android/data/{pkg}/files/db/gkd.db".format(pkg=PKG)


def sh(*args):
    return subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True, timeout=30)


def main():
    sh("shell", "run-as", PKG, "cp", DB_REL, "/data/local/tmp/bypass-samples.db")
    tmp = Path("tmp_s.db")
    sh("pull", "/data/local/tmp/bypass-samples.db", str(tmp))
    if not tmp.exists() or tmp.stat().st_size == 0:
        print("NO DB")
        return
    con = sqlite3.connect(str(tmp))
    cur = con.cursor()
    tables = [r[0] for r in cur.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()]
    print("tables:", tables)
    if "bypass_detection_session" in tables:
        cols = [r[1] for r in cur.execute("PRAGMA table_info(bypass_detection_session)").fetchall()]
        print("columns:", cols)
        rows = cur.execute("SELECT * FROM bypass_detection_session ORDER BY start_time DESC LIMIT 30").fetchall()
        print(f"session rows: {len(rows)}")
        for r in rows:
            print(r)
    con.close()


if __name__ == "__main__":
    main()
