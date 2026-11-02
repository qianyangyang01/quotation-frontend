import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import assert from 'node:assert/strict'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const output = path.join(root, 'artifacts/update-overview')
const port = Number(process.env.OVERVIEW_PREVIEW_PORT || 5216)
const previewUrl = `http://127.0.0.1:${port}/`
await mkdir(output, { recursive: true })
const { bindings } = JSON.parse(await readFile(path.join(root, 'src/data/logisticsPriceWatchlistBindings.json'), 'utf8'))
const cases = [
  ['燕文专线惠选', 53, 67], ['联邮通服装', 63, 66], ['万邦专线挂号普货', 64, 66],
  ['万邦服装专线普货', 61, 62], ['云途普货CHC', 75, 73], ['通邮普货A', 93, 93], ['验收普通渠道', 80, 80],
]
const point = price => ({ price, rate: price - 5, fee: 5, chargeWeight: 1, model: 'per-kg', intervalPrice: null, reason: '' })
const fixture = {
  capturedAt: '2026-11-02T04:30:00Z', dataset: 'isolated-ui-fixture',
  importBatches: [
    { id: 'batch-previous-fixture', importedAt: '2026-11-01T08:00:00+08:00', operator: '物流负责人（示例）' },
    { id: 'batch-first-upload', importedAt: '2026-11-02T08:00:00+08:00', operator: '物流负责人（示例）' },
    { id: 'batch-current-fixture', importedAt: '2026-11-02T08:10:00+08:00', operator: '物流负责人（示例）' },
    { id: 'batch-second-fixture', importedAt: '2026-11-02T09:30:00+08:00', operator: '物流负责人（示例）' },
    { id: 'batch-third-fixture', importedAt: '2026-11-02T11:50:00+08:00', operator: '物流负责人（示例）' },
  ],
  channels: cases.map(([name], index) => ({ id: bindings[name]?.id || 'test-' + index, provider: bindings[name]?.provider || '测试物流商', name: bindings[name]?.name || name, code: 'test-' + index, ruleId: index + 1, current: `v${index}-2`, enabled: true, quoteReady: true, attribute: '普货' })),
  versions: cases.flatMap(([name, before, after], index) => [before, after].map((price, n) => ({ id: `v${index}-${n + 1}`, channel: bindings[name]?.id || 'test-' + index, number: n + 1, status: n ? 'published' : 'superseded', publishedAt: n ? `2026-11-02T${index < 3 ? '08:15' : index < 5 ? '09:35' : '11:55'}:${String(index).padStart(2, '0')}+08:00` : '2026-11-01T10:00:00+08:00', importBatchId: n ? (index < 3 ? 'batch-current-fixture' : index < 5 ? 'batch-second-fixture' : 'batch-third-fixture') : 'batch-previous-fixture', baseVersionId: n ? `v${index}-1` : '', fileName: '本地验收样例.xlsx', rows: [], preparedPrices: { 'DE|1': { 全国统一: point(price) }, 'DE|2': { 全国统一: point(price * 2) } } }))),
}
const html = `<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>更新概览 · 本地验收</title><style>body{margin:0;background:#f3f5f8;font-family:Arial,"Microsoft YaHei",sans-serif}.fixture-note{font-size:12px;padding:8px 28px;background:#fff6df;color:#785b27}</style></head><body><div class="fixture-note">本地验收样例 · 非生产价格</div><div id="app"></div><script type="module" src="/__overview_entry.js"></script></body></html>`
const entry = `import {createApp,h,shallowRef} from 'vue';import View from '/src/components/logistics/LogisticsPriceChanges.vue';const data=shallowRef(await (await fetch('/__overview_fixture.json')).json());window.overviewFixture=data;createApp({render:()=>h(View,{snapshot:data.value,refreshing:false,error:''})}).mount('#app');if(new URLSearchParams(location.search).has('overview'))document.querySelector('.overview-button')?.click();`
const server = await createServer({
  configFile: false, root, plugins: [vue(), { name: 'overview-fixture', resolveId(id) { if (id === '/__overview_entry.js') return '\0overview-entry' }, load(id) { if (id === '\0overview-entry') return entry }, configureServer(server) {
    server.middlewares.use((req, res, next) => {
      const routes = { '/': ['text/html', html], '/__overview_fixture.json': ['application/json', JSON.stringify(fixture)] }
      const route = routes[req.url?.split('?')[0]]
      if (!route) return next()
      res.setHeader('Content-Type', route[0] + '; charset=utf-8'); res.end(route[1])
    })
  } }], resolve: { alias: { '@': path.join(root, 'src') } }, server: { host: '127.0.0.1', port, strictPort: true },
})
await server.listen()
console.log('Preview: ' + previewUrl)
if (process.argv.includes('--serve')) await new Promise(() => {})
else {
  let browser
  try {
    const modulePath = process.env.QUOTATION_PLAYWRIGHT_MODULE
    const { chromium } = await import(modulePath ? pathToFileURL(modulePath).href : 'playwright')
    browser = await chromium.launch({ headless: true, channel: 'msedge' })
    const page = await browser.newPage({ viewport: { width: 1600, height: 1100 }, deviceScaleFactor: 1.5 })
    const errors = []; const apiCalls = []
    page.on('pageerror', error => { errors.push(error.message); console.error(error.message) })
    page.on('request', request => { if (request.url().includes('/api/')) apiCalls.push(request.url()) })
    await page.goto(previewUrl)
    await page.getByRole('button', { name: '更新概览', exact: true }).waitFor()
    await page.screenshot({ path: path.join(output, '页面按钮.png'), fullPage: true })
    await page.getByRole('button', { name: '更新概览', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: /价格更新概览/ })
    await dialog.waitFor()
    assert.equal(await dialog.locator('tbody tr').count(), 5)
    assert.match(await dialog.innerText(), /涨价 14.00 元\/票/)
    assert.match(await dialog.innerText(), /降价 2.00 元\/票/)
    assert.equal(await dialog.getByLabel('更新批次', { exact: true }).inputValue(), 'window:batch-first-upload')
    assert.match(await dialog.innerText(), /08:00 — 2026-11-02 12:00/)
    await page.screenshot({ path: path.join(output, '更新概览弹窗.png'), fullPage: true })
    await dialog.locator('.original-batches > summary').click()
    assert.equal(await dialog.locator('.original-batch-item').count(), 4)
    assert.equal(await dialog.locator('[data-batch="import:batch-first-upload"] button').isDisabled(), true)
    await page.screenshot({ path: path.join(output, '原始上传批次展开.png'), fullPage: true })
    await dialog.locator('[data-batch="import:batch-second-fixture"] button').click()
    assert.equal(await dialog.locator('tbody tr').count(), 2)
    assert.match(await dialog.locator('tbody').innerText(), /降价 2.00 元\/票/)
    assert.equal(await dialog.locator('.original-batches').evaluate(element => element.open), false)
    await page.screenshot({ path: path.join(output, '原始批次价格.png'), fullPage: true })
    await dialog.getByRole('button', { name: '返回整组汇总' }).click()
    assert.equal(await dialog.locator('tbody tr').count(), 5)
    const [download] = await Promise.all([page.waitForEvent('download'), dialog.getByRole('button', { name: '下载概览图' }).click()])
    await download.saveAs(path.join(output, '下载概览图.png'))
    await dialog.getByRole('button', { name: '全部 7', exact: true }).click()
    assert.equal(await dialog.locator('tbody tr').count(), 7)
    await dialog.getByLabel('概览试算重量').selectOption('2')
    assert.match(await dialog.innerText(), /涨价 28.00 元\/票/)
    await dialog.getByLabel('更新批次', { exact: true }).selectOption('window:batch-previous-fixture')
    assert.match(await dialog.innerText(), /首次记录/)
    await page.keyboard.press('Escape')
    assert.equal(await page.locator('dialog').count(), 0)
    assert.equal(await page.evaluate(() => document.activeElement?.textContent), '更新概览')
    await page.getByRole('button', { name: '更新概览', exact: true }).click()
    assert.equal(await dialog.getByLabel('概览试算重量').inputValue(), '1')
    // Keyboard focus stays inside the native modal.
    for (let index = 0; index < 25; index++) { await page.keyboard.press('Tab'); assert.equal(await page.evaluate(() => document.activeElement === document.body || !!document.activeElement?.closest('dialog')), true) }
    await page.setViewportSize({ width: 390, height: 844 })
    await page.screenshot({ path: path.join(output, '窄屏弹窗.png'), fullPage: true })
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
    const rect = await dialog.boundingBox(); assert.ok(rect && rect.x >= 0 && rect.x + rect.width <= 390)
    await page.setViewportSize({ width: 1024, height: 768 })
    await page.screenshot({ path: path.join(output, '平板弹窗.png'), fullPage: true })
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
    // Download a many-row overview as a ZIP; no rows may be lost at canvas limits.
    await page.evaluate(() => {
      const data = window.overviewFixture.value
      const extraChannels = [], extraVersions = []
      for (let i = 0; i < 30; i++) {
        extraChannels.push({ ...data.channels[0], id: `extra-${i}`, name: `导出测试渠道 ${i}`, current: `extra-${i}-2` })
        for (let n = 1; n <= 2; n++) extraVersions.push({ ...data.versions[n - 1], id: `extra-${i}-${n}`, channel: `extra-${i}`, baseVersionId: n === 2 ? `extra-${i}-1` : '' })
      }
      window.overviewFixture.value = { ...data, channels: [...data.channels, ...extraChannels], versions: [...data.versions, ...extraVersions] }
    })
    await page.waitForFunction(() => document.querySelectorAll('dialog tbody tr').length === 35)
    const [zip] = await Promise.all([page.waitForEvent('download'), dialog.getByRole('button', { name: '下载概览图' }).click()])
    assert.ok(zip.suggestedFilename().endsWith('.zip'))
    await zip.saveAs(path.join(output, '多页概览图.zip'))
    const { default: JSZip } = await import('jszip')
    const exported = await JSZip.loadAsync(await readFile(path.join(output, '多页概览图.zip')))
    assert.equal(Object.keys(exported.files).filter(file => file.endsWith('.png')).length, 2)
    assert.deepEqual(errors, []); assert.deepEqual(apiCalls, [])
    await writeFile(path.join(output, 'browser-verification.json'), JSON.stringify({ passed: true, checks: ['fixed four-hour group', 'original batch expansion and drilldown', 'unpublished first-upload anchor', 'header button', 'exact before/after and money', 'historical batch', 'weight', 'unchanged rows', 'native focus trap', 'Escape and focus restore', '390/1024/1600 widths', 'PNG download', 'multipage ZIP download', 'no production API calls'], errors, apiCalls }, null, 2))
    console.log('Browser verification passed')
  } finally { await browser?.close(); await server.close() }
}
