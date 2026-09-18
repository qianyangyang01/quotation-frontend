// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import View from './QuotationRecordsView.vue'
import { authState } from '@/data/authStore'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
const mocks=vi.hoisted(()=>({page:vi.fn(),get:vi.fn(),patch:vi.fn()}))
vi.mock('@/data/quotationRecordQuery',()=>({loadRecordPage:mocks.page,loadFilteredRecords:vi.fn(),loadRecord:vi.fn(),recentRecordDates:()=>({startDate:'',endDate:''})}))
vi.mock('@/services/http',()=>({api:{get:mocks.get,patch:mocks.patch},setRequestAccount:vi.fn()}))
vi.mock('@/data/purchaseStore',()=>({loadPurchaseProducts:()=>Promise.resolve([])}))
vi.mock('vue-router',()=>({useRoute:()=>({query:{}})}))
let app:App
const flush=async()=>{await nextTick();await Promise.resolve();await nextTick();await Promise.resolve();await nextTick()}
const saved=()=>normalizeQuotationRecord({id:'r',no:'QT-1',salespersonAccount:'EMPLOYEE',_version:2,financeReviewStatus:'pending'})!
async function mount(role:'super_admin'|'employee',scope:'mine'|'company') {
  authState.current={id:role,name:role,account:role==='employee'?'EMPLOYEE':'ADMIN',role,status:'enabled',mustChangePassword:false,passwordUpdatedAt:''}
  authState.permissions=role==='employee'?['myRecords']:['allRecords']
  mocks.page.mockResolvedValue({items:[saved()],page:0,size:10,total:1,totalPages:1,summary:{pending:1,won:0,lost:0,total:1},countries:[]})
  mocks.get.mockResolvedValue([{id:'r',_version:2,financeReviewStatus:'pending'}])
  const host=document.createElement('div');document.body.append(host);app=createApp(View,{scope});app.component('RouterLink',{template:'<a><slot /></a>'});app.mount(host);await flush()
}
afterEach(()=>{app?.unmount();document.body.innerHTML='';authState.current=null;authState.permissions=[];vi.useRealTimers();vi.resetAllMocks()})
it('admin dropdown saves an explicit version and immediately displays success in green',async()=>{
  vi.useFakeTimers();await mount('super_admin','company')
  mocks.patch.mockResolvedValue({...saved(),_version:3,financeReviewStatus:'approved',financeReviewedBy:'ADMIN'})
  const select=document.querySelector('select.finance-review') as HTMLSelectElement
  select.value='approved';select.dispatchEvent(new Event('change'));await flush()
  expect(mocks.patch).toHaveBeenCalledWith('/quotations/r/finance-review',{_version:2,financeReviewStatus:'approved'})
  expect(select.classList.contains('approved')).toBe(true)
  expect(select.value).toBe('approved')
})
it('employee cannot review and receives the changed status while the page remains open',async()=>{
  vi.useFakeTimers();await mount('employee','mine')
  expect(document.querySelector('select.finance-review')).toBeNull()
  mocks.get.mockResolvedValue([{id:'r',_version:3,financeReviewStatus:'rejected',financeReviewedBy:'ADMIN'}])
  await vi.advanceTimersByTimeAsync(3000);await flush()
  expect(document.querySelector('strong.finance-review')?.textContent).toBe('财务已审核-价格有误不可报价')
  expect(document.querySelector('strong.finance-review')?.classList.contains('rejected')).toBe(true)
})
it('failed review retains saved status and asks for a fresh version instead of optimistic approval',async()=>{
  vi.useFakeTimers();await mount('super_admin','company');mocks.patch.mockRejectedValue(new Error('报价记录已被其他用户修改，请刷新后重试'))
  const select=document.querySelector('select.finance-review') as HTMLSelectElement
  select.value='approved';select.dispatchEvent(new Event('change'));await flush()
  expect(select.value).toBe('pending');expect(document.body.textContent).toContain('报价记录已被其他用户修改')
})
