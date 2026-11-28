import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatApiError, streamChat } from '../../src/api/chatClient';
import { useChat } from '../../src/hooks/useChat';

vi.mock('../../src/api/chatClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api/chatClient')>();
  return { ...actual, streamChat: vi.fn() };
});

describe('useChat session identity', () => {
  beforeEach(() => {
    vi.mocked(streamChat).mockReset();
    vi.mocked(streamChat).mockImplementation(async ({ onEvent }) => {
      onEvent({ type: 'token', text: 'ok' });
      onEvent({ type: 'done' });
    });
  });

  it('sends the same conversationId on every turn of one session', async () => {
    const { result } = renderHook(() => useChat());

    await act(async () => {
      await result.current.send('first question');
    });
    await act(async () => {
      await result.current.send('follow-up');
    });

    const ids = vi.mocked(streamChat).mock.calls.map(([args]) => args.conversationId);
    expect(ids).toHaveLength(2);
    expect(ids[0]).toBe(ids[1]);
    expect(ids[0]).toBe(result.current.conversationId);
  });

  it('gives a new hook instance (a new page session) a different conversationId', () => {
    const first = renderHook(() => useChat());
    const second = renderHook(() => useChat());

    expect(first.result.current.conversationId).not.toBe(second.result.current.conversationId);
    expect(first.result.current.conversationId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
    );
  });
});

describe('useChat session expiry (User Story 3)', () => {
  beforeEach(() => {
    vi.mocked(streamChat).mockReset();
  });

  it('keeps the transcript and the failed question when the session has expired', async () => {
    vi.mocked(streamChat).mockRejectedValueOnce(
      new ChatApiError({ code: 'UNAUTHENTICATED', message: 'Your session has expired. Please sign in again.' }),
    );
    const { result } = renderHook(() => useChat());

    await act(async () => {
      await result.current.send('my unsent question');
    });

    const [question, reply] = result.current.messages;
    expect(question).toMatchObject({ role: 'user', content: 'my unsent question' });
    expect(reply).toMatchObject({ role: 'assistant', status: 'failed', error: { code: 'UNAUTHENTICATED' } });
  });

  it('reports the unsent question to the session-expired handler', async () => {
    const onSessionExpired = vi.fn();
    vi.mocked(streamChat).mockRejectedValueOnce(
      new ChatApiError({ code: 'UNAUTHENTICATED', message: 'Your session has expired. Please sign in again.' }),
    );
    const { result } = renderHook(() => useChat({ onSessionExpired }));

    await act(async () => {
      await result.current.send('keep me');
    });

    expect(onSessionExpired).toHaveBeenCalledWith('keep me');
  });

  it('retry after signing in resends the same question without adding it twice', async () => {
    vi.mocked(streamChat)
      .mockRejectedValueOnce(
        new ChatApiError({ code: 'UNAUTHENTICATED', message: 'Your session has expired. Please sign in again.' }),
      )
      .mockImplementationOnce(async ({ onEvent }) => {
        onEvent({ type: 'token', text: 'answer' });
        onEvent({ type: 'done' });
      });
    const { result } = renderHook(() => useChat());

    await act(async () => {
      await result.current.send('retry me');
    });
    const failedReply = result.current.messages[1];
    await act(async () => {
      await result.current.retry(failedReply.id);
    });

    expect(vi.mocked(streamChat).mock.calls[1][0].message).toBe('retry me');
    expect(result.current.messages.filter((m) => m.role === 'user')).toHaveLength(1);
    expect(result.current.messages[1]).toMatchObject({ status: 'complete', content: 'answer' });
  });
});
