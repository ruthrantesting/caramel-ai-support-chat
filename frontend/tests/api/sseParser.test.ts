import { describe, expect, it } from 'vitest';
import { SseParser } from '../../src/api/sseParser';

describe('SseParser', () => {
  it('reassembles an event split across chunk boundaries', () => {
    const parser = new SseParser();
    expect(parser.push('event: tok')).toEqual([]);
    expect(parser.push('en\ndata: "Hel"\n')).toEqual([]);
    expect(parser.push('\n')).toEqual([{ type: 'token', text: 'Hel' }]);
  });

  it('accepts CRLF line endings, including a CR split from its LF', () => {
    const parser = new SseParser();
    expect(parser.push('event: token\r\ndata: "a"\r\n\r')).toEqual([]);
    expect(parser.push('\n')).toEqual([{ type: 'token', text: 'a' }]);
  });

  it('ignores comment lines and keep-alives', () => {
    const parser = new SseParser();
    expect(parser.push(': keep-alive\n\nevent: done\ndata: {}\n\n')).toEqual([{ type: 'done' }]);
  });

  it('ignores unknown event names', () => {
    const parser = new SseParser();
    expect(parser.push('event: future-thing\ndata: {"x":1}\n\n')).toEqual([]);
  });

  it('decodes a token containing a newline round trip', () => {
    const parser = new SseParser();
    const events = parser.push('event: token\ndata: "line one\\nline two"\n\n');
    expect(events).toEqual([{ type: 'token', text: 'line one\nline two' }]);
  });

  it('decodes an error event with a known code and message', () => {
    const parser = new SseParser();
    const events = parser.push(
      'event: error\ndata: {"code":"TIMEOUT","message":"The reply took too long. Please try again."}\n\n',
    );
    expect(events).toEqual([
      { type: 'error', code: 'TIMEOUT', message: 'The reply took too long. Please try again.' },
    ]);
  });

  it('maps an error event with an unknown code to INTERNAL_ERROR', () => {
    const parser = new SseParser();
    const events = parser.push('event: error\ndata: {"code":"SOMETHING_NEW","message":"x"}\n\n');
    expect(events).toEqual([{ type: 'error', code: 'INTERNAL_ERROR', message: 'x' }]);
  });

  it('returns several events from a single chunk in order', () => {
    const parser = new SseParser();
    const events = parser.push('event: token\ndata: "a"\n\nevent: token\ndata: "b"\n\nevent: done\ndata: {}\n\n');
    expect(events).toEqual([
      { type: 'token', text: 'a' },
      { type: 'token', text: 'b' },
      { type: 'done' },
    ]);
  });

  it('drops a malformed token payload instead of throwing', () => {
    const parser = new SseParser();
    expect(parser.push('event: token\ndata: not-json\n\n')).toEqual([]);
  });
});
