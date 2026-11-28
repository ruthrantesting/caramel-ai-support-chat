import { useCallback, useRef, useState } from 'react';
import { ChatApiError, clearConversation, streamChat } from '../api/chatClient';
import { FALLBACK_MESSAGES } from '../api/errorMessages';
import type { ChatFailure, ChatMessage } from '../types/chat';

/** Spec FR-020. The composer enforces the same limit; this guards the hook too. */
export const MAX_MESSAGE_CHARS = 4000;

export type ChatPhase = 'idle' | 'sending' | 'streaming';

/**
 * Owns one chat session: the transcript, the phase, and the conversation id.
 *
 * The conversation id is kept only in memory, so a page reload starts a new conversation
 * (data-model.md; no persistence, Constitution I). New chat replaces it.
 */
export interface UseChatOptions {
  /** Called with the unsent question when the session has expired (User Story 3, FR-015). */
  onSessionExpired?: (unsent: string) => void;
}

export function useChat(options: UseChatOptions = {}) {
  // crypto.randomUUID needs a secure context (https or localhost).
  const [conversationId, setConversationId] = useState<string>(() => crypto.randomUUID());
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [phase, setPhase] = useState<ChatPhase>('idle');
  // Holds the in-flight request. Its presence also blocks a second send or retry synchronously.
  const inFlight = useRef<AbortController | null>(null);

  const patch = useCallback((id: string, change: (m: ChatMessage) => ChatMessage) => {
    setMessages((prev) => prev.map((m) => (m.id === id ? change(m) : m)));
  }, []);

  /**
   * Streams the reply for `text` into the assistant message `assistantId`. Every outcome ends
   * with the reply marked complete, interrupted, or failed. The user's text is never discarded
   * (spec FR-017).
   */
  const runReply = useCallback(
    async (text: string, assistantId: string) => {
      const controller = new AbortController();
      inFlight.current = controller;
      setPhase('sending');
      let terminated = false;

      const fail = (failure: ChatFailure | undefined, aborted: boolean) => {
        patch(assistantId, (m) => ({
          ...m,
          // A reply that already has text is interrupted; one with no text has failed.
          status: aborted || m.content !== '' ? 'interrupted' : 'failed',
          error: aborted ? undefined : failure,
        }));
        if (!aborted && failure?.code === 'UNAUTHENTICATED') {
          // The question stays in the transcript for Retry; the handler also sees it for the sign-in step.
          options.onSessionExpired?.(text);
        }
      };

      try {
        await streamChat({
          conversationId,
          message: text,
          signal: controller.signal,
          onEvent: (event) => {
            if (event.type === 'token') {
              setPhase('streaming');
              patch(assistantId, (m) => ({ ...m, content: m.content + event.text }));
            } else if (event.type === 'done') {
              terminated = true;
              patch(assistantId, (m) => ({ ...m, status: 'complete' }));
            } else {
              terminated = true;
              fail({ code: event.code, message: event.message }, false);
            }
          },
        });
        // The body ended without a terminal event: treat it as an interruption (sse-events.md).
        if (!terminated) {
          fail({ code: 'INTERNAL_ERROR', message: FALLBACK_MESSAGES.INTERNAL_ERROR }, false);
        }
      } catch (err) {
        fail(controller.signal.aborted ? undefined : failureFrom(err), controller.signal.aborted);
      } finally {
        inFlight.current = null;
        setPhase('idle');
      }
    },
    [conversationId, patch, options],
  );

  const send = useCallback(
    async (raw: string) => {
      const text = raw.trim();
      if (text === '' || text.length > MAX_MESSAGE_CHARS || inFlight.current !== null) {
        return;
      }

      const assistantId = crypto.randomUUID();
      setMessages((prev) => [
        ...prev,
        { id: crypto.randomUUID(), role: 'user', content: text, status: 'complete' },
        { id: assistantId, role: 'assistant', content: '', status: 'streaming' },
      ]);
      await runReply(text, assistantId);
    },
    [runReply],
  );

  /**
   * Retries a failed or interrupted reply by resending the question above it. The question is not
   * added again, and the reply starts empty. Because the server restores its memory after a
   * failure, the retry does not duplicate the turn (research R-11).
   */
  const retry = useCallback(
    async (assistantId: string) => {
      if (inFlight.current !== null) {
        return;
      }
      const index = messages.findIndex((m) => m.id === assistantId);
      const reply = messages[index];
      const question = index > 0 ? messages[index - 1] : undefined;
      const retryable = reply?.role === 'assistant' && (reply.status === 'failed' || reply.status === 'interrupted');
      if (!retryable || !question || question.role !== 'user') {
        return;
      }

      patch(assistantId, (m) => ({ ...m, content: '', status: 'streaming', error: undefined }));
      await runReply(question.content, assistantId);
    },
    [messages, patch, runReply],
  );

  /**
   * New chat (spec FR-013): stops any reply in progress, clears the server memory for the old
   * conversation (failures are ignored; the server also expires idle conversations), and starts a
   * fresh conversation with an empty transcript.
   */
  const reset = useCallback(() => {
    inFlight.current?.abort();
    void clearConversation(conversationId).catch(() => undefined);
    setConversationId(crypto.randomUUID());
    setMessages([]);
    setPhase('idle');
  }, [conversationId]);

  return { conversationId, messages, phase, send, retry, reset };
}

function failureFrom(err: unknown): ChatFailure {
  if (err instanceof ChatApiError) {
    return { code: err.code, message: err.message, retryAfterSeconds: err.retryAfterSeconds };
  }
  return { code: 'PROVIDER_UNAVAILABLE', message: FALLBACK_MESSAGES.PROVIDER_UNAVAILABLE };
}
