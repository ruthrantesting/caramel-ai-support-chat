import { useState } from 'react';
import { AuthProvider, useAuth } from './auth/AuthProvider';
import { ChatWindow } from './components/ChatWindow';
import { SignInForm } from './components/SignInForm';
import { SignUpForm } from './components/SignUpForm';

/** A visitor sees only the sign-in and sign-up screens; there is no anonymous chat (FR-031). */
export default function App() {
  return (
    <AuthProvider>
      <AppScreen />
    </AuthProvider>
  );
}

function AppScreen() {
  const { status, user } = useAuth();
  const [view, setView] = useState<'signIn' | 'signUp'>('signIn');

  if (status === 'loading') {
    return <p role="status">Checking your session…</p>;
  }
  if (status === 'signedIn' && user) {
    // Keyed by user, so a different user always starts with an empty transcript and conversation.
    return <ChatWindow key={user.id} />;
  }
  return view === 'signIn' ? (
    <SignInForm onCreateAccount={() => setView('signUp')} />
  ) : (
    <SignUpForm onHaveAccount={() => setView('signIn')} />
  );
}
