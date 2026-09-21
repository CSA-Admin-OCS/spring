#!/usr/bin/env python3
"""Shared migration helpers for the Spring Boot database scripts.

run_schema_tool() is the bridge to the application's schema-tool mode
(--schema.tool=<mode>, see SchemaToolRunner.java): it boots the app far enough to
run Flyway and Hibernate validation, parses the SCHEMA_TOOL_JSON line it prints,
and returns (exit_code, report_dict, log_path).
"""

from __future__ import annotations

import os
import shutil
import signal
import socket
import subprocess
import time
from datetime import datetime
from pathlib import Path


class MigrationError(RuntimeError):
    """Raised when a migration step fails."""


# ── Schema tool (Flyway + Hibernate validate inside the real app) ─────────────

SCHEMA_TOOL_MARKER = "SCHEMA_TOOL_JSON "
SCHEMA_TOOL_ERROR = "SCHEMA_TOOL_ERROR "

# Every tool invocation: no seeding/S3/shutdown exports, random ports so it can run
# beside a live server (SQLite WAL allows concurrent readers), devtools off.
_TOOL_BASE_ARGS = [
    "--app.bootstrap.enabled=false",
    "--server.port=0",
    "--socket.port=0",
    "--spring.devtools.restart.enabled=false",
    "--logging.level.root=warn",
]


def _tool_command(project_root: Path, run_args: list[str], jar: Path | None) -> list[str]:
    if jar is not None:
        return ["java", "-Dspring.devtools.restart.enabled=false", "-jar", str(jar), *run_args]
    return [
        "./mvnw", "-q", "-B", "spring-boot:run",
        "-Dspring-boot.run.jvmArguments=-Dspring.devtools.restart.enabled=false",
        "-Dspring-boot.run.arguments=" + " ".join(run_args),
    ]


def find_packaged_jar(project_root: Path) -> Path | None:
    """The boot jar under target/, if one has been built (much faster than mvnw)."""
    for candidate in sorted((project_root / "target").glob("spring-*.jar")):
        if not candidate.name.endswith(".original"):
            return candidate
    return None


def run_schema_tool(
    project_root: Path,
    mode: str,
    extra_args: list[str] | None = None,
    log_file: Path | None = None,
    use_jar: bool = False,
    timeout_seconds: int = 900,
    env: dict | None = None,
):
    """Boot the app in schema-tool mode and return (exit_code, report, log_path).

    `report` is the parsed SCHEMA_TOOL_JSON line, or a dict with "error" when the
    application failed before reaching the tool (e.g. Hibernate validation failure --
    the message is extracted from the log so callers can print it).
    """
    import json
    import re

    run_args = [f"--schema.tool={mode}", *_TOOL_BASE_ARGS, *(extra_args or [])]
    jar = find_packaged_jar(project_root) if use_jar else None
    cmd = _tool_command(project_root, run_args, jar)

    log_file = log_file or (project_root / "volumes" / "logs" / f"schema_tool_{mode}.log")
    log_file.parent.mkdir(parents=True, exist_ok=True)
    full_env = dict(os.environ)
    if env:
        full_env.update(env)
    with open(log_file, "w") as log:
        try:
            proc = subprocess.run(cmd, cwd=project_root, stdout=log, stderr=subprocess.STDOUT,
                                  timeout=timeout_seconds, env=full_env)
            code = proc.returncode
        except subprocess.TimeoutExpired:
            return 1, {"error": f"schema tool timed out after {timeout_seconds}s"}, log_file

    text = log_file.read_text(encoding="utf-8", errors="replace")
    report = None
    for line in text.splitlines():
        if line.startswith(SCHEMA_TOOL_MARKER):
            report = json.loads(line[len(SCHEMA_TOOL_MARKER):])
        elif line.startswith(SCHEMA_TOOL_ERROR):
            report = {"ok": False, "error": line[len(SCHEMA_TOOL_ERROR):]}
    if report is None:
        # The app never reached the tool: surface the most useful line from the log.
        msg = None
        for pattern in (r"Schema-validation: .*", r"Migration .* failed.*", r"Caused by: .*Flyway.*",
                        r"Caused by: .*Exception: .*", r"ERROR .*"):
            m = re.search(pattern, text)
            if m:
                msg = m.group(0).strip()
                break
        report = {"ok": False, "error": msg or f"no {SCHEMA_TOOL_MARKER.strip()} line in {log_file}"}
        code = code or 1
    return code, report, log_file


def print_header(title: str) -> None:
    print("\n" + "=" * 60)
    print(title)
    print("=" * 60 + "\n")


def is_port_in_use(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        return sock.connect_ex(("localhost", port)) == 0


def ensure_port_not_in_use(port: int, hint: str) -> None:
    if is_port_in_use(port):
        raise MigrationError(f"Port {port} is already in use. {hint}")


class SqliteDatabaseFiles:
    """Manages SQLite database file backup and deletion."""

    def __init__(self, db_file: Path, backup_dir: Path):
        self.db_file = db_file
        self.backup_dir = backup_dir

    def backup(self) -> None:
        if not self.db_file.exists():
            print("No existing database file to backup")
            return

        self.backup_dir.mkdir(parents=True, exist_ok=True)
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        backup_file = self.backup_dir / f"sqlite_backup_{timestamp}.db"
        shutil.copy2(self.db_file, backup_file)
        print(f"Database backed up to: {backup_file}")

        for ext in ("-wal", "-shm"):
            src = Path(str(self.db_file) + ext)
            if src.exists():
                dst = self.backup_dir / f"sqlite_backup_{timestamp}.db{ext}"
                shutil.copy2(src, dst)
                print(f"{ext.lstrip('-').upper()} file backed up")

    def remove(self) -> None:
        print("\nRemoving old database...")
        for ext in ("", "-wal", "-shm"):
            target = Path(str(self.db_file) + ext)
            if target.exists():
                target.unlink()
        print("Old database removed")


class SkipModelInitFlag:
    """Manages the .skip-modelinit flag lifecycle."""

    def __init__(self, flag_file: Path):
        self.flag_file = flag_file

    def create(self) -> None:
        self.flag_file.parent.mkdir(parents=True, exist_ok=True)
        self.flag_file.touch()
        print(f"Created skip-modelinit flag at {self.flag_file}")

    def remove(self) -> None:
        if self.flag_file.exists():
            self.flag_file.unlink()
            print("Removed skip-modelinit flag")


class SpringBootSchemaRunner:
    """Runs Spring Boot temporarily to create schema and then shuts it down."""

    def __init__(
        self,
        project_root: Path,
        log_file: str,
        spring_port: int,
        additional_run_args: list[str] | None = None,
    ):
        self.project_root = project_root
        self.log_file = log_file
        self.spring_port = spring_port
        self.additional_run_args = additional_run_args or []

    def run(self, timeout_seconds: int = 180, settle_seconds: int = 5) -> None:
        process = self._start()
        try:
            self._wait_for_start(timeout_seconds, settle_seconds)
        finally:
            self._stop(process)

    def _start(self) -> subprocess.Popen:
        run_args = ["--spring.jpa.hibernate.ddl-auto=create", *self.additional_run_args]
        run_args_value = " ".join(run_args)

        return subprocess.Popen(
            [
                "./mvnw",
                "spring-boot:run",
                f"-Dspring-boot.run.arguments={run_args_value}",
            ],
            stdout=open(self.log_file, "w"),
            stderr=subprocess.STDOUT,
            cwd=self.project_root,
            preexec_fn=os.setsid,
        )

    def _wait_for_start(self, timeout_seconds: int, settle_seconds: int) -> None:
        print("Waiting for Spring Boot", end="", flush=True)
        start_time = time.time()

        while time.time() - start_time < timeout_seconds:
            if is_port_in_use(self.spring_port):
                print(" OK")
                time.sleep(settle_seconds)
                return
            print(".", end="", flush=True)
            time.sleep(1)

        print("\nTimeout waiting for Spring Boot to start")
        raise MigrationError(f"Application failed to start. Check {self.log_file} for errors")

    def _stop(self, process: subprocess.Popen) -> None:
        print("\nStopping temporary instance...")
        try:
            os.killpg(os.getpgid(process.pid), signal.SIGTERM)
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(os.getpgid(process.pid), signal.SIGKILL)
            process.wait()
        time.sleep(2)
        print("Temporary instance stopped")
