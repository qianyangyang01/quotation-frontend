import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import { randomUUID } from 'node:crypto'

export default defineConfig(({ command }) => {
  // The bundle and manifest share one opaque ID, including when a saved release
  // is rolled back. Development sessions do not check production versions.
  const buildId = command === 'build' ? randomUUID() : ''

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
            source: JSON.stringify({ schemaVersion: 1, buildId }),
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
