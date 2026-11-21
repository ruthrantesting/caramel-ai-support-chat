/** Error codes from contracts/chat-api.openapi.yaml. Keep in sync with the backend enum. */
export type ChatErrorCode =
  | 'INVALID_REQUEST'
  | 'RATE_LIMITED'
  | 'CONVERSATION_BUSY'
  | 'PROVIDER_UNAVAILABLE'
  | 'PROVIDER_RATE_LIMITED'
  | 'TIMEOUT'
  | 'INTERNAL_ERROR';

/** JSON body for failures that happen before a stream starts. */
export interface ApiError {
  code: ChatErrorCode;
  message: string;
  retryAfterSeconds?: number;
}

/** Events decoded from the SSE stream (contracts/sse-events.md). */
export type ChatEvent =
  | { type: 'token'; text: string }
  | { type: 'done' }
  | { type: 'error'; code: ChatErrorCode; message: string };

export type MessageRole = 'user' | 'assistant';

/**
 * Assistant replies move between these states (data-model.md). `failed` and `interrupted`
 * replies can be retried.
 */
export type MessageStatus = 'complete' | 'streaming' | 'interrupted' | 'failed';

export interface ChatFailure {
  code: ChatErrorCode;
  message: string;
  retryAfterSeconds?: number;
}

export interface ChatMessage {
  id: string;
  role: MessageRole;
  content: string;
  status: MessageStatus;
  error?: ChatFailure;
}
