// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import View from './QuotationRecordsView.vue'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
const mock = vi.hoisted(() => ({ page: vi.fn(), cancel: vi.fn(), withdraw: vi.fn(), push: vi.fn() }))
vi.mock('@/data/quotationRecordQuery', () => ({ loadRecordPage: mock.page, loadFilteredRecords: vi.fn(), loadRecord: vi.fn(), recentRecordDates: () => ({ startDate: '', endDate: '' }) }))
vi.mock('@/data/purchaseStore', () => ({ loadPurchaseProducts: async () => [] }))
vi.mock('@/data/authStore', async () => ({ currentAuthUser: (await import('vue')).ref({ account: 'ME', role: 'employee' }), hasPermission: () => true }))
vi.mock('@/services/quotationWithdrawal', async original => ({ ...await original<typeof import('@/services/quotationWithdrawal')>(), cancelQuotation: mock.cancel, withdrawQuotation: mock.withdraw }))
vi.mock('@/composables/useQuotationReviewSync', () => ({ useQuotationReviewSync: () => ({ stateFor: (r: unknown) => r, accept: vi.fn(), isMissing: () => false, markMissing: vi.fn(), error: { value: '' }, poll: vi.fn() }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: mock.push }) }))
let app: App
const row = () => normalizeQuotationRecord({ id: 'one', no: 'QT-1', _version: 3, customerName: '客户', primarySku: 'SKU', salespersonAccount: 'ME', financeReviewStatus: 'reviewing', financeReviewClaimedBy: '财务' })!
const flush = async () => { for (let i = 0; i < 8; i++) { await nextTick(); await Promise.resolve() } }
const button = (text: string) => [...document.querySelectorAll('button')].find(b => b.textContent === text)!
async function mount(records = [row()]) {
  mock.page.mockResolvedValue({ items: records, total: records.length, page: 0, totalPages: 1, summary: { pending: records.length, won: 0, total: records.length }, countries: [] })
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(View, { scope: 'mine' }); app.component('RouterLink', { template: '<a><slot /></a>' }); app.mount(host); await flush()
}
beforeEach(() => { vi.useFakeTimers(); mock.cancel.mockResolvedValue({ cancelled: true }); mock.withdraw.mockResolvedValue({}); mock.push.mockResolvedValue(undefined) })
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.clearAllMocks(); vi.useRealTimers() })
it('requires explicit permanent cancellation confirmation even during review', async () => {
  await mount(); expect(button('撤回重新编辑')).toBeDefined()
  button('取消').click(); await flush(); expect(mock.cancel).not.toHaveBeenCalled()
  expect(document.querySelector('[role=dialog]')?.textContent).toContain('永久删除')
  mock.page.mockResolvedValue({ items: [], total: 0, page: 0, totalPages: 0, summary: { pending: 0, won: 0, total: 0 }, countries: [] })
  button('确认永久取消').click(); button('确认永久取消').click(); await flush()
  expect(mock.cancel).toHaveBeenCalledExactlyOnceWith('one', 3)
  expect(document.querySelector('[role=dialog]')).toBeNull()
})
it('leaves the record and dialog intact on draft conflict and navigates only after withdrawal succeeds', async () => {
  await mount(); mock.withdraw.mockRejectedValueOnce(new Error('已有草稿，请先完成或放弃'))
  button('撤回重新编辑').click(); await flush(); button('确认撤回并编辑').click(); await flush()
  expect(document.querySelector('[role=alert]')?.textContent).toContain('已有草稿')
  expect(mock.push).not.toHaveBeenCalled(); expect(document.querySelector('.record-customer')).not.toBeNull()
  button('确认撤回并编辑').click(); await flush(); expect(mock.push).toHaveBeenCalledExactlyOnceWith('/quotation')
})
it('does not expose owner commands on another owner or deal record', async () => {
  await mount([{ ...row(), salespersonAccount: 'OTHER' }, { ...row(), id: 'deal', status: 'won' }, { ...row(), id: 'details', dealQuantity: 1 }])
  expect(button('取消')).toBeUndefined(); expect(button('撤回重新编辑')).toBeUndefined()
})
