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
- [docs/group-chat-architecture.md](docs/group-chat-architecture.md) covers every groups and chat route, the WebSocket event contexts, S3 message storage (keyed by group name, not id) and the known issues. Over the WebSocket there is no Spring Security context, so a service that needs the signed-in account (like `GroupChatIdentityService`) only works from REST. HTTP: `api/groups.http`.

## Assignment grading

The grading policy lives in one place, [SubmissionGradingService.java](src/main/java/com/open/spring/mvc/assignments/SubmissionGradingService.java), with closing in `AssignmentClosureService` and teacher reviews in `GradeReviewService`; read their class comments before changing grading.
- A submission keeps the AI grade, a manual override and the effective grade apart. New content is a new `revision` that releases the override; every grading write names its revision.
- Before close, teachers and student teachers (assignment creators, assigned graders) override and request AI re-grades. `POST /api/assignments/{id}/close` (teacher-only, no reopen) finalizes every grade and copies it to `SynergyGrade`. After close only a teacher's `GradeReview` (`/api/grade-reviews/**`: reason, evidence, decision with rationale) can change a grade.
- Grading and content columns are mapped `updatable = false`: never set them on an entity and `save()`; use the service, whose conditional UPDATEs take the row lock first (`lockForGrading`, a no-op UPDATE, because SQLite has no `SELECT ... FOR UPDATE` and a read-then-write transaction fails with `SQLITE_BUSY`). Don't save a new `GenerationType.AUTO` entity after taking a lock: its id comes from a sequence table on another connection, which on SQLite waits on that lock.
- Services return a typed `GradingOutcome`; `GradingResponse.statusOf` is the only outcome-to-status mapping, and the pages hold the text (`static/js/grading/common.js` here, `assets/js/submissions/grading.js` in Pages). Tests check both cover every outcome.
- Tests: `./mvnw test -Dtest='Submission*Test,Grad*Test,AssignmentSubmission*Test'`. `SubmissionGradingPersistenceTest` runs the rules and races on a real SQLite file with the `.env` test accounts hop (student) and niko (teacher).

## Architecture

Single Spring Boot app, root package `com.open.spring`, three layers:

- **`system/`** — cross-cutting infra. `Main` is the entry point. `DatabaseModeEnvironmentPostProcessor` runs *before* the context starts and rewrites `spring.jpa.properties.hibernate.dialect` + JDBC driver based on whether `DB_URL` looks like SQLite or MySQL — this is how the same JAR runs on dev (SQLite) and prod (MySQL). `DatabaseInitializer` is a standalone resetter used by `scripts/db_init.py`. `ModelInit` seeds default users/data on startup. `MvcConfig` holds CORS, the `BCryptPasswordEncoder` bean, and static/file-upload resource handlers.
- **`security/`** — cookie-based JWT auth. `POST /authenticate` (JwtApiController) returns the JWT in an httpOnly `jwt_java_spring` cookie (12h, SameSite=None in prod). `JwtRequestFilter` reads that cookie on every `/api/*` request and populates the SecurityContext via `PersonDetailsService`. `RateLimitFilter` caps 1000 req/min/IP (5000 for admins). User JWT authentication has no Authorization-header bearer flow; the Toolchain local agent uses a separate session-scoped bearer token validated by `ToolchainSessionService`.
- **`mvc/`** — ~70 feature subpackages (person, groups, jokes, bathroom, assignments, quiz, games, blackjack, plant, S3uploads, …). The convention per domain is JPA `@Entity` + `*JpaRepository` + `*ApiController` (REST under `/api/...`) and optionally `*ViewController` (Thymeleaf under `/`) and a service class for non-trivial logic. Follow this pattern when adding a new domain.
- `mvc/announcement` is the smallest package with all five standard parts, named like the newer Toolchain and Calendar packages (`*ApiRestController`, `*Entity`, `*JpaRepository`, `*Service`, `*TableInitializer`); see [docs/announcement-architecture.md](docs/announcement-architecture.md). Its `/api/announcements` routes, JSON field order and status codes are a contract: `AnnouncementEntity` pins `@Entity(name = "Announcement")` so Hibernate keeps using `announcement_seq`. Tests: `./mvnw test -Dtest='Announcement*Test'`; HTTP: `api/announcement.http`.

### CS Pathway

- CS Pathway and Toolchain Trail Java types are consolidated in `src/main/java/com/open/spring/mvc/toolchain/`. This package includes scores, help, setup reports, and interactive station-agent sessions. The older report-upload flow and live station-agent flow have different lifecycles and distinct persistence, even though their code is now together. Neither local client writes directly to SQL.
- Use [docs/toolchain-architecture.md](docs/toolchain-architecture.md) as the guide for the unified package, local agent flow, routes, persistence, security, and naming. It is linked from the README so developers and coding agents can discover it.
- Keep JavaDoc in each Java type concise and readable inline as plain prose. Add method JavaDoc for public behavior or constraints that are not self-evident; avoid HTML and Markdown in source comments. Keep the architecture guide current when behavior changes.
- DTO records are API or view transport shapes; JPA entities map persisted rows; `JpaRepository` interfaces provide persistence operations; services own business rules and integration; REST controllers handle HTTP contracts; `ToolchainMvcController` supplies the Thymeleaf administrative view. Do not describe all pathway endpoints as full CRUD: they expose specific use cases. Keep existing `/api/cs-pathway` and `/api/toolchain` contracts stable unless coordinating an explicit client migration.
- Toolchain uses the active `Person` account model and `PersonJpaRepository` for identity/display-name lookups, not a student table. "Student" is a user role; `ToolchainScoresService.StudentRecord` is only a dashboard DTO.
- Use the topic-based type families `ToolchainHelp*`, `ToolchainReport*`, `ToolchainReview*`, `ToolchainSession*`, and `ToolchainScores*`. `ToolchainStationRules` evaluates station evidence, `ToolchainHelpExceptionTooSoon` represents the help cooldown, and `ToolchainStatsJpaRepository` accesses shared score rows. Do not reintroduce the old HelpRequest, SetupReport, SetupReview, or AgentSession type families.
- Keep API-specific request/response records nested in their API controller, and service-owned result records nested in their service. Use short contextual names and Javadoc identifying the DTO's purpose, rather than separate `ToolchainDto*` files. Callers and tests import the nested types; preserve JSON component names when reorganizing Java types.
- The web-to-machine pairing pattern is reusable, but its current implementation is Toolchain-specific. Session routes remain under `/api/toolchain/sessions`, with pairing at `/api/toolchain/pair`; the table remains `toolchain_session`. Codes expire after 5 minutes and agent tokens after 4 hours by default, configured in `application.properties`. Spring evaluates evidence only. Before local commands run, the external agent must require explicit user approval and validate the station against its predefined checks; this local approval gate is not enforced by Spring.
- API examples are in [api/cspathway.http](api/cspathway.http) and [api/toolchain.http](api/toolchain.http). Run focused Toolchain tests with `./mvnw test '-Dtest=Toolchain*Test'`. Keep endpoint and database compatibility names unchanged during Java-only renames.

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
