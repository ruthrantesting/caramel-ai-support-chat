import type { ApiError, ChatErrorCode, ChatEvent } from '../types/chat';
import { apiFetch } from '../auth/apiFetch';
import { FALLBACK_MESSAGES } from './errorMessages';
import { SseParser } from './sseParser';

/**
 * A failure reported before the stream started, as an HTTP error with an {@link ApiError} body
 * (for example 400, 409, 429, or 503).
 */
export class ChatApiError extends Error {
  readonly code: ChatErrorCode;
  readonly retryAfterSeconds?: number;

  constructor(apiError: ApiError) {
    super(apiError.message);
    this.name = 'ChatApiError';
    this.code = apiError.code;
    this.retryAfterSeconds = apiError.retryAfterSeconds;
  }
}

export interface StreamChatArgs {
  conversationId: string;
  message: string;
  signal?: AbortSignal;
  onEvent: (event: ChatEvent) => void;
}

/**
 * Sends one message and streams the reply (contracts/chat-api.openapi.yaml).
 *
 * Uses fetch rather than EventSource, because EventSource can only send GET requests
 * (research R-13). Resolves when the body ends. It does not retry: retry is a user action
 * (spec FR-017).
 */
export async function streamChat({ conversationId, message, signal, onEvent }: StreamChatArgs): Promise<void> {
  const response = await apiFetch('/api/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify({ conversationId, message }),
    signal,
  });

  if (!response.ok) {
    throw new ChatApiError(await readApiError(response));
  }
  if (!response.body) {
    throw new ChatApiError(fallback('PROVIDER_UNAVAILABLE'));
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const parser = new SseParser();

  for (;;) {
    const { value, done } = await reader.read();
    if (done) {
      break;
    }
    for (const event of parser.push(decoder.decode(value, { stream: true }))) {
      onEvent(event);
    }
  }
  const tail = decoder.decode();
  if (tail) {
    for (const event of parser.push(tail)) {
      onEvent(event);
    }
  }
}

/** Removes the conversation's memory on the server (New chat, FR-013). Callers ignore failures. */
export async function clearConversation(conversationId: string): Promise<void> {
  await apiFetch(`/api/chat/${encodeURIComponent(conversationId)}`, { method: 'DELETE' });
}

async function readApiError(response: Response): Promise<ApiError> {
  try {
    const body = (await response.json()) as Partial<ApiError>;
    if (typeof body.code === 'string' && typeof body.message === 'string') {
      return {
        code: body.code as ChatErrorCode,
        message: body.message,
        retryAfterSeconds: body.retryAfterSeconds,
      };
    }
  } catch {
    // Not JSON: fall through to the fixed message for the status.
  }
  if (response.status === 401) {
    return fallback('UNAUTHENTICATED');
  }
  if (response.status === 404) {
    // Only the chat endpoints return 404, and only for an identifier this user does not own.
    return fallback('CONVERSATION_NOT_FOUND');
  }
  return fallback(response.status === 429 ? 'RATE_LIMITED' : 'INTERNAL_ERROR');
}

function fallback(code: ChatErrorCode): ApiError {
  return { code, message: FALLBACK_MESSAGES[code] };
}
