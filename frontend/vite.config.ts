import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// Dev server proxies /api to the Spring Boot backend, so no CORS config is needed in development
// (research R-13).
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8082',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['tests/setup.ts'],
    include: ['tests/**/*.test.{ts,tsx}'],
  },
});
