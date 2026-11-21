<!--
SYNC IMPACT REPORT (temporary scratch for human review; remove before committing)

Version change: 1.0.0 → 2.0.0
Bump rationale: MAJOR. Principle I redefines MVP scope: user authentication and accounts move
from "MUST NOT be built" to in scope, reversing the prior exclusion. Principle V changes the
chat API contract in a backward-incompatible way: POST /api/chat and
DELETE /api/chat/{conversationId} now return 401 to unauthenticated callers, and chat memory is
keyed by user as well as conversationId.
Alternative: MINOR, if maintainers treat this as additive scope expansion. Confirm before merge.

Principle changes:
  - I. Scope Discipline → modified (redefined). Auth moved from excluded to
    in scope (002-prd-user-auth.md section 5). Out-of-scope list extended with the 002 non-goals.
  - II. Secrets Stay in the Backend → modified. Generalized from the API key to all secrets
    (session signing key, SMTP credentials). Passwords, session IDs, and reset tokens added to
    the no-leak rule.
  - III. Grounded Support Persona → unchanged.
  - IV. Resilient, Streamed Provider Calls → modified. Added: authentication does not depend on
    provider availability; expired sessions keep the unsent draft.
  - V. Layered, Configuration-Driven Design → modified. Memory keyed by user and conversationId;
    contract requires authentication; auth is a separate layer; process-local rule scoped to
    chat memory and ownership bindings.
  - VI. Authenticated Access and Account Security → added.

Section changes:
  - Technology and Configuration Constraints → modified. Durable store for accounts; process-local
    rule now names its one exception (accounts).
  - Development Workflow and Quality Gates → modified. Rate-limit gate covers auth endpoints;
    open-registration abuse mitigation gate added.
  - Governance → modified. Source-of-truth references corrected to 001-prd.md and
    002-prd-user-auth.md.

Renamed principles: none. Removed sections: none.
Reference correction (PATCH-level): "prd.md" changed to "001-prd.md", the file that exists on disk.

Follow-up required (outside this command's write scope; the constitution now conflicts with these):
  - CLAUDE.md line 61 still lists "user auth or accounts" as out of scope. Governance says
    CLAUDE.md MUST NOT contradict this constitution, so it must be updated.
  - 001-prd.md section 4 still lists "User authentication / accounts" as out of scope. 002
    declares that it extends 001. Consider annotating 001 to point to 002.

Deferred items (open in 002-prd-user-auth.md section 13; intentionally not decided here):
  - TODO(USER_STORE): durable store technology (002 default: embedded DB for dev, Postgres for
    prod). Must be recorded in 002 before implementation (Principle VI).
  - Session mechanism: server-side cookie (002 default) vs. stateless JWT. Principle VI requires
    only an HttpOnly cookie transport.
  - Session lifetime: 8h idle / 7d absolute is pending confirmation. Not pinned here.
  - Conversation ownership on restart: this version assumes ownership bindings are process-local
    (002 default). Confirm.
  - Open registration policy, email verification, password reset in MVP, anonymous trial,
    bot sign-up mitigation, and social login readiness are unresolved. Not pinned here.

Ratification date assumption: 2026-11-14, the date of first adoption. Confirm if an earlier
adoption date applies.
-->

# Caramel AI Tech Support Chat Assistant Constitution

## Core Principles

### I. Scope Discipline

The MVP scope is defined by `001-prd.md` Section 4, as extended by `002-prd-user-auth.md`. The MVP
MUST include: streamed multi-turn chat, persona and guardrails, a new-chat reset, error states with
retry, and user authentication (sign-up, sign-in, sign-out, session persistence, and per-user access
control) as specified in `002-prd-user-auth.md`.

The following are out of scope and MUST NOT be built unless the user explicitly requests them in
the current task:

- Live account or billing lookups.
- Linking a chat user to a Caramel AI platform account, API keys, or billing data.
- Human agent handoff or ticketing.
- RAG over company documents.
- Persistent chat history across sessions.
- Social login, single sign-on, or enterprise identity (SAML, SCIM).
- Multi-factor authentication.
- Roles, permissions, teams, or organization accounts.
- An admin UI for managing users.

**Rationale**: Fixed scope keeps the MVP shippable and prevents speculative infrastructure. Auth is
the one exclusion reversed in v2.0.0, and only for the scope in `002-prd-user-auth.md`.

### II. Secrets Stay in the Backend

All secrets MUST be read by the backend only. This includes the Google AI Studio key
(`GOOGLE_API_KEY`), any session signing key, and any SMTP credentials. The backend loads them from
a `.env` file at the project root at startup, or from the shell or container environment.

- No secret MUST appear in frontend code, API responses, logs, error messages, or
  browser-visible network traffic. The session cookie is the only credential the browser receives.
- Passwords, session identifiers, and reset tokens MUST NOT appear in frontend code, logs, or API
  responses (see Principle VI).
- `.env` MUST be git-ignored and MUST NOT be committed.
- `.env.example` MUST be committed with a placeholder value for every secret, for example
  `GOOGLE_API_KEY=your-key-here`.
- Source code MUST NOT hardcode any secret.
- The production frontend build MUST pass `npm run verify:no-secrets`.

**Rationale**: The PRD success criteria require that the key never be visible in client code or
traffic. A leaked key cannot be cleanly removed from version history.

### III. Grounded Support Persona

The assistant MUST respond as Caramel AI technical support. Its scope is LLM inferencing: API
usage, model selection, errors, rate limits, latency, billing questions, and integration issues.

- Requests unrelated to that scope MUST be declined politely and redirected to in-scope help.
- The assistant MUST NOT invent account-specific data, pricing, quotas, SLAs, or policies.
- When the assistant lacks reliable information, it MUST say so and point the customer to official
  Caramel AI documentation or the support channel they already use.
- Persona, scope, and guardrail rules MUST live in the backend system prompt. The UI MUST NOT
  alter them.

**Rationale**: A general-purpose LLM fabricates confident answers about pricing and account facts
unless explicit guardrails forbid it. Fabricated billing information damages customer trust.

### IV. Resilient, Streamed Provider Calls

- Assistant replies MUST be streamed from the backend to the client over Server-Sent Events (SSE)
  and rendered token by token.
- The first token SHOULD be visible within approximately 2 seconds under normal conditions.
- Every call to the model provider MUST have an explicit timeout set in configuration.
- Provider failures (unavailable service, timeout, rate limit) MUST be mapped to friendly,
  non-technical messages. Raw provider errors MUST NOT reach the user.
- When the backend or model is unavailable, the UI MUST show an error state with a retry action.
- Sign-in, sign-out, and session restore MUST NOT depend on the model provider. They MUST keep
  working when the provider is unavailable.
- When a session expires during a chat, the UI MUST keep the unsent draft, ask the user to sign in
  again, and let the user resend the message after signing in.

**Rationale**: Streaming is the main mechanism for perceived latency. Explicit timeouts prevent
hung sessions and unbounded waits. Auth that depends on the provider would lock users out during
provider outages.

### V. Layered, Configuration-Driven Design

- The backend MUST keep these layers separate:
  - Controller: HTTP and SSE endpoints.
  - Service: ChatClient orchestration, system prompt, and conversation handling.
  - Spring AI configuration: model, API key binding, and chat memory.
  - Authentication: security configuration, user service, and auth controller.
- Controllers MUST NOT call the model directly.
- The chat service MUST receive only the authenticated user ID. It MUST NOT depend on how
  authentication is implemented.
- The model name `gemma-4-26b-a4b-it` MUST be set in application properties and MUST NOT be
  hardcoded.
- Conversation memory MUST use Spring AI chat memory, held in process memory and keyed by the
  authenticated user ID and the client-supplied `conversationId`. Conversation memory and
  conversation ownership bindings MUST NOT be written to disk or a database in the MVP.
- The API contract MUST match `001-prd.md` Section 8, as extended by `002-prd-user-auth.md`
  Section 9. `POST /api/chat` with body `{ conversationId, message }` returns an SSE stream.
  `DELETE /api/chat/{conversationId}` clears that conversation's memory. Both require an
  authenticated session. Any change to the contract MUST update the PRD first.

**Rationale**: Separating layers keeps the persona, the model choice, and the auth mechanism
independently changeable. Externalized configuration lets the model be swapped without code
changes.

### VI. Authenticated Access and Account Security

- Every chat endpoint MUST require an authenticated session. An unauthenticated call MUST return
  `401`. This MUST be enforced on the backend. Hiding the UI is not sufficient.
- Passwords MUST be stored only as hashes from a slow, salted, adaptive algorithm (bcrypt or
  Argon2, through Spring Security's `PasswordEncoder`). Plain-text passwords MUST NOT be stored,
  logged, or returned.
- Passwords MUST be 12 to 128 characters. Composition rules MUST NOT be imposed. Passwords on a
  common-password list MUST be rejected.
- Session credentials MUST be carried in an `HttpOnly`, `Secure`, `SameSite=Lax` (or stricter)
  cookie. Session tokens MUST NOT be stored in `localStorage` or `sessionStorage`. The `Secure`
  flag MAY be relaxed for local HTTP development, by configuration only.
- Because sessions are cookie-based, state-changing requests (`POST`, `DELETE`) MUST carry a valid
  CSRF token. A missing or invalid token MUST return `403`.
- Sign-in failures MUST return the same message for an unknown email and for a wrong password.
  Sign-in and sign-up attempts MUST be rate limited per client IP address and per account. Failed
  sign-ins MUST trigger a lockout or increasing delay.
- Chat usage MUST be rate limited per authenticated user, not per IP address.
- A conversation MUST be bound to the user who first used its `conversationId`. Any other user's
  request for that conversation MUST return `404`, not `403`, so the response does not reveal that
  the ID exists.
- Logs MUST NOT contain passwords, session identifiers, or reset tokens. Email addresses MAY be
  logged only for security events. If password reset is delivered, reset tokens MUST be single-use
  and time-limited.
- User accounts MUST persist across backend restarts in a durable store. The store technology MUST
  be recorded in `002-prd-user-auth.md` before implementation.
- Before merge, automated tests MUST cover: `401` for unauthenticated calls to both chat endpoints;
  `404` for cross-user read, continue, and delete; password storage as a hash only; and the session
  cookie's `HttpOnly` flag.

**Rationale**: Anonymous access let anyone with a `conversationId` read or delete that
conversation, and let anonymous callers consume the provider quota. Per-user limits remove the
shared-NAT penalty of per-IP limits. Generic sign-in errors prevent account enumeration.

## Technology and Configuration Constraints

- Backend: Java with Spring Boot, Spring AI, and Spring Security.
- Frontend: React with TypeScript, as a single-page application.
- Model: `gemma-4-26b-a4b-it`, served by Google AI Studio. Using any other model requires amending
  this constitution.
- The Spring AI integration path (Google GenAI starter or OpenAI-compatible endpoint), the
  `.env` loading mechanism, and the exact API key variable name MUST be decided and recorded in
  `001-prd.md` before the model is wired into the application.
- Provider connect and response timeouts MUST be set in application properties.
- Chat memory and conversation ownership bindings are process-local. A server restart clears them.
  This is an accepted MVP limitation, not a defect.
- User accounts are the one durable data store in the MVP. They MUST persist across restarts
  (Principle VI). Their store technology is an open decision in `002-prd-user-auth.md` and MUST
  be recorded there before implementation.

## Development Workflow and Quality Gates

- A root `.gitignore` MUST exist before the first commit, and it MUST exclude `.env`.
- `.mcp.json` MUST NOT contain literal credentials. It MUST reference an environment variable
  instead, and that token MUST NOT be copied into any other file.
- Any change to the system prompt, persona, or guardrails MUST be verified against Principle III
  with at least two prompts: one off-topic request and one question about pricing or account
  data that the assistant has no source for.
- Any change to authentication, sessions, or conversation ownership MUST include the automated
  tests listed in Principle VI.
- Pull requests MUST state how the change was verified.
- Before any public deployment, rate limiting and abuse protection for the chat and auth endpoints
  MUST be decided and implemented. Per-user chat limits and per-IP plus per-account sign-in and
  sign-up limits form the baseline. Bot sign-up mitigation (stricter sign-up limits, email
  verification, or CAPTCHA) MUST be decided before public deployment with open registration.
- Once the Spring Boot and React projects are scaffolded, `CLAUDE.md` MUST be updated with the real
  build, lint, and test commands, including how to run a single test. Until then, no build or test
  commands may be assumed.

## Governance

This constitution supersedes other practices in this repository where they conflict.
`001-prd.md` and `002-prd-user-auth.md` are the sources of truth for scope and design. `CLAUDE.md`
provides runtime guidance for agents and MUST NOT contradict this constitution.

**Amendment procedure**: An amendment is a change to this file. It MUST include an updated Sync
Impact Report, a stated rationale, and the version bump type. It requires maintainer approval
before merge. Any PRD change that alters a principle MUST be made in the same change.

**Versioning policy** (semantic versioning):
- MAJOR: removing or redefining a principle or governance rule in a backward-incompatible way.
- MINOR: adding a principle or section, or materially expanding guidance.
- PATCH: clarifications, wording, typo fixes, and non-semantic refinements.

**Compliance review**: Every pull request and code review MUST verify compliance with Principles
I through VI and with the Technology and Quality Gates sections. Each violation MUST be fixed, or
waived in the pull request with a recorded reason. Any complexity beyond the PRDs MUST be justified
in writing.

**Version**: 2.0.0 | **Ratified**: 2026-11-14 | **Last Amended**: 2026-11-21
