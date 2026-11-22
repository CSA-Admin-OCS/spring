# Calls Architecture Guide

## Overview

`mvc/calls` is the Spring side of video calls on OCS. A call happens in a **room**, and each group,
DM conversation and course has exactly one room, made the first time someone opens it and reused for
every call after. This first part of the feature stores rooms and decides who may join them. Join
tokens, the safety rules during a call and the "call is live" frames come in later changes; the
media itself will go through a LiveKit server, never through Spring.

Calls are **off by default**. With `calls.enabled=false` every `/api/calls` route answers
404 `{"reason":"calls_disabled"}`, even to a request that would otherwise be refused, and the
`call_room` table is not created. Nothing changes on a server until someone turns calls on.

## The parts

| Class | Job |
|---|---|
| `CallApiRestController` | `/api/calls` routes. Checks the off switch, the page origin and the signed-in person, then asks `CallRoomService`. |
| `CallRoomService` | Who may join a room, and the find-or-create of each context's room. |
| `CallRoomEntity`, `CallRoomJpaRepository` | One row per group, DM or course (`call_room`). |
| `CallRoomTableInitializer` | Creates `call_room` on boot when calls are on (ddl-auto is `none`). |
| `CallRequestGuard` | Only OCS pages may call the routes: `X-Origin: client`, an exact origin from `calls.allowed-origins`, JSON on writes. |
| `CallExceptionHandler` | Every refusal as `{"reason": ...}`; every answer is 404 `calls_disabled` while calls are off. |
| `CallsProperties`, `CallPolicySettings` | The `calls.*` and `calls.policy.*` settings. |

## Who may join a room

- **Group room**: the group's members.
- **DM room**: the conversation's participants (`DirectMessageService.isParticipant`).
- **Course room** (`CSA`, `CSP`, ...): members of that course's class group, the group named in
  `courses.class-groups`.
- **Anywhere**: anyone with a role in `calls.policy.supervisor-roles` (teachers and admins), who may
  watch over any room.

Membership is read from the group, the DM or the class group, so a rule those already have applies
to their calls with no calls code. Role names come only from `application.properties`.

## REST operations

Every route needs a signed-in person (the default `/api/**` rule in `SecurityConfig`) and the page
headers the guard checks. Errors are `{"reason": ...}`.

| Route | Answers |
|---|---|
| `POST /api/calls` `{"contextType":"GROUP","contextKey":"12","purpose":"standup"}` | 200 `{"id","state","title","contextType","contextKey","purpose"}`; 400 `UNKNOWN_CONTEXT_TYPE`, `UNKNOWN_PURPOSE` or `invalid_body`; 403 `NOT_A_MEMBER`; 404 `UNKNOWN_CONTEXT` (no such group or DM, or a course not in `courses.class-groups`) |
| `GET /api/calls/{id}` | 200 the same fields; 403 `NOT_A_MEMBER`; 404 `CALL_NOT_FOUND` |
| any route | 401 `unauthorized`, 403 `origin_rejected`, 404 `calls_disabled`; 503 `CALLS_UNAVAILABLE` with `Retry-After: 30` when the database fails (for example, `call_room` couldn't be created) |

- `contextType` is `GROUP`, `DM` or `COURSE`. `contextKey` is the group's or DM's id, or the course
  (`csa` and `CSA` are the same room).
- `purpose` is one of `calls.purposes` (`meeting`, `standup`); without one the room gets the first.
  It is chosen when a room opens: opening an idle room again changes it, and a live call keeps the
  purpose it started with.
- `title` is the group's or course's name, and `null` for a DM, whose page names it.
- The LiveKit room name (a random UUID) and who first opened the room are never sent.
- Two people opening a context's first room at the same moment both get the same room: the unique
  key refuses the second insert, and the controller reads the first one's row.

## Persistence

`call_room` has `context_type` and `context_key` unique together, and `room_name` unique.
`CallRoomTableInitializer` runs `CREATE TABLE IF NOT EXISTS` on boot (SQLite and MySQL). It
already creates `started_at` and `ended_at`, which later changes fill in, because that statement
can't add a column to a table that exists. If the statement fails, it logs an error and the rest of
Spring keeps running, while `/api/calls` answers 503 `CALLS_UNAVAILABLE`.

On SQLite, Hibernate reports a unique-key failure as `JpaSystemException`; on MySQL it's
`DataIntegrityViolationException`. Code that expects one should catch `DataAccessException`.

## Testing

```bash
./mvnw test -Dtest='Call*Test'
```

- `CallRoomServiceTest`: who may join, and opening rooms (in-memory store, `CallTestStore`).
- `CallApiHttpTest`: routes, the off switch, the guard, JSON fields and status codes.
- `CallRoomTableInitializerTest`: the table and its unique keys on in-memory SQLite.
- `CallRoomPersistenceTest`: the entity through JPA on a SQLite file, against the initializer's table.

`api/calls.http` runs the routes against a local server with calls turned on.

## Change checklist

- A new column goes in both `CallRoomEntity` and the initializer, plus an `ALTER TABLE` for servers
  that already have the table.
- A new purpose goes in `calls.purposes` and in the Pages call page's data.
- A new role rule goes in `calls.policy.*`, never as a role name in the code.
