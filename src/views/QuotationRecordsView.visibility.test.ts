// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import View from './QuotationRecordsView.vue'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
import { setPricingTestPermissions, clearPricingTestPermissions } from '@/test/pricingPermissions'
const mocks = vi.hoisted(() => ({ page: vi.fn(), get: vi.fn() }))
vi.mock('@/data/quotationRecordQuery', () => ({ loadRecordPage: mocks.page, loadFilteredRecords: vi.fn(), loadRecord: vi.fn(), recentRecordDates: () => ({ startDate: '', endDate: '' }) }))
vi.mock('@/services/http', () => ({ api: { get: mocks.get }, setRequestAccount: vi.fn() }))
vi.mock('@/data/purchaseStore', () => ({ loadPurchaseProducts: () => Promise.resolve([]) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => ({ query: {} }) }))
let app: App
const flush = async () => { for (let i = 0; i < 10; i++) { await nextTick(); await Promise.resolve() } }
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; clearPricingTestPermissions(); vi.useRealTimers(); vi.clearAllMocks() })
it.each(['mine', 'company'])('hides all repeated bundle costs in the %s record drawer and responds to permission changes', async scope => {
  vi.useFakeTimers()
  setPricingTestPermissions(['quote', 'myRecords', 'allRecords'])
  const record = normalizeQuotationRecord({ id: 'bundle-privacy', no: 'QT-PRIVACY', quoteMode: 'bundle', primarySku: 'BUNDLE-A', salespersonAccount: 'PRIVACY_TEST', systemQuoteUsd: 150,
    bundleItems: [{ sku: 'BUNDLE-A', name: '商品', quantityPerSet: 2, effectiveWeightKg: 0.123, purchaseUnitPriceCny: 731.29, domesticFreightPerUnitCny: 12.37 }] })!
  const original = JSON.stringify(record)
  mocks.page.mockResolvedValue({ items: [record], page: 0, size: 10, total: 1, totalPages: 1, summary: { pending: 1, won: 0, lost: 0, total: 1 }, countries: [] })
  mocks.get.mockResolvedValue([record])
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(View, { scope }); app.component('RouterLink', { template: '<a><slot /></a>' }); app.mount(host); await flush()
  document.querySelector<HTMLButtonElement>('.difference-cell')!.click(); await flush()
  const drawer = () => document.querySelector('.record-drawer')!
  const snapshot = () => document.querySelector('.bundle-snapshot')!
  expect(snapshot().textContent).toContain('BUNDLE-A × 2/套')
  expect(snapshot().textContent).toContain('123 g/件')
  for (const secret of ['731.29', '12.37', '采购 ¥', '国内运费 ¥', '产品成本快照']) expect(drawer().innerHTML).not.toContain(secret)
  setPricingTestPermissions(['quote', 'myRecords', 'allRecords', 'purchase']); await flush()
  expect(snapshot().textContent).toContain('采购 ¥731.29')
  expect(snapshot().textContent).toContain('国内运费 ¥12.37')
  setPricingTestPermissions(['quote', 'myRecords', 'allRecords']); await flush()
  expect(drawer().innerHTML).not.toContain('731.29')
  expect(drawer().innerHTML).not.toContain('12.37')
  expect(JSON.stringify(record)).toBe(original)
})
