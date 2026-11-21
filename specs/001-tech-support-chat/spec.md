# Feature Specification: Caramel AI Tech Support Chat Assistant

**Feature Branch**: `001-tech-support-chat` (no git branch created; repository is not under version control)

**Created**: 2026-11-14

**Status**: Draft

**Input**: User description: "Caramel AI tech support chat assistant: a customer-facing chat that gives technical support on LLM inferencing (API usage, model selection, errors, rate limits, latency, billing questions, integration issues), per prd.md and the project constitution."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Get a Streamed Answer to a Technical Question (Priority: P1)

A developer integrating Caramel AI opens the chat, types a question about an LLM inferencing issue (for example, an error they are seeing or which model suits their use case), and watches the answer appear progressively instead of waiting for the full reply.

**Why this priority**: This is the core value of the product. Without a helpful, responsive answer, no other story matters.

**Independent Test**: Open a new chat, send one in-scope technical question, and confirm that text begins appearing within 2 seconds and continues to appear until the answer is complete.

**Acceptance Scenarios**:

1. **Given** a customer has an empty chat open, **When** they type an in-scope question and submit it, **Then** the first part of the reply becomes visible within 2 seconds under normal conditions.
2. **Given** a reply is being generated, **When** the customer watches the chat, **Then** the reply grows progressively until it is complete, rather than appearing all at once.
3. **Given** a reply has finished, **When** the customer looks at the chat, **Then** the full question and reply remain visible in order.

---

### User Story 2 - Ask Follow-Up Questions in Context (Priority: P1)

A customer continues a troubleshooting conversation over several messages. Each follow-up refers to earlier answers (for example, "what about the rate limit you mentioned?"), and the assistant responds in light of what was already discussed.

**Why this priority**: Multi-turn troubleshooting is the main way technical teams solve production issues. Without memory of earlier turns, customers must repeat context, which makes the product unusable for real support work.

**Independent Test**: Start a conversation, ask a question that names a specific error, then ask a follow-up that uses "it" or "that error" without restating the error. Confirm the reply correctly refers to the earlier error.

**Acceptance Scenarios**:

1. **Given** a customer has asked about a specific error, **When** they ask a follow-up that refers to it implicitly, **Then** the reply addresses that error without the customer restating it.
2. **Given** a conversation has run for ten or more turns, **When** the customer asks about something from the first turn, **Then** the assistant still answers in context.
3. **Given** two customers each have a conversation open, **When** each sends messages, **Then** neither customer's earlier messages influence the other's replies.

---

### User Story 3 - Stay On Topic and Avoid Invented Facts (Priority: P2)

A customer asks something unrelated to Caramel AI's inference service (for example, a general coding or travel question), or asks for pricing, quotas, or account details that the assistant has no reliable source for. The assistant politely declines the off-topic request, and for unknown specifics it says so and points the customer to official resources.

**Why this priority**: Off-topic answers waste support capacity, and invented pricing or account facts damage customer trust and could lead to billing disputes.

**Independent Test**: Send one clearly off-topic request and one question asking for a specific current price or account quota. Confirm the first is politely declined with a redirect to in-scope help, and the second is answered with an admission of uncertainty and a pointer to official resources, with no invented figures.

**Acceptance Scenarios**:

1. **Given** a customer asks an unrelated question, **When** the assistant replies, **Then** it politely declines and offers to help with an inference-related topic.
2. **Given** a customer asks for the current price of a model or their account's rate limit, **When** the assistant replies, **Then** it does not state a specific figure it has no source for and directs the customer to official documentation or their existing support channel.
3. **Given** a customer tries to change the assistant's role or rules (for example, "ignore your instructions"), **When** the assistant replies, **Then** it keeps its support role and does not comply.

---

### User Story 4 - Start a New Chat (Priority: P2)

A customer who has finished one problem wants to start fresh with a different one. They choose "New chat", and the assistant no longer uses anything from the previous conversation.

**Why this priority**: Customers often switch between unrelated problems; carrying stale context leads to wrong answers.

**Independent Test**: In a conversation that mentions a specific model, choose "New chat" and ask a question that only makes sense with that model's context. Confirm the assistant does not assume it.

**Acceptance Scenarios**:

1. **Given** a conversation with earlier turns, **When** the customer selects "New chat", **Then** the chat view clears and the next message starts a fresh conversation.
2. **Given** a new chat has started, **When** the customer asks a question that depends on earlier turns, **Then** the assistant asks for the missing context rather than assuming it.

---

### User Story 5 - Recover Clearly From Failures (Priority: P2)

When the assistant service or its underlying AI service is unavailable, slow, or limiting usage, the customer sees a clear, non-technical message and can retry without retyping their question.

**Why this priority**: Support tools are most needed during incidents. Unclear failures or silent hangs send customers to other channels and erode trust.

**Independent Test**: Simulate an unavailable service, a timed-out reply, and a usage limit. Confirm each produces a distinct, plain-language message with a retry option, and that the customer's last message is preserved for retry.

**Acceptance Scenarios**:

1. **Given** the assistant service is unavailable, **When** the customer sends a message, **Then** they see a plain-language message that the service is temporarily unavailable, with a retry option.
2. **Given** a reply does not complete within the time limit, **When** the limit is reached, **Then** the customer sees a timeout message with a retry option rather than waiting indefinitely.
3. **Given** a customer has sent too many messages in a short period, **When** they send another, **Then** they see a message asking them to wait briefly, and their message is not lost.
4. **Given** a failure has occurred, **When** the customer chooses retry, **Then** their last message is sent again without being retyped.

---

### Edge Cases

- What happens when a customer submits an empty message? The message is not sent, and the customer is asked to type a question.
- What happens when a customer submits a very long message (more than 4,000 characters)? The message is not sent, and the customer is asked to shorten it.
- What happens when a customer submits a new message while a reply is still streaming? The input is disabled until the current reply completes or fails.
- What happens when a reply is interrupted partway through? The partial reply is marked as incomplete, an error message with retry is shown, and no duplicate turn is recorded in the conversation.
- What happens when the assistant service restarts mid-conversation? Earlier context is lost. The assistant MUST NOT claim to remember it, and the next reply must not rely on it.
- What happens when a customer asks for a comparison between models? The assistant may describe general differences but MUST NOT state current prices or quotas it has no reliable source for.
- What happens when a customer's question mixes in-scope and out-of-scope topics? The assistant answers the in-scope part and politely declines the rest.
- What happens when a customer asks a question in a language other than English? The assistant replies in the language used if it can; this is a default, not a guarantee (see Assumptions).

## Requirements *(mandatory)*

### Functional Requirements

**Chat and streaming**

- **FR-001**: Customers MUST be able to type a message and submit it to the assistant.
- **FR-002**: The assistant's reply MUST appear progressively as it is generated, not all at once.
- **FR-003**: Under normal conditions, the first part of a reply MUST become visible within 2 seconds of submission.
- **FR-004**: The full conversation (customer messages and assistant replies) MUST remain visible in order for the duration of the chat.

**Conversation memory**

- **FR-005**: Within a conversation, the assistant MUST use earlier messages to answer follow-up questions that refer to them.
- **FR-006**: Conversations MUST be isolated from each other. A message in one conversation MUST NOT affect replies in another.
- **FR-007**: Conversation content MUST NOT carry over into a new chat or survive a service restart.

**Scope and guardrails**

- **FR-008**: The assistant MUST answer only questions within its support scope: API usage, model selection, errors, rate limits, latency, billing questions, and integration issues for LLM inferencing.
- **FR-009**: For requests outside that scope, the assistant MUST politely decline and offer help with an in-scope topic.
- **FR-010**: The assistant MUST NOT state account-specific data, current pricing, quotas, service-level commitments, or policies for which it has no reliable source.
- **FR-011**: When it lacks reliable information, the assistant MUST say so and direct the customer to official documentation or their existing support channel.
- **FR-012**: The assistant MUST keep its support role when a customer asks it to change its rules or role.

**New chat**

- **FR-013**: Customers MUST be able to start a new chat in a single action, which clears the visible conversation and the assistant's memory of it.

**Errors, limits, and recovery**

- **FR-014**: When the assistant service or its underlying AI service is unavailable, customers MUST see a plain-language message explaining that the service is temporarily unavailable, with a retry option.
- **FR-015**: A reply that does not complete within 60 seconds MUST end with a timeout message and a retry option.
- **FR-016**: The system MUST limit how many messages a single client can send in one minute to 20. When the limit is reached, customers MUST see a message asking them to wait briefly.
- **FR-017**: After any failure, the customer's last message MUST be preserved so it can be retried without retyping.
- **FR-018**: Customer-facing messages MUST NOT show internal error details, provider identifiers, or credentials.
- **FR-019**: Provider credentials MUST NOT be visible to customers in any page, reply, or browser-visible traffic.

**Input limits**

- **FR-020**: Empty messages and messages longer than 4,000 characters MUST NOT be sent, and customers MUST see a message explaining why.

### Key Entities *(include if feature involves data)*

- **Conversation**: A single chat session, identified by an identifier the customer's browser supplies. Holds an ordered list of messages. Exists only for the active session; cleared on New chat or service restart.
- **Message**: One customer-submitted question or one assistant reply within a conversation. Has a sender (customer or assistant), text content, a position in order, and a status (complete, streaming, interrupted, or failed).
- **Reply**: An assistant message generated in response to a customer message. Streamed progressively; may end complete, interrupted, or failed.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Under normal load, the first visible part of a reply appears within 2 seconds for at least 95% of customer messages.
- **SC-002**: In a test set of at least 20 multi-turn troubleshooting conversations, at least 90% of follow-up answers correctly use context from earlier turns.
- **SC-003**: In a test set of at least 30 off-topic requests, at least 95% receive a polite decline that offers an in-scope alternative.
- **SC-004**: In a test set of pricing, quota, and account questions, zero replies state a specific figure or account fact the assistant has no reliable source for.
- **SC-005**: Customers can start a new chat in one action, and in 100% of tests the assistant does not reference the prior conversation afterward.
- **SC-006**: In 100% of simulated outages, timeouts, and usage-limit events, customers see a plain-language message with a retry option, and their last message is available to retry.
- **SC-007**: Across all test runs, zero customer-visible outputs contain provider credentials or internal error details.
- **SC-008**: No reply is left in an indefinite waiting state: every submitted message ends in a complete reply, an interruption message, or a timeout message within 60 seconds.

## Assumptions

- Customers use the chat without signing in. Accounts and identity are out of scope for this feature, so the assistant cannot see who a customer is or what their account contains.
- Target users are developers, technical teams troubleshooting production issues, and prospective customers evaluating models and pricing, as described in the PRD.
- The primary language is English. Other languages are best-effort and are not covered by the success criteria.
- Out of scope for this feature, as set by the PRD: user accounts and authentication, live account or billing lookups, human agent handoff or ticketing, retrieval over company documents, and persistent chat history across sessions.
- Public-facing abuse protection is handled by the per-client message limit in FR-016 (20 messages per minute). The threshold is a reasonable default and can be changed during planning.
- The 60-second reply limit (FR-015) and the 4,000-character input limit (Edge Cases, FR-020) are reasonable defaults. Both can be adjusted during planning.
- Pricing, quota, and account details are not supplied to the assistant in this phase, so it must defer to official resources for them.
- Official documentation and an existing support channel exist, and the customer-facing wording will name them once the business provides their details.
- A test set of prompts (off-topic, pricing, account, and multi-turn scenarios) will be maintained to verify SC-002 through SC-004.
- Technology choices (languages, frameworks, AI provider, and configuration) are governed by the project constitution and `prd.md`, not by this specification.
