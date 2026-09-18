// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, ref, type App } from 'vue'
import { useQuotationReviewSync } from './useQuotationReviewSync'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
const api = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/services/http', () => ({ api }))
let app: App
afterEach(() => { app?.unmount(); vi.useRealTimers(); vi.resetAllMocks() })
function mount() {
  const row = normalizeQuotationRecord({id:'a',no:'QT',_version:1,financeReviewStatus:'pending'})!
  const rows=ref([row]), selected=ref(row), account=ref('EMPLOYEE')
  let sync!: ReturnType<typeof useQuotationReviewSync>
  app=createApp({setup(){sync=useQuotationReviewSync(rows,selected,account);return()=>null}})
  app.mount(document.createElement('div'))
  return {rows,selected,account,sync,row}
}
it('updates employee badges within 3 seconds without replacing the editor snapshot or its version', async () => {
  vi.useFakeTimers();api.get.mockResolvedValue([{id:'a',_version:1,financeReviewStatus:'pending'}])
  const {sync,row,selected}=mount();await vi.advanceTimersByTimeAsync(0)
  api.get.mockResolvedValue([{id:'a',_version:2,financeReviewStatus:'approved',financeReviewedBy:'管理员'}])
  await vi.advanceTimersByTimeAsync(3000)
  expect(sync.stateFor(row).financeReviewStatus).toBe('approved')
  expect(selected.value._version).toBe(1)
  api.get.mockResolvedValue([{id:'a',_version:3,financeReviewStatus:'rejected'}])
  await vi.advanceTimersByTimeAsync(3000)
  expect(sync.stateFor(row).financeReviewStatus).toBe('rejected')
})
it('ignores stale responses after account switching and after a newer saved review', async () => {
  vi.useFakeTimers();let old!: (value:unknown)=>void
  api.get.mockReturnValueOnce(new Promise(resolve=>{old=resolve})).mockResolvedValue([])
  const {sync,account,row}=mount();account.value='OTHER';await vi.advanceTimersByTimeAsync(0)
  old([{id:'a',_version:8,financeReviewStatus:'approved'}]);await vi.advanceTimersByTimeAsync(0)
  expect(sync.stateFor(row).financeReviewStatus).toBe('pending')
  sync.accept({id:'a',_version:9,financeReviewStatus:'rejected'})
  sync.accept({id:'a',_version:8,financeReviewStatus:'approved'})
  expect(sync.stateFor(row).financeReviewStatus).toBe('rejected')
})
it('retries failures, refreshes on focus, and stops polling on unmount', async () => {
  vi.useFakeTimers();api.get.mockRejectedValue(new Error('offline'))
  const {sync}=mount();await vi.advanceTimersByTimeAsync(0)
  expect(sync.error.value).toContain('同步失败')
  api.get.mockResolvedValue([]);window.dispatchEvent(new Event('focus'));await vi.advanceTimersByTimeAsync(0)
  expect(sync.error.value).toBe('')
  app.unmount();const calls=api.get.mock.calls.length;await vi.advanceTimersByTimeAsync(10000)
  expect(api.get).toHaveBeenCalledTimes(calls)
})
