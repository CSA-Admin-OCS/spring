#!/usr/bin/env python3
"""
Reseat Hibernate's *_seq id generators above their table's MAX(id).

Most entities here allocate ids from a Hibernate `<table>_seq` table rather than
from a database AUTO_INCREMENT. Any process that copies rows between environments
without also copying the sequence tables -- db_local2prod.py and db_prod2local.py
both do exactly this -- leaves next_val behind MAX(id). The next insert then fails
with a primary key collision, which surfaces to the browser as a 500 (and, before
the security fix, as a redirect to the HTML login page).

Dry run by default; pass --apply to write.

    ./scripts/fix_sequences.py                      # local sqlite, report only
    ./scripts/fix_sequences.py --apply              # local sqlite, repair
    ./scripts/fix_sequences.py --mysql --apply      # remote, repair (reads .env)
"""

import argparse
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SQLITE_DB = REPO / "volumes" / "sqlite.db"

# Hibernate's default allocationSize. Leaving this much headroom means the repair
# is correct whichever optimizer is in play (pooled stores the upper bound of the
# block, pooled-lo the lower), at the cost of skipping a few ids. That is free.
ALLOCATION_SIZE = 50


class Sqlite:
    quote = '"'

    def __init__(self, path):
        import sqlite3
        self.conn = sqlite3.connect(path)
        self.label = f"sqlite {path}"

    def tables(self):
        return [r[0] for r in self.conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")]

    def columns(self, table):
        return [r[1] for r in self.conn.execute(f'PRAGMA table_info("{table}")')]

    def query(self, sql):
        return list(self.conn.execute(sql))

    def execute(self, sql):
        self.conn.execute(sql)

    def commit(self):
        self.conn.commit()


class MySql:
    quote = "`"

    def __init__(self):
        # Same driver, credentials and JDBC parsing as the other scripts in this
        # directory -- see mysql_common.py. Imported here rather than at module
        # scope so the sqlite path needs no MySQL driver at all.
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        from mysql_common import get_mysql_config, get_mysql_connection

        host, port, user, password, db = get_mysql_config()
        self.conn = get_mysql_connection(host, port, user, password, db)
        self.db = db
        self.label = f"mysql {host}/{db}"

    def tables(self):
        with self.conn.cursor() as cur:
            cur.execute("SELECT table_name FROM information_schema.tables "
                        "WHERE table_schema = %s", (self.db,))
            return [r[0] for r in cur.fetchall()]

    def columns(self, table):
        with self.conn.cursor() as cur:
            cur.execute("SELECT column_name FROM information_schema.columns "
                        "WHERE table_schema = %s AND table_name = %s", (self.db, table))
            return [r[0] for r in cur.fetchall()]

    def query(self, sql):
        with self.conn.cursor() as cur:
            cur.execute(sql)
            return list(cur.fetchall())

    def execute(self, sql):
        with self.conn.cursor() as cur:
            cur.execute(sql)

    def commit(self):
        self.conn.commit()


def audit(db):
    """Return one row per sequence table: (seq, base, column, values, max_id, target)."""
    names = db.tables()
    lower = {n.lower(): n for n in names}
    q = db.quote
    findings = []

    for seq in sorted(n for n in names if n.lower().endswith("_seq")):
        base = lower.get(seq[:-4].lower())
        if base is None:
            findings.append((seq, None, None, None, None, None))
            continue

        cols = db.columns(seq)
        if not cols:
            continue
        col = cols[0]

        id_col = "id" if "id" in [c.lower() for c in db.columns(base)] else None
        if id_col is None:
            findings.append((seq, base, col, None, None, None))
            continue

        values = [r[0] for r in db.query(f"SELECT {q}{col}{q} FROM {q}{seq}{q}")]
        max_id = db.query(f"SELECT MAX({q}id{q}) FROM {q}{base}{q}")[0][0] or 0
        target = max_id + ALLOCATION_SIZE + 1
        findings.append((seq, base, col, values, max_id, target))

    return findings


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apply", action="store_true", help="write the repair (default: report only)")
    ap.add_argument("--mysql", action="store_true", help="target the remote MySQL from DB_URL")
    args = ap.parse_args()

    if args.mysql:
        db = MySql()
    else:
        if not SQLITE_DB.exists():
            raise SystemExit(f"no sqlite database at {SQLITE_DB}")
        db = Sqlite(str(SQLITE_DB))

    print(f"target: {db.label}")
    print(f"mode:   {'APPLY' if args.apply else 'dry run (use --apply to write)'}\n")

    findings = audit(db)
    broken, ok, skipped = [], [], []

    for seq, base, col, values, max_id, target in findings:
        if base is None:
            skipped.append((seq, "no matching table"))
        elif values is None:
            skipped.append((seq, "no id column on base table"))
        elif len(values) != 1 or values[0] <= max_id:
            broken.append((seq, base, col, values, max_id, target))
        else:
            ok.append(seq)

    if broken:
        print(f"{'SEQUENCE':<34} {'next_val':>18}  {'MAX(id)':>9}  {'->':>2} {'repair':>9}")
        print("-" * 82)
        for seq, base, col, values, max_id, target in broken:
            shown = ",".join(str(v) for v in values) if values else "(empty)"
            flag = "  << duplicate rows" if len(values) > 1 else ""
            print(f"{seq:<34} {shown:>18}  {max_id:>9}  -> {target:>9}{flag}")
        print()

    print(f"{len(broken)} sequence(s) behind their table, {len(ok)} healthy, {len(skipped)} skipped")

    if not broken:
        return 0

    if not args.apply:
        print("\nnothing written. re-run with --apply to repair.")
        return 1

    q = db.quote
    for seq, base, col, values, max_id, target in broken:
        # Collapse to exactly one row: Hibernate expects a single-row table, and a
        # duplicated row makes which value it reads dependent on scan order.
        db.execute(f"DELETE FROM {q}{seq}{q}")
        db.execute(f"INSERT INTO {q}{seq}{q} ({q}{col}{q}) VALUES ({target})")
        print(f"  repaired {seq} -> {target}")
    db.commit()
    print(f"\n{len(broken)} sequence(s) repaired.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
