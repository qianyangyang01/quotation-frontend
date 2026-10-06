// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import View from './QuotationRecordsView.vue'
import { authState } from '@/data/authStore'
import { normalizeQuotationRecord, type QuotationRecord } from '@/data/quotationRecords'
const mocks=vi.hoisted(()=>({page:vi.fn(),get:vi.fn(),patch:vi.fn()}))
vi.mock('@/data/quotationRecordQuery',()=>({loadRecordPage:mocks.page,loadFilteredRecords:vi.fn(),loadRecord:vi.fn(),recentRecordDates:()=>({startDate:'',endDate:''})}))
vi.mock('@/services/http',()=>({api:{get:mocks.get,patch:mocks.patch},setRequestAccount:vi.fn()}))
vi.mock('@/data/purchaseStore',()=>({loadPurchaseProducts:()=>Promise.resolve([])}))
vi.mock('vue-router',()=>({useRouter:()=>({push:vi.fn()}),useRoute:()=>({query:{}})}))
let app:App
const flush=async()=>{for(let i=0;i<10;i++){await nextTick();await Promise.resolve()}}
const row=(data:Partial<QuotationRecord>={})=>normalizeQuotationRecord({id:'one',no:'QT-1',salespersonAccount:'ME',_version:5,_reviewVersion:2,financeReviewStatus:'pending',...data})!
const page=(items:QuotationRecord[])=>({items,page:0,size:10,total:items.length,totalPages:1,summary:{pending:items.length,won:0,lost:0,total:items.length},countries:[]})
async function mount(items:QuotationRecord[]) {
  vi.useFakeTimers();authState.current={id:'me',name:'ME',account:'ME',role:'employee',status:'enabled',mustChangePassword:false,passwordUpdatedAt:''};authState.permissions=['myRecords']
  mocks.page.mockResolvedValue(page(items));mocks.get.mockResolvedValue(items)
  const host=document.createElement('div');document.body.append(host);app=createApp(View,{scope:'mine'});app.component('RouterLink',{template:'<a><slot /></a>'});app.mount(host);await flush()
}
afterEach(()=>{app?.unmount();document.body.innerHTML='';authState.current=null;authState.permissions=[];vi.useRealTimers();vi.resetAllMocks()})
it('sets priority once with live versions and no reason, then shows the returned flag',async()=>{
  const saved=row({priorityProcessing:true,_reviewVersion:3});await mount([row()])
  let resolve!:(value:QuotationRecord)=>void;mocks.patch.mockImplementation(()=>new Promise<QuotationRecord>(r=>{resolve=r}))
  const button=document.querySelector<HTMLButtonElement>('.priority-action')!;button.click();await flush();button.click()
  expect(button.disabled).toBe(true);expect(mocks.patch).toHaveBeenCalledOnce()
  expect(mocks.patch).toHaveBeenCalledWith('/quotations/one/priority',{priorityProcessing:true,_version:5,_reviewVersion:2})
  expect(document.querySelector('textarea')).toBeNull()
  mocks.page.mockResolvedValue(page([saved]));resolve(saved);await flush()
  expect(document.querySelector('.priority-badge')?.textContent).toBe('优先处理')
  expect(document.querySelector('.priority-action')?.textContent).toBe('取消优先')
})
it('limits actions to owned active pending/reviewing records',async()=>{
  await mount([row(),row({id:'other',salespersonAccount:'OTHER'}),row({id:'done',financeReviewStatus:'approved'}),row({id:'working',financeReviewStatus:'reviewing'}),row({id:'archived',lifecycleState:'archived'})])
  expect(document.querySelectorAll('.priority-action')).toHaveLength(2)
  expect(document.querySelectorAll('.review-results')).toHaveLength(0)
})
it('keeps the prior mark on a conflict and polls authoritative state',async()=>{
  await mount([row({priorityProcessing:true})]);const calls=mocks.get.mock.calls.length
  mocks.patch.mockRejectedValue(new Error('审核已完成，请刷新'))
  document.querySelector<HTMLButtonElement>('.priority-action')!.click();await flush()
  expect(document.body.textContent).toContain('审核已完成，请刷新')
  expect(mocks.get.mock.calls.length).toBeGreaterThan(calls)
  expect(document.querySelector('.priority-badge')).not.toBeNull()
})
it('filters on the server, resets pagination, and refreshes an empty priority queue',async()=>{
  await mount([])
  document.querySelectorAll<HTMLButtonElement>('.priority-filters button')[1]!.click();await flush();await vi.advanceTimersByTimeAsync(250);await flush()
  expect(mocks.page.mock.lastCall?.slice(0,3)).toEqual(['mine',expect.objectContaining({priorityOnly:true}),0])
  mocks.page.mockResolvedValue(page([row({priorityProcessing:true})]));mocks.get.mockResolvedValue([row({priorityProcessing:true})]);await vi.advanceTimersByTimeAsync(3000);await flush()
  expect(document.querySelector('.priority-badge')).not.toBeNull()
})
