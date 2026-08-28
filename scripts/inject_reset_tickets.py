#!/usr/bin/env python3
"""Create test reset tickets by calling the real POST /mvc/person/reset/ticket
endpoint, instead of hand-writing SQL against reset_ticket.

Goes through the actual endpoint on purpose: it's idempotent per uid (won't
double-create), rate-limited to 5 requests / 15 min per uid
(ResetCode.canRequestTicket), and its schema (GenerationType.IDENTITY) has
already bitten one direct-SQL testing pass this session that never exercised
the endpoint itself -- see forgot-password-pipeline.md's "Ticket-creation
rate limiting" section for that story. Hitting the endpoint is what actually
proves the whole path (idempotency, rate limit, admin panel query) works,
not just that a row exists.

Usage:
    python3 scripts/inject_reset_tickets.py hop niko
    python3 scripts/inject_reset_tickets.py --db-check hop
    BASE_URL=http://localhost:8585 python3 scripts/inject_reset_tickets.py hop

After running, open /mvc/person/read as an admin to see the "Password Reset
Tickets" panel, or use --db-check to confirm without a browser.
"""

from __future__ import annotations

import argparse
import os
import sqlite3
import sys
from pathlib import Path
from urllib import request

BASE_URL = os.getenv("BASE_URL", "http://localhost:8585")
PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DB = PROJECT_ROOT / "volumes" / "sqlite.db"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create test reset tickets via the real reset-ticket endpoint."
    )
    parser.add_argument(
        "uids",
        nargs="+",
        help="GitHub uid(s) to raise a reset ticket for (max 5 requests per uid per 15 min)",
    )
    parser.add_argument(
        "--db-check",
        action="store_true",
        help="After creating, print each uid's open-ticket row from the DB",
    )
    parser.add_argument(
        "--db",
        default=str(DEFAULT_DB),
        help=f"SQLite DB path for --db-check (default: {DEFAULT_DB})",
    )
    return parser.parse_args()


class NoRedirectHandler(request.HTTPRedirectHandler):
    """Turn a 3xx into a raised HTTPError instead of silently following it.

    This endpoint must be reachable with zero auth (see the security-config
    comment in MvcSecurityConfig.java) -- if it's ever accidentally dropped
    from permitAll again, Spring redirects an anonymous POST to /login (302)
    instead of rejecting it, and urllib's default opener follows that
    transparently and reports the login page's 200 as if the ticket had been
    created. That exact bug shipped once already; this handler is what
    would have caught it immediately instead of needing a manual curl -i.
    """

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


OPENER = request.build_opener(NoRedirectHandler)


def create_ticket(uid: str) -> tuple[int, str]:
    url = f"{BASE_URL}/mvc/person/reset/ticket"
    body = ('{"uid":"%s"}' % uid).encode("utf-8")
    req = request.Request(
        url, data=body, method="POST", headers={"Content-Type": "application/json"}
    )
    try:
        with OPENER.open(req) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except Exception as exc:
        status = getattr(exc, "code", 0)
        body_bytes = exc.read() if hasattr(exc, "read") else b""
        return status, body_bytes.decode("utf-8", errors="replace")


STATUS_MEANING = {
    200: "created (or an open ticket already existed for this uid)",
    204: "no such uid -- person not found",
    400: "bad request -- uid missing/blank",
    302: "REDIRECTED TO LOGIN -- endpoint is requiring auth, nothing was created. "
         "Check MvcSecurityConfig has POST /mvc/person/reset/ticket in permitAll().",
    429: "rate-limited: 5 ticket-creation requests / 15 min for this uid already used",
}


def print_db_check(db_path: Path, uids: list[str]) -> None:
    if not db_path.exists():
        print(f"\n--db-check: database file not found: {db_path}")
        return

    conn = sqlite3.connect(str(db_path))
    try:
        cur = conn.cursor()
        print(f"\n--db-check ({db_path}):")
        for uid in uids:
            cur.execute(
                'SELECT id, resolved, created_at FROM reset_ticket WHERE uid = ? ORDER BY id DESC LIMIT 1',
                (uid,),
            )
            row = cur.fetchone()
            if row is None:
                print(f"  {uid}: no reset_ticket row found")
            else:
                ticket_id, resolved, created_at = row
                state = "open" if not resolved else "resolved"
                print(f"  {uid}: ticket #{ticket_id} ({state}), created {created_at}")
    finally:
        conn.close()


def main() -> int:
    args = parse_args()

    from collections import Counter
    repeated = [uid for uid, count in Counter(args.uids).items() if count > 5]
    if repeated:
        print(
            f"Note: {repeated} repeated more than 5 times, but the endpoint only "
            "allows 5 ticket-creation requests per 15 min per uid -- the rest will "
            "come back 429 in this same run. Different uids don't share a budget.\n"
        )

    for uid in args.uids:
        status, body = create_ticket(uid)
        meaning = STATUS_MEANING.get(status, "unexpected status")
        print(f"{uid}: POST /mvc/person/reset/ticket -> {status} ({meaning})")
        if status not in (200,) and body:
            print(f"  body: {body[:300]}")

    if args.db_check:
        print_db_check(Path(args.db).expanduser().resolve(), args.uids)

    return 0


if __name__ == "__main__":
    sys.exit(main())
