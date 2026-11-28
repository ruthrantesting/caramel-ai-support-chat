import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import * as authClient from '../../src/auth/authClient';
import { AuthProvider, useAuth } from '../../src/auth/AuthProvider';
import { SignUpForm } from '../../src/components/SignUpForm';

vi.mock('../../src/auth/authClient', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/auth/authClient')>()),
  register: vi.fn(),
  me: vi.fn(() => Promise.reject(new Error('no session'))),
}));

/** Shows the current auth status, so a test can see the transition to signed in. */
function StatusProbe() {
  const { status } = useAuth();
  return <p data-testid="status">{status}</p>;
}

function renderForm() {
  return render(
    <AuthProvider>
      <SignUpForm />
      <StatusProbe />
    </AuthProvider>,
  );
}

describe('SignUpForm', () => {
  it('has labeled email and password fields with autocomplete values for password managers', () => {
    renderForm();

    const email = screen.getByLabelText('Email');
    const password = screen.getByLabelText('Password');
    expect(email).toHaveAttribute('autocomplete', 'email');
    expect(password).toHaveAttribute('autocomplete', 'new-password');
  });

  it('shows a server email error next to the email field and moves focus there', async () => {
    const user = userEvent.setup();
    vi.mocked(authClient.register).mockRejectedValueOnce(
      new authClient.AuthError({
        code: 'VALIDATION_FAILED',
        message: 'Please correct the highlighted fields.',
        fieldErrors: [{ field: 'email', message: 'Enter a valid email address.' }],
      }),
    );
    renderForm();

    await user.type(screen.getByLabelText('Email'), 'not-an-address');
    await user.type(screen.getByLabelText('Password'), 'correct-horse-battery-staple-42');
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    const email = screen.getByLabelText('Email');
    await waitFor(() => expect(email).toHaveFocus());
    expect(email).toHaveAccessibleDescription('Enter a valid email address.');
    expect(screen.getByText('Enter a valid email address.')).toBeInTheDocument();
  });

  it('shows a password error next to the password field', async () => {
    const user = userEvent.setup();
    vi.mocked(authClient.register).mockRejectedValueOnce(
      new authClient.AuthError({
        code: 'VALIDATION_FAILED',
        message: 'Please correct the highlighted fields.',
        fieldErrors: [{ field: 'password', message: 'Password must be at least 12 characters.' }],
      }),
    );
    renderForm();

    await user.type(screen.getByLabelText('Email'), 'ana@example.com');
    await user.type(screen.getByLabelText('Password'), 'short');
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    const password = screen.getByLabelText('Password');
    await waitFor(() => expect(password).toHaveAccessibleDescription('Password must be at least 12 characters.'));
  });

  it('announces a neutral refusal for a registered address in a live region', async () => {
    const user = userEvent.setup();
    vi.mocked(authClient.register).mockRejectedValueOnce(
      new authClient.AuthError({
        code: 'EMAIL_UNAVAILABLE',
        message: 'We couldn’t create an account with this email. If you already have one, sign in or reset your password.',
      }),
    );
    renderForm();

    await user.type(screen.getByLabelText('Email'), 'ana@example.com');
    await user.type(screen.getByLabelText('Password'), 'correct-horse-battery-staple-42');
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('reset your password');
  });

  it('signs the user in on success without a page reload', async () => {
    const user = userEvent.setup();
    vi.mocked(authClient.register).mockResolvedValueOnce({
      id: '00000000-0000-4000-8000-000000000001',
      email: 'ana@example.com',
    });
    renderForm();

    await user.type(screen.getByLabelText('Email'), 'Ana@Example.com');
    await user.type(screen.getByLabelText('Password'), 'correct-horse-battery-staple-42');
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('signedIn'));
    expect(authClient.register).toHaveBeenCalledWith('Ana@Example.com', 'correct-horse-battery-staple-42');
  });
});
