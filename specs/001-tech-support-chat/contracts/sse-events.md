# SSE Event Contract: `POST /api/chat`

**Plan**: [../plan.md](../plan.md) | **OpenAPI**: [chat-api.openapi.yaml](chat-api.openapi.yaml)

A successful `POST /api/chat` returns `200` with `Content-Type: text/event-stream`. The body is a
sequence of events. Errors detected before the stream starts use HTTP status codes instead (see the
OpenAPI file). Errors after the first byte arrive as an `error` event, because the status line has
already been sent.

## Event grammar

```text
stream     = *token ( done / error )
token      = "event: token\n"  "data: " JSON-string "\n\n"
done       = "event: done\n"   "data: {}\n\n"
error      = "event: error\n"  "data: " error-object "\n\n"
error-object = { "code": ChatErrorCode, "message": string }
```

Exactly one terminal event (`done` or `error`) ends every stream. After a terminal event the server
closes the connection. A stream that ends without a terminal event (a dropped connection) MUST be
treated by the client as an interruption.

## Events

### `token`

- `data` is a **JSON string**, for example `"Hello"` or `"line one\nline two"`.
- Parsing the JSON, rather than splitting on newlines, keeps newlines inside model output from
  breaking SSE framing.
- The client appends each decoded string to the assistant message, in arrival order.
- A chunk may be any length. It is not guaranteed to be a whole word.

### `done`

- `data` is `{}`. The reply is complete.

### `error`

- `data` is `{ "code": "...", "message": "..." }`. Codes are listed in
  [chat-api.openapi.yaml](chat-api.openapi.yaml).
- `message` is fixed, user-safe text suitable for direct display.
- Any `token` events already received stay visible and are marked incomplete (see
  [../data-model.md](../data-model.md)).

## Example

```text
event: token
data: "A 429 means "

event: token
data: "you hit a rate limit."

event: done
data: {}

```

Failure after partial output:

```text
event: token
data: "A 429 means "

event: error
data: {"code":"TIMEOUT","message":"The reply took too long. Please try again."}

```

## Client rules

1. Read the body with `fetch` and `response.body.getReader()`. `EventSource` cannot send a POST body.
2. Buffer partial chunks and split events on a blank line (`\n\n`). Accept `\r\n` as well.
3. Ignore unknown event names and SSE comment lines (those starting with `:`), so keep-alives and
   later additions are safe.
4. Do not retry automatically. Retry is a user action (FR-017).
5. Abort with `AbortController` on New chat and on unmount.

## Server rules

1. Send each model chunk as soon as it arrives, with no buffering, so the first token meets the
   2-second target.
2. Never include provider error text, stack traces, request payloads, or credentials in any event.
3. Enforce the 30-second first-token timeout and the 60-second overall deadline, and report either as `TIMEOUT`.
4. On any failure, restore the conversation's memory to its state before the turn.
