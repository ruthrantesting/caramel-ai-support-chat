/**
 * The one helper every API call goes through (specs/002-user-auth, research A-5, A-18).
 *
 * - Sends cookies with each request (same origin).
 * - Reads the XSRF-TOKEN cookie at request time, never caches it, and sends it as the
 *   X-XSRF-TOKEN header on POST and DELETE. The session credential is HttpOnly and never
 *   touches script.
 * - On 403 for a state-changing request, refreshes the token once with GET /api/auth/me and
 *   retries once. Nothing has been streamed at that point, so the retry is safe.
 * - Reports 401 to the auth state through a callback, so the UI can react.
 */

const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';
const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

type UnauthorizedListener = () => void;
let unauthorizedListener: UnauthorizedListener | null = null;

/** Registers the callback that runs when any API call answers 401. Returns an unsubscribe function. */
export function onUnauthorized(listener: UnauthorizedListener): () => void {
  unauthorizedListener = listener;
  return () => {
    if (unauthorizedListener === listener) {
      unauthorizedListener = null;
    }
  };
}

/** The current CSRF token from its cookie, or undefined when the cookie is absent. */
export function readCsrfToken(): string | undefined {
  const prefix = `${CSRF_COOKIE}=`;
  for (const part of document.cookie.split(';')) {
    const cookie = part.trim();
    if (cookie.startsWith(prefix)) {
      return decodeURIComponent(cookie.slice(prefix.length));
    }
  }
  return undefined;
}

export async function apiFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const method = (init.method ?? 'GET').toUpperCase();
  const unsafe = UNSAFE_METHODS.has(method);

  let response = await send(input, init, unsafe);
  if (response.status === 403 && unsafe) {
    // The token may have changed (for example, after sign-in). Fetch a fresh one, then retry once.
    await fetch('/api/auth/me', { credentials: 'same-origin' });
    response = await send(input, init, unsafe);
  }
  if (response.status === 401) {
    unauthorizedListener?.();
  }
  return response;
}

function send(input: string, init: RequestInit, unsafe: boolean): Promise<Response> {
  const headers = new Headers(init.headers);
  if (unsafe) {
    const token = readCsrfToken();
    if (token) {
      headers.set(CSRF_HEADER, token);
    }
  }
  return fetch(input, { ...init, headers, credentials: 'same-origin' });
}
