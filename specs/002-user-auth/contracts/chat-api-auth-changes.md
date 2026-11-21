# Chat API Changes for Authentication

**Base contract**: [`specs/001-tech-support-chat/contracts/chat-api.openapi.yaml`](../../001-tech-support-chat/contracts/chat-api.openapi.yaml) and [`sse-events.md`](../../001-tech-support-chat/contracts/sse-events.md)

**Auth contract**: [auth-api.openapi.yaml](auth-api.openapi.yaml)

This file lists only what changes in the 001 chat contract. Anything not mentioned here is unchanged: the request body, the SSE events, the 4,000-character limit, the busy rule, and the provider error codes.

Constitution V requires that any contract change updates the PRD first. The first implementation step records these changes in `002-prd-user-auth.md` section 9 before any code changes.

## Both endpoints now require a signed-in user and a CSRF header

| Call | Before | After |
|------|--------|-------|
| `POST /api/chat` | Public | Needs the session cookie and an `X-XSRF-TOKEN` header |
| `DELETE /api/chat/{conversationId}` | Public | Needs the session cookie and an `X-XSRF-TOKEN` header |

## New and changed responses

| Status | Code | When | Applies to |
|--------|------|------|-----------|
| `401` | `UNAUTHENTICATED` | No session, or the session expired or was ended. Returned before any stream starts. | both |
| `403` | `CSRF_INVALID` | The CSRF header is missing or wrong. | both |
| `404` | `CONVERSATION_NOT_FOUND` | `POST`: the identifier belongs to another user. `DELETE`: the identifier belongs to another user, or is unknown. | both |
| `429` | `RATE_LIMITED` | **Changed**: the limit (20 per minute) now applies per signed-in user, not per client address. The body and `Retry-After` header are unchanged. | `POST` |

## Changed behavior

- **`DELETE` of an unknown identifier** returned `204` in 001. It now returns `404`, so a user cannot tell an unknown identifier from one that belongs to someone else. The "New chat" button ignores the result, so users see no difference. Owner deletes still return `204`.
- **Ownership** is set by the first accepted `POST`. See [data-model.md](../data-model.md), Conversation ownership binding.
- **Memory** is kept separately per user and identifier. A client cannot see this in the API.

## New error codes for the chat error enum

`ChatErrorCode` gains `UNAUTHENTICATED`, `CSRF_INVALID`, and `CONVERSATION_NOT_FOUND`. The frontend `ChatErrorCode` type and the fixed messages in `errorMessages.ts` gain the same three entries.

| Code | Fixed message |
|------|---------------|
| `UNAUTHENTICATED` | Your session has expired. Please sign in again. |
| `CSRF_INVALID` | Something went wrong with your request. Please try again. |
| `CONVERSATION_NOT_FOUND` | This conversation is no longer available. Please start a new chat. |

## Error body

Unchanged shape: `{ "code": "...", "message": "...", "retryAfterSeconds": 12 }`. The `401` and `403` bodies come from the security filter chain, so they are written by the entry point and access-denied handler, not by `GlobalExceptionHandler`. They use the same JSON shape. A `401` carries no `WWW-Authenticate` header.

## SSE

No change to events. The intended behavior is that a session that ends while a reply is streaming does not cut the stream, because the user was authenticated when the stream began, and the next request returns `401`. This is not yet verified, because streamed replies are dispatched asynchronously and the security filter also checks asynchronous dispatches (research A-14). A test must confirm it. If the test shows the stream is cut, the contract must say so instead.
