/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
  server: {
    proxy: {
      // Forward API calls to the Grails server during development so the SPA
      // and API share one origin (no CORS setup needed).
      '/api': {
        target: 'http://localhost:8080',
        // Keep the browser's Host header: Spring's WebSocket handshake
        // rejects requests whose Origin does not match the Host, and the
        // browser always sends Origin http://localhost:5173.
        changeOrigin: false,
        // Remote view (noVNC) runs over a WebSocket on the same API prefix.
        ws: true,
      },
    },
  },
})
