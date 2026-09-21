#!/usr/bin/env python3
"""
db_init.py - DISASTER RECOVERY: wipe the database and rebuild it from the migrations.

Normal schema changes never need this. Flyway applies migrations incrementally at
every app start; use `db_migrate.py upgrade` / `verify`. Run this only when the
database must be thrown away and rebuilt from scratch (V1 baseline + V2..Vn), for
example after a corrupted file or to reset a development machine.

What it does:
  1. backs up the current database (SQLite online backup API / mysqldump path)
  2. SQLite: deletes volumes/sqlite.db (+ -wal/-shm); MySQL: drops every table
  3. runs Flyway migrate (schema.tool=migrate) which creates the schema from V1..Vn
  4. the next normal app start seeds sample data (ModelInit) because `person` is empty

Usage:
    python3 scripts/db_migrate.py init
    FORCE_YES=true python3 scripts/db_migrate.py init      # no confirmation prompt
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from migration_utils import (  # noqa: E402
    MigrationError,
    SqliteDatabaseFiles,
    ensure_port_not_in_use,
    print_header,
    run_schema_tool,
)
from mysql_common import (  # noqa: E402
    BACKUP_DIR,
    PROJECT_ROOT,
    SQLITE_DB_FILE,
    describe_target,
    detect_target,
    get_mysql_config,
    get_mysql_connection,
)

SPRING_PORT = 8585


def get_user_confirmation(target_db, db_file):
    if target_db == "sqlite" and not db_file.exists():
        return True
    if os.getenv("FORCE_YES") == "true":
        print("FORCE_YES detected, proceeding automatically...")
        return True
    if target_db == "mysql":
        print("WARNING: every table in the configured MySQL database will be DROPPED and rebuilt.")
    else:
        print(f"WARNING: {db_file} will be DELETED and rebuilt from the migrations (after a backup).")
    print("This is the disaster-recovery path. For a schema change use `db_migrate.py upgrade`.\n")
    response = input("Continue? (y/n): ").strip().lower()
    return response in ("y", "yes")


def _wipe_mysql():
    import mysqlrestore
    host, port, user, password, database = get_mysql_config()
    print("\nTaking a mysqldump rollback point first...")
    mysqlrestore.safety_dump(host, port, user, password, database)
    conn = get_mysql_connection(host, port, user, password, database)
    try:
        mysqlrestore.drop_all_tables(conn)
    finally:
        conn.close()


def main(db_file: Path | None = None, use_jar: bool = False) -> int:
    target_db = detect_target()
    db_file = db_file or SQLITE_DB_FILE
    print_header(f"Database Rebuild From Migrations ({target_db.upper()})")
    print(f"Target: {describe_target() if db_file == SQLITE_DB_FILE else db_file}")

    if target_db == "sqlite" and db_file == SQLITE_DB_FILE:
        ensure_port_not_in_use(SPRING_PORT, "Stop the Spring app first (./dev.sh stop spring).")

    if not get_user_confirmation(target_db, db_file):
        print("Cancelled.")
        return 0

    extra = []
    if target_db == "sqlite":
        if db_file.exists():
            # Online backup API, not a file copy: the database runs in WAL mode.
            import sqlite_migrate
            sqlite_migrate.backup_sqlite(db_file, BACKUP_DIR)
        SqliteDatabaseFiles(db_file, BACKUP_DIR).remove()
        extra.append(f"--spring.datasource.url=jdbc:sqlite:{db_file}?journal_mode=WAL")
    else:
        _wipe_mysql()

    print("\nCreating the schema with Flyway (V1 baseline + V2..Vn)...")
    code, report, log = run_schema_tool(PROJECT_ROOT, "migrate", extra_args=extra, use_jar=use_jar)
    if code != 0 or not report.get("ok"):
        raise MigrationError(f"Flyway migrate failed (log: {log}): {report.get('error')}")

    print_header("DATABASE REBUILT")
    print(f"Flyway applied: {report.get('applied')}")
    print("The schema is empty. The next normal app start seeds sample data (ModelInit).")
    if target_db == "mysql":
        print("To load real data on top of it: python3 scripts/db_migrate.py restore --keep-target-schema \\")
        print("      --backup-file volumes/backups/<your_backup>.db")
    else:
        print("To load real data on top of it: python3 scripts/db_migrate.py restore --backup-file <backup>.db")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n\nInterrupted by user")
        sys.exit(1)
    except Exception as e:
        print(f"\nAn error occurred: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        sys.exit(1)
