import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { clearConversation, streamChat } from '../../src/api/chatClient';
import { useChat } from '../../src/hooks/useChat';

vi.mock('../../src/api/chatClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api/chatClient')>();
  return { ...actual, streamChat: vi.fn(), clearConversation: vi.fn() };
});

describe('useChat.reset (New chat)', () => {
  beforeEach(() => {
    vi.mocked(streamChat).mockReset();
    vi.mocked(clearConversation).mockReset();
    vi.mocked(clearConversation).mockResolvedValue(undefined);
    vi.mocked(streamChat).mockImplementation(async ({ onEvent }) => {
      onEvent({ type: 'token', text: 'an answer' });
      onEvent({ type: 'done' });
    });
  });

  it('clears the transcript, deletes the old conversation, and starts a new id', async () => {
    const { result } = renderHook(() => useChat());
    await act(async () => {
      await result.current.send('first question');
    });
    const oldId = result.current.conversationId;
    expect(result.current.messages).toHaveLength(2);

    act(() => {
      result.current.reset();
    });

    expect(result.current.messages).toHaveLength(0);
    expect(result.current.conversationId).not.toBe(oldId);
    expect(clearConversation).toHaveBeenCalledWith(oldId);
  });

  it('still resets when the server call fails', async () => {
    vi.mocked(clearConversation).mockRejectedValue(new Error('network down'));
    const { result } = renderHook(() => useChat());
    await act(async () => {
      await result.current.send('hello');
    });

    act(() => {
      result.current.reset();
    });

    expect(result.current.messages).toHaveLength(0);
    expect(result.current.phase).toBe('idle');
  });

  it('stops a reply that is still streaming', async () => {
    let capturedSignal: AbortSignal | undefined;
    vi.mocked(streamChat).mockImplementation(({ signal, onEvent }) => {
      capturedSignal = signal;
      onEvent({ type: 'token', text: 'partial' });
      return new Promise<void>((_resolve, reject) => {
        signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      });
    });

    const { result } = renderHook(() => useChat());
    act(() => {
      void result.current.send('long question');
    });
    await waitFor(() => expect(result.current.phase).toBe('streaming'));

    act(() => {
      result.current.reset();
    });

    expect(capturedSignal?.aborted).toBe(true);
    await waitFor(() => expect(result.current.phase).toBe('idle'));
    expect(result.current.messages).toHaveLength(0);
  });
});
