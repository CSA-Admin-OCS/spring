#!/usr/bin/env python3
"""
mysql_common.py - Shared configuration and connection helpers for the MySQL
migration scripts.

Everything in here used to be copy-pasted into mysqlbackup.py, mysqlrestore.py
and db_init.py independently. Three copies of "how do we read .env and reach
the database" is three chances for them to disagree about which database they
are pointed at, which is not a mistake you want to make against production.

The type mappings themselves deliberately stay in their own scripts -- they are
large and direction-specific -- but they are inverse functions of each other,
so `roundtrip_report()` here exercises both together. Run it via:

    python3 scripts/db_migrate.py check
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# mysql.connector is imported lazily inside get_mysql_connection() so that the
# offline commands -- notably `db_migrate.py check` -- run on any machine,
# including CI, without the driver or a database being available.

PROJECT_ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = PROJECT_ROOT / ".env"
BACKUP_DIR = PROJECT_ROOT / "volumes" / "backups"

# Bookkeeping table written into every backup by mysqlbackup.py: the exact
# MySQL DDL and source row count for each table.
META_TABLE = "__migration_meta__"

# Tables that are never data: Flyway's history, SQLite's own bookkeeping, backup metadata.
SKIP_TABLES = {"flyway_schema_history", "sqlite_sequence", META_TABLE}

try:
    from mysql.connector import Error as MySQLError
except ImportError:  # driver absent -- only the offline commands can run
    class MySQLError(Exception):
        """Stand-in for mysql.connector.Error when the driver is not installed."""


def print_header(title):
    """Print a formatted header"""
    print("\n" + "=" * 60)
    print(title)
    print("=" * 60 + "\n")


def load_env_file():
    """Load environment variables from .env file"""
    env_vars = {}

    if not ENV_FILE.exists():
        print(f"Error: .env file not found at {ENV_FILE}")
        sys.exit(1)

    with open(ENV_FILE, 'r') as f:
        for line in f:
            line = line.strip()
            # Skip comments and empty lines
            if not line or line.startswith('#'):
                continue

            # Parse KEY=VALUE format
            if '=' in line:
                key, value = line.split('=', 1)
                key = key.strip()
                value = value.strip()
                # Remove quotes if present
                value = value.strip('"').strip("'")
                env_vars[key] = value

    return env_vars


def parse_jdbc_url(jdbc_url):
    """Parse JDBC URL format: jdbc:mysql://host:port/database"""
    pattern = r'jdbc:mysql://([^:/]+):(\d+)/([^?]+)'
    match = re.match(pattern, jdbc_url)

    if not match:
        print(f"Error: Invalid JDBC URL format: {jdbc_url}")
        print("Expected format: jdbc:mysql://host:port/database")
        sys.exit(1)

    host = match.group(1)
    port = int(match.group(2))
    database = match.group(3)

    return host, port, database


def get_mysql_config():
    """Get MySQL configuration from .env file.

    Returns (host, port, username, password, database).
    """
    env_vars = load_env_file()

    db_url = env_vars.get('DB_URL')
    db_username = env_vars.get('DB_USERNAME')
    db_password = env_vars.get('DB_PASSWORD')

    missing = [
        name for name, value in (
            ('DB_URL', db_url),
            ('DB_USERNAME', db_username),
            ('DB_PASSWORD', db_password),
        ) if not value
    ]
    if missing:
        print(f"Error: {', '.join(missing)} not found in {ENV_FILE}")
        sys.exit(1)

    host, port, database = parse_jdbc_url(db_url)

    return host, port, db_username, db_password, database


def get_mysql_connection(host, port, user, password, database):
    """Create MySQL connection"""
    try:
        import mysql.connector
        from mysql.connector import Error
    except ImportError:
        print("Error: mysql-connector-python is not installed in this environment.")
        print("  pip install mysql-connector-python")
        sys.exit(1)

    try:
        return mysql.connector.connect(
            host=host,
            port=port,
            user=user,
            password=password,
            database=database,
        )
    except Error as e:
        print(f"Error connecting to MySQL: {e}")
        sys.exit(1)


SQLITE_DB_FILE = PROJECT_ROOT / "volumes" / "sqlite.db"


def detect_target():
    """Return 'mysql' or 'sqlite' for the database the app is configured to use.

    application.properties defaults to `jdbc:sqlite:volumes/sqlite.db` and only
    switches to MySQL when DB_URL is set, so a commented-out DB_URL means the
    deployment is running on the local SQLite file -- which is where its real
    data then lives.
    """
    if not ENV_FILE.exists():
        return "sqlite"

    env_vars = load_env_file()
    db_url = env_vars.get("DB_URL")
    db_driver = env_vars.get("DB_DRIVER")

    if db_url and db_url.lower().startswith("jdbc:mysql:"):
        return "mysql"
    if db_driver and "mysql" in db_driver.lower():
        return "mysql"
    return "sqlite"


def describe_target():
    """One-line description of the database the scripts are pointed at."""
    if detect_target() == "sqlite":
        return f"sqlite:{SQLITE_DB_FILE}"
    host, port, user, _password, database = get_mysql_config()
    return f"{user}@{host}:{port}/{database}"


# ── Round-trip check ───────────────────────────────────────────────────────────

# Constructs that are legal in the SQLite intermediate but that MySQL rejects
# or that silently degrade the schema.
def _mysql_problems(ddl):
    problems = []

    if "DEFAULT 'NULL'" in ddl:
        problems.append("literal DEFAULT 'NULL'")
    if re.search(r"\b(LONGTEXT|TEXT|JSON|LONGBLOB|BLOB)\b[^,)]*\bDEFAULT\b", ddl, re.IGNORECASE):
        problems.append("DEFAULT on a TEXT/BLOB/JSON column (MySQL error 1101)")

    pk = re.search(r"PRIMARY KEY \(([^)]*)\)", ddl, re.IGNORECASE)
    if pk:
        for col in re.findall(r"`([^`]+)`", pk.group(1)):
            if re.search(rf"`{re.escape(col)}`\s+(LONGTEXT|TEXT|BLOB)\b", ddl, re.IGNORECASE):
                problems.append(f"un-indexable primary key column `{col}`")

    return problems


def roundtrip_report(ddl_statements):
    """Push MySQL DDL through backup -> SQLite -> restore and report the damage.

    *ddl_statements* maps table name to a CREATE TABLE statement. Returns
    (ok_count, findings) where findings is a list of (table, problem) tuples.

    This is the check that keeps the two type maps honest. They live in separate
    files and are inverses of each other; nothing but this asserts that.
    """
    import sqlite3

    from mysqlbackup import adapt_mysql_to_sqlite_schema
    from mysqlrestore import build_mysql_schema_from_sqlite_table

    con = sqlite3.connect(":memory:")
    findings = []
    ok = 0

    for table_name, mysql_ddl in ddl_statements.items():
        sqlite_ddl = adapt_mysql_to_sqlite_schema(mysql_ddl)

        try:
            con.execute(sqlite_ddl)
        except Exception as e:
            findings.append((table_name, f"SQLite rejected the translated schema: {e}"))
            continue

        rebuilt = build_mysql_schema_from_sqlite_table(con, table_name)
        if not rebuilt:
            findings.append((table_name, "restore could not rebuild a MySQL schema"))
            continue

        problems = _mysql_problems(rebuilt)
        if problems:
            findings.extend((table_name, p) for p in problems)
        else:
            ok += 1

    con.close()
    return ok, findings


def read_ddl_from_mysql():
    """SHOW CREATE TABLE for every table on the configured MySQL server."""
    host, port, user, password, database = get_mysql_config()
    conn = get_mysql_connection(host, port, user, password, database)
    statements = {}
    try:
        cursor = conn.cursor()
        cursor.execute("SHOW TABLES")
        tables = [
            row[0].decode('utf-8') if isinstance(row[0], (bytes, bytearray)) else row[0]
            for row in cursor.fetchall()
        ]
        for table_name in tables:
            cursor.execute(f"SHOW CREATE TABLE `{table_name}`")
            result = cursor.fetchone()
            if result:
                statements[table_name] = result[1]
        cursor.close()
    finally:
        conn.close()
    return statements


_CONSTRAINT_LEADERS = (
    "primary", "foreign", "unique", "key", "constraint", "check", "index",
)


def _quote_column_names(statement):
    """Backtick-quote bare column names in a CREATE TABLE statement.

    schema_full.txt is a Hibernate/SQLite-style dump with unquoted identifiers,
    but the scripts really consume MySQL `SHOW CREATE TABLE` output, where every
    identifier is quoted. Quoting here means the check exercises the same code
    path production does -- including the guard that stops type substitutions
    from rewriting a column named `timestamp`.
    """
    match = re.match(r'(?is)^(CREATE\s+TABLE\s+)`?([^\s(`]+)`?(\s*\()(.*)(\)\s*)$', statement.strip())
    if not match:
        return statement

    head, table, open_paren, body, close = match.groups()

    # Split the column list on top-level commas.
    parts, depth, current = [], 0, ""
    for ch in body:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += ch
    if current.strip():
        parts.append(current)

    rebuilt = []
    for part in parts:
        stripped = part.strip()
        first = stripped.split()[0].lower() if stripped.split() else ""
        if first in _CONSTRAINT_LEADERS or not stripped:
            # Column references inside PRIMARY KEY (...) / UNIQUE (...) are
            # identifiers too, and MySQL quotes them.
            rebuilt.append(" " + re.sub(
                r'\(([^)]*)\)',
                lambda m: "(" + ", ".join(
                    c.strip() if c.strip().startswith("`") else f"`{c.strip()}`"
                    for c in m.group(1).split(",") if c.strip()
                ) + ")",
                stripped,
            ))
            continue
        name, _, rest = stripped.partition(" ")
        rebuilt.append(f" `{name.strip('`')}` {rest}".rstrip())

    return f"{head}`{table}`{open_paren}{','.join(rebuilt)}{close}"


def read_ddl_from_schema_dump(path=None):
    """Parse CREATE TABLE statements out of schema_full.txt."""
    path = Path(path) if path else (PROJECT_ROOT / "schema_full.txt")
    if not path.exists():
        return {}

    statements = {}
    buffer = ""
    for line in path.read_text().splitlines():
        stripped = line.strip()
        if not buffer and not stripped.upper().startswith("CREATE TABLE"):
            continue
        buffer = f"{buffer} {stripped}".strip() if buffer else stripped
        if buffer.rstrip().endswith(";"):
            statement = buffer.rstrip().rstrip(";")
            match = re.match(r'(?i)CREATE\s+TABLE\s+`?([^\s(`]+)`?', statement)
            if match and not match.group(1).startswith("sqlite_"):
                statements[match.group(1)] = _quote_column_names(statement)
            buffer = ""

    return statements
