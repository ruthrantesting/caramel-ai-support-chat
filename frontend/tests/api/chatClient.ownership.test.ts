import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearConversation, streamChat, ChatApiError } from '../../src/api/chatClient';
import { FALLBACK_MESSAGES } from '../../src/api/errorMessages';

const NOT_FOUND_MESSAGE = 'This conversation is no longer available. Please start a new chat.';

describe('chat client and conversation ownership (User Story 4)', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('reports a 404 CONVERSATION_NOT_FOUND from a foreign or unknown identifier with the fixed message', async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ code: 'CONVERSATION_NOT_FOUND', message: NOT_FOUND_MESSAGE }), {
        status: 404,
        headers: { 'Content-Type': 'application/json' },
      }),
    );

    const error = await streamChat({
      conversationId: '11111111-1111-4111-8111-111111111111',
      message: 'hi',
      onEvent: () => {},
    }).catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ChatApiError);
    expect((error as ChatApiError).code).toBe('CONVERSATION_NOT_FOUND');
    expect((error as ChatApiError).message).toBe(NOT_FOUND_MESSAGE);
  });

  it('maps a 404 with no JSON body to CONVERSATION_NOT_FOUND, not a generic error', async () => {
    fetchMock.mockResolvedValue(new Response('not found', { status: 404 }));

    const error = await streamChat({
      conversationId: '11111111-1111-4111-8111-111111111111',
      message: 'hi',
      onEvent: () => {},
    }).catch((caught: unknown) => caught);

    expect((error as ChatApiError).code).toBe('CONVERSATION_NOT_FOUND');
  });

  it('has a fixed message for CONVERSATION_NOT_FOUND that matches the contract', () => {
    expect(FALLBACK_MESSAGES.CONVERSATION_NOT_FOUND).toBe(NOT_FOUND_MESSAGE);
  });

  it('New chat deletes only the identifier it is given, and encodes it in the path', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await clearConversation('22222222-2222-4222-8222-222222222222');

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/chat/22222222-2222-4222-8222-222222222222');
    expect((init as RequestInit).method).toBe('DELETE');
  });
});
