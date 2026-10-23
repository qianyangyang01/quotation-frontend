import assert from 'node:assert/strict'
import { test } from 'node:test'
import { fileURLToPath, URL } from 'node:url'
import { build, loadConfigFromFile } from 'vite'

const configFile = fileURLToPath(new URL('../vite.config.ts', import.meta.url))

async function buildVersionFixture() {
  const { config } = await loadConfigFromFile({ command: 'build', mode: 'production' }, configFile)
  const result = await build({
    ...config,
    configFile: false,
    logLevel: 'silent',
    build: {
      write: false,
      minify: false,
      rollupOptions: { input: 'virtual:version-check' },
    },
    plugins: [
      ...config.plugins,
      {
        name: 'version-check-fixture',
        resolveId(id) {
          if (id === 'virtual:version-check') return id
        },
        load(id) {
          if (id === 'virtual:version-check') return 'globalThis.versionCheck = __APP_BUILD_ID__;'
        },
      },
    ],
  })
  const manifest = JSON.parse(result.output.find((item) => item.fileName === 'app-version.json').source)
  const chunk = result.output.find((item) => item.type === 'chunk').code
  assert.equal(manifest.schemaVersion, 1)
  assert.match(manifest.buildId, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  assert.equal(JSON.parse(config.define.__APP_BUILD_ID__), manifest.buildId)
  assert.ok(chunk.includes(manifest.buildId), 'bundle must contain the same ID as its manifest')
  return manifest.buildId
}

test('production bundles and manifests share an ID that changes for each build', async () => {
  const firstBuildId = await buildVersionFixture()
  const secondBuildId = await buildVersionFixture()
  assert.notEqual(firstBuildId, secondBuildId)
})

test('development disables production version checks', async () => {
  const { config } = await loadConfigFromFile({ command: 'serve', mode: 'development' }, configFile)
  assert.equal(JSON.parse(config.define.__APP_BUILD_ID__), '')
})
