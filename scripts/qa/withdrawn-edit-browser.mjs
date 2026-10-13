import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { pathToFileURL } from 'node:url'
const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE || 'C:/Users/25490/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs').href)
const base = process.env.QA_BASE_URL || 'http://127.0.0.1:5193', out = 'artifacts/withdrawn-edit'
assert.equal(new URL(base).hostname, '127.0.0.1')
await mkdir(out, { recursive: true })
const task = { recordId: 'withdrawn-demo', quoteNo: 'QUOTE-DEMO-003', customerName: '示例客户A', primarySku: 'DEMO-SKU-03', batchNo: 'BATCH-DEMO-008', withdrawnAt: '2026-10-13T02:30:00Z' }
const batch = { id: 'batch-demo', no: task.batchNo, index: 3, count: 20 }
const draft = { schemaVersion: 2, customerName: task.customerName, quoteMode: 'single', skuSearch: task.primarySku, logisticsAttribute: '普货', productCategory: '日用品', selectedCustomerGrade: 'S', monthlySalesEstimate: '10', customQuoteQuantity: 30, quoteMatrixMode: 'common', selectedQuoteRegions: {}, product: { sku: task.primarySku, quantity: 1, weightSource: 'purchase', manualWeight: 0, volumetricEnabled: false, packageLengthCm: 0, packageWidthCm: 0, packageHeightCm: 0, primaryCountry: '', primaryChannelKey: '', primaryRule: '', primaryCarrier: '' }, bundleItems: [], commonSelections: [], specifiedSelections: [], templateSelections: [], activeTemplate: null }
const values = { 'country-classification': [{ country: '美国', enabled: true, stage: 'common', sortOrder: 1 }], 'channel-policies': [{ id: '普货', category: '普货', enabled: true, countryRules: [] }], 'customer-grades': [{ grade: 'S', coefficient: 1.2, enabled: true }], 'exchange-rate': { usdCny: 6.7, eurUsd: 1.1, updatedAt: 'test' }, 'tax-settings': { countries: [], providers: [], updatedAt: 'test' }, 'surcharge-settings': { countries: [], providers: [], updatedAt: 'test' }, 'customer-operation-fees': { customers: [] }, 'fob-small-order-policy': { scope: 'single-price', calculation: 'before-coefficient' } }
const finance = Object.fromEntries(Object.entries(values).map(([key, value]) => [key, { value, _version: 1 }]))
const versions = Object.fromEntries(Object.keys(values).map(key => [key, 1]))
const errors = [], writes = []
const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  const context = await browser.newContext({ viewport: { width: 1754, height: 1000 }, locale: 'zh-CN' })
  const page = await context.newPage()
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/v1/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname.replace('/api/v1', '')
    if (!['GET', 'HEAD'].includes(req.method())) writes.push({ path, method: req.method() })
    let data
    if (path === '/auth/me') data = { id: 'local-demo', account: 'LOCALDEMO', name: '本地验收（示例）', role: 'super_admin', permissions: ['quote', 'purchase', 'logistics', 'finance', 'allRecords', 'myRecords', 'permissions'], mustChangePassword: false }
    else if (path === '/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'local-only' }
    else if (path === '/finance-settings') data = finance
    else if (path === '/quotation-readiness') data = { ready: true, missing: [] }
    else if (path === '/review-notifications') data = { items: [], unread: [], counts: {}, total: 0, page: 0, totalPages: 0, withdrawnEdit: task }
    else if (path === '/quotation-drafts/mine/state') data = { exists: true, version: 7, updatedAt: task.withdrawnAt, payload: draft, sourceQuote: { id: task.recordId, no: task.quoteNo, version: 8, customerName: task.customerName, primarySku: task.primarySku, quotationBatch: batch } }
    else if (path === '/quotation-templates' || path === '/personal-quotation-customers') data = []
    else if (path === '/logistics/published/manifest') data = { revision: 'test', publishedChannels: 0, countries: [], attributes: ['普货'] }
    else if (path === '/logistics/published/rules' || path === '/logistics/published/catalog') data = { revision: 'test', rules: [] }
    else if (path === '/quotation-sync') data = { purchaseVersions: {}, logisticsRevision: 'test', financeVersions: versions }
    else if (path === '/quotation-sync/logistics') data = { revision: 'test' }
    else if (path.startsWith('/purchase-products/')) data = { sku: task.primarySku, category: '日用品', catalogState: 'ready', dataSource: 'standard', weightG: 50, minOrderQty: 1, purchasePriceCny: 12, singleFreightCny: 0, taxPoint: 0 }
    else if (path.startsWith('/fob-purchase-products/')) data = { sku: decodeURIComponent(path.split('/').at(-1)), category: '日用品', weight: '50g', updatedAt: task.withdrawnAt, parsed: { minOrderQty: 3, orderMultiple: 1, priceTiers: [{ minQty: 3, maxQty: null, unitPriceCny: 3.9, unit: '件' }], freight: { quantity: 100, totalFreightCny: 17, unitFreightCny: .17, basis: '100件运费17÷100', estimated: false } } }
    else if (path === '/quotations/fob/preview') data = req.postDataJSON().fob
    else { errors.push('Unexpected API ' + path); data = [] }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'OK', data }) })
  })
  await page.goto(base + '/quotation')
  await page.getByLabel('报价模式', { exact: true }).waitFor()
  assert.equal(await page.locator('.withdrawn-context').count(), 0)
  await page.getByLabel('报价模式', { exact: true }).selectOption('fob')
  await page.getByRole('button', { name: '批量粘贴报价', exact: true }).click()
  await page.locator('#fob-batch-paste').fill(Array.from({ length: 20 }, (_, i) => `DEMO-${String(i + 1).padStart(3, '0')} 30`).join('\n'))
  await page.getByRole('button', { name: '查询粘贴清单', exact: true }).click()
  await page.getByRole('button', { name: '生成20行商品报价单', exact: true }).waitFor()
  await page.locator('.metadata input').first().fill('测试客户')
  await page.getByRole('button', { name: '生成20行商品报价单', exact: true }).click()
  const save = page.getByRole('button', { name: '保存 20 条FOB报价记录', exact: true })
  await save.waitFor()
  assert(await save.isEnabled(), 'Independent FOB batch must remain saveable')
  await page.locator('.notification-trigger').click()
  await page.getByRole('button', { name: '继续编辑该单品', exact: true }).waitFor()
  await page.screenshot({ path: out + '/bell-fob-batch.png' })
  page.once('dialog', dialog => dialog.dismiss())
  await page.getByRole('button', { name: '继续编辑该单品', exact: true }).click()
  assert.equal(new URL(page.url()).pathname, '/quotation')
  assert(await save.isEnabled(), 'Cancelling navigation preserves the generated batch')
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '继续编辑该单品', exact: true }).click()
  await page.waitForURL('**/quotation/withdrawn-edit?record=withdrawn-demo')
  await page.locator('.withdrawn-context').waitFor()
  assert((await page.locator('.withdrawn-context').innerText()).includes(batch.no))
  assert(await page.getByLabel('报价模式', { exact: true }).isDisabled())
  await page.getByRole('button', { name: '保存并重新提交审核', exact: true }).waitFor()
  await page.screenshot({ path: out + '/single-editor.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.locator('.notification-trigger').click()
  await page.screenshot({ path: out + '/bell-mobile.png' })
  assert.deepEqual(errors, [])
  assert.equal(writes.filter(r => r.path !== '/quotations/fob/preview').length, 0, 'Navigation and preview must not modify quotations or drafts')
  await writeFile(out + '/result.json', JSON.stringify({ passed: true, errors, writes, checks: ['20 SKU FOB save enabled with pending edit', 'bell accessible above batch panel', 'cancel navigation preserves batch', 'one record-specific editor with batch context', 'read-only restoration'] }, null, 2))
  console.log('Withdrawn edit browser checks passed')
} finally { await browser.close() }
