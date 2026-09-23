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
  const rows=ref([row]), selected=ref<typeof row|null>(row), account=ref('EMPLOYEE')
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
  expect(selected.value?._version).toBe(1)
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
it('orders workflow revisions independently when the quotation version is unchanged',async()=>{
  vi.useFakeTimers();api.get.mockResolvedValue([]);const {sync,row}=mount();await vi.advanceTimersByTimeAsync(0)
  sync.accept({id:'a',_version:1,_reviewVersion:4,financeReviewStatus:'reviewing',financeReviewClaimedBy:'财务一'})
  sync.accept({id:'a',_version:1,_reviewVersion:3,financeReviewStatus:'pending'})
  expect(sync.stateFor(row).financeReviewStatus).toBe('reviewing')
  sync.accept({id:'a',_version:1,_reviewVersion:5,financeReviewStatus:'approved'})
  expect(sync.stateFor(row).financeReviewStatus).toBe('approved')
})
it('pauses hidden tabs, immediately recovers online, and aborts requests after unmount',async()=>{
  vi.useFakeTimers();api.get.mockResolvedValue([]);mount();await vi.advanceTimersByTimeAsync(0)
  const hidden=vi.spyOn(document,'visibilityState','get').mockReturnValue('hidden')
  document.dispatchEvent(new Event('visibilitychange'));const before=api.get.mock.calls.length
  await vi.advanceTimersByTimeAsync(30000);expect(api.get).toHaveBeenCalledTimes(before)
  hidden.mockReturnValue('visible');window.dispatchEvent(new Event('online'));await vi.advanceTimersByTimeAsync(0)
  expect(api.get).toHaveBeenCalledTimes(before+1)
  api.get.mockImplementation((_path,options)=>new Promise((_resolve,reject)=>options.signal.addEventListener('abort',()=>reject(new Error('aborted')))))
  await vi.advanceTimersByTimeAsync(3000);const signal=api.get.mock.lastCall?.[1].signal as AbortSignal
  app.unmount();expect(signal.aborted).toBe(true);hidden.mockRestore()
})
it('waits for a slow response before scheduling the next poll',async()=>{
  vi.useFakeTimers();let finish!:(value:unknown)=>void
  api.get.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve})).mockResolvedValue([])
  mount();await vi.advanceTimersByTimeAsync(9000);expect(api.get).toHaveBeenCalledTimes(1)
  finish([]);await vi.advanceTimersByTimeAsync(0);await vi.advanceTimersByTimeAsync(2999);expect(api.get).toHaveBeenCalledTimes(1)
  await vi.advanceTimersByTimeAsync(1);expect(api.get).toHaveBeenCalledTimes(2)
})
it('aborts a timed out poll and retries without erasing the last known badge',async()=>{
  vi.useFakeTimers();api.get.mockResolvedValueOnce([{id:'a',_version:1,_reviewVersion:1,financeReviewStatus:'approved'}])
  const {sync,row}=mount();await vi.advanceTimersByTimeAsync(0)
  api.get.mockImplementationOnce((_path,options)=>new Promise((_resolve,reject)=>options.signal.addEventListener('abort',()=>reject(new Error('timeout')))))
  await vi.advanceTimersByTimeAsync(13000)
  expect(sync.error.value).toContain('同步失败');expect(sync.stateFor(row).financeReviewStatus).toBe('approved')
  api.get.mockResolvedValue([{id:'a',_version:1,_reviewVersion:2,financeReviewStatus:'rejected'}]);await vi.advanceTimersByTimeAsync(3000)
  expect(sync.error.value).toBe('');expect(sync.stateFor(row).financeReviewStatus).toBe('rejected')
})
it('discards delayed data after pagination and keeps business fields unchanged',async()=>{
  vi.useFakeTimers();let old!:(value:unknown)=>void
  api.get.mockImplementationOnce(()=>new Promise(resolve=>{old=resolve})).mockResolvedValue([])
  const {sync,rows,selected,row}=mount();const original=JSON.stringify(selected.value)
  rows.value=[normalizeQuotationRecord({id:'b',no:'QT2',_version:0})!];selected.value=null;await vi.advanceTimersByTimeAsync(0)
  old([{id:'a',_version:9,financeReviewStatus:'approved'}]);await vi.advanceTimersByTimeAsync(0)
  expect(sync.stateFor(row).financeReviewStatus).toBe('pending');expect(JSON.stringify(row)).toBe(original)
})
