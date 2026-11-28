import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiFetch, onUnauthorized, readCsrfToken } from '../../src/auth/apiFetch';

function setCookie(value: string) {
  document.cookie = `XSRF-TOKEN=${value}; path=/`;
}

function clearCookie() {
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
}

function header(call: unknown[], name: string): string | null {
  const init = call[1] as RequestInit;
  return new Headers(init.headers).get(name);
}

describe('apiFetch', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    clearCookie();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    clearCookie();
    onUnauthorized(() => {});
  });

  it('sends the XSRF-TOKEN cookie value as X-XSRF-TOKEN on POST and DELETE', async () => {
    setCookie('token-abc');
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await apiFetch('/api/chat', { method: 'POST' });
    await apiFetch('/api/chat/1', { method: 'DELETE' });

    expect(header(fetchMock.mock.calls[0], 'X-XSRF-TOKEN')).toBe('token-abc');
    expect(header(fetchMock.mock.calls[1], 'X-XSRF-TOKEN')).toBe('token-abc');
  });

  it('does not send the CSRF header on GET', async () => {
    setCookie('token-abc');
    fetchMock.mockResolvedValue(new Response(null, { status: 200 }));

    await apiFetch('/api/auth/me');

    expect(header(fetchMock.mock.calls[0], 'X-XSRF-TOKEN')).toBeNull();
  });

  it('always sends cookies with the same-origin policy', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 200 }));

    await apiFetch('/api/auth/me');

    expect((fetchMock.mock.calls[0][1] as RequestInit).credentials).toBe('same-origin');
  });

  it('reads the token at request time, so a changed cookie is used on the next call', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    setCookie('first');
    await apiFetch('/api/chat', { method: 'POST' });
    setCookie('second');
    await apiFetch('/api/chat', { method: 'POST' });

    expect(header(fetchMock.mock.calls[0], 'X-XSRF-TOKEN')).toBe('first');
    expect(header(fetchMock.mock.calls[1], 'X-XSRF-TOKEN')).toBe('second');
  });

  it('on 403, refreshes the token with GET /api/auth/me and retries once', async () => {
    setCookie('old');
    fetchMock
      .mockResolvedValueOnce(new Response(null, { status: 403 }))
      .mockImplementationOnce(async () => {
        setCookie('new'); // the refresh call rotates the cookie
        return new Response(null, { status: 200 });
      })
      .mockResolvedValueOnce(new Response(null, { status: 204 }));

    const response = await apiFetch('/api/chat', { method: 'POST' });

    expect(response.status).toBe(204);
    expect(fetchMock.mock.calls[1][0]).toBe('/api/auth/me');
    expect(header(fetchMock.mock.calls[2], 'X-XSRF-TOKEN')).toBe('new');
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('retries a 403 only once and then returns the second 403', async () => {
    setCookie('token');
    fetchMock.mockResolvedValue(new Response(null, { status: 403 }));

    const response = await apiFetch('/api/chat', { method: 'POST' });

    expect(response.status).toBe(403);
    // POST, me, POST: no third attempt.
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('reports a 401 to the registered listener and returns the response', async () => {
    const listener = vi.fn();
    onUnauthorized(listener);
    fetchMock.mockResolvedValue(new Response(null, { status: 401 }));

    const response = await apiFetch('/api/chat', { method: 'POST' });

    expect(response.status).toBe(401);
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('does not report a 200 to the 401 listener', async () => {
    const listener = vi.fn();
    onUnauthorized(listener);
    fetchMock.mockResolvedValue(new Response(null, { status: 200 }));

    await apiFetch('/api/auth/me');

    expect(listener).not.toHaveBeenCalled();
  });

  it('reads no token when the cookie is absent', () => {
    expect(readCsrfToken()).toBeUndefined();
  });

  it('never touches localStorage or sessionStorage', async () => {
    const local = vi.spyOn(Storage.prototype, 'setItem');
    const session = vi.spyOn(Storage.prototype, 'getItem');
    setCookie('token');
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await apiFetch('/api/chat', { method: 'POST' });

    expect(local).not.toHaveBeenCalled();
    expect(session).not.toHaveBeenCalled();
    local.mockRestore();
    session.mockRestore();
  });
});
