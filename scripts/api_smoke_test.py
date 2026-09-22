#!/usr/bin/env python3
import os
import sys
import time
from http import cookiejar
from urllib import request, parse

BASE_URL = os.getenv("BASE_URL", "http://localhost:8585")
UID = os.getenv("UID", "toby")
PASSWORD = os.getenv("PASSWORD", "Admin14*&*41")

# The signup check inserts a real account, so it is opt-in: set SMOKE_CREATE=1.
# Leave it off against production unless you intend to keep the account.
CREATE_ACCOUNT = os.getenv("SMOKE_CREATE") == "1"

FAILURES = []


def fail(message):
    FAILURES.append(message)
    print(f"  FAIL: {message}")

COOKIE_JAR = cookiejar.CookieJar()
OPENER = request.build_opener(request.HTTPCookieProcessor(COOKIE_JAR))


def http_request(method, url, data=None, headers=None):
    if headers is None:
        headers = {}
    req_data = None
    if data is not None:
        req_data = data.encode("utf-8")
    req = request.Request(url, data=req_data, headers=headers, method=method)
    try:
        with OPENER.open(req) as resp:
            return resp.status, resp.read(), resp.headers
    except Exception as exc:
        if hasattr(exc, "code"):
            return exc.code, b"", getattr(exc, "headers", {})
        raise


def print_status(label, method, path, json_body=None):
    url = f"{BASE_URL}{path}"
    headers = {}
    data = None
    if json_body is not None:
        headers["Content-Type"] = "application/json"
        data = json_body
    status, body, resp_headers = http_request(method, url, data=data, headers=headers)
    location = resp_headers.get("Location") if hasattr(resp_headers, "get") else None
    if location:
        print(f"{label} -> {status} (Location: {location})")
    else:
        print(f"{label} -> {status}")
    if status >= 400 and body:
        try:
            text = body.decode("utf-8", errors="replace")
        except Exception:
            text = "<non-text body>"
        print("  Error body:", text[:500])
    return status, body


def dump_cookies():
    if not COOKIE_JAR:
        print("Cookies: <none>")
        return
    print("Cookies:")
    for cookie in COOKIE_JAR:
        print(f"  {cookie.name}={cookie.value}; path={cookie.path}; secure={cookie.secure}")


def check_api_never_returns_html():
    """An /api request must never be answered with the HTML login page.

    A container ERROR dispatch re-enters the security filter chain as /error, which
    the API chain's securityMatcher does not match. Before this was fixed, the MVC
    chain caught it and form login replied 302 -> /login, so the frontend's
    fetch().json() got "<!DOCTYPE html>" instead of the real error. Both probes below
    take that path deliberately and neither creates anything.
    """
    print("\n== API must never return HTML (regression: error-dispatch escape) ==")
    probes = [
        ("404 path", "POST", "/api/person/create/", "{}"),
        ("rejected token", "POST", "/api/person/create", "{}"),
    ]
    for label, method, path, body in probes:
        url = f"{BASE_URL}{path}"
        headers = {"Content-Type": "application/json"}
        if label == "rejected token":
            headers["Cookie"] = "jwt_java_spring=not.a.real.token"
        status, payload, resp_headers = http_request(method, url, data=body, headers=headers)
        location = resp_headers.get("Location") if hasattr(resp_headers, "get") else None
        ctype = resp_headers.get("Content-Type", "") if hasattr(resp_headers, "get") else ""
        print(f"{label} ({method} {path}) -> {status}"
              + (f" (Location: {location})" if location else ""))
        if status in (301, 302, 303, 307, 308) and location and "/login" in location:
            fail(f"{label}: redirected to the login page instead of returning an API response")
        elif "text/html" in (ctype or ""):
            fail(f"{label}: returned HTML instead of JSON")


def check_signup():
    """The endpoint that went down: a genuinely new account must insert cleanly.

    Uses a unique uid so this exercises the INSERT rather than the duplicate check --
    a stale *_seq counter only fails at insert time, which is what took signup down.
    """
    print("\n== Signup (regression: id generator behind its table) ==")
    if not CREATE_ACCOUNT:
        print("  skipped -- set SMOKE_CREATE=1 to run it (creates a real account)")
        return
    uid = "smoketest%d" % int(time.time())
    body = ('{"name":"Smoke Test","uid":"%s","sid":"%s","email":"%s@example.com",'
            '"password":"123Qwerty!","kasm_server_needed":false}') % (uid, uid, uid)
    status, payload = print_status("POST /api/person/create", "POST", "/api/person/create",
                                   json_body=body)
    if status == 200:
        print(f"  created {uid} -- remove it at /mvc/person/delete when you are done")
    else:
        fail(f"signup returned {status}; a new account could not be created")


def main():
    print("== Authenticate (JWT cookie) ==")
    auth_body = '{"uid":"%s","password":"%s"}' % (UID, PASSWORD)
    status, body = print_status("POST /authenticate", "POST", "/authenticate", json_body=auth_body)
    if body:
        try:
            print("Auth response:", body.decode("utf-8"))
        except Exception:
            print("Auth response: <non-text body>")
    dump_cookies()

    print("\n== Person APIs (auth required) ==")
    print_status("GET /api/person/get", "GET", "/api/person/get")
    print_status("GET /api/people", "GET", "/api/people")

    print("\n== Admin-only check ==")
    # Probes the ROLE_ADMIN rule on DELETE /api/person/**, NOT the deletion itself.
    # This used to target person 6 while defaulting to the admin credentials in .env,
    # so pointing the script at production could delete a real person. The id below
    # is deliberately one that will never exist: an authorized caller gets 404 and an
    # unauthorized one gets 401/403, which is the only thing this check is asking.
    print_status("DELETE /api/person/<nonexistent>", "DELETE", "/api/person/999999999")

    print("\n== Analytics (auth required) ==")
    print_status("GET /api/analytics/", "GET", "/api/analytics/")

    print("\n== Code Runner (auth required) ==")
    print_status("GET /api/challenge-submission/my-submissions", "GET", "/api/challenge-submission/my-submissions")

    print("\n== Tinkle (auth required) ==")
    print_status("GET /api/tinkle/all", "GET", "/api/tinkle/all")

    print("\n== Export/Import (admin only) ==")
    print_status("GET /api/exports/getAll", "GET", "/api/exports/getAll")
    print_status("GET /api/imports/backups", "GET", "/api/imports/backups")

    check_api_never_returns_html()
    check_signup()

    print()
    if FAILURES:
        print(f"FAILED: {len(FAILURES)} regression check(s)")
        for message in FAILURES:
            print(f"  - {message}")
        return 1
    print("Done. All regression checks passed.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(2)
        sys.exit(1)
