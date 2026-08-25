import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In development the SPA runs on 5173 and proxies to Spring on 8080, so the
// browser sees a single origin and the session cookie and the Entra redirect
// both behave exactly as they do in production.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/oauth2': 'http://localhost:8080',
      '/login': 'http://localhost:8080',
      '/logout': 'http://localhost:8080',
    },
  },
})
