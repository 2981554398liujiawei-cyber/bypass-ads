import sqlite3
import sys

con = sqlite3.connect(sys.argv[1] if len(sys.argv) > 1 else "tmp_s.db")
cur = con.cursor()
for table in ["app_visit_log", "activity_log_v2", "action_log"]:
    try:
        n = cur.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        print(f"{table}: {n} rows")
        if n:
            cols = [r[1] for r in cur.execute(f"PRAGMA table_info({table})").fetchall()]
            print("  cols:", cols)
            for r in cur.execute(f"SELECT * FROM {table} ORDER BY rowid DESC LIMIT 3").fetchall():
                print("  ", r)
    except Exception as e:
        print(f"{table}: {e}")
con.close()
