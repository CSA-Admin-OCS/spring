# Personal GitHub Gist tokens

Users connect a GitHub token through Profile Settings. Spring encrypts it using
AES-256-GCM with fresh random nonces and authenticated person IDs. New Gists use
only the logged-in user's credential; there is no shared creation fallback.
GitHub identity may differ from OCS UID. Gists are unlisted, not private: anyone
with the URL can read them. Existing links remain readable without author tokens.
The optional legacy server token is used only for reads and API rate limits.

Configure `GIST_TOKEN_ENCRYPTION_KEY` once in the ignored Spring `.env`, using
base64 encoding of exactly 32 random bytes (`openssl rand -base64 32`). Preserve
and privately back up this key across deployments; never commit it. Missing or
invalid keys disable the feature without stopping other application features.
Rotation requires explicit re-encryption or user reconnection, not automatic
key generation. Never reset the database for this feature. Deploy Spring first.

The additive SQLite/MySQL startup migration creates `gist_connections` when
absent. Its ciphertext is omitted from JSON exports and profile responses. Raw
database backups still contain ciphertext and require protection.

Authenticated GET/PUT/DELETE `/api/gist-connection` always resolve the owner from
the session, never a submitted person ID. PUT validates `/user` before replacing
the existing credential. Classic tokens require gist scope; fine-grained tokens
require Gists write. Identity validation alone does not establish write permission.
Write operations also require a trusted Origin and `X-Origin: client` header.
Disconnect removes OCS storage, not GitHub authorization; revocation is separate.

Automated verification: Java 21 compilation and 125 Spring tests passed,
including isolated encryption, user scoping, replacement, GitHub error handling,
SQLite migration, export exclusion and origin checks. Tests mock GitHub and use
an isolated in-memory database; they do not create real Gists or submissions.
The local backend returns JSON 401 for anonymous connection requests.
The user reported successful local connection and submission testing. Live
two-user ownership verification and production acceptance remain separate checks.

## Local worktree setup

Keep feature worktrees in a persistent directory, not `/tmp`. Ignored configuration,
database files and dependencies do not follow Git worktrees automatically. This
local worktree links the existing Spring `.env` and SQLite files rather than
creating a fresh database. Pages reuses the existing virtual environment and gems.
Neither local links nor secrets belong in commits.
