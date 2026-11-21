---
name: spring-ai-chatbot
description: Build a streaming, multi-turn chatbot backend with Java, Spring Boot and Spring AI (ChatClient, ChatModel, chat memory, system prompt, SSE) on Google GenAI / AI Studio, with a React client. Use when adding or changing chat endpoints, ChatClient configuration, conversation memory, system prompts, streaming, provider config, or error handling in this project.
---

# Spring AI chatbot (Java + Spring Boot + React)

Guidance for building the Caramel AI support chatbot backend. Written against **Spring AI 2.0.x** (docs checked at 2.0.2; the local Maven repo has 2.0.1 and 1.1.8). Spring AI changes quickly between minors, so if a signature here does not compile, check the reference docs and the Javadoc for the version in the POM before guessing.

Primary references:
- Chat Model API: https://docs.spring.io/spring-ai/reference/api/chatmodel.html
- ChatClient: https://docs.spring.io/spring-ai/reference/api/chatclient.html
- Chat memory: https://docs.spring.io/spring-ai/reference/api/chat-memory.html
- Google GenAI chat: https://docs.spring.io/spring-ai/reference/api/chat/google-genai-chat.html

Project rules in `CLAUDE.md` and `prd.md` take precedence over this skill. In particular: the API key never leaves the backend, the model name is a property and not hardcoded, memory is in-memory only, and RAG, auth, tools and persistence are out of scope unless asked.

## Core abstractions

| Type | Role |
|---|---|
| `ChatModel` | Low-level provider interface. `call(Prompt)` returns `ChatResponse`. `call(String)` is a shortcut. |
| `StreamingChatModel` | `stream(Prompt)` returns `Flux<ChatResponse>`. `stream(String)` returns `Flux<String>`. `ChatModel` extends it. |
| `Prompt` | A list of `Message`s (`SystemMessage`, `UserMessage`, `AssistantMessage`) plus optional `ChatOptions`. |
| `ChatOptions` | Portable options: model, temperature, maxTokens, topP, topK, stop sequences, penalties. Provider-specific subclasses exist (`GoogleGenAiChatOptions`). |
| `ChatResponse` | Holds `Generation`s and metadata. `getResult().getOutput()` is the `AssistantMessage`. |
| `ChatClient` | Fluent, higher-level API on top of `ChatModel`. Adds default system prompt, advisors (memory, logging), templating, structured output. **Use this for the chatbot.** |

Option precedence: with raw `ChatModel`, options in a `Prompt` fully replace the defaults (pass `null` to keep defaults). With `ChatClient`, per-request options are applied as a delta on top of the defaults.

The model API is stateless. History must be sent with every request, which is what chat memory does.

## Dependencies

Import the Spring AI BOM, then add the starter for the provider. Use the BOM version already in the project POM; do not mix 1.1.x and 2.0.x artifacts.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>${spring-ai.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-google-genai</artifactId>
  </dependency>
  <!-- plus spring-boot-starter-web or -webflux (see Streaming) -->
</dependencies>
```

## Provider configuration (Google GenAI / AI Studio)

Property names verified against the 2.0.1 autoconfigure jar:

```properties
# Gemini Developer API (AI Studio) mode is used when an api-key is set
spring.ai.google.genai.api-key=${GOOGLE_API_KEY}
spring.ai.google.genai.chat.model=${CHAT_MODEL:gemma-4-26b-a4b-it}
spring.ai.google.genai.chat.temperature=0.3
spring.ai.google.genai.chat.max-output-tokens=1024
```

- Connection prefix: `spring.ai.google.genai.*` (`api-key`, `project-id`, `location`, `credentials-uri`). Chat prefix: `spring.ai.google.genai.chat.*`.
- Per-request overrides use `GoogleGenAiChatOptions.builder().temperature(0.4).build()`.
- `thinking-level` and `thinking-budget` are mutually exclusive and apply to Gemini 2.5/3.x models.

Open items to verify before wiring (these are also open in `CLAUDE.md`):
1. **Gemma on the Google GenAI starter.** The docs list Gemini model names. Whether `gemma-4-26b-a4b-it` works through `spring-ai-starter-model-google-genai` is **unverified**. Test with one real call. The alternative is AI Studio's OpenAI-compatible endpoint through the OpenAI starter with a custom `base-url`. If Gemma rejects features such as a system role or safety settings, adjust accordingly.
2. **Loading `.env`.** Spring Boot does not read `.env` by default. Options: `spring.config.import=optional:file:.env[.properties]` (requires `KEY=value` lines and a matching property name such as `GOOGLE_API_KEY`), a dotenv library, or exporting env vars in the shell or container. Confirm with the user before choosing.
3. **Provider timeouts.** Set connect and read timeouts on provider calls. Find the correct knob for the chosen starter in its docs; do not invent property names. Also apply a request-level timeout on the stream (`Flux.timeout(...)`).

## ChatClient setup

Spring Boot autoconfigures a prototype-scoped `ChatClient.Builder`. Build one `ChatClient` bean with the system prompt and memory advisor.

```java
@Configuration
class ChatConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory,
                          @Value("classpath:prompts/support-system.st") Resource systemPrompt) {
        return builder
            .defaultSystem(systemPrompt)
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
            .build();
    }
}
```

- Keep the persona, scope limits and "do not invent account data, pricing or policies" rules in the system prompt file, not in controllers or the UI.
- Prompt templates use `{placeholder}` delimiters. A literal `{` in the prompt (JSON examples) will be parsed as a placeholder. Avoid braces, or switch delimiters with `StTemplateRenderer.builder().startDelimiterToken('<').endDelimiterToken('>').build()`.
- `ChatClient.create(chatModel)` and `ChatClient.builder(chatModel)` bypass autoconfiguration (observability, customizers). Prefer the injected builder.

## Chat memory

Spring AI autoconfigures a `ChatMemory` bean: `MessageWindowChatMemory` (default window of 20 messages, system messages preserved, eviction by whole turns) over `InMemoryChatMemoryRepository`. That matches the project requirement: in-memory, lost on restart.

```java
// Optionally size the window explicitly
@Bean
ChatMemory chatMemory() {
    return MessageWindowChatMemory.builder().maxMessages(20).build();
}
```

Rules:
- **`ChatMemory.CONVERSATION_ID` is required on every call** that goes through a memory advisor. There is no default, and omitting it throws `IllegalArgumentException`.
- The client supplies `conversationId` per `CLAUDE.md`. Validate it on the server (non-blank, bounded length, safe charset such as a UUID) and never use it to look up or reveal other users' data.
- Clear a conversation ("New chat"): `chatMemory.clear(conversationId)` (verified on `ChatMemory` in 2.0.1). At repository level the equivalent is `ChatMemoryRepository.deleteByConversationId(id)`.
- Memory is chat *context*, not a chat history record. Do not add persistence unless asked.

## Streaming

```java
Flux<String> tokens = chatClient.prompt()
    .user(message)
    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
    .stream()
    .content();
```

`stream()` returns `Flux<String>` (`content()`), `Flux<ChatResponse>` (`chatResponse()`), or `Flux<ChatClientResponse>`. Nothing is sent to the model until the terminal method is called and the Flux is subscribed.

Controller sketch for `POST /api/chat`:

```java
@RestController
@RequestMapping("/api/chat")
class ChatController {

    private final ChatService service;
    ChatController(ChatService service) { this.service = service; }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<String> chat(@Valid @RequestBody ChatRequest request) {
        return service.stream(request.conversationId(), request.message());
    }

    @DeleteMapping("/{conversationId}")
    ResponseEntity<Void> clear(@PathVariable String conversationId) {
        service.clear(conversationId);
        return ResponseEntity.noContent().build();
    }
}

record ChatRequest(@NotBlank String conversationId, @NotBlank @Size(max = 4000) String message) {}
```

Notes:
- The ChatClient docs say `stream()` is meant for a reactive stack (`spring-boot-starter-webflux`). Spring MVC can also return a `Flux` as SSE when Reactor is on the classpath, which Spring AI brings in. This is **unverified for this project**: pick one stack, then test that tokens arrive incrementally (for example with `curl -N`) and that the first token appears in about 2 seconds.
- Browser `EventSource` supports only GET. For a POST with a body, the React client must use `fetch` and read the response body stream, or a library such as `@microsoft/fetch-event-source`.
- Memory advisors are non-blocking on streaming calls. Built-in advisors can run on a configurable Reactor `Scheduler`.
- Keep the layers separate: controller (HTTP/SSE), `ChatService` (ChatClient orchestration, conversation handling, error mapping), configuration (model, key, memory).

## Error handling and timeouts

- Apply `.timeout(Duration)` on the Flux and map errors with `onErrorResume` or `onErrorMap` to a small set of friendly error types: provider unavailable, timeout, rate limited (HTTP 429), and a generic failure.
- Send errors to the client as a distinct SSE event (for example `event: error` with a short code and message) so the UI can show a retry option. Do not leak provider exception text, request payloads or the API key.
- Streaming errors are not covered in the Spring AI docs. Standard Reactor operators apply. Test a mid-stream failure.
- Do not log prompts, responses or keys at INFO. `SimpleLoggerAdvisor` (enable with `logging.level.org.springframework.ai.chat.client.advisor=DEBUG`) logs full content, so use it only locally and keep it last in the advisor chain.
- Do not retry non-idempotent calls blindly. Spring AI has its own retry module (`spring-ai-retry`). Check its settings before layering your own retry.

## React client essentials

- Generate `conversationId` with `crypto.randomUUID()` on first load and on "New chat". "New chat" calls `DELETE /api/chat/{conversationId}` and then resets local state with a fresh id.
- Read the stream with `fetch` plus `response.body.getReader()` and a `TextDecoder`. Append tokens to the last assistant message. Support `AbortController` for a stop button.
- States to handle: sending, streaming, error with retry (resend the last user message), and empty conversation.
- Never put the API key in frontend code, environment variables prefixed `VITE_`, or API responses.

## Testing

- Unit test `ChatService` with a mocked `ChatClient` or a stub `ChatModel` that returns a fixed `Flux`, and assert memory scoping by `conversationId` and the clear behavior.
- Use `StepVerifier` for streaming and error paths (timeout, provider failure).
- Do not call the real provider in CI. Keep one opt-in smoke test for the real model, gated on `GOOGLE_API_KEY`.

## Out of scope (do not add unless asked)

Tool or function calling, RAG or vector stores, persistent memory repositories (JDBC and others), authentication, account or billing lookups, human handoff.
