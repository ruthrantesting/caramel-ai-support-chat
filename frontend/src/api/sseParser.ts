import type { ChatErrorCode, ChatEvent } from '../types/chat';

const KNOWN_CODES: ReadonlySet<string> = new Set<ChatErrorCode>([
  'INVALID_REQUEST',
  'RATE_LIMITED',
  'CONVERSATION_BUSY',
  'PROVIDER_UNAVAILABLE',
  'PROVIDER_RATE_LIMITED',
  'TIMEOUT',
  'INTERNAL_ERROR',
]);

/**
 * Incremental parser for the chat SSE stream (contracts/sse-events.md).
 *
 * Feed it decoded text as it arrives. It returns the events that are complete so far. A partial
 * event stays buffered until its blank-line terminator arrives. Comment lines (starting with `:`),
 * unknown event names, and malformed token payloads are ignored, so keep-alives and future
 * additions are safe.
 */
export class SseParser {
  private buffer = '';

  push(chunk: string): ChatEvent[] {
    // Normalise CRLF after concatenation, so a CR at the end of one chunk pairs with the LF in the
    // next chunk.
    this.buffer = (this.buffer + chunk).replace(/\r\n/g, '\n');

    const events: ChatEvent[] = [];
    let boundary = this.buffer.indexOf('\n\n');
    while (boundary !== -1) {
      const raw = this.buffer.slice(0, boundary);
      this.buffer = this.buffer.slice(boundary + 2);
      const event = parseEvent(raw);
      if (event) {
        events.push(event);
      }
      boundary = this.buffer.indexOf('\n\n');
    }
    return events;
  }
}

function parseEvent(raw: string): ChatEvent | null {
  let name = 'message';
  const dataLines: string[] = [];

  for (const line of raw.split('\n')) {
    if (line === '' || line.startsWith(':')) {
      continue;
    }
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) {
      value = value.slice(1);
    }
    if (field === 'event') {
      name = value;
    } else if (field === 'data') {
      dataLines.push(value);
    }
  }

  const data = dataLines.join('\n');
  switch (name) {
    case 'token':
      return decodeToken(data);
    case 'done':
      return { type: 'done' };
    case 'error':
      return decodeError(data);
    default:
      return null;
  }
}

/** Token data is a JSON string, so embedded newlines arrive escaped and safe. */
function decodeToken(data: string): ChatEvent | null {
  try {
    const text: unknown = JSON.parse(data);
    return typeof text === 'string' ? { type: 'token', text } : null;
  } catch {
    return null;
  }
}

function decodeError(data: string): ChatEvent {
  try {
    const body = JSON.parse(data) as { code?: unknown; message?: unknown };
    const code =
      typeof body.code === 'string' && KNOWN_CODES.has(body.code)
        ? (body.code as ChatErrorCode)
        : 'INTERNAL_ERROR';
    const message = typeof body.message === 'string' ? body.message : '';
    return { type: 'error', code, message };
  } catch {
    return { type: 'error', code: 'INTERNAL_ERROR', message: '' };
  }
}
