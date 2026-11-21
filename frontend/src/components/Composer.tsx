import { useState } from 'react';
import type { FormEvent } from 'react';
import { MAX_MESSAGE_CHARS } from '../hooks/useChat';

interface Props {
  disabled: boolean;
  onSend: (text: string) => void;
}

/**
 * The message input. It is disabled while a reply streams (spec edge case) and blocks empty and
 * over-long messages with a reason (spec FR-020).
 */
export function Composer({ disabled, onSend }: Props) {
  const [text, setText] = useState('');
  const [notice, setNotice] = useState<string | null>(null);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (disabled) {
      return;
    }
    const trimmed = text.trim();
    if (trimmed === '') {
      setNotice('Type a question before sending.');
      return;
    }
    if (trimmed.length > MAX_MESSAGE_CHARS) {
      setNotice(`Your message is longer than ${MAX_MESSAGE_CHARS.toLocaleString('en-US')} characters. Please shorten it.`);
      return;
    }
    onSend(trimmed);
    setText('');
    setNotice(null);
  }

  return (
    <form className="composer" onSubmit={submit}>
      <label className="composer__label" htmlFor="composer-input">
        Ask a question about LLM inference
      </label>
      <textarea
        id="composer-input"
        className="composer__input"
        value={text}
        onChange={(e) => setText(e.target.value)}
        disabled={disabled}
        rows={3}
        placeholder="For example: why do I get a 429 error on my requests?"
      />
      <div className="composer__actions">
        {notice && <span className="composer__notice" role="alert">{notice}</span>}
        <button type="submit" disabled={disabled}>
          Send
        </button>
      </div>
    </form>
  );
}
