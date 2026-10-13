// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import View from './QuotationRecordsView.vue'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
import { currentAuthUser } from '@/data/authStore'
const query=vi.hoisted(()=>({loadRecordPage:vi.fn(),loadFilteredRecords:vi.fn(),loadRecord:vi.fn(),recentRecordDates:()=>({startDate:'2026-09-23',endDate:'2026-09-23'})}))
const change=vi.hoisted(()=>vi.fn())
const batchLoad=vi.hoisted(()=>vi.fn())
vi.mock('@/services/quotationBatchCopy',async original=>({...await original<typeof import('@/services/quotationBatchCopy')>(),loadQuotationBatchRecords:batchLoad}))
vi.mock('@/data/quotationRecordQuery',()=>query)
vi.mock('@/data/quotationLifecycle',async importOriginal=>({...await importOriginal<typeof import('@/data/quotationLifecycle')>(),changeQuotationLifecycle:change}))
vi.mock('@/data/purchaseStore',()=>({loadPurchaseProducts:()=>Promise.resolve([])}))
vi.mock('@/data/authStore',async()=>{const {ref}=await import('vue');return {currentAuthUser:ref({account:'ME',role:'super_admin'}),hasPermission:()=>true}})
vi.mock('@/composables/useQuotationReviewSync',()=>({useQuotationReviewSync:()=>({isMissing: () => false, markMissing: vi.fn(), stateFor:(r:unknown)=>r,accept:vi.fn(),error:{value:''},poll:vi.fn()})}))
vi.mock('vue-router',()=>({useRouter: () => ({ push: vi.fn().mockResolvedValue(undefined) }), useRoute:()=>({query:{}})}))
let app:App
const normal=()=>normalizeQuotationRecord({id:'one',no:'QT-TEST',_version:3,customerName:'测试客户',primarySku:'SKU',salespersonAccount:'ME'})!
const result=(rows=[normal()])=>({items:rows,page:0,size:10,total:rows.length,totalPages:1,summary:{pending:rows.length,won:0,lost:0,total:rows.length},countries:['美国']})
const flush=async()=>{await nextTick();await Promise.resolve();await nextTick();vi.advanceTimersByTime(1)}
const button=(text:string)=>Array.from(document.querySelectorAll('button')).find(b=>b.textContent===text)!
async function mount(scope='company'){const host=document.createElement('div');document.body.append(host);app=createApp(View,{scope});app.component('RouterLink',{template:'<a><slot /></a>'});app.mount(host);await flush()}
beforeEach(()=>{
  vi.useFakeTimers()
  Object.defineProperty(HTMLDialogElement.prototype,'showModal',{configurable:true,value:function(){this.open=true}})
  Object.defineProperty(HTMLDialogElement.prototype,'close',{configurable:true,value:function(){this.open=false}})
  currentAuthUser.value.account='ME';currentAuthUser.value.role='super_admin'
  query.loadRecordPage.mockResolvedValue(result());change.mockResolvedValue({changed:1})
})
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.useRealTimers();vi.clearAllMocks()})
it('requires reviewed selection and reason, sends versions only after confirmation, then reloads',async()=>{
  await mount()
  expect(button('移入回收站')).toBeUndefined()
  document.querySelector<HTMLInputElement>('.lifecycle-checkbox')!.click();await flush()
  button('移入回收站').click();await flush()
  expect(document.querySelector('dialog')?.textContent).toContain('QT-TEST')
  expect(change).not.toHaveBeenCalled();expect(button('确认移入').disabled).toBe(true)
  const reason=document.querySelector<HTMLTextAreaElement>('#lifecycle-reason')!
  reason.value='测试数据清理';reason.dispatchEvent(new Event('input'));await flush()
  button('确认移入').click();await flush()
  expect(change).toHaveBeenCalledWith('trash','测试数据清理',[expect.objectContaining({_version:3,id:'one'})])
  expect(document.querySelector('dialog')).toBeNull()
  expect(query.loadRecordPage).toHaveBeenCalledTimes(2)
})
it('locks won and reviewed rows and clears selection when changing lifecycle',async()=>{
  query.loadRecordPage.mockResolvedValue(result([normal(),{...normal(),id:'won',no:'QT-WON',status:'won'},{...normal(),id:'reviewed',no:'QT-REVIEW',financeReviewStatus:'approved'},{...normal(),id:'reviewing',no:'QT-CLAIMED',financeReviewStatus:'reviewing',financeReviewClaimedAccount:'FINANCE'}]))
  await mount();const checks=document.querySelectorAll<HTMLInputElement>('.lifecycle-checkbox')
  expect(checks[1]!.disabled).toBe(true);expect(checks[2]!.disabled).toBe(true);expect(checks[3]!.disabled).toBe(true)
  checks[0]!.click();await flush()
  button('回收站').click();await flush();await vi.advanceTimersByTimeAsync(250);await flush()
  expect(query.loadRecordPage.mock.lastCall?.[1]).toMatchObject({lifecycle:'trashed'})
  expect(document.querySelector('.lifecycle-toolbar')?.textContent).toContain('已选 0 条')
})
it('retains dialog and error on conflict and never displays a successful cleanup',async()=>{
  change.mockRejectedValue(new Error('所选报价已变更，本次未执行'))
  await mount();document.querySelector<HTMLInputElement>('.lifecycle-checkbox')!.click();await flush()
  button('批量归档').click();await flush()
  const reason=document.querySelector<HTMLTextAreaElement>('#lifecycle-reason')!;reason.value='归档';reason.dispatchEvent(new Event('input'));await flush()
  button('确认归档').click();await flush()
  expect(document.querySelector('dialog [role=alert]')?.textContent).toContain('本次未执行')
  expect(document.querySelector('.toast')).toBeNull()
})
it('keeps eligible selection during review polling and removes it if another reviewer claims the quote',async()=>{
  await mount()
  button('待审核').click();await flush();await vi.advanceTimersByTimeAsync(250);await flush()
  document.querySelector<HTMLInputElement>('.lifecycle-checkbox')!.click();await flush()
  await vi.advanceTimersByTimeAsync(15000);await flush()
  expect(document.querySelector('.lifecycle-toolbar')?.textContent).toContain('已选 1 条')
  query.loadRecordPage.mockResolvedValue(result([{...normal(),financeReviewStatus:'reviewing',financeReviewClaimedAccount:'FINANCE'}]))
  await vi.advanceTimersByTimeAsync(15000);await flush()
  expect(document.querySelector('.lifecycle-toolbar')?.textContent).toContain('已选 0 条')
  expect(document.querySelector<HTMLInputElement>('.lifecycle-checkbox')!.disabled).toBe(true)
})
it('restores to previous archived state and renders archived entries read-only',async()=>{
  query.loadRecordPage.mockResolvedValue(result([{...normal(),lifecycleState:'trashed',lifecyclePreviousState:'archived',lifecycleChangedAccount:'ME',lifecycleReason:'测试'}]))
  await mount();button('回收站').click();await flush();await vi.advanceTimersByTimeAsync(250);await flush()
  expect(Array.from(document.querySelectorAll('.review-panel button')).some(button=>button.textContent?.includes('审核'))).toBe(false)
  expect(document.querySelector('.reissue-quote')).toBeNull()
  document.querySelector<HTMLInputElement>('.lifecycle-checkbox')!.click();await flush();button('恢复所选记录').click();await flush()
  expect(document.querySelector('dialog tbody')?.textContent).toContain('已归档')
})
it('does not grant finance company-wide cleanup and prevents employees restoring admin cleanup',async()=>{
  currentAuthUser.value.role='finance';await mount()
  expect(document.querySelector('.lifecycle-toolbar')).toBeNull();app.unmount();document.body.innerHTML=''
  currentAuthUser.value.role='employee';query.loadRecordPage.mockResolvedValue(result([{...normal(),lifecycleState:'trashed',lifecycleChangedAccount:'ADMIN'}]))
  await mount('mine');button('回收站').click();await flush();await vi.advanceTimersByTimeAsync(250);await flush()
  expect(document.querySelector<HTMLInputElement>('.lifecycle-checkbox')!.disabled).toBe(true)
})

const batchRows=()=>Array.from({length:14},(_,index)=>({...normal(),id:`batch-${index}`,no:`QT-${index}`,quotationBatch:{id:'batch',no:'QB-14',index:index+1,count:14}}))
async function openBatchTrash(){
  document.querySelector<HTMLButtonElement>('.record-row-actions .record-more-trigger')!.click();await flush()
  const item=Array.from(document.querySelectorAll<HTMLButtonElement>('.record-more-panel button')).find(b=>b.textContent?.includes('整批移入回收站'))!
  item.click();await flush()
}
function reason(text='整批不再需要'){
  const input=document.querySelector<HTMLTextAreaElement>('#lifecycle-reason')!;input.value=text;input.dispatchEvent(new Event('input'))
}
it.each(['single','fob'] as const)('loads all batch members from any visible %s record and confirms the entire batch',async quoteMode=>{
  const rows=batchRows().map(row=>({...row,quoteMode}));query.loadRecordPage.mockResolvedValue(result([rows[9]!]));batchLoad.mockResolvedValue(rows)
  await mount('mine');await openBatchTrash()
  expect(batchLoad).toHaveBeenCalledWith(expect.objectContaining({id:'batch-9'}),'trash')
  expect(document.querySelectorAll('dialog tbody tr')).toHaveLength(14)
  expect(document.querySelector('dialog')?.textContent).toContain('QB-14')
  expect(change).not.toHaveBeenCalled();reason();await flush()
  query.loadRecordPage.mockResolvedValue(result([]));change.mockResolvedValue({changed:14});button('确认移入').click();await flush()
  expect(change).toHaveBeenCalledWith('trash','整批不再需要',rows,'batch-9')
  expect(document.querySelectorAll('.record-row')).toHaveLength(0)
})
it('lists protected off-page batch members and blocks the entire batch',async()=>{
  const rows=batchRows();rows[12]!.financeReviewStatus='reviewing';rows[13]!.status='won'
  query.loadRecordPage.mockResolvedValue(result([rows[0]!]));batchLoad.mockResolvedValue(rows)
  await mount();await openBatchTrash();reason();await flush()
  expect(document.querySelector('dialog [role=alert]')?.textContent).toContain('QT-12')
  expect(document.querySelector('dialog [role=alert]')?.textContent).toContain('QT-13')
  expect(button('确认移入').disabled).toBe(true);expect(change).not.toHaveBeenCalled()
})
it('keeps current-only action independent and retains the confirmation after a batch conflict',async()=>{
  const rows=batchRows();query.loadRecordPage.mockResolvedValue(result([rows[5]!]));batchLoad.mockResolvedValue(rows)
  await mount();document.querySelector<HTMLButtonElement>('.record-row-actions .record-more-trigger')!.click();await flush()
  Array.from(document.querySelectorAll<HTMLButtonElement>('.record-more-panel button')).find(b=>b.textContent?.includes('当前 1 条'))!.click();await flush();reason();await flush();button('确认移入').click();await flush()
  expect(batchLoad).not.toHaveBeenCalled();expect(change).toHaveBeenCalledWith('trash','整批不再需要',[rows[5]])
  await openBatchTrash();reason();await flush();change.mockRejectedValue(new Error('QT-12：正在审核，本次整组未执行'))
  button('确认移入').click();await flush()
  expect(document.querySelector('dialog [role=alert]')?.textContent).toContain('QT-12')
})
it.each(['批次记录不完整', '本批包含已撤回或回收站记录，无法整批操作，请先恢复记录或单独处理', '网络连接失败，请重试'])('keeps batch loading failure visible without a success toast or submission: %s',async message=>{
  query.loadRecordPage.mockResolvedValue(result([batchRows()[0]!]));batchLoad.mockRejectedValue(new Error(message))
  await mount();document.querySelector<HTMLInputElement>('.lifecycle-checkbox')!.click();await flush();await openBatchTrash()
  expect(document.querySelector('dialog [role=alert]')?.textContent).toContain(message)
  expect(document.querySelector('dialog [role=alert]')?.textContent).toContain('本次未移入任何记录')
  expect(document.querySelector('dialog button[type=submit]')).toBeNull()
  expect(document.querySelector('.toast')).toBeNull()
  document.querySelector('dialog form')!.dispatchEvent(new Event('submit',{cancelable:true}));await flush()
  await vi.advanceTimersByTimeAsync(3000);await flush()
  expect(document.querySelector('dialog [role=alert]')?.textContent).toContain(message)
  expect(document.querySelector('.lifecycle-toolbar')?.textContent).toContain('已选 1 条')
  expect(document.querySelector('.record-row-actions .record-more-trigger')?.getAttribute('aria-expanded')).toBe('false')
  expect(change).not.toHaveBeenCalled()
  button('关闭').click();await flush();expect(document.querySelector('dialog')).toBeNull()
})
it.each(['single','fob'] as const)('confirms only remaining %s records while retaining skipped versions for atomic validation',async quoteMode=>{
  const rows=batchRows().map(row=>({...row,quoteMode}))
  rows[0]!.lifecycleState='withdrawn';rows[1]!.lifecycleState='trashed';rows[2]!.lifecycleState='archived'
  query.loadRecordPage.mockResolvedValue(result([rows[9]!]));batchLoad.mockResolvedValue(rows)
  await mount();await openBatchTrash()
  expect(document.querySelectorAll('dialog tbody tr')).toHaveLength(12)
  expect(document.querySelector('.lifecycle-skipped')?.textContent).toContain('已跳过 2 条')
  expect(document.querySelector('.lifecycle-skipped')?.textContent).toContain('已撤回重新编辑')
  expect(document.querySelector('.lifecycle-skipped')?.textContent).toContain('已在回收站')
  reason();await flush();change.mockResolvedValue({changed:12});button('确认移入').click();await flush()
  expect(change).toHaveBeenCalledWith('trash','整批不再需要',rows,'batch-9')
  expect(document.querySelector('.toast')?.textContent).toContain('已处理 12 条报价记录')
})
it('still blocks protected remaining members after skipping withdrawn records',async()=>{
  const rows=batchRows();rows[0]!.lifecycleState='withdrawn';rows[1]!.financeReviewStatus='reviewing'
  query.loadRecordPage.mockResolvedValue(result([rows[9]!]));batchLoad.mockResolvedValue(rows)
  await mount();await openBatchTrash();reason();await flush()
  expect(document.querySelector('dialog [role=alert]')?.textContent).toContain('QT-1')
  expect(button('确认移入').disabled).toBe(true);expect(change).not.toHaveBeenCalled()
})
it('does not submit when all batch members were withdrawn or recycled',async()=>{
  const rows=batchRows();rows.forEach(row=>{row.lifecycleState='withdrawn'})
  query.loadRecordPage.mockResolvedValue(result([batchRows()[0]!]));batchLoad.mockResolvedValue(rows)
  await mount();await openBatchTrash()
  expect(document.querySelector('dialog [role=alert]')?.textContent).toContain('已无可移入')
  reason();await flush();expect(button('确认移入').disabled).toBe(true);expect(change).not.toHaveBeenCalled()
})
it('rechecks the complete batch after a loading failure and still requires explicit confirmation',async()=>{
  const rows=batchRows();query.loadRecordPage.mockResolvedValue(result([rows[0]!]));batchLoad.mockRejectedValueOnce(new Error('加载失败')).mockResolvedValueOnce(rows)
  await mount();await openBatchTrash();button('重新检查').click();await flush()
  expect(batchLoad).toHaveBeenCalledTimes(2)
  expect(document.querySelectorAll('dialog tbody tr')).toHaveLength(14)
  expect(button('确认移入').disabled).toBe(true)
  expect(change).not.toHaveBeenCalled()
  reason();await flush();button('确认移入').click();await flush()
  expect(change).toHaveBeenCalledWith('trash','整批不再需要',rows,rows[0]!.id)
})
it('discards a stale batch loading failure after switching accounts',async()=>{
  let rejectLoad!:(error:Error)=>void
  query.loadRecordPage.mockResolvedValue(result([batchRows()[0]!]));batchLoad.mockReturnValue(new Promise((_,reject)=>{rejectLoad=reject}))
  await mount();await openBatchTrash();currentAuthUser.value.account='OTHER';await flush()
  rejectLoad(new Error('原账号批次异常'));await flush()
  expect(document.querySelector('dialog')).toBeNull();expect(document.querySelector('.toast')).toBeNull();expect(change).not.toHaveBeenCalled()
})
