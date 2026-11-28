# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status and commands

The backend (`backend/`, Spring Boot 4.1.1, Spring AI 2.0.1, Spring Security 7) and the frontend (`frontend/`, React 19, TypeScript, and Vite) are implemented, including user authentication. `001-prd.md` and `002-prd-user-auth.md` are the sources of truth for scope. The specs, plans, and tasks are under `specs/001-tech-support-chat/` and `specs/002-user-auth/`.

Setup: copy `.env.example` to `.env` at the repository root and set `GOOGLE_API_KEY`. The backend refuses to start if the key is missing. The auth settings in `.env.example` are optional: leave `AUTH_DB_*` unset to use the development database under `backend/data/`, and keep `SESSION_COOKIE_SECURE=true` except for local HTTP.

Backend, run from `backend/`:

- Build: `mvn -q -B -DskipTests package`
- Run (port 8082): `mvn spring-boot:run`
- All tests: `mvn -q -B test` (the live tests are skipped without a key)
- One test class: `mvn -B test -Dtest=ChatServiceStreamTest`
- Live provider smoke test (needs a key): `mvn -B test -Dtest=ProviderSmokeIT`
- Live guardrail evals (needs a key; replies are written to `backend/target/eval-report/` for review): `mvn -B test -Dtest=GuardrailEvalIT`
- Auth tests only: `mvn -B test -Dtest='*Auth*,SessionCookieTest,SessionExpiryTest,CsrfTest,ConversationOwnershipTest,ChatEndpointsAuthTest,LoginThrottleTest,RateLimitFilterTest,StreamingFlushTest'`
- A `NoClassDefFoundError` or "Unresolved compilation problem" in a test that should pass means stale output from an incremental build: run `mvn clean test`.

Frontend, run from `frontend/`:

- Dev server (port 5173, proxies `/api` to 8082): `npm run dev`
- All tests: `npm test`
- One test file: `npx vitest run tests/api/sseParser.test.ts`
- Type-check and production build: `npm run build`
- Secret scan of the build (run after `npm run build`): `npm run verify:no-secrets`. It also fails if any file under `src/` uses `localStorage` or `sessionStorage`.

There is no linter configured. `npx tsc --noEmit` is the only static check on the frontend.

## What we're building

Caramel AI tech-support chat assistant: a customer-facing chat UI for an LLM inferencing platform, answering questions about API usage, model selection, errors, rate limits, latency, billing, and integration. Full requirements are in `001-prd.md`.

## Planned architecture

Request path:

React + TypeScript SPA → HTTP/SSE → Spring Boot backend (Spring AI) → Google AI Studio → `gemma-4-26b-a4b-it`

- Keep the backend layers separate: controller (HTTP/SSE), service (ChatClient orchestration, system prompt, conversation handling), and Spring AI configuration (model, API key, chat memory).
- Conversation memory is Spring AI chat memory, held in memory and keyed by the authenticated user ID and the `conversationId` the client supplies (`<userId>:<conversationId>`). Each conversation identifier is owned by the user who first uses it; another user's request gets 404. Nothing survives a server restart, and chat history is not persisted across sessions.
- Accounts are the only durable data. They live in a relational table (`schema.sql`): an embedded H2 file in development and PostgreSQL in production, accessed with plain JDBC. Sessions, ownership bindings, memory, and rate-limit state are in process memory.
- Authentication lives in the `auth` package and the `config` security classes. The chat layer receives a plain user ID string and must not import Spring Security (enforced by `ChatLayerHasNoSecurityImportsTest`).
- The system prompt defines the Caramel AI support persona and scope limits. Persona and guardrail changes belong there, not in the UI.

API (draft):

- `POST /api/chat`: body `{ conversationId, message }`, reply streamed over SSE.
- `DELETE /api/chat/{conversationId}`: clears that conversation's memory. This backs the "New chat" action.

## Constraints that shape the code

- Session credentials are an `HttpOnly`, `SameSite=Lax` cookie (`Secure` by default). Requests that change data need the `X-XSRF-TOKEN` header. Neither value is stored in browser storage.
- The Google AI Studio API key lives only in the backend. It is read from `.env` at the project root as `GOOGLE_API_KEY`, and it must never reach the frontend, API responses, or logs.
- `.env` is git-ignored (see `.gitignore`). Commit `.env.example` with placeholder values instead.
- `.env` is loaded with `spring.config.import` (see `backend/src/main/resources/application.properties`). The Spring AI path is the Google GenAI starter, with the OpenAI-compatible endpoint as fallback. Both decisions are recorded in `001-prd.md` sections 7 and 10. Gemma's acceptance through the starter is still unverified until the live smoke test passes.
- The model name (`gemma-4-26b-a4b-it`) is set in application properties, not hardcoded.
- Set timeouts on provider calls. Map provider failures (unavailable, timeout, rate limit) to friendly messages that the UI can show with a retry option.
- Latency target: first token visible within about 2 seconds under normal conditions.

## Scope

- In scope (MVP): streamed multi-turn chat, persona and guardrails, new chat, error states, and user auth (email and password sign-up, sign-in, sign-out, session persistence, per-user conversation ownership and rate limits). Auth details are in `002-prd-user-auth.md`.
- Out of scope for now: social login, SSO, MFA, roles or teams, admin UI, live account or billing lookups, human handoff or ticketing, RAG over docs, persistent chat history. Don't build these unless asked.
- The assistant must not invent account-specific data, pricing, or policies.

## Config notes

- `.mcp.json` reads the GitHub bearer token from `${GITHUB_TOKEN}`. Keep the token in the environment, and don't copy it into any other file.
