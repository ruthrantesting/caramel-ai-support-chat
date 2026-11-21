import type { ChatMessage } from '../types/chat';
import { ErrorBanner } from './ErrorBanner';

interface Props {
  message: ChatMessage;
  /** Present only when a retry is allowed now (no reply is in progress). */
  onRetry?: (messageId: string) => void;
}

const LABELS: Record<ChatMessage['role'], string> = {
  user: 'You',
  assistant: 'Caramel AI support',
};

export function MessageBubble({ message, onRetry }: Props) {
  const isStopped = message.status === 'failed' || message.status === 'interrupted';

  return (
    <article className={`bubble bubble--${message.role}`} data-status={message.status}>
      <header className="bubble__label">{LABELS[message.role]}</header>
      {(message.content !== '' || message.status === 'streaming') && (
        <p className="bubble__text">
          {message.content}
          {message.status === 'streaming' && (
            <span className="bubble__cursor" aria-hidden="true"> ▍</span>
          )}
        </p>
      )}
      {message.status === 'interrupted' && !message.error && (
        <p className="bubble__note">This reply was interrupted.</p>
      )}
      {message.error && (
        <ErrorBanner
          failure={message.error}
          onRetry={isStopped && onRetry ? () => onRetry(message.id) : undefined}
        />
      )}
      {!message.error && isStopped && onRetry && (
        <button type="button" onClick={() => onRetry(message.id)}>
          Retry
        </button>
      )}
    </article>
  );
}
