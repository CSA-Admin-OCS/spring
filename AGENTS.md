# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## Commands

Build / run / test (Maven wrapper, Java 21):
- `./mvnw clean compile` — compile
- `./mvnw spring-boot:run` — run locally on http://127.0.0.1:8585 (websocket on 8589)
- `./mvnw test` — run all tests
- `./mvnw test -Dtest=GradeTest` — run a single test class
- `./mvnw test -Dtest=GradeTest#methodName` — run a single test method
- `./mvnw package` — produce `target/spring-0.0.1-SNAPSHOT.jar`

Docker (mirrors prod): `docker compose up -d --build` (binds 127.0.0.1:8585 and :8589, mounts `./volumes`).

Database migration scripts (Python; need a venv and `ADMIN_PASSWORD` in `.env`):
- `python scripts/db_init.py` — wipe + reseed local SQLite from `ModelInit`
- `python scripts/db_prod2local.py` — pull prod data into local SQLite via API
- `python scripts/db_local2prod.py` — push local SQLite to prod via API (requires prod admin password)
- `python scripts/db_mysql2local.py` / `db_local2mysql.py` — direct MySQL ↔ SQLite sync (set `FORCE_YES=true` for non-interactive)

Entity-removal helpers (run before any large cleanup):
- `./audit2.sh` — scans code + DB for usage of legacy entities (User, StudentResponse, etc.) and prints a SAFE-TO-DELETE verdict
- `./delete.sh` — removes only the entities `audit2.sh` cleared; rerun `./mvnw clean compile` after

## Quick links

- DB migrations/scripts and `.env` setup: [README.md](README.md) and [scripts/](scripts/)
- Auth/JWT cookie flow: `JwtApiController` sets `jwt_java_spring` on POST `/authenticate`; `JwtRequestFilter` reads it on `/api/**` (local cookie overrides in `.env`, see [README.md](README.md))
- WebSocket endpoints: native `/websocket` in [src/main/java/com/open/spring/mvc/mortevision/nativesocket/WebSocketConfig.java](src/main/java/com/open/spring/mvc/mortevision/nativesocket/WebSocketConfig.java); group chat STOMP `/ws-chat` with `/app` + `/topic` in [src/main/java/com/open/spring/mvc/groups/WebSocketBrokerConfig.java](src/main/java/com/open/spring/mvc/groups/WebSocketBrokerConfig.java) and port gating in [src/main/java/com/open/spring/mvc/groups/ChatWebSocketPortFilter.java](src/main/java/com/open/spring/mvc/groups/ChatWebSocketPortFilter.java)
- Frontend templates/static assets: [src/main/resources/templates/](src/main/resources/templates/) (per-domain + layouts) and [src/main/resources/static/](src/main/resources/static/); Backend UI notes in [README.md](README.md)
- Testing/build verification: commands above + Verification section; run `./mvnw clean compile` and `./mvnw test` before schema or auth changes

## Groups & realtime chat

- Course groups are the canonical uppercase list `CSA, CSP, CSH, CSSE`, configured as `courses.class-groups` plus `courses.periods[NAME]` in [application.properties](src/main/resources/application.properties) and bound by [CourseGroupProperties.java](src/main/java/com/open/spring/mvc/groups/CourseGroupProperties.java). That one config block drives both the `Groups` rows seeded by `ModelInit` and the class list `ClassGroupMembershipService` validates against, so course names and bell periods only need changing in one place. A single period can be overridden in `.env` (`courses.periods[CSA]=4`) because Spring merges map entries per key, whereas overriding `courses.class-groups` replaces the entire list. List order matters — it is the order memberships are returned in. Startup fails if a listed group has no period. Profile class selection syncs via `PUT /api/groups/class-memberships` (`GroupsApiController` → `ClassGroupMembershipService.syncMemberships`), which only adds/removes membership in those four groups and leaves all other groups untouched.
- Realtime chat uses STOMP over a **second connector on port 8589**: broker config in `WebSocketBrokerConfig.java` (`/ws-chat`, `/app`, `/topic`), port gating in `ChatWebSocketPortFilter.java`, presence in `GroupChatPresenceService.java`. The native `/websocket` endpoint in `mvc/mortevision/nativesocket/WebSocketConfig.java` is separate.
- Gotcha: Java package is `mvc.groups` (plural) but templates live in `templates/group/` (singular); static JS exists both as legacy files in `static/js/group-*.js` and packaged versions in `static/js/group/*.js` — prefer the latter.

## Architecture

Single Spring Boot app, root package `com.open.spring`, three layers:

- **`system/`** — cross-cutting infra. `Main` is the entry point. `DatabaseModeEnvironmentPostProcessor` runs *before* the context starts and rewrites `spring.jpa.properties.hibernate.dialect` + JDBC driver based on whether `DB_URL` looks like SQLite or MySQL — this is how the same JAR runs on dev (SQLite) and prod (MySQL). `DatabaseInitializer` is a standalone resetter used by `scripts/db_init.py`. `ModelInit` seeds default users/data on startup. `MvcConfig` holds CORS, the `BCryptPasswordEncoder` bean, and static/file-upload resource handlers.
- **`security/`** — cookie-based JWT auth. `POST /authenticate` (JwtApiController) returns the JWT in an httpOnly `jwt_java_spring` cookie (12h, SameSite=None in prod). `JwtRequestFilter` reads that cookie on every `/api/*` request and populates the SecurityContext via `PersonDetailsService`. `RateLimitFilter` caps 1000 req/min/IP (5000 for admins). There is no Authorization-header bearer flow.
- **`mvc/`** — ~70 feature subpackages (person, groups, jokes, bathroom, assignments, quiz, games, blackjack, plant, S3uploads, …). The convention per domain is JPA `@Entity` + `*JpaRepository` + `*ApiController` (REST under `/api/...`) and optionally `*ViewController` (Thymeleaf under `/`) and a service class for non-trivial logic. Follow this pattern when adding a new domain.

Two ports are intentional: **8585** is the main HTTP/Tomcat connector; **8589** is a second connector wired to the websocket handler at `mvc/mortevision/nativesocket/WebSocketConfig` (group chat realtime). Nginx (`nginx_spring_8585_8589.conf`) terminates TLS for both: 443→8585, 8589→8589 with websocket upgrade headers and 86400s timeouts.

Hibernate runs with `ddl-auto=none` and globally quoted identifiers (because tables like `groups` are SQL reserved words). Schema changes are not auto-applied — they go through `DatabaseInitializer` / the migration scripts.

Thymeleaf templates live under `src/main/resources/templates/` with one folder per domain plus a `layouts/` folder for shared fragments. Static assets are in `src/main/resources/static/`.

## Configuration & environment

`application.properties` carries production-safe defaults; `.env` (gitignored, loaded by `java-dotenv`) overrides them locally. The required keys are listed in the README's `.env` block — at minimum `ADMIN_PASSWORD`, `DEFAULT_PASSWORD`, and (for local dev) `jwt.cookie.secure=false`, `jwt.cookie.same-site=Lax`. CORS in `application.properties` allows `*.opencodingsociety.com` plus localhost on 4500/4599/4600/8585.

The SQLite DB lives at `volumes/sqlite.db` (WAL mode); `volumes/backups/` holds timestamped backups. Both `volumes/sqlite.db*` and `.env` are gitignored.

## Database update workflow (production)

When changing schema or seed data, follow this order — out-of-order steps will diverge prod and local:
1. Local: `python scripts/db_init.py` (verify schema migrates cleanly)
2. Local: `python scripts/db_prod2local.py` (test against real data)
3. Test thoroughly
4. On prod (cockpit, in the spring directory): `cp volumes/sqlite.db volumes/backups/sqlite_YYYY-MM-DD.db`, `docker compose down`, `git pull`, `python scripts/db_init.py`, `docker compose up -d --build`
5. Local: swap `ADMIN_PASSWORD` to the prod value in `.env`, then `python scripts/db_local2prod.py`

## Verification

After meaningful changes, run all of:
- `./mvnw clean compile` — catches Lombok/annotation-processor breakage
- `./mvnw test`
- `./mvnw spring-boot:run`, then `curl http://127.0.0.1:8585/api/jokes/` (smoke test, no auth required) and authenticate via `POST /authenticate` with `{"uid":"toby","password":"<ADMIN_PASSWORD>"}` to confirm the JWT cookie flow still works

## Coding Conventions & Agent Notes

To ensure consistency in this repository, agents should adhere to the following conventions:

- **Testing**: The project relies on plain JUnit 5 and Mockito (`@Mock`, `@InjectMocks`) for unit tests. Spring context testing (e.g., `@WebMvcTest`, `@SpringBootTest`) is generally avoided. Prefer isolating layers and testing logic directly using Mockito.
- **Entity & Code Style**: There is no enforcement via Checkstyle or Spotless. However, classes (especially entities) heavily utilize Lombok (`@Data`, `@NoArgsConstructor`, `@AllArgsConstructor`). Avoid generating manual getters, setters, or standard constructors for entities.
- **Naming/Keywords**: `spring.jpa.properties.hibernate.globally_quoted_identifiers=true` is enabled. You can safely use SQL reserved words (like `groups`) as table or column names, as Hibernate will quote them automatically.
- **Deployment**: There are no GitHub Action workflows currently managing CI/CD. The application isolates deployment to `docker-compose up -d --build` leveraging the root `Dockerfile` and mounting `./volumes` to persist SQLite data and backups locally.
