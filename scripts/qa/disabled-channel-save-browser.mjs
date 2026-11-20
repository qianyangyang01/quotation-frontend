import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { pathToFileURL } from 'node:url'
const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE || 'C:/Users/25490/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs').href)
const base = process.env.QA_BASE_URL || 'http://127.0.0.1:5297'
assert(['127.0.0.1', 'vip.ahmln.com'].includes(new URL(base).hostname))
const out = process.env.QA_OUTPUT || 'outputs/disabled-channel-save-browser'
await mkdir(out, { recursive: true })
// Isolated fixture: the reported SKU and template name, synthetic rates and customer.
// No production API, authentication, or database writes are used by this test.
const key = '9910::云速递::TEST'
const countries = [{ code: 'HK', name: '中国香港' }, { code: 'US', name: '美国' }, { code: 'GB', name: '英国' }]
const rule = { id: 9910, name: '全球专线敏感', billingVerified: true, status: '启用', logisticsChannelId: 'test-channel', logisticsVersionId: 'test-version',
  relations: [{ carrier: '云速递', channel: '全球专线敏感', channelCode: 'TEST' }], prices: countries.slice(1).map(c => ({
    areaName: c.name, countryCode: c.code, weightFromKg: 0, weightToKg: 100, pricingModel: 'per-kg', quoteReady: true,
    minChargeWeightKg: 0, startWeightKg: 0, firstWeightKg: 0, firstWeightPrice: 0, nextWeightKg: 0, nextWeightPrice: 0,
    pricePerKg: c.code === 'US' ? 50 : 60, registrationFee: 5, intervalWeightKg: 0, intervalPrice: 0,
    surcharge: 0, fuelSurchargeRate: 0, volumetric: false, zoneName: '', prohibitedMarks: '', allowedMarks: '',
  })) }
const fees = { countries: countries.map((c, i) => ({ country: c.name, fixedFeeUsd: 0, selected: true, enabled: true, sortOrder: i })),
  providers: [{ provider: '云速递', mode: 'taxable', selected: true, channels: [] }], updatedAt: 'test' }
const values = { 'country-classification': countries.map((c, i) => ({ country: c.name, enabled: true, stage: 'common', sortOrder: i })),
  'channel-policies': [{ id: '纯电', category: '纯电', enabled: true, countryRules: countries.map(c => ({ country: c.name, allowedChannels: [key] })) }],
  'customer-grades': [{ grade: 'S', coefficient: 1.2, enabled: true }], 'exchange-rate': { usdCny: 6.63, eurUsd: 1.16, updatedAt: 'test' },
  'tax-settings': fees, 'surcharge-settings': fees, 'customer-operation-fees': { customers: [] } }
const finance = Object.fromEntries(Object.entries(values).map(([k, value]) => [k, { value, _version: 1 }]))
const versions = Object.fromEntries(Object.keys(values).map(k => [k, 1]))
const template = { id: 'local-pure', name: '帽子客户-纯电', ownerAccount: 'LOCALQA', ownerName: '本地验收', ownerKey: 'ACCOUNT:LOCALQA',
  createdAt: '2026-11-19', updatedAt: '2026-11-19', _version: 1, items: countries.slice(1).map(c => ({
    country: c.name, countryCode: c.code, channelKey: key, ruleId: 9910, rule: rule.name, carrier: '云速递', transport: rule.name, channelCode: 'TEST',
  })) }
const product = { sku: 'TC2602577', category: '配饰', catalogState: 'ready', quoteReady: true, weightG: 400,
  minOrderQty: 1, purchasePriceCny: 75, singleFreightCny: 6, taxPoint: .1, invoiceType: '专票13', _version: 0, _updatedAt: 'test' }
const retired = { country: '美国', carrier: '通邮', rule: '通邮挂号特货', channel: '通邮挂号特货', channelKey: '563::通邮::RETIRED', isPrimary: true, quote1Usd: 999 }
const selections = [retired, ...template.items.map(item => ({ ...item, channel: item.transport }))]
const source = { id: 'retired-source', no: 'LOCAL-OLD', customerName: '本地停用渠道回归，不用于业务', quoteMode: 'shipping-only',
  logisticsAttribute: '纯电', customerGrade: 'S', monthlySalesEstimate: '10', exchangeRate: 6.63,
  manualPricing: { costCny: 0, weightGrams: 400 }, matrixMode: process.env.QA_MATRIX || 'common', customQuoteQuantity: 5,
  ...retired, systemQuoteUsd: 999, quoteOptions: selections, _version: 3 }
const original = JSON.stringify(source)
const draftSelections = selections.map(o => ({ country: o.country, channelKey: o.channelKey, rule: o.rule, carrier: o.carrier, transport: o.channel }))
const draft = { schemaVersion: 2, customerName: source.customerName, quoteMode: source.quoteMode, logisticsAttribute: source.logisticsAttribute,
  selectedCustomerGrade: 'S', monthlySalesEstimate: '10', commissionThreshold: 1, customQuoteQuantity: 5,
  manualPricing: source.manualPricing, skuSearch: '', productCategory: '', quoteMatrixMode: source.matrixMode, selectedQuoteRegions: {},
  product: { quantity: 1, primaryCountry: retired.country, primaryChannelKey: retired.channelKey, primaryRule: retired.rule, primaryCarrier: retired.carrier },
  commonSelections: source.matrixMode === 'common' ? draftSelections : [], templateSelections: source.matrixMode === 'template' ? draftSelections : [],
  specifiedSelections: [], bundleItems: [], activeTemplate: null }
let draftVersion = 2
const withdrawn = process.env.QA_WITHDRAWN === '1'
const draftState = () => ({ exists: true, version: draftVersion, payload: draft, updatedAt: '2026-11-20T05:00:00Z',
  sourceQuote: { id: source.id, no: source.no, version: source._version } })
const saved = [], requests = [], errors = []
let ruleLoads = 0
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const page = await browser.newPage({ viewport: { width: 1600, height: 1100 }, locale: 'zh-CN' })
page.on('pageerror', error => errors.push(error.message))
try {
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname.replace('/api/v1', '')
    requests.push(req.method() + ' ' + path + url.search)
    let data
    if (!url.pathname.startsWith('/api/v1/')) { await route.fulfill({ status: 404, body: '{}' }); return }
    if (path === '/auth/me') data = { id: 'qa-user', account: 'LOCALQA', name: '本地验收', role: 'super_admin', permissions: ['quote','purchase','logistics','finance','allRecords','permissions'], mustChangePassword: false }
    else if (path === '/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'local-only' }
    else if (path === '/finance-settings') data = finance
    else if (path === '/quotation-readiness') data = { ready: true, missing: [] }
    else if (path === '/quotations/retired-source/resubmit') {
      data = { ...req.postDataJSON().quotation, id: 'local-save', no: 'LOCAL-QA-ONLY', createdAt: new Date().toISOString() }; saved.push(data)
    }
    else if (path === '/quotations/retired-source') data = source
    else if (path === '/quotation-drafts/mine/state') { if (req.method() === 'PUT') draftVersion++; data = withdrawn ? draftState() : { exists: false } }
    else if (path === '/quotation-templates') data = [template]
    else if (path === '/personal-quotation-customers') data = []
    else if (path === '/purchase-products/TC2602577') data = product
    else if (path === '/logistics/published/manifest') data = { revision: 'test', publishedChannels: 1, countries, attributes: ['纯电'] }
    else if (path === '/logistics/published/rules' || path === '/logistics/published/catalog') {
      const requested = url.searchParams.getAll('country')
      const hasDestination = ++ruleLoads > 0 && (requested.some(c => ['美国', '英国', 'US', 'GB'].includes(c)) || path.endsWith('/catalog'))
      if (hasDestination) await new Promise(resolve => setTimeout(resolve, 500))
      data = { revision: 'test', rules: hasDestination ? [rule] : [] }
    }
    else if (path === '/quotation-sync') data = { purchaseVersions: { TC2602577: '0:test' }, logisticsRevision: 'test', financeVersions: versions }
    else if (path === '/quotation-sync/logistics') data = { revision: 'test' }
    else if (path === '/quotations' && req.method() === 'POST') {
      data = { ...req.postDataJSON(), id: 'local-save', no: 'LOCAL-QA-ONLY', createdAt: new Date().toISOString() }; saved.push(data)
    } else if (path.includes('notification')) data = { items: [], unreadCount: 0, total: 0 }
    else { errors.push('Unexpected endpoint: ' + req.method() + ' ' + path); data = [] }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'OK', data }) })
  })
  await page.goto(base + (withdrawn ? '/quotation/withdrawn-edit?record=retired-source' : '/quotation?reissue=retired-source'))
  await page.waitForFunction(() => document.body.innerText.includes('不影响其他可用渠道保存报价'))
  const save = page.locator('.quote-preview .save')
  await save.waitFor()
  await page.waitForFunction(() => document.querySelector('.quote-preview .save')?.disabled === false)
  await page.screenshot({ path: out + '/ready.png', fullPage: true })
  assert.equal(saved.length, 0)
  await save.click()
  await page.waitForFunction(() => document.body.innerText.includes('报价已保存') || document.body.innerText.includes('已重新提交'))
  assert.equal(saved.length, 1)
  assert.equal(saved[0].quoteOptions.length, 3, 'Retired row remains in saved snapshot')
  const unavailable = saved[0].quoteOptions.find(o => o.channelKey === retired.channelKey)
  assert.equal(unavailable.available, false)
  assert.equal(unavailable.quote1Usd, null)
  assert.match(unavailable.availabilityMessage, /停用/)
  assert.equal(unavailable.isPrimary, false)
  assert.equal(saved[0].quoteOptions.filter(o => o.isPrimary).length, 1)
  assert.equal(saved[0].quoteOptions.find(o => o.isPrimary).channelKey, key)
  assert(saved[0].systemQuoteUsd > 0 && saved[0].systemQuoteUsd < 999)
  assert.equal(JSON.stringify(source), original)
  assert(!requests.some(r => /^(PATCH|DELETE) /.test(r)))
  assert(!requests.some(r => /^PUT /.test(r) && (!withdrawn || !r.includes('/quotation-drafts/mine/state'))))
  assert.deepEqual(errors, [])
  await page.screenshot({ path: out + '/saved.png', fullPage: true })
  await writeFile(out + '/result.json', JSON.stringify({ result: 'passed', saved, requests, errors }, null, 2))
  console.log('PASS: retired original primary retained without prices, usable rows saved, original snapshot unchanged')
} catch (error) {
  await writeFile(out + '/failure.txt', String(error) + '\n' + await page.locator('body').innerText() + '\n' + JSON.stringify({ requests, errors }, null, 2))
  await page.screenshot({ path: out + '/failure.png', fullPage: true })
  throw error
} finally { await browser.close() }
