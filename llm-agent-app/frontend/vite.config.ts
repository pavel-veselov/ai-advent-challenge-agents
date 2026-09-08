import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Прокси на backend (Spring WebFlux, порт 8080) — нет CORS в dev.
// /system-ctrl — супервизорный контроль (порт 8081), префикс срезается: /system-ctrl/stop → /stop.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/system-ctrl': {
        target: 'http://127.0.0.1:8081',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/system-ctrl/, ''),
      },
    },
  },
});
