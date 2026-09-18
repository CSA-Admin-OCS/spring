# Sprint 1 account directory

The Pages Sprint 1 source bundle is integrated into `com.open.spring.mvc.directory`.
Visit `/login` using an existing administrator account, then `/mvc/data/directory`.
The shared navigation includes an administrator-only Account directory link.

The directory supports listing, viewing, creating, editing and deleting student
and guest entries. Name and email are required; school, student ID and GitHub
username are optional. IDs and UTC creation timestamps are server-managed.
Student IDs are strings, preserving leading zeros. All directory routes require
`ROLE_ADMIN`; mutation requests require a session CSRF token. The API security
chain remains Order 1; the session-based MVC chain is Order 2. MVC rejects
cross-origin requests, including origins allowed by the API. Login and logout
POSTs also require CSRF tokens; the navigation submits logout as an HTML form.
Other legacy MVC endpoints retain their existing CSRF exemptions.

The current route prefix is `/mvc/data/directory`. The September 9 portfolio
examples using `/mvc/directory` predate this correction; use the routes below
when running the integrated backend.

| Method | Route | Result |
| --- | --- | --- |
| GET | `/mvc/data/directory` | Directory list |
| GET | `/mvc/data/directory/new` | Creation form |
| POST | `/mvc/data/directory` | Create and redirect |
| GET | `/mvc/data/directory/{id}` | Account detail |
| GET | `/mvc/data/directory/{id}/edit` | Edit form |
| POST | `/mvc/data/directory/{id}` | Update and redirect |
| POST | `/mvc/data/directory/{id}/delete` | Delete and redirect |

These are separate directory records, not login accounts. Adding a guest does not
create credentials, grant roles or reset passwords. The original POJO and expanded
Lombok example informed the entity; the teaching examples, screenshots and Pages
pom.xml are not runtime source files and were not copied into Spring.

## Database setup

Hibernate remains configured with `ddl-auto=none`. Before using the directory on
an existing database, back it up using `python scripts/db_migrate.py backup`, then
apply `scripts/migrations/directory-sqlite.sql` to that SQLite database, or
`scripts/migrations/directory-mysql.sql` to the configured MySQL database. Both are
additive and create only `directory_accounts`. Use the database's SQL client to
execute the matching file. Do not enable create-drop on retained data.

For new databases, the existing DatabaseInitializer discovers the entity along
with the other domain entities. Its full-reset workflow is destructive and is
unnecessary merely to add this table.

## Verification

Run `./mvnw clean compile` and `./mvnw test`. DirectoryAccountControllerTest uses
standalone MVC and Mockito to check validation, mass-assignment protection,
preserved timestamps, missing IDs and deletion without a Spring application context.

For browser verification, start Spring, log in as an administrator and create a
synthetic student with ID `001234`. Edit the school, confirm the timestamp is
unchanged, then delete the entry. Repeat with a guest and optional fields blank.
Check that a non-admin is denied and a POST without a CSRF token returns 403.
The forms use Thymeleaf's automatic CSRF hidden input with `th:action`.

For local HTTP session login, set `server.servlet.session.cookie.secure=false`
and `server.servlet.session.cookie.same-site=Lax` in your local environment, along
with the README's local JWT cookie settings.

On Windows PowerShell, select Java 21 before running Maven:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21.0.11'
./mvnw.cmd spring-boot:run
```

Then visit `http://localhost:8585/login` and sign in with your existing
administrator account. The directory is at
`http://localhost:8585/mvc/data/directory`.

`scripts/verify-directory.mjs` exercises real HTTP form login, Thymeleaf output,
CRUD, validation, CSRF, CORS, logout and the existing API login. Run it only
against a disposable local test database with synthetic credentials:

```powershell
$env:DIRECTORY_BASE_URL = 'http://127.0.0.1:18585'
$env:DIRECTORY_ADMIN_UID = '<test admin uid>'
$env:DIRECTORY_ADMIN_PASSWORD = '<test admin password>'
node scripts/verify-directory.mjs
```

The script retains only the MVC session cookie while testing directory pages,
so a JWT cannot accidentally satisfy the session-authentication checks.

### Recorded results (September 10, 2026)

- Java 21 clean compilation passed.
- Full Maven suite passed: 33 tests, zero failures or errors, including five
  directory controller tests and two tests of the actual MVC security chain.
- SQLite migration passed an in-memory check for repeatability, preservation of
  existing rows and student IDs with leading zeros. MySQL execution was not tested.
- Application startup was attempted but stopped because the existing
  GeminiChatTestController requires a missing `.env` file. This checkout also
  has no `volumes/sqlite.db`. Browser CRUD and JWT smoke tests therefore remain
  unverified; no existing database was reset or production data accessed.

### Verified results (September 15, 2026)

- Java 21 clean compilation passed; the subsequent error-dispatch fix also compiled.
- Final full Maven suite: **21 tests, zero failures or errors**. This is the count
  in the current checkout, superseding the earlier recorded count.
- Full Spring Boot application started on ports 18585/18589 with a separate
  SQLite database at `target/directory-verification.db` and synthetic credentials.
- `node scripts/verify-directory.mjs` passed all checks: actual session form
  login; list/detail/edit HTML; hidden CSRF inputs; student and guest CRUD;
  server-rendered validation errors; escaped user input; leading-zero student
  IDs; immutable ID/creation time; missing-record 404; missing/invalid CSRF 403;
  same-origin access and cross-origin GET/POST/preflight rejection; logout.
- MVC was exercised using only the session cookie. A JWT without a session
  redirected to login. API authentication still issued a JWT; `/api/jokes/`
  returned 401 anonymously and 200 with that JWT (the current API policy requires
  authentication, despite the older AGENTS.md smoke-test note).
- Anonymous CSRF failures now retain HTTP 403 through the servlet error dispatch
  instead of becoming a login redirect. A regression test covers this behavior.
- The additive SQLite migration passed repeatability and preservation checks.
  It was applied to the existing local `volumes/sqlite.db`, which lacked the
  directory table, after backing it up to
  `volumes/backups/directory-before-20260915-1789493880901.db`.
  Row counts in all 39 pre-existing user tables were unchanged.
- Local `.env` now sets the session cookie to `secure=false`, `same-site=Lax`
  for HTTP development. Production defaults are unchanged.

Runtime verification used `ddl-auto=none` after initially creating the disposable
test schema. Restarting that large test schema with `ddl-auto=update` hit SQLite's
compound-SELECT metadata limit; do not use that setting as a migration strategy.
MySQL execution and browser screenshot capture were not performed. The temporary
verification server was stopped after testing. Local logs are under `target/`:
`directory-http-verification.log`, `compile-verification.log`,
`test-verification.log`, and `runtime-verification.log`.
