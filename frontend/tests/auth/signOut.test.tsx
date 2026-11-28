import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import App from '../../src/App';
import * as authClient from '../../src/auth/authClient';

vi.mock('../../src/auth/authClient', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/auth/authClient')>()),
  login: vi.fn(),
  logout: vi.fn(),
  me: vi.fn(() => Promise.reject(new Error('no session'))),
}));

const CHAT_LABEL = 'Caramel AI tech support chat';

async function signIn(user: ReturnType<typeof userEvent.setup>) {
  vi.mocked(authClient.login).mockResolvedValueOnce({
    id: '00000000-0000-4000-8000-000000000003',
    email: 'ana@example.com',
  });
  // The app starts in the loading state, so wait for the sign-in form before typing.
  await user.type(await screen.findByLabelText('Email'), 'ana@example.com');
  await user.type(screen.getByLabelText('Password'), 'correct-horse-battery-staple-42');
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
  await screen.findByRole('region', { name: CHAT_LABEL });
}

describe('sign-out and back navigation', () => {
  it('replaces the chat with the sign-in screen and leaves no chat content behind', async () => {
    const user = userEvent.setup();
    vi.mocked(authClient.logout).mockResolvedValueOnce(undefined);
    render(<App />);
    await signIn(user);

    await user.click(screen.getByRole('button', { name: 'Sign out' }));

    await waitFor(() => expect(screen.queryByRole('region', { name: CHAT_LABEL })).not.toBeInTheDocument());
    expect(screen.getByLabelText('Email')).toBeInTheDocument();
    expect(authClient.logout).toHaveBeenCalledTimes(1);
  });

  it('re-checks the session when the page comes back from the back-forward cache', async () => {
    const user = userEvent.setup();
    vi.mocked(authClient.me).mockRejectedValueOnce(
      new authClient.AuthError({ code: 'UNAUTHENTICATED', message: 'Please sign in to continue.' }),
    );
    render(<App />);
    await signIn(user);

    // A page restored from the back-forward cache comes back with its old DOM. Simulate that.
    const restored = new Event('pageshow');
    Object.defineProperty(restored, 'persisted', { value: true });
    await act(async () => {
      window.dispatchEvent(restored);
    });

    await waitFor(() => expect(screen.queryByRole('region', { name: CHAT_LABEL })).not.toBeInTheDocument());
    expect(authClient.me).toHaveBeenCalled();
    expect(screen.getByLabelText('Email')).toBeInTheDocument();
  });
});
