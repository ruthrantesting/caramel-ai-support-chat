# Data Model: Caramel AI Tech Support Chat Assistant

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

Nothing is persisted (Constitution V). The server holds conversation state in process memory. The
browser holds the visible transcript in component state. A server restart or page reload loses both.

## Server-side state

### Conversation (server)

Backed by Spring AI chat memory, keyed by `conversationId`. It is not a class we define; the
`ChatMemory` bean owns the messages. `ConversationRegistry` adds bookkeeping only.

| Field | Type | Rules |
|-------|------|-------|
| `conversationId` | string (UUID) | Supplied by the client. Canonical UUID format, 36 chars, case-insensitive. Anything else is rejected with `INVALID_REQUEST`. |
| messages | ordered list (system, user, assistant) | Held by `MessageWindowChatMemory`. Window: `support.memory.max-messages` (default 40). Oldest whole turns are evicted first. The system message is never evicted. |
| `lastActivityAt` | instant | Updated on every accepted turn. Used for idle expiry (default 2 h). |
| `turnInFlight` | boolean | True while a reply is streaming. A second `POST` for the same id is rejected with `CONVERSATION_BUSY`. |

**Lifecycle**:

```text
(absent) --first accepted POST--> active
active --POST accepted--> active (turnInFlight = true until the stream ends)
active --DELETE--> (absent)
active --idle > TTL--> (absent)
any    --server restart--> (absent)
```

**Failed turns**: if a stream ends in error or timeout, the conversation is restored to its state
before that turn (research R-11). The failed user message is not kept, so a retry does not duplicate it.

### Rate-limit bucket (server)

| Field | Type | Rules |
|-------|------|-------|
| client key | string | Remote address, or the first `X-Forwarded-For` value only when `trust-forwarded-for` is on. |
| request timestamps | sliding list | Entries older than 60 s are dropped. More than `support.rate-limit.per-minute` (default 20) returns `RATE_LIMITED`. |

Idle buckets are purged on a schedule so the map cannot grow without bound.

## Wire entities

### ChatRequest (`POST /api/chat` body)

| Field | Type | Validation |
|-------|------|------------|
| `conversationId` | string | Required. Canonical UUID. |
| `message` | string | Required. Not blank after trimming. At most 4,000 characters (FR-020). |

### ChatEvent (SSE)

| Event | Data | Meaning |
|-------|------|---------|
| `token` | JSON string | A chunk of assistant text. Concatenate in arrival order. |
| `done` | `{}` | The reply is complete. |
| `error` | `{ "code": ChatErrorCode, "message": string }` | The reply ended abnormally. No `done` follows. |

### ChatErrorCode

| Code | When | HTTP (pre-stream only) | User-facing message (summary) | Retry offered |
|------|------|------------------------|-------------------------------|---------------|
| `INVALID_REQUEST` | Empty, too long, or malformed input | 400 | Explains the limit | No (edit first) |
| `RATE_LIMITED` | Over 20 messages per minute | 429 (+ `Retry-After`) | Please wait a moment | Yes, after the wait |
| `CONVERSATION_BUSY` | Another reply is in progress for this id | 409 | A reply is still in progress | Yes |
| `PROVIDER_UNAVAILABLE` | Provider down, network failure, 5xx | 503 / in-stream | Service temporarily unavailable | Yes |
| `PROVIDER_RATE_LIMITED` | Provider returned 429 | 503 / in-stream | The service is busy; try again shortly | Yes |
| `TIMEOUT` | No first token in 30 s, or reply exceeded 60 s | in-stream | The reply took too long | Yes |
| `INTERNAL_ERROR` | Anything else | 500 / in-stream | Something went wrong | Yes |

Messages are fixed strings owned by the backend. Provider text, stack traces, and request payloads
are never included (FR-018).

## Client-side state

### Message (frontend)

| Field | Type | Rules |
|-------|------|-------|
| `id` | string | Local, unique within the transcript. |
| `role` | `"user"` or `"assistant"` | |
| `content` | string | For the assistant, grows as tokens arrive. |
| `status` | `"complete"` / `"streaming"` / `"interrupted"` / `"failed"` | See transitions below. |
| `error` | `{ code, message }`, optional | Present when `status` is `interrupted` or `failed`. |

**Assistant message transitions**:

```text
streaming --done event--> complete
streaming --error event after >= 1 token--> interrupted   (partial text kept, marked incomplete)
streaming --error event before any token--> failed
streaming --user cancel / unmount--> interrupted
```

A user message whose reply ended in `failed` or `interrupted` is the **pending retry** target.
Retry resends that text with the same `conversationId` and replaces the failed reply. The text is
never cleared on failure (FR-017).

### Chat session (frontend)

| Field | Type | Rules |
|-------|------|-------|
| `conversationId` | string (UUID) | `crypto.randomUUID()` on first load and on every New chat. Not stored in `localStorage`, so a reload starts fresh (no persistence). |
| `messages` | Message[] | Ordered transcript. |
| `phase` | `"idle"` / `"sending"` / `"streaming"` / `"error"` | The composer is disabled during `sending` and `streaming`. |

**New chat**: send `DELETE /api/chat/{oldId}` (ignore failure; the server also expires it), then
create a new `conversationId` and an empty transcript. The UI does not wait on the `DELETE`.

## Mapping to spec requirements

| Requirement | Where it lands |
|-------------|----------------|
| FR-005, FR-006 | Chat memory keyed by `conversationId` |
| FR-007, FR-013 | `DELETE`, TTL, no persistence, fresh id in the client |
| FR-014 to FR-018 | `ChatErrorCode` table; pending-retry rule |
| FR-016 | Rate-limit bucket |
| FR-020 | `ChatRequest` validation, mirrored in the composer |
