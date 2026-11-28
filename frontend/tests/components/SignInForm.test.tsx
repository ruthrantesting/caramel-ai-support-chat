import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import * as authClient from '../../src/auth/authClient';
import { AuthProvider, useAuth } from '../../src/auth/AuthProvider';
import { SignInForm } from '../../src/components/SignInForm';

vi.mock('../../src/auth/authClient', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/auth/authClient')>()),
  login: vi.fn(),
  me: vi.fn(() => Promise.reject(new Error('no session'))),
}));

function StatusProbe() {
  const { status } = useAuth();
  return <p data-testid="status">{status}</p>;
}

function renderForm() {
  return render(
    <AuthProvider>
      <SignInForm />
      <StatusProbe />
    </AuthProvider>,
  );
}

const GENERIC = 'Email or password is incorrect';

describe('SignInForm', () => {
  it('has labeled fields with autocomplete values for a password manager', () => {
    renderForm();

    expect(screen.getByLabelText('Email')).toHaveAttribute('autocomplete', 'email');
    expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'current-password');
  });

  it('shows the generic message and stays signed out for a wrong password', async () => {
    const user = userEvent.setup();
    vi.mocked(authClient.login).mockRejectedValueOnce(
      new authClient.AuthError({ code: 'INVALID_CREDENTIALS', message: GENERIC }),
    );
    renderForm();

    await user.type(screen.getByLabelText('Email'), 'ana@example.com');
    await user.type(screen.getByLabelText('Password'), 'wrong-passphrase-here');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(GENERIC);
    expect(screen.getByTestId('status')).toHaveTextContent('signedOut');
  });

  it('shows the same generic message for an unregistered email', async () => {
    const user = userEvent.setup();
    vi.mocked(authClient.login).mockRejectedValueOnce(
      new authClient.AuthError({ code: 'INVALID_CREDENTIALS', message: GENERIC }),
    );
    renderForm();

    await user.type(screen.getByLabelText('Email'), 'nobody@example.com');
    await user.type(screen.getByLabelText('Password'), 'any-passphrase-here');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(GENERIC);
  });

  it('signs the user in on success', async () => {
    const user = userEvent.setup();
    vi.mocked(authClient.login).mockResolvedValueOnce({
      id: '00000000-0000-4000-8000-000000000002',
      email: 'ana@example.com',
    });
    renderForm();

    await user.type(screen.getByLabelText('Email'), 'ana@example.com');
    await user.type(screen.getByLabelText('Password'), 'correct-horse-battery-staple-42');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('signedIn'));
    expect(authClient.login).toHaveBeenCalledWith('ana@example.com', 'correct-horse-battery-staple-42');
  });
});
