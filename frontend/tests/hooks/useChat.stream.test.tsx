import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { streamChat } from '../../src/api/chatClient';
import { useChat } from '../../src/hooks/useChat';

vi.mock('../../src/api/chatClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api/chatClient')>();
  return { ...actual, streamChat: vi.fn() };
});

describe('useChat streaming', () => {
  beforeEach(() => {
    vi.mocked(streamChat).mockReset();
  });

  it('grows the assistant reply token by token and completes on done', async () => {
    let releaseSecondToken!: () => void;
    const gate = new Promise<void>((resolve) => {
      releaseSecondToken = resolve;
    });

    vi.mocked(streamChat).mockImplementation(async ({ onEvent }) => {
      onEvent({ type: 'token', text: 'Hel' });
      await gate;
      onEvent({ type: 'token', text: 'lo' });
      onEvent({ type: 'done' });
    });

    const { result } = renderHook(() => useChat());

    act(() => {
      void result.current.send('why 429?');
    });

    // The first token is visible while the stream is still open.
    await waitFor(() => {
      expect(result.current.messages[1]?.content).toBe('Hel');
    });
    expect(result.current.messages[1]?.status).toBe('streaming');
    expect(result.current.phase).toBe('streaming');

    await act(async () => {
      releaseSecondToken();
    });

    await waitFor(() => {
      expect(result.current.messages[1]?.content).toBe('Hello');
      expect(result.current.messages[1]?.status).toBe('complete');
    });
    expect(result.current.phase).toBe('idle');
  });

  it('keeps the user message and the order of the transcript', async () => {
    vi.mocked(streamChat).mockImplementation(async ({ onEvent }) => {
      onEvent({ type: 'token', text: 'ok' });
      onEvent({ type: 'done' });
    });

    const { result } = renderHook(() => useChat());
    await act(async () => {
      await result.current.send('  how do I list models?  ');
    });

    expect(result.current.messages.map((m) => m.role)).toEqual(['user', 'assistant']);
    expect(result.current.messages[0]?.content).toBe('how do I list models?');
  });

  it('does not send empty or over-long messages', async () => {
    vi.mocked(streamChat).mockResolvedValue(undefined);
    const { result } = renderHook(() => useChat());

    await act(async () => {
      await result.current.send('   ');
      await result.current.send('a'.repeat(4001));
    });

    expect(streamChat).not.toHaveBeenCalled();
    expect(result.current.messages).toHaveLength(0);
  });
});
