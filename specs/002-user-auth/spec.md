# Feature Specification: User Authentication for the Caramel AI Tech Support Chat

**Feature Branch**: `002-user-auth` (no git branch created; repository is not under version control)

**Created**: 2026-11-21

**Status**: Draft

**Input**: User description: "create a new spec 002 inside @specs/ using @002-prd-user-auth.md". Feature: user authentication for the Caramel AI tech support chat (email and password sign-up and sign-in, sign-out, session persistence, per-user conversation ownership, and per-user rate limits), as defined in `002-prd-user-auth.md`, which extends `001-prd.md`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Create an Account and Start Chatting (Priority: P1)

A prospective customer who has never used the assistant visits the chat, creates an account with an email address and a password, and lands directly in the chat view, signed in, ready to ask a question.

**Why this priority**: Sign-up is the entry point for every user. Without it, no one can use the assistant once authentication is required.

**Independent Test**: Starting from the sign-in screen, create a new account, send one in-scope question, and confirm a reply arrives. No other story needs to be built to run this test.

**Acceptance Scenarios**:

1. **Given** a visitor is on the sign-in screen, **When** they choose "Sign up" and enter a valid email address and a password of at least 12 characters, **Then** the account is created and the chat view opens with the user signed in, without a full page reload.
2. **Given** a visitor enters an email address that is not well formed, **When** they submit the sign-up form, **Then** an error message appears next to the email field and no account is created.
3. **Given** a visitor enters a password shorter than 12 characters, **When** they submit the sign-up form, **Then** an error message appears next to the password field and no account is created.
4. **Given** a visitor enters a password that appears on the common-passwords list, **When** they submit the sign-up form, **Then** they are asked to choose a different password and no account is created.

---

### User Story 2 - Sign In and Sign Out (Priority: P1)

A returning customer signs in with their email and password to reach the chat. When they finish, they sign out, and the chat is replaced by the sign-in screen.

**Why this priority**: Returning users are the main audience. Sign-out is required so that shared devices do not expose a conversation.

**Independent Test**: With an existing account, sign in, confirm the chat view opens, sign out, and confirm the sign-in screen replaces the chat without a page reload.

**Acceptance Scenarios**:

1. **Given** a registered user is on the sign-in screen, **When** they enter the correct email and password, **Then** the chat view opens.
2. **Given** a registered user enters a wrong password, **When** they submit, **Then** they see one generic message, "Email or password is incorrect", and remain on the sign-in screen.
3. **Given** an email address that is not registered, **When** a visitor submits it with any password, **Then** they see the same generic message as in Acceptance Scenario 2.
4. **Given** a signed-in user, **When** they choose "Sign out", **Then** their session ends, and the chat view is replaced by the sign-in screen.
5. **Given** a user has signed out, **When** they use the browser's back navigation to return to the chat view, **Then** no conversation content is shown and they are asked to sign in.

---

### User Story 3 - Stay Signed In Across Reloads and Recover From Expiry (Priority: P1)

A signed-in customer reloads the page or returns later and is still signed in. If their session expires while they are typing a message, they are asked to sign in again and can send the message they had written without retyping it.

**Why this priority**: Customers switch tabs and reload often while troubleshooting. Repeated sign-ins or lost drafts would make the product frustrating during incidents.

**Independent Test**: Sign in, reload the page, and confirm the user is still signed in. Then let the session expire, type a message, submit it, sign in again, and confirm the draft is retained and can be sent.

**Acceptance Scenarios**:

1. **Given** a signed-in user, **When** they reload the page, **Then** the chat view opens without asking them to sign in again.
2. **Given** a signed-in user who has been idle beyond the idle limit, **When** they next send a message, **Then** the chat shows a message that the session has expired, asks them to sign in, and keeps the unsent text.
3. **Given** a session has expired with an unsent draft, **When** the user signs in again, **Then** the draft is still in the message box and can be sent without retyping.

---

### User Story 4 - Keep Each User's Conversations Private (Priority: P1)

Each customer's conversation belongs only to them. Another user who learns or guesses the same conversation identifier cannot read, continue, or clear it. A visitor who is not signed in cannot use the chat at all.

**Why this priority**: Without ownership, anyone with a conversation identifier can read or alter another customer's support history, and anonymous traffic can consume the AI service quota.

**Independent Test**: Create two accounts. Have the first account send a message with a given conversation identifier. Have the second account send, read, and clear that same identifier, and confirm it is refused as not found. Separately, send a message while signed out and confirm it is refused.

**Acceptance Scenarios**:

1. **Given** a visitor who is not signed in, **When** they try to send a message or clear a conversation, **Then** the request is refused and the sign-in screen is shown.
2. **Given** user A has a conversation with identifier X, **When** user B sends a message using identifier X, **Then** user B's request is refused as "not found", and the response does not reveal that identifier X exists.
3. **Given** user A and user B each have their own conversation, **When** each sends messages, **Then** each sees only their own history, and neither user's earlier messages influence the other's replies.
4. **Given** user A clears conversation X, **When** user B later uses identifier X, **Then** user B starts a new, empty conversation with none of user A's earlier messages.

---

### User Story 5 - Fair Usage and Brute-Force Protection (Priority: P2)

Each signed-in customer has their own message allowance, so one customer sharing a client address with others is not blocked by someone else's usage. Repeated failed sign-in attempts against one account are slowed down, so an attacker cannot guess passwords quickly.

**Why this priority**: Per-user limits replace the current per-address limit, which penalizes users behind shared networks. Brute-force protection is needed before the service is opened to public sign-up, but it does not block the first working version of sign-up and chat.

**Independent Test**: From one client address, have two accounts each send messages up to the limit and confirm both succeed. Then send one more message from one account and confirm it is refused with a retry time. Separately, submit repeated wrong passwords for one account and confirm later attempts are delayed.

**Acceptance Scenarios**:

1. **Given** a signed-in user has sent 20 messages within one minute, **When** they send a 21st, **Then** the message is refused with a message showing how long to wait before retrying.
2. **Given** two users share the same client address, **When** each sends 20 messages within one minute, **Then** both succeed, because the limit applies to each user, not to the address.
3. **Given** wrong passwords are submitted repeatedly for one account, **When** the fifth failed attempt occurs within 15 minutes, **Then** further attempts for that account are delayed, with the delay increasing after each further failure, up to 15 minutes.
4. **Given** one client address sends sign-in or sign-up requests faster than the allowed rate, **When** the limit is exceeded, **Then** further requests are refused with a retry time until the limit window passes.

---

### User Story 6 - Reset a Forgotten Password (Priority: P3)

A customer who has forgotten their password requests a reset link by email, opens it, and sets a new password. The link works only once and only for a limited time.

**Why this priority**: Password reset is listed in the MVP scope, but sign-up, sign-in, and chat work without it. It depends on an email delivery service that is not yet chosen.

**Independent Test**: Request a reset for an existing account, use the link from the email to set a new password, sign in with the new password, and confirm the same link is refused on a second use.

**Acceptance Scenarios**:

1. **Given** a visitor enters an email address on the "Forgot password" form, **When** they submit it, **Then** they see the same confirmation message whether or not the address is registered.
2. **Given** a registered user opens a valid reset link, **When** they enter a new password that meets the password rules, **Then** the password is changed and they can sign in with it.
3. **Given** a reset link has already been used, **When** someone opens it again, **Then** the reset is refused and the user is directed to request a new link.
4. **Given** a reset link is older than its time limit, **When** the user opens it, **Then** the reset is refused and the user is directed to request a new link.
5. **Given** a password has just been reset, **When** the account had other active sessions, **Then** those sessions are ended.

---

### Edge Cases

- What happens when a visitor signs up with an email address that is already registered? The sign-up is refused with a neutral message that directs the user to sign in or reset their password. See Assumptions for the trade-off this creates.
- What happens when the same email address is entered with different letter case (for example, "Ana@Example.com" and "ana@example.com")? Both refer to the same account.
- What happens when the AI service is unavailable? Sign-up, sign-in, sign-out, and the sign-in screen still work. Only sending a message shows the existing retry message from `001-prd.md`.
- What happens when the service restarts? User accounts survive the restart. Active sessions and conversations do not, so users must sign in again and start their chats over.
- What happens when a user signs out in one browser tab while the chat is open in another? The other tab shows the sign-in screen on its next request and does not continue the conversation.
- What happens when a user sends a message at the exact moment their session expires? The message is not sent, the draft is kept, and the user is asked to sign in.
- What happens when a password is exactly 12 characters or exactly 128 characters? Both are accepted. A password of 129 characters or more is refused with a message stating the maximum length.
- What happens when a reset link is opened by someone other than the account owner? The link is still honored if it is valid, since the link itself is the proof of access. This is accepted because reset links are sent only to the registered address.

## Requirements *(mandatory)*

### Functional Requirements

**Sign-up and sign-in**

- **FR-001**: Visitors MUST be able to create an account with an email address and a password, without an invitation or an allow-list.
- **FR-002**: Email addresses MUST be checked for a well-formed address before an account is created. Two addresses that differ only in letter case MUST be treated as the same address.
- **FR-003**: Passwords MUST be at least 12 characters and at most 128 characters long. No character-composition rules (such as required symbols or digits) apply.
- **FR-004**: Passwords that appear on a published common-passwords list MUST be refused at sign-up and at password reset.
- **FR-005**: Passwords MUST never be stored, displayed, logged, or returned in readable form.
- **FR-006**: Sign-up for an email address that is already registered MUST be refused with a neutral message that does not confirm the address is registered and directs the user to sign in or reset their password.
- **FR-007**: A registered user MUST be able to sign in with their email address and password and land in the chat view.
- **FR-008**: A failed sign-in MUST show one generic message, "Email or password is incorrect", that is identical for an unregistered email address and a wrong password.
- **FR-009**: Sign-up, sign-in, and sign-out MUST all work in the existing chat application without a full page reload.

**Sessions**

- **FR-010**: A signed-in user MUST stay signed in across page reloads until the session expires or the user signs out.
- **FR-011**: A session MUST end after 8 hours without activity, or 7 days after sign-in, whichever comes first.
- **FR-012**: A signed-in user MUST be able to sign out. Signing out MUST end the session on the service, not only in the browser, and MUST replace the chat view with the sign-in screen.
- **FR-013**: Session credentials MUST NOT be readable by page scripts, and MUST NOT appear in responses other than the response that issues the session.
- **FR-014**: Requests that change data (sending a message, clearing a conversation, signing out, resetting a password) MUST be protected against forged requests from other websites.
- **FR-015**: When a session expires during a chat, the unsent text MUST be kept, the user MUST be asked to sign in again, and the user MUST be able to send the kept text after signing in.

**Access control and conversation ownership**

- **FR-016**: Sending a message and clearing a conversation MUST be refused for any request that is not from a signed-in user, and the sign-in screen MUST be shown.
- **FR-017**: Each conversation MUST belong to the user who first uses its identifier. Any other user's request for that identifier MUST be refused as "not found", so the response does not reveal whether the identifier exists.
- **FR-018**: Conversation memory MUST be kept separately for each user and conversation identifier, so that the histories of two users can never mix, even if the same identifier is used by both at different times.
- **FR-019**: The "New chat" action MUST clear only the signed-in user's conversation.
- **FR-020**: User accounts MUST survive a service restart. Conversations, chat memory, and sessions are not required to survive a restart.

**Rate limits and brute-force protection**

- **FR-021**: Each signed-in user MUST be limited to 20 messages per minute. Users who exceed the limit MUST see how long to wait before retrying.
- **FR-022**: Sign-in and sign-up requests MUST be rate limited per client address. Sign-in attempts MUST also be limited per email address.
- **FR-023**: After five failed sign-in attempts for one email address within 15 minutes, further attempts for that address MUST be delayed, with the delay increasing after each further failure, up to 15 minutes. The delay MUST NOT block a correct sign-in from another client indefinitely.
- **FR-024**: Logs MUST NOT contain passwords, session credentials, or reset tokens. Email addresses MAY be logged only where needed to record a security event.

**Password reset (MVP, lowest priority)**

- **FR-025**: A visitor MUST be able to request a password reset link by email. The confirmation shown MUST be the same whether or not the address is registered.
- **FR-026**: A reset link MUST be usable once and MUST expire 60 minutes after it is issued.
- **FR-027**: A successful password reset MUST end all other active sessions for that account.

**Reliability, accessibility, and other behavior**

- **FR-028**: Sign-in and sign-up MUST keep working when the AI service is unavailable.
- **FR-029**: Authentication forms MUST be fully usable with a keyboard, have labeled fields, announce errors to screen readers, and support password managers.
- **FR-030**: Validation errors MUST appear next to the field they refer to. Rate-limit responses MUST show a retry time.
- **FR-031**: The product MUST NOT offer anonymous chat. A visitor who is not signed in sees only the sign-in and sign-up screens.

### Key Entities

- **User Account**: A person who can use the chat. Has a unique email address (case-insensitive), a protected password credential, and a creation date. Accounts have no roles, teams, or permission levels; all users have the same access.
- **Session**: A period during which a user is signed in on a given browser. Belongs to one user account, ends on sign-out, after inactivity, or at its absolute limit, and ends for every other session of the same user after a password reset.
- **Conversation**: A chat thread identified by a conversation identifier. Owned by exactly one user account. Its memory is held in the service and is lost on restart.
- **Password Reset Request**: A single-use, time-limited reset link issued to a registered email address. Becomes unusable once used or after 60 minutes.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of attempts to send a message or clear a conversation without being signed in are refused, verified by automated tests.
- **SC-002**: In tests with two accounts using the same conversation identifier, no account can read, continue, or clear the other account's conversation. Zero such cases occur.
- **SC-003**: A new visitor can create an account, send a message, reload the page and remain signed in, and sign out, in one session, in under 3 minutes and with no errors.
- **SC-004**: After a correct sign-in, the chat view is shown to the user within 1 second under normal conditions, not counting the deliberate delay that protects passwords.
- **SC-005**: With sign-in enabled, the first part of a chat reply still appears within 2 seconds under normal conditions, the same target as `001-prd.md`.
- **SC-006**: Automated scans find zero passwords, session credentials, or reset tokens in logs, in responses returned to users (other than the response that issues the session), or in publicly downloadable application code.
- **SC-007**: With the AI service unavailable, 100% of sign-up, sign-in, and sign-out attempts in tests still complete as expected.
- **SC-008**: Under a test of 100 wrong-password attempts against one account within 5 minutes, no more than 5 attempts are processed without delay, and the delay increases with each further attempt.
- **SC-009**: Two users on the same client address can each send 20 messages within one minute without either being refused. The 21st message from one user is refused with a retry time, and the other user is not affected.
- **SC-010**: Users can complete sign-in, sign-out, and sign-up using only a keyboard and a screen reader, verified by an accessibility review of the authentication screens.

## Assumptions

The following defaults were chosen where the PRD left a question open. Each one can be changed during `/speckit-clarify`.

- **Open registration**: Anyone can sign up. No allow-list of emails or domains. (Confirms the PRD's "A visitor can create an account" requirement.)
- **Email verification**: Not required in the MVP. Bot sign-ups are limited by the per-client-address sign-up rate limit (FR-022). Verification gating is deferred.
- **Anonymous trial**: Not in the MVP. Sign-in is required before any chat (FR-031).
- **Duplicate email on sign-up**: Without email verification, a sign-up for a registered address is refused with a neutral message (FR-006). This means someone can tell whether an address is registered by trying to sign up with it. Full concealment would require verification. This trade-off is accepted for the MVP and should be confirmed.
- **Session lifetime**: 8 hours idle and 7 days absolute (FR-011). These are the PRD's suggested defaults.
- **Brute-force thresholds**: Five failed sign-ins per email address within 15 minutes, then increasing delay up to 15 minutes (FR-023). Chosen to avoid a permanent lockout that an attacker could use to block a real user.
- **Password reset in MVP**: Included because the PRD lists it under MVP scope, but it has the lowest priority (User Story 6). Delivering it requires an email delivery service, which is not yet chosen. If that service is not ready, User Story 6 can move to a later phase without affecting Stories 1 to 5.
- **Reset link lifetime**: 60 minutes, single use (FR-026).
- **Persistent accounts**: User accounts must survive restarts (FR-020). Chat memory and sessions do not. Accounts therefore need durable storage, which `001-prd.md` did not require. This is a decision to confirm before implementation, as the PRD notes under Risks.
- **Conversation ownership on restart**: Ownership records are lost on restart along with chat memory. This is accepted under `001-prd.md` and is confirmed as not requiring change.
- **Email case**: Email addresses are compared without regard to letter case (FR-002).
- **Single sign-in method**: Each user has one account and one password. No other sign-in methods are supported.
- **Dependencies**: The chat behavior and its quality targets come from `001-tech-support-chat`. Password reset depends on an email delivery service, which is an external dependency.

### Out of Scope

- Social login (Google, GitHub), single sign-on, and enterprise identity providers.
- Multi-factor authentication and passkeys.
- Email verification gating.
- Account deletion and profile editing screens.
- Roles, permissions, teams, and organization accounts.
- Linking a chat user to a Caramel AI platform account, API keys, or billing data.
- Live account or billing lookups.
- Persistent chat history across sessions or restarts.
- An administrative screen for managing users.
