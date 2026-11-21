import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ErrorBanner } from '../../src/components/ErrorBanner';

describe('ErrorBanner', () => {
  it('shows the wait time for a rate limit and offers retry', async () => {
    const onRetry = vi.fn();
    render(
      <ErrorBanner
        failure={{ code: 'RATE_LIMITED', message: 'Please wait a moment.', retryAfterSeconds: 7 }}
        onRetry={onRetry}
      />,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('Try again in 7 seconds.');
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('does not offer retry for an invalid request', () => {
    render(<ErrorBanner failure={{ code: 'INVALID_REQUEST', message: 'Shorten your message.' }} onRetry={() => {}} />);

    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
  });

  it('shows the fallback message when the backend text is empty', () => {
    render(<ErrorBanner failure={{ code: 'TIMEOUT', message: '' }} onRetry={() => {}} />);

    expect(screen.getByRole('alert')).toHaveTextContent('The reply took too long. Please try again.');
  });
});
