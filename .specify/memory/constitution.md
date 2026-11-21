<!--
SYNC IMPACT REPORT (temporary scratch for human review; remove before committing)

Version change: unversioned template → 1.0.0
Bump rationale: initial ratification. No prior ratified constitution exists; the file
contained only the unfilled template.

Principle slots filled:
  - [PRINCIPLE_1_NAME] → I. Scope Discipline
  - [PRINCIPLE_2_NAME] → II. Secrets Stay in the Backend
  - [PRINCIPLE_3_NAME] → III. Grounded Support Persona
  - [PRINCIPLE_4_NAME] → IV. Resilient, Streamed Provider Calls
  - [PRINCIPLE_5_NAME] → V. Layered, Configuration-Driven Design

Section slots filled:
  - [SECTION_2_NAME] → Technology and Configuration Constraints
  - [SECTION_3_NAME] → Development Workflow and Quality Gates

Modified principles: none (initial fill)
Added sections: Core Principles, Technology and Configuration Constraints,
  Development Workflow and Quality Gates, Governance
Removed sections: none

Deferred items (open decisions from prd.md, intentionally not resolved here; not placeholders):
  - Spring AI integration path for Gemma on AI Studio (Google GenAI starter vs.
    OpenAI-compatible endpoint). prd.md Section 10. Must be decided before wiring the model.
  - `.env` loading mechanism (Spring config import, dotenv library, or shell/container env).
    prd.md Section 7. Must be decided before wiring the model.
  - `GOOGLE_API_KEY` variable name to confirm against the chosen starter. prd.md Section 7.
  - Rate limiting and abuse protection for the public chat endpoint. prd.md Section 10.
    Must be decided before public deployment.
  - Knowledge base / RAG in a later phase. prd.md Section 10. Out of scope for MVP.

Ratification date assumption: 2026-11-14, the date of first adoption. Confirm if an earlier
adoption date applies.
-->

# Caramel AI Tech Support Chat Assistant Constitution

## Core Principles

### I. Scope Discipline

The MVP scope is defined by `prd.md` Section 4. The MVP MUST include: streamed multi-turn chat,
persona and guardrails, a new-chat reset, and error states with retry.

The following are out of scope and MUST NOT be built unless the user explicitly requests them in
the current task: user authentication or accounts, live account or billing lookups, human agent
handoff or ticketing, RAG over company documents, and persistent chat history across sessions.

**Rationale**: Fixed scope keeps the MVP shippable and prevents speculative infrastructure that
the PRD has deliberately deferred.

### II. Secrets Stay in the Backend

The Google AI Studio API key MUST be read from the `GOOGLE_API_KEY` variable. The backend loads it
from a `.env` file at the project root at startup, or from the shell or container environment.

- The API key MUST NOT appear in frontend code, API responses, logs, error messages, or
  browser-visible network traffic.
- `.env` MUST be git-ignored and MUST NOT be committed.
- `.env.example` with the placeholder `GOOGLE_API_KEY=your-key-here` MUST be committed instead.
- Source code MUST NOT hardcode any secret.

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

**Rationale**: Streaming is the main mechanism for perceived latency. Explicit timeouts prevent
hung sessions and unbounded waits.

### V. Layered, Configuration-Driven Design

- The backend MUST keep three layers separate:
  - Controller: HTTP and SSE endpoints.
  - Service: ChatClient orchestration, system prompt, and conversation handling.
  - Spring AI configuration: model, API key binding, and chat memory.
- Controllers MUST NOT call the model directly.
- The model name `gemma-4-26b-a4b-it` MUST be set in application properties and MUST NOT be
  hardcoded.
- Conversation memory MUST use Spring AI chat memory, held in process memory and keyed by the
  client-supplied `conversationId`. Conversation memory MUST NOT be written to disk or a database
  in the MVP.
- The API contract MUST match `prd.md` Section 8: `POST /api/chat` with body
  `{ conversationId, message }` returns an SSE stream, and `DELETE /api/chat/{conversationId}`
  clears that conversation's memory. Any change to the contract MUST update the PRD first.

**Rationale**: Separating layers keeps the persona and the model choice independently changeable.
Externalized configuration lets the model be swapped without code changes.

## Technology and Configuration Constraints

- Backend: Java with Spring Boot and Spring AI.
- Frontend: React with TypeScript, as a single-page application.
- Model: `gemma-4-26b-a4b-it`, served by Google AI Studio. Using any other model requires amending
  this constitution.
- The Spring AI integration path (Google GenAI starter or OpenAI-compatible endpoint), the
  `.env` loading mechanism, and the exact API key variable name MUST be decided and recorded in
  `prd.md` before the model is wired into the application.
- Provider connect and response timeouts MUST be set in application properties.
- Conversation state is process-local. A server restart clears all conversations. This is an
  accepted MVP limitation, not a defect.

## Development Workflow and Quality Gates

- A root `.gitignore` MUST exist before the first commit, and it MUST exclude `.env`.
- `.mcp.json` MUST NOT contain literal credentials. It MUST reference an environment variable
  instead, and that token MUST NOT be copied into any other file.
- Any change to the system prompt, persona, or guardrails MUST be verified against Principle III
  with at least two prompts: one off-topic request and one question about pricing or account
  data that the assistant has no source for.
- Pull requests MUST state how the change was verified.
- Before any public deployment, rate limiting and abuse protection for the chat endpoint MUST be
  decided and implemented.
- Once the Spring Boot and React projects are scaffolded, `CLAUDE.md` MUST be updated with the real
  build, lint, and test commands, including how to run a single test. Until then, no build or test
  commands may be assumed.

## Governance

This constitution supersedes other practices in this repository where they conflict.
`prd.md` is the source of truth for scope and design. `CLAUDE.md` provides runtime guidance for
agents and MUST NOT contradict this constitution.

**Amendment procedure**: An amendment is a change to this file. It MUST include an updated Sync
Impact Report, a stated rationale, and the version bump type. It requires maintainer approval
before merge. Any PRD change that alters a principle MUST be made in the same change.

**Versioning policy** (semantic versioning):
- MAJOR: removing or redefining a principle or governance rule in a backward-incompatible way.
- MINOR: adding a principle or section, or materially expanding guidance.
- PATCH: clarifications, wording, typo fixes, and non-semantic refinements.

**Compliance review**: Every pull request and code review MUST verify compliance with Principles
I through V and with the Technology and Quality Gates sections. Each violation MUST be fixed, or
waived in the pull request with a recorded reason. Any complexity beyond the PRD MUST be justified
in writing.

**Version**: 1.0.0 | **Ratified**: 2026-11-14 | **Last Amended**: 2026-11-14
