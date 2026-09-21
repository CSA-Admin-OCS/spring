#!/usr/bin/env python3
"""
db_verify.py - Row-count and checksum reconciliation around a schema migration.

    snapshot(target)              -> {"taken_at", "dialect", "tables": {name: {count, checksum}}}
    compare(before, after, ...)   -> (ok, report_rows)

The snapshot format is shared with the Flask side (flask/dbtools/reconcile.py) so the
two backends' verify reports read the same. Checksums are sha256 over the rows in
primary-key / rowid order; MySQL uses CHECKSUM TABLE ... EXTENDED for speed.

Rules applied by compare():
  * a table with fewer rows after            -> FAIL
  * a table missing after                    -> FAIL unless it matches an allow-drop pattern
  * checksum changed with equal row counts   -> FAIL unless the table matches an allow-change pattern
  * a new table                              -> INFO
Allow patterns are fnmatch globs (e.g. "HT_*"). Migrations declare their own in a header
comment: `// verify-allow-change: person, adventure` / `// verify-allow-drop: HT_*`.
"""

from __future__ import annotations

import fnmatch
import hashlib
import json
import re
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

from mysql_common import (
    BACKUP_DIR,
    META_TABLE,
    PROJECT_ROOT,
    SQLITE_DB_FILE,
    detect_target,
    get_mysql_config,
    get_mysql_connection,
)

# Never part of the data being verified.
SKIP_TABLES = {"flyway_schema_history", "sqlite_sequence", META_TABLE}

MIGRATION_DIRS = [
    PROJECT_ROOT / "src" / "main" / "java" / "db" / "migration" / "common",
    PROJECT_ROOT / "src" / "main" / "resources" / "db" / "migration" / "sqlite",
    PROJECT_ROOT / "src" / "main" / "resources" / "db" / "migration" / "mysql",
]


# ── Snapshots ──────────────────────────────────────────────────────────────────

def _sqlite_snapshot(db_file: Path) -> dict:
    conn = sqlite3.connect(f"file:{db_file}?mode=ro", uri=True)
    tables = {}
    try:
        names = [r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
        for name in names:
            if name in SKIP_TABLES:
                continue
            count = conn.execute(f'SELECT COUNT(*) FROM "{name}"').fetchone()[0]
            h = hashlib.sha256()
            # WITHOUT ROWID tables would need their PK; Hibernate never creates those.
            try:
                cur = conn.execute(f'SELECT * FROM "{name}" ORDER BY rowid')
            except sqlite3.OperationalError:
                cur = conn.execute(f'SELECT * FROM "{name}"')
            for row in cur:
                h.update(repr(row).encode("utf-8"))
                h.update(b"\n")
            tables[name] = {"count": count, "checksum": h.hexdigest()}
    finally:
        conn.close()
    return tables


def _mysql_snapshot() -> dict:
    host, port, user, password, database = get_mysql_config()
    conn = get_mysql_connection(host, port, user, password, database)
    tables = {}
    try:
        cur = conn.cursor()
        cur.execute(
            "SELECT table_name FROM information_schema.tables "
            "WHERE table_schema = %s AND table_type = 'BASE TABLE' ORDER BY table_name",
            (database,),
        )
        names = [r[0] for r in cur.fetchall()]
        for name in names:
            if name in SKIP_TABLES:
                continue
            cur.execute(f"SELECT COUNT(*) FROM `{name}`")
            count = cur.fetchone()[0]
            cur.execute(f"CHECKSUM TABLE `{name}` EXTENDED")
            checksum = cur.fetchone()[1]
            tables[name] = {"count": int(count), "checksum": str(checksum)}
        cur.close()
    finally:
        conn.close()
    return tables


def snapshot(target: str | None = None, db_file: Path | None = None) -> dict:
    """Snapshot the configured target (or an explicit SQLite file)."""
    target = target or detect_target()
    if target == "sqlite":
        tables = _sqlite_snapshot(db_file or SQLITE_DB_FILE)
    else:
        tables = _mysql_snapshot()
    return {
        "taken_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "dialect": target,
        "tables": tables,
    }


def write_snapshot(snap: dict, label: str, stamp: str | None = None) -> Path:
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    stamp = stamp or datetime.now().strftime("%Y%m%d_%H%M%S")
    path = BACKUP_DIR / f"verify_{stamp}_{label}.json"
    path.write_text(json.dumps(snap, indent=2, sort_keys=True))
    return path


# ── Allow lists from migration headers ──────────────────────────────────────────

_HEADER_RE = re.compile(r"verify-allow-(change|drop)\s*:\s*(.+)$", re.MULTILINE)


def migration_allowances(versions: list[str] | None = None) -> tuple[list[str], list[str]]:
    """(allow_change, allow_drop) patterns declared by migration files.

    Restricted to the given versions when provided (e.g. the ones that were pending).
    """
    allow_change: list[str] = []
    allow_drop: list[str] = []
    for d in MIGRATION_DIRS:
        if not d.exists():
            continue
        for f in sorted(d.iterdir()):
            m = re.match(r"V(\d+)__", f.name)
            if not m:
                continue
            if versions is not None and m.group(1) not in versions:
                continue
            head = f.read_text(encoding="utf-8", errors="replace")[:4000]
            for kind, spec in _HEADER_RE.findall(head):
                pats = [p.strip() for p in spec.replace("//", "").replace("--", "").split(",") if p.strip()]
                (allow_change if kind == "change" else allow_drop).extend(pats)
    return allow_change, allow_drop


def _matches(name: str, patterns: list[str]) -> bool:
    return any(fnmatch.fnmatchcase(name, p) for p in patterns)


# ── Compare ────────────────────────────────────────────────────────────────────

def compare(before: dict, after: dict, allow_change=(), allow_drop=()):
    """Return (ok, rows). Each row: (status, table, before_count, after_count, note)."""
    allow_change = list(allow_change)
    allow_drop = list(allow_drop)
    rows = []
    ok = True
    b_tables, a_tables = before["tables"], after["tables"]
    for name in sorted(set(b_tables) | set(a_tables)):
        b, a = b_tables.get(name), a_tables.get(name)
        if b is None:
            rows.append(("INFO", name, "-", a["count"], "new table"))
            continue
        if a is None:
            if _matches(name, allow_drop):
                rows.append(("OK", name, b["count"], "-", "dropped (allowed)"))
            else:
                ok = False
                rows.append(("FAIL", name, b["count"], "-", "table missing after migration"))
            continue
        if a["count"] < b["count"]:
            ok = False
            rows.append(("FAIL", name, b["count"], a["count"], f"lost {b['count'] - a['count']} rows"))
        elif a["checksum"] != b["checksum"]:
            if _matches(name, allow_change):
                rows.append(("OK", name, b["count"], a["count"], "content changed (allowed)"))
            else:
                ok = False
                rows.append(("FAIL", name, b["count"], a["count"], "content changed"))
        elif a["count"] > b["count"]:
            rows.append(("INFO", name, b["count"], a["count"], "rows added"))
        else:
            rows.append(("OK", name, b["count"], a["count"], ""))
    return ok, rows


def print_report(rows, verbose=False):
    print(f"{'STATUS':<6} {'TABLE':<40} {'BEFORE':>9} {'AFTER':>9}  NOTE")
    shown = 0
    for status, name, b, a, note in rows:
        if not verbose and status == "OK" and not note:
            continue
        print(f"{status:<6} {name:<40} {str(b):>9} {str(a):>9}  {note}")
        shown += 1
    n_ok = sum(1 for r in rows if r[0] == "OK")
    n_fail = sum(1 for r in rows if r[0] == "FAIL")
    n_info = sum(1 for r in rows if r[0] == "INFO")
    if not verbose:
        print(f"({len(rows) - shown} unchanged tables not shown)")
    print(f"\nok={n_ok} info={n_info} fail={n_fail}")


def write_report(ok, rows, stamp: str) -> Path:
    path = BACKUP_DIR / f"verify_{stamp}_report.json"
    path.write_text(json.dumps({
        "ok": ok,
        "rows": [dict(zip(("status", "table", "before", "after", "note"), r)) for r in rows],
    }, indent=2))
    return path
