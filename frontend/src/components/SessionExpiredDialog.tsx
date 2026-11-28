import { type KeyboardEvent, useEffect, useRef } from 'react';
import { SignInForm } from './SignInForm';

const FOCUSABLE = 'input, button, textarea, select, a[href], [tabindex]:not([tabindex="-1"])';

/**
 * A modal sign-in dialog over the kept chat, shown when the session expires (User Story 3, A-18).
 * Focus moves in when it opens, stays inside while it is open (Tab wraps), and returns to the
 * element that had focus when it closes (FR-029).
 */
export function SessionExpiredDialog({ onClose }: { onClose?: () => void }) {
  const dialogRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const returnFocusTo = document.activeElement as HTMLElement | null;
    dialogRef.current?.querySelector<HTMLElement>(FOCUSABLE)?.focus();
    return () => {
      returnFocusTo?.focus();
    };
  }, []);

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const dialog = dialogRef.current;
    if (event.key !== 'Tab' || !dialog) {
      return;
    }
    const items = Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (items.length === 0) {
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement;
    const outside = !dialog.contains(active);
    if (event.shiftKey && (active === first || outside)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && (active === last || outside)) {
      event.preventDefault();
      first.focus();
    }
  }

  return (
    <div className="session-dialog-backdrop">
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="session-expired-title"
        className="session-dialog"
        onKeyDown={onKeyDown}
      >
        <h2 id="session-expired-title">Your session has expired</h2>
        <p>Sign in again to keep chatting. Your conversation and the message you were sending are still here.</p>
        <SignInForm onSignedIn={onClose} />
      </div>
    </div>
  );
}
