# Implementation Plan: User Authentication for the Caramel AI Tech Support Chat

**Branch**: `002-user-auth` (no git branch created; the repository is not under version control) | **Date**: 2026-11-21 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-user-auth/spec.md`, built on `002-prd-user-auth.md`

## Summary

Require sign-in before anyone can use the chat. A visitor registers or signs in with an email address and a password. The backend keeps the signed-in user in a server-side session, carried by an `HttpOnly` cookie, and protects every state-changing call with a CSRF token. Each conversation is bound to the user who first used its identifier, and chat memory is stored per user. The chat message limit moves from per-address to per-user, and sign-in and sign-up get their own per-address and per-account limits.

Technical approach, with the reasoning in [research.md](research.md):

- **Spring Security 7** (managed by Boot 4.1.1) provides the filter chain, CSRF, session handling, and logout (A-2, A-6).
- **Server-side session cookie**, 8 hours idle and 7 days absolute (A-1, A-7). Cookie attributes are `HttpOnly`, `SameSite=Lax`, and `Secure` by default, with a documented local override (A-8).
- **Argon2id** password hashing, chosen over bcrypt because the policy allows 128 characters and bcrypt reads only 72 bytes (A-3).
- **User store**: plain JDBC. Embedded H2 for development and tests, PostgreSQL for production (A-9).
- **Ownership**: the existing `ConversationRegistry` gains an owner. A foreign user gets `404`. Memory is keyed by user and conversation (A-10).
- **Rate limits**: reuse the existing sliding-window logic for per-user chat and per-address auth limits. A small throttle slows repeated failed sign-ins per account (A-11, A-12).
- **Frontend**: an auth state and three new screens, with no router library. The chat stays mounted when a session expires, so the user keeps their messages (A-18).
- **Password reset** is a separate last phase because it needs an email provider that has not been chosen (A-17).

### Spec correction made during planning

While designing ownership, a conflict surfaced in the first draft of the spec. FR-017 says another user's request for a conversation returns "not found". FR-018 and User Story 4 scenarios 3 and 4 said two users who use the same identifier each get their own history. Both cannot hold, because a user who is refused never gets a history. The constitution requires the "not found" rule, so [spec.md](spec.md) was changed: FR-018 now requires that histories can never mix, and the two scenarios now describe two users with separate conversations, and a second user reusing an identifier after the owner cleared it. The success criteria are unchanged.

## Technical Context

**Language/Version**: Java 21 (installed: OpenJDK 21.0.13); TypeScript 5.x on Node 22 (installed: v22.22.2)

**Primary Dependencies**:
- Backend, existing: Spring Boot 4.1.1, Spring AI 2.0.1, `spring-boot-starter-webmvc`, `spring-boot-starter-validation`.
- Backend, new: `spring-boot-starter-security`, `spring-boot-starter-jdbc`, `h2` (runtime), the PostgreSQL JDBC driver (runtime), `org.bouncycastle:bcprov-jdk18on` (required by Argon2), `spring-security-test` (test). `spring-boot-starter-mail` is added only by the password-reset phase.
- Frontend: no new dependencies. React 19, Vite, Vitest, React Testing Library, as before.

**Storage**: Accounts and reset tokens in a relational database (H2 file for development, PostgreSQL for production). Sessions, conversation ownership, chat memory, throttle state, and rate-limit windows stay in process memory (Constitution V and VI).

**Testing**:
- Backend: JUnit 5, Spring Boot Test, MockMvc with `spring-security-test`, Reactor `StepVerifier`. Existing tests must be updated (research A-19).
- Frontend: Vitest and React Testing Library.
- The four tests required by Constitution VI are listed in [quickstart.md](quickstart.md).

**Target Platform**: Linux server (JVM) plus a static browser bundle.

**Project Type**: Web application (backend and frontend).

**Performance Goals**: Sign-in completes within about 1 second including hashing (SC-004). First chat token within 2 seconds, unchanged (SC-005). One password hash about 250 ms or less on the target machine (to be measured, research A-3).

**Constraints**:
- No secret, password, session identifier, CSRF token, or reset token in logs, in responses other than the cookie, or in the frontend bundle (FR-005, FR-013, FR-024).
- No credential in `localStorage` or `sessionStorage` (Constitution VI).
- Chat service receives only the user ID and does not import Spring Security (Constitution V).
- Chat memory and ownership bindings are not persisted (Constitution V).
- Authentication must not depend on the model provider (FR-028).

**Scale/Scope**: MVP, single instance. Sessions, throttles, and ownership are per instance. Running several instances would need a shared session store and shared limiters. That is out of scope.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle / rule | Pre-research | Post-design | How the plan satisfies it |
|---|------------------|--------------|-------------|---------------------------|
| I | Scope Discipline | PASS | PASS | Only the in-scope list of `002-prd-user-auth.md` is built. No social login, MFA, roles, admin UI, account deletion, or profile screens. Email verification and CAPTCHA are not built. |
| II | Secrets stay in the backend | PASS | PASS | New secrets (database password, SMTP credentials) are read from `.env` or the environment, with placeholders in `.env.example`. There is no session signing key, because sessions are server-side. A test fails if a password, session ID, or generated security password appears in logs (A-15, A-16). The secret scan is extended (A-16). |
| III | Grounded persona | PASS | PASS | The system prompt, persona, and guardrails are untouched. |
| IV | Resilient streamed calls | PASS | PASS (one test pending) | Sign-in, sign-out, and session restore never call the provider (FR-028). An expired session keeps the draft (A-18). Streamed replies and timeouts are unchanged. Behavior of an ending session during a stream is unverified and has a gate (A-14). |
| V | Layered, configuration-driven design | PASS | PASS | New `auth` package with its own controller, service, and repository. `chat` receives a user ID string and has no Spring Security import, enforced by a test. Model name unchanged. Memory stays in process, keyed by user and conversation. The contract change is documented in [contracts/chat-api-auth-changes.md](contracts/chat-api-auth-changes.md). |
| VI | Authenticated access and account security | PASS | PASS | Every chat endpoint requires a session (`401`). Argon2id hashes only. 12 to 128 characters with a common-password check and no composition rules. `HttpOnly`, `Secure`, `SameSite=Lax` cookie, with relaxation by configuration only. CSRF token on `POST` and `DELETE` (`403`). Same message for unknown email and wrong password. Per-address and per-account limits with increasing delay. Per-user chat limit. Foreign conversations return `404`. Reset tokens are single-use and expire. |
| Tech | Technology constraints | PASS | PASS | Java, Spring Boot, Spring AI, and Spring Security. React and TypeScript single-page app. Model unchanged. |
| Tech | Durable store for accounts is recorded in `002-prd-user-auth.md` before implementation | **OPEN** | **OPEN** | Decided in research A-9 (H2 file for development, PostgreSQL for production, JDBC). The first implementation step MUST record it, and the contract changes, in the PRD before any code. This plan does not edit the PRD. Who hosts PostgreSQL is still the user's decision. |
| Workflow | Auth changes include the required automated tests | PASS | PASS | Four required tests plus the extra tests in research A-19, listed in the quickstart. |
| Workflow | Rate limiting and abuse protection decided before public deployment | PASS | **OPEN (partial)** | Per-user chat limits and per-address plus per-account sign-in and sign-up limits are planned (A-11, A-12). The constitution also requires a decision on bot sign-up mitigation beyond rate limits (stricter limits, email verification, or CAPTCHA). The plan implements only the per-address sign-up limit. The rest is a decision for the user before any public deployment. |
| Workflow | `.env.example` has a placeholder for every secret | OPEN | OPEN | Part of the foundation phase. |
| Workflow | `CLAUDE.md` updated with real commands | OPEN | OPEN | No new commands are planned, except the new test classes. `CLAUDE.md` scope text already lists auth as in scope. The final task checks that its architecture and config notes match. |
| Workflow | `.gitignore` excludes `.env` | PASS | PASS | Existing. The plan adds `backend/data/` for the development database file. |

**Gate result**: no violations. Three items are open work, not violations: recording the store decision in the PRD, the bot sign-up decision before public launch, and the `.env.example` and `CLAUDE.md` updates.

### Additions beyond the PRD (justified, not violations)

| Addition | Why needed | Simpler alternative rejected because |
|----------|-----------|--------------------------------------|
| Absolute-session-lifetime filter | The container tracks idle time only. FR-011 needs a 7-day maximum. | A longer idle limit alone would let an active session live forever. |
| `SlidingWindowLimiter` extracted from `RateLimitFilter` | Three limiters (chat, auth, throttle) need the same logic. | Copying the logic three times would triple the code to test. |
| Common-password list file | FR-004 needs a list, and an external service adds a privacy and availability risk (A-4). | A hard-coded short list in code is too small to be useful. |
| `bcprov` dependency | Argon2 in Spring Security needs it (A-3). | bcrypt would need a pre-hash step to honor the 128-character maximum. |
| `SessionRegistry` (password-reset phase only) | FR-027 must end every other session of a user. | Without it, sessions of a user who reset their password stay valid. |
| `DELETE` of an unknown identifier returns `404` | Prevents telling "unknown" from "someone else's" (A-10). | Keeping `204` would let a user probe which identifiers exist. |

## Project Structure

### Documentation (this feature)

```text
specs/002-user-auth/
├── plan.md                           # This file (/speckit-plan command output)
├── research.md                       # Phase 0 output
├── data-model.md                     # Phase 1 output
├── quickstart.md                     # Phase 1 output
├── contracts/
│   ├── auth-api.openapi.yaml         # New auth endpoints
│   └── chat-api-auth-changes.md      # Changes to the 001 chat contract
├── checklists/
│   └── requirements.md               # From /speckit-specify
├── spec.md
└── tasks.md                          # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

New files are marked `+`. Existing files that change are marked `~`.

```text
backend/
├── pom.xml                                                    ~ new dependencies
├── src/main/java/ai/caramel/support/
│   ├── auth/                                                  + authentication layer (Constitution V)
│   │   ├── AuthController.java                                + register, login, me
│   │   ├── AuthService.java                                   + sign-up rules, throttle, sign-in
│   │   ├── AuthenticatedUser.java                             + record (id, email): the principal
│   │   ├── User.java                                          + record
│   │   ├── UserRepository.java                                + JDBC access to users
│   │   ├── JdbcUserDetailsService.java                        + also stops the default generated user
│   │   ├── PasswordPolicy.java                                + length, normalization, common list
│   │   ├── EmailAddress.java                                  + normalization and format check
│   │   ├── LoginThrottle.java                                 + per-account delay
│   │   ├── AuthErrorCode.java, AuthApiError.java              + error codes and body
│   │   ├── RegisterRequest.java, LoginRequest.java            + request records
│   │   ├── JsonAuthenticationEntryPoint.java                  + 401 body
│   │   ├── JsonAccessDeniedHandler.java                       + 403 body
│   │   └── reset/                                             + password reset (last phase)
│   │       ├── PasswordResetController.java, PasswordResetService.java
│   │       ├── PasswordResetTokenRepository.java
│   │       └── ResetMailer.java, SmtpResetMailer.java
│   ├── config/
│   │   ├── SecurityConfig.java                                + filter chain, CSRF, logout, session rules
│   │   ├── AuthProperties.java                                + support.auth.* settings
│   │   ├── PasswordEncoderConfig.java                         + Argon2id in a delegating encoder
│   │   ├── CsrfCookieFilter.java                              + always sets the XSRF-TOKEN cookie
│   │   ├── AbsoluteSessionTimeoutFilter.java                  + 7-day limit
│   │   └── SupportProperties.java                             ~ rate-limit keys per user
│   ├── chat/
│   │   ├── ChatService.java                                   ~ stream(userId, conversationId, message), clear(userId, conversationId)
│   │   ├── ConversationRegistry.java                          ~ owner binding, atomic first use
│   │   ├── ConversationNotFoundException.java                 + maps to 404
│   │   ├── ConversationExpiryTask.java                        ~ releases bindings by owner
│   │   └── ChatErrorCode.java                                 ~ UNAUTHENTICATED, CSRF_INVALID, CONVERSATION_NOT_FOUND
│   └── web/
│       ├── ChatController.java                                ~ reads the user ID, passes it on
│       ├── RateLimitFilter.java                               ~ keys by user ID, uses SlidingWindowLimiter
│       ├── AuthRateLimitFilter.java                           + per-address limits on auth paths
│       ├── SlidingWindowLimiter.java                          + extracted from RateLimitFilter
│       └── GlobalExceptionHandler.java                        ~ 404 mapping, re-throws security exceptions
├── src/main/resources/
│   ├── application.properties                                 ~ session, cookie, datasource, support.auth.*
│   ├── schema.sql                                             + users, password_reset_tokens
│   └── auth/common-passwords.txt                              + common passwords of 12+ characters
└── src/test/java/ai/caramel/support/
    ├── auth/                                                  + AuthControllerTest, PasswordEncoderTest, PasswordPolicyTest,
    │                                                            UserRepositoryTest, LoginThrottleTest, SessionCookieTest,
    │                                                            SessionExpiryTest, CsrfTest, AuthLogLeakTest, reset tests
    ├── chat/                                                  + ConversationOwnershipTest, ChatLayerHasNoSecurityImportsTest
    │                                                          ~ ChatService*, ConversationIsolation, MemoryWindow, ConversationExpiry
    ├── web/                                                   + ChatEndpointsAuthTest, AuthRateLimitFilterTest
    │                                                          ~ ChatControllerStream, ClearConversation, ConversationBusy,
    │                                                            RateLimitFilter, StreamingFlush, ApiKeyLeak
    └── AuthTestSupport.java                                   + signs a request in as a user, adds the CSRF token

frontend/
├── scripts/verify-no-secrets.mjs                              ~ also scans src/ for storage APIs
├── src/
│   ├── auth/                                                  + authentication state
│   │   ├── AuthProvider.tsx, useAuth.ts                       + status, user, sign in/up/out, expired state
│   │   ├── authClient.ts                                      + register, login, logout, me, reset calls
│   │   └── apiFetch.ts                                        + adds CSRF header and cookies, reports 401
│   ├── components/
│   │   ├── SignInForm.tsx, SignUpForm.tsx                     + labeled fields, field errors, retry time
│   │   ├── SessionExpiredDialog.tsx                           + sign-in dialog over the kept chat
│   │   ├── UserBar.tsx                                        + shows the email and "Sign out"
│   │   ├── ForgotPasswordForm.tsx, ResetPasswordForm.tsx      + password reset (last phase)
│   │   └── ChatWindow.tsx                                     ~ header gets UserBar
│   ├── api/
│   │   ├── chatClient.ts                                      ~ uses apiFetch, maps 401 and 404
│   │   └── errorMessages.ts                                   ~ three new messages
│   ├── hooks/useChat.ts                                       ~ expired-session handling, user switch
│   ├── types/chat.ts                                          ~ three new error codes
│   └── App.tsx                                                ~ chooses sign-in, sign-up, or chat
└── tests/                                                     + auth, forms, dialog, apiFetch, useChat session tests
                                                               ~ existing chat tests keep passing

.env.example                                                   ~ placeholders for the new variables
.gitignore                                                     ~ backend/data/
002-prd-user-auth.md                                           ~ record store decision and contract changes (first step)
```

**Structure Decision**: The existing web-application layout (`backend/`, `frontend/`) is kept. Authentication is a new `auth` package beside `chat`, `web`, and `config`, so Constitution V's layer separation holds. The chat service takes a user ID string and has no dependency on how that ID was obtained.

## Implementation Phasing (input to `/speckit-tasks`)

Phases follow the user-story priorities. Each phase ends with its tests passing.

| Phase | Content | Stories | Notes |
|-------|---------|---------|-------|
| 0. Gates | Record the store decision and contract changes in `002-prd-user-auth.md`. Add dependencies and confirm they resolve. Run the encoder test (quickstart step 2). | | Blocks everything. Constitution V (contract changes) and VI (store technology) require the PRD update before any implementation. |
| 1. Foundation | Schema, user repository, password policy and encoder, security filter chain, JSON 401 and 403, CSRF cookie, session settings, the `AuthTestSupport` helper, and fixes to existing tests so they pass again. | | Existing tests must pass before new behavior is added. |
| 2. Sign-up, sign-in, sign-out, persistence | Auth endpoints, absolute-lifetime filter, frontend auth state, forms, user bar. | 1, 2, 3 (restore) | MVP slice. |
| 3. Ownership | Owner binding, `404` handling, composite memory key, `DELETE` change, expiry task update, frontend `404` handling. | 4 | |
| 4. Expired session UX | `401` handling in the chat client, sign-in dialog over the kept chat, user switch. | 3 | |
| 5. Limits | Extract the limiter, per-user chat limit, per-address auth limits, login throttle, frontend retry time. | 5 | |
| 6. Hardening | Log-leak tests, secret scan extension, streaming-with-session test, accessibility pass, `CLAUDE.md` check. | all | |
| 7. Password reset | Tokens, mailer, endpoints, `SessionRegistry`, frontend forms. | 6 | Starts only when an email provider is chosen. May be dropped from this release. |

## Decisions the user still owns

These are not blocking the plan, but each blocks something later:

1. **Who hosts PostgreSQL** (needed before production deployment, and to record in the PRD).
2. **Email provider for password reset** (blocks phase 7 only).
3. **Bot sign-up mitigation beyond the per-address limit**: email verification, CAPTCHA, or an allow-list (constitution requires a decision before public deployment with open registration).
4. **Duplicate-email wording**: the plan keeps the spec's trade-off, where sign-up reveals that an address is registered (FR-006), until email verification exists.

## Complexity Tracking

No constitution violations to justify. The additions in the table above are each tied to a spec requirement or a constitution rule.
