# PRD: Caramel AI Tech Support Chat Assistant

## 1. Objective

Build a customer service chat assistant for **Caramel AI**, an LLM inferencing company similar to OpenRouter. The assistant provides **technical support on LLM inferencing** — helping customers with API usage, model selection, errors, rate limits, latency, billing questions, and integration issues.

## 2. Tech Stack

| Layer            | Choice                                 |
|------------------|----------------------------------------|
| Backend          | Java                                   |
| Framework        | Spring AI                              |
| Frontend         | React + TypeScript                     |
| LLM              | `gemma-4-26b-a4b-it`                   |
| Service provider | Google, via AI Studio                  |

## 3. Target Users

- Developers integrating Caramel AI's inference API into their apps.
- Technical teams troubleshooting production issues (errors, timeouts, throttling).
- Prospective customers evaluating models and pricing.

## 4. Scope

### In scope (MVP)
- Chat UI where a user sends messages and receives streamed assistant replies.
- Multi-turn conversation with context retained within a session.
- System prompt that frames the assistant as Caramel AI tech support and keeps it on-topic (LLM inferencing, API usage, troubleshooting).
- Backend REST endpoint(s) that call Gemma via Spring AI and stream responses.
- Basic error handling (provider failures, timeouts, rate limits) with friendly messages.

### Out of scope (for now)
- User authentication / accounts.
- Live account or billing lookups.
- Human agent handoff / ticketing integration.
- RAG over company docs (candidate for a later phase).
- Persistent chat history across sessions.

## 5. Functional Requirements

1. **Chat**: User can type a message and see the assistant's response streamed token by token.
2. **Conversation memory**: The assistant remembers earlier turns within the current session (Spring AI chat memory).
3. **Persona & guardrails**: The assistant responds as Caramel AI support, declines unrelated topics politely, and does not invent account-specific data, pricing, or policies it does not know.
4. **New chat**: User can reset the conversation.
5. **Error states**: UI shows clear messages when the backend or LLM is unavailable, and allows retry.

## 6. Non-Functional Requirements

- **Latency**: First token visible within ~2 seconds under normal conditions.
- **Security**: The Google AI Studio API key is stored in a `.env` file at the project root, loaded by the backend at startup, and never exposed to the frontend. `.env` must be git-ignored and must not be committed. A `.env.example` file with placeholder values is committed instead.
- **Reliability**: Timeouts and graceful failure on provider errors.
- **Maintainability**: Clear separation between controller, service, and Spring AI configuration layers.

## 7. High-Level Architecture

```
React + TypeScript UI  --(HTTP / SSE)-->  Spring Boot (Spring AI)  -->  Google AI Studio  -->  gemma-4-26b-a4b-it
```

- **Frontend**: Single-page chat interface; consumes a streaming endpoint (SSE).
- **Backend**: Spring Boot app using Spring AI `ChatClient` with a system prompt and in-memory chat memory, keyed by a session/conversation ID.
- **LLM access**: Spring AI's Google AI Studio–compatible integration with the Gemma model configured via properties.

### Configuration

| Item            | Value                                                                 |
|-----------------|-----------------------------------------------------------------------|
| Secrets file    | `.env` at project root (developer-provided)                            |
| API key variable| `GOOGLE_API_KEY` (decided: bound to `spring.ai.google.genai.api-key` via the Google GenAI starter) |
| Model           | `gemma-4-26b-a4b-it`, set in application properties                   |
| Template        | `.env.example` with `GOOGLE_API_KEY=your-key-here`, committed to git  |

- Spring Boot does not read `.env` natively. **Decided**: `spring.config.import=optional:file:./.env[.properties],optional:file:../.env[.properties]`, so the file is found from the project root or from `backend/`. A real `GOOGLE_API_KEY` environment variable takes precedence over the file, which covers shell and container use. `.env` entries are plain `KEY=value` lines with no `export` and no quotes. No dotenv library is used.
- **Integration path (decided, pending live verification)**: Spring AI Google GenAI starter (`spring-ai-starter-model-google-genai`, Spring AI 2.0.1), configured through `spring.ai.google.genai.*`. Fallback if Gemma rejects the starter's calls: AI Studio's OpenAI-compatible endpoint through the OpenAI starter with a custom base URL. Whether `gemma-4-26b-a4b-it` is accepted through the starter is confirmed by the smoke test in `backend/src/test/java/ai/caramel/support/live/ProviderSmokeIT.java`, which needs a real key.

## 8. API (Draft)

| Method | Path                        | Description                              |
|--------|-----------------------------|------------------------------------------|
| POST   | `/api/chat`                 | Send a message; returns streamed reply (SSE). Body: `{ conversationId, message }` |
| DELETE | `/api/chat/{conversationId}`| Clear a conversation's memory            |

## 9. Success Criteria

- A user can hold a coherent multi-turn tech-support conversation about LLM inferencing.
- Responses stream smoothly in the UI.
- Off-topic requests are politely redirected.
- API key is never visible in client code or network traffic, and `.env` is never committed to version control.

## 10. Open Questions

- ~~Exact Spring AI integration path for Gemma on AI Studio (Google GenAI starter vs. OpenAI-compatible endpoint).~~ **Decided**: Google GenAI starter, with the OpenAI-compatible endpoint as fallback (see section 7).
- Should a knowledge base (docs, FAQ, status page) be added in a follow-up phase via RAG?
- ~~Rate limiting and abuse protection for the public chat endpoint.~~ **Decided**: in-memory limit of 20 messages per minute per client, enforced by the backend, with `X-Forwarded-For` trusted only when configured. Reverse-proxy limits are a deployment concern and are not covered here.
