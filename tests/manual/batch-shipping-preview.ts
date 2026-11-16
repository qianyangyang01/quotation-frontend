// Real application + deterministic local API fixtures. All writes stay in this browser origin.
import type { BatchSingleDocument } from '../../src/services/batchSingleQuotation'
import type { QuotationRecord } from '../../src/data/quotationRecords'
if (location.hostname !== '127.0.0.1' || location.port !== '5187') throw new Error('Local fixture only')
const storeKey = 'batch-shipping-local-preview-v1'
const state: { records: QuotationRecord[]; submissions: Record<string, unknown>; batches: BatchSingleDocument[] } = JSON.parse(localStorage.getItem(storeKey) || '{"records":[],"submissions":{},"batches":[]}')
const requests: string[] = [], unexpected: string[] = []
const persist = () => localStorage.setItem(storeKey, JSON.stringify(state))
const relation = (id: number, carrier: string, code: string) => ({ carrier, channel: '本地演示专线' + code, channelCode: code })
const specifications = [{ id: 9910, carrier: '燕文', code: 'DEMO-A', countries: [['美国', 'US']] }, { id: 9911, carrier: '容鼎', code: 'DEMO-B', countries: [['美国', 'US']] }, { id: 9912, carrier: '递四方', code: 'DEMO-D', countries: [['英国', 'GB']] }]
const rules = specifications.map((item, index) => ({ id: item.id, name: '本地实重规则' + item.code, billingVerified: true, status: '启用', logisticsChannelId: 'local-channel-' + item.id, logisticsVersionId: 'local-version-' + item.id,
  relations: [relation(item.id, item.carrier, item.code)], prices: item.countries.map(([name, code]) => ({ areaName: name, countryCode: code, weightFromKg: 0, weightToKg: 10, pricingModel: 'per-kg', quoteReady: true, minChargeWeightKg: 0, startWeightKg: 0, firstWeightKg: 0, firstWeightPrice: 0, nextWeightKg: 0, nextWeightPrice: 0, pricePerKg: 50 + index * 5, registrationFee: 5 + index, intervalWeightKg: 0, intervalPrice: 0, surcharge: 0, fuelSurchargeRate: 0, volumetric: false, zoneName: '', prohibitedMarks: '', allowedMarks: '', deliveryDays: '7-12' })) }))
const countryList = [{ country: '美国', code: 'US' }, { country: '英国', code: 'GB' }]
const fees = (amount: number) => ({ countries: countryList.map((c, index) => ({ country: c.country, fixedFeeUsd: amount, selected: true, enabled: true, sortOrder: index + 1 })), providers: specifications.map(item => ({ provider: item.carrier, mode: 'taxable', selected: true, channels: [] })), updatedAt: 'local' })
const values = {
  'country-classification': countryList.map((c, index) => ({ country: c.country, enabled: true, stage: 'common', sortOrder: index + 1 })),
  'channel-policies': [{ id: '普货', category: '普货', enabled: true, countryRules: countryList.map(c => ({ country: c.country, allowedChannels: specifications.filter(s => s.countries.some(([name]) => name === c.country)).map(s => `${s.id}::${s.carrier}::${s.code}`) })) }],
  'customer-grades': [{ grade: 'S', coefficient: 1.2, enabled: true }], 'exchange-rate': { usdCny: 6.63, eurUsd: 1.1, updatedAt: 'local' },
  'tax-settings': fees(.3), 'surcharge-settings': fees(.2), 'customer-operation-fees': { customers: [] },
}
const finance = Object.fromEntries(Object.entries(values).map(([key, value]) => [key, { value, _version: 1 }])), versions = Object.fromEntries(Object.keys(values).map(key => [key, 1]))
const templates = [{ id: 'local-three', name: '美国＋英国 · 3条演示渠道', ownerAccount: 'LOCALQA', ownerName: '本地验收', ownerKey: 'ACCOUNT:LOCALQA', createdAt: '2026-11-16', updatedAt: '2026-11-16', items: specifications.map(s => ({ country: s.countries[0]![0], countryCode: s.countries[0]![1], channelKey: `${s.id}::${s.carrier}::${s.code}`, ruleId: s.id, rule: '本地实重规则' + s.code, carrier: s.carrier, transport: '本地演示专线' + s.code, channelCode: s.code })) }]
const assets: Record<string, string> = JSON.parse(localStorage.getItem(storeKey + '-photos') || '{}')
const originalFetch = window.fetch.bind(window)
window.fetch = async (url, init = {}) => {
  const u = new URL(typeof url === 'string' ? url : url instanceof URL ? url.href : url.url, location.href)
  if (u.origin !== location.origin) throw new Error('本地预览禁止访问外部接口')
  if (!u.pathname.startsWith('/api/')) return originalFetch(url, init)
  const path = u.pathname.replace('/api/v1', ''), method = init.method || 'GET'
  requests.push(method + ' ' + path)
  let data: unknown, status = 200
  const body = init.body && !(init.body instanceof FormData) ? JSON.parse(String(init.body)) : {}
  const headers = new Headers(init.headers), key = headers.get('Idempotency-Key') || ''
  const makeRecord = (input: BatchSingleDocument['items'][number]['input'], batch?: QuotationRecord['quotationBatch']) => {
    const n = state.records.length + 1, now = new Date().toISOString()
    const record = { ...structuredClone(input), id: 'local-' + n, no: 'Q-LOCAL-' + String(n).padStart(4, '0'), salespersonName: '本地验收', salespersonAccount: 'LOCALQA', status: 'pending', financeReviewStatus: 'pending', _version: 0, _reviewVersion: 0, createdAt: now, updatedAt: now, revisions: [], lifecycleState: 'active', ...(batch ? { quotationBatch: batch } : {}) } as QuotationRecord
    state.records.push(record); return record
  }
  if (path === '/quotation-sheet-photos' && init.body instanceof FormData) {
    const file = init.body.get('file') as File, id = crypto.randomUUID()
    assets[id] = await new Promise<string>(resolve => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result)); reader.readAsDataURL(file) })
    localStorage.setItem(storeKey + '-photos', JSON.stringify(assets)); data = { assetId: id, name: file.name }
  }
  else if (path.startsWith('/assets/')) { const saved = assets[path.split('/')[2]!]; if (!saved) return new Response('Not found', { status: 404 }); return originalFetch(saved) }
  else if (path === '/auth/me') data = { id: 'local-user', account: 'LOCALQA', name: '本地验收', role: 'employee', permissions: ['quote', 'myRecords'], mustChangePassword: false }
  else if (path === '/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'local-only' }
  else if (path === '/finance-settings') data = finance
  else if (path === '/quotation-readiness') data = { ready: false, purchase: { ready: false }, logistics: { ready: true }, finance: { ready: true }, missing: ['本地无采购数据'] }
  else if (path === '/quotation-drafts/mine/state') data = { exists: false }
  else if (path === '/quotation-templates') data = templates
  else if (path === '/purchase-products/deleted-snapshots') data = []
  else if (path === '/purchase-products') data = { items: [], page: 0, size: 100, total: 0, totalPages: 0 }
  else if (path === '/personal-quotation-customers') data = []
  else if (path === '/logistics/published/manifest') data = { revision: 'local-shipping', publishedChannels: 3, countries: countryList.map(c => ({ name: c.country, code: c.code })), attributes: ['普货'] }
  else if (path === '/logistics/published/rules' || path === '/logistics/published/catalog') data = { revision: 'local-shipping', rules }
  else if (path === '/quotation-sync') data = { purchaseVersions: {}, logisticsRevision: 'local-shipping', financeVersions: versions }
  else if (path === '/quotation-sync/logistics') data = { revision: 'local-shipping' }
  else if (path === '/quotations/batch' && method === 'POST') {
    if (state.submissions[key]) data = state.submissions[key]
    else {
      const document = body.document as BatchSingleDocument
      if (body.kind !== 'shipping-only' || !document?.items.length || document.items.some(item => item.input.quoteMode !== 'shipping-only')) throw new Error('本地批量代发请求格式错误')
      const batchId = 'local-batch-' + (state.batches.length + 1), batchNo = 'B-LOCAL-' + String(state.batches.length + 1).padStart(3, '0')
      const records = document.items.map((item, index) => makeRecord(item.input, { id: batchId, no: batchNo, index: index + 1, count: document.items.length }))
      data = { batchId, batchNo, records: records.map(r => ({ id: r.id, no: r.no, primarySku: r.primarySku, quoteMode: r.quoteMode })) }
      state.batches.push(structuredClone(document)); state.submissions[key] = data; persist()
    }
  }
  else if (path === '/quotations' && method === 'POST') { data = state.submissions[key] || makeRecord(body); state.submissions[key] = data; persist() }
  else if ((path === '/quotations' || path === '/quotations/search') && method === 'GET') {
    let records = [...state.records].reverse()
    const term = (u.searchParams.get('product') || u.searchParams.get('q') || u.searchParams.get('search') || '').toLowerCase()
    if (term) records = records.filter(r => JSON.stringify(r).toLowerCase().includes(term))
    const size = Number(u.searchParams.get('size') || 10), page = Number(u.searchParams.get('page') || 0)
    data = { items: records.slice(page * size, (page + 1) * size), page, size, total: records.length, totalPages: Math.ceil(records.length / size), summary: { total: records.length, pending: records.length, won: 0, lost: 0 }, countries: ['美国', '英国'] }
  }
  else if (path === '/quotations/review-status') data = state.records.filter(r => u.searchParams.get('ids')?.split(',').includes(r.id))
  else if (path.endsWith('/batch-records')) { const record = state.records.find(r => r.id === path.split('/')[2]); data = state.records.filter(r => r.quotationBatch?.id === record?.quotationBatch?.id) }
  else if (path.endsWith('/review-history')) data = []
  else if (/^\/quotations\/local-\d+$/.test(path)) {
    const record = state.records.find(r => r.id === path.split('/')[2])
    if (!record) status = 404
    else if (method === 'PATCH') {
      if (body._version !== record._version) status = 409
      else { Object.assign(record, body, { _version: record._version! + 1, updatedAt: new Date().toISOString() }); persist() }
    }
    data = record
  }
  else if (path.startsWith('/review-notifications')) data = { items: [], total: 0, unread: [], counts: {}, page: 0, totalPages: 0, withdrawnEdit: null }
  else if (path.includes('/comments')) data = { items: [], total: 0 }
  else { status = 400; unexpected.push(method + ' ' + path); data = null; console.error('Unimplemented local fixture', path) }
  return new Response(JSON.stringify({ code: status === 200 ? 'OK' : 'LOCAL_FIXTURE_ERROR', message: '本地演示接口', data }), { status, headers: { 'Content-Type': 'application/json', 'X-Account': 'LOCALQA' } })
}
if (!location.pathname.startsWith('/quotation')) window.history.replaceState({}, '', '/quotation/batch-shipping')
await import('/src/main.ts')
const banner = document.createElement('div'); banner.style.cssText = 'position:fixed;bottom:0;left:0;z-index:99999;background:#263344;color:white;padding:5px 12px;font-size:12px'; banner.textContent = '本地功能预览 · 演示价格 · 保存仅在本机浏览器 · 不连接生产'; document.body.append(banner)
;(window as unknown as { shippingFixture: unknown }).shippingFixture = { state, requests, unexpected }
