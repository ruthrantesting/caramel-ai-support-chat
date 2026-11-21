# Quickstart: Validating User Authentication

**Plan**: [plan.md](plan.md) | **Contracts**: [contracts/](contracts/) | **Data model**: [data-model.md](data-model.md)

This is a validation guide. It describes how to prove the feature works end to end once it is built, and it contains no implementation code. Commands for the existing app are the ones in `CLAUDE.md`. The new files and settings named below do not exist yet.

Steps marked **Gate** resolve an item that research marked **Unverified**. Run them early.

## Prerequisites

- Java 21, Maven 3.9, Node 22 (installed here: OpenJDK 21.0.13, Maven 3.9.12, Node 22.22.2).
- Network access on the first build. No Spring Security, PostgreSQL driver, or Argon2 support jar is in the local Maven cache (research A-2).
- A Google AI Studio key in `.env`, for steps that send a real chat message. Steps that only touch sign-up, sign-in, ownership, and limits use a stubbed model in automated tests and do not need it. The backend still refuses to start without a key, as in 001.
- `curl` and `jq` for the HTTP checks.
- For step 11 only: a local mail catcher that speaks SMTP (for example Mailpit) on port 1025.

## 1. Configure the new settings

Add these lines to `.env.example` as placeholders, then copy any you need into `.env`:

- `AUTH_DB_URL`, `AUTH_DB_USER`, `AUTH_DB_PASSWORD`: used only when you point the app at PostgreSQL. Leave blank to use the embedded file database.
- `SESSION_COOKIE_SECURE`: `true` by default. Set to `false` only for local HTTP when your browser or `curl` refuses a secure cookie.
- `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `MAIL_FROM`: used only by password reset (User Story 6).

**Expected**: `.env` is still git-ignored. `.env.example` has a placeholder for every new secret and no real value.

## 2. Gate: dependencies resolve and the encoder works

```bash
cd backend && mvn -q -B -DskipTests package
```

**Expected**: the build succeeds with the new dependencies. Then run the single test that exercises the password encoder (`PasswordEncoderTest`, written in the foundation phase):

```bash
cd backend && mvn -B test -Dtest=PasswordEncoderTest
```

**Expected**: a 128-character password round-trips, and a password that differs only in its last character is rejected (this proves nothing is truncated, research A-3). If it fails, record why in `research.md` before continuing.

## 3. Run all automated tests

```bash
cd backend && mvn -q -B test
cd frontend && npm test
cd frontend && npx tsc --noEmit
```

**Expected**: all pass. Live provider tests are skipped without a key, as before.

Tests that Constitution VI requires before merge, each runnable alone with `mvn -B test -Dtest=<Class>`:

| Requirement | Test class |
|-------------|-----------|
| `401` for unauthenticated calls to both chat endpoints | `ChatEndpointsAuthTest` |
| `404` for another user's read, continue, and delete | `ConversationOwnershipTest` |
| Password stored as a hash only | `UserRepositoryTest` |
| Session cookie is `HttpOnly` | `SessionCookieTest` |

## 4. Start the app

```bash
./run.sh 2>&1 | tee backend.log
```

Leave it running and use a second terminal for the next steps. The log is kept in `backend.log` for the scans in steps 4 and 13 (it is not committed; do not add it to version control).

**Expected**: backend on port 8082 and frontend on port 5173. The backend log does not contain "Using generated security password" (research A-15):

```bash
grep -i "generated security password" backend.log || echo "none (good)"
```

## 5. Gate: session cookie and CSRF cookie

```bash
curl -si -c /tmp/a.jar http://localhost:8082/api/auth/me
```

**Expected**: status `401`, body code `UNAUTHENTICATED`, no `WWW-Authenticate` header, and a `Set-Cookie: XSRF-TOKEN=...` header. This proves a visitor gets a CSRF token (research A-5).

Register a user. Read the token from the jar first, then send it back:

```bash
TOKEN=$(awk '$6=="XSRF-TOKEN"{print $7}' /tmp/a.jar)
curl -si -b /tmp/a.jar -c /tmp/a.jar -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"email":"Ana@Example.com","password":"correct-horse-battery-staple-42"}' \
  http://localhost:8082/api/auth/register
```

**Expected**: `201`, body `{ "id": "...", "email": "ana@example.com" }` (lower case), and a `Set-Cookie` for the session containing `HttpOnly`, `SameSite=Lax`, and, unless you relaxed it, `Secure`. If `curl` does not send a secure cookie over HTTP, set `SESSION_COOKIE_SECURE=false` and restart. Record whether Chrome and Firefox accepted the secure cookie on `http://localhost` (research A-8).

## 6. CSRF and protected endpoints

Using the jar from step 5, and a new conversation identifier `CID=$(uuidgen)`:

| Check | Command outline | Expected |
|-------|-----------------|----------|
| No session | `POST /api/chat` with a fresh jar and a valid token | `401 UNAUTHENTICATED` |
| No CSRF header | `POST /api/chat` with the session jar, no `X-XSRF-TOKEN` | `403 CSRF_INVALID` |
| Valid | `POST /api/chat` with session and header, body `{conversationId, message}` | `200`, `text/event-stream` |
| Delete, no session | `DELETE /api/chat/$CID` with a fresh jar | `401` |
| Sign-in wrong password | `POST /api/auth/login` with a wrong password | `401 INVALID_CREDENTIALS`, message "Email or password is incorrect" |
| Sign-in unknown email | `POST /api/auth/login` with an unregistered email | Same status, code, and message as the row above |

After a sign-in the token changes. Re-read the jar before the next call (research A-5).

## 7. Primary flow in the browser (SC-003, User Stories 1 to 3)

1. Open `http://localhost:5173/`. **Expected**: the sign-in screen, no chat (FR-031).
2. Choose "Sign up", enter a valid email and a password of 12 or more characters. **Expected**: the chat opens without a page reload (FR-009).
3. Send "How do I handle a 429 error?". **Expected**: the reply starts within 2 seconds (SC-005).
4. Reload the page. **Expected**: still signed in, a new empty chat (FR-010). The conversation resets on reload, as in 001.
5. In the browser developer tools, open the cookies. **Expected**: the session cookie is marked `HttpOnly`. Local storage and session storage hold nothing from this app.
6. Choose "Sign out". **Expected**: the sign-in screen replaces the chat. Press the browser Back button. **Expected**: no chat content (User Story 2, scenario 5).
7. Sign up with the same email again. **Expected**: a neutral message that does not confirm the address is registered, and no second account (FR-006).

Time steps 2 to 3 with a stopwatch. **Expected**: under 3 minutes in total.

## 8. Ownership between two users (User Story 4, SC-001, SC-002)

Use two jars, `/tmp/a.jar` and `/tmp/b.jar`, one signed-in user each, and a shared `CID`.

| Step | Expected |
|------|----------|
| User A `POST /api/chat` with `CID` | `200` |
| User B `POST /api/chat` with the same `CID` | `404 CONVERSATION_NOT_FOUND`, and the model is not called |
| User B `DELETE /api/chat/$CID` | `404`, and A's conversation is intact |
| User A sends a follow-up on `CID` that refers to the first message | The reply uses the earlier context |
| User A `DELETE /api/chat/$CID` | `204` |
| User B `POST /api/chat` with `CID` | `200`, and the reply has no context from A |
| User B `DELETE /api/chat/<unused uuid>` | `404`, the same response as the not-owned case |

## 9. Gate: latency (SC-004, SC-005)

- Time a sign-in: `curl -s -o /dev/null -w '%{time_total}\n' ... /api/auth/login`. **Expected**: under 1 second, including hashing.
- Time one password hash through the encoder test output or a log line at `DEBUG` in the test only. **Expected**: about 250 ms or less. If it is slower, lower the Argon2 memory setting and record the value in `research.md` A-3.
- Send 10 chat messages as a signed-in user and note the time to the first token. **Expected**: at least 9 of 10 under 2 seconds, the same target as 001.

## 10. Limits and throttle (User Story 5, SC-008, SC-009)

1. Per-user limit: as user A, send 21 messages within a minute. **Expected**: the 21st gets `429` with `Retry-After`.
2. Shared network: from the same address, as user B, send a message right after. **Expected**: it succeeds, because the limit is per user, not per address.
3. Throttle: send 6 wrong passwords for one email within a minute, then the correct password. **Expected**: from the sixth attempt on, `429` with `Retry-After`, and the correct password is also refused until the delay passes. The wait grows with each further failure and never exceeds 15 minutes.
4. Same check with an unregistered email. **Expected**: the same responses, so the throttle does not reveal registration.
5. Address limit: send 6 sign-up requests within an hour from one address. **Expected**: the sixth gets `429`.

## 11. Password reset (User Story 6, only if built)

Start a local mail catcher on port 1025 and set `SMTP_HOST=localhost`, `SMTP_PORT=1025`.

1. Request a reset for a registered email and for an unregistered email. **Expected**: both return `202` with the same body, and similar response times.
2. Open the mail for the registered address. **Expected**: a link whose token is after `#` in the URL.
3. Open the link, enter a new valid password. **Expected**: success, and every existing session of that user is ended (reload another browser signed in as that user: it returns to sign-in).
4. Open the same link again. **Expected**: refused with the single "invalid or expired" message.
5. Request another link and wait past 60 minutes (or advance the test clock in the automated test). **Expected**: refused.
6. Check the backend log. **Expected**: no token and no link.

## 12. Gate: PostgreSQL

Point `AUTH_DB_URL`, `AUTH_DB_USER`, and `AUTH_DB_PASSWORD` at an empty PostgreSQL database and restart.

**Expected**: tables are created on first start, sign-up works, and accounts survive a restart of the backend (FR-020). After the restart, the old session is gone: the browser returns to the sign-in screen, and the same email and password work.

## 13. Secret and log scan (SC-006)

```bash
cd frontend && npm run build && npm run verify:no-secrets
```

**Expected**: passes. The script is extended in this feature to fail if the frontend source uses `localStorage` or `sessionStorage`.

Then grep the backend log from steps 5 to 11:

```bash
grep -F -e 'correct-horse-battery-staple-42' -e "$(awk '$6=="JSESSIONID"{print $7}' /tmp/a.jar)" backend.log || echo "none (good)"
```

**Expected**: no password, no session identifier, no CSRF token, and no reset token in the log.

## 14. Accessibility (SC-010)

Using only the keyboard, complete sign-up, sign-in, and sign-out. With a screen reader on, submit the sign-in form with a wrong password.

**Expected**: every field is reachable by Tab and has a label, the error is announced without moving focus away from the form, and a password manager offers to fill and save the credentials (the `autocomplete` values are `email`, `current-password`, and `new-password`).

## 15. Expired session keeps the draft (User Story 3, FR-015)

Set `server.servlet.session.timeout` to a short value for this run (for example `1m`), sign in, send one message, wait for the timeout, then type and send a second message.

**Expected**: a dialog says the session expired and offers sign-in, the chat and the failed message stay visible, and after signing in again as the same user the Retry action sends the message. Repeat, signing in as a different user. **Expected**: the old transcript is gone, a new conversation starts, and the unsent text is back in the message box.
