import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatApiError, streamChat } from '../../src/api/chatClient';
import { useChat } from '../../src/hooks/useChat';

vi.mock('../../src/api/chatClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api/chatClient')>();
  return { ...actual, streamChat: vi.fn(), clearConversation: vi.fn() };
});

describe('useChat failures and retry', () => {
  beforeEach(() => {
    vi.mocked(streamChat).mockReset();
  });

  it('marks a reply that timed out after some tokens as interrupted, keeping the partial text', async () => {
    vi.mocked(streamChat).mockImplementation(async ({ onEvent }) => {
      onEvent({ type: 'token', text: 'partial ' });
      onEvent({ type: 'error', code: 'TIMEOUT', message: 'The reply took too long. Please try again.' });
    });

    const { result } = renderHook(() => useChat());
    await act(async () => {
      await result.current.send('why is it slow?');
    });

    const reply = result.current.messages[1];
    expect(reply?.status).toBe('interrupted');
    expect(reply?.content).toBe('partial ');
    expect(reply?.error?.code).toBe('TIMEOUT');
  });

  it('marks a reply that failed before any token as failed', async () => {
    vi.mocked(streamChat).mockImplementation(async ({ onEvent }) => {
      onEvent({ type: 'error', code: 'PROVIDER_UNAVAILABLE', message: 'The assistant is temporarily unavailable.' });
    });

    const { result } = renderHook(() => useChat());
    await act(async () => {
      await result.current.send('hello');
    });

    expect(result.current.messages[1]?.status).toBe('failed');
    expect(result.current.messages[1]?.error?.code).toBe('PROVIDER_UNAVAILABLE');
  });

  it('keeps the retry-after wait from a pre-stream rate limit', async () => {
    vi.mocked(streamChat).mockRejectedValue(
      new ChatApiError({ code: 'RATE_LIMITED', message: 'Please wait a moment.', retryAfterSeconds: 5 }),
    );

    const { result } = renderHook(() => useChat());
    await act(async () => {
      await result.current.send('hello');
    });

    expect(result.current.messages[1]?.status).toBe('failed');
    expect(result.current.messages[1]?.error).toMatchObject({ code: 'RATE_LIMITED', retryAfterSeconds: 5 });
  });

  it('keeps the user message and resends the same text on retry, without duplicating it', async () => {
    vi.mocked(streamChat)
      .mockImplementationOnce(async ({ onEvent }) => {
        onEvent({ type: 'error', code: 'TIMEOUT', message: 'slow' });
      })
      .mockImplementationOnce(async ({ onEvent }) => {
        onEvent({ type: 'token', text: 'fine now' });
        onEvent({ type: 'done' });
      });

    const { result } = renderHook(() => useChat());
    await act(async () => {
      await result.current.send('the question');
    });
    const failedReplyId = result.current.messages[1]!.id;

    await act(async () => {
      await result.current.retry(failedReplyId);
    });

    expect(streamChat).toHaveBeenCalledTimes(2);
    expect(vi.mocked(streamChat).mock.calls[1]?.[0].message).toBe('the question');
    expect(result.current.messages.map((m) => m.role)).toEqual(['user', 'assistant']);
    expect(result.current.messages[0]?.content).toBe('the question');
    await waitFor(() => expect(result.current.messages[1]?.status).toBe('complete'));
    expect(result.current.messages[1]?.content).toBe('fine now');
  });

  it('does not retry a reply that is not in a failed or interrupted state', async () => {
    vi.mocked(streamChat).mockImplementation(async ({ onEvent }) => {
      onEvent({ type: 'token', text: 'ok' });
      onEvent({ type: 'done' });
    });

    const { result } = renderHook(() => useChat());
    await act(async () => {
      await result.current.send('hi');
    });
    await act(async () => {
      await result.current.retry(result.current.messages[1]!.id);
    });

    expect(streamChat).toHaveBeenCalledTimes(1);
  });
});
