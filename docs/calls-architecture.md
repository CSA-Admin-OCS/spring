# Calls Architecture Guide

## Overview

`mvc/calls` is the Spring side of video calls on OCS. A call happens in a **room**, and each group,
DM conversation and course has exactly one room, made the first time someone opens it and reused for
every call after. Spring stores the rooms, decides who may join, hands out short-lived join tokens
for a LiveKit server, which carries the video, and keeps the safety rules while a call goes on. The
media never goes through Spring. Setting up the LiveKit server is in
[calls-deployment.md](calls-deployment.md).

Calls are **off by default**. With `calls.enabled=false` every `/api/calls` route answers
404 `{"reason":"calls_disabled"}`, even to a request that would otherwise be refused, no table is
created, and nothing runs at night. Nothing changes on a server until someone turns calls on.

## The parts

| Class | Job |
|---|---|
| `CallApiRestController` | `/api/calls` routes. Checks the off switch, the page origin and the signed-in person, then asks a service. |
| `CallWebhookController` | `POST /api/calls/webhook`, LiveKit's signed reports of who joined and left. |
| `CallRoomService` | Who may join a room, and the find-or-create of each context's room. |
| `CallTokenService` | A join token: who may join, then the safety rules, then the token. |
| `CallWebhookService` | What a webhook changes: presence, attendance, the room's state, then the rules. |
| `CallSupervisor` | Keeps the rules during a call: pauses and resumes people through LiveKit, on its own thread. |
| `CallModerationService` | A moderator pausing, resuming or removing someone, or ending the call. |
| `CallAttendanceService` | Attendance rows from webhooks, and the nightly clean-up. |
| `CallEvents`, `CallEvent` | "A call is live" frames on the group's or DM's chat topic. |
| `CallLiveService` | The live calls a person may join, for course pages. |
| `CallPolicy` | The safety rules, as pure functions of the settings and who is connected. |
| `CallPresence` | Who is connected to each room and who was removed, in memory, by LiveKit connection. |
| `LiveKitTokens`, `CallIdentity` | LiveKit's JWT format, and each person's opaque LiveKit identity. |
| `LiveKitRooms`, `LiveKitWebhookVerifier` | LiveKit's room service over HTTP, and the webhook signature. |
| `CallRoomEntity`, `CallAttendanceEntity` + repositories | The `call_room` and `call_attendance` tables. |
| `CallRoomTableInitializer`, `CallAttendanceTableInitializer` | Create the tables on boot when calls are on (ddl-auto is `none`). |
| `CallRequestGuard` | Only OCS pages may call the routes: `X-Origin: client`, an exact origin from `calls.allowed-origins`, JSON on writes. |
| `CallExceptionHandler` | Every refusal as `{"reason": ...}`; a 503 also sends `Retry-After: 30`. |
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

Every route but the webhook needs a signed-in person (the default `/api/**` rule in
`SecurityConfig`) and the page headers the guard checks. Errors are `{"reason": ...}`.

| Route | Answers |
|---|---|
| `POST /api/calls` `{"contextType":"GROUP","contextKey":"12","purpose":"standup"}` | 200 `{"id","state","title","contextType","contextKey","purpose","participantCount"}`; 400 `UNKNOWN_CONTEXT_TYPE`, `UNKNOWN_PURPOSE` or `invalid_body`; 403 `NOT_A_MEMBER`; 404 `UNKNOWN_CONTEXT` (no such group or DM, or a course not in `courses.class-groups`) |
| `GET /api/calls/{id}` | 200 the same fields; 403 `NOT_A_MEMBER`; 404 `CALL_NOT_FOUND` |
| `POST /api/calls/{id}/token` | 200 `{"url","token","expiresIn":600,"canModerate"}`; 403 `WAITING_FOR_SUPERVISOR`, `WAITING_FOR_SECOND_ADULT`, `REMOVED` or `NOT_A_MEMBER`; 503 `MEDIA_UNAVAILABLE` while `calls.livekit.*` isn't set |
| `POST /api/calls/{id}/participants/{identity}/pause`, `/resume`, `/remove` | 204; 403 `NOT_A_MODERATOR`; 404 `PARTICIPANT_NOT_FOUND` (pause, resume); 409 `WAITING_FOR_SUPERVISOR` (resume while the rules keep them paused); 503 `MEDIA_UNAVAILABLE` |
| `POST /api/calls/{id}/end` | 204; 403 `NOT_A_MODERATOR`; 503 `MEDIA_UNAVAILABLE` |
| `GET /api/calls/live?course=csa` | 200 `[{"id","title","contextType","count","oneOnOne"}]`: the live calls with someone in them that the caller may join; with `course`, that course's room and its teams' group rooms |
| `POST /api/calls/webhook` (LiveKit only, no login) | 200; 401 `bad_signature` |
| any route | 401 `unauthorized`, 403 `origin_rejected`, 404 `calls_disabled`; 503 `CALLS_UNAVAILABLE` when the database fails (for example, a table couldn't be created) |

- `contextType` is `GROUP`, `DM` or `COURSE`. `contextKey` is the group's or DM's id, or the course
  (`csa` and `CSA` are the same room).
- `purpose` is one of `calls.purposes` (`meeting`, `standup`); without one the room gets the first.
  It is chosen when a room opens: opening an idle room again changes it, and a live call keeps the
  purpose it started with.
- `title` is the group's or course's name, and `null` for a DM, whose page names it.
- `state` is `IDLE` or `LIVE`; `participantCount` counts people, not tabs.
- The LiveKit room name (a random UUID) and who first opened the room are never sent.
- Two people opening a context's first room at the same moment both get the same room: the unique
  key refuses the second insert, and the controller reads the first one's row.

## Join tokens and the safety rules

Students are minors and mentors are adults, so `CallPolicy` applies these rules to every token
request. The roles come from `calls.policy.*`. Every account starts with `ROLE_STUDENT`, so each
person counts as the first of these their roles match: a supervisor (`ROLE_TEACHER`,
`ROLE_ADMIN`), a supervised role (`ROLE_MENTOR`), then a minor (`ROLE_STUDENT`).

- A supervised person gets a token only while a supervisor is connected, whenever a minor is in the
  room. Otherwise the answer is 403 `WAITING_FOR_SUPERVISOR`, and the page can try again.
- One adult alone with one minor: `ALLOW_AND_LIST` (the default) allows it and marks the call;
  `REQUIRE_SECOND_ADULT` makes whoever would start it wait (403 `WAITING_FOR_SECOND_ADULT`). With
  that setting, adults should join before a student who is alone in the room.
- A person a moderator removed gets no new token for that call (403 `REMOVED`), because a
  self-hosted LiveKit can't take back a token it accepted.

The token is a JWT signed HS256 with `calls.livekit.api-secret`, in LiveKit's format: `iss` is the
API key, `sub` the person's identity, `name` their display name, and `video` the grants. LiveKit
allows any grant a token leaves out, so every grant is written, and every room power
(`roomAdmin`, `roomCreate`, `roomList`, `roomRecord`, `canUpdateOwnMetadata`, `hidden`) is false. The
token lasts `calls.token-ttl-seconds` (600). Its `metadata` lists the roles the rules name (minor
roles too), which LiveKit hands back in its webhooks. That is how Spring knows who in a call is a
minor, and the page uses it for labels. A participant can't change their own metadata.

A person's identity is the first 22 characters of the base64url HMAC-SHA256 of `calls:` + their
uid, keyed with the API secret. It stays the same on every join, and LiveKit never sees a uid or
email. The room's name is its random UUID.

## During a call

LiveKit posts to `/api/calls/webhook` when someone joins or leaves and when a room finishes. Each
request carries a JWT in `Authorization` (LiveKit 1.13 sends it without `Bearer `) whose `sha256`
claim is the base64 SHA-256 of the body; `LiveKitWebhookVerifier` checks both, and anything else is
401. LiveKit retries and may send events out of order, so each step can run twice: a connection
(LiveKit's `sid`) that already left can't come back, and two tabs count as one person.

- `participant_joined`: the person is present, gets an attendance row (one per person), and the room
  turns `LIVE`.
- `participant_left`: when their last connection is gone, their attendance row closes.
- `room_finished`: presence, removals and pauses for the room are forgotten, open rows close, and the
  room turns `IDLE` with `endedAt` set.

After each change `CallSupervisor` checks the rules on its own thread, so the webhook answers 200
at once. When minors are connected and no supervisor is, supervised people are paused through
LiveKit's room service (no publish, no subscribe, no data), at once, or after
`calls.policy.supervisor-grace-seconds` when that is above 0. When a supervisor is back they get
full grants again. A LiveKit failure is tried again 5 seconds later.

Moderators (`calls.policy.moderator-roles`) can pause, resume and remove someone, or end the call.
A moderator's pause lasts until the moderator resumes them, even through a new token; resuming
while the rules still keep the person paused is 409. A removal is kept before LiveKit is called,
so it holds even when LiveKit is down.

## Live in the chats

After each join or leave, the group's chat topic (`/topic/group/{id}`) or the DM's
(`/topic/dm/{id}`) gets `{"context":"callServer","callId":5,"state":"live","count":2}`, and
`"state":"ended"` when the room finishes. Chat pages ignore a frame `context` they don't know, so
today's pages are unchanged. The frame has no names and no links, because a client can fake a
frame on a group topic: a page that shows "live" asks `GET /api/calls/{id}` first. Course rooms send
no frame; course pages ask `GET /api/calls/live?course=`. A supervisor may join any room, so
supervisors see every live call there, with the one-adult-one-minor calls marked `oneOnOne`.

## Persistence

- `call_room`: `context_type` and `context_key` unique together, and `room_name` unique.
- `call_attendance`: who was in which room (`call_room_id`, their `identity`, `uid` when Spring
  gave them their token since it last started, the role names), `joined_at` and `left_at`. Rows
  are deleted at 3:30 every night once they are `calls.attendance-days` (30) old.

The initializers run `CREATE TABLE IF NOT EXISTS` on boot (SQLite and MySQL), only while calls are
on. `call_room` already has `started_at` and `ended_at`, since that statement can't add a column to
a table that exists. If a statement fails, it logs an error and the rest of Spring keeps running,
while `/api/calls` answers 503 `CALLS_UNAVAILABLE`.

On SQLite, Hibernate reports a unique-key failure as `JpaSystemException`; on MySQL it's
`DataIntegrityViolationException`. Code that expects one should catch `DataAccessException`.

## Testing

```bash
./mvnw test -Dtest='Call*Test,LiveKit*Test'
```

- `CallRoomServiceTest`: who may join, and opening rooms (in-memory store, `CallTestStore`).
- `CallApiHttpTest`, `CallModerationHttpTest`, `CallWebhookHttpTest`, `CallLiveHttpTest`: the
  routes, the off switch, the guard, JSON fields and status codes.
- `CallEventsTest`: the chat frames and their topics.
- `CallPolicyTest`, `CallSupervisorTest`: the safety rules, and pausing during a call (with a
  scheduler and clock the test moves, `ManualCallScheduler`).
- `CallPresenceTest`, `CallIdentityTest`, `LiveKitTokensTest`, `LiveKitWebhookVerifierTest`:
  presence, identities, the token and the webhook signature.
- `LiveKitRoomsTest`: the room service requests, against a stand-in HTTP server.
- `CallRoomTableInitializerTest`, `CallAttendanceTableInitializerTest`, `CallAttendanceServiceTest`:
  the tables and the nightly clean-up.
- `CallPersistenceTest`: the entities through JPA on a SQLite file, against the initializers' tables.

`api/calls.http` runs the routes against a local server with calls turned on.

## Change checklist

- A new column goes in both the entity and its initializer, plus an `ALTER TABLE` for servers that
  already have the table.
- A new purpose goes in `calls.purposes` and in the Pages call page's data.
- A new role rule goes in `calls.policy.*`, never as a role name in the code.
