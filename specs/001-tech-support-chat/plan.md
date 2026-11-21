# Implementation Plan: Caramel AI Tech Support Chat Assistant

**Branch**: `001-tech-support-chat` | **Date**: 2026-11-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-tech-support-chat/spec.md`

## Summary

Build a streamed, multi-turn tech-support chat for Caramel AI. A React + TypeScript single-page
app posts each message to a Spring Boot backend. The backend runs it through a Spring AI
`ChatClient` (fixed system prompt, in-memory windowed chat memory keyed by `conversationId`) against
`gemma-4-26b-a4b-it` on Google AI Studio, and streams tokens back as Server-Sent Events. Failures
are mapped to a small set of friendly error codes, delivered as a typed SSE `error` event (or an
HTTP error before the stream starts), and the UI offers retry. The API key stays in the backend.

Technical approach, with the reasoning in [research.md](research.md):

- Spring AI **Google GenAI starter** is the primary integration path. The AI Studio
  OpenAI-compatible endpoint is the documented fallback if Gemma is rejected (gate G-1 below).
- **Spring MVC** returning `Flux<ServerSentEvent<String>>`. This is the stack whose Boot 4.1.1
  artifacts are already in the local Maven cache, and Spring AI 2.0.1 is built against Boot 4.1.1.
- Provider timeouts are set on a custom `com.google.genai.Client` bean, because the starter exposes
  no timeout properties. A reactive deadline enforces the 60-second reply limit.
- `.env` is loaded with `spring.config.import`, with no extra library.
- Rate limiting is a small in-memory per-client filter, with no new dependency.

## Technical Context

**Language/Version**: Java 21 (installed: OpenJDK 21.0.12); TypeScript 5.x on Node 22 (installed: v22.22.2)

**Primary Dependencies**:
- Backend: Spring Boot 4.1.1, Spring AI 2.0.1 (`spring-ai-starter-model-google-genai`), `spring-boot-starter-webmvc`, `spring-boot-starter-validation`.
- Frontend: React 19, Vite, Vitest, React Testing Library.

**Storage**: None. Conversation memory is process-local (`MessageWindowChatMemory` over `InMemoryChatMemoryRepository`), per Constitution V.

**Testing**:
- Backend: JUnit 5, Spring Boot Test, MockMvc, Reactor `StepVerifier` (`reactor-test` is not in the local cache and needs a network download on first build).
- Frontend: Vitest + React Testing Library.
- Live-model checks are opt-in and gated on `GOOGLE_API_KEY`.

**Target Platform**: Linux server (JVM) plus a static browser bundle.

**Project Type**: Web application (backend + frontend).

**Performance Goals**: First token visible within about 2 seconds for at least 95% of messages (SC-001). Every submitted message ends within 60 seconds (SC-008).

**Constraints**:
- API key backend-only and never logged (FR-018, FR-019).
- Explicit provider timeout.
- 4,000-character message cap.
- 20 messages/minute/client.
- No persistence.

**Scale/Scope**: MVP. Single instance, in-process state, low concurrency. A horizontal scale-out would need a shared memory repository and a shared rate limiter; this is out of scope.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle / rule | Pre-research | Post-design | How the plan satisfies it |
|---|------------------|--------------|-------------|---------------------------|
| I | Scope Discipline | PASS | PASS | No auth, billing lookups, handoff, RAG, tools, or persistence. Two small additions are justified below. |
| II | Secrets in backend | PASS | PASS | Key read from `GOOGLE_API_KEY` only in the backend `Client` bean. `.env` git-ignored, `.env.example` committed. No actuator and no HTTP-level debug logging. A test asserts the key is absent from every response. |
| III | Grounded persona | PASS | PASS | Persona and guardrails live in `prompts/support-system.st`. UI cannot alter them. Eval prompt set covers off-topic, pricing, account, and role-override cases. |
| IV | Resilient streamed calls | PASS | PASS | SSE streaming. Explicit provider timeout (custom `Client` bean), spring-ai retry capped, 60 s reply deadline, error-to-code mapping, UI retry. |
| V | Layered, config-driven | PASS | PASS | `web` (controller, filter), `chat` (service), `config` (Spring AI beans). Model name from properties. Memory is Spring AI `ChatMemory`, in process. Contract matches PRD section 8. |
| Tech | Constraints | PASS | PASS | Java / Spring Boot / Spring AI, React + TS, `gemma-4-26b-a4b-it`. |
| Tech | Integration path, `.env` loading, key name MUST be recorded in `prd.md` before the model is wired | **OPEN** | **OPEN** | Decided in [research.md](research.md) (R-1, R-2, R-3). Task 1 of `/speckit-tasks` MUST update `prd.md` sections 7 and 10 before any model wiring. This plan does not edit `prd.md`. |
| Workflow | Root `.gitignore` before first commit | OPEN | OPEN | Scaffolding task creates `.gitignore` (excludes `.env`, `node_modules/`, `target/`, build output). |
| Workflow | `.mcp.json` has no literal token | OPEN | OPEN | Pre-existing issue, not caused by this feature. Called out in tasks as a pre-commit blocker. |
| Workflow | Persona changes verified with off-topic and pricing prompts | PASS | PASS | Part of the eval set in the quickstart. |
| Workflow | Rate limiting decided and implemented before public deployment | PASS | PASS | FR-016 and R-8. |
| Workflow | `CLAUDE.md` updated with real commands after scaffolding | OPEN | OPEN | Final task. Commands are in [quickstart.md](quickstart.md). |

**Gate result**: no violations. Four items are open work for later phases, not violations.

### Additions beyond the PRD (justified, not violations)

| Addition | Why needed | Simpler alternative rejected because |
|----------|-----------|--------------------------------------|
| Per-client rate-limit filter (FR-016) | Constitution requires abuse protection before public deployment; PRD section 10 left it open. | A reverse proxy limit is outside this repo and cannot be tested here. |
| Idle-conversation expiry (default 2 h) | The in-memory repository has no eviction, so a public endpoint can grow memory without bound. | Doing nothing makes the endpoint trivially exhaustible. Expiry does not persist anything, so it stays consistent with FR-007 and Principle V. |
| Server-side guard against concurrent turns on one `conversationId` (HTTP 409) | Two overlapping turns would interleave in memory. The UI already disables input; this protects the server from other clients. | Trusting the UI alone leaves memory corruptible by any caller. |

## Project Structure

### Documentation (this feature)

```text
specs/001-tech-support-chat/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── chat-api.openapi.yaml
│   └── sse-events.md
├── checklists/
│   └── requirements.md  # From /speckit-specify
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
.env.example                         # GOOGLE_API_KEY=your-key-here  (committed)
.env                                 # developer-provided (git-ignored, never committed)
.gitignore

backend/
├── pom.xml
├── src/main/java/ai/caramel/support/
│   ├── SupportApplication.java
│   ├── config/                      # Spring AI configuration layer
│   │   ├── ChatClientConfig.java    # ChatClient + system prompt + memory advisor
│   │   ├── GenAiClientConfig.java   # com.google.genai.Client with HTTP timeout
│   │   └── SupportProperties.java   # @ConfigurationProperties (limits, timeouts, TTL)
│   ├── chat/                        # Service layer
│   │   ├── ChatService.java         # streaming, memory scoping, rollback, deadline
│   │   ├── ConversationRegistry.java# active-turn guard + idle expiry
│   │   ├── ChatFailure.java         # error code enum + friendly messages
│   │   └── ChatErrorMapper.java     # Throwable -> ChatFailure
│   └── web/                         # Controller layer
│       ├── ChatController.java      # POST /api/chat, DELETE /api/chat/{id}
│       ├── ChatRequest.java
│       ├── ApiError.java
│       ├── RateLimitFilter.java
│       └── GlobalExceptionHandler.java
├── src/main/resources/
│   ├── application.properties
│   └── prompts/support-system.st    # persona + guardrails
└── src/test/
    ├── java/ai/caramel/support/...  # unit, web, and opt-in live tests
    └── resources/eval/              # off-topic / pricing / multi-turn prompt sets

frontend/
├── package.json
├── vite.config.ts                   # dev proxy: /api -> http://localhost:8080
├── src/
│   ├── api/                         # chatClient.ts (fetch stream), sseParser.ts
│   ├── components/                  # ChatWindow, MessageList, MessageBubble, Composer, ErrorBanner
│   ├── hooks/                       # useChat.ts
│   ├── types/                       # chat.ts
│   └── App.tsx
└── tests/                           # Vitest + Testing Library
```

**Structure Decision**: Web application with two top-level projects, `backend/` and `frontend/`,
at the repository root. The `.env` file stays at the project root, as the PRD and constitution
require. The backend packages mirror the constitution's three layers: `web` is the controller
layer, `chat` is the service layer, and `config` is the Spring AI configuration layer.

## Complexity Tracking

No constitution violations. The three additions above are the only complexity beyond the PRD.
