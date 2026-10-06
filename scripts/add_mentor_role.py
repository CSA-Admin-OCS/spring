#!/usr/bin/env python3
"""Ensure ROLE_MENTOR and ROLE_PENDING exist in the local SQLite database.

Unlike add_student_role_all_users.py, this does not assign the role to every
user -- mentors are approved one account at a time via the admin "Manage
Roles" page. This script only seeds the person_role rows those assignments
depend on (PersonDetailsService.addRoleToPerson silently no-ops without them).

Usage:
    python3 scripts/add_mentor_role.py
    python3 scripts/add_mentor_role.py --apply
    python3 scripts/add_mentor_role.py --db /path/to/sqlite.db --apply
"""

from __future__ import annotations

import argparse
import sqlite3
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DB = PROJECT_ROOT / "volumes" / "sqlite.db"
ROLE_NAMES = ("ROLE_MENTOR", "ROLE_PENDING")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Seed ROLE_MENTOR and ROLE_PENDING into person_role."
    )
    parser.add_argument(
        "--db",
        default=str(DEFAULT_DB),
        help=f"Path to SQLite DB (default: {DEFAULT_DB})",
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Apply updates. Without this flag, runs in dry-run mode.",
    )
    return parser.parse_args()


def ensure_required_tables(cur: sqlite3.Cursor) -> None:
    required = {"person_role"}
    cur.execute("SELECT name FROM sqlite_master WHERE type='table'")
    existing = {row[0] for row in cur.fetchall()}
    missing = sorted(required - existing)
    if missing:
        raise RuntimeError(f"Missing required table(s): {', '.join(missing)}")


def get_role_id(cur: sqlite3.Cursor, role_name: str) -> int | None:
    cur.execute("SELECT id FROM person_role WHERE name = ?", (role_name,))
    row = cur.fetchone()
    return int(row[0]) if row else None


def create_role(cur: sqlite3.Cursor, role_name: str) -> int:
    cur.execute("INSERT INTO person_role(name) VALUES (?)", (role_name,))
    return int(cur.lastrowid)


def main() -> int:
    args = parse_args()
    db_path = Path(args.db).expanduser().resolve()

    if not db_path.exists():
        print(f"Error: database file not found: {db_path}")
        return 1

    conn = sqlite3.connect(str(db_path))
    try:
        cur = conn.cursor()
        ensure_required_tables(cur)

        print(f"Database: {db_path}")

        missing_roles = []
        for role_name in ROLE_NAMES:
            role_id = get_role_id(cur, role_name)
            if role_id is None:
                missing_roles.append(role_name)
                print(f"{role_name}: missing")
            else:
                print(f"{role_name}: already exists (id={role_id})")

        if not missing_roles:
            print("Nothing to do.")
            return 0

        if not args.apply:
            print(f"Dry run only. Re-run with --apply to create: {', '.join(missing_roles)}")
            return 0

        for role_name in missing_roles:
            role_id = create_role(cur, role_name)
            print(f"Created {role_name} (id={role_id})")

        conn.commit()
        return 0

    except Exception as exc:
        conn.rollback()
        print(f"Error: {exc}")
        return 1
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
