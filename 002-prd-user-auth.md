# PRD: User Authentication for the Caramel AI Tech Support Chat

Extends `001-prd.md`. That PRD lists "User authentication / accounts" as out of scope. This PRD reverses that exclusion for authentication only. Everything else in `001-prd.md` that is out of scope stays out of scope, including live account or billing lookups, human handoff, RAG, and persistent chat history.

## 1. Objective

Require users to sign in before they can use the chat assistant. Authentication gives each conversation an owner, lets the backend limit usage per user instead of per IP address, and protects the Gemma provider quota from anonymous abuse.

## 2. Background and Problem

Today the chat endpoints are public. Any client that knows the URL can send messages, and the only protections are a per-client rate limit of 20 messages per minute and a client-supplied `conversationId`. This causes three problems:

- **No ownership**: Anyone who guesses or learns a `conversationId` can read, continue, or delete that conversation.
- **Weak abuse control**: Rate limiting by IP address (or `X-Forwarded-For`) punishes users behind shared NATs and does not stop a determined caller who rotates IPs.
- **No path to account-aware support**: Personalized support, such as the live account lookups deferred in `001-prd.md`, needs a known user identity first.

## 3. Target Users

- Developers and technical teams who already use Caramel AI and want support.
- Prospective customers who want to try the assistant, who need a low-friction way to sign up.

## 4. Goals and Non-Goals

### Goals

- Only authenticated users can call the chat endpoints.
- Each conversation is bound to the user who created it, and no other user can access it.
- Sign-up, sign-in, and sign-out work in the existing React SPA without a full page reload.
- Credentials and session tokens are handled securely, and never appear in logs or in the frontend bundle.

### Non-Goals

- Single sign-on or enterprise identity providers (SAML, SCIM).
- Multi-factor authentication. This is a candidate for a later phase.
- Roles, permissions, teams, or organization accounts. All users have the same access.
- Linking a chat user to a Caramel AI platform account, API keys, or billing data.
- Persistent chat history. Chat memory stays in memory, and accounts do not change that.
- Admin UI for managing users.

## 5. Scope

### In scope (MVP)

- Email and password sign-up and sign-in.
- Sign-out.
- Session handling that keeps the user signed in across page reloads.
- Authentication required on `/api/chat` and `/api/chat/{conversationId}`.
- Conversation ownership checks.
- Per-user rate limiting.
- Frontend sign-in and sign-up screens, auth state, and handling of expired sessions.
- Password reset by email (see Open Questions for whether this is MVP).

### Out of scope (for now)

- Social login (Google, GitHub).
- MFA and passkeys.
- Email verification gating (see Open Questions).
- Account deletion and profile editing UI.

## 6. Functional Requirements

1. **Sign up**: A visitor can create an account with an email address and a password. Duplicate emails are rejected without revealing which emails are registered, where practical.
2. **Sign in**: A registered user can sign in with email and password and lands in the chat view.
3. **Sign out**: A signed-in user can sign out. The session is invalidated on the server, and the chat view is replaced by the sign-in screen.
4. **Session persistence**: A signed-in user stays signed in across page reloads until the session expires or the user signs out.
5. **Protected endpoints**: Unauthenticated calls to `/api/chat` and `DELETE /api/chat/{conversationId}` return `401`. The frontend responds by showing the sign-in screen.
6. **Conversation ownership**: A conversation is bound to the user who first used its `conversationId`. A request for a conversation owned by another user returns `404`, so that the response does not confirm that the ID exists.
7. **Memory scoping**: Chat memory is keyed by user and `conversationId`, so two users who send the same `conversationId` get separate histories.
8. **New chat**: The existing "New chat" action keeps working for the signed-in user and clears only that user's conversation.
9. **Per-user rate limit**: The existing limit of 20 messages per minute applies per authenticated user. Unauthenticated sign-in and sign-up attempts are rate limited per client IP address and per account (see section 7).
10. **Session expiry**: When a session expires during a chat, the UI keeps the unsent text, asks the user to sign in again, and lets the user retry the message after signing in.
11. **Password reset** (if in MVP): A user can request a reset link by email, and use it once, within a limited time, to set a new password.

## 7. Non-Functional Requirements

### Security

- **Password storage**: Passwords are hashed with a slow, salted, adaptive algorithm (bcrypt or Argon2 through Spring Security's `PasswordEncoder`). Plain-text passwords are never stored, logged, or returned.
- **Password policy**: Minimum 12 characters, with no composition rules. A maximum length of 128 characters bounds hashing cost. Passwords are checked against a list of common passwords.
- **Session transport**: The session identifier is carried in an `HttpOnly`, `Secure`, `SameSite=Lax` (or stricter) cookie, so that frontend JavaScript cannot read it. Tokens are not stored in `localStorage`.
- **CSRF**: State-changing endpoints (`POST`, `DELETE`) are protected against CSRF. If the cookie-based session is used, a CSRF token is required on those calls.
- **Brute-force protection**: Failed sign-in attempts are rate limited per account and per client IP address, with a lockout or increasing delay. Error messages are the same for an unknown email and a wrong password.
- **Secrets**: Any new secret (such as a session signing key or SMTP credentials) lives in the backend only. It is read from `.env` at the project root, listed with placeholder values in `.env.example`, and never reaches the frontend, API responses, or logs. This follows the constraint in `001-prd.md`.
- **Logging**: Logs never contain passwords, session identifiers, or reset tokens. Email addresses are logged only where needed for security events.
- **Transport**: Production deployment is HTTPS only. Local development over HTTP is allowed with the `Secure` cookie flag relaxed by configuration.

### Latency

- Sign-in completes within about 1 second under normal conditions, excluding the cost of the deliberately slow password hash.
- The authentication check must not delay the first streamed token noticeably, and the 2-second first-token target from `001-prd.md` still applies.

### Reliability

- Authentication state does not depend on the LLM provider. The sign-in screen works when Google AI Studio is unavailable.
- Session loss on a backend restart is acceptable only if user accounts survive (see section 8).

### Maintainability

- Authentication is a separate layer (security configuration, user service, auth controller) and does not leak into the chat service beyond receiving the authenticated user ID.
- The existing split between controller, service, and Spring AI configuration stays intact.

### Accessibility

- Auth forms are keyboard-navigable, have labeled fields, announce errors to screen readers, and support password managers (`autocomplete` attributes).

## 8. High-Level Architecture

```
React SPA --(HTTP + session cookie, CSRF token)--> Spring Boot
                                                    |-- Spring Security filter chain (authn, CSRF, rate limits)
                                                    |-- Auth controller (sign-up, sign-in, sign-out, me)
                                                    |-- User service --> user store
                                                    |-- Chat controller / service (user-scoped memory)
                                                         --> Google AI Studio --> gemma-4-26b-a4b-it
```

- **Backend**: Spring Security added to the Spring Boot app. It uses a server-side session with a cookie (the recommended default). The chat service receives the authenticated user ID and uses it to scope chat memory and ownership checks.
- **User store**: Accounts must survive restarts, unlike chat memory. This needs a durable store, which `001-prd.md` did not require. The recommended default is an embedded file-backed database for development and a managed relational database (such as Postgres) for production, accessed through Spring Data JPA or JDBC. See Open Questions.
- **Frontend**: An auth context holds the signed-in user, calls `GET /api/auth/me` on load to restore state, and routes between sign-in, sign-up, and chat views. The existing SSE client sends credentials and the CSRF header, and handles `401` by returning to sign-in.
- **Dev setup**: The Vite dev server already proxies `/api` to the backend, so cookies work as same-origin in development. No CORS changes are expected.

## 9. API (Draft)

| Method | Path                         | Auth     | Description                                                          |
|--------|------------------------------|----------|----------------------------------------------------------------------|
| POST   | `/api/auth/register`         | Public   | Create an account. Body: `{ email, password }`. Returns `201` and starts a session. |
| POST   | `/api/auth/login`            | Public   | Sign in. Body: `{ email, password }`. Returns `200` and sets the session cookie. |
| POST   | `/api/auth/logout`           | Required | Invalidate the session. Returns `204`.                               |
| GET    | `/api/auth/me`               | Required | Return `{ id, email }` for the current user, or `401`.               |
| POST   | `/api/auth/password-reset`   | Public   | Request a reset email. Always returns `202`. (If in MVP.)            |
| POST   | `/api/auth/password-reset/confirm` | Public | Body: `{ token, newPassword }`. (If in MVP.)                    |
| POST   | `/api/chat`                  | Required | Existing endpoint. Now scoped to the signed-in user.                 |
| DELETE | `/api/chat/{conversationId}` | Required | Existing endpoint. Clears the signed-in user's conversation.         |

Error responses use the same problem-detail style as the chat endpoints. `401` means not signed in, `403` means a missing or invalid CSRF token, `404` means unknown or not-owned conversation, and `429` means rate limited.

## 10. UX Notes

- **Signed-out state**: The app opens on the sign-in screen, with a link to sign up. There is no anonymous chat.
- **Signed-in state**: The chat header shows the user's email and a "Sign out" action beside "New chat".
- **Errors**: Sign-in failures show one generic message ("Email or password is incorrect"). Validation errors on sign-up show next to the field. Rate-limit responses show a retry time.
- **Expired session**: A banner or modal explains that the session expired and keeps any draft message.

## 11. Success Criteria

- An unauthenticated request to `/api/chat` or `DELETE /api/chat/{conversationId}` returns `401`, verified by automated tests.
- A user cannot read, continue, or delete another user's conversation, even with the same `conversationId`, verified by automated tests.
- Passwords are stored only as hashes, and the session cookie is `HttpOnly`, verified by tests and by inspecting the response headers.
- A new user can sign up, chat, reload the page and still be signed in, then sign out, in one pass without errors.
- No password, session identifier, or reset token appears in logs, API responses (other than the cookie), or the production frontend build. The existing `npm run verify:no-secrets` check is extended to cover this where applicable.
- The 2-second first-token target still holds with authentication enabled.

## 12. Risks

- **Scope creep**: Authentication invites requests for roles, MFA, social login, and account pages. These are listed as non-goals to keep the MVP small.
- **New durable storage**: Adding a user database changes the deployment story, which until now needed nothing persistent. This must be agreed before implementation.
- **Email delivery**: Password reset and email verification need an SMTP provider, which adds a secret, configuration, and a failure mode.
- **SSE and CSRF**: The streaming `POST` must carry the CSRF token and cookie. This needs care in the existing fetch-based SSE client.
- **Bot sign-ups**: Open registration lets bots create many accounts and multiply the per-user rate limit. Mitigations include per-IP sign-up limits, email verification, or CAPTCHA.

## 13. Open Questions

- **User store**: Embedded database for development plus Postgres for production, or a single store for both? Who hosts it?
- **Session mechanism**: Server-side session cookie (recommended, simplest with Spring Security) or stateless JWT?
- **Open registration**: Can anyone sign up, or is sign-up limited to an allow-list of emails or domains?
- **Email verification**: Required before first chat, optional, or not in MVP?
- **Password reset**: In MVP, or deferred? It requires an SMTP provider.
- **Anonymous trial**: Should visitors get a few free messages before sign-up, which would be useful for prospective customers, or is sign-in strictly required?
- **Session lifetime**: Suggested defaults are 8 hours idle and 7 days absolute. Confirm.
- **Conversation ownership on restart**: Chat memory is lost on restart, so ownership records are too. This is acceptable under `001-prd.md`. Confirm that no change is wanted.
- **Social login**: Is Google or GitHub sign-in expected soon enough to shape the user model now, for example by allowing accounts without a password?
