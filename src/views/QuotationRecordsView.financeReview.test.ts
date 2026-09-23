// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import View from './QuotationRecordsView.vue'
import { authState } from '@/data/authStore'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
const mocks=vi.hoisted(()=>({page:vi.fn(),get:vi.fn(),patch:vi.fn(),load:vi.fn()}))
vi.mock('@/data/quotationRecordQuery',()=>({loadRecordPage:mocks.page,loadFilteredRecords:vi.fn(),loadRecord:mocks.load,recentRecordDates:()=>({startDate:'',endDate:''})}))
vi.mock('@/services/http',()=>({api:{get:mocks.get,patch:mocks.patch},setRequestAccount:vi.fn()}))
vi.mock('@/data/purchaseStore',()=>({loadPurchaseProducts:()=>Promise.resolve([])}))
vi.mock('vue-router',()=>({useRoute:()=>({query:{}})}))
let app:App
const flush=async()=>{for(let i=0;i<6;i++){await nextTick();await Promise.resolve()}}
const saved=()=>normalizeQuotationRecord({id:'r',no:'QT-1',salespersonAccount:'EMPLOYEE',_version:2,_reviewVersion:0,financeReviewStatus:'pending'})!
const claimed=()=>({...saved(),_reviewVersion:1,financeReviewStatus:'reviewing' as const,financeReviewClaimedBy:'ADMIN',financeReviewClaimedAccount:'ADMIN',financeReviewStartedAt:'2026-09-23T12:00:00Z'})
const button=(text:string)=>[...document.querySelectorAll('button')].find(b=>b.textContent===text) as HTMLButtonElement
async function mount(role:'super_admin'|'employee',scope:'mine'|'company') {
  authState.current={id:role,name:role,account:role==='employee'?'EMPLOYEE':'ADMIN',role,status:'enabled',mustChangePassword:false,passwordUpdatedAt:''};authState.permissions=role==='employee'?['myRecords']:['allRecords']
  mocks.page.mockResolvedValue({items:[saved()],page:0,size:10,total:1,totalPages:1,summary:{pending:1,won:0,lost:0,total:1},countries:[]});mocks.get.mockResolvedValue([saved()])
  const host=document.createElement('div');document.body.append(host);app=createApp(View,{scope});app.component('RouterLink',{template:'<a><slot /></a>'});app.mount(host);await flush()
}
afterEach(()=>{app?.unmount();document.body.innerHTML='';authState.current=null;authState.permissions=[];vi.useRealTimers();vi.resetAllMocks()})
it('requires claiming before completion and opens the exact returned quotation',async()=>{
  vi.useFakeTimers();await mount('super_admin','company');expect(button('审核完成 · 可报价')).toBeUndefined()
  mocks.patch.mockResolvedValue(claimed());button('开始审核').click();await flush()
  expect(mocks.patch).toHaveBeenLastCalledWith('/quotations/r/finance-review',{action:'claim',_version:2,_reviewVersion:0})
  expect(document.querySelector('.record-drawer')).not.toBeNull();expect(document.body.textContent).toContain('ADMIN审核中')
  mocks.patch.mockResolvedValue({...saved(),_reviewVersion:2,financeReviewStatus:'approved',financeReviewedBy:'ADMIN'})
  button('审核完成 · 可报价').click();await flush()
  expect(mocks.patch).toHaveBeenLastCalledWith('/quotations/r/finance-review',{action:'complete',financeReviewStatus:'approved',note:'',_version:2,_reviewVersion:1})
  expect(document.querySelector('.finance-review.approved')).not.toBeNull()
})
it('employees see live ownership without getting review buttons',async()=>{
  vi.useFakeTimers();await mount('employee','mine');expect(button('开始审核')).toBeUndefined()
  mocks.get.mockResolvedValue([claimed()]);await vi.advanceTimersByTimeAsync(3000);await flush()
  expect(document.body.textContent).toContain('ADMIN审核中');expect(button('审核完成 · 可报价')).toBeUndefined()
})
it('refreshes filtered list and count after claim, while keeping the claimed detail open',async()=>{
  vi.useFakeTimers();await mount('super_admin','company')
  const filter=document.querySelector('.filters select') as HTMLSelectElement;filter.value='finance-pending';filter.dispatchEvent(new Event('change'));await flush();await vi.advanceTimersByTimeAsync(250);await flush()
  mocks.patch.mockResolvedValue(claimed());mocks.page.mockResolvedValue({items:[],page:0,size:10,total:0,totalPages:0,summary:{pending:0,won:0,lost:0,total:0},countries:[]})
  button('开始审核').click();await flush();expect(mocks.page.mock.lastCall?.[1]).toMatchObject({status:'finance-pending'})
  expect(document.querySelector('[aria-label="报价记录分页"]')?.textContent).toContain('共 0 条');expect(document.querySelector('.record-drawer')).not.toBeNull()
})
it('failed claim is never displayed as successful ownership',async()=>{
  vi.useFakeTimers();await mount('super_admin','company');mocks.patch.mockRejectedValue(new Error('该报价已由其他财务审核中'));button('开始审核').click();await flush()
  expect(document.querySelector('.record-drawer')).toBeNull();expect(document.body.textContent).toContain('该报价已由其他财务审核中')
})
it('polling a newer price cannot silently approve the stale drawer snapshot',async()=>{
  vi.useFakeTimers();await mount('super_admin','company');mocks.patch.mockResolvedValue(claimed());button('开始审核').click();await flush()
  mocks.get.mockResolvedValue([{...claimed(),_version:3,_reviewVersion:2}]);await vi.advanceTimersByTimeAsync(3000);await flush()
  expect(button('审核完成 · 可报价').disabled).toBe(true);expect(document.body.textContent).toContain('报价内容已更新')
  mocks.load.mockResolvedValue({...claimed(),_version:3,_reviewVersion:2});button('重新加载详情').click();await flush();expect(button('审核完成 · 可报价').disabled).toBe(false)
})
it('a released and reclaimed quotation cannot be completed from the previous claim session',async()=>{
  vi.useFakeTimers();await mount('super_admin','company');mocks.patch.mockResolvedValue(claimed());button('开始审核').click();await flush()
  mocks.get.mockResolvedValue([{...claimed(),_reviewVersion:3}]);await vi.advanceTimersByTimeAsync(3000);await flush()
  expect(button('审核完成 · 可报价').disabled).toBe(true);expect(button('取消审核').disabled).toBe(true)
  expect(document.body.textContent).toContain('审核占用已变化');expect(mocks.patch).toHaveBeenCalledTimes(1)
})
