import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Прокси на backend (Spring WebFlux, порт 8080) — нет CORS в dev.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
