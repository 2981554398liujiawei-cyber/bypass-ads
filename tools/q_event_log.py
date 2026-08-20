import sqlite3
import sys

con = sqlite3.connect(sys.argv[1] if len(sys.argv) > 1 else "tmp_s.db")
cur = con.cursor()
cur.execute("SELECT name FROM sqlite_master WHERE type='table'")
tables = [r[0] for r in cur.fetchall()]
print("a11y_event_log:", "a11y_event_log" in tables)
if "a11y_event_log" in tables:
    cols = [r[1] for r in cur.execute("PRAGMA table_info(a11y_event_log)").fetchall()]
    print("cols:", cols)
    rows = cur.execute("SELECT * FROM a11y_event_log ORDER BY rowid DESC LIMIT 8").fetchall()
    for r in rows:
        print(r)
con.close()
