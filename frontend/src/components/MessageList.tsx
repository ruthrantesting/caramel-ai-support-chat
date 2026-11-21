import { useEffect, useRef } from 'react';
import type { ChatMessage } from '../types/chat';
import { MessageBubble } from './MessageBubble';

interface Props {
  messages: ChatMessage[];
  /** Undefined while a reply is in progress, so retry is not offered then. */
  onRetry?: (messageId: string) => void;
}

export function MessageList({ messages, onRetry }: Props) {
  const endRef = useRef<HTMLDivElement>(null);

  // Keep the newest text in view while a reply streams.
  useEffect(() => {
    endRef.current?.scrollIntoView?.({ block: 'end' });
  }, [messages]);

  return (
    <div className="message-list" role="log" aria-live="polite" aria-label="Conversation">
      {messages.map((message) => (
        <MessageBubble key={message.id} message={message} onRetry={onRetry} />
      ))}
      <div ref={endRef} />
    </div>
  );
}
