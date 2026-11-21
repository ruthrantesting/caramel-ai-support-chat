import { useChat } from '../hooks/useChat';
import { Composer } from './Composer';
import { MessageList } from './MessageList';
import { NewChatButton } from './NewChatButton';

export function ChatWindow() {
  const { messages, phase, send, retry, reset } = useChat();
  const idle = phase === 'idle';

  return (
    <section className="chat-window" aria-label="Caramel AI tech support chat">
      <header className="chat-window__header">
        <h1>Caramel AI Tech Support</h1>
        <NewChatButton onClick={reset} />
      </header>
      <MessageList messages={messages} onRetry={idle ? (id) => void retry(id) : undefined} />
      <Composer disabled={!idle} onSend={send} />
    </section>
  );
}
