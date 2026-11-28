# Research: User Authentication for the Caramel AI Tech Support Chat

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-11-21

Entries use the format Decision, Rationale, Alternatives, Evidence. Evidence labels:

- **Verified**: checked against the repository, the local Maven cache, or the installed toolchain.
- **Unverified**: depends on a library that is not yet downloaded or on a running app. Each has a gate in [quickstart.md](quickstart.md).

IDs use an `A-` prefix so they do not collide with the `R-` entries in `specs/001-tech-support-chat/research.md`.

## A-1. Session mechanism

- **Decision**: Server-side session in the servlet container, carried by an `HttpOnly` cookie. The session lives in process memory.
- **Rationale**: Constitution VI requires a cookie carrying the session credential, and sign-out must end the session on the server (FR-012). A server-side session can be invalidated at once. It needs no signing key, so there is no new signing secret to protect. Loss of sessions on restart is accepted by the PRD (section 7, Reliability) because accounts survive.
- **Alternatives**:
  - Stateless JWT in a cookie. Rejected: cannot be revoked on sign-out or password reset without a deny-list, which is server-side state anyway.
  - Spring Session with a database. Rejected for the MVP: adds a table and a dependency to solve horizontal scale-out, which is out of scope (plan, Scale/Scope).
- **Evidence**: Constitution VI and PRD section 13 recommend this. **Verified** against the documents.

## A-2. Spring Security version and new dependencies

- **Decision**: Add `spring-boot-starter-security` (managed by Boot 4.1.1, which pulls Spring Security 7.1.x), `spring-security-test` (test scope), `spring-boot-starter-jdbc`, `h2` (runtime), the PostgreSQL JDBC driver (runtime), and `org.bouncycastle:bcprov-jdk18on` (needed by Argon2, see A-3). `spring-boot-starter-mail` is added only for User Story 6.
- **Rationale**: Boot manages every version, so no explicit versions are needed except where Boot does not manage a library.
- **Evidence**:
  - `spring-security-bom` 7.1.1 and `h2` 2.4.240 are in the local Maven cache (**Verified**).
  - No Spring Security jar, no PostgreSQL driver, and no `bcprov` jar are cached (**Verified**). The first build needs network access. Maven Central answered HTTP 200 from this environment (**Verified**).
  - Resolved during T003 (**Verified**): `mvn -DskipTests package` succeeds with the new dependencies. Spring Security resolves to **7.1.1** (`spring-security-crypto-7.1.1.jar` in the local cache). `bcprov-jdk18on` is not managed by Spring Boot, so it is pinned in `backend/pom.xml` as `bouncycastle.version` 1.87, the newest release on Maven Central.
  - Still **Unverified**: the Boot 4 package names that code depends on (for example, the location of `UserDetailsServiceAutoConfiguration`). Each is checked when the code that uses it is written.

## A-3. Password hashing

- **Decision**: Argon2id through Spring Security's `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`, wrapped in a `DelegatingPasswordEncoder` so a later change of algorithm needs no migration.
- **Rationale**: The policy allows passwords of up to 128 characters (FR-003). bcrypt only uses the first 72 bytes, so it would silently ignore the tail of a long password, or reject it, depending on the library version. Argon2 has no such limit and is permitted by Constitution VI. The Spring defaults (16 MiB memory, 2 iterations, parallelism 1) keep one hash in the tens of milliseconds, well inside the 1-second sign-in target (SC-004), and the per-address limit (A-11) bounds how many hashes run at once.
- **Alternatives**:
  - bcrypt with a SHA-256 pre-hash to avoid the 72-byte limit. Rejected: extra custom code that a reviewer must verify, to avoid one extra dependency.
  - bcrypt without a pre-hash and a 72-byte maximum. Rejected: contradicts the 128-character maximum in the spec.
- **Evidence**: `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()` exists in spring-security-crypto 7.1.1 (**Verified** by `javap`, T003). The 72-byte bcrypt limit is a property of the algorithm, stated from general knowledge and not checked against this repository (**Unverified**; gate: the 128-character round-trip in quickstart step 2). Argon2 timing on the target machine is **Unverified**: measure in quickstart step 9. If one hash exceeds about 250 ms, lower the memory parameter.

## A-4. Password policy and the common-password list

- **Decision**:
  - Normalize the password with Unicode NFKC before hashing and before checking, so the same text typed on different systems gives the same hash.
  - Count length in Unicode code points, 12 to 128.
  - Check against a bundled list in `backend/src/main/resources/auth/common-passwords.txt`, loaded once into a set at startup. The list is compared case-insensitively and holds only entries of 12 characters or more, because shorter ones are already rejected by the length rule.
- **Rationale**: A bundled list has no runtime dependency, no privacy exposure, and no outage mode. Filtering to 12 or more characters keeps the file small.
- **Alternatives**: The Have I Been Pwned range API. Rejected: sends a hash prefix of every new password to a third party, and sign-up would fail whenever that service is down, which breaks the reliability goal.
- **Evidence**: The word list source is **Unverified**. The implementation must pick a list with a licence that permits redistribution (for example, a public top-passwords list) and record the source and licence in the file header.

## A-5. CSRF with a cookie session and a single-page app

- **Decision**: Use Spring Security's CSRF protection with `CookieCsrfTokenRepository.withHttpOnlyFalse()` and the single-page-app request handler, plus a small filter that reads the token on every response so the `XSRF-TOKEN` cookie is always set. The frontend copies the cookie value into an `X-XSRF-TOKEN` header on every `POST` and `DELETE`. A missing or invalid token returns `403`.
- **Rationale**: This is the documented Spring pattern for a separate single-page app. The CSRF cookie is not the session credential, so making it readable by scripts does not weaken A-1. The first call the app makes on load is `GET /api/auth/me`, which returns `401` for a visitor and still sets the cookie.
- **Details that matter**:
  - Spring rotates the CSRF token when a user signs in. The frontend reads the cookie before each request, never caches it, so rotation is harmless.
  - The streaming chat `POST` uses `fetch`, so it can set the header (PRD section 12, "SSE and CSRF").
  - On a `403` the client calls `GET /api/auth/me` once to get a fresh cookie and retries the request once, before any bytes are streamed.
- **Alternatives**: Disable CSRF and rely on `SameSite=Lax`. Rejected: Constitution VI requires a CSRF token on state-changing calls.
- **Evidence**: Class names (`CookieCsrfTokenRepository`, the single-page-app request handler) are **Unverified** against Security 7.1 until the dependency resolves. The behavior is gated by the CSRF tests in quickstart step 6.

## A-6. Auth endpoints and the security filter chain

- **Decision**: A JSON `AuthController` handles register, login, and `me`. Login calls a standard `AuthenticationManager` built on `DaoAuthenticationProvider`, a JDBC-backed `UserDetailsService`, and the `PasswordEncoder` from A-3. On success the controller saves the `SecurityContext` to the session explicitly and changes the session ID. Logout uses the filter chain's logout support (`POST /api/auth/logout`, response `204`). Authorization rules: `register`, `login`, and the two `password-reset` paths are public. Everything else requires authentication.
- **Rationale**: `DaoAuthenticationProvider` already hides "user not found" behind the same `BadCredentialsException` as a wrong password, and runs a dummy hash for an unknown user so the response time does not reveal registration (FR-008). Spring Security 6 and later no longer saves the context to the session automatically for custom login code, so the explicit save is required. Changing the session ID on login prevents session fixation.
- **Alternatives**: Spring's built-in form login. Rejected: it redirects on success and failure, which does not suit a JSON API.
- **Evidence**: **Unverified** until the dependency resolves. Behavior is gated by the login tests.

## A-7. Session lifetime

- **Decision**:
  - Idle limit: `server.servlet.session.timeout=8h`.
  - Absolute limit: a filter reads a `signedInAt` session attribute, set at login from the injected `Clock`, and invalidates the session when it is older than 7 days.
  - Both values are configuration properties.
- **Rationale**: The container tracks idle time. It does not track total age from sign-in, so the absolute limit needs a small filter. Using the injected `Clock` lets tests advance time with the existing `MutableClock`.
- **Evidence**: `MutableClock` exists in `backend/src/test/java/ai/caramel/support/` (**Verified**).

## A-8. Cookie attributes and local development

- **Decision**: `server.servlet.session.cookie.http-only=true`, `same-site=lax`, and `secure` taken from the environment variable `SESSION_COOKIE_SECURE`, default `true`. `.env.example` documents `SESSION_COOKIE_SECURE=false` for local HTTP development when a browser rejects secure cookies on plain HTTP.
- **Rationale**: Constitution VI allows relaxing `Secure` for local development by configuration only. Defaulting to `true` makes the unsafe state opt-in. Chrome and Firefox treat `http://localhost` as a trustworthy origin and accept secure cookies there, so the default should work with `./run.sh`.
- **Evidence**: Browser behavior for `localhost` is **Unverified** here. Gate: quickstart step 5. Safari is known to differ, so the documented override covers it.

## A-9. User store

- **Decision**:
  - Plain JDBC through Spring's `JdbcClient`. No JPA, no Hibernate.
  - Development and tests: embedded H2 in PostgreSQL compatibility mode. Development uses a file at `backend/data/` (git-ignored), tests use in-memory.
  - Production: PostgreSQL, selected by `AUTH_DB_URL`, `AUTH_DB_USER`, and `AUTH_DB_PASSWORD` in `.env`. Who hosts it is an open question for the user.
  - Schema: `schema.sql` with `CREATE TABLE IF NOT EXISTS`, run by Spring's SQL initialization. No migration tool in the MVP.
- **Rationale**: Two small tables do not justify an object-relational mapper. The SQL used is portable between H2 in PostgreSQL mode and PostgreSQL. A migration tool is worth adding when the schema first changes after release.
- **Alternatives**: Spring Data JPA (heavier, and Hibernate is not in the local cache). Flyway (extra dependency for two tables).
- **Gate**: Constitution VI requires the store technology to be recorded in `002-prd-user-auth.md` before implementation. This plan does not edit the PRD. The first implementation step does.
- **Evidence**: H2 2.4.240 is cached (**Verified**). PostgreSQL mode compatibility of the schema is **Unverified** until tested against a real PostgreSQL. Gate: quickstart step 12.

## A-10. Conversation ownership and memory scoping

- **Decision**:
  - `ConversationRegistry` stores the owner's user ID with each conversation. The first accepted request for an identifier binds it to that user.
  - Any request by a different user for a bound identifier raises `ConversationNotFoundException`, mapped to `404`.
  - The chat service receives the user ID as a plain argument. Chat memory is stored under the key `userId:conversationId`.
  - The binding is created atomically, so two users racing for a new identifier cannot both own it.
  - `DELETE` for an unknown identifier now returns `404`, like a not-owned one, so a probing user cannot tell the two apart.
  - The binding is released when the owner clears the conversation, when the idle expiry removes it, or on restart. Sign-out does not release it.
- **Rationale**: The 404 rule (Constitution VI) and "separate histories for the same identifier" (spec FR-018 as first drafted) conflict: if a second user is refused, they never get a history. The binding gives the 404. The composite memory key is defense in depth, so even a registry bug cannot let two users share a history. The spec wording for FR-018 and User Story 4 was corrected to match (see the Spec correction note in [plan.md](plan.md)).
- **Residual risk**: A user who probes with an unused identifier binds it. Probing therefore reveals whether an identifier is in use, but only for identifiers that are guessable. The web client generates random v4 UUIDs with `crypto.randomUUID()` (122 bits of entropy), so guessing a real user's identifier is not feasible. The backend validation (`ChatRequest`) enforces only the canonical UUID format, not randomness, so a caller who picks a predictable identifier can have it probed. This is the caller's own choice and is accepted.
- **Alternatives**: Memory keyed by identifier alone. Rejected: removes defense in depth. A registry in a database. Rejected: Constitution V forbids persisting ownership bindings.
- **Evidence**: Existing `ConversationRegistry` is keyed by `conversationId` with `tryBeginTurn` using `computeIfAbsent` (**Verified** in `backend/src/main/java/ai/caramel/support/chat/ConversationRegistry.java`).

## A-11. Rate limits

- **Decision**:
  - Extract the existing sliding-window logic from `RateLimitFilter` into a reusable `SlidingWindowLimiter`.
  - Chat: the filter keys on the authenticated user ID instead of the client address. Default stays at 20 per minute.
  - Auth: a second filter limits `POST /api/auth/login`, `/register`, and the two reset paths per client address. Defaults, all configurable: sign-in 10 per minute, sign-up 5 per hour, reset request 5 per hour.
  - Per-account throttling for sign-in is separate (A-12).
- **Rationale**: The existing filter already implements the contract (429 with `Retry-After` and a body). Reusing it avoids a second implementation. Security's filter runs before ordinary servlet filters, so the user is already authenticated when the chat limiter runs. No new dependency is needed. `bucket4j` is in the local cache, but its token-bucket behavior differs from the sliding window already shipped and tested.
- **Trade-off**: A per-address sign-up limit penalizes users behind a shared network. Sign-ups are rare, and this is the PRD's named baseline mitigation against bot sign-ups (section 12). Email verification or CAPTCHA remains an open decision before public deployment (plan, Constitution Check).
- **Evidence**: Existing filter reviewed (**Verified**). Filter ordering relative to Spring Security is **Unverified** until the dependency resolves. Gate: the `RateLimitFilterTest` update.

## A-12. Brute-force protection per account

- **Decision**: A `LoginThrottle` in process memory tracks failed sign-ins per normalized email address, whether or not the address is registered. After 5 failures in 15 minutes, further attempts are refused with `429` and a `Retry-After`. The wait starts at 30 seconds and doubles with each further failure, capped at 15 minutes. A success clears the count. Failures are counted consecutively: the count restarts after a quiet period longer than 15 minutes, which is the data model's purge rule. Counting only failures inside the last 15 minutes would stop the escalation after about 10 minutes, because the older failures leave the window (implemented in `auth/LoginThrottle.java`, tested by `LoginThrottleTest`).
- **Rationale**: Tracking unregistered addresses the same way as registered ones keeps the response identical, so the throttle does not leak registration (FR-008). A capped, increasing delay, not a permanent lockout, limits what an attacker gains by deliberately locking a victim out.
- **Known weakness**: An attacker can still keep a chosen account in a delay of up to 15 minutes by sending bad attempts. This is accepted for the MVP. The spec Assumptions record only that the delay is capped to avoid a permanent lockout. They do not yet state this residual weakness, so confirm it with the user and add it. Mitigations for later: per-address plus per-account tracking, MFA, or CAPTCHA.
- **Evidence**: Thresholds come from the spec Assumptions. State is lost on restart (accepted).

## A-13. Error responses and the existing exception handler

- **Decision**:
  - A JSON authentication entry point returns `401` with code `UNAUTHENTICATED` and no `WWW-Authenticate` header, so a browser never shows a native login dialog.
  - A JSON access-denied handler returns `403` with code `CSRF_INVALID`.
  - Chat adds `CONVERSATION_NOT_FOUND` for `404`.
  - Auth endpoints use their own `AuthErrorCode` and an `AuthApiError` that adds a list of field errors.
  - All bodies keep the `{ code, message, retryAfterSeconds }` shape of the chat endpoints.
  - `GlobalExceptionHandler` gets a rule to re-throw `AccessDeniedException` and `AuthenticationException`.
- **Rationale**: `GlobalExceptionHandler` has a catch-all `@ExceptionHandler(Exception.class)` that returns `500`. If any security exception ever reaches it, a `401` or `403` would turn into a `500`. Re-throwing prevents that.
- **Evidence**: Catch-all handler **Verified** in `backend/src/main/java/ai/caramel/support/web/GlobalExceptionHandler.java`.

## A-14. Authentication with streamed responses

- **Decision**: The controller reads the user ID from the request thread before the stream starts. The reactive pipeline never touches the security context. A test confirms that a long streamed reply completes while the session stays valid, and that signing out in another request does not corrupt a reply already in progress.
- **Rationale**: Security 6 and later also authorizes asynchronous dispatches. The streaming reply is dispatched asynchronously, so the session must still be readable when the stream completes. Capturing the ID early removes any dependence on thread-bound context.
- **Evidence**: **Verified** by `StreamingFlushTest`: the first event is flushed before the stream ends, and a sign-out during a reply does not cut it (`signingOutMidReplyDoesNotCutTheReplyThatIsAlreadyStreaming`). The first run failed: after the session ended, Security authorized the async dispatch that completes the stream, denied it, and the reply was cut with no `done` event. The fix is `dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()` in `SecurityConfig`, so only the original request is authorized. `shouldFilterAllDispatcherTypes` does not exist in Spring Security 7.1.1.

## A-15. Default generated user

- **Decision**: Define our own `UserDetailsService` bean so Spring Boot's default in-memory user is not created. Add a test that fails if the log output contains "Using generated security password".
- **Rationale**: Without a custom user service, Boot creates a user with a random password and prints it to the log at startup. That would put a credential in the log (Constitution II).
- **Evidence**: **Unverified** for Boot 4 until the dependency resolves. The log assertion is the safety net.

## A-16. Logging and the secret scan

- **Decision**:
  - Keep Spring Security logging at the default level, and set `logging.level.org.springframework.security=WARN` explicitly.
  - Log one line per security event (sign-up, sign-in failure, throttle, reset requested) with the user ID or, where needed, the email address. Never log passwords, session IDs, CSRF tokens, or reset tokens.
  - A test captures log output while it signs up, signs in, and fails a sign-in, and fails if the password or session ID appears.
  - Extend `frontend/scripts/verify-no-secrets.mjs` so it also fails if the frontend source uses `localStorage` or `sessionStorage` (Constitution VI). It scans `src/`, because library code in `dist/` may mention these names for unrelated reasons.
- **Evidence**: Existing script reviewed (**Verified**). It currently scans only `dist/` for the provider key.

## A-17. Password reset (User Story 6)

- **Decision**:
  - Token: 32 random bytes from `SecureRandom`, encoded URL-safe. Only its SHA-256 hash is stored, with an expiry (60 minutes) and a used-at time.
  - Link: the emailed link puts the token in the URL fragment (`/#reset=<token>`), so it is never sent to a server log or a `Referer` header. The page reads it and posts it to the confirm endpoint.
  - Mail: SMTP through `spring-boot-starter-mail`, behind a small `ResetMailer` interface. The request handler sends asynchronously, so the response time does not reveal whether the address is registered. Tests use a fake mailer.
  - Sessions: after a successful reset, all other sessions of that user are ended. This needs a `SessionRegistry`, which is added in this story only.
  - Secrets: SMTP host, port, user, and password come from `.env`. Placeholders go in `.env.example`.
- **Rationale**: Hashing the stored token means a database leak does not leak usable reset links. The interface keeps the SMTP provider replaceable, which matters because the provider is not chosen.
- **Gate**: The SMTP provider is an open decision in the PRD (section 13). This story is planned as a separate, last phase. If no provider is chosen, the other five stories ship without it.
- **Alternatives**: Log the reset link in development. Rejected: Constitution II and spec FR-024 forbid reset tokens in logs. Use a local mail catcher instead (quickstart step 11).
- **Evidence**: **Unverified**: no mail library is cached and no provider exists.

## A-18. Frontend structure

- **Decision**:
  - No router library. A small auth state (`loading`, `signedOut`, `signedIn`) chooses between the sign-in form, the sign-up form, and the chat. The password-reset page is chosen by the URL fragment.
  - One shared `apiFetch` helper adds the CSRF header, sends cookies, and reports `401` to the auth state. `chatClient.ts` and the new `authClient.ts` both use it.
  - On a `401` while chatting, the chat stays mounted and a sign-in dialog opens on top. The transcript and the failed reply stay visible. After a successful sign-in the user retries with the existing retry action.
  - If the user signs in again as a different account, the transcript is cleared, a new conversation identifier is created, and the unsent text is put back in the message box. This prevents one user's conversation from being shown to the next user on a shared computer.
  - Explicit sign-out clears all chat state.
- **Rationale**: Keeping the chat mounted is the simplest way to satisfy FR-015 (keep the draft). A router would add a dependency for three screens.
- **Accessibility**: Labeled inputs, `autocomplete` values (`email`, `new-password`, `current-password`), errors in an `aria-live` region, focus moved to the first invalid field, and the dialog traps focus and returns it on close (FR-029).
- **Evidence**: Existing hook and client reviewed (**Verified**): `useChat` holds the transcript and conversation identifier, and `retry` already resends the question above a failed reply.

## A-19. Testing strategy and effect on existing tests

- **Decision**: Add a small test helper that signs a request in as a given user ID (a security request post-processor plus the CSRF token). Update existing tests to use it. Add the tests required by Constitution VI.
- **Existing tests affected** (**Verified** by reading them):
  - Call `chatService.stream(conversationId, message)` and must pass a user ID: `ChatServiceStreamTest`, `ChatServiceFailureTest`, `ConversationIsolationTest`, `MemoryWindowTest`.
  - Send requests through the controller and must authenticate and send a CSRF token: `ChatControllerStreamTest` (a `@WebMvcTest`, so it also needs the security configuration imported), `ClearConversationTest`, `ConversationBusyTest`, `StreamingFlushTest`, `ApiKeyLeakTest`.
  - Key the rate limit by client address and must key by user: `RateLimitFilterTest`.
  - Verify clearing by identifier alone: `ConversationExpiryTest`.
- **Required new tests** (Constitution VI): `401` on both chat endpoints when unauthenticated; `404` for another user's read, continue, and delete; password stored as a hash only; session cookie has `HttpOnly`.
- **Additional tests**: CSRF (`403` without a token), generic sign-in error and equal timing class, throttle backoff, absolute and idle expiry using `MutableClock`, no password or session ID in logs, no generated-password log line, cookie flags, per-user versus per-address limits.
