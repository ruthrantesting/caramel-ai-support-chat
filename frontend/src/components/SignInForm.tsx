import { type FormEvent, useState } from 'react';
import { AuthError, describeAuthError } from '../auth/authClient';
import { useAuth } from '../auth/AuthProvider';

/**
 * Sign-in form (FR-007, FR-008, FR-029). One generic message for every failure, so the form does
 * not reveal whether an address is registered. Labeled fields and autocomplete for password managers.
 */
export function SignInForm({
  onCreateAccount,
  onSignedIn,
}: {
  onCreateAccount?: () => void;
  onSignedIn?: () => void;
}) {
  const { login } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setFormError(null);
    try {
      await login(email, password);
      onSignedIn?.();
    } catch (error) {
      setFormError(error instanceof AuthError ? describeAuthError(error) : 'Something went wrong. Please try again.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="auth-form" onSubmit={(event) => void onSubmit(event)} noValidate aria-labelledby="sign-in-title">
      <h1 id="sign-in-title">Sign in</h1>

      <div className="auth-form__field">
        <label htmlFor="sign-in-email">Email</label>
        <input
          id="sign-in-email"
          type="email"
          autoComplete="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />
      </div>

      <div className="auth-form__field">
        <label htmlFor="sign-in-password">Password</label>
        <input
          id="sign-in-password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
      </div>

      {formError && (
        <p role="alert" className="auth-form__error">
          {formError}
        </p>
      )}

      <button type="submit" disabled={busy}>
        Sign in
      </button>
      {onCreateAccount && (
        <button type="button" className="auth-form__switch" onClick={onCreateAccount}>
          Create an account
        </button>
      )}
    </form>
  );
}
