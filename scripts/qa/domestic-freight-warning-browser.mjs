import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { pathToFileURL } from 'node:url'

const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE || 'C:/Users/25490/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs').href)
const base = process.env.QA_BASE_URL || 'http://127.0.0.1:5197'
assert.equal(new URL(base).hostname, '127.0.0.1', 'local mock data only')
const out = process.env.QA_ARTIFACT_DIR || 'artifacts/domestic-freight-warning'
await mkdir(out, { recursive: true })
const rule = {
  id: 9910, name: '本地模拟渠道', status: '启用', billingVerified: true,
  logisticsChannelId: 'local-channel', logisticsVersionId: 'test',
  relations: [{ carrier: '本地模拟物流', channel: '本地演示渠道', channelCode: 'DEMO' }],
  prices: [{ areaName: '美国', countryCode: 'US', weightFromKg: 0, weightToKg: 30,
    pricingModel: 'per-kg', quoteReady: true, minChargeWeightKg: 0, startWeightKg: 0,
    firstWeightKg: 0, firstWeightPrice: 0, nextWeightKg: 0, nextWeightPrice: 0,
    pricePerKg: 50, registrationFee: 5, intervalWeightKg: 0, intervalPrice: 0,
    surcharge: 0, fuelSurchargeRate: 0, volumetric: false, zoneName: '', prohibitedMarks: '', allowedMarks: '' }],
}
const values = {
  'country-classification': [{ country: '美国', enabled: true, stage: 'common', sortOrder: 1 }],
  'channel-policies': [{ id: '普货', category: '普货', enabled: true, countryRules: [{ country: '美国', allowedChannels: ['9910::本地模拟物流::DEMO'] }] }],
  'customer-grades': [{ grade: 'S', coefficient: 1.2, enabled: true }],
  'exchange-rate': { usdCny: 6.7, eurUsd: 1.1, updatedAt: 'local-demo' },
  'tax-settings': { countries: [], providers: [], updatedAt: 'local-demo' },
  'surcharge-settings': { countries: [], providers: [], updatedAt: 'local-demo' },
  'customer-operation-fees': { customers: [] },
  'freight-discount-settings': {},
}
const finance = Object.fromEntries(Object.entries(values).map(([key, value]) => [key, { value, _version: 1 }]))
const financeVersions = Object.fromEntries(Object.keys(values).map(key => [key, 1]))
const purchase = {
  sku: 'FREIGHT-WARN-01', category: '运费预警演示商品', catalogState: 'ready', dataSource: 'standard',
  factoryInfo: '江苏省苏州市本地演示工厂', weightG: 230, weightOriginal: '230g', minOrderQty: 1,
  purchasePriceCny: 20, tier2MinQty: 20, tier2PriceCny: 18, tier3MinQty: 100, tier3PriceCny: 16,
  taxPoint: 0, invoiceType: '普票', freight10Cny: 12, freight100Cny: 30, _version: 1, _updatedAt: 'local-demo',
}
const errors = [], writes = [], saved = []
const browser = await chromium.launch({ channel: 'chrome', headless: true })
let page
try {
  page = await browser.newPage({ viewport: { width: 1536, height: 1060 }, locale: 'zh-CN' })
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname.replace('/api/v1', '')
    if (!['GET', 'HEAD'].includes(request.method())) writes.push(`${request.method()} ${path}`)
    let data
    if (path === '/auth/me') data = { id: 'local-demo', account: 'LOCALDEMO', name: '本地模拟数据', role: 'super_admin', permissions: ['quote', 'purchase', 'logistics', 'finance', 'allRecords', 'myRecords', 'permissions'], mustChangePassword: false }
    else if (path === '/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'local-only' }
    else if (path === '/finance-settings') data = finance
    else if (path === '/quotation-readiness') data = { ready: true, missing: [] }
    else if (path === '/quotation-drafts/mine/state') data = { exists: false }
    else if (path === '/quotation-templates' || path === '/personal-quotation-customers') data = []
    else if (path === '/review-notifications') data = { items: [], unread: [], counts: {}, total: 0, page: 0, totalPages: 0 }
    else if (path === '/logistics/published/manifest') data = { revision: 'local-demo', publishedChannels: 1, countries: [{ code: 'US', name: '美国' }], attributes: ['普货'] }
    else if (path === '/logistics/published/rules') data = { revision: 'local-demo', rules: [rule] }
    else if (path === '/logistics/published/catalog') data = { revision: 'local-demo', rules: [rule] }
    else if (path.startsWith('/purchase-products/')) data = purchase
    else if (path === '/quotation-sync') data = { purchaseVersions: { [purchase.sku]: '1:local-demo' }, logisticsRevision: 'local-demo', financeVersions }
    else if (path === '/quotation-sync/logistics') data = { revision: 'local-demo' }
    else if (path === '/quotations' && request.method() === 'POST') { data = { ...request.postDataJSON(), id: `LOCAL-${saved.length + 1}`, no: 'LOCAL-DEMO', createdAt: '2026-10-17T00:00:00Z' }; saved.push(data) }
    else { errors.push(`Unexpected API ${request.method()} ${path}`); data = [] }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'OK', data }) })
  })
  await page.goto(base + '/quotation')
  await page.locator('.customer-field input').fill('本地运费预警演示客户')
  await page.locator('.sku-field input').fill(purchase.sku)
  await page.getByRole('button', { name: '查询商品', exact: true }).click()
  await page.locator('.domestic-freight-warning').waitFor()
  assert((await page.locator('.domestic-freight-warning').innerText()).includes('苏州市'))
  assert((await page.locator('.domestic-freight-warning').innerText()).includes('¥8.00'))
  await page.locator('.module-card').screenshot({ path: `${out}/warning-panel.png` })
  await page.screenshot({ path: `${out}/warning-visible.png`, fullPage: true })
  await page.locator('[data-validation-field="monthlySalesEstimate"] select').selectOption('100')
  assert(await page.locator('.domestic-freight-warning').count(), 'tier 2 min quantity 20 still uses the 10-piece freight tier')
  await page.locator('[data-validation-field="monthlySalesEstimate"] select').selectOption('100+')
  await page.locator('.domestic-freight-warning').waitFor({ state: 'detached' })
  await page.locator('[data-validation-field="monthlySalesEstimate"] select').selectOption('10')
  await page.locator('.domestic-freight-warning').waitFor()
  await page.locator('details.warning-rules summary').click()
  assert(await page.locator('details.warning-rules[open]').count())
  await page.screenshot({ path: `${out}/warning-rules-open.png`, fullPage: true })
  const commonMode = page.locator('[aria-label="报价矩阵分类"] button').first()
  await commonMode.click()
  await page.waitForFunction(() => document.querySelector('[aria-label="报价矩阵分类"] button')?.getAttribute('aria-pressed') === 'true')
  const unitedStates = page.locator('.country-grid button').filter({ hasText: '美国' })
  await unitedStates.waitFor()
  await unitedStates.click()
  const channel = page.locator('.quote-rows article').first()
  await channel.waitFor()
  const addChannel = channel.locator('.selection-actions button').first()
  await addChannel.waitFor()
  assert(await addChannel.isEnabled(), 'the local quote fixture must provide a usable channel')
  await addChannel.click()
  await page.locator('.sheet-price input').first().waitFor()
  await page.locator('.quote-preview').scrollIntoViewIfNeeded()
  const save = page.getByRole('button', { name: /保存 1 张报价单/ })
  await save.waitFor()
  assert(await save.isEnabled(), 'a freight notice cannot block quotation save')
  await save.click()
  await page.waitForFunction(() => document.body.innerText.includes('报价已保存'))
  assert.equal(saved.length, 1)
  assert.equal(saved[0].domesticFreightPerUnitCny, 1.2)

  // An unresolved factory location makes the optional warning indeterminate,
  // but must not make an otherwise valid quote unavailable.
  purchase.factoryInfo = ''
  await page.reload()
  await page.locator('.customer-field input').fill('工厂资料缺失仍可报价')
  await page.locator('.sku-field input').fill(purchase.sku)
  await page.getByRole('button', { name: '查询商品', exact: true }).click()
  await page.locator('.cost-workbench').waitFor()
  assert.equal(await page.locator('.domestic-freight-warning').count(), 0)
  await page.locator('[aria-label="报价矩阵分类"] button').first().click()
  await page.locator('.country-grid button').filter({ hasText: '美国' }).click()
  const missingFactoryChannel = page.locator('.quote-rows article').first()
  await missingFactoryChannel.waitFor()
  await missingFactoryChannel.locator('.selection-actions button').first().click()
  const missingFactorySave = page.getByRole('button', { name: /保存 1 张报价单/ })
  await missingFactorySave.waitFor()
  assert(await missingFactorySave.isEnabled(), 'missing factory location must not block quotation save')
  await missingFactorySave.click()
  await page.waitForFunction(() => document.body.innerText.includes('报价已保存'))
  assert.equal(saved.length, 2)
  assert.equal(saved[1].domesticFreightPerUnitCny, 1.2)
  assert.deepEqual(errors, [])
  await writeFile(`${out}/browser-report.json`, JSON.stringify({ passed: true, errors, writes, savedRecords: saved.length, normalFreightPerUnitCny: 1.2, missingFactoryWarningCount: 0 }, null, 2))
  console.log('Domestic freight notice, tier recheck, quote save, and missing-factory quote passed against local mocks')
} catch (error) {
  if (page) {
    await page.screenshot({ path: `${out}/failure.png`, fullPage: true }).catch(() => {})
    await writeFile(`${out}/failure.txt`, `${error.stack}\n\n${await page.locator('body').innerText().catch(() => '')}\n\n${JSON.stringify({ errors, writes }, null, 2)}`)
  }
  throw error
} finally { await browser.close() }
