import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import * as authClient from './authClient';
import type { AuthUser } from './authClient';
import { onUnauthorized } from './apiFetch';

/** loading until the session is known, then signedOut or signedIn (data-model, Frontend state). */
export type AuthStatus = 'loading' | 'signedOut' | 'signedIn';

export interface AuthState {
  status: AuthStatus;
  user: AuthUser | null;
  /** The user whose session expired while the chat was open, or null. Opens the sign-in dialog. */
  expiredFor: string | null;
  /** The question that was unsent when the session expired, for a different user to edit and send. */
  unsentDraft: string | null;
  register: (email: string, password: string) => Promise<void>;
  login: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
  reportSessionExpired: (unsent: string) => void;
  consumeUnsentDraft: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [user, setUser] = useState<AuthUser | null>(null);
  const [expiredFor, setExpiredFor] = useState<string | null>(null);
  const [unsentDraft, setUnsentDraft] = useState<string | null>(null);

  // Read by the 401 listener, which is registered once and must see the latest values.
  const statusRef = useRef(status);
  const userRef = useRef(user);
  const expiredRef = useRef(expiredFor);
  statusRef.current = status;
  userRef.current = user;
  expiredRef.current = expiredFor;

  const signedIn = useCallback((signedInUser: AuthUser) => {
    const previouslyExpiredFor = expiredRef.current;
    setUser(signedInUser);
    setStatus('signedIn');
    setExpiredFor(null);
    // The same user keeps the chat and retries from the transcript, so the draft is dropped. A
    // different user gets a fresh chat, so the draft is kept for it to edit and send.
    setUnsentDraft((draft) => (previouslyExpiredFor === signedInUser.id ? null : draft));
  }, []);

  const signedOut = useCallback(() => {
    setUser(null);
    setStatus('signedOut');
    setExpiredFor(null);
    setUnsentDraft(null);
  }, []);

  // Restore the session on load. Until this answers, the app shows only a busy indicator.
  useEffect(() => {
    authClient.me().then(signedIn).catch(signedOut);
  }, [signedIn, signedOut]);

  // Any 401 while signed in means the session ended. The sign-in dialog opens over the kept chat.
  useEffect(() => {
    return onUnauthorized(() => {
      if (statusRef.current === 'signedIn' && userRef.current) {
        setExpiredFor(userRef.current.id);
      }
    });
  }, []);

  const register = useCallback(
    async (email: string, password: string) => signedIn(await authClient.register(email, password)),
    [signedIn],
  );

  const login = useCallback(
    async (email: string, password: string) => signedIn(await authClient.login(email, password)),
    [signedIn],
  );

  const signOut = useCallback(async () => {
    try {
      await authClient.logout();
    } finally {
      // The chat is unmounted with the signed-in screen, so its transcript and conversation go too.
      signedOut();
    }
  }, [signedOut]);

  const reportSessionExpired = useCallback((unsent: string) => {
    setUnsentDraft(unsent);
    if (userRef.current) {
      setExpiredFor(userRef.current.id);
    }
  }, []);

  const consumeUnsentDraft = useCallback(() => setUnsentDraft(null), []);

  // A page restored from the back-forward cache can show the old chat. Re-check the session then,
  // so a signed-out or expired session is not shown (User Story 2, scenario 5).
  useEffect(() => {
    function onPageShow(event: Event) {
      if (!(event as Event & { persisted?: boolean }).persisted) {
        return;
      }
      authClient.me().then(signedIn).catch(signedOut);
    }
    window.addEventListener('pageshow', onPageShow);
    return () => window.removeEventListener('pageshow', onPageShow);
  }, [signedIn, signedOut]);

  const value = useMemo<AuthState>(
    () => ({
      status,
      user,
      expiredFor,
      unsentDraft,
      register,
      login,
      signOut,
      reportSessionExpired,
      consumeUnsentDraft,
    }),
    [status, user, expiredFor, unsentDraft, register, login, signOut, reportSessionExpired, consumeUnsentDraft],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

/** Reads the auth state. Must be used inside AuthProvider. */
export function useAuth(): AuthState {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return value;
}
