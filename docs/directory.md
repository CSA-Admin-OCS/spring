# Sprint 1 account directory

The Pages Sprint 1 source bundle is integrated into `com.open.spring.mvc.directory`.
Visit `/login` using an existing administrator account, then `/mvc/directory`.
The shared navigation includes an administrator-only Account directory link.

The directory supports listing, viewing, creating, editing and deleting student
and guest entries. Name and email are required; school, student ID and GitHub
username are optional. IDs and UTC creation timestamps are server-managed.
Student IDs are strings, preserving leading zeros. All directory routes require
`ROLE_ADMIN`; mutation requests require a session CSRF token. Existing API security
and legacy MVC form behavior are preserved.

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
