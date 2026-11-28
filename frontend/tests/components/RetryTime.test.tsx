import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import * as authClient from '../../src/auth/authClient';
import { AuthProvider } from '../../src/auth/AuthProvider';
import { SignInForm } from '../../src/components/SignInForm';
import { SignUpForm } from '../../src/components/SignUpForm';

vi.mock('../../src/auth/authClient', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/auth/authClient')>()),
  login: vi.fn(),
  register: vi.fn(),
  me: vi.fn(() => Promise.reject(new Error('no session'))),
}));

const LIMITED = new authClient.AuthError({
  code: 'RATE_LIMITED',
  message: 'Too many attempts. Please wait and try again.',
  retryAfterSeconds: 42,
});

describe('rate-limit responses show a retry time (FR-030)', () => {
  it('the sign-in form shows how long to wait', async () => {
    const user = userEvent.setup();
    vi.mocked(authClient.login).mockRejectedValueOnce(LIMITED);
    render(
      <AuthProvider>
        <SignInForm />
      </AuthProvider>,
    );

    await user.type(screen.getByLabelText('Email'), 'ana@example.com');
    await user.type(screen.getByLabelText('Password'), 'correct-horse-battery-staple-42');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Try again in 42 seconds.');
  });

  it('the sign-up form shows how long to wait', async () => {
    const user = userEvent.setup();
    vi.mocked(authClient.register).mockRejectedValueOnce(LIMITED);
    render(
      <AuthProvider>
        <SignUpForm />
      </AuthProvider>,
    );

    await user.type(screen.getByLabelText('Email'), 'ana@example.com');
    await user.type(screen.getByLabelText('Password'), 'correct-horse-battery-staple-42');
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Try again in 42 seconds.'));
  });
});
