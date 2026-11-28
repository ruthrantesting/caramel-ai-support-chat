import { apiFetch } from './apiFetch';

/** The signed-in user, as returned by the auth API (contracts/auth-api.openapi.yaml, schema Me). */
export interface AuthUser {
  id: string;
  email: string;
}

export interface AuthFieldError {
  field: string;
  message: string;
}

/** Body of an auth error response (schema AuthApiError). `message` is fixed, user-safe text. */
export interface AuthErrorBody {
  code: string;
  message: string;
  retryAfterSeconds?: number;
  fieldErrors?: AuthFieldError[];
}

/** A failed auth call. Carries the server's code, message, and per-field errors. */
export class AuthError extends Error {
  readonly code: string;
  readonly retryAfterSeconds?: number;
  readonly fieldErrors: AuthFieldError[];

  constructor(body: AuthErrorBody) {
    super(body.message);
    this.name = 'AuthError';
    this.code = body.code;
    this.retryAfterSeconds = body.retryAfterSeconds;
    this.fieldErrors = body.fieldErrors ?? [];
  }
}

/** Creates an account and signs the user in (FR-001). Field problems come back as AuthError.fieldErrors. */
export async function register(email: string, password: string): Promise<AuthUser> {
  const response = await apiFetch('/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  if (!response.ok) {
    throw new AuthError(await readAuthError(response));
  }
  return (await response.json()) as AuthUser;
}

/**
 * The message to show for a failed auth call. A rate-limit response also says how long to wait
 * (FR-030), so the user knows when to try again.
 */
export function describeAuthError(error: AuthError): string {
  if (error.code === 'RATE_LIMITED' && error.retryAfterSeconds) {
    return `${error.message} Try again in ${error.retryAfterSeconds} seconds.`;
  }
  return error.message;
}

async function readAuthError(response: Response): Promise<AuthErrorBody> {
  try {
    const body = (await response.json()) as Partial<AuthErrorBody>;
    if (typeof body.code === 'string' && typeof body.message === 'string') {
      return body as AuthErrorBody;
    }
  } catch {
    // Not JSON: use the fixed fallback below.
  }
  return {
    code: response.status === 429 ? 'RATE_LIMITED' : 'INTERNAL_ERROR',
    message: 'Something went wrong. Please try again.',
  };
}

/** Checks the credentials and starts a session (FR-007). Every failure is the same generic AuthError (FR-008). */
export async function login(email: string, password: string): Promise<AuthUser> {
  const response = await apiFetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  if (!response.ok) {
    throw new AuthError(await readAuthError(response));
  }
  return (await response.json()) as AuthUser;
}

/** Ends the session on the server (FR-012). */
export async function logout(): Promise<void> {
  const response = await apiFetch('/api/auth/logout', { method: 'POST' });
  if (!response.ok) {
    throw new AuthError(await readAuthError(response));
  }
}

/** The signed-in user, or an AuthError with code UNAUTHENTICATED. Used to restore state after a reload. */
export async function me(): Promise<AuthUser> {
  const response = await apiFetch('/api/auth/me');
  if (!response.ok) {
    throw new AuthError(await readAuthError(response));
  }
  return (await response.json()) as AuthUser;
}
