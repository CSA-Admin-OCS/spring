# Capstone mentoring: deployment note

For Dhyan. This release adds one nullable column, `person.capstone_mentoring` (JSON). It adds no tables.

**No schema rebuild is needed.** At startup, before Hibernate or any Person query runs, Spring adds the column if it
is missing. It never drops, rebuilds, or rewrites data. If the column cannot be added, Spring stops with a clear
error rather than starting with broken logins, so the database user needs `ALTER` permission on `person`.

## Steps

1. `python3 scripts/db_migrate.py status`, and confirm the target is the one you mean.
2. `python3 scripts/db_migrate.py backup`. It must exit 0. Keep the printed backup path.
3. `docker compose down`
4. `git pull`
5. `docker compose up -d --build`

Do not run `db_migrate.py init` or `restore` for this release.

## Verify

- The startup log shows `Added nullable person.capstone_mentoring column` once. Later restarts do not.
- An existing account can log in and save its profile.
- `/mvc/person/read` loads for an Admin and for a Teacher.
- `GET /api/person/capstones/mentors` returns `{}` when signed out, until a mentor is approved.
- A mentor can request a project, and a Teacher can approve it from `/mvc/person/read`.

## Rollback

Run `docker compose down`, check out the previous commit, and run `docker compose up -d --build`. Leave the column in
place: the previous code ignores it. Restore the step 2 backup only if the data itself is damaged.

## Backups

`db_migrate.py`, `sqlite_migrate.py`, `mysqlbackup.py`, and `mysqlrestore.py` copy this column with the table.
The API copies (`/api/exports`, `/api/people/bulk/extract`, `/mvc/extract`, `/mvc/import`) do not carry it. They
already leave out roles, stats, and grades, so do not use them to move mentor approvals.
