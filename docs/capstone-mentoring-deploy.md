# Capstone mentoring: deployment note

For Dhyan. This release adds one nullable column, `person.capstone_mentoring` (JSON). It adds no tables.

**The column must exist before the new code starts.** Spring runs with `ddl-auto=none`, so without the column
every Person query fails, including login.

## Steps

Use the normal schema-change sequence:

1. `python3 scripts/db_migrate.py status`, and confirm the target is the one you mean.
2. `python3 scripts/db_migrate.py backup`. It must exit 0. Keep the printed backup path.
3. `docker compose down && git pull`
4. `python3 scripts/db_migrate.py init`. This rebuilds the schema and is destructive.
5. `python3 scripts/db_migrate.py restore --backup-file <path>`. On MySQL, add `--keep-target-schema`.
6. `docker compose up -d --build`

The restore report will say `person: new columns left at default: capstone_mentoring` on the first deploy. That is
expected: every existing person starts with `NULL`, which means no choices.

## Verify

- The restore report shows no row-count failures.
- An existing account can log in and save its profile.
- `/mvc/person/read` loads for an Admin and for a Teacher.
- `GET /api/person/capstones/mentors` returns `{}` when signed out.
- A mentor can request a project, and a Teacher can approve it from `/mvc/person/read`.

## Rollback

Redeploy the previous image. Leave the column in place: old code ignores a nullable column, so no data step is
needed. Restore from the step 2 backup only if the data itself is damaged.

## What was tested

Backup, `init`, and restore were run twice on a local SQLite copy with 19 people. Row counts for every table in the
new schema matched, as did `uid`, `name`, `email`, `sid`, `password`, `roles_json`, `stats`, and `grades_json`. An
approved and a pending request both survived the second round unchanged.

**Not tested on MySQL.** If production uses MySQL, run the same sequence on a copy first.

## Backups

`db_migrate.py`, `sqlite_migrate.py`, `mysqlbackup.py`, and `mysqlrestore.py` copy this column with the table.
The API copies (`/api/exports`, `/api/people/bulk/extract`, `/mvc/extract`, `/mvc/import`) do not carry it. They
already leave out roles, stats, and grades, so do not use them to move mentor approvals.
