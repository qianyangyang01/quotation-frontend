import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import { randomUUID } from 'node:crypto'

export default defineConfig(({ command }) => {
  // Compatible releases retain the client protocol identity so already-open
  // pages can finish their work. The artifact still has a unique release ID.
  const releaseBuildId = command === 'build' ? randomUUID() : ''
  const compatibleBuildId = process.env.QUOTATION_COMPATIBLE_BUILD_ID || ''
  if (compatibleBuildId && !/^[a-zA-Z0-9-]{1,100}$/.test(compatibleBuildId)) throw new Error('Invalid compatible build ID')
  const buildId = command === 'build' ? compatibleBuildId || releaseBuildId : ''

  return {
    define: { __APP_BUILD_ID__: JSON.stringify(buildId) },
    plugins: [
      vue(),
      {
        name: 'app-version-manifest',
        apply: 'build',
        generateBundle() {
          this.emitFile({
            type: 'asset',
            fileName: 'app-version.json',
            source: JSON.stringify({ schemaVersion: 1, buildId, releaseBuildId }),
          })
        },
      },
    ],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      host: '0.0.0.0',
      port: 5173,
      strictPort: true,
      watch: { ignored: ['**/backend/target/**', '**/outputs/**'] },
      proxy: { '/api': { target: process.env.QUOTATION_DEV_API_TARGET || 'http://127.0.0.1:8088', changeOrigin: false } },
    },
  }
})
