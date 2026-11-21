import { FALLBACK_MESSAGES } from '../api/errorMessages';
import type { ChatErrorCode, ChatFailure } from '../types/chat';

/** Every code except INVALID_REQUEST can succeed on a later attempt (backend ChatErrorCode). */
const NOT_RETRYABLE: ReadonlySet<ChatErrorCode> = new Set<ChatErrorCode>(['INVALID_REQUEST']);

interface Props {
  failure: ChatFailure;
  onRetry?: () => void;
}

/**
 * Plain-language failure message with a Retry action where a retry can help (spec FR-014 to
 * FR-017, Constitution IV). The text is the backend's fixed message, or the client fallback.
 */
export function ErrorBanner({ failure, onRetry }: Props) {
  const text = failure.message || FALLBACK_MESSAGES[failure.code];
  const wait =
    failure.code === 'RATE_LIMITED' && failure.retryAfterSeconds
      ? ` Try again in ${failure.retryAfterSeconds} seconds.`
      : '';
  const canRetry = onRetry !== undefined && !NOT_RETRYABLE.has(failure.code);

  return (
    <div className="error-banner" role="alert">
      <p>
        {text}
        {wait}
      </p>
      {canRetry && (
        <button type="button" onClick={onRetry}>
          Retry
        </button>
      )}
    </div>
  );
}
