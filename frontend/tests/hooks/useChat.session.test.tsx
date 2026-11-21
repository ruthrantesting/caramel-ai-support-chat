import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { streamChat } from '../../src/api/chatClient';
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
