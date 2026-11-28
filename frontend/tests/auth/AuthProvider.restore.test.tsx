import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from '../../src/App';
import * as authClient from '../../src/auth/authClient';

vi.mock('../../src/auth/authClient', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/auth/authClient')>()),
  me: vi.fn(),
}));

const SIGNED_IN = { id: '00000000-0000-4000-8000-000000000004', email: 'ana@example.com' };

describe('restoring the session on load (User Story 3)', () => {
  beforeEach(() => {
    vi.mocked(authClient.me).mockReset();
  });

  afterEach(() => {
    vi.mocked(authClient.me).mockReset();
  });

  it('shows only a busy indicator while the session is unknown, and no sign-in form', () => {
    vi.mocked(authClient.me).mockReturnValue(new Promise(() => {}));

    render(<App />);

    expect(screen.getByRole('status')).toHaveTextContent(/checking your session/i);
    expect(screen.queryByLabelText('Email')).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Caramel AI tech support chat' })).not.toBeInTheDocument();
  });

  it('opens the chat without asking to sign in again when the session is still valid', async () => {
    vi.mocked(authClient.me).mockResolvedValue(SIGNED_IN);

    render(<App />);

    expect(await screen.findByRole('region', { name: 'Caramel AI tech support chat' })).toBeInTheDocument();
    expect(screen.getByText(SIGNED_IN.email)).toBeInTheDocument();
  });

  it('shows the sign-in form when there is no session', async () => {
    vi.mocked(authClient.me).mockRejectedValue(
      new authClient.AuthError({ code: 'UNAUTHENTICATED', message: 'Please sign in to continue.' }),
    );

    render(<App />);

    await waitFor(() => expect(screen.getByLabelText('Email')).toBeInTheDocument());
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
