import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { pathToFileURL } from 'node:url'
const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE || 'C:/Users/25490/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs').href)
const base = process.env.QA_BASE_URL || 'http://127.0.0.1:5391'
assert.equal(new URL(base).hostname, '127.0.0.1')
const out = 'outputs/fob-discount-browser'
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
const saved = [], requests = [], errors = []
let ruleLoads = 0
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const page = await browser.newPage({ viewport: { width: 1600, height: 1100 }, locale: 'zh-CN' })
page.on('pageerror', error => errors.push(error.message))
page.on('dialog',d=>d.accept())
await page.addInitScript(() => { Object.defineProperty(navigator.clipboard,'writeText',{value:async text=>{window.__qaClipboard=text}}) })
try {
  await page.route('**/api/v1/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname.replace('/api/v1', '')
    requests.push(req.method() + ' ' + path + url.search)
    let data
    if (path === '/auth/me') data = { id: 'qa-user', account: 'LOCALQA', name: '本地验收', role: 'super_admin', permissions: ['quote','purchase','logistics','finance','allRecords','permissions'], mustChangePassword: false }
    else if(path.startsWith('/fob-pricing/')) data = {
      sku:'FOB-DISCOUNT-TEST',source:'standard',category:'本地示例，不用于业务',weight:'100g',classification:url.searchParams.get('classification'),invoiceType:'专票',
      invoicePricing:{mode:'actual-tax-point',basePurchaseCny:500,policyVersion:5,effectiveTaxPoint:.1,verificationRequired:false,ruleSource:'procurement-tax-point'},
      parsed:{minOrderQty:1,orderMultiple:1,freight:{quantity:100,totalFreightCny:250,unitFreightCny:2.5,basis:'本地示例',estimated:false},priceTiers:[{minQty:1,maxQty:null,unit:'件',unitPriceCny:500}]},notices:[],
      pricing:{version:1,policyVersion:5,rate:6.63,fobCoefficient:1.2,rows:[{minQty:1,maxQty:null,unit:'件',purchaseCny:500,taxIncludedCny:550,freightCny:2.5,costCny:552.5,thresholdQty:1,declaredUsd:'100.00',undeclaredUsd:'100.00',smallOrderUsd:'100.18'}]}
    }
    else if(path === '/quotations/local-save') data={...saved[0],primarySku:saved[0].fob.product.sku,lifecycleState:'active'}
    else if(path === '/quotations/fob/preview') data = req.postDataJSON().fob
    else if(path === '/quotations/batch') {saved.push(req.postDataJSON());data={batchNo:'LOCAL-BATCH',records:[{id:'b1',no:'B1',primarySku:'FOB-DISCOUNT-TEST',quoteMode:'fob'}]}}
    else if (path === '/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'local-only' }
    else if (path === '/finance-settings') data = finance
    else if (path === '/quotation-readiness') data = { ready: true, missing: [] }
    else if (path === '/quotation-drafts/mine/state') data = { exists: false }
    else if (path === '/quotation-templates') data = [template]
    else if (path === '/personal-quotation-customers') data = []
    else if (path === '/purchase-products/TC2602577') data = product
    else if (path === '/logistics/published/manifest') data = { revision: 'test', publishedChannels: 1, countries, attributes: ['纯电'] }
    else if (path === '/logistics/published/rules' || path === '/logistics/published/catalog') {
      const requested = url.searchParams.getAll('country')
      const hasDestination = ++ruleLoads > 1 && (requested.some(c => ['美国', '英国', 'US', 'GB'].includes(c)) || path.endsWith('/catalog'))
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
  await page.goto(base + '/quotation')
  await page.getByLabel('报价模式',{exact:true}).selectOption('fob')
  const discount=page.getByLabel('FOB折扣',{exact:true})
  assert.equal(await discount.inputValue(),'1')
  await page.screenshot({path:out+'/default-discount.png',fullPage:true})
  await page.getByLabel('FOB查询SKU').fill('FOB-DISCOUNT-TEST')
  await page.getByRole('button',{name:'查询报价',exact:true}).click()
  await page.locator('.final-quote strong').filter({hasText:'$100.00'}).waitFor()
  await discount.fill('0.95')
  await page.locator('.final-quote strong').filter({hasText:'$95.00'}).waitFor()
  await page.getByRole('button',{name:'复制当前数量报价',exact:true}).click()
  assert((await page.evaluate(()=>window.__qaClipboard)).includes('95.00'))
  await page.getByLabel('FOB客户名称').fill('本地折扣验收，不用于业务')
  await page.getByRole('button',{name:'保存FOB报价',exact:true}).click()
  await page.getByText('已保存：LOCAL-QA-ONLY',{exact:false}).waitFor()
  assert.equal(saved[0].fob.discount,.95)
  assert.equal(saved[0].fob.sheet.rows[0].prices[0],95)
  await page.screenshot({path:out+'/discount-095.png',fullPage:true})
  for(const bad of ['', '0','-1','1.01','abc']) {
    await discount.fill(bad)
    assert.equal(await discount.getAttribute('aria-invalid'),'true')
    assert(await page.getByRole('button',{name:'保存FOB报价',exact:true}).isDisabled())
  }
  await discount.fill('1')
  await page.locator('.final-quote strong').filter({hasText:'$100.00'}).waitFor()
  await discount.fill('0.95')
  await page.getByRole('button',{name:'批量粘贴报价',exact:true}).click()
  const dialog=page.getByRole('dialog')
  await dialog.locator('textarea').fill('FOB-DISCOUNT-TEST 1\nFOB-DISCOUNT-TEST 10')
  await dialog.getByRole('button',{name:'查询粘贴清单',exact:true}).click()
  await dialog.getByRole('button',{name:/生成2行/}).click()
  await dialog.getByRole('button',{name:/条FOB报价记录/}).waitFor()
  await page.screenshot({path:out+'/batch-discount.png',fullPage:true})
  await dialog.getByRole('button',{name:'关闭批量报价',exact:true}).click()
  const originalSnapshot=JSON.stringify(saved[0])
  await page.goto(base+'/quotation?reissue=local-save')
  await page.locator('.final-quote strong').filter({hasText:'$95.00'}).waitFor()
  assert.equal(await discount.inputValue(),'0.95')
  assert.equal(JSON.stringify(saved[0]),originalSnapshot)
  for(const classification of ['ordinary-fob','rebate-cfr-cif','rebate-fob']) {
    await page.getByLabel('报价分类',{exact:true}).selectOption(classification)
    await page.getByRole('button',{name:'查询报价',exact:true}).click()
    await page.locator('.final-quote strong').filter({hasText:'$95.00'}).waitFor()
  }
  await page.getByLabel('报价模式',{exact:true}).selectOption('single')
  assert.equal(await discount.count(),0)
  assert.equal(await page.getByLabel('佣金阈值',{exact:true}).inputValue(),'1')
  await page.getByLabel('报价模式',{exact:true}).selectOption('fob')
  assert.equal(await discount.inputValue(),'1')
  assert.deepEqual(errors,[])
  await writeFile(out+'/report.json',JSON.stringify({saved,requests,errors,checks:['default 1','100 × .95 = 95','save snapshot','invalid values blocked','return to 1','batch preview']},null,2))
  console.log('FOB browser checks passed')
} catch(error) {await page.screenshot({path:out+'/failure.png',fullPage:true});console.log(await page.locator('body').innerText());throw error}
finally {await browser.close()}
