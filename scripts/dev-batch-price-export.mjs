import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const server = await createServer({ configFile: false, root, plugins: [vue()], resolve: { alias: { '@': path.join(root, 'src') } }, define: { __APP_BUILD_ID__: JSON.stringify('') }, server: { host: '127.0.0.1', port: 5226, strictPort: true } })
await server.listen()
console.log('Local preview: http://127.0.0.1:5226/dev/batch-price-export/index.html')
