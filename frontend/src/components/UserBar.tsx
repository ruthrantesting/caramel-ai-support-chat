import { useState } from 'react';
import { useAuth } from '../auth/AuthProvider';

/** Shows the signed-in email and the Sign out action (PRD section 10). */
export function UserBar() {
  const { user, signOut } = useAuth();
  const [busy, setBusy] = useState(false);

  async function onSignOut() {
    setBusy(true);
    try {
      await signOut();
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="user-bar">
      <span className="user-bar__email">{user?.email}</span>
      <button type="button" onClick={() => void onSignOut()} disabled={busy}>
        Sign out
      </button>
    </div>
  );
}
