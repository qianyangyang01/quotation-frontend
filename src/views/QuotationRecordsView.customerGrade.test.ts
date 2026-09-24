// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import View from './QuotationRecordsView.vue'
import { normalizeQuotationRecord } from '@/data/quotationRecords'

const mocks = vi.hoisted(() => ({ page: vi.fn() }))
vi.mock('@/data/quotationRecordQuery', () => ({ loadRecordPage: mocks.page, loadFilteredRecords: vi.fn(), loadRecord: vi.fn(), recentRecordDates: () => ({ startDate: '', endDate: '' }) }))
vi.mock('@/data/purchaseStore', () => ({ loadPurchaseProducts: () => Promise.resolve([]) }))
vi.mock('@/services/http', () => ({ api: { get: vi.fn().mockResolvedValue([]) }, setRequestAccount: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn().mockResolvedValue(undefined) }), useRoute: () => ({ query: {} }) }))
let app: App | undefined
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.clearAllMocks() })

it.each(['mine', 'company'] as const)('shows saved customer grades below the customer name in %s records', async scope => {
  const items = ['A', 'NEW', 'E', undefined].map((customerGrade, index) => normalizeQuotationRecord({ id: String(index), no: 'QT-' + index, customerGrade })!)
  mocks.page.mockResolvedValue({ items, page: 0, total: 4, totalPages: 1, summary: { pending: 4, won: 0, lost: 0, total: 4 }, countries: [] })
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(View, { scope }); app.component('RouterLink', { template: '<a><slot /></a>' }); app.mount(host)
  await vi.waitFor(() => expect(host.querySelectorAll('.record-customer-grade')).toHaveLength(4))
  await nextTick()
  expect([...host.querySelectorAll('.record-customer-grade')].map(el => el.textContent)).toEqual([
    '客户级别：A级客户', '客户级别：新客户', '客户级别：普通客户', '客户级别：—',
  ])
  for (const el of host.querySelectorAll('.record-customer-grade')) {
    expect(el.parentElement?.classList.contains('record-customer')).toBe(true)
    expect(el.previousElementSibling?.tagName).toBe('B')
  }
})
