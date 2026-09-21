#!/usr/bin/env python3
"""
db_migrate.py - One entry point for every Spring database schema operation.

Day to day (Flyway migrations, applied automatically at every app start):

    python3 scripts/db_migrate.py status            # target + Flyway history + pending
    python3 scripts/db_migrate.py check             # Flyway validate + Hibernate validate; non-zero on drift
    python3 scripts/db_migrate.py new "add foo"     # scaffold V<n>__add_foo.sql for sqlite/ and mysql/
    python3 scripts/db_migrate.py upgrade           # backup, then apply pending migrations
    python3 scripts/db_migrate.py verify            # snapshot -> upgrade -> snapshot -> reconcile

Disaster recovery only (drop/rebuild + backup/restore, see README "Disaster recovery"):

    python3 scripts/db_migrate.py backup            # verified backup of the live database
    python3 scripts/db_migrate.py pull              # run backup on the server, copy it here (SQLite)
    python3 scripts/db_migrate.py restore           # load a backup back in
    python3 scripts/db_migrate.py init              # wipe and rebuild the schema from V1..Vn (destructive)
    python3 scripts/db_migrate.py roundtrip         # DR type-map round-trip check (was `check`)
    python3 scripts/db_migrate.py baseline-dump     # regenerate V1__baseline.sql (rare)
    python3 scripts/db_migrate.py repair            # flyway repair after a failed migration

Every command follows whatever the app itself is configured to use. If DB_URL is
set to a jdbc:mysql: URL the target is MySQL; otherwise application.properties
falls back to `jdbc:sqlite:volumes/sqlite.db` and the target is that file. Pass
`--db <path>` to point the SQLite commands at another file (rehearsals on a copy).

status/check/upgrade/verify boot the application in schema-tool mode
(--schema.tool=..., see SchemaToolRunner.java). That takes ~30-60 s via mvnw; pass
--jar to use target/spring-*.jar when one has been packaged.
"""

from __future__ import annotations

import argparse
import re
import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import mysql_common  # noqa: E402
from mysql_common import (  # noqa: E402
    BACKUP_DIR,
    PROJECT_ROOT,
    describe_target,
    detect_target,
    get_mysql_config,
    get_mysql_connection,
    print_header,
    read_ddl_from_mysql,
    read_ddl_from_schema_dump,
    roundtrip_report,
)
from migration_utils import (  # noqa: E402
    MigrationError,
    ensure_port_not_in_use,
    run_schema_tool,
)

SQLITE_DB_FILE = mysql_common.SQLITE_DB_FILE  # may be overridden by --db
SPRING_PORT = 8585
MIGRATION_ROOT = PROJECT_ROOT / "src" / "main" / "resources" / "db" / "migration"
JAVA_MIGRATION_DIR = PROJECT_ROOT / "src" / "main" / "java" / "db" / "migration" / "common"

JAVA_MIGRATION_TEMPLATE = """package db.migration.common;

import java.sql.Connection;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

// verify-allow-change:
// verify-allow-drop:
/** {description} */
public class {cls} extends BaseJavaMigration {{

    @Override
    public void migrate(Context context) throws Exception {{
        Connection c = context.getConnection();
        boolean mysql = MigrationSupport.isMySql(c);
        // Check DatabaseMetaData before every DDL statement so the migration is idempotent
        // against databases that were baseline-stamped. SQLite: no ALTER COLUMN / DROP
        // CONSTRAINT -- use V2__Legacy_schema_sync.rebuildSqliteTable().
        throw new UnsupportedOperationException("TODO: implement " + getClass().getSimpleName());
    }}
}}
"""


# ── Shared helpers ─────────────────────────────────────────────────────────────

def _apply_db_override(args):
    """--db <path>: run the SQLite-side commands against another file."""
    global SQLITE_DB_FILE
    db = getattr(args, "db", None)
    if not db:
        return
    if detect_target() != "sqlite":
        raise MigrationError("--db only applies to SQLite targets (DB_URL is set to MySQL)")
    path = Path(db)
    if not path.is_absolute():
        path = Path.cwd() / path
    SQLITE_DB_FILE = path
    mysql_common.SQLITE_DB_FILE = path
    import sqlite_migrate
    sqlite_migrate.DB_FILE = path


def _is_live_sqlite():
    return detect_target() == "sqlite" and SQLITE_DB_FILE == PROJECT_ROOT / "volumes" / "sqlite.db"


def _tool_args():
    """Extra Spring arguments for a schema-tool run against the current target."""
    if detect_target() == "sqlite":
        return [f"--spring.datasource.url=jdbc:sqlite:{SQLITE_DB_FILE}?journal_mode=WAL"]
    return []


def _print_tool_report(report):
    if report.get("error"):
        print(f"  error: {report['error']}")
    if "current" in report:
        print(f"  Flyway current: {report.get('current')}   applied: {report.get('applied')}")
        print(f"  pending: {report.get('pending')}   failed: {report.get('failed')}")
    if "hibernateValid" in report:
        print(f"  Hibernate validate: {'ok' if report.get('hibernateValid') else 'FAILED'}")
    if report.get("sequenceWarnings"):
        print("  id generator warnings:")
        for w in report["sequenceWarnings"]:
            print(f"    {w}")


def _print_backups(pattern):
    print("\nLocal backups:")
    if not BACKUP_DIR.exists():
        print("  none -- run `db_migrate.py backup` first")
        return
    backups = sorted(BACKUP_DIR.glob(pattern), key=lambda p: p.stat().st_mtime, reverse=True)
    if not backups:
        print("  none -- run `db_migrate.py backup` first")
    for path in backups[:5]:
        stamp = datetime.fromtimestamp(path.stat().st_mtime).strftime("%Y-%m-%d %H:%M")
        print(f"  {stamp}  {path.stat().st_size:>12,} bytes  {path.name}")


# ── status ─────────────────────────────────────────────────────────────────────

def cmd_status(args):
    """Show the configured target, what is in it, and Flyway's view of it."""
    print_header("Migration Status")

    target = detect_target()
    print(f"Mode:   {target.upper()}")
    print(f"Target: {describe_target()}")

    if target == "sqlite":
        if not SQLITE_DB_FILE.exists():
            print(f"\nNo database file at {SQLITE_DB_FILE}")
            print("Starting the app (or `db_migrate.py upgrade`) creates it from the migrations.")
            return 0
        _status_sqlite()
    else:
        _status_mysql()

    print("\nFlyway (schema.tool=info):")
    code, report, log = run_schema_tool(
        PROJECT_ROOT, "info",
        extra_args=[*_tool_args(), "--spring.jpa.hibernate.ddl-auto=none"],
        use_jar=args.jar,
    )
    _print_tool_report(report)
    if report.get("error"):
        print(f"  (log: {log})")
        return 1
    if code == 2:
        print("  -> pending migrations: run `db_migrate.py upgrade` (or start the app)")
    return 0


def _status_mysql():
    host, port, user, password, database = get_mysql_config()
    conn = get_mysql_connection(host, port, user, password, database)
    try:
        cursor = conn.cursor()
        cursor.execute(
            "SELECT table_name, table_rows FROM information_schema.tables "
            "WHERE table_schema = %s ORDER BY table_name",
            (database,),
        )
        rows = cursor.fetchall()
        cursor.close()
    finally:
        conn.close()

    seqs = [r for r in rows if str(r[0]).endswith("_seq")]
    print(f"\nTables:              {len(rows)}")
    print(f"  Hibernate id seqs: {len(seqs)}  (*_seq)")
    print(f"  Application:       {len(rows) - len(seqs)}")
    print("\n(information_schema row counts are InnoDB estimates, not exact.)")
    _print_backups("mysql_backup_*.db")


def _status_sqlite():
    import sqlite3
    import sqlite_migrate

    conn = sqlite3.connect(f"file:{SQLITE_DB_FILE}?mode=ro", uri=True)
    try:
        tables = sqlite_migrate.list_tables(conn)
        counts = {t: sqlite_migrate.row_count(conn, t) for t in tables}
        journal = conn.execute("PRAGMA journal_mode").fetchone()[0]
    finally:
        conn.close()

    seqs = [t for t in tables if t.endswith("_seq")]
    scratch = [t for t in tables if t.startswith(("HT_", "HTE_"))]
    total_rows = sum(c for c in counts.values() if c > 0)

    print(f"\nFile size:           {SQLITE_DB_FILE.stat().st_size:,} bytes")
    print(f"Journal mode:        {journal}")
    print(f"\nTables:              {len(tables)}")
    print(f"  Hibernate id seqs: {len(seqs)}  (*_seq)")
    if scratch:
        print(f"  Hibernate scratch: {len(scratch)}  (HT_* / HTE_*, dropped by migration V3)")
    print(f"  Application:       {len(tables) - len(seqs) - len(scratch)}")
    print(f"Rows (exact):        {total_rows:,}")

    populated = sorted(
        ((t, c) for t, c in counts.items() if c > 0 and not t.endswith("_seq")),
        key=lambda kv: kv[1], reverse=True,
    )
    if populated:
        print("\nLargest tables:")
        for name, count in populated[:10]:
            print(f"  {count:>9,}  {name}")

    _print_backups("sqlite_backup_*.db")


# ── check / upgrade / verify ───────────────────────────────────────────────────

def cmd_check(args):
    """Flyway validate + Hibernate ddl-auto=validate + id generator sanity. Non-zero on drift."""
    print_header("Schema Check")
    print(f"Target: {describe_target()}")
    code, report, log = run_schema_tool(PROJECT_ROOT, "check", extra_args=_tool_args(), use_jar=args.jar)
    _print_tool_report(report)
    if code == 0 and report.get("ok"):
        print("\nSchema matches the entities and every migration is applied.")
        return 0
    if report.get("error"):
        print(f"\nCheck FAILED (log: {log})")
        if "Schema-validation" in str(report.get("error")):
            print("The entities and the database disagree. Write a migration:")
            print('  python3 scripts/db_migrate.py new "<what changed>"')
    elif report.get("pending"):
        print("\nCheck FAILED: pending migrations. Run `db_migrate.py upgrade`.")
    else:
        print(f"\nCheck FAILED (log: {log})")
    return 1


def _backup_before_upgrade():
    if detect_target() == "sqlite":
        if SQLITE_DB_FILE.exists():
            import sqlite_migrate
            sqlite_migrate.backup_sqlite(SQLITE_DB_FILE, BACKUP_DIR)
        else:
            print("No database file yet -- nothing to back up (Flyway will create it)")
    else:
        import mysqlbackup
        mysqlbackup.main()


def cmd_upgrade(args, quiet=False):
    """Back up, then apply pending Flyway migrations (schema.tool=migrate)."""
    if not quiet:
        print_header("Schema Upgrade")
        print(f"Target: {describe_target()}")
    if _is_live_sqlite():
        # Flyway on SQLite has no cross-process lock: never migrate under a running app.
        ensure_port_not_in_use(SPRING_PORT, "Stop the Spring app first (./dev.sh stop spring).")
    if not args.no_backup:
        _backup_before_upgrade()
    code, report, log = run_schema_tool(PROJECT_ROOT, "migrate", extra_args=_tool_args(), use_jar=args.jar)
    _print_tool_report(report)
    if code != 0 or not report.get("ok"):
        print(f"\nUpgrade FAILED (log: {log})")
        if detect_target() == "mysql":
            print("MySQL DDL is not transactional: inspect the state, then `db_migrate.py repair`.")
        return 1
    print("\nSchema is at the latest migration.")
    return 0


def cmd_verify(args):
    """Snapshot -> upgrade -> snapshot -> reconcile row counts and checksums."""
    import db_verify

    print_header("Verified Schema Upgrade")
    print(f"Target: {describe_target()}")
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    # Which migrations are about to run? Their headers may allow specific changes.
    code, info, _log = run_schema_tool(
        PROJECT_ROOT, "info",
        extra_args=[*_tool_args(), "--spring.jpa.hibernate.ddl-auto=none"], use_jar=args.jar)
    if info.get("error"):
        print(f"Could not read Flyway state: {info['error']}")
        return 1
    pending = list(info.get("pending") or [])
    print(f"Pending migrations: {pending or 'none'}")

    kw = {"db_file": SQLITE_DB_FILE} if detect_target() == "sqlite" else {}
    before = db_verify.snapshot(**kw)
    before_path = db_verify.write_snapshot(before, "before", stamp)
    print(f"Before snapshot: {len(before['tables'])} tables -> {before_path}")
    if args.dry_run:
        print("--dry-run: stopping before the upgrade.")
        return 0

    rc = cmd_upgrade(args, quiet=True)
    after = db_verify.snapshot(**kw)
    after_path = db_verify.write_snapshot(after, "after", stamp)
    print(f"After snapshot:  {len(after['tables'])} tables -> {after_path}")

    allow_change, allow_drop = db_verify.migration_allowances(pending or None)
    allow_change += [p for p in (args.allow_changed or "").split(",") if p]
    allow_drop += [p for p in (args.allow_dropped or "").split(",") if p]
    ok, rows = db_verify.compare(before, after, allow_change, allow_drop)
    print()
    db_verify.print_report(rows, verbose=args.verbose)
    report_path = db_verify.write_report(ok, rows, stamp)
    print(f"Report: {report_path}")
    if rc != 0:
        print("\nUpgrade step failed; see above.")
        return 1
    if not ok:
        print("\nVERIFY FAILED: data changed in a way no migration declared. Restore from the backup")
        print("taken before the upgrade if this was not intended.")
        return 1
    print("\nVERIFY PASSED: no rows lost, no undeclared content changes.")
    return 0


# ── new / repair / baseline-dump ───────────────────────────────────────────────

def _next_migration_version():
    versions = [0]
    for d in (JAVA_MIGRATION_DIR, MIGRATION_ROOT / "sqlite", MIGRATION_ROOT / "mysql"):
        if d.exists():
            for f in d.iterdir():
                m = re.match(r"V(\d+)__", f.name)
                if m:
                    versions.append(int(m.group(1)))
    return max(versions) + 1


def cmd_new(args):
    """Scaffold the next migration for both vendors (or one Java migration)."""
    slug = re.sub(r"[^a-z0-9]+", "_", args.description.lower()).strip("_")
    if not slug:
        raise MigrationError("description must contain letters or digits")
    version = _next_migration_version()
    created = []
    if args.java:
        cls = "V%d__%s" % (version, "_".join(w.capitalize() for w in slug.split("_")))
        path = JAVA_MIGRATION_DIR / f"{cls}.java"
        path.write_text(JAVA_MIGRATION_TEMPLATE.format(description=args.description, cls=cls))
        created.append(path)
    else:
        for vendor in ("sqlite", "mysql"):
            path = MIGRATION_ROOT / vendor / f"V{version}__{slug}.sql"
            notes = ("-- SQLite: no ALTER COLUMN / DROP CONSTRAINT; DROP COLUMN cannot touch PK/unique/indexed\n"
                     "-- columns. For those, rebuild the table (see V2__Legacy_schema_sync.java).\n"
                     if vendor == "sqlite" else
                     "-- MySQL DDL is not transactional: keep each migration small.\n")
            path.write_text(f"-- V{version}__{slug} ({vendor})\n"
                            "-- verify-allow-change:        (tables whose checksum may legitimately change)\n"
                            "-- verify-allow-drop:          (tables this migration removes)\n"
                            f"{notes}\n")
            created.append(path)
    for p in created:
        print(f"created {p.relative_to(PROJECT_ROOT)}")
    print("\nNext: write the DDL, then `./mvnw test` (fresh-DB chain + Hibernate validate),")
    print("      `db_migrate.py verify` on a pulled copy, and commit the file(s).")
    return 0


def cmd_repair(args):
    """flyway repair: clear failed history rows and realign checksums."""
    print_header("Flyway Repair")
    code, report, log = run_schema_tool(
        PROJECT_ROOT, "repair", extra_args=[*_tool_args(), "--spring.jpa.hibernate.ddl-auto=none"],
        use_jar=args.jar)
    _print_tool_report(report)
    return 0 if code in (0, 2) and not report.get("error") else 1


BASELINE_HEADER = """-- V1__baseline ({vendor})
-- Generated by: python3 scripts/db_migrate.py baseline-dump --vendor {vendor}
-- Source: Hibernate schema export of the JPA entities (ddl-auto=create), post-processed:
--   * one statement per line
--   * HT_*/HTE_* Hibernate scratch tables omitted (never created; see V3)
{vendor_note}-- Databases that predate Flyway are baseline-stamped at version 1 and never execute
-- this file (spring.flyway.baseline-on-migrate=true). Fresh databases run it in full.
-- FROZEN: Flyway checksums this file. Never edit it; add a new V<n> migration instead.
"""


def cmd_baseline_dump(args):
    """Regenerate db/migration/<vendor>/V1__baseline.sql from the entities (rare)."""
    import shutil
    import tempfile

    vendor = args.vendor
    print_header(f"Baseline dump ({vendor})")
    target = MIGRATION_ROOT / vendor / "V1__baseline.sql"
    if target.exists() and not args.force:
        raise MigrationError(f"{target} exists and is frozen (Flyway checksums it). "
                             "Pass --force only for a deliberate re-baseline.")
    tmpdir = Path(tempfile.mkdtemp(prefix="baseline_"))
    raw = tmpdir / f"baseline-{vendor}.sql"
    extra = [
        "--spring.flyway.enabled=false",
        "--spring.jpa.hibernate.ddl-auto=create",
        "--spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create",
        f"--spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target={raw}",
    ]
    env = None
    if vendor == "sqlite":
        extra.append(f"--spring.datasource.url=jdbc:sqlite:{tmpdir / 'baseline.db'}")
    else:
        if not args.mysql_url:
            raise MigrationError("--mysql-url jdbc:mysql://host:port/db is required for the mysql vendor "
                                 "(a throwaway database; nothing is written to it)")
        env = {"DB_URL": args.mysql_url, "DB_USERNAME": args.mysql_user, "DB_PASSWORD": args.mysql_password}
    code, report, log = run_schema_tool(PROJECT_ROOT, "exit", extra_args=extra, env=env, use_jar=args.jar)
    if code != 0 or not raw.exists():
        raise MigrationError(f"schema export failed (log: {log}): {report.get('error')}")
    out = []
    for line in raw.read_text().splitlines():
        line = line.strip()
        if not line or re.search(r'(?i)create table [`"]?HTE?_', line):
            continue
        if vendor == "mysql":
            line = line.replace(" jsonb", " json")
        out.append(line if line.endswith(";") else line + ";")
    note = "--   * jsonb -> json (MySQL has no jsonb type)\n" if vendor == "mysql" else ""
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(BASELINE_HEADER.format(vendor=vendor, vendor_note=note) + "\n".join(out) + "\n")
    shutil.rmtree(tmpdir, ignore_errors=True)
    print(f"wrote {target.relative_to(PROJECT_ROOT)} ({len(out)} statements)")
    return 0


# ── Disaster recovery ──────────────────────────────────────────────────────────

def cmd_roundtrip(args):
    """DR: verify the backup/restore type maps are still inverses of each other.

    mysqlbackup.py maps MySQL types down to SQLite and mysqlrestore.py maps them
    back up. They live in separate files, so nothing but this command stops them
    drifting apart -- which is exactly how the round trip previously turned every
    VARCHAR and DATETIME column into LONGTEXT.
    """
    print_header("Schema Round-Trip Check (DR type maps)")

    if args.live:
        print(f"Source: live MySQL ({describe_target()})")
        statements = read_ddl_from_mysql()
    else:
        dump = Path(args.schema) if args.schema else MIGRATION_ROOT / "mysql" / "V1__baseline.sql"
        statements = read_ddl_from_schema_dump(dump)
        print(f"Source: {dump}")

    if not statements:
        print("\nNo CREATE TABLE statements found. Pass --live to read from MySQL,")
        print("or --schema <path> to point at a dump file.")
        return 1

    print(f"Tables: {len(statements)}\n")
    ok, findings = roundtrip_report(statements)

    if findings:
        print(f"{len(findings)} problem(s) found:\n")
        for table_name, problem in findings:
            print(f"  {table_name}: {problem}")
        print(f"\n{ok}/{len(statements)} tables round-trip cleanly.")
        print("\nThe two type maps have drifted. Fix them before migrating:")
        print("  adapt_mysql_to_sqlite_schema()        in scripts/mysqlbackup.py")
        print("  build_mysql_schema_from_sqlite_table() in scripts/mysqlrestore.py")
        return 1

    print(f"All {ok} tables round-trip cleanly. The two type maps agree.")
    return 0


def cmd_backup(_args):
    """DR: back up whichever database the app is configured to use."""
    if detect_target() == "sqlite":
        import sqlite_migrate
        sqlite_migrate.backup_sqlite(SQLITE_DB_FILE, BACKUP_DIR)
        return 0

    import mysqlbackup
    mysqlbackup.main()
    return 0


def cmd_pull(args):
    """DR: run `backup` on the production host over ssh and copy the result here.

    Only meaningful for the SQLite deployment: the live database is a file on
    the server, so no laptop-side command can read it. The MySQL target is a
    network server and plain `backup` already pulls it.
    """
    import shlex
    import subprocess

    print_header("Pull SQLite backup from server")
    remote_dir = args.remote_path.rstrip("/")
    remote_cmd = f"cd {shlex.quote(remote_dir)} && python3 scripts/db_migrate.py backup"

    print(f"Host:        {args.host}")
    print(f"Remote repo: {remote_dir}")
    print(f"Running:     {remote_cmd}\n")
    result = subprocess.run(["ssh", args.host, remote_cmd], text=True, capture_output=True)
    sys.stdout.write(result.stdout)
    sys.stderr.write(result.stderr)
    if result.returncode != 0:
        raise RuntimeError(f"remote backup exited {result.returncode}; nothing copied")

    # The remote script prints "Target: <path>"; take that rather than guessing.
    remote_file = None
    for line in result.stdout.splitlines():
        if line.startswith("Target:") and "sqlite_backup_" in line:
            remote_file = line.split(":", 1)[1].strip()
    if not remote_file:
        raise RuntimeError("could not find the backup path in the remote output")

    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    local_file = BACKUP_DIR / Path(remote_file).name
    print(f"\nCopying {args.host}:{remote_file}\n     -> {local_file}")
    subprocess.run(["scp", "-q", f"{args.host}:{remote_file}", str(local_file)], check=True)

    # Verify the copy opened and has the same tables as a real backup.
    import sqlite3
    conn = sqlite3.connect(f"file:{local_file}?mode=ro", uri=True)
    try:
        n_tables = conn.execute(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table'").fetchone()[0]
        n_person = conn.execute("SELECT COUNT(*) FROM person").fetchone()[0]
    finally:
        conn.close()
        # Opening a WAL-mode file, even read-only, leaves empty sidecars behind.
        wal = local_file.with_name(local_file.name + "-wal")
        shm = local_file.with_name(local_file.name + "-shm")
        if wal.exists() and wal.stat().st_size == 0:
            wal.unlink()
        if shm.exists():
            shm.unlink()
    print(f"Local copy:  {local_file.stat().st_size:,} bytes, {n_tables} tables, {n_person} rows in person")

    if args.install:
        if SQLITE_DB_FILE.exists():
            print("\nBacking up the current local database first...")
            import sqlite_migrate
            sqlite_migrate.backup_sqlite(SQLITE_DB_FILE, BACKUP_DIR)
        import shutil
        shutil.copy2(local_file, SQLITE_DB_FILE)
        for suffix in ("-wal", "-shm"):
            side = SQLITE_DB_FILE.with_name(SQLITE_DB_FILE.name + suffix)
            if side.exists():
                side.unlink()
        print(f"Installed as {SQLITE_DB_FILE}")
        print("The next app start (or `db_migrate.py upgrade`) applies any migrations it is missing.")
    else:
        print("\nTo run the app on it:\n"
              f"  cp {local_file} {SQLITE_DB_FILE}\n"
              f"  rm -f {SQLITE_DB_FILE}-wal {SQLITE_DB_FILE}-shm\n"
              "or re-run with --install.")
    return 0


def cmd_init(args):
    """DR: wipe the database and rebuild the schema from V1..Vn with Flyway (destructive)."""
    import db_init
    return db_init.main(db_file=SQLITE_DB_FILE, use_jar=args.jar)


def cmd_restore(args):
    """DR: restore a backup into whichever database the app is configured to use."""
    if detect_target() == "sqlite":
        import sqlite_migrate
        backup_file = Path(args.backup_file) if args.backup_file else sqlite_migrate.find_latest_backup()
        if args.backup_file and not backup_file.is_absolute():
            backup_file = PROJECT_ROOT / backup_file
        if not args.backup_file:
            print(f"Using most recent backup: {backup_file}")
        sqlite_migrate.restore_sqlite(backup_file, SQLITE_DB_FILE, force=args.force)
        return 0

    import mysqlrestore

    host, port, user, password, database = get_mysql_config()

    if args.backup_file:
        backup_file = Path(args.backup_file)
        if not backup_file.is_absolute():
            backup_file = PROJECT_ROOT / backup_file
    else:
        backup_file = mysqlrestore.find_latest_backup()
        print(f"Using most recent backup: {backup_file}")

    mysqlrestore.restore_sqlite_to_mysql(
        str(backup_file), host, port, user, password, database,
        force=args.force,
        keep_target_schema=args.keep_target_schema,
        skip_safety_dump=args.skip_safety_dump,
    )
    return 0


# ── CLI ────────────────────────────────────────────────────────────────────────

def build_parser():
    parser = argparse.ArgumentParser(
        prog="db_migrate.py",
        description="Spring database schema operations.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--db", help="SQLite file to operate on instead of volumes/sqlite.db (rehearsals)")
    parser.add_argument("--jar", action="store_true",
                        help="run schema-tool commands with target/spring-*.jar instead of ./mvnw (faster)")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("status", help="target, Flyway history and pending migrations")
    sub.add_parser("check", help="Flyway validate + Hibernate validate; non-zero on drift")

    up = sub.add_parser("upgrade", help="back up, then apply pending migrations")
    up.add_argument("--no-backup", action="store_true", help="skip the backup before migrating")

    ver = sub.add_parser("verify", help="snapshot -> upgrade -> snapshot -> reconcile")
    ver.add_argument("--no-backup", action="store_true")
    ver.add_argument("--dry-run", action="store_true", help="only report pending migrations and snapshot")
    ver.add_argument("--allow-changed", help="comma-separated table globs whose content may change")
    ver.add_argument("--allow-dropped", help="comma-separated table globs that may disappear")
    ver.add_argument("--verbose", action="store_true", help="list unchanged tables too")

    new = sub.add_parser("new", help="scaffold the next migration")
    new.add_argument("description", help='short description, e.g. "add foo to bar"')
    new.add_argument("--java", action="store_true",
                     help="one Java migration in db/migration/common instead of per-vendor SQL")

    sub.add_parser("repair", help="flyway repair after a failed migration")

    bd = sub.add_parser("baseline-dump", help="regenerate V1__baseline.sql from the entities (rare)")
    bd.add_argument("--vendor", choices=("sqlite", "mysql"), required=True)
    bd.add_argument("--force", action="store_true", help="overwrite the frozen V1 (deliberate re-baseline)")
    bd.add_argument("--mysql-url", help="throwaway MySQL jdbc URL for --vendor mysql")
    bd.add_argument("--mysql-user", default="root")
    bd.add_argument("--mysql-password", default="")

    rt = sub.add_parser("roundtrip", help="DR: verify the backup/restore type maps round-trip")
    rt.add_argument("--live", action="store_true", help="read the schema from the configured MySQL server")
    rt.add_argument("--schema", help="path to a CREATE TABLE dump (default: db/migration/mysql/V1__baseline.sql)")

    sub.add_parser("backup", help="DR: verified backup of the live database")
    pull = sub.add_parser("pull", help="DR: run backup on the server over ssh and copy it here (SQLite)")
    pull.add_argument("--host", default="cockpit", help="ssh host or alias (default: cockpit)")
    pull.add_argument("--remote-path", default="open/spring",
                      help="repo path on the server, relative to the ssh login dir (default: open/spring)")
    pull.add_argument("--install", action="store_true",
                      help="also copy the pulled backup over volumes/sqlite.db (after backing up the current one)")

    sub.add_parser("init", help="DR: wipe and rebuild the schema from V1..Vn (destructive)")

    restore = sub.add_parser("restore", help="DR: load a backup into the target (destructive)")
    restore.add_argument("--backup-file", help="backup to restore (default: most recent)")
    restore.add_argument("--force", action="store_true", help="skip the confirmation prompt")
    restore.add_argument("--keep-target-schema", action="store_true",
                         help="load into the schema already in MySQL instead of recreating it "
                              "(SQLite targets always keep the target schema)")
    restore.add_argument("--skip-safety-dump", action="store_true",
                         help="do not mysqldump the target first (not recommended)")

    return parser


HANDLERS = {
    "status":        cmd_status,
    "check":         cmd_check,
    "upgrade":       cmd_upgrade,
    "verify":        cmd_verify,
    "new":           cmd_new,
    "repair":        cmd_repair,
    "baseline-dump": cmd_baseline_dump,
    "roundtrip":     cmd_roundtrip,
    "backup":        cmd_backup,
    "pull":          cmd_pull,
    "init":          cmd_init,
    "restore":       cmd_restore,
}


def main():
    args = build_parser().parse_args()
    try:
        _apply_db_override(args)
        return HANDLERS[args.command](args)
    except KeyboardInterrupt:
        print("\n\nInterrupted by user")
        return 1
    except Exception as e:
        print(f"\nAn error occurred: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main())
