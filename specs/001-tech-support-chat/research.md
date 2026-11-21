# Research: Caramel AI Tech Support Chat Assistant

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-11-14

Each entry gives the decision, the rationale, the alternatives, and an evidence label:

- **Verified**: checked against artifacts in the local Maven cache or the installed toolchain.
- **Unverified**: depends on a live call or documentation not available offline. Each has a gate.

## R-1. Spring AI integration path for Gemma on AI Studio

- **Decision**: Use `spring-ai-starter-model-google-genai` (Spring AI 2.0.1) with the API key set, which selects the Gemini Developer API (AI Studio) mode.
- **Rationale**: It is the native path, exposes the right options (`temperature`, `max-output-tokens`, safety settings), and lets us hand it a custom `Client` for timeouts (R-4). Streaming and `ChatClient` integration are first-class.
- **Alternatives**:
  - OpenAI starter against `https://generativelanguage.googleapis.com/v1beta/openai/` with a custom `base-url`. Kept as the **fallback**. It changes only configuration and the starter dependency, because `ChatClient` code is provider-neutral.
- **Evidence**: Starter, autoconfiguration, and SDK (`google-genai` 1.65.0) exist at 2.0.1 locally (**Verified**). Whether `gemma-4-26b-a4b-it` is accepted through this starter is **Unverified**; there is no API key in this environment.
- **Gate G-1**: The first implementation task is a one-call smoke test (quickstart step 3). If Gemma rejects the call, switch to the fallback and record that in `prd.md`.

## R-2. Loading `.env`

- **Decision**: In `application.properties`:
  `spring.config.import=optional:file:./.env[.properties],optional:file:../.env[.properties]`.
  The first path covers running from the project root, the second covers running from `backend/`. A real environment variable named `GOOGLE_API_KEY` takes precedence over both, which covers shell and container use.
- **Rationale**: Built into Spring Boot, with no dependency and no code. The `.env` file uses plain `KEY=value` lines, which is also the format of `.env.example`.
- **Constraints to document**: No `export` prefix, and no quotes around values (the `.properties` format would keep them literally). The `optional:` prefix means a missing file is not an error, so a missing key must fail loudly at startup instead (R-6).
- **Alternatives**: A dotenv library (extra dependency, global side effects); relying on shell export only (poor developer experience).
- **Evidence**: **Unverified** here, because it needs a running app. Verified by quickstart step 2.

## R-3. API key variable name

- **Decision**: `GOOGLE_API_KEY`, bound with `spring.ai.google.genai.api-key=${GOOGLE_API_KEY}`.
- **Evidence**: The property `spring.ai.google.genai.api-key` exists in the 2.0.1 configuration metadata (**Verified**).

## R-4. Provider timeouts

- **Decision**: Define our own `com.google.genai.Client` bean built with `Client.builder().apiKey(...).httpOptions(HttpOptions.builder().timeout(<ms>).build()).build()`. Spring AI's `Client` bean is `@ConditionalOnMissingBean`, so ours replaces it. Property: `support.provider.timeout` (default 55 s), converted to milliseconds.
- **Rationale**: The starter has no timeout properties (checked the full 2.0.1 metadata). `HttpOptions.timeout()` exists on the SDK.
- **Unit of the SDK timeout**: assumed milliseconds. **Unverified**; confirm in the SDK documentation or with a test that points at a deliberately slow stub.
- **Alternatives**: Relying only on a reactive `.timeout()`. That cancels the subscription but may leave the underlying HTTP call running, so both layers are used (see R-5).
- **Evidence**: Bean condition and `HttpOptions.timeout()` **Verified** by bytecode inspection.

## R-5. Reply deadline and first-token behaviour

- **Decision**: In `ChatService`, wrap the stream so that (a) a first-token timeout of 30 s fires if nothing arrives, and (b) an overall 60 s deadline ends the stream with a `TIMEOUT` failure. Both map to the same user message.
- **Rationale**: FR-015 and SC-008 require a bounded wait. Reactor's `timeout(Duration)` is per item, so the overall deadline is enforced by racing the stream against a delayed error signal.
- **Spring MVC async timeout**: set `spring.mvc.async.request-timeout=75s`. Without this the servlet container's default async timeout (commonly 30 s) would cut long replies before our deadline.
- **Evidence**: Standard Reactor/MVC behaviour; the 30 s MVC default is **Unverified** for Boot 4.1.1, but setting it explicitly makes the question moot.

## R-6. Startup failure when the key is missing

- **Decision**: Validate that `spring.ai.google.genai.api-key` is non-blank at startup (a `@ConfigurationProperties` check or an `ApplicationRunner`). Fail with a message naming the variable, never printing its value.
- **Rationale**: Because `.env` is `optional:`, a developer who forgot it would otherwise get a confusing provider error on the first chat.

## R-7. Spring AI retry defaults

- **Decision**: Set `spring.ai.retry.max-attempts=2` and `spring.ai.retry.backoff.initial-interval=500ms`, keep `on-client-errors=false`.
- **Rationale**: The 2.0.1 defaults are 10 attempts with a 2 s initial interval and a multiplier of 5, which would blow through the 2 s first-token and 60 s reply limits on a failing provider.
- **Evidence**: Defaults **Verified** from `spring-ai-autoconfigure-retry` metadata. In `GoogleGenAiChatModel` the retry template is used at one call site, which I did not trace to the streaming path; the cap applies either way. **Unverified** whether streaming retries at all.

## R-8. Rate limiting (answers PRD open question 3)

- **Decision**: A servlet `OncePerRequestFilter` on `POST /api/chat` only, limiting each client to 20 requests per rolling minute (property `support.rate-limit.per-minute`). Client key is the remote address. `X-Forwarded-For` is honoured only when `support.rate-limit.trust-forwarded-for=true`. Over the limit it returns HTTP 429 with `Retry-After` and the `RATE_LIMITED` code. Idle client entries are purged on a schedule.
- **Rationale**: No dependency and fully testable. `DELETE` is not limited because it is cheap and a user must always be able to reset.
- **Alternatives**: Bucket4j (a good library, but a dependency for one small rule); a gateway (outside this repo).
- **Limit**: Per-instance and in-memory. A client behind a shared NAT shares a bucket. Both are acceptable for the MVP and are listed in the quickstart's known limits.

## R-9. Web stack: MVC versus WebFlux

- **Decision**: `spring-boot-starter-webmvc` returning `Flux<ServerSentEvent<String>>`.
- **Rationale**: Boot 4.1.1 MVC artifacts (`starter-webmvc`, `starter-validation`, `starter-test`, `starter-webmvc-test`) are in the local cache. The WebFlux starter is cached only for Boot 3.5.x. Reactor comes in through Spring AI. MVC can return a `Flux` as an SSE stream.
- **Risk**: **Unverified** that tokens flush incrementally under MVC with this version. Quickstart step 4 checks this with `curl -N` and measures time to first byte.
- **Alternative**: WebFlux (the ChatClient documentation's preferred stack). Switch only if the incremental-flush check fails.

## R-10. Chat memory window size

- **Decision**: `MessageWindowChatMemory` with `maxMessages=40` (property `support.memory.max-messages`), not the default 20.
- **Rationale**: User story 2, scenario 2 requires context after "ten or more turns". Ten turns are 20 messages, so the default window would already be evicting the first turn at turn 11. 40 messages gives 20 turns of context.
- **Cost**: Larger prompts per request; acceptable for the MVP.
- **Evidence**: Default window of 20 comes from the Spring AI chat-memory documentation (via the project skill); not re-checked in code.

## R-11. Failed turns and memory

- **Decision**: In `ChatService`, snapshot the conversation's messages before the call. If the stream fails or times out, restore the snapshot (clear, then re-add).
- **Rationale**: The memory advisor records the user message when the call starts, but the assistant message only after a successful completion. A failed turn would leave a dangling user message, and a retry would then duplicate it, violating the spec edge case "no duplicate turn is recorded".
- **Evidence**: The advisor's exact write timing is **Unverified**. A unit test pins the behaviour: after a failed stream the memory equals the pre-call snapshot. If the advisor already does this, the code becomes a no-op and the test still guards it.

## R-12. SSE contract

- **Decision**: Events are `token` (text chunk), `done`, and `error` (JSON `{code, message}`). Pre-stream failures (validation, rate limit, busy conversation) are plain HTTP errors with a JSON `ApiError`. Details are in [contracts/sse-events.md](contracts/sse-events.md).
- **Rationale**: A distinct event name lets the UI tell an error apart from model text and show retry (Constitution IV). Streams that fail after the first byte cannot change the HTTP status, so an in-band error event is required.
- **Token encoding**: each `token` event's data is a JSON string (for example `"Hello\n"`), so newlines in model output cannot break SSE framing.

## R-13. Client-side streaming from a POST

- **Decision**: `fetch` with `response.body.getReader()` and a small hand-written SSE parser, in `src/api/sseParser.ts`. Use `AbortController` for cancel and unmount.
- **Rationale**: `EventSource` is GET-only. `@microsoft/fetch-event-source` is workable but retries automatically by default, which fights our explicit retry UX. The parser is about 40 lines and is unit-testable.
- **Dev setup**: Vite proxies `/api` to the backend, so no CORS configuration is needed in development.

## R-14. Gemma and system instructions

- **Risk**: Some Gemma models on AI Studio have rejected a separate system instruction. If `gemma-4-26b-a4b-it` does, the persona (Constitution III) would not reach the model.
- **Decision**: Plan for it. Keep the system prompt in `ChatClient` defaults. If the smoke test (G-1) shows a system-role error, fold the system prompt into the first user turn inside `ChatClientConfig`, with no change to the service or API.
- **Evidence**: **Unverified**; settled by the smoke test.

## R-15. Conversation identifiers

- **Decision**: The client generates a UUID. The server accepts only canonical UUID strings (case-insensitive) and returns 400 otherwise.
- **Rationale**: Bounded, safe character set; prevents the id being used for log injection or key abuse. The id is not a secret, so there is no authentication claim. A guessed id could read nothing, since no endpoint returns history.

## R-16. Idle conversation expiry

- **Decision**: `ConversationRegistry` records last activity per conversation and a scheduled task clears conversations idle for more than `support.memory.idle-ttl` (default 2 h) via `ChatMemory.clear`.
- **Rationale**: See the plan's "Additions" table. Without this, memory grows without bound on a public endpoint.

## R-17. Logging and secret hygiene

- **Decision**: No `SimpleLoggerAdvisor` outside local debugging; no actuator; no HTTP wire logging for the SDK; request and response bodies are never logged at INFO. A test scans every response, including error bodies, for the configured key.
- **Rationale**: Constitution II and FR-018/FR-019.

## R-18. Testing strategy and success-criteria coverage

| Criterion | Verified by |
|-----------|-------------|
| SC-001 first token <= 2 s | Timed manual check in the quickstart (live key); stub-based test proves there is no buffering |
| SC-002 context use | Live eval over multi-turn scripts (opt-in) |
| SC-003 off-topic decline | Live eval, 30 prompts (opt-in) |
| SC-004 no invented facts | Live eval over pricing/quota/account prompts (opt-in) |
| SC-005 new chat clears | Automated: DELETE then memory empty; plus a live check |
| SC-006 error states | Automated with stubbed failures (unavailable, timeout, rate limit); UI tests |
| SC-007 no secrets leaked | Automated response scan |
| SC-008 bounded wait | Automated `StepVerifier` with virtual time |

SC-002 to SC-004 are statistical properties of the model. They are measured by an opt-in eval
run against the real model and reviewed by a human, not asserted in CI. Because the model output
is non-deterministic, a single run passing is evidence, not proof.

## Open items carried into tasks

1. G-1: Gemma smoke test and possible fallback (R-1, R-14).
2. Update `prd.md` sections 7 and 10 with the decisions in R-1, R-2, R-3 (constitution gate).
3. Confirm the SDK timeout unit (R-4).
4. Confirm incremental flushing under MVC (R-9).
5. Pin the failed-turn memory behaviour with a test (R-11).

## Implementation verification (2026-11-14)

Outcome of each open item, from the implementation pass. "Verified" means a passing automated
test or a run of the built application; "Open" means it still needs a live key.

| Item | Status | Evidence |
|------|--------|----------|
| R-1 / G-1: Gemma through the Google GenAI starter | **Open** | No `GOOGLE_API_KEY` in the build environment. `ProviderSmokeIT` is written and skips without the key. Run it before the Phase 3 checkpoint. |
| R-2: `.env` loading | **Verified** | The application booted with no environment variable and a temporary `.env` at the project root. The key value never appeared in the log. |
| R-3: key name `GOOGLE_API_KEY` | **Verified** | `ApiKeyStartupCheck` names it. Startup without a key fails with that name and no value. |
| R-4: SDK HTTP timeout unit | **Verified: milliseconds** | `GenAiClientTransportTest`. A 1000 ms timeout against a 3 s response fails within the limit. |
| R-4 / R-12: SDK error types | **Verified** | HTTP 429 raises `ClientException`, HTTP 5xx raises `ServerException`, and a timeout raises `GenAiIOException`. `ChatErrorMapper` classifies all three. |
| R-6: missing-key startup failure | **Verified** | Startup without a key stops with "GOOGLE_API_KEY is not set" and no value. |
| R-7: retry cap | **Verified by configuration** | Spring AI retry is capped at 2 attempts. The SDK's own retries are disabled (`HttpRetryOptions` attempts 1). |
| R-8: rate limit | **Verified** | `RateLimitFilterTest`: 20 per minute, 429 with `Retry-After`, `DELETE` not limited, window slides. |
| R-9: incremental flush under Spring MVC | **Verified: MVC kept** | `StreamingFlushTest` over a real port. Tokens arrived more than 500 ms before `done`, so the output is not buffered. |
| R-10: memory window of 40 | **Verified** | `MemoryWindowTest`. The first turn survives 12 turns, and the oldest whole turns are evicted beyond 40 messages. |
| R-11: failed-turn memory | **Verified** | `ChatServiceFailureTest.failedTurnLeavesMemoryAsItWasBeforeTheTurn`. A cancelled turn is restored the same way (`doOnCancel`). |
| R-12: pre-first-token failures as HTTP 503 | **Verified** | `ChatControllerStreamTest.providerFailureBeforeFirstTokenIsHttp503WithApiError`. The OpenAPI 503 entries stand as written. |
| R-14: system instruction accepted by Gemma | **Open** | Depends on the live smoke test (G-1). |
| R-16: idle expiry | **Verified** | `ConversationExpiryTest`. Idle conversations are cleared, and a conversation with a turn in flight is kept. |
| R-17: no key in logs | **Verified, with a fix** | `ApiKeyLeakTest` found that Spring AI's `MessageAggregator` logs the full provider exception message at ERROR. The key could reach the logs if a provider error echoed it. The logger is now set to OFF in `application.properties`, with the trade-off stated there. |

### Behaviour found during implementation (not in the original research)

- **New Chat during a reply:** `ChatService.clear` cancels the in-flight turn first. That cancel restores memory from before the turn, so clearing memory must come after it. The order is fixed in code and commented.
- **Turn-finished signal:** Turn state is released with `doOnTerminate` and `doOnCancel`, not `doFinally`. `doFinally` runs after the terminal signal reaches the caller, so a caller that starts the next turn immediately could still see the conversation as busy. `ChatServiceFailureTest` and `ConversationBusyTest` cover this.
- **Error responses and `Accept`:** The chat client sends `Accept: text/event-stream`. Error bodies therefore set `Content-Type: application/json` explicitly. Without that, content negotiation would answer 406 instead of the error.
- **Constraint violations:** A `@Validated` controller raises `ConstraintViolationException` for path variables. This maps to 400, not 500.
