// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import AppTopbar from '@/components/AppTopbar.vue'
import View from './QuotationRecordsView.vue'
import { authState } from '@/data/authStore'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
import { reviewNotifications as inbox, stopReviewNotifications, type ReviewInbox } from '@/data/reviewNotifications'
const mocks = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), load: vi.fn(), page: vi.fn() }))
vi.mock('@/services/http', () => ({ api: mocks, setRequestAccount: vi.fn() }))
vi.mock('@/data/quotationRecordQuery', () => ({ loadRecord: mocks.load, loadRecordPage: mocks.page, loadFilteredRecords: vi.fn(), recentRecordDates: () => ({ startDate: '', endDate: '' }) }))
vi.mock('@/data/purchaseStore', () => ({ loadPurchaseProducts: async () => [] }))
vi.mock('@/router/routeViews', () => ({ prefetchRouteView: vi.fn(), scheduleRouteViewPrefetch: () => () => {} }))
const record = () => normalizeQuotationRecord({ id: 'r1', no: 'QT-EXACT', salespersonAccount: 'ME', customerName: '对应客户', primarySku: 'SKU-EXACT', createdAt: '2026-10-04T00:00:00Z', updatedAt: '2026-10-04T00:00:00Z', _version: 0, _reviewVersion: 2, financeReviewStatus: 'approved', financeReviewedBy: '财务', financeReviewCommentCount: 1, financeReviewLatestComment: { id: 'c1', action: 'complete', before: 'reviewing', after: 'approved', actorAccount: 'FINANCE', quoteVersion: 0, note: '包装重量已核对', actorName: '财务', at: '2026-10-04T00:00:00Z' } })!
const initial = (): ReviewInbox => ({ total: 1, page: 0, totalPages: 1, counts: { approved: 1 }, unread: [{ recordId: 'r1', eventId: 'e1', reviewVersion: 2, status: 'approved' }], items: [{ recordId: 'r1', eventId: 'e1', reviewVersion: 2, status: 'approved', kind: 'complete', note: '包装重量已核对', customerName: '对应客户', primarySku: 'SKU-EXACT', actorName: '财务', occurredAt: '2026-10-04T00:00:00Z', quoteNo: 'QT-EXACT' }] })
let app: App
const flush = async () => { for (let i=0;i<20;i++) { await nextTick(); await Promise.resolve() } }
beforeEach(() => {
  vi.useFakeTimers(); mocks.get.mockReset(); mocks.post.mockReset(); mocks.load.mockReset(); mocks.page.mockReset()
  authState.current = { id: 'me', name: '业务员', account: 'ME', role: 'employee', status: 'enabled', mustChangePassword: false, passwordUpdatedAt: '' }; authState.permissions = ['myRecords', 'quote']
  mocks.get.mockImplementation(async (path: string) => path.startsWith('/review-notifications') ? initial() : [record()])
  mocks.post.mockResolvedValue(undefined); mocks.load.mockResolvedValue(record())
  mocks.page.mockResolvedValue({ items: [record()], page: 0, size: 10, total: 1, totalPages: 1, summary: { pending: 0, won: 0, lost: 0, total: 1 }, countries: [] })
})
afterEach(() => { app?.unmount(); stopReviewNotifications(); document.body.innerHTML=''; authState.current=null; authState.permissions=[]; vi.useRealTimers() })
async function mount() {
  const router=createRouter({ history:createMemoryHistory(),routes:[{path:'/quotation/my-records',component:View,props:{scope:'mine'}},{path:'/quotation',component:{template:'<p>新建报价</p>'}}] })
  await router.push('/quotation/my-records');await router.isReady()
  const host=document.createElement('div');document.body.append(host)
  app=createApp({render:()=>h('div',[h(AppTopbar),h(RouterView)])});app.use(router);app.mount(host);await flush();return router
}
function viewMessage() {
  document.querySelector<HTMLButtonElement>('.notification-trigger')!.click()
  return flush().then(()=>{document.querySelector<HTMLButtonElement>('.inbox-messages article>button')!.click()})
}
it('opens the exact personal quote and updates the bell, category and row immediately after viewing', async () => {
  const router=await mount();expect(document.querySelector('.review-unread-count')?.textContent).toBe('1')
  await viewMessage()
  mocks.get.mockImplementation((path:string)=>path.startsWith('/review-notifications')?new Promise(()=>{}):Promise.resolve([record()]))
  await flush()
  expect(router.currentRoute.value.query).toMatchObject({record:'r1',reviewEvent:'e1'})
  expect(document.querySelector('.record-drawer')?.textContent).toContain('QT-EXACT')
  expect(document.querySelector('.record-drawer')?.textContent).toContain('包装重量已核对')
  expect(mocks.post).toHaveBeenCalledWith('/review-notifications/r1/read',{eventId:'e1'})
  expect(inbox.total).toBe(0);expect(document.querySelector('.notification-trigger>b')).toBeNull()
  expect(document.querySelector('.review-unread-count')).toBeNull();expect(document.querySelector('.review-unread-link')).toBeNull()
})
it('does not mark a message read when its quotation cannot load', async () => {
  await mount();mocks.load.mockRejectedValue(new Error('详情加载失败'));await viewMessage();await flush()
  expect(mocks.post).not.toHaveBeenCalled();expect(inbox.total).toBe(1);expect(document.querySelector('.record-drawer')).toBeNull()
})
it('closing the message panel keeps every unread count', async () => {
  await mount();document.querySelector<HTMLButtonElement>('.notification-trigger')!.click();await flush()
  document.querySelector<HTMLButtonElement>('[aria-label="关闭审核消息"]')!.click();await flush()
  expect(inbox.total).toBe(1);expect(mocks.post).not.toHaveBeenCalled()
})
it('clears all notifications with one button without opening individual quotes', async () => {
  await mount();document.querySelector<HTMLButtonElement>('.notification-trigger')!.click();await flush()
  mocks.get.mockImplementation((path:string)=>path.startsWith('/review-notifications')?new Promise(()=>{}):Promise.resolve([record()]))
  document.querySelector<HTMLButtonElement>('.inbox-bulk-actions button')!.click();await flush()
  expect(mocks.post).toHaveBeenCalledWith('/review-notifications/read-batch',{eventIds:['e1']})
  expect(mocks.load).not.toHaveBeenCalled();expect(document.querySelector('.record-drawer')).toBeNull()
  expect(inbox.total).toBe(0);expect(document.querySelector('.notification-trigger>b')).toBeNull()
  expect(document.querySelector('.review-unread-count')).toBeNull();expect(document.querySelector('.review-unread-link')).toBeNull()
})
it('can retry the same notification after a detail load failure', async () => {
  await mount();mocks.load.mockRejectedValueOnce(new Error('暂时断网'))
  await viewMessage();await flush();expect(mocks.post).not.toHaveBeenCalled()
  await viewMessage();await flush()
  expect(document.querySelector('.record-drawer')?.textContent).toContain('QT-EXACT')
  expect(mocks.post).toHaveBeenCalledWith('/review-notifications/r1/read',{eventId:'e1'})
})
