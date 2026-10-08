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
const row=(data:Partial<QuotationRecord>={})=>normalizeQuotationRecord({id:'one',no:'QT-1',salespersonAccount:'ME',_version:5,_reviewVersion:2,financeReviewStatus:'approved',...data})!
const marked=()=>row({spotChecked:true,spotCheckedBy:'管理员',spotCheckedAt:'2026-10-08T08:00:00Z',_reviewVersion:3})
async function mount(role:'super_admin'|'employee'|'finance',items=[row()]) {
  vi.useFakeTimers();authState.current={id:'me',name:'ME',account:'ME',role,status:'enabled',mustChangePassword:false,passwordUpdatedAt:''};authState.permissions=role==='employee'?['myRecords']:['allRecords']
  mocks.page.mockResolvedValue({items,page:0,size:10,total:items.length,totalPages:1,summary:{pending:items.length,won:0,lost:0,total:items.length},countries:[]});mocks.get.mockResolvedValue(items)
  const host=document.createElement('div');document.body.append(host);app=createApp(View,{scope:role==='employee'?'mine':'company'});app.component('RouterLink',{template:'<a><slot /></a>'});app.mount(host);await flush()
}
afterEach(()=>{app?.unmount();document.body.innerHTML='';authState.current=null;authState.permissions=[];vi.useRealTimers();vi.resetAllMocks()})
it('marks once without a remark and renders the authoritative marker without changing the review conclusion',async()=>{
  await mount('super_admin');let resolve!:(value:QuotationRecord)=>void;mocks.patch.mockImplementation(()=>new Promise<QuotationRecord>(r=>{resolve=r}))
  const button=document.querySelector<HTMLButtonElement>('.spot-check-button')!;button.click();await flush();button.click()
  expect(button.disabled).toBe(true);expect(mocks.patch).toHaveBeenCalledOnce();expect(mocks.patch).toHaveBeenCalledWith('/quotations/one/spot-check',{_version:5,_reviewVersion:2,spotChecked:true})
  expect(document.querySelector('textarea')).toBeNull();const pageCalls=mocks.page.mock.calls.length;await vi.advanceTimersByTimeAsync(3000);expect(mocks.page).toHaveBeenCalledTimes(pageCalls);resolve(marked());await flush()
  expect(document.querySelector('.spot-check-badge')?.textContent).toContain('已抽检');expect(document.querySelector('.spot-check-badge')?.getAttribute('title')).toContain('管理员')
  expect(document.querySelector('.finance-review')?.textContent).toBe('审核通过');expect(document.querySelector('.spot-check-button')?.getAttribute('aria-label')).toContain('点击取消')
})
it('lets an admin click the purple marker again to cancel without changing the review conclusion',async()=>{
  await mount('super_admin',[marked()]);mocks.patch.mockResolvedValue(row({spotChecked:false,_reviewVersion:4}))
  document.querySelector<HTMLButtonElement>('.spot-check-badge')!.click();await flush()
  expect(mocks.patch).toHaveBeenCalledWith('/quotations/one/spot-check',{_version:5,_reviewVersion:3,spotChecked:false})
  expect(document.querySelector('.spot-check-badge')).toBeNull();expect(document.querySelector('.spot-check-button')?.textContent).toContain('标记已抽检')
  expect(document.querySelector('.finance-review')?.textContent).toBe('审核通过');expect(document.body.textContent).toContain('已取消抽检标记')
  mocks.patch.mockResolvedValue(row({spotChecked:true,spotCheckedBy:'管理员',_reviewVersion:5}));document.querySelector<HTMLButtonElement>('.spot-check-button')!.click();await flush()
  expect(mocks.patch).toHaveBeenLastCalledWith('/quotations/one/spot-check',{_version:5,_reviewVersion:4,spotChecked:true})
  expect(document.querySelector('.spot-check-badge')).not.toBeNull()
})
it('keeps the marker on a failed cancellation and uses the latest polled review version',async()=>{
  await mount('super_admin',[marked()]);mocks.get.mockResolvedValue([row({spotChecked:true,_reviewVersion:8})]);await vi.advanceTimersByTimeAsync(3000);await flush()
  mocks.patch.mockRejectedValue(new Error('抽检或审核状态已变化'));document.querySelector<HTMLButtonElement>('.spot-check-badge')!.click();await flush()
  expect(mocks.patch).toHaveBeenCalledWith('/quotations/one/spot-check',{_version:5,_reviewVersion:8,spotChecked:false})
  expect(document.querySelector('.spot-check-badge')).not.toBeNull();expect(document.body.textContent).toContain('抽检或审核状态已变化')
})
it.each(['employee','finance'] as const)('shows marks but no marking control to %s',async role=>{
  await mount(role,[marked(),row({id:'two'})]);expect(document.querySelectorAll('.spot-check-badge')).toHaveLength(1);expect(document.querySelector('.spot-check-button')).toBeNull();expect(mocks.patch).not.toHaveBeenCalled()
})
it('automatically synchronizes the admin mark into the employee list within the next poll',async()=>{
  await mount('employee');expect(document.querySelector('.spot-check-badge')).toBeNull();mocks.get.mockResolvedValue([marked()]);await vi.advanceTimersByTimeAsync(3000);await flush()
  expect(document.querySelector('.spot-check-badge')?.textContent).toContain('已抽检');expect(document.querySelector('.spot-check-button')).toBeNull();expect(document.querySelector('.finance-review')?.textContent).toBe('审核通过')
  mocks.get.mockResolvedValue([row({spotChecked:false,_reviewVersion:4})]);await vi.advanceTimersByTimeAsync(3000);await flush()
  expect(document.querySelector('.spot-check-badge')).toBeNull();expect(document.querySelector('.spot-check-button')).toBeNull();expect(document.querySelector('.finance-review')?.textContent).toBe('审核通过')
})
it('does not optimistically mark failed writes, and excludes archived records from actions',async()=>{
  await mount('super_admin',[row(),row({id:'archive',lifecycleState:'archived'})]);expect(document.querySelectorAll('.spot-check-button')).toHaveLength(1)
  mocks.patch.mockRejectedValue(new Error('报价内容已更新'));document.querySelector<HTMLButtonElement>('.spot-check-button')!.click();await flush()
  expect(document.querySelector('.spot-check-badge')).toBeNull();expect(document.body.textContent).toContain('报价内容已更新')
})
it('sends the selected inspection filter to the server and clears it on reset',async()=>{
  await mount('super_admin');const select=document.querySelector<HTMLSelectElement>('[aria-label="抽检筛选"]')!;select.value='checked';select.dispatchEvent(new Event('change',{bubbles:true}));await flush();await vi.advanceTimersByTimeAsync(250);await flush()
  expect(mocks.page.mock.lastCall?.[1]).toMatchObject({spotCheck:'checked'});Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='重置')!.click();await flush();await vi.advanceTimersByTimeAsync(250);await flush();expect(mocks.page.mock.lastCall?.[1]).toMatchObject({spotCheck:''})
})
