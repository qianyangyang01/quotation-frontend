// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import View from './QuotationSystemView.vue'
import type { BundleQuoteItem, QuotationProduct } from '@/components/quotation/types'
import { api } from '@/services/http'
import { clearFinanceSettingsCache } from '@/services/financeSettings'

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
let state: {
  draftReady: boolean
  products: QuotationProduct[]
  bundleItems: BundleQuoteItem[]
  monthlySalesEstimate: string
  bundlePurchaseCost: (sets?: number) => number
  bundleDomesticFreight: (sets?: number) => number
  salePrice: (product: QuotationProduct) => number
}
beforeEach(() => { vi.clearAllMocks(); clearFinanceSettingsCache() })
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); vi.restoreAllMocks(); clearFinanceSettingsCache() })

async function mount(mode: 'single' | 'bundle', estimate = '10') {
  vi.spyOn(api, 'get').mockImplementation(async path => {
    if (path === '/finance-settings') return {
      'country-classification': { value: [], _version: 1 }, 'channel-policies': { value: [], _version: 1 },
      'customer-grades': { value: [{ grade: 'S', coefficient: 1.2, enabled: true }], _version: 1 },
      'exchange-rate': { value: { usdCny: 6.7, updatedAt: 'test' }, _version: 1 },
      'tax-settings': { value: { countries: [], providers: [], updatedAt: 'test' }, _version: 1 },
      'customer-operation-fees': { value: { customers: [] }, _version: 1 },
    }
    if (path === '/quotation-readiness') return { ready: true, missing: [] }
    if (path === '/quotation-templates') return []
    if (path === '/quotation-drafts/mine/state') return { exists: true, version: 1, payload: {
      schemaVersion: 2, quoteMode: mode, customerName: '阶梯回归', skuSearch: record.sku,
      selectedCustomerGrade: 'S', monthlySalesEstimate: estimate, logisticsAttribute: '普货',
      product: { sku: record.sku, quantity: 1, purchaseInvoiceTaxApplied: true },
      bundleItems: [
        { sku: record.sku, quantityPerSet: 2, purchaseInvoiceTaxApplied: true },
        { sku: 'SINGLE', quantityPerSet: 1, purchaseInvoiceTaxApplied: true },
      ],
    } }
    if (path === `/purchase-products/${record.sku}`) return record
    if (path === '/purchase-products/SINGLE') return { sku: 'SINGLE', category: '宠物用品', weightG: 100, minOrderQty: 100, purchasePriceCny: 20, taxPoint: .02, freight10Cny: 5 }
    throw new Error(`Unexpected API: ${path}`)
  })
  vi.spyOn(api, 'put').mockResolvedValue({ exists: true, version: 2, updatedAt: 'test' })
  host = document.createElement('div'); document.body.append(host)
  app = createApp(View)
  const vm = app.mount(host)
  state = (vm.$ as unknown as { setupState: typeof state }).setupState
  await vi.waitFor(() => expect(state.draftReady).toBe(true))
}

async function selectTier(value: string) {
  const select = host.querySelector<HTMLSelectElement>('[data-validation-field="monthlySalesEstimate"] select')!
  expect([...select.options].map(option => option.text)).toEqual(['阶梯价1', '阶梯价2', '阶梯价3'])
  select.value = value
  select.dispatchEvent(new Event('change', { bubbles: true }))
  await nextTick()
}

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

it('updates every bundle SKU and explains a missing tier without changing domestic freight', async () => {
  await mount('bundle')
  const freight = state.bundleDomesticFreight(3)
  await selectTier('100')
  expect(state.bundleItems.map(item => item.purchaseUnitPrice)).toEqual([12.75, 20.4])
  expect(state.bundlePurchaseCost(3)).toBe(137.7)
  expect(state.bundleDomesticFreight(3)).toBe(freight)
  const labels = [...host.querySelectorAll('.purchase-price small')].map(item => item.textContent)
  expect(labels[0]).toContain('阶梯价2（500–999件）原价 ¥12.50')
  expect(labels[1]).toContain('阶梯价2未配置，采用阶梯价1（100件起）')
})
