import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// Vitest globals are off, so Testing Library's automatic cleanup is not registered. Unmount
// rendered trees after each test so DOM does not leak between tests.
afterEach(() => {
  cleanup();
});
