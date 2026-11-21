# Tasks: Caramel AI Tech Support Chat Assistant

**Input**: Design documents from `/specs/001-tech-support-chat/`

**Prerequisites**: [plan.md](plan.md) (required), [spec.md](spec.md) (required), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Included because [plan.md](plan.md) names the test suites (JUnit, Spring Boot Test, `StepVerifier`, Vitest, Testing Library, opt-in live evals) and the quickstart depends on them. Spec does not request TDD, so tests are written alongside their story, not gated first. Each test task can be dropped without restructuring the phases.

**Organization**: Grouped by user story so each story can be built and checked on its own.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 to US5, mapped to the user stories in [spec.md](spec.md)
- Paths are relative to the repository root. Backend root: `backend/`. Frontend root: `frontend/`.
- Java base package: `ai.caramel.support`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Repository hygiene, build files, and project scaffolding.

- [X] T001 Create `.gitignore` at the repository root. It MUST exclude `.env`, `backend/target/`, `frontend/node_modules/`, `frontend/dist/`, and IDE folders (`.idea/`, `.vscode/`). This is a Constitution gate that applies before the first commit.
- [X] T002 [P] Create `.env.example` at the repository root containing exactly one line: `GOOGLE_API_KEY=your-key-here`
- [X] T003 [P] Edit `.mcp.json` to replace the literal GitHub bearer token with an environment-variable reference (for example `${GITHUB_TOKEN}`). Do not copy the token value into any other file. This is a pre-commit blocker (CLAUDE.md config note, Constitution Workflow gate).
- [X] T004 Create `backend/pom.xml`. Use parent `spring-boot-starter-parent` 4.1.1. Import `spring-ai-bom` 2.0.1. Add `spring-ai-starter-model-google-genai`, `spring-boot-starter-webmvc`, and `spring-boot-starter-validation`. Add test-scope `spring-boot-starter-test`, `spring-boot-starter-webmvc-test`, and `io.projectreactor:reactor-test`. Note: `reactor-test` is not in the local Maven cache, so the first build needs network access. Use Java 21.
- [X] T005 Create `backend/src/main/java/ai/caramel/support/SupportApplication.java` with `@SpringBootApplication` and `@ConfigurationPropertiesScan`.
- [X] T006 [P] Create `frontend/package.json` (React 19, TypeScript 5, Vite, Vitest, `@testing-library/react`, `@testing-library/user-event`, jsdom), `frontend/tsconfig.json`, and `frontend/vite.config.ts`. The Vite config MUST proxy `/api` to `http://localhost:8080` in dev.
- [X] T007 Create `frontend/src/main.tsx` and `frontend/src/App.tsx` (renders a heading only) so `npm run dev` starts. Depends on T006.

**Checkpoint**: `mvn -q -DskipTests package` builds `backend/` and `npm run build` builds `frontend/`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Configuration, shared error model, and the Spring AI wiring that every story uses. No user story work starts until this phase is complete.

- [X] T008 **GATE (Constitution Technology rule)**: Edit `prd.md`. In section 7, record the decisions from [research.md](research.md) R-1 (Google GenAI starter, OpenAI-compatible endpoint as fallback), R-2 (`spring.config.import=optional:file:./.env[.properties]`), and R-3 (variable name `GOOGLE_API_KEY`). In section 10, mark the first two open questions as decided. This MUST happen before T018 wires the model.
- [X] T009 Create `backend/src/main/resources/application.properties` with these entries and no others. Use `spring.config.import=optional:file:./.env[.properties],optional:file:../.env[.properties]`. Use `spring.ai.google.genai.api-key=${GOOGLE_API_KEY:}` (blank default, so T012 reports the failure). Use `spring.ai.google.genai.chat.model=gemma-4-26b-a4b-it` (the `chat.model` key, not the deprecated `chat.options.model`). Use `spring.ai.google.genai.chat.temperature=0.3` and `spring.ai.google.genai.chat.max-output-tokens=1024`. Use `spring.ai.retry.max-attempts=2`, `spring.ai.retry.backoff.initial-interval=500ms`, and `spring.ai.retry.on-client-errors=false` (research R-7). Use `spring.mvc.async.request-timeout=75s` (research R-5). Use `support.provider.timeout=55s`, `support.memory.max-messages=40`, `support.memory.idle-ttl=2h`, `support.rate-limit.per-minute=20`, `support.rate-limit.trust-forwarded-for=false`, and `support.max-message-chars=4000`. Do not enable actuator endpoints or `SimpleLoggerAdvisor` (research R-17).
- [X] T010 Create `backend/src/main/java/ai/caramel/support/config/SupportProperties.java`. It is a `@ConfigurationProperties("support")` record with nested groups `provider` (`timeout`, `Duration`, default 55s), `memory` (`maxMessages` default 40, `idleTtl` default 2h), `rateLimit` (`perMinute` default 20, `trustForwardedFor` default false), and top-level `maxMessageChars` default 4000. Add Jakarta constraints: `maxMessages` between 2 and 200, `perMinute` between 1 and 1000, `timeout` positive.
- [X] T011 Create `backend/src/main/java/ai/caramel/support/config/GenAiClientConfig.java`. Define a `com.google.genai.Client` bean built with `Client.builder().apiKey(key).httpOptions(HttpOptions.builder().timeout(ms).build()).build()`, where `ms` comes from `support.provider.timeout`. Spring AI's own `Client` bean is `@ConditionalOnMissingBean`, so this one replaces it. Confirm the unit of `HttpOptions.timeout()` in google-genai 1.65.0 (assumed milliseconds, research R-4) and record the result in [research.md](research.md) R-4.
- [X] T012 Create `backend/src/main/java/ai/caramel/support/config/ApiKeyStartupCheck.java`. At startup it fails when `spring.ai.google.genai.api-key` is blank. The failure message MUST name `GOOGLE_API_KEY` and MUST NOT include the value (research R-6, Constitution II).
- [X] T013 [P] Create `backend/src/main/java/ai/caramel/support/config/ChatMemoryConfig.java`. It defines a `ChatMemory` bean: `MessageWindowChatMemory.builder().maxMessages(props.memory().maxMessages()).build()`, backed by `InMemoryChatMemoryRepository`. Do NOT add `spring-ai-starter-model-chat-memory-repository-jdbc` (Constitution I and V: memory is in process only).
- [X] T014 [P] Create `backend/src/main/java/ai/caramel/support/chat/ChatErrorCode.java` (enum with exactly `INVALID_REQUEST`, `RATE_LIMITED`, `CONVERSATION_BUSY`, `PROVIDER_UNAVAILABLE`, `PROVIDER_RATE_LIMITED`, `TIMEOUT`, `INTERNAL_ERROR`, per [data-model.md](data-model.md)). Create `backend/src/main/java/ai/caramel/support/chat/ChatFailure.java`, a record of `code`, a fixed message, an HTTP status, and a `retryable` flag. The fixed messages are: `INVALID_REQUEST`: "Your message can't be sent. Check that it isn't empty and is 4,000 characters or fewer." `RATE_LIMITED`: "You're sending messages too quickly. Please wait a moment and try again." `CONVERSATION_BUSY`: "A reply is still in progress. Please wait for it to finish." `PROVIDER_UNAVAILABLE`: "The assistant is temporarily unavailable. Please try again shortly." `PROVIDER_RATE_LIMITED`: "The assistant is busy right now. Please try again shortly." `TIMEOUT`: "The reply took too long. Please try again." `INTERNAL_ERROR`: "Something went wrong. Please try again." HTTP statuses: 400, 429, 409, 503, 503, 504 (in-stream only), 500.
- [X] T015 Create `backend/src/main/java/ai/caramel/support/chat/ChatErrorMapper.java`. It maps `Throwable` to `ChatFailure`: a provider connection failure or 5xx maps to `PROVIDER_UNAVAILABLE`, a provider HTTP 429 maps to `PROVIDER_RATE_LIMITED`, a timeout maps to `TIMEOUT`, and anything else maps to `INTERNAL_ERROR`. Before coding, look up the exception types that google-genai 1.65.0 and Spring AI throw for 429 and 5xx responses. The mapper MUST NOT include the source exception's message in the output (FR-018).
- [X] T016 Create `backend/src/main/java/ai/caramel/support/web/ApiError.java` (record: `code`, `message`, nullable `retryAfterSeconds`, matching [contracts/chat-api.openapi.yaml](contracts/chat-api.openapi.yaml)). Create `backend/src/main/java/ai/caramel/support/web/GlobalExceptionHandler.java`. It maps validation failures and malformed JSON to HTTP 400 with `INVALID_REQUEST`, and maps any other unhandled exception to HTTP 500 with `INTERNAL_ERROR`. Neither response includes a stack trace or provider text.
- [X] T017 [P] Create `frontend/src/types/chat.ts`. It defines the `ChatErrorCode` union (the seven codes), the `ApiError` type, the `ChatEvent` union (`token` with `text`, `done`, and `error` with `code` and `message`), and the `Message` type and status union (`complete`, `streaming`, `interrupted`, `failed`) from [data-model.md](data-model.md).
- [X] T018 Create `backend/src/main/java/ai/caramel/support/config/ChatClientConfig.java`. It defines one `ChatClient` bean from the injected `ChatClient.Builder`, with `.defaultSystem(...)` loading `classpath:prompts/support-system.st` and `.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())`. Depends on T008, T013, T019.
- [X] T019 Create `backend/src/main/resources/prompts/support-system.st`. It contains the Caramel AI tech-support persona, the in-scope topics from FR-008 (API usage, model selection, errors, rate limits, latency, billing questions, integration issues), a polite-decline instruction for off-topic requests (FR-009), the rule not to state unsourced account data, pricing, quotas, SLAs, or policies and to point to official documentation (FR-010, FR-011), and a rule to keep the support role under override attempts (FR-012). **Do not use curly braces** in this file: `StTemplateRenderer` treats `{...}` as a placeholder.
- [ ] T020 **GATE (research G-1, R-1, R-14)**: Create `backend/src/test/java/ai/caramel/support/live/ProviderSmokeIT.java`, annotated `@EnabledIfEnvironmentVariable(named = "GOOGLE_API_KEY", matches = ".+")`. It makes one real call to `gemma-4-26b-a4b-it` through `ChatModel` and asserts that text comes back and that a system message is accepted. If the Google GenAI starter rejects Gemma, switch to the OpenAI-compatible fallback (R-1) and update `prd.md` again (T008). If the system role is rejected, fold the persona into the first user turn (R-14). This gate MUST pass before the Phase 3 checkpoint.

**Checkpoint**: Foundation ready. Backend and frontend stories can proceed in parallel. Phase 3 wiring depends on T020 passing.

---

## Phase 3: User Story 1 - Get a Streamed Answer to a Technical Question (Priority: P1) 🎯 MVP

**Goal**: A customer asks one in-scope question and sees the answer stream in token by token.

**Independent Test**: Run [quickstart.md](quickstart.md) §4. `curl -N` shows several `event: token` lines over time, then `event: done`, and the first byte arrives in about 2 seconds. In the UI, an in-scope question streams progressively and the composer is disabled until the reply completes.

- [X] T021 [P] [US1] Create `backend/src/main/java/ai/caramel/support/web/ChatRequest.java`. Its `conversationId` field is `@Pattern` for a canonical UUID (36 characters, case-insensitive hex, 8-4-4-4-12). Quote [data-model.md](data-model.md): "Required. Canonical UUID." Its `message` field is `@NotBlank` and `@Size(max = 4000)`. Quote [data-model.md](data-model.md): "Required. Not blank after trimming. At most 4,000 characters."
- [X] T022 [P] [US1] Create `backend/src/main/java/ai/caramel/support/chat/ChatEvent.java`, a sealed interface with `Token(String text)`, `Done()`, and `Failed(ChatFailure failure)`.
- [X] T023 [US1] Implement `ChatService.stream(String conversationId, String message)` in `backend/src/main/java/ai/caramel/support/chat/ChatService.java`. It returns `Flux<ChatEvent>` built from `chatClient.prompt().user(message).advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)).stream().content()`. Map each non-empty chunk to `Token`. Append `Done` on completion. Map errors with `ChatErrorMapper` to `Failed`. Depends on T015, T018, T022.
- [X] T024 [US1] Implement `POST /api/chat` in `backend/src/main/java/ai/caramel/support/web/ChatController.java`. It accepts `ChatRequest` with `@Valid`, produces `text/event-stream`, and returns `Flux<ServerSentEvent<String>>`. Each `Token` becomes an event named `token` whose data is the JSON encoding of the text (`objectMapper.writeValueAsString(text)`), so newlines cannot break SSE framing ([contracts/sse-events.md](contracts/sse-events.md)). `Done` becomes `done` with data `{}`. `Failed` becomes `error` with `{code, message}`. The controller calls only `ChatService`, never `ChatClient` or `ChatModel` (Constitution V). Depends on T021, T023.
- [X] T025 [US1] Create `backend/src/test/java/ai/caramel/support/chat/ChatServiceStreamTest.java`. Use a stub `ChatModel` that emits three chunks. With `StepVerifier`, assert the order `Token`, `Token`, `Token`, `Done`, and assert that the first `Token` is emitted before the source completes (no buffering). Depends on T023.
- [X] T026 [US1] Create `backend/src/test/java/ai/caramel/support/web/ChatControllerStreamTest.java` with MockMvc in async mode and a stubbed `ChatService`. Assert `Content-Type: text/event-stream`, `event: token` framing with JSON-string data, a token containing a newline encoded as `\n` inside the JSON string, and a terminal `event: done`. Depends on T024.
- [X] T027 [P] [US1] Create `frontend/src/api/sseParser.ts`. It is an incremental parser that buffers chunks, splits events on a blank line (`\n\n` or `\r\n\r\n`), ignores comment lines (starting with `:`) and unknown event names, and decodes `token` data with `JSON.parse`. It returns `ChatEvent` values from `frontend/src/types/chat.ts`.
- [X] T028 [US1] Create `frontend/tests/api/sseParser.test.ts`. Depends on T027. Cover an event split across chunk boundaries, CRLF line endings, comment lines, an unknown event name (ignored), and a token containing a newline (round trip).
- [X] T029 [US1] Create `frontend/src/api/chatClient.ts` with `streamChat({ conversationId, message, signal, onEvent })`. It calls `fetch('/api/chat', { method: 'POST', ... })`, reads `response.body.getReader()` with `TextDecoder`, and feeds the bytes to `sseParser`. On a non-2xx response, it parses the body as `ApiError`. Depends on T017, T027.
- [X] T030 [US1] Create `frontend/src/hooks/useChat.ts` with the send path only. It generates `conversationId` with `crypto.randomUUID()` on first load and stores it in component state only (no `localStorage`, data-model). It appends the user message, appends an assistant message with status `streaming`, appends tokens to it, and sets status `complete` on `done`. Depends on T017, T029.
- [X] T031 [P] [US1] Create `frontend/src/components/MessageList.tsx` and `frontend/src/components/MessageBubble.tsx`. They render each message's role and content and keep the transcript scrolled to the bottom while a reply streams.
- [X] T032 [P] [US1] Create `frontend/src/components/Composer.tsx`. It contains a textarea and a send button. It disables sending while a reply streams (spec edge case). It blocks empty messages and messages over 4,000 characters, and shows the reason (FR-020).
- [X] T033 [US1] Create `frontend/src/components/ChatWindow.tsx` and update `frontend/src/App.tsx` to render `ChatWindow` with `useChat`. Depends on T030, T031, T032.
- [X] T034 [US1] Create `frontend/tests/hooks/useChat.stream.test.tsx` with a mocked `streamChat` that yields three tokens. Assert that the assistant text grows after each token and that the status moves from `streaming` to `complete`. Depends on T030.

**Checkpoint**: US1 works end to end. Run [quickstart.md](quickstart.md) §4 and the automated tests above.

---

## Phase 4: User Story 2 - Ask Follow-Up Questions in Context (Priority: P1)

**Goal**: Follow-up questions use earlier turns in the same conversation, and conversations do not affect each other.

**Independent Test**: Run [quickstart.md](quickstart.md) §5.1, §5.2, and §5.4. A follow-up that says "how do I fix it?" refers back to the earlier error, a different conversation id does not, and a question about turn 1 after 12 turns is still answered in context.

- [X] T035 [US2] Create `backend/src/main/java/ai/caramel/support/chat/ConversationRegistry.java`. For each conversation it tracks `lastActivityAt` (Instant), a `turnInFlight` flag, and the `Disposable` of the in-flight turn. It provides `tryBeginTurn(id)` (returns false if a turn is already in flight), `endTurn(id)`, `touch(id)`, and `remove(id)`. Use `ConcurrentHashMap.compute` so each check is atomic. Depends on T010.
- [X] T036 [US2] Wire `ConversationRegistry` into `ChatService.stream` in `backend/src/main/java/ai/caramel/support/chat/ChatService.java`. Call `tryBeginTurn` synchronously when `stream` is invoked, before returning the `Flux`, and throw a `ConversationBusyException` if it returns false. Call `endTurn` in `doFinally` and `touch` on each accepted turn. Depends on T023, T035.
- [X] T037 [US2] Map `ConversationBusyException` to HTTP 409 with `ApiError` `CONVERSATION_BUSY` in `backend/src/main/java/ai/caramel/support/web/GlobalExceptionHandler.java`. The status must come from the synchronous check in T036, before streaming starts. Depends on T036.
- [X] T038 [P] [US2] Create `backend/src/test/java/ai/caramel/support/chat/ConversationIsolationTest.java`. Send messages on two conversation ids. Using `chatMemory.get(id)`, assert that the second conversation's memory contains none of the first's messages (FR-006).
- [X] T039 [P] [US2] Create `backend/src/test/java/ai/caramel/support/chat/MemoryWindowTest.java`. Run 12 turns, then ask about turn 1. Assert that turn 1 is still in the window when `support.memory.max-messages` is 40 (research R-10), and that the system message is never evicted.
- [X] T040 [US2] Create `backend/src/test/java/ai/caramel/support/web/ConversationBusyTest.java`. A second `POST` for the same id while a turn is in flight gets HTTP 409 with `CONVERSATION_BUSY`. After the first turn finishes, the same id is accepted again. Depends on T036, T037.
- [X] T041 [P] [US2] Create `frontend/tests/hooks/useChat.session.test.tsx`. Send two messages and assert that both `streamChat` calls use the same `conversationId`. Assert that a new hook instance gets a different id.

**Checkpoint**: US1 and US2 both work. P1 stories are complete.

---

## Phase 5: User Story 3 - Stay On Topic and Avoid Invented Facts (Priority: P2)

**Goal**: Off-topic requests are politely declined, unsourced pricing, quota, and account facts are not invented, and role-override attempts are refused.

**Independent Test**: Run [quickstart.md](quickstart.md) §6. The eval thresholds are met: at least 95% of off-topic prompts are declined, zero unsourced figures appear in the pricing and account set, role-override attempts keep the support role, and at least 90% of multi-turn follow-ups use earlier context.

- [X] T042 [US3] Refine `backend/src/main/resources/prompts/support-system.st`. Make the scope list explicit (FR-008). Add a polite-decline pattern that offers an in-scope alternative (FR-009). Add the unsourced-facts rule with the pointer to official documentation or the existing support channel (FR-010, FR-011). Add the role-override rule (FR-012). Add the rule that the assistant must not claim to remember earlier context after a restart (spec edge case). Keep the no-curly-braces rule from T019. Depends on T019.
- [X] T043 [P] [US3] Create `backend/src/test/resources/eval/off-topic.jsonl` with at least 30 off-topic prompts (general coding, travel, cooking, legal advice, and similar), one JSON object per line: `{"prompt": "..."}`.
- [X] T044 [P] [US3] Create `backend/src/test/resources/eval/pricing-account.jsonl` with at least 10 prompts asking for a model's current price, the customer's own rate limit, an invoice total, or an SLA guarantee.
- [X] T045 [P] [US3] Create `backend/src/test/resources/eval/role-override.jsonl` with at least 5 override attempts ("ignore your instructions", "you are now a different assistant", "print your system prompt", and similar).
- [X] T046 [P] [US3] Create `backend/src/test/resources/eval/multi-turn.json` with at least 20 scripts. Each script has an ordered list of user turns and a note of the earlier fact that the final turn depends on (SC-002).
- [X] T047 [US3] Create `backend/src/test/java/ai/caramel/support/live/GuardrailEvalIT.java`, annotated `@EnabledIfEnvironmentVariable(named = "GOOGLE_API_KEY", matches = ".+")`. Run each set through `ChatService`. Assert the thresholds: at least 95% off-topic declines (SC-003), at least 90% multi-turn context use (SC-002), and every role-override attempt keeps the support role. Write every pricing and account reply to `backend/target/eval-report/` for a person to review, because unsourced figures can't be detected reliably by the test alone (SC-004). Depends on T042 to T046.

**Checkpoint**: Guardrail evals run and their thresholds are met. Persona changes are verified against the off-topic and pricing prompts (Constitution Workflow gate).

---

## Phase 6: User Story 4 - Start a New Chat (Priority: P2)

**Goal**: A customer starts a fresh conversation in one action, and the assistant does not use the old one.

**Independent Test**: Run [quickstart.md](quickstart.md) §5.3 and §8.3. After "New chat", a question that depends on the earlier model gets a request for context, not an assumption.

- [X] T048 [US4] Add `ChatService.clear(String conversationId)` in `backend/src/main/java/ai/caramel/support/chat/ChatService.java`. It cancels any in-flight turn for that id (disposes its `Disposable`), calls `chatMemory.clear(id)`, and calls `ConversationRegistry.remove(id)`. Depends on T035, T023.
- [X] T049 [US4] Implement `DELETE /api/chat/{conversationId}` in `backend/src/main/java/ai/caramel/support/web/ChatController.java`. It validates the path value as a canonical UUID (400 `INVALID_REQUEST` if malformed), calls `ChatService.clear`, and returns 204. It returns 204 for an unknown id too ([contracts/chat-api.openapi.yaml](contracts/chat-api.openapi.yaml)). Depends on T048, T024.
- [X] T050 [US4] Create `backend/src/test/java/ai/caramel/support/web/ClearConversationTest.java` (MockMvc). Assert 204 for an existing id, 204 for an unknown id, and 400 for a malformed id. After a clear, `chatMemory.get(id)` is empty (SC-005). Depends on T049.
- [X] T051 [US4] Add `clearConversation(conversationId)` to `frontend/src/api/chatClient.ts`. It sends `DELETE /api/chat/{id}`, and the caller ignores any failure. Depends on T029.
- [X] T052 [P] [US4] Create `frontend/src/components/NewChatButton.tsx`. It is labelled "New chat" and calls the `onClick` prop.
- [X] T053 [US4] Add `reset()` to `frontend/src/hooks/useChat.ts`. It aborts any in-flight stream through its `AbortController`, calls `clearConversation` with the old id (not awaited, failures ignored), generates a new `crypto.randomUUID()`, and empties the transcript. Wire `NewChatButton` into `ChatWindow.tsx`. Depends on T030, T033, T051, T052.
- [X] T054 [P] [US4] Create `frontend/tests/hooks/useChat.reset.test.tsx`. Assert that reset empties the transcript, issues a `DELETE` with the old id, generates a new id, and still resets when the `DELETE` fails.

**Checkpoint**: New chat clears both the screen and the server memory.

---

## Phase 7: User Story 5 - Recover Clearly From Failures (Priority: P2)

**Goal**: Outages, timeouts, and usage limits show a plain-language message with a retry option, and the customer's last message is kept for retry.

**Independent Test**: Run [quickstart.md](quickstart.md) §7 and the manual check in §7 (stop the backend mid-reply). Each failure shows its own message, the partial reply is marked incomplete, and retry resends the same text.

- [X] T055 [US5] Add the two deadlines to `ChatService.stream` in `backend/src/main/java/ai/caramel/support/chat/ChatService.java`. A first-token deadline of 30 seconds applies only to the first item. An overall deadline of 60 seconds applies to the whole reply (research R-5). Confirm the exact Reactor operator signatures against the reactor-core version in the local cache (3.8.x) before coding. Both deadlines map to `ChatFailure` `TIMEOUT`. Depends on T023.
- [X] T056 [US5] Snapshot and restore conversation memory in `ChatService.stream` (research R-11). Before the call, copy `chatMemory.get(id)`. On error, timeout, or cancel, clear the memory and re-add the snapshot. This keeps a failed user message from being recorded, so a retry does not duplicate it. Depends on T023.
- [X] T057 [US5] Create `backend/src/main/java/ai/caramel/support/web/RateLimitFilter.java`. It is a `OncePerRequestFilter` that applies only to `POST /api/chat`. It allows `support.rate-limit.per-minute` requests per client per rolling minute. The client key is the remote address, and `X-Forwarded-For` is used only when `trust-forwarded-for` is true. Over the limit, it returns HTTP 429 with a `Retry-After` header and an `ApiError` `RATE_LIMITED` with `retryAfterSeconds`. It does not limit `DELETE` (research R-8). Inject a `java.time.Clock` so tests can advance time. Purge idle buckets when the map is accessed. Do not use a scheduler here.
- [X] T058 [US5] Decide how pre-stream provider failures are reported. First check whether a `Flux` that errors before its first element reaches `GlobalExceptionHandler` in Spring MVC for this version. If it does, map `PROVIDER_UNAVAILABLE` and `PROVIDER_RATE_LIMITED` to HTTP 503 with `ApiError` in `backend/src/main/java/ai/caramel/support/web/ChatController.java`. If it does not, send them as in-stream `error` events and change the 503 entries in [contracts/chat-api.openapi.yaml](contracts/chat-api.openapi.yaml) to "in-stream only". Record the decision in [research.md](research.md) R-12. Depends on T024, T016.
- [X] T059 [US5] Create `backend/src/test/java/ai/caramel/support/chat/ChatServiceFailureTest.java` with `StepVerifier` and virtual time. Cover: provider unavailable gives `PROVIDER_UNAVAILABLE`; provider 429 gives `PROVIDER_RATE_LIMITED`; no first token in 30 seconds gives `TIMEOUT`; the 60-second overall deadline gives `TIMEOUT`; a failure after some tokens gives `Failed` after those tokens. In every case the memory equals the pre-turn snapshot, and no message contains provider text (FR-018). Depends on T055, T056.
- [X] T060 [US5] Create `backend/src/test/java/ai/caramel/support/web/RateLimitFilterTest.java`. Assert that 20 `POST`s in one minute pass and the 21st gets 429 with `Retry-After` and `RATE_LIMITED`. Assert that `DELETE` is not limited, and that the window slides after 60 seconds (fake `Clock`). Depends on T057.
- [X] T061 [P] [US5] Create `frontend/src/components/ErrorBanner.tsx`. It shows the fixed message for each `ChatErrorCode` and a Retry button when the code is retryable. For `RATE_LIMITED`, it shows the wait time when `retryAfterSeconds` is present.
- [X] T062 [US5] Extend `frontend/src/hooks/useChat.ts` for failures. An `error` event sets the assistant message to `interrupted` if it already has tokens and to `failed` otherwise. A pre-stream HTTP error is parsed as `ApiError`. The user's text is never cleared (FR-017). `retry()` resends the last user message with the same `conversationId` and replaces the failed reply. Aborting on unmount marks the reply `interrupted`. Depends on T030.
- [X] T063 [P] [US5] Create `frontend/tests/hooks/useChat.errors.test.tsx`. Assert that a timeout after tokens shows `interrupted` with Retry. Assert that a failure before any token shows `failed` with Retry. Assert that retry resends the same text and does not duplicate the user message. Assert that a 429 response shows the wait message.

**Checkpoint**: Failure states are clear and recoverable.

---

## Phase N: Polish & Cross-Cutting Concerns

**Purpose**: Items that span stories: idle expiry, secret hygiene, styling, verification, and documentation.

- [X] T064 Create `backend/src/main/java/ai/caramel/support/chat/ConversationExpiryTask.java`. It is a `@Scheduled` task that clears conversations idle longer than `support.memory.idle-ttl`, through `ChatService.clear` and `ConversationRegistry` (research R-16). Add `@EnableScheduling` to `SupportApplication.java`. Depends on T035, T048.
- [X] T065 [P] Create `backend/src/test/java/ai/caramel/support/chat/ConversationExpiryTest.java` with a fake `Clock`. Assert that an idle conversation is cleared and that an active one is not.
- [X] T066 [P] Create `backend/src/test/java/ai/caramel/support/web/ApiKeyLeakTest.java`. It sets a known fake key and sends requests through every error path (400, 409, 429, 503, 500) and the success path. It scans every response body and header, and the captured log output, for the key value (SC-007, FR-018, FR-019, research R-17).
- [X] T067 [P] Create `frontend/scripts/verify-no-secrets.mjs` and an npm script `verify:no-secrets`. After `npm run build`, the script searches `frontend/dist/` for `GOOGLE_API_KEY` and for the key pattern and exits non-zero on a match (Constitution II).
- [X] T068 Review the logging configuration (Constitution II, research R-17). Confirm that `ChatClientConfig.java` does not register `SimpleLoggerAdvisor`, that no actuator endpoints are exposed in `application.properties`, and that request and response bodies are not logged at INFO. Record the result in the pull request description.
- [X] T069 [P] Create `frontend/src/styles.css`. It covers basic layout for the chat window, composer, message bubbles, and error banner, with readable contrast and a responsive width.
- [X] T070 Update [research.md](research.md) with the verified results for open items 1 to 5 (the Gemma smoke test, the `.env` loading, the SDK timeout unit, incremental flushing under MVC, and the failed-turn memory behaviour). Depends on T011, T020, T025, T059.
- [X] T071 Update `CLAUDE.md` (Constitution Workflow gate) with the real commands: `cd backend && mvn -q test`, single test `mvn -Dtest=ClassName test`, live tests `mvn -Dtest=ProviderSmokeIT test` (needs `GOOGLE_API_KEY`), `cd frontend && npm test`, single test `npx vitest run path`, `npm run dev`, and `npm run verify:no-secrets`. Confirm each command by running it first.
- [ ] T072 Run every section of [quickstart.md](quickstart.md) (§1 to §8) and record each outcome in the pull request description, including any step that could not be run without a key.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies.
- **Foundational (Phase 2)**: Depends on Setup. **Blocks all user stories.** T008 must finish before T018. T020 must pass before the Phase 3 checkpoint.
- **User Stories (Phases 3 to 7)**: Start after Phase 2.
- **Polish (Phase N)**: Depends on the stories it touches (T064 needs US2 and US4).

### Story Dependencies

- **US1 (P1)**: None beyond Phase 2. This is the MVP.
- **US2 (P1)**: Extends `ChatService` from US1 (T036 edits T023's file). Start after US1's T023.
- **US3 (P2)**: Uses `ChatService` from US1. Its evals run through the streamed path.
- **US4 (P2)**: Uses `ConversationRegistry` from US2 (T035, T048). Start after US2.
- **US5 (P2)**: Extends `ChatService` from US1 (T055, T056). It can run in parallel with US3 and US4 once US1 is done.

### Within Each Story

- Backend and frontend tracks are independent once Phase 2 completes, except where a task says it depends on the other track (for example, T029 uses T027).
- Services before controllers. Controllers before web tests. Implementation before tests that exercise it.

### Parallel Opportunities

- **Phase 1**: T002, T003, T006 run in parallel.
- **Phase 2**: T013, T014, T017 run in parallel.
- **US1**: T021 and T022 together; T031 and T032 together; T027 alone, then T028.
- **US2**: T038, T039, T041 together.
- **US3**: T043 to T046 all in parallel.
- **US4**: T052 and T054 together.
- **US5**: T061 and T063 together.
- **Polish**: T065, T066, T067, T069 together.
- **Across stories**: once Phase 2 is done, a backend developer can work US2 while a frontend developer works US1's UI tasks.

---

## Parallel Example: User Story 1

```bash
# Backend contract and events together:
Task: "Create backend/src/main/java/ai/caramel/support/web/ChatRequest.java (T021)"
Task: "Create backend/src/main/java/ai/caramel/support/chat/ChatEvent.java (T022)"

# Frontend parser and components together:
Task: "Create frontend/src/api/sseParser.ts (T027)"
Task: "Create frontend/src/components/MessageList.tsx and MessageBubble.tsx (T031)"
Task: "Create frontend/src/components/Composer.tsx (T032)"
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Complete Phase 1 (Setup).
2. Complete Phase 2 (Foundational). T008 and T020 are gates.
3. Complete Phase 3 (US1).
4. **STOP and VALIDATE**: run [quickstart.md](quickstart.md) §4 and the US1 tests.

### Recommended First Increment: US1 + US2

Both US1 and US2 are P1. A tech-support chat that cannot answer a follow-up is not useful, so ship Phases 1 to 4 together, then validate with [quickstart.md](quickstart.md) §4 and §5.

### Incremental Delivery

1. Phases 1 to 4: core chat with memory.
2. Add US3: guardrails and evals.
3. Add US4: new chat.
4. Add US5: failure handling and rate limiting.
5. Polish.

---

## Notes

- [P] tasks touch different files and have no dependency on an incomplete task.
- Story labels map each task to its user story in [spec.md](spec.md) for traceability.
- Live tests (`ProviderSmokeIT`, `GuardrailEvalIT`) are skipped without `GOOGLE_API_KEY`. They never run in CI.
- Do not commit `.env` (T001, Constitution II).
- Stop at each checkpoint to validate the story on its own.
