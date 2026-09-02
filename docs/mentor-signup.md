# Mentor Signup Flow

How the Student/Mentor signup selector works, and what mentor accounts look like on the backend. Current as of the `feat/mentor-role` branch.

> ### ⚠️ REQUIRED BEFORE THIS DEPLOYS TO PRODUCTION
>
> The `mentor_email_verified` column has only been applied to the **local dev SQLite database**, the same way `token_version` and `reset_ticket` were handled earlier on this branch's history (`ddl-auto=none`, schema managed by hand). Production runs a separate database this session has no access to. **Run this before the code ships, or every signup/dashboard load will error on the missing column:**
>
> ```sql
> ALTER TABLE person ADD COLUMN mentor_email_verified boolean NOT NULL DEFAULT 0;
> ```

## Summary

`pages/navigation/authentication/login.md`'s signup form gained a Student/Mentor selector. Student signup is unchanged — Student ID, school dropdown, and mandatory Google OAuth verification against `@stu.powayusd.com` all work exactly as before. Mentor signup drops the Student ID/school requirement and makes the OAuth step **optional**: a mentor can sign up with just a GitHub uid, email, and password, or additionally verify a business email via Google Sign-In for a visible signal on the admin dashboard. Either way, mentor accounts land in `ROLE_PENDING` — promotion to `ROLE_MENTOR` is still an explicit admin action via the existing `update-roles.html`, never automatic.

## Flow

1. User selects "Mentor" from the `#signupRole` dropdown in `login.md`. `updateSignupModeUI()` hides and un-requires the Student ID/school fields, and swaps the OAuth panel's copy to explain the step is optional.
2. Submitting the form always shows the OAuth panel (same as student signup), but mentor mode adds a **"Skip — verify later"** button next to the Google Sign-In button.
   - **Skip**: `skipMentorOAuth()` sets `signupIdToken = null` and calls `signup()` directly — no Google interaction at all.
   - **Google Sign-In**: the existing `handleGoogleSignIn` callback runs unchanged, capturing a verified idToken, then calls `signup()`.
3. `signup()` sends `accountType: "mentor"` (from the dropdown's value) to Spring's `POST /api/person/create` alongside whatever `idToken` is set (or absent).

## Backend (`PersonApiController.postPerson`)

- `boolean isMentorSignup = "mentor".equalsIgnoreCase(personDto.getAccountType())` — an **explicit opt-in match**. Anything else (null, `"student"`, a typo, any other existing caller of this endpoint) falls through to the unchanged student path: idToken required, `403` if missing/invalid.
- Mentor path:
  - If an idToken **is** present, it's still verified server-side via `GoogleIdTokenVerifier` (never trust a claimed email) — an invalid token is still a real `403`, not silently ignored. If verified, the email's domain is checked against `TrustedDomains.isTrusted(...)`; a match sets `mentorEmailVerified = true` on the created `Person`.
  - If no idToken, falls back to `personDto.getEmail()` (required, `400` if blank). `mentorEmailVerified` stays `false`.
  - Role is always `ROLE_PENDING` — never auto-`ROLE_USER`, regardless of email domain.

## Business-email whitelist

`spring/volumes/mentor-trusted-domains.txt` — plain text, one domain per line, `#` for comments, case-insensitive suffix match (`mail.google.com` matches a whitelisted `google.com`). Lives in the externally-mounted `volumes/` directory (see `docker-compose.yml`), not `src/main/resources`, specifically so an admin can add/remove domains without a rebuild.

`ModelInit.ensureMentorDomainsSeeded()` writes a starter list (~50-80 well-known tech/finance/consulting domains) on first boot if the file doesn't exist yet, and never touches it again afterward — admin edits always survive a restart.

## Admin dashboard

`person/read.html` has a new "Mentor" column (between SID and Action) showing a badge when `person.mentorEmailVerified` is true. This is purely informational — it doesn't change how promotion works. An admin still uses the existing "Update Roles" link to promote a `ROLE_PENDING` account (mentor-signed-up or not) to `ROLE_MENTOR`.

Note the column was inserted **before** `Action`/`Import-Export` rather than appended at the very end, specifically so those two stay the trailing two columns — `read.html`'s inline `exportCSV`/`importCSV` functions hardcode "exclude the last 2 columns" (`cols.length - 2`), and appending after them would have broken CSV export (it would've started including the `Action` buttons column and dropping the real data). `read-filter.js`'s hidden-column indices (`targets: [...]`) were updated to match the new column positions.

## Flask

No backend changes. `POST /api/user` already accepts signup with no OAuth and treats `sid`/`email` as optional, so it needed nothing new — a mentor signup there just omits `sid` (sent as `''`, which Flask's `if body.get('sid')` check already treats as absent) and lands on `role: 'Pending'`, same as any other non-`@stu.powayusd.com` signup. No `mentor_email_verified` mirror column — Flask has no dashboard to show it on, and the existing `reconcile_roles.py` precedence mapping already handles the eventual `ROLE_MENTOR` promotion correctly with zero changes.

## Key files

| System | File | Role |
|---|---|---|
| pages | `navigation/authentication/login.md` | Student/Mentor dropdown, conditional fields, optional OAuth step |
| spring | `mvc/person/PersonApiController.java` | `postPerson` mentor branch, `PersonDto.accountType` |
| spring | `mvc/person/Person.java` | `mentorEmailVerified` field |
| spring | `mvc/person/TrustedDomains.java` | Business-domain whitelist lookup |
| spring | `system/ModelInit.java` | `ensureMentorDomainsSeeded()` — starter whitelist file bootstrap |
| spring | `templates/person/read.html` | Admin dashboard "Mentor" column |
| spring | `static/js/read-filter.js` | Updated hidden-column indices |
