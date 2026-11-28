import { type FormEvent, useRef, useState } from 'react';
import { AuthError, describeAuthError } from '../auth/authClient';
import { useAuth } from '../auth/AuthProvider';

type FieldErrors = { email?: string; password?: string };

/**
 * Sign-up form (FR-001, FR-009, FR-029, FR-030). Labeled fields, autocomplete values for password
 * managers, and field errors next to their fields. Focus moves to the first invalid field.
 * The server decides validity; the form only shows what it returns.
 */
export function SignUpForm({ onHaveAccount }: { onHaveAccount?: () => void }) {
  const { register } = useAuth();
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setFieldErrors({});
    setFormError(null);
    try {
      await register(email, password);
    } catch (error) {
      if (error instanceof AuthError && error.fieldErrors.length > 0) {
        const next: FieldErrors = {};
        for (const fieldError of error.fieldErrors) {
          if (fieldError.field === 'email' || fieldError.field === 'password') {
            next[fieldError.field] = fieldError.message;
          }
        }
        setFieldErrors(next);
        if (next.email) {
          emailRef.current?.focus();
        } else if (next.password) {
          passwordRef.current?.focus();
        }
      } else {
        setFormError(error instanceof AuthError ? describeAuthError(error) : 'Something went wrong. Please try again.');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="auth-form" onSubmit={(event) => void onSubmit(event)} noValidate aria-labelledby="sign-up-title">
      <h1 id="sign-up-title">Create your account</h1>

      <div className="auth-form__field">
        <label htmlFor="sign-up-email">Email</label>
        <input
          id="sign-up-email"
          ref={emailRef}
          type="email"
          autoComplete="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          aria-invalid={fieldErrors.email ? true : undefined}
          aria-describedby={fieldErrors.email ? 'sign-up-email-error' : undefined}
        />
        {fieldErrors.email && (
          <p id="sign-up-email-error" className="field-error">
            {fieldErrors.email}
          </p>
        )}
      </div>

      <div className="auth-form__field">
        <label htmlFor="sign-up-password">Password</label>
        <input
          id="sign-up-password"
          ref={passwordRef}
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          aria-invalid={fieldErrors.password ? true : undefined}
          aria-describedby={fieldErrors.password ? 'sign-up-password-error' : undefined}
        />
        {fieldErrors.password && (
          <p id="sign-up-password-error" className="field-error">
            {fieldErrors.password}
          </p>
        )}
      </div>

      {formError && (
        <p role="alert" className="auth-form__error">
          {formError}
        </p>
      )}

      <button type="submit" disabled={busy}>
        Create account
      </button>
      {onHaveAccount && (
        <button type="button" className="auth-form__switch" onClick={onHaveAccount}>
          I already have an account
        </button>
      )}
    </form>
  );
}
