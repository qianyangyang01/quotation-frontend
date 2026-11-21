import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { pathToFileURL } from 'node:url'
import Decimal from 'decimal.js'
const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE || 'C:/Users/25490/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs').href)
const base = process.env.QA_DEPLOYED_URL || process.env.QA_BASE_URL || 'http://127.0.0.1:5397'
assert(['127.0.0.1', ...(process.env.QA_DEPLOYED_URL ? ['vip.ahmln.com'] : [])].includes(new URL(base).hostname))
const testAverage = process.env.QA_AVERAGE === '1'
const out = process.env.QA_OUTPUT || 'outputs/compact-regression/save-' + (testAverage ? 'average' : process.env.QA_FEATURES === '1' ? 'features' : 'baseline')
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
const rules = [rule]
if(testAverage) {
  const secondKey='9911::云速递::TEST2'
  const second={...structuredClone(rule),id:9911,name:'本地第二渠道',logisticsChannelId:'test-channel-2',logisticsVersionId:'test-version-2',relations:[{carrier:'云速递',channel:'本地第二渠道',channelCode:'TEST2'}],prices:[{...rule.prices[0],pricePerKg:70}]}
  rules.push(second)
  values['channel-policies'][0].countryRules.forEach(country=>country.allowedChannels.push(secondKey))
  template.items.push({country:'美国',countryCode:'US',channelKey:secondKey,ruleId:9911,rule:second.name,carrier:'云速递',transport:second.name,channelCode:'TEST2'})
}
const saved = [], requests = [], errors = []
let ruleLoads = 0
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const page = await browser.newPage({ viewport: { width: 1600, height: 900 }, locale: 'zh-CN', permissions: ['clipboard-read', 'clipboard-write'] })
page.on('pageerror', error => errors.push(error.message))
try {
  await page.route('**/api/v1/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname.replace('/api/v1', '')
    requests.push(req.method() + ' ' + path + url.search)
    let data
    if (path === '/auth/me') data = { id: 'qa-user', account: 'LOCALQA', name: '本地验收', role: 'super_admin', permissions: ['quote','purchase','logistics','finance','allRecords','permissions'], mustChangePassword: false }
    else if (path === '/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'local-only' }
    else if (path === '/finance-settings') data = finance
    else if (path === '/quotation-readiness') data = { ready: true, missing: [] }
    else if (path === '/quotation-drafts/mine/state') data = { exists: false }
    else if (path === '/quotation-templates') data = [template]
    else if (path === '/personal-quotation-customers') data = []
    else if (path === '/purchase-products/TC2602577') data = product
    else if (path === '/logistics/published/manifest') data = { revision: 'test', publishedChannels: rules.length, countries, attributes: ['纯电'] }
    else if (path === '/logistics/published/rules' || path === '/logistics/published/catalog') {
      const requested = url.searchParams.getAll('country')
      const hasDestination = ++ruleLoads > 1 && (requested.some(c => ['美国', '英国', 'US', 'GB'].includes(c)) || path.endsWith('/catalog'))
      if (hasDestination) await new Promise(resolve => setTimeout(resolve, 500))
      data = { revision: 'test', rules: hasDestination ? rules : [] }
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
  await page.locator('.customer-field input').fill('本地回归测试，不用于业务')
  await page.locator('.sku-field input').fill('TC2602577')
  await page.locator('.logistics-field select').selectOption('纯电')
  await page.getByRole('button', { name: '查询商品', exact: true }).click()
  await page.waitForFunction(() => document.body.innerText.includes('当前条件没有已发布物流渠道'))
  assert.equal(await page.getByRole('button', { name: '首选', exact: true }).count(), 0)
  await page.locator('.template-toolbar select').selectOption('local-pure')
  await page.getByRole('button', { name: '⚡ 一键应用', exact: true }).click()
  await page.getByRole('button', { name: '首选', exact: true }).waitFor()
  assert.equal(await page.getByRole('button', { name: '首选', exact: true }).count(), 1)
  assert.equal(saved.length, 0, 'Synchronizing the primary must not write a quotation')
  await page.screenshot({ path: out + '/auto-primary.png', fullPage: true })
  // Explicitly choose the other channel; recalculating quantities must preserve it.
  await page.locator('article').filter({hasText:'英国'}).getByRole('button', { name: '设为首选', exact: true }).click()
  const chosen = await page.getByRole('button', { name: '首选', exact: true }).locator('xpath=ancestor::article').innerText()
  assert(chosen.includes('英国'))
  await page.getByRole('button', { name: '⚡ 一键应用', exact: true }).click()
  await page.waitForFunction(count => document.querySelectorAll('.selected-channels section').length === count,testAverage?3:2)
  assert((await page.getByRole('button', { name: '首选', exact: true }).locator('xpath=ancestor::article').innerText()).includes('英国'))
  if (process.env.QA_FEATURES === '1') {
    const copy=page.getByRole('button',{name:'复制图文表格',exact:true}).first()
    await page.evaluate(()=>{window.qaClipboardWrite=navigator.clipboard.write.bind(navigator.clipboard);navigator.clipboard.write=async()=>{throw new DOMException('QA denied','NotAllowedError')}})
    await copy.click()
    await page.getByText('图文表格未复制成功，请允许剪贴板权限后重试',{exact:true}).waitFor()
    assert.equal(await copy.isEnabled(),true,'Clipboard error must release busy state')
    assert.equal(await page.getByRole('button',{name:/保存 1 张报价单/}).isEnabled(),true)
    await page.evaluate(()=>navigator.clipboard.write=window.qaClipboardWrite)
    await copy.click()
    await page.getByText('图文表格已复制，可直接粘贴到 WPS；同 SKU 和商品图片为合并单元格',{exact:true}).waitFor()
    const html=await page.evaluate(async()=>{const items=await navigator.clipboard.read();return (await items[0].getType('text/html')).text()})
    assert.ok(html.includes('TC2602577'));assert.ok(html.includes('United Kingdom'))
    await writeFile(out+'/clipboard.html',html)
    const sheet=page.locator('.customer-sheet').first()
    await sheet.locator('[data-group-handle="country"]').press('ArrowLeft')
    await sheet.getByRole('button',{name:'第 1 行排序，上下键移动',exact:true}).press('ArrowDown')
    await copy.click()
    await page.getByText('图文表格已复制，可直接粘贴到 WPS；同 SKU 和商品图片为合并单元格',{exact:true}).waitFor()
    await page.evaluate(()=>window.scrollTo(0,document.body.scrollHeight))
    const top=page.getByRole('button',{name:'回到顶部',exact:true}).first()
    await top.waitFor({state:'visible'})
    const box=await top.boundingBox(),before=await page.evaluate(()=>window.scrollY)
    await page.mouse.move(box.x+20,box.y+20);await page.mouse.down()
    await page.mouse.move(box.x-150,box.y-80,{steps:10});await page.mouse.up()
    assert.equal(await page.evaluate(()=>window.scrollY),before)
    assert.equal(saved.length,0,'Copy/reorder/drag must not save automatically')
  }
  if(testAverage) {
    await page.getByRole('button',{name:'渠道平均报价',exact:true}).click()
    const us=await page.getByLabel('平均报价国家').locator('option').evaluateAll(options=>options.find(option=>option.textContent.includes('美国')).value)
    await page.getByLabel('平均报价国家').selectOption(us)
    await page.getByRole('button',{name:'加权平均',exact:true}).click()
    for(const input of await page.locator('.average-channel input[type=checkbox]').all())await input.check()
    const weights=page.locator('.average-weight')
    await weights.nth(0).fill('30');await weights.nth(1).fill('50')
    const save=page.getByRole('button',{name:/保存 1 张报价单/})
    if(await save.isEnabled()) {
      await save.click()
      // Save preparation awaits finance/logistics checks; do not edit the inputs
      // until that attempt has reached the sheet validator and returned.
      await page.locator('.toast').filter({hasText:'权重合计必须为 100%'}).waitFor()
      await page.waitForFunction(()=>!document.querySelector('.quote-preview footer .save')?.disabled)
    }
    assert.equal(saved.length,0,'Incomplete weights must not submit an API save')
    await weights.nth(1).fill('70')
    await page.locator('.sheet-average-row').waitFor()
    assert.ok((await page.locator('.sheet-editor tbody tr').first().innerText()).includes('United States'))
    await page.screenshot({path:out+'/average-before-save.png',fullPage:true})
  }
  await page.getByRole('button', { name: /保存 1 张报价单/ }).click()
  await page.waitForFunction(() => document.body.innerText.includes('报价已保存'))
  assert.equal(saved.length, 1)
  await writeFile(out+'/submitted.json',JSON.stringify(saved[0],null,2))
  assert.equal(saved[0].primarySku, 'TC2602577')
  assert.equal(saved[0].country, '英国')
  assert.equal(saved[0].quoteOptions.filter(r => r.isPrimary).length, 1)
  assert.equal(saved[0].quoteOptions.find(r => r.isPrimary).country, '英国')
  if(testAverage) {
    const snapshot=saved[0].customerQuote,plan=snapshot.averagePlans[0]
    assert.equal(snapshot.averagePlans.length,1);assert.equal(plan.mode,'weighted')
    assert.deepEqual(plan.members.map(member=>member.weight),[30,70])
    assert.equal(snapshot.rowOrder[0],'average:'+plan.id)
    const expected=snapshot.quantities.map((_,i)=>plan.members.reduce((sum,member)=>{
      const source=saved[0].systemQuantityQuotes.rows.find(row=>row.optionId===member.optionId)
      assert.ok(source);assert.equal(member.sourcePrices[i],source.prices[i])
      return sum.plus(new Decimal(source.prices[i]).mul(member.weight).div(100))
    },new Decimal(0)).toDecimalPlaces(2,Decimal.ROUND_HALF_UP).toNumber())
    assert.deepEqual(plan.systemPrices,expected);assert.deepEqual(plan.prices,expected)
    assert.deepEqual(snapshot.rows.map(row=>row.prices),saved[0].systemQuantityQuotes.rows.map(row=>row.prices))
  }
  assert(!requests.some(r => /^(PUT|PATCH|DELETE) /.test(r)), 'No history/template/draft mutation allowed')
  assert.deepEqual(errors, [])
  await page.screenshot({ path: out + '/saved.png', fullPage: true })
  await writeFile(out + '/result.json', JSON.stringify({ result: 'passed', saved, requests, errors }, null, 2))
  console.log('PASS: calculated quote, optional copy failure/retry/reorder/drag, one explicit save, no history mutation')
} catch (error) {
  await writeFile(out + '/failure.txt', String(error) + '\n' + await page.locator('body').innerText() + '\n' + JSON.stringify({ requests, errors }, null, 2))
  await page.screenshot({ path: out + '/failure.png', fullPage: true })
  throw error
} finally { await browser.close() }
