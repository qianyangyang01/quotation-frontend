// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import View from './QuotationRecordsView.vue'
import { authState } from '@/data/authStore'
import { normalizeQuotationRecord } from '@/data/quotationRecords'

const mocks = vi.hoisted(() => ({ page: vi.fn(), get: vi.fn(), loadBatch: vi.fn(), loadDetail: vi.fn() }))
vi.mock('@/data/quotationRecordQuery', () => ({ loadRecordPage: mocks.page, loadFilteredRecords: vi.fn(), loadRecord: vi.fn(), recentRecordDates: () => ({ startDate: '', endDate: '' }) }))
vi.mock('@/services/http', () => ({ api: { get: mocks.get }, setRequestAccount: vi.fn() }))
vi.mock('@/data/purchaseStore', () => ({ loadPurchaseProducts: () => Promise.resolve([]) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => ({ query: {} }) }))
vi.mock('@/components/quotation/FobBatchReviewPanel.vue', () => {
  mocks.loadBatch()
  return { __esModule: true, default: { setup() { throw new Error('broken batch review') } } }
})
vi.mock('@/components/quotation/FobRecordDetail.vue', () => {
  mocks.loadDetail()
  return { __esModule: true, default: { setup() { throw new Error('broken FOB detail') } } }
})
let app: App
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; authState.current = null; authState.permissions = [] })

it('keeps ordinary record details available before and after a FOB batch review failure', async () => {
  authState.current = { id: 'me', account: 'ME', name: 'ME', role: 'super_admin', status: 'enabled', mustChangePassword: false, passwordUpdatedAt: '' }
  authState.permissions = ['allRecords', 'quote']
  const ordinary = normalizeQuotationRecord({ id: 'ordinary', no: 'QT-NORMAL', quoteMode: 'single', primarySku: 'NORMAL', customerName: '普通客户', salespersonAccount: 'ME', systemQuoteUsd: 12.3 })!
  const batch = normalizeQuotationRecord({ id: 'batch', no: 'QT-BATCH', quoteMode: 'fob', primarySku: 'BATCH', customerName: '批量客户', salespersonAccount: 'ME', fob: { schemaVersion: 2, items: [], rate: 6.7, quantity: 1, displayMode: 'quantity', policy: { scope: 'single-price', calculation: 'before-coefficient' }, product: { sku: 'BATCH', category: '日用品', source: 'fob', weight: '60', notices: [], parsed: { minOrderQty: 1, orderMultiple: 1, priceTiers: [], freight: { quantity: 1, totalFreightCny: 0, unitFreightCny: 0, estimated: false, basis: '包邮' } } }, sheet: { agent: 'ME', date: '12 Oct 2026', quantityLabels: [], issues: [], rows: [] } } })!
  const before = JSON.stringify([ordinary, batch])
  mocks.page.mockResolvedValue({ items: [ordinary, batch], total: 2, totalPages: 1, page: 0, size: 10, summary: { total: 2, pending: 2, won: 0, lost: 0 }, countries: [] })
  mocks.get.mockResolvedValue([ordinary, batch])
  const globalError = vi.fn()
  app = createApp(View, { scope: 'company' })
  app.component('RouterLink', { template: '<a><slot /></a>' })
  app.config.errorHandler = globalError
  app.mount(document.body.appendChild(document.createElement('div')))
  await vi.waitFor(() => { expect(globalError.mock.calls.map(c => String(c[0]))).toEqual([]); expect(document.querySelectorAll('.difference-cell')).toHaveLength(2) })
  expect(mocks.loadBatch).not.toHaveBeenCalled()
  expect(mocks.loadDetail).not.toHaveBeenCalled()
  document.querySelectorAll<HTMLButtonElement>('.difference-cell')[0]!.click(); await nextTick()
  expect(document.querySelector('.overview-panel')?.textContent).toContain('12.30')
  document.querySelector<HTMLButtonElement>('.record-drawer [aria-label="关闭"]')!.click(); await nextTick()
  document.querySelectorAll<HTMLButtonElement>('.difference-cell')[1]!.click()
  await vi.waitFor(() => expect(document.querySelector('[role="alert"]')?.textContent).toContain('FOB逐项审核暂时无法使用'))
  expect(mocks.loadDetail).not.toHaveBeenCalled()
  ;[...document.querySelectorAll('button')].find(b => b.textContent === '关闭当前功能')!.click(); await nextTick()
  document.querySelectorAll<HTMLButtonElement>('.difference-cell')[0]!.click(); await nextTick()
  expect(document.querySelector('.overview-panel')?.textContent).toContain('12.30')
  expect(globalError).not.toHaveBeenCalled()
  expect(JSON.stringify([ordinary, batch])).toBe(before)
})
