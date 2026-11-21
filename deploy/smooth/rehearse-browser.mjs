import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { spawnSync } from 'node:child_process'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
import { log } from 'node:console'
const { chromium } = await import(pathToFileURL('C:/Users/25490/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs').href)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const out = path.join(root, 'outputs/smooth-rehearsal/browser')
await mkdir(out, { recursive: true })
const base = 'http://127.0.0.1:18191'
const control = '/var/lib/docker/volumes/quotation-smooth-control/_data'
function switchTo(release) {
  const result = spawnSync('docker', ['run', '--rm', '--network', 'quotation-smooth-uat-edge',
    '-v', '/var/run/docker.sock:/var/run/docker.sock', '-v', `quotation-smooth-control:${control}`,
    '-v', `${path.join(root, 'deploy/smooth')}:/tool:ro`, 'quotation-smooth-tool:20261121',
    'python', '/tool/release.py', '--config', `${control}/config.json`, 'switch', '--release', release], { encoding: 'utf8', timeout: 240_000 })
  assert.equal(result.status, 0, result.stderr)
}
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const page = await browser.newPage({ viewport: { width: 1500, height: 1000 }, locale: 'zh-CN' })
const errors = [], versions = [], writes = []
page.on('pageerror', error => errors.push(error.message))
page.on('response', response => { if (response.url().includes('/app-version.json')) versions.push(response.status()) })
page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/api/v1/quotations')) writes.push(request.url()) })
try {
  const before = await (await page.request.get(base + '/__quotation_release')).json()
  assert.equal(before.release, 'web-v1')
  const csrf = (await (await page.request.get(base + '/api/v1/auth/csrf')).json()).data
  const login = await page.request.post(base + '/api/v1/auth/login', { data: { account: 'SMOOTHQA', password: 'isolated-smooth-rehearsal-only-20261121' }, headers: { [csrf.headerName]: csrf.token } })
  assert.equal(login.status(), 200)
  await page.goto(base + '/quotation')
  const field = page.getByRole('combobox', { name: '客户名称' })
  await field.waitFor({ timeout: 30_000 })
  const draft = '隔离演练：切换期间保留未保存输入'
  await field.fill(draft)
  let reloads = 0
  page.on('framenavigated', frame => { if (frame === page.mainFrame()) reloads++ })
  await page.screenshot({ path: path.join(out, 'before.png'), fullPage: true })
  switchTo('web-v2')
  await page.waitForTimeout(6000)
  await page.evaluate(() => globalThis.dispatchEvent(new globalThis.Event('focus')))
  await page.waitForTimeout(2500)
  assert.equal(await field.inputValue(), draft)
  assert.equal(reloads, 0)
  assert.equal(await page.getByText('系统已更新，当前操作未提交。', { exact: false }).count(), 0)
  assert.equal(writes.length, 0)
  assert.equal(errors.length, 0, errors.join('\n'))
  await page.screenshot({ path: path.join(out, 'after.png'), fullPage: true })
  const me = await page.request.get(base + '/api/v1/auth/me')
  assert.equal(me.status(), 200)
  await writeFile(path.join(out, 'result.json'), JSON.stringify({ passed: true, retainedDraft: true, reloads, errors, versionChecks: versions, quotationWrites: writes.length }, null, 2))
  log('Old browser tab preserved unsaved input and session; no reload, forced update, or quotation submission.')
} finally { await browser.close() }
