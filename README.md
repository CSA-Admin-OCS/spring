# Runtime links

Backend UI

Use login with .env setup user to manage and restore data.

- Runtime link: https://spring.opencodingsociety.com/

API access

Validate system is up by testing an endpoint

- Jokes endpoint: https://spring.opencodingsociety.com/api/jokes/

Examine JWT Login

Review cookies after accessing a page that needs them (ie Groups)

- JWT Login: https://pages.opencodingsociety.com/login

## Backend UI purpose

This Backend UI is to manage adminstrative functions like reseting passwords and managing database content: CRUD, Backup, and Restore.

- Thymeleaf UI should be visual and practical
- Home page is organized with Bootstrap menu and cards
- Most menus and operations are dedicated to Tables
- Some sample menus exist to reference basic capability

## Backend Primary purpose

The site is build on Springboot.  The project is primarly used to store and retrieve data through APIs.  The site has JWT authorization and implements security.  In optimal deployed form the data would be served through a professional database, it supports SQLite for development and deployment verification.

## Getting started

Java 21 or higher is requirement using VSCode tooling.

- Install Java 21: **macOS** `brew install --cask temurin@21` | **Linux** `sudo apt install openjdk-21-jdk`
- Clone project, open in VSCode
- Run `Main.java` (if issues: `Ctrl+Shift+P` → "Java: Reload Projects")
- Browse to http://127.0.0.1:8585/

**Build Commands:**
```bash
./mvnw clean compile    # Build
./mvnw test            # Test  
./mvnw spring-boot:run # Run
```

**Key Files:** Java source (`src/main/java/...`) | templates and application.properties (`src/main/resources/templates/...`)

### Configuration Requirements

- Create custom `.env` file to setup default user passwords to satisfy code in Person.java.  Students of OCS should leave users as default until competency is obtained.

```java
final String adminPassword = dotenv.get("ADMIN_PASSWORD");
final String defaultPassword = dotenv.get("DEFAULT_PASSWORD");
```

- Modify `application.properties` ports to be unique for your indivdual project.

```text
server.port=8585
socket.port=8589
```

## Run Project

- Play or click entry point is Main.java, look for Run option in code.  This eanbles Springboot to build and load.
    - If you do not see the `Run | Debug` option in code, install the **Java Extension Pack** (by Microsoft) and **Spring Boot Extension Pack** (by VMware)
- Load loopback:port in browser (http://127.0.0.1:8585/)
- Login to ADMIN (toby) user using ADMIN_PASSWORD, examing menus and data
- Try API endpoint: http://127.0.0.1:8585/api/jokes/


## IDE management

- Extension Pack for Java from the Marketplace, you may need to close are restart VSCode
- A ".gitignore" can teach a Developer a lot about Java runtime.  A target directory is created when you press play button, byte code is generated and files are moved into this location.
- "pom.xml" file can teach you a lot about Java dependencies.  This is similar to "requirements.txt" file in Python.  It manages packages and dependencies.

## .env files

The `.env` file provides local environment-specific configuration that overrides `application.properties`. This file is excluded from git (via `.gitignore`) to prevent committing sensitive credentials and local settings.

**How it works:**
- Spring Boot loads `application.properties` first (production defaults)
- Then imports `.env` which overrides those values
- Properties in `.env` take precedence over `application.properties`

**Required .env setup for local development:**

```bash
# Default password and reset passwor
DEFAULT_PASSWORD=123Qwerty!

# Admin user defaults
ADMIN_NAME=Thomas Edison
ADMIN_UID=toby
ADMIN_EMAIL=toby@example.com
ADMIN_SID=0000001
ADMIN_PASSWORD=123Toby!
ADMIN_PFP=/images/toby.png

# Teacher user defaults
TEACHER_NAME=Nikola Tesla
TEACHER_UID=niko
TEACHER_EMAIL=niko@example.com
TEACHER_SID=0000002
TEACHER_PASSWORD=123Niko!
TEACHER_PFP=/images/niko.png

# Default user for testing 
USER_NAME=Grace Hopper
USER_UID=hop
USER_EMAIL=hop@example.com
USER_SID=0000003
USER_PASSWORD=123Hop!
USER_PFP=/images/hop.png

# Convience user defaults
MY_NAME=John Mortensen
MY_UID=jm1021
MY_SID=0000004
MY_EMAIL=jmort1021@gmail.com

# JWT Cookie Settings - Local Development (HTTP)
# These override the production defaults in application.properties
jwt.cookie.secure=false
jwt.cookie.same-site=Lax

# API Keys (optional - defaults exist in application.properties)
GAMIFY_API_URL=https://api.openai.com/v1/chat/completions
GAMIFY_API_KEY=your-openai-api-key-here
GEMINI_API_KEY=your-gemini-api-key-here
GITHUB_API_TOKEN=your-github-token-here

# Email Configuration (optional - overrides application.properties)
# spring.mail.username=your-email@gmail.com
# spring.mail.password=your-app-password

# S3 Bucket Defaults
AWS_BUCKET_NAME=your-bucket-name
AWS_ACCESS_KEY_ID=your-access-key
AWS_SECRET_ACCESS_KEY=your-secret-key
AWS_REGION=us-east-2
```

**Production Configuration:**
- Production uses the secure defaults from `application.properties` (HTTPS settings)
- No `.env` file needed on production unless overriding specific values
- Use environment variables on production servers if preferred (e.g., `JWT_COOKIE_SECURE=true`)

**Important:** Never commit the `.env` file to git. It contains sensitive credentials and local-only settings.

## Person MVC

![Class Diagram](https://github.com/user-attachments/assets/26219a16-e3dc-45e3-af1c-466763957dce)

- Basically there is a rough MVCframework.
- The webpages act as the view. These pages can view details about the users, and request the controller to change details about them
- The controller is mainly "personViewController" for the backend, but other controllers include "personApiController" for the front end.
- Techincally the image is wrong, "personDetailsService" is a controller. It is used by other controllers to change the database, so it seemed more accurate to call it a part of the model, rather than a controller.
- The person.java is the pojo (object) that is used for the database schema.


## Database Management Workflow with Scripts

> **Which database am I on?** `application.properties` defaults to
> `jdbc:sqlite:volumes/sqlite.db` and only switches to MySQL when `DB_URL` is set. If
> `DB_URL` is commented out in `.env`, the deployment is running on that **local SQLite
> file**, and that file — not RDS — holds the live data. Always run
> `python3 scripts/db_migrate.py status` first; every command follows the same detection,
> so `backup`, `init` and `restore` all act on whichever database the app itself uses.

Two deployment shapes are supported, and the scripts handle both. See
**SQLite deployment** below for the current one; the MySQL procedure that follows applies
once `DB_URL` is set and RDS becomes the live database again.

> **Where does `backup` run?** It reaches over the network only when the target is
> MySQL. On SQLite the database is a file on the server's disk, so `backup` has to run
> **on the server**. From a laptop, use `python3 scripts/db_migrate.py pull`, which runs
> the backup on cockpit over ssh and copies the result into `volumes/backups/` locally.
> Running `backup` on a laptop with `DB_URL` unset backs up the *laptop's* `volumes/sqlite.db`.

Note: the MySQL procedure assumes production is on RDS. Be sure all PRs are merged, pulled
and tested before you touch production either way.

### MySQL deployment (historical -- applies only when `DB_URL` points at RDS)

0. Set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` in `.env` (pointing at production RDS) and
   create a venv with `mysql-connector-python` installed. `mysqldump` must be on PATH.

   Confirm what you are pointed at, and that the schema translation is sound:
   > python3 scripts/db_migrate.py status
   > python3 scripts/db_migrate.py check

1. Pull production into a local SQLite backup. This records production's exact MySQL DDL
   and row counts alongside the data, and **exits non-zero if any table or row is missing**.
   > python3 scripts/db_migrate.py backup

   The backup lands in `volumes/backups/mysql_backup_<timestamp>.db`. Do not proceed if
   this command fails -- an incomplete backup is not a valid migration source.

   Known data quirk: RDS holds MySQL zero-dates (`0000-00-00 00:00:00`) in the NOT NULL
   `ocs_analytics.created_at` and `progress.last_updated` columns. The backup reads
   temporal columns as text so those rows survive verbatim; a restore into a MySQL with
   `NO_ZERO_DATE` in `sql_mode` will reject them until the data is cleaned.

2. Point your local app at that backup (or at `volumes/sqlite.db`) and TEST TEST TEST.
   Make sure the new code works with real production data.

3. Verify the new schema builds cleanly on a scratch MySQL database first, if you have one.

4. On production (cockpit, `open/spring`):
   - Take spring down: `docker compose down`
   - Update code: `git pull`
   - Rebuild the schema with Hibernate (native MySQL DDL, no cross-dialect translation):
     `python3 scripts/db_migrate.py init`

5. Load your data on top of the new schema:
   > python3 scripts/db_migrate.py restore --keep-target-schema --backup-file volumes/backups/mysql_backup_<timestamp>.db

   This takes a `mysqldump` rollback point before touching anything, loads only the columns
   the old and new schemas share (reporting added/dropped columns), and re-counts every
   table afterwards. It exits non-zero if MySQL does not match the backup.

6. Bring spring up: `docker compose up -d --build`

### Restoring production exactly as it was (rollback)

`mysqlrestore.py` without `--keep-target-schema` rebuilds each table from the MySQL DDL
recorded in the backup -- types, indexes, UNIQUE keys and foreign keys included -- so it
reproduces the source database rather than approximating it:

> python3 scripts/db_migrate.py restore --backup-file volumes/backups/mysql_backup_<timestamp>.db

The `mysqldump` safety dump taken before any destructive run is the faster rollback:

> mysql -h <host> -P <port> -u <user> -p <database> < volumes/backups/predrop_<db>_<timestamp>.sql

### Notes on what the scripts guarantee

- **All tables, including Hibernate Envers audit tables** (`HT_*`, `HTE_*`) and the
  Hibernate id-allocation tables (`*_seq`). The app runs with
  `spring.jpa.hibernate.ddl-auto=none`, so Hibernate will *not* recreate anything that
  gets dropped -- losing `*_seq` would restart id allocation at 1 and collide with
  existing rows, and losing `HTE_*` breaks every write to an audited entity.
- **Row-count reconciliation** on both directions. Any shortfall is a non-zero exit,
  never a printed warning.
- Older backups that predate the recorded-DDL format still restore, via a fallback that
  derives types from SQLite. That path is lossy and the script says so loudly; prefer
  `--keep-target-schema`.

## SQLite deployment (current state)

With `DB_URL` unset, production data lives in `volumes/sqlite.db` **on cockpit**. The
sequence is the same shape as the MySQL one, and `db_migrate.py` picks the SQLite code
paths for you.

**On your laptop first** -- get a copy of production and test the new code against it:

```bash
python3 scripts/db_migrate.py status              # confirm Mode: SQLITE (DB_URL commented out)
python3 scripts/db_migrate.py check               # type maps still round-trip
python3 scripts/db_migrate.py pull --install      # ssh cockpit, run backup there, copy it here,
                                                  # install it as volumes/sqlite.db
./mvnw spring-boot:run                            # TEST TEST TEST on real data
```

`pull` defaults to ssh host `cockpit` and repo path `open/spring`; override with
`--host` and `--remote-path`. Without `--install` it only lands the file in
`volumes/backups/` and prints the `cp` to run. With `--install` it backs up your current
local database first.

**On cockpit, in `open/spring`** -- the actual migration:

```bash
python3 scripts/db_migrate.py status              # confirm Mode: SQLITE
python3 scripts/db_migrate.py backup              # must exit 0
docker compose down
git pull
python3 scripts/db_migrate.py init                # fresh schema from the JPA entities
python3 scripts/db_migrate.py restore --backup-file volumes/backups/sqlite_backup_<ts>.db
docker compose up -d --build
```

`init` runs Spring Boot through `./mvnw` outside Docker, so the server needs Java 21 and
network access for Maven.

`backup` uses SQLite's online backup API, not a file copy. The database runs in WAL mode,
so a `cp` of `sqlite.db` can silently miss everything still sitting in the `-wal` file.

`restore` keeps the schema `init` just built and loads only the columns both sides share.
Columns added by your change take their defaults; columns and tables removed by it are
listed explicitly rather than being dropped in silence. `*_seq` tables come across intact,
so Hibernate keeps allocating ids where it left off instead of restarting at 1.

Rolling back is a file copy, because the backup *is* a complete database:

```bash
docker compose down
cp volumes/backups/sqlite_backup_<ts>.db volumes/sqlite.db
rm -f volumes/sqlite.db-wal volumes/sqlite.db-shm
docker compose up -d
```

### How the scripts fit together

`db_migrate.py` is the only entry point you need:

| Command | What it does |
| --- | --- |
| `status` | Prints the configured target and what is currently in it |
| `check` | Round-trips the schema through both translators and fails if they disagree |
| `backup` | Verified backup of the live database (MySQL or SQLite); on SQLite, run it on the server |
| `pull` | Runs `backup` on the server over ssh and copies the file here (SQLite only) |
| `init` | Rebuilds the schema on the target with Hibernate |
| `restore` | Loads a backup back into the target |

Underneath, `mysqlbackup.py` / `mysqlrestore.py` (MySQL) and `sqlite_migrate.py` (SQLite)
hold the backup and restore implementations and can be run directly -- `db_migrate.py` calls straight into them, so
there is one implementation, not two. Everything shared between them (reading `.env`,
parsing `DB_URL`, opening connections, the backup metadata table name) lives in
`mysql_common.py`, so the two scripts cannot disagree about which database they are
talking to.

**Why `check` exists.** The MySQL-to-SQLite and SQLite-to-MySQL type maps are inverse
functions living in two different files. Nothing structural forces them to stay inverse,
and when they drifted the round trip quietly turned every `VARCHAR` and `DATETIME` column
into `LONGTEXT`. `check` runs every table in `schema_full.txt` through both directions and
fails on any degradation. It needs no database and no driver, so it is safe to run
anywhere, including CI. Run it after any change to either map.

`schema_full.txt` is a point-in-time snapshot, so it goes stale as entities are added.
It is fixture data for `check` and nothing else — no part of the application reads it.
To check against the schema that actually exists right now:

> python3 scripts/db_migrate.py check --live

### Removed scripts

`db_prod2local.py`, `db_local2prod.py`, `db_mysql2local.py`, `db_local2mysql.py` and
`db_prod_to_mysql.py` predated the MySQL migration and have been **deleted**. If you find
one in an old branch or a stale checkout, do not run it. `db_local2mysql.py` in particular
carried its own independent MySQL writer that received none of the schema, safety-dump or
row-count fixes -- running it against production would reintroduce every bug those fixes
addressed. They remain recoverable from git history if you ever need to read them.

The current scripts are: `db_migrate.py` (entry point), `mysql_common.py` (shared config
and connections), `mysqlbackup.py` / `mysqlrestore.py` (MySQL), `sqlite_migrate.py`
(SQLite), `db_init.py` (schema rebuild) and `migration_utils.py` (Spring Boot runner).

These seven migration files are byte-identical to the ones in `spring`. A fix applied to one
repo belongs in the other -- check both before you consider a migration bug closed.

### The full runbook

The step-by-step production procedure -- with the gates, the rollback paths, and the audit
of what was wrong with the old scripts -- lives at
<https://pages.opencodingsociety.com/documentation/migration-runbook>. Read it before your
first migration; this section is the summary.

# Testing Grade FRQs API with Postman

## Step 1: Authenticate

**POST** `http://127.0.0.1:8585/authenticate`

**Headers:** `Content-Type: application/json`

**Body:**
```json
{
  "uid": "toby",
  "password": "123Toby!"
}
```

**Action:** Send request → Copy `jwt_java_spring` token from Cookies tab

## Step 2: Grade FRQs

**POST** `http://127.0.0.1:8585/api/grade-frqs`

**Headers:** `Cookie: jwt_java_spring=YOUR_TOKEN_HERE`

**Action:** Send request
