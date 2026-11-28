import { render, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import * as authClient from '../../src/auth/authClient';
import { AuthProvider } from '../../src/auth/AuthProvider';
import { SessionExpiredDialog } from '../../src/components/SessionExpiredDialog';

vi.mock('../../src/auth/authClient', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../src/auth/authClient')>()),
  me: vi.fn().mockRejectedValue(new Error('no session')),
  login: vi.fn(),
}));

function renderDialog(onClose = () => {}) {
  return render(
    <AuthProvider>
      <button type="button">Open</button>
      <SessionExpiredDialog onClose={onClose} />
    </AuthProvider>,
  );
}

describe('SessionExpiredDialog', () => {
  it('is a modal dialog that says the session expired and offers sign-in', () => {
    renderDialog();

    const dialog = screen.getByRole('dialog', { name: /session has expired/i });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByLabelText('Email')).toBeInTheDocument();
  });

  it('moves focus into the dialog when it opens', () => {
    renderDialog();

    expect(screen.getByLabelText('Email')).toHaveFocus();
  });

  it('keeps Tab focus inside the dialog', async () => {
    const user = userEvent.setup();
    renderDialog();

    // Tab through every focusable element twice; focus must never leave the dialog.
    for (let i = 0; i < 12; i += 1) {
      await user.tab();
      expect(screen.getByRole('dialog').contains(document.activeElement)).toBe(true);
    }
  });

  it('returns focus to the element that was focused before it opened, when it closes', async () => {
    const user = userEvent.setup();
    vi.mocked(authClient.login).mockResolvedValueOnce({
      id: '00000000-0000-4000-8000-000000000005',
      email: 'ana@example.com',
    });
    function Page() {
      const [open, setOpen] = useState(false);
      return (
        <AuthProvider>
          <button type="button" onClick={() => setOpen(true)}>
            Open
          </button>
          {open && <SessionExpiredDialog onClose={() => setOpen(false)} />}
        </AuthProvider>
      );
    }
    render(<Page />);
    const trigger = screen.getByRole('button', { name: 'Open' });
    trigger.focus();
    await user.click(trigger);
    expect(screen.getByLabelText('Email')).toHaveFocus();

    await user.type(screen.getByLabelText('Email'), 'ana@example.com');
    await user.type(screen.getByLabelText('Password'), 'correct-horse-battery-staple-42');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
  });
});
