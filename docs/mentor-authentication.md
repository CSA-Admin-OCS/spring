# Mentor Authentication

How a mentor's identity and permissions actually work once they have an account — distinct from [mentor-signup.md](mentor-signup.md), which covers how that account gets *created* and *approved*. Current as of `feat/mentor-role` (spring) and `origin/feat/mentor-role` (pages).

## Summary

There is no separate "mentor login." A mentor authenticates through the exact same `POST /login` form the rest of the app uses. What makes an account a mentor is entirely a **role**, `ROLE_MENTOR`, checked the same way `ROLE_ADMIN` or `ROLE_TEACHER` are. The business-email verification a mentor can do at signup (Google OAuth against a trusted-domain whitelist) never grants that role by itself — it only sets a visible `mentorEmailVerified` flag for an admin reviewing the request. The role itself is only ever granted by an explicit admin action (approving the account's `MentorTicket`). So a pending mentor can log in just fine; they simply don't have `ROLE_MENTOR` yet, and the backend enforces that on every mentor-only action regardless of what the frontend shows.

## How login works for any account, mentor or not

1. `PersonDetailsService.loadUserByUsername(uid)` loads the `Person` row and converts **every role it currently holds** into a Spring Security authority — unconditionally. There's no branch for "pending" vs "approved"; a `ROLE_PENDING` mentor authenticates exactly like anyone else, they just come out of this call with `ROLE_PENDING` instead of `ROLE_MENTOR`.
2. On successful form login, `MvcSecurityConfig`'s success handler calls `jwtTokenUtil.generateToken(userDetails, roles)`, which embeds the account's role list as a `roles` claim directly in the JWT (`JwtTokenUtil.java`), alongside a `tokenVersion` claim used to invalidate old tokens after a password reset. Putting roles in the token is deliberate — it lets the frontend know what an account can do without a second request.
3. That JWT is set as the `jwt_java_spring` cookie. Every subsequent request carries the account's current roles along with it until the next login.

## How the frontend decides "mentor view" vs "student view"

This part is presentation only — it never grants access by itself.

**This used to be static, pre-login.** The login card originally had its own Student/Mentor radio toggle, separate from the signup form's role selector. A user had to correctly guess/declare their account type *before* even attempting to log in, and that declared choice — not the account's real role — decided which UI they landed on. It was redundant with the signup form's own selector, and wrong if the user picked the wrong option or the two selectors ever drifted out of sync.

That toggle was removed (`a2a27476d`, "Remove the redundant Student/Mentor login toggle"). The replacement is dynamic and happens *after* login: `loginBoth()` no longer pre-asks which role to log in as — it logs in first, then checks the account's real roles from Spring, and routes accordingly. `viewFor()` simplified down to a single `roles.includes('ROLE_MENTOR')` check against that real data, because Spring's role is now the only source of truth — there's nothing left to override with a guess. The one thing layered back on top at runtime is `switchView()`, which lets a confirmed mentor manually flip to the student view and back (sidebar, dashboard tabs, and capstone actions all re-render), but that choice only exists *after* the real role has already been established, and resets on every login/logout.

- `role-view.js`'s `roleNames(person)` reads the roles array from `GET /api/person/get`.
- `isMentorAccount(roles)` is a single check: does the list include `ROLE_MENTOR`.
- `viewFor(roles)` returns `'student'` outright if that check fails. If it passes, a mentor can still manually flip to the student view via `switchView()` (stored in `localStorage`, reset on every login/logout by `clearRoleViewCache()`), so "has the mentor role" and "is currently seeing the mentor UI" aren't the same thing.
- None of this is authoritative. It only controls which sidebar/tabs render. Spring is what actually decides what a request is allowed to do.

## Where the role is actually enforced

Backend endpoints that are genuinely mentor-only check for `ROLE_MENTOR` directly (e.g. the capstone project apply endpoint). A `ROLE_PENDING` account — even one with `mentorEmailVerified = true` from a verified business email at signup — gets a `403` from those endpoints exactly like a plain student would, because the OAuth verification at signup time never touched the role table. Only the admin-approval path in `mentor-signup.md` does that.

## Key files

| System | File | Role |
|---|---|---|
| spring | `mvc/person/PersonDetailsService.java` | `loadUserByUsername` — loads current roles unconditionally into the Spring Security authorities |
| spring | `security/JwtTokenUtil.java` | `generateToken` — embeds the `roles` and `tokenVersion` claims in the JWT |
| spring | `security/MvcSecurityConfig.java` | Unified `/login` form-auth chain; issues the JWT cookie on success |
| pages | `assets/js/api/role-view.js` | `roleNames`, `isMentorAccount`, `viewFor`, `switchView`, `clearRoleViewCache` — frontend view derivation only |

## See also

[mentor-signup.md](mentor-signup.md) — how an account becomes a mentor in the first place: the signup form, optional business-email OAuth verification, the `MentorTicket` queue, and the admin approval action that is the only thing that actually grants `ROLE_MENTOR`.
