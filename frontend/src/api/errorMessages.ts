import type { ChatErrorCode } from '../types/chat';

/**
 * Fixed, user-safe text for each error code. This copy mirrors `ChatErrorCode` in the backend.
 * It is used only when the backend text is unavailable, such as a network failure.
 */
export const FALLBACK_MESSAGES: Record<ChatErrorCode, string> = {
  INVALID_REQUEST:
    "Your message can't be sent. Check that it isn't empty and is 4,000 characters or fewer.",
  RATE_LIMITED: "You're sending messages too quickly. Please wait a moment and try again.",
  CONVERSATION_BUSY: 'A reply is still in progress. Please wait for it to finish.',
  PROVIDER_UNAVAILABLE: 'The assistant is temporarily unavailable. Please try again shortly.',
  PROVIDER_RATE_LIMITED: 'The assistant is busy right now. Please try again shortly.',
  TIMEOUT: 'The reply took too long. Please try again.',
  INTERNAL_ERROR: 'Something went wrong. Please try again.',
};
