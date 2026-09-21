#!/usr/bin/env python3
"""
mysqlbackup.py - MySQL to SQLite Backup

Backs up all data from MySQL server and stores it locally in a SQLite backup file.
Reads MySQL connection details from .env file.

Usage:
    python3 scripts/mysqlbackup.py
    cd scripts && python3 mysqlbackup.py
"""

import os
import sys
import re
import sqlite3
from datetime import datetime
from pathlib import Path

# Configuration, connection handling and the META_TABLE name are shared with
# mysqlrestore.py and db_init.py so all three always resolve the same target.
from mysql_common import (
    MySQLError as Error,
    BACKUP_DIR,
    ENV_FILE,
    META_TABLE,
    PROJECT_ROOT,
    get_mysql_config,
    get_mysql_connection,
    load_env_file,
    parse_jdbc_url,
    print_header,
)

BACKUP_DIR.mkdir(parents=True, exist_ok=True)


def get_table_names(mysql_conn):
    """Get all table names from MySQL database and ensure they are decoded strings. (FIXED)"""
    cursor = mysql_conn.cursor()
    cursor.execute("SHOW TABLES")
    
    tables = []
    for row in cursor.fetchall():
        table_name = row[0]
        
        # FIX: Decode bytes/bytearray table names into standard strings
        if isinstance(table_name, (bytes, bytearray)):
            try:
                table_name = table_name.decode('utf-8')
            except UnicodeDecodeError as e:
                print(f"Warning: Could not decode table name {row[0]}: {e}. Skipping table.")
                continue 
        
        tables.append(table_name)
    
    cursor.close()
    return tables


def get_table_schema(mysql_conn, table_name):
    """Get CREATE TABLE statement for a table"""
    cursor = mysql_conn.cursor()
    cursor.execute(f"SHOW CREATE TABLE `{table_name}`")
    result = cursor.fetchone()
    cursor.close()
    if result:
        return result[1]
    return None


def _sub_outside_identifiers(pattern, replacement, text, flags=0):
    """re.sub, but never inside a `backtick-quoted identifier`.

    Type substitutions must not touch column names. This schema has columns
    literally named `timestamp` and `text`, and rewriting the name of one into
    the name of the other produces "duplicate column name" -- or, worse, a
    quietly renamed column.
    """
    parts = re.split(r'(`[^`]*`)', text)
    for i, part in enumerate(parts):
        if part.startswith('`') and part.endswith('`') and len(part) >= 2:
            continue  # an identifier: leave it alone
        parts[i] = re.sub(pattern, replacement, part, flags=flags)
    return ''.join(parts)


def adapt_mysql_to_sqlite_schema(mysql_schema):
    """Adapt MySQL CREATE TABLE statement to SQLite-compatible format."""
    import re

    sqlite_schema = mysql_schema

    # Preserve the Hibernate session id as a fixed-width string so it can
    # round-trip back into MySQL primary keys without type issues.
    sqlite_schema = re.sub(
        r'\bhib_sess_id\b\s+CHAR\s*\(\s*36\s*\)',
        'hib_sess_id CHAR(36)',
        sqlite_schema,
        flags=re.IGNORECASE,
    )

    # Strip MySQL versioned comments (/*!80016 DEFAULT_GENERATED */ etc.) and
    # column/table COMMENT clauses before anything else tries to parse them.
    sqlite_schema = re.sub(r'/\*!\d*\s*(.*?)\s*\*/', r'\1', sqlite_schema, flags=re.DOTALL)
    sqlite_schema = re.sub(r'\bDEFAULT_GENERATED\b', '', sqlite_schema, flags=re.IGNORECASE)
    sqlite_schema = re.sub(r"\s+COMMENT\s+'(?:[^']|'')*'", '', sqlite_schema, flags=re.IGNORECASE)

    # Handle ENUM/SET including multiline definitions before other substitutions.
    sqlite_schema = re.sub(r'\b(ENUM|SET)\s*\(.*?\)', 'TEXT', sqlite_schema, flags=re.IGNORECASE | re.DOTALL)

    # Bit-string defaults (b'0') are not SQLite literals.
    sqlite_schema = re.sub(
        r"\bDEFAULT\s+b'([01]+)'",
        lambda m: f"DEFAULT {int(m.group(1), 2)}",
        sqlite_schema,
        flags=re.IGNORECASE,
    )

    replacements = [
        # BIT(n) is how MySQL stores Hibernate booleans; keep it numeric.
        (r'\bBIT\s*\(\s*\d+\s*\)', 'INTEGER'),
        # Fractional-second precision must go with the type, otherwise DATETIME(6)
        # becomes TEXT(6) and the restore cannot tell it was ever a timestamp.
        # \b before the optional precision so a bare "TIMESTAMP NULL" keeps the
        # space that separates it from the next keyword.
        (r'\bDATETIME\b(?:\s*\(\s*\d+\s*\))?', 'TEXT'),
        (r'\bTIMESTAMP\b(?:\s*\(\s*\d+\s*\))?', 'TEXT'),
        (r'\bTIME\b\s*\(\s*\d+\s*\)', 'TEXT'),
        (r'\bTINYINT\b', 'INTEGER'),
        (r'\bSMALLINT\b', 'INTEGER'),
        (r'\bMEDIUMINT\b', 'INTEGER'),
        (r'\bINT\b', 'INTEGER'),
        (r'\bBIGINT\b', 'INTEGER'),
        (r'\bTINYTEXT\b', 'TEXT'),
        (r'\bMEDIUMTEXT\b', 'TEXT'),
        (r'\bLONGTEXT\b', 'TEXT'),
        (r'\bJSON\b', 'TEXT'),
        (r'\bJSONB\b', 'TEXT'),
        (r'\bBLOB\b', 'BLOB'),
        (r'\bLONGBLOB\b', 'BLOB'),
        (r'\bTINYBLOB\b', 'BLOB'),
        (r'\bVARCHAR\s*\(\s*\d+\s*\)', 'TEXT'),
        (r'\bCHAR\s*\(\s*\d+\s*\)', 'TEXT'),
        (r'\bUNSIGNED\b', ''),
        (r'\bZEROFILL\b', ''),
        (r'\bON\s+UPDATE\s+CURRENT_TIMESTAMP(?:\(\d+\))?\b', ''),
    ]

    for pattern, replacement in replacements:
        sqlite_schema = _sub_outside_identifiers(
            pattern, replacement, sqlite_schema, flags=re.IGNORECASE
        )

    # Drop MySQL index/key declarations; keep PK/FK/UNIQUE constraints only.
    sqlite_schema = re.sub(r',\s*(?:UNIQUE\s+)?KEY\s+`[^`]+`\s*\([^\)]*\)', '', sqlite_schema, flags=re.IGNORECASE)

    # Drop explicit MySQL constraint names but keep constraint content.
    sqlite_schema = re.sub(r'\bCONSTRAINT\s+`[^`]+`\s+', '', sqlite_schema, flags=re.IGNORECASE)

    # Remove table-level MySQL options.
    sqlite_schema = re.sub(r'ENGINE=\w+', '', sqlite_schema, flags=re.IGNORECASE)
    sqlite_schema = re.sub(r'DEFAULT CHARSET=\w+', '', sqlite_schema, flags=re.IGNORECASE)
    sqlite_schema = re.sub(r'COLLATE=\w+', '', sqlite_schema, flags=re.IGNORECASE)
    sqlite_schema = re.sub(r'AUTO_INCREMENT=\d+', '', sqlite_schema, flags=re.IGNORECASE)

    # The column-level AUTO_INCREMENT keyword is a syntax error in SQLite. Left in
    # place it fails CREATE TABLE, which used to skip the whole table -- silently
    # dropping every GenerationType.IDENTITY entity from the backup.
    sqlite_schema = re.sub(r'\s+AUTO_INCREMENT\b', '', sqlite_schema, flags=re.IGNORECASE)

    # Remove column-level collation and charset hints.
    sqlite_schema = re.sub(r'\s+COLLATE\s+[`\'"]?[a-zA-Z0-9_\-\.]+[`\'"]?', '', sqlite_schema, flags=re.IGNORECASE)
    sqlite_schema = re.sub(r'\s+CHARACTER\s+SET\s+[`\'"]?[a-zA-Z0-9_\-\.]+[`\'"]?', '', sqlite_schema, flags=re.IGNORECASE)
    sqlite_schema = re.sub(r'\s+CHARSET\s+[`\'"]?[a-zA-Z0-9_\-\.]+[`\'"]?', '', sqlite_schema, flags=re.IGNORECASE)

    # MySQL may emit charset-prefixed literals in CHECK constraints, e.g.
    # _utf8mb4'NOTE'. SQLite cannot parse these prefixes.
    sqlite_schema = re.sub(r"_utf8mb4\s*'([^']*)'", r"'\1'", sqlite_schema, flags=re.IGNORECASE)

    # Cleanup commas and whitespace.
    sqlite_schema = re.sub(r',\s*\)', ')', sqlite_schema)
    sqlite_schema = re.sub(r'\s+', ' ', sqlite_schema).strip()

    sqlite_schema = re.sub(
        r'([`"]?hib_sess_id[`"]?\s+)TEXT\b',
        r'\1CHAR(36)',
        sqlite_schema,
        flags=re.IGNORECASE,
    )

    return sqlite_schema




def _to_sqlite_value(value):
    """Convert a MySQL driver value into something sqlite3 can bind."""
    import datetime as _dt
    from decimal import Decimal

    if isinstance(value, Decimal):
        # Store exactly; float() would lose precision on money-like columns.
        return str(value)
    if isinstance(value, _dt.timedelta):
        return str(value)
    if isinstance(value, (_dt.datetime, _dt.date, _dt.time)):
        return value.isoformat(sep=' ') if isinstance(value, _dt.datetime) else value.isoformat()
    if isinstance(value, (bytes, bytearray)):
        return bytes(value)
    if isinstance(value, set):
        # MySQL SET columns come back as a Python set.
        return ','.join(sorted(str(v) for v in value))
    return value


def create_meta_table(sqlite_conn):
    """Create the bookkeeping table that records the original MySQL schema."""
    cursor = sqlite_conn.cursor()
    cursor.execute(f"DROP TABLE IF EXISTS `{META_TABLE}`")
    cursor.execute(
        f"CREATE TABLE `{META_TABLE}` ("
        "table_name TEXT PRIMARY KEY, "
        "mysql_ddl TEXT NOT NULL, "
        "source_rows INTEGER NOT NULL)"
    )
    sqlite_conn.commit()
    cursor.close()


def record_table_meta(sqlite_conn, table_name, mysql_ddl, source_rows):
    """Record one table's original MySQL DDL and source row count."""
    cursor = sqlite_conn.cursor()
    cursor.execute(
        f"INSERT OR REPLACE INTO `{META_TABLE}` (table_name, mysql_ddl, source_rows) VALUES (?, ?, ?)",
        (table_name, mysql_ddl, source_rows),
    )
    sqlite_conn.commit()
    cursor.close()


def count_mysql_rows(mysql_conn, table_name):
    """Row count for a MySQL table."""
    cursor = mysql_conn.cursor()
    try:
        cursor.execute(f"SELECT COUNT(*) FROM `{table_name}`")
        return int(cursor.fetchone()[0])
    finally:
        cursor.close()


_TEMPORAL_TYPES = ("datetime", "timestamp", "date", "time", "year")


def _select_expression(mysql_conn, table_name):
    """Column expressions for SELECT: temporal columns are CAST to CHAR so
    zero-dates and out-of-range dates come back as the exact text MySQL holds
    instead of None."""
    cursor = mysql_conn.cursor()
    try:
        cursor.execute(f"SHOW COLUMNS FROM `{table_name}`")
        exprs = []
        for row in cursor.fetchall():
            name, col_type = row[0], row[1]
            if isinstance(name, (bytes, bytearray)):
                name = name.decode("utf-8")
            if isinstance(col_type, (bytes, bytearray)):
                col_type = col_type.decode("utf-8")
            if col_type.lower().startswith(_TEMPORAL_TYPES):
                exprs.append(f"CAST(`{name}` AS CHAR) AS `{name}`")
            else:
                exprs.append(f"`{name}`")
        return exprs
    finally:
        cursor.close()


def copy_table_data(mysql_conn, sqlite_conn, table_name):
    """Copy data from MySQL table to SQLite table.

    Returns (source_rows, copied_rows). The caller treats any shortfall as a
    failed backup -- a partially copied table used to be reported as a success.
    """
    mysql_cursor = mysql_conn.cursor()
    sqlite_cursor = sqlite_conn.cursor()

    try:
        source_rows = count_mysql_rows(mysql_conn, table_name)

        # Fetch all data from MySQL. Temporal columns are read as text: the
        # connector turns MySQL zero-dates ('0000-00-00 00:00:00') into None,
        # which then fails NOT NULL in SQLite and lost every row of the table.
        select_list = ", ".join(_select_expression(mysql_conn, table_name))
        mysql_cursor.execute(f"SELECT {select_list} FROM `{table_name}`")
        columns = [desc[0] for desc in mysql_cursor.description]
        rows = [tuple(_to_sqlite_value(v) for v in row) for row in mysql_cursor.fetchall()]

        if not rows:
            print(f"  Table '{table_name}': 0 rows (empty)")
            return source_rows, 0

        # Create placeholders for INSERT statement
        placeholders = ','.join(['?' for _ in columns])
        columns_str = ','.join([f'`{col}`' for col in columns])

        # Insert data into SQLite
        insert_sql = f"INSERT INTO `{table_name}` ({columns_str}) VALUES ({placeholders})"

        try:
            sqlite_cursor.executemany(insert_sql, rows)
            sqlite_conn.commit()
            copied = len(rows)
        except Exception as bulk_error:
            # Fall back to row-at-a-time so one bad row cannot lose the batch,
            # and report precisely which rows failed instead of hiding it.
            sqlite_conn.rollback()
            print(f"  Bulk insert failed ({bulk_error}); retrying row by row...")
            copied = 0
            first_error = None
            for row in rows:
                try:
                    sqlite_cursor.execute(insert_sql, row)
                    copied += 1
                except Exception as row_error:
                    if first_error is None:
                        first_error = row_error
            sqlite_conn.commit()
            if first_error is not None:
                print(f"  First row error: {first_error}")

        if copied != source_rows:
            print(f"  Table '{table_name}': {copied}/{source_rows} rows copied  <-- INCOMPLETE")
        else:
            print(f"  Table '{table_name}': {copied} rows copied")

        return source_rows, copied

    except Exception as e:
        print(f"  Error copying table '{table_name}': {e}")
        sqlite_conn.rollback()
        try:
            return count_mysql_rows(mysql_conn, table_name), 0
        except Exception:
            return -1, 0
    finally:
        mysql_cursor.close()


def backup_mysql_to_sqlite(host, port, user, password, database, backup_file):
    """Backup MySQL database to SQLite file"""
    print_header("MySQL Backup to SQLite")
    
    # Connect to MySQL
    print(f"Connecting to MySQL: {user}@{host}:{port}/{database}")
    mysql_conn = get_mysql_connection(host, port, user, password, database)
    
    # Create SQLite backup file
    print(f"\nCreating SQLite backup file: {backup_file}")
    if backup_file.exists():
        backup_file.unlink()
    
    sqlite_conn = sqlite3.connect(str(backup_file))
    
    try:
        # Get all tables
        print("\nFetching table list...")
        tables = get_table_names(mysql_conn)
        print(f"Found {len(tables)} tables")

        create_meta_table(sqlite_conn)

        # Copy schema and data for each table
        print("\nCopying tables...")
        failures = []          # (table, reason) - table is missing or incomplete
        total_source = 0
        total_copied = 0

        for table_name in tables:
            print(f"\nProcessing table: {table_name}")

            # Get MySQL schema
            mysql_schema = get_table_schema(mysql_conn, table_name)
            if not mysql_schema:
                print(f"  Warning: Could not get schema for '{table_name}', skipping")
                failures.append((table_name, "could not read MySQL schema"))
                continue

            # Adapt schema for SQLite
            sqlite_schema = adapt_mysql_to_sqlite_schema(mysql_schema)

            # Create table in SQLite
            try:
                sqlite_cursor = sqlite_conn.cursor()
                sqlite_cursor.execute(f"DROP TABLE IF EXISTS `{table_name}`")
                sqlite_cursor.execute(sqlite_schema)
                sqlite_conn.commit()
                sqlite_cursor.close()
            except Exception as e:
                print(f"  Error creating table '{table_name}' in SQLite: {e}")
                print(f"  Translated schema was: {sqlite_schema}")
                failures.append((table_name, f"SQLite CREATE TABLE failed: {e}"))
                continue

            # Copy data
            source_rows, copied_rows = copy_table_data(mysql_conn, sqlite_conn, table_name)
            total_source += max(source_rows, 0)
            total_copied += copied_rows

            # Keep the original MySQL DDL so the restore is an exact rebuild
            # rather than a guess derived from SQLite type affinities.
            record_table_meta(sqlite_conn, table_name, mysql_schema, source_rows)

            if source_rows != copied_rows:
                failures.append(
                    (table_name, f"only {copied_rows} of {source_rows} rows copied")
                )

        print_header("Backup Summary")
        print(f"Backup file:    {backup_file}")
        print(f"Tables in source: {len(tables)}")
        print(f"Rows in source:   {total_source}")
        print(f"Rows copied:      {total_copied}")

        if failures:
            print(f"\n{len(failures)} table(s) did NOT back up completely:")
            for table_name, reason in failures:
                print(f"  - {table_name}: {reason}")
            raise RuntimeError(
                f"Backup incomplete: {len(failures)} table(s) failed. "
                "This backup must not be used as a migration source."
            )

        print("\nAll tables and all rows accounted for.")

    finally:
        mysql_conn.close()
        sqlite_conn.close()


def main():
    """Main backup process"""
    # Get MySQL configuration from .env
    host, port, username, password, database = get_mysql_config()
    
    # Generate backup filename with timestamp
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    backup_file = BACKUP_DIR / f"mysql_backup_{timestamp}.db"
    
    try:
        backup_mysql_to_sqlite(
            host,
            port,
            username,
            password,
            database,
            backup_file
        )
    except KeyboardInterrupt:
        print("\n\nBackup interrupted by user")
        sys.exit(1)
    except Exception as e:
        print(f"\nAn error occurred: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()