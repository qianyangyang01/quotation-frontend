// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import View from './QuotationSystemView.vue'
import type { BundleQuoteItem, QuotationProduct } from '@/components/quotation/types'
import { api } from '@/services/http'
import { clearFinanceSettingsCache } from '@/services/financeSettings'
import type { QuotationDraftPayload } from '@/services/quotationDrafts'
import { replaceLogisticsRules, type LogisticsRule } from '@/data/logistics'
import type { FinanceTaxSettings } from '@/data/financeTaxSettings'

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ replace: vi.fn() }), onBeforeRouteLeave: vi.fn() }))
vi.mock('@/services/quotationSync', async original => ({ ...await original<typeof import('@/services/quotationSync')>(), startQuotationSync: vi.fn(() => vi.fn()) }))
vi.mock('@/data/publishedLogisticsRepository', async original => ({
  ...await original<typeof import('@/data/publishedLogisticsRepository')>(),
  loadPublishedLogisticsManifest: vi.fn().mockResolvedValue({ verified: true, manifest: { revision: 'test' } }),
  loadPublishedLogisticsRules: vi.fn().mockResolvedValue({ verified: true, revision: 'test', rules: [] }),
}))

const record = {
  sku: 'BK2601961-1', category: '宠物用品', catalogState: 'ready', weightG: 219,
  minOrderQty: 100, purchasePriceCny: 13.8, tier2MinQty: 500, tier2PriceCny: 12.5,
  tier3MinQty: 1000, tier3PriceCny: 11.5, taxPoint: .02, invoiceType: '普票', freight10Cny: 2.1,
}
let app: App | undefined
let host: HTMLDivElement
let purchaseOverride: Record<string, unknown> = {}
let state: {
  queryProduct: () => Promise<void>
  queryBundleItems: () => Promise<void>
  purchaseInvoiceNotice: string[]
  purchaseTaxBlockReason: string
  purchaseQueryError: string
  draftReady: boolean
  products: QuotationProduct[]
  bundleItems: BundleQuoteItem[]
  monthlySalesEstimate: string
  bundlePurchaseCost: (sets?: number) => number
  bundleDomesticFreight: (sets?: number) => number
  salePrice: (product: QuotationProduct) => number
  flushDraft: () => Promise<void>
  draftPayload: () => QuotationDraftPayload
  applyDraftPayload: (payload: QuotationDraftPayload, purchases?: undefined, options?: { restoreQuotation?: boolean }) => Promise<void>
  updateBundleItemQuantity: (item: BundleQuoteItem) => void
  queryBundleItem: (item: BundleQuoteItem, options: { loadLogistics: boolean; announce: boolean }) => Promise<boolean>
  changeQuoteMode: (mode: 'single' | 'bundle') => void
  addBundleItem: () => void
  removeBundleItem: (id: number) => void
  hasQueriedQuotationProduct: boolean
  saveValidationIssues: Array<{ key: string; message: string }>
  financeTaxSettings: FinanceTaxSettings
  financeSurchargeSettings: FinanceTaxSettings
  customerGradeSettings: Array<{ grade: string; coefficient: number }>
  exchange: { usd: number; eurUsd: number }
  bundleGoodsWeight: (sets?: number) => number
  quantityCostBreakdown: (product: QuotationProduct, rule: string, sets: number, country: string, provider: string, region: string, channelKey: string) => { freight: number; cost: number; quoteUsd: number; quoteCny: number; tax: { configured: boolean; surchargeUsd: number } } | null
}
beforeEach(() => { vi.clearAllMocks(); clearFinanceSettingsCache(); purchaseOverride = {} })
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); vi.restoreAllMocks(); clearFinanceSettingsCache(); replaceLogisticsRules([]) })

async function mount(mode: 'single' | 'bundle', estimate = '10', tiers: Array<string | undefined> = [], secondSku = 'SINGLE', omitProductSnapshot = false, sku = record.sku) {
  vi.spyOn(api, 'get').mockImplementation(async path => {
    if (path === '/finance-settings') return {
      'country-classification': { value: [], _version: 1 }, 'channel-policies': { value: [{id:'普货',category:'普货',enabled:true,countryRules:[{country:'美国',allowedChannels:['1::测试::A']}]}], _version: 1 },
      'customer-grades': { value: [{ grade: 'S', coefficient: 1.2, enabled: true }], _version: 1 },
      'exchange-rate': { value: { usdCny: 6.7, updatedAt: 'test' }, _version: 1 },
      'tax-settings': { value: { countries: [], providers: [], updatedAt: 'test' }, _version: 1 },
      'customer-operation-fees': { value: { customers: [] }, _version: 1 },
    }
    if (path === '/quotation-readiness') return { ready: true, missing: [] }
    if (path === '/quotation-templates') return []
    if (path === '/quotation-drafts/mine/state') return { exists: true, version: 1, sourceQuote: { id: 'withdrawn', no: 'QT-WITHDRAWN', version: 1 }, payload: {
      schemaVersion: 2, quoteMode: mode, customerName: '阶梯回归', skuSearch: sku,
      selectedCustomerGrade: 'S', monthlySalesEstimate: estimate, logisticsAttribute: '普货',
      ...(omitProductSnapshot ? {} : { product: { sku, quantity: 1, purchaseInvoiceTaxApplied: true } }),
      bundleItems: [
        { sku, purchaseTier: tiers[0], quantityPerSet: 2, purchaseInvoiceTaxApplied: true },
        { sku: secondSku, purchaseTier: tiers[1], quantityPerSet: 1, purchaseInvoiceTaxApplied: true },
      ],
    } }
    if (path === `/purchase-products/${sku}`) return { ...record, ...purchaseOverride, sku }
    if (path === '/purchase-products/BK2601961') return { ...record, sku: 'BK2601961', purchasePriceCny: 20, tier2PriceCny: 18 }
    if (path === '/purchase-products/SINGLE') return { sku: 'SINGLE', category: '宠物用品', weightG: 100, minOrderQty: 100, purchasePriceCny: 20, taxPoint: .02, freight10Cny: 5 }
    throw new Error(`Unexpected API: ${path}`)
  })
  vi.spyOn(api, 'put').mockResolvedValue({ exists: true, version: 2, updatedAt: 'test' })
  host = document.createElement('div'); document.body.append(host)
  app = createApp(View)
  const vm = app.mount(host)
  state = (vm.$ as unknown as { setupState: typeof state }).setupState
  await vi.waitFor(() => expect(state.draftReady).toBe(true))
  if (mode === 'single') await state.queryProduct()
  else await state.queryBundleItems()
  await nextTick()
}

async function selectTier(value: string) {
  const select = host.querySelector<HTMLSelectElement>('[data-validation-field="monthlySalesEstimate"] select')!
  expect([...select.options].map(option => option.text)).toEqual(['阶梯价1', '阶梯价2', '阶梯价3'])
  select.value = value
  select.dispatchEvent(new Event('change', { bubbles: true }))
  await nextTick()
}

it.each(['', '少量现货，7天补货', '无货'])('keeps optional stock notes in single and bundle quotations without changing costs: %s', async stockStatus => {
  purchaseOverride = { stockStatus }
  await mount('single')
  expect(state.products[0]!.stockStatus).toBe(stockStatus)
  expect(state.products[0]!.purchase).toBe(14.08)
  expect(state.purchaseQueryError).toBe('')
  state.changeQuoteMode('bundle'); await nextTick()
  state.bundleItems[0]!.sku = record.sku
  expect(await state.queryBundleItem(state.bundleItems[0]!, { loadLogistics: false, announce: false })).toBe(true)
  await nextTick()
  expect(state.bundleItems[0]!.stockStatus).toBe(stockStatus)
  expect(state.bundleItems[0]!.purchaseUnitPrice).toBe(14.08)
  if (stockStatus) expect(host.textContent).toContain(stockStatus)
})

it('queries every bundle SKU from the detail toolbar and retains row queries and add action', async () => {
  await mount('bundle')
  expect(host.querySelector('.condition-card')?.textContent).not.toContain('查询全部 SKU')
  const buttons = Array.from(host.querySelectorAll<HTMLButtonElement>('.bundle-actions button'))
  expect(buttons.map(button => button.textContent)).toEqual(['＋ 添加 SKU', '查询全部 SKU'])
  const get = vi.mocked(api.get); get.mockClear()
  buttons[1]!.click()
  await vi.waitFor(() => expect(get.mock.calls.filter(([path]) => String(path).startsWith('/purchase-products/'))).toHaveLength(2))
  await vi.waitFor(() => expect(state.bundleItems.every(item => item.status === '采购资料已加载')).toBe(true))
  get.mockClear()
  host.querySelector<HTMLButtonElement>('[aria-label="查询第1行 SKU"]')!.click()
  await vi.waitFor(() => expect(get.mock.calls.filter(([path]) => String(path).startsWith('/purchase-products/'))).toHaveLength(1))
  const count = state.bundleItems.length
  buttons[0]!.click(); await nextTick()
  expect(state.bundleItems).toHaveLength(count + 1)
})

it('updates the single SKU cost and quotation immediately when selecting tier 2 or tier 3', async () => {
  await mount('single')
  const product = state.products[0]!
  expect(product.purchase).toBe(14.08)
  const before = state.salePrice(product)
  await selectTier('100')
  expect(product.purchaseBaseUnitPrice).toBe(12.5)
  expect(product.purchase).toBe(12.75)
  expect(product.purchaseFreightPerUnit).toBeCloseTo(.21)
  expect(state.salePrice(product)).toBeCloseTo(before + (12.75 - 14.08) * 1.2)
  expect(host.querySelector('.tier-match')?.textContent).toContain('阶梯价2（500–999件）原价 ¥12.50 × 1.02（普票）= 计入成本 ¥12.75')
  await selectTier('100+')
  expect(product.purchase).toBe(11.73)
  expect(host.querySelector('.tier-match')?.textContent).toContain('阶梯价3（1000件起）')
  await selectTier('10')
  expect(product.purchase).toBe(14.08)
})

it('recalculates a restored tier 2 draft with current purchase tiers and keeps the persisted option value', async () => {
  await mount('single', '100')
  expect(state.monthlySalesEstimate).toBe('100')
  expect(state.products[0]!.purchase).toBe(12.75)
  expect(host.querySelector<HTMLSelectElement>('[data-validation-field="monthlySalesEstimate"] select')!.value).toBe('100')
  expect(host.querySelector('.tier-match')?.textContent).toContain('阶梯价2（500–999件）')
})

async function selectBundleTier(index: number, value: string) {
  const select = host.querySelectorAll<HTMLSelectElement>('.purchase-tier')[index]!
  select.value = value
  select.dispatchEvent(new Event('change', { bubbles: true }))
  await nextTick()
}

it('changes only the selected bundle SKU and explains missing tiers without changing domestic freight', async () => {
  await mount('bundle')
  const freight = state.bundleDomesticFreight(3)
  expect(host.querySelector('[data-validation-field="monthlySalesEstimate"] select')).toBeNull()
  await selectBundleTier(0, '100')
  expect(state.bundleItems.map(item => item.purchaseUnitPrice)).toEqual([12.75, 20.4])
  expect(state.bundlePurchaseCost(3)).toBe(137.7)
  expect(state.bundleDomesticFreight(3)).toBe(freight)
  await selectBundleTier(1, '100')
  const labels = [...host.querySelectorAll('.purchase-price small')].map(item => item.textContent)
  expect(labels[0]).toContain('阶梯价2（500–999件）原价 ¥12.50')
  expect(labels[1]).toContain('阶梯价2未配置，采用阶梯价1（100件起）')
})

it('keeps 1961 at tier 1 and 1961-1 at tier 2 through quantity changes, requery and mode switches', async () => {
  await mount('bundle', '10', [], 'BK2601961')
  await selectBundleTier(0, '100')
  expect(state.bundleItems.map(item => item.purchaseTier)).toEqual(['100', '10'])
  expect(state.bundleItems.map(item => item.purchaseUnitPrice)).toEqual([12.75, 20.4])
  expect(state.bundlePurchaseCost(1)).toBe(45.9)
  expect(state.bundlePurchaseCost(5)).toBe(229.5)
  const first = state.bundleItems[0]!
  first.quantityPerSet = 3
  state.updateBundleItemQuantity(first)
  expect(state.bundlePurchaseCost(1)).toBe(58.65)
  expect(await state.queryBundleItem(first, { loadLogistics: false, announce: false })).toBe(true)
  expect(first.purchaseTier).toBe('100')
  expect(first.purchaseUnitPrice).toBe(12.75)
  state.changeQuoteMode('single')
  await nextTick()
  await selectTier('100+')
  state.changeQuoteMode('bundle')
  await nextTick()
  expect(state.bundleItems.map(item => item.purchaseTier)).toEqual(['100', '10'])
  expect(state.bundlePurchaseCost(1)).toBe(58.65)
  state.addBundleItem()
  expect(state.bundleItems[2]!.purchaseTier).toBe('10')
})

it('quotes and restores one SKU with three pieces per set through the quantity input', async () => {
  await mount('bundle')
  state.removeBundleItem(state.bundleItems[1]!.id)
  await nextTick()
  const input = host.querySelector<HTMLInputElement>('.qty input')!
  input.value = '3'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  input.dispatchEvent(new Event('change', { bubbles: true }))
  await nextTick()
  expect(state.bundleItems).toHaveLength(1)
  expect(state.hasQueriedQuotationProduct).toBe(true)
  expect(state.saveValidationIssues.filter(issue => issue.key === 'sku')).toEqual([])
  expect(state.bundlePurchaseCost(2)).toBe(84.48)
  expect(state.bundleDomesticFreight(2)).toBe(1.26)
  expect(state.bundleGoodsWeight(2)).toBeCloseTo(.22338 * 6)
  const saved = JSON.parse(JSON.stringify(state.draftPayload())) as QuotationDraftPayload
  expect(saved.bundleItems).toHaveLength(1)
  expect(saved.bundleItems[0]!.quantityPerSet).toBe(3)
  state.bundleItems[0]!.quantityPerSet = 1
  state.updateBundleItemQuantity(state.bundleItems[0]!)
  await nextTick()
  expect(state.hasQueriedQuotationProduct).toBe(false)
  expect(state.saveValidationIssues.some(issue => issue.key === 'sku' && issue.message.includes('至少 2 件'))).toBe(true)
  await state.applyDraftPayload(saved, undefined, { restoreQuotation: true })
  await nextTick()
  expect(state.bundleItems).toHaveLength(1)
  expect(state.bundleItems[0]!.quantityPerSet).toBe(3)
  expect(state.hasQueriedQuotationProduct).toBe(true)
})

it('saves and restores each SKU tier independently in the actual draft payload', async () => {
  await mount('bundle', '10', [], 'BK2601961')
  await selectBundleTier(0, '100')
  await state.flushDraft()
  const call = [...vi.mocked(api.put).mock.calls].reverse().find(([path]) => path === '/quotation-drafts/mine/state')!
  const saved = JSON.parse(JSON.stringify(call[1])) as QuotationDraftPayload
  expect(saved.bundleItems.map(item => item.purchaseTier)).toEqual(['100', '10'])
  await selectBundleTier(0, '100+')
  await state.applyDraftPayload(saved, undefined, { restoreQuotation: true })
  await nextTick()
  expect(state.bundleItems.map(item => item.purchaseUnitPrice)).toEqual([12.75, 20.4])
  expect([...host.querySelectorAll<HTMLSelectElement>('.purchase-tier')].map(select => select.value)).toEqual(['100', '10'])
})

it('restores an old bundle draft with its original shared tier and lets rows diverge', async () => {
  await mount('bundle', '100')
  expect(state.bundleItems.map(item => item.purchaseTier)).toEqual(['100', '100'])
  expect(state.bundleItems[0]!.purchaseUnitPrice).toBe(12.75)
  await selectBundleTier(0, '10')
  expect(state.bundleItems.map(item => item.purchaseTier)).toEqual(['10', '100'])
})

it('retains per-SKU tiers when restoring conditions only before querying current purchases', async () => {
  await mount('bundle', '10', ['100', '10'], 'BK2601961')
  const saved = state.draftPayload()
  await state.applyDraftPayload(saved)
  expect(state.bundleItems.map(item => item.purchaseTier)).toEqual(['100', '10'])
  expect(state.bundleItems.map(item => item.purchaseUnitPrice)).toEqual([0, 0])
  await state.queryBundleItems()
  expect(state.bundleItems.map(item => item.purchaseUnitPrice)).toEqual([12.75, 20.4])
  expect(state.bundlePurchaseCost(1)).toBe(45.9)
})

it('prefers saved row tiers over the legacy shared tier and normalizes invalid row values', async () => {
  await mount('bundle', '100+', ['100', 'invalid'])
  expect(state.bundleItems.map(item => item.purchaseTier)).toEqual(['100', '100+'])
  expect(state.bundleItems[0]!.purchaseUnitPrice).toBe(12.75)
})

it('matches hand-calculated mixed-tier costs and final quotes for 1, 2, 3 and 5 sets after changing a row tier', async () => {
  // Controlled fixtures, not current production purchase or logistics prices.
  await mount('bundle', '10', ['100', '10'], 'BK2601961')
  replaceLogisticsRules([{
    id: 9910, name: '混合阶梯验算', billingVerified: true, status: '启用',
    relations: [{ carrier: '验算物流商', channel: '验算渠道', channelCode: 'TIER' }],
    prices: [{ areaName: '美国', countryCode: 'US', weightFromKg: 0, weightToKg: 30, pricingModel: 'per-kg',
      quoteReady: true, pricePerKg: 40, registrationFee: 6, minChargeWeightKg: 0,
      startWeightKg: 0, firstWeightKg: 0, firstWeightPrice: 0, nextWeightKg: 0, nextWeightPrice: 0,
      intervalPrice: 0, surcharge: 0, fuelSurchargeRate: 0, volumetric: false, zoneName: '', prohibitedMarks: '', allowedMarks: '' }],
  } as LogisticsRule])
  const fees = (fee: number): FinanceTaxSettings => ({
    countries: [{ country: '美国', fixedFeeUsd: fee, selected: true, enabled: true, sortOrder: 1 }],
    providers: [{ provider: '验算物流商', mode: 'taxable', selected: true, channels: [] }], updatedAt: 'test',
  })
  state.financeTaxSettings = fees(.3)
  state.financeSurchargeSettings = fees(.2)
  const quote = (sets: number) => state.quantityCostBreakdown(state.products[0]!, '混合阶梯验算', sets, '美国', '验算物流商', '', '9910::验算物流商::TIER')!
  // 1961-1: 12.50 x 1.02 x 2; 1961: 20.00 x 1.02 x 1 = 45.90/set.
  // Production packaging is 1g per started 50g: .219 + .005 per item, .672 kg/set.
  // Domestic freight .63/set; international freight 40/kg + 6/order.
  // Existing order: round cost x 1.2 to CNY cents, divide by 6.7, add .30 duty and round to USD cents,
  // then add .20 surcharge/order and ceil to .05 USD.
  expect(state.bundleItems.map(item => item.purchaseBaseUnitPrice)).toEqual([12.5, 20])
  expect(state.bundleItems.map(item => item.purchaseUnitPrice)).toEqual([12.75, 20.4])
  expect(state.bundleGoodsWeight(1)).toBe(.672)
  expect(state.bundleDomesticFreight(1)).toBe(.63)
  for (const [sets, cost, freight, usd, cny] of [
    [1, 79.41, 32.88, 14.75, 98.83],
    [2, 152.82, 59.76, 27.90, 186.93],
    [3, 226.23, 86.64, 41.05, 275.04],
    [5, 373.05, 140.40, 67.35, 451.25],
  ]) {
    expect(quote(sets!)).toMatchObject({ cost, freight, quoteUsd: usd, quoteCny: cny, tax: { configured: true, surchargeUsd: .2 } })
  }
  await selectBundleTier(0, '100+')
  expect(state.bundleItems.map(item => item.purchaseUnitPrice)).toEqual([11.73, 20.4])
  for (const [sets, cost, freight, usd] of [
    [1, 77.37, 32.88, 14.40], [2, 148.74, 59.76, 27.15],
    [3, 220.11, 86.64, 39.95], [5, 362.85, 140.40, 65.50],
  ]) {
    expect(quote(sets!)).toMatchObject({ cost, freight, quoteUsd: usd, tax: { configured: true, surchargeUsd: .2 } })
  }
})

it('opens a blocking dialog for legacy pending invoices and allows requery after purchase confirms', async () => {
  purchaseOverride = { dataSource: 'legacy_2026', taxPoint: 0, invoiceType: '待确认', singleFreightCny: 2.1, quoteReady: true }
  await mount('single')
  const dialog = host.querySelector('[role="alertdialog"]')
  expect(dialog?.textContent).toContain('采购票点为待确认，不可报价')
  expect(dialog?.textContent).toContain(record.sku)
  expect(state.purchaseQueryError).toContain('待确认')
  state.purchaseInvoiceNotice = []
  purchaseOverride = { ...purchaseOverride, invoiceType: '不开票' }
  await state.queryProduct(); await nextTick()
  expect(host.querySelector('[role="alertdialog"]')).toBeNull()
  expect(state.purchaseTaxBlockReason).toBe('')
  expect(state.purchaseQueryError).toBe('')
  expect(state.products[0]?.purchase).toBe(13.94)
})

it('blocks the whole bundle while any legacy SKU still needs invoice confirmation', async () => {
  purchaseOverride = { dataSource: 'legacy_2026', taxPoint: .08, invoiceType: '待确认', singleFreightCny: 2.1, quoteReady: true }
  await mount('bundle')
  expect(host.querySelector('[role="alertdialog"]')?.textContent).toContain(record.sku)
  expect(state.purchaseTaxBlockReason).toContain('待确认')
  expect(state.purchaseTaxBlockReason).toContain(record.sku)
})

it('restores a withdrawn quotation containing a SKU but no product snapshot without blocking future edits', async () => {
  await mount('single', '10', [], 'SINGLE', true)
  expect(state.draftReady).toBe(true)
  expect(state.products[0]?.sku).toBe(record.sku)
  expect(state.products[0]?.purchaseBaseUnitPrice).toBe(13.8)
  expect(host.textContent).not.toContain('primaryChannelKey')
})

it.each(['single', 'bundle'] as const)('refreshes legacy domestic freight from current batch data in the %s editor and restored drafts', async mode => {
  purchaseOverride = { dataSource: 'legacy_2026', singleFreightCny: 5, freight10Cny: null }
  await mount(mode)
  const freight = () => mode === 'single' ? state.products[0]!.purchaseFreightPerUnit : state.bundleItems[0]!.purchaseFreightPerUnit
  expect(freight()).toBe(5)
  const draft = state.draftPayload()
  purchaseOverride.freight10Cny = 5
  await state.applyDraftPayload(draft, undefined, { restoreQuotation: true })
  expect(freight()).toBe(.5)
  if (mode === 'bundle') expect(state.bundleDomesticFreight(10)).toBe(15) // 2 legacy items + 1 standard item per set.
  purchaseOverride.freight10Cny = 0
  if (mode === 'single') await state.queryProduct()
  else await state.queryBundleItems()
  expect(freight()).toBe(0)
})

it('quotes ten YT2600676 items to NL at $21.90 using the recorded five-yuan batch freight', async () => {
  // Production inputs observed 2026-10-05; this regression does not access production.
  purchaseOverride = {
    dataSource: 'legacy_2026', category: '袜子', weightG: 70, minOrderQty: 1,
    purchasePriceCny: 3.61, sourceQuotedPriceCny: 3.9, purchasePriceBasis: 'tax_included',
    taxIncludedPriceCny: 3.61, taxPoint: 0, invoiceType: '普票',
    singleFreightCny: 5, freight10Cny: 5, freight100Cny: 11.5,
    tier2MinQty: null, tier2PriceCny: null, tier3MinQty: null, tier3PriceCny: null,
  }
  await mount('single', '10', [], 'SINGLE', false, 'YT2600676')
  state.customerGradeSettings.find(row => row.grade === 'S')!.coefficient = 1.21605
  state.exchange = { usd: 6.7, eurUsd: 1.16 }
  const name = '云途欧洲专线（特惠普货）-CHC'
  const channelKey = '593::云途::C-f79790fa71c225481346'
  replaceLogisticsRules([{
    id: 593, name, billingVerified: true, status: '启用',
    relations: [{ carrier: '云途', channel: name, channelCode: 'C-f79790fa71c225481346' }],
    prices: [{ areaName: '荷兰', countryCode: 'NL', weightFromKg: 0, weightToKg: 1, pricingModel: 'per-kg',
      quoteReady: true, pricePerKg: 59, registrationFee: 23, minChargeWeightKg: 0,
      startWeightKg: 0, firstWeightKg: 0, firstWeightPrice: 0, nextWeightKg: 0, nextWeightPrice: 0,
      intervalPrice: 0, surcharge: 0, fuelSurchargeRate: 0, volumetric: false, zoneName: '', prohibitedMarks: '', allowedMarks: '' }],
  } as LogisticsRule])
  state.financeTaxSettings = {
    countries: [{ country: '欧盟', selected: true, enabled: true, fixedFeeUsd: 3.51, sortOrder: 1,
      channelRules: [{ key: channelKey, mode: 'weight', perKg: 1.5, amount: .6, currency: 'EUR' }] }],
    providers: [], updatedAt: 'test',
  }
  state.financeSurchargeSettings = { countries: [], providers: [], updatedAt: 'test' }
  const product = state.products[0]!
  expect(product).toMatchObject({ sku: 'YT2600676', purchase: 3.94, purchaseFreightPerUnit: .5 })
  const quote = () => state.quantityCostBreakdown(product, name, 10, '荷兰', '云途', '', channelKey)
  // Goods 39.40 + domestic 5 + international (0.72 * 59 + 23) = 109.88 CNY.
  // S coefficient 1.21605 / 6.7 + duty 1.95 USD, rounded upward to 0.05 USD.
  expect(quote()).toMatchObject({ cost: 109.88, freight: 65.48, quoteUsd: 21.9, tax: { configured: true } })
  product.purchaseFreightPerUnit = 5
  expect(quote()?.quoteUsd).toBe(30.1) // Reproduces the reported screenshot with the old freight selection.
})

it.each(['single', 'bundle'] as const)('applies TC2601815 tier tax in the %s editor and restored drafts', async mode => {
  purchaseOverride = {
    dataSource: 'legacy_2026', purchasePriceBasis: 'tax_included', sourceQuotedPriceCny: 15,
    purchasePriceCny: 16.2, taxIncludedPriceCny: 16.2, taxPoint: .08, invoiceType: '不开票',
    minOrderQty: 3, tier2MinQty: 100, tier2PriceCny: 10, tier3MinQty: null, tier3PriceCny: null,
    singleFreightCny: 4, freight10Cny: 8, freight100Cny: 44,
  }
  await mount(mode, '100+', ['100+'], 'SINGLE', false, 'TC2601815')
  const check = () => {
    const item = mode === 'single' ? state.products[0]! : state.bundleItems[0]!
    expect(item).toMatchObject({ purchaseBaseUnitPrice: 10, purchaseInvoiceType: '不开票',
      purchaseInvoiceRatePercent: 8, purchaseInvoiceTaxApplied: true, purchasePriceSource: 'legacy-tax-point',
      purchaseFreightPerUnit: .8 })
    expect('purchase' in item ? item.purchase : item.purchaseUnitPrice).toBe(10.8)
    expect(host.textContent).toContain('原始报价 ¥10.00 ×（1 + 8%）')
  }
  check()
  await state.applyDraftPayload(state.draftPayload(), undefined, { restoreQuotation: true })
  await nextTick()
  check()
})

it.each(['single', 'bundle'] as const)('recalculates the legacy 6.80/1%% case in the %s editor and after draft restoration', async mode => {
  purchaseOverride = {
    dataSource: 'legacy_2026', purchasePriceBasis: 'tax_included', sourceQuotedPriceCny: 6.8,
    purchasePriceCny: 6.8, taxIncludedPriceCny: 6.8, taxPoint: .01, invoiceType: '普票',
    minOrderQty: 1, tier2MinQty: null, tier2PriceCny: null, tier3MinQty: null, tier3PriceCny: null, singleFreightCny: 1,
  }
  await mount(mode)
  const check = () => {
    const item = mode === 'single' ? state.products[0]! : state.bundleItems[0]!
    expect(item).toMatchObject({ purchaseBaseUnitPrice: 6.8, purchaseInvoiceRatePercent: 1, purchaseInvoiceTaxApplied: true, purchasePriceSource: 'legacy-tax-point' })
    expect('purchase' in item ? item.purchase : item.purchaseUnitPrice).toBe(6.87)
    expect(host.textContent).toContain('原始报价 ¥6.80 ×（1 + 1%）')
    expect(host.textContent).not.toContain('优先采用含票价 ¥6.80')
  }
  check()
  const draft = state.draftPayload()
  await state.applyDraftPayload(draft, undefined, { restoreQuotation: true })
  await nextTick()
  check()
})
