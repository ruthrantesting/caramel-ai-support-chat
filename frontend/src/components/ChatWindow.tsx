import { useEffect, useRef } from 'react';
import { useAuth } from '../auth/AuthProvider';
import { useChat } from '../hooks/useChat';
import { Composer } from './Composer';
import { MessageList } from './MessageList';
import { NewChatButton } from './NewChatButton';
import { SessionExpiredDialog } from './SessionExpiredDialog';
import { UserBar } from './UserBar';

export function ChatWindow() {
  const auth = useAuth();
  const { messages, phase, send, retry, reset } = useChat({ onSessionExpired: auth.reportSessionExpired });
  const idle = phase === 'idle';

  // The draft is taken once, when this chat mounts for a different user. Then it is consumed.
  const draft = useRef(auth.unsentDraft);
  const { consumeUnsentDraft } = auth;
  useEffect(() => {
    consumeUnsentDraft();
  }, [consumeUnsentDraft]);

  return (
    <section className="chat-window" aria-label="Caramel AI tech support chat">
      <header className="chat-window__header">
        <h1>Caramel AI Tech Support</h1>
        <UserBar />
        <NewChatButton onClick={reset} />
      </header>
      <MessageList messages={messages} onRetry={idle ? (id) => void retry(id) : undefined} />
      <Composer disabled={!idle} onSend={send} initialText={draft.current ?? undefined} />
      {auth.expiredFor !== null && <SessionExpiredDialog />}
    </section>
  );
}
