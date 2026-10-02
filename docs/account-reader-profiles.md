# Account and reading readiness profiles

Implementation scope approved in this conversation: email/password login, account profile, reading readiness profile. Frontend is owned by another contributor.

## Design and API contract

- Spring owns authentication and persistence. Passwords use Spring Security PBKDF2-HMAC-SHA256 (600,000 iterations, random salt). Bearer sessions use 32 random bytes, SHA-256 hashes in PostgreSQL, 12-hour expiry and explicit revocation.
- POST /api/auth/register: email, password (12–128 characters), displayName (1–120). Creates a new user; never claims an existing demo ID. Returns userId, accessToken, tokenType, expiresAt.
- POST /api/auth/login: email, password. Returns a new session. Invalid credentials share one message. Login/register throttled per source IP before password hashing.
- POST /api/auth/logout: revokes the presented token, 204.
- GET /api/me, PUT /api/me: own account. PUT replaces displayName, bio (max 500), avatarKey (BOOK/LEAF/MOON/SUN), interestTopicIds (max 20 unique existing topics). Avatar is a built-in key, not an uploaded file.
- GET /api/me/readiness: latest completed saved profile per assessed topic, stored evidence, source counts, and total completed assessments. Unassessed interests are reported separately; no invented scores.
- GET /api/me/readiness/{topicId}/history?page=0&size=20: completed snapshots newest first, stable ID tie-break, total count.
- GET /api/me/readiness/{topicId}/diagnostics: current ML detailed diagnostics for latest completed session. This can fail independently of the saved profile and may use newer ML configuration.

## Access and compatibility

Authentication is required by default. Public reads: catalog, topics, book reviews, health and Swagger/OpenAPI documentation. Swagger's Authorize dialog accepts the raw bearer token. All existing user/session/recommendation paths check the authenticated owner, including body userId on creates and feedback. /api/me derives identity only from the token.
APP_AUTH_MODE=demo explicitly permits legacy anonymous demo records; registered accounts still require authentication. Test tasks opt into demo for existing fixtures; dedicated security tests override to required.
Legacy route ownership is enforced for named path variables userId/sessionId/runId/itemId; create/feedback controllers additionally validate body userId. New private routes must derive identity from CurrentAccount or explicitly call AccountOwnership and add cross-account tests. Authentication alone is not resource authorization.
Tokens must be sent in Authorization: Bearer. Cookies and HTTP Basic are not authentication methods; CSRF is disabled for this header-only stateless transport. Use HTTPS outside local development; do not put tokens in URLs or logs. Frontend must forward Authorization and add login UI before using the default required mode.
No email verification, password recovery, social login, profile photo upload, public profile page or claim of improved diagnostic accuracy is included. Email identifies the login account but is not proof of ownership of that mailbox.

The initial throttle permits 10 login/register attempts per 60 seconds per remote IP per process. It does not trust forwarded headers; a reverse proxy may make clients share a limit. Before public deployment, configure trusted proxy handling and a shared limiter. Sessions are not refreshed automatically; expiry requires another login. Logout revokes only the presented session.

## Frontend handoff

1. POST `/api/auth/register` with `{"email":"reader@example.com","password":"a-long-unique-password","displayName":"독자"}` or POST `/api/auth/login` with email/password.
2. Keep the returned accessToken out of URLs and logs. Send `Authorization: Bearer <accessToken>` on private requests. No cookies are set; browser storage and XSS protection must be decided by the frontend. A 401 clears the local login state; a 403 is an ownership error, not an empty profile.
3. GET `/api/me` supplies userId for existing assessment/recommendation/shelf requests. Never use the old hard-coded demo ID for a registered account.
4. PUT `/api/me` with all fields, e.g. `{"displayName":"독자","bio":"운영체제를 공부합니다","avatarKey":"LEAF","interestTopicIds":[]}`. Obtain real topic IDs from GET `/api/topics`; an empty list clears interests.
5. GET `/api/me/readiness` returns `latestProfiles`, `unassessedInterests`, `completedAssessmentCount`, `limitations`. Render empty latestProfiles as “진단 전”, not a zero score. Scores are stored 0–1 values; evidence and calculationVersion describe their source, not a validated skill percentile. `demoCalculation` identifies stub calculations. History is paginated and must not be plotted as verified growth across different configurations.
6. POST `/api/auth/logout` with the token, then clear local token/profile state. No frontend files are changed in this PR.

## Dependencies and checks

Based on Backend #29 for shelf/review ownership. Keep PR stacked until #29 is merged. New migration adds account credentials, session hashes, profile fields and interest relationships without overwriting existing user/profile data.
PostgreSQL HTTP tests cover registration/login/logout/expiry, invalid input, duplicate email, cross-user denial across legacy APIs, profile edits, empty readiness, deterministic history and evidence preservation.

2026-10-02 verification: Java 21 / PostgreSQL 17.11 Testcontainers, `./gradlew clean build --no-daemon --console=plain`: 172 tests, 168 passed, 0 failed, 4 optional live-ML tests skipped. Nine new account/security tests are included. The final run used an identical source copy on WSL's Linux filesystem (source/build configuration compared) to avoid Windows-mounted filesystem startup latency. The initial mounted run hit the existing 10-second OpenAPI timeout; only that cold-document request now allows 30 seconds. An added evidence fixture initially omitted required difficulty_snapshot; corrected before the passing full run. These checks validate API behavior and authorization, not diagnostic or recommendation accuracy.
