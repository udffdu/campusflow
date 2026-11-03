import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
export default defineConfig({
  plugins: [vue()],
  server: { port: 5173, strictPort: true, proxy: { '/api/m1': process.env.M1_PROXY_TARGET ?? 'http://127.0.0.1:8081', '/api': process.env.D_PROXY_TARGET ?? 'http://127.0.0.1:8080' } },
  test: { environment: 'happy-dom', include: ['src/**/*.test.ts'], clearMocks: true },
})
