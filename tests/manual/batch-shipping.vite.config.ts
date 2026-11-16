import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

// Isolated preview server: no backend proxy, no production connection even if the fixture fails.
export default defineConfig({
  plugins: [vue(), { name: 'local-shipping-preview', configureServer(server) {
    server.middlewares.use((req, res, next) => {
      if (req.url?.startsWith('/api/')) { res.statusCode = 503; res.end('Local fixture only; backend access disabled'); return }
      if (req.url === '/' || /^\/quotation(?:[/?]|$)/.test(req.url || '')) req.url = '/tests/manual/batch-shipping-preview.html'
      next()
    })
  } }],
  resolve: { alias: { '@': fileURLToPath(new URL('../../src', import.meta.url)) } },
  server: { host: '127.0.0.1', port: 5187, strictPort: true, watch: { ignored: ['**/backend/target/**', '**/outputs/**'] } },
})
