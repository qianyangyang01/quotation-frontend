// @vitest-environment happy-dom
import { createApp, nextTick, reactive, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import Dialog from './FobCorrectionDialog.vue'
import { FOB_SMALL_ORDER_POLICY, loadFobQuoteProduct, type FobQuoteProduct } from '@/services/fobQuotation'
import { fobBatchSnapshot } from '@/services/fobBatchQuotation'
import { validateFobQuotation } from '@/services/fobQuotationRecords'
import { submitFobCorrections } from '@/services/fobCorrections'
import { normalizeQuotationRecord, type QuotationRecord } from '@/data/quotationRecords'
import { api, ApiError } from '@/services/http'
import { setPricingTestPermissions, clearPricingTestPermissions } from '@/test/pricingPermissions'
vi.mock('@/services/http', async original => ({...await original<typeof import('@/services/http')>(),api:{get:vi.fn(),post:vi.fn()}}))
vi.mock('@/services/fobQuotation', async original => ({ ...await original<typeof import('@/services/fobQuotation')>(), loadFobQuoteProduct: vi.fn() }))
vi.mock('@/services/fobQuotationRecords', async original => ({...await original<typeof import('@/services/fobQuotationRecords')>(),validateFobQuotation:vi.fn()}))
vi.mock('@/services/fobCorrections', async original => ({...await original<typeof import('@/services/fobCorrections')>(),submitFobCorrections:vi.fn()}))
vi.mock('@/data/reviewNotifications',()=>({refreshReviewNotifications:vi.fn(),unreadReview:vi.fn(),acknowledgeReview:vi.fn()}))
const product=(sku:string,date='2026-10-11T00:00:00Z'):FobQuoteProduct=>({sku,source:'fob',category:'日用品',weight:'50g',updatedAt:date,notices:[],parsed:{minOrderQty:3,orderMultiple:1,priceTiers:[{minQty:3,maxQty:null,unitPriceCny:3.9,unit:'件'}],freight:{quantity:100,totalFreightCny:17,unitFreightCny:.17,basis:'100件运费17÷100',estimated:false}}})
const props=reactive({id:'quote-id',rate:6.7,policy:FOB_SMALL_ORDER_POLICY,financePending:false})
let app:App|undefined, record:QuotationRecord
const tick=async()=>{for(let i=0;i<30;i++){await Promise.resolve();await nextTick()}}
function button(text:string){return [...document.querySelectorAll('button')].find(b=>b.textContent?.includes(text))!}
async function mount(){const host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(Dialog,props)});app.mount(host);await tick()}
import {h} from 'vue'
beforeEach(()=>{
  props.rate=6.7;props.financePending=false;setPricingTestPermissions(['quote','myRecords'])
  const fob=fobBatchSnapshot(['OK','MISSING','TIMEOUT','UNCHANGED','APPROVED'].map((sku,i)=>({id:i+1,sku,quantity:'3',selected:true,formatError:'',product:product(sku,'2026-10-09T00:00:00Z')})),'quantity',6.7,FOB_SMALL_ORDER_POLICY,{title:'Saved',agent:'Agent',date:'2026-10-11',whatsapp:''})
  record=normalizeQuotationRecord({id:'quote-id',no:'QT1',quoteMode:'fob',customerName:'客户',salespersonAccount:'PRIVACY_TEST',_version:0,_reviewVersion:2,financeReviewStatus:'rejected',fob,fobItemReviews:fob.items!.map((_,index)=>({index,status:index===4?'approved':'rejected',reason:'自定义：请核实采购价格'}))})!
  vi.mocked(api.get).mockResolvedValue(record)
  vi.mocked(loadFobQuoteProduct).mockImplementation(async sku=>{if(sku==='MISSING')throw new ApiError('missing',404,'NOT_FOUND','x');if(sku==='TIMEOUT')throw new DOMException('timeout','TimeoutError');return product(sku,sku==='UNCHANGED'?'2026-10-09T00:00:00Z':undefined)})
  vi.mocked(validateFobQuotation).mockImplementation(async (_,snapshot)=>JSON.parse(JSON.stringify(snapshot)))
})
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.resetAllMocks();clearPricingTestPermissions()})
it('prefills returned items only and separates missing source, timeouts and unchanged data; submits successes only',async()=>{
  await mount();expect(loadFobQuoteProduct).not.toHaveBeenCalled();expect(document.querySelectorAll('tbody tr')).toHaveLength(4);expect(document.body.textContent).not.toContain('APPROVED');expect(document.body.textContent).toContain('自定义：请核实采购价格')
  button('重新查询异常项').click();await tick()
  expect(document.body.textContent).toContain('未找到SKU');expect(document.body.textContent).toContain('不代表SKU不存在');expect(document.body.textContent).toContain('资料、数量和计价参数未变化')
  expect(loadFobQuoteProduct).toHaveBeenCalledWith('OK','fob',expect.any(AbortSignal),true)
  expect(button('提交1项').disabled).toBe(false)
  vi.mocked(submitFobCorrections).mockResolvedValue({...record,_reviewVersion:3,fobRevision:1})
  button('提交1项').click();await tick()
  const [,items]=vi.mocked(submitFobCorrections).mock.calls[0]!
  expect(items.map(i=>i.index)).toEqual([0]);expect(items[0]!.snapshot.sheet.rows[0]!.prices).toEqual([.93,.95]);expect(document.querySelectorAll('tbody tr')).toHaveLength(3);expect(document.body.textContent).toContain('尚未审核通过')
})
it('keeps the same idempotency key on an uncertain response and invalidates results on finance changes',async()=>{
  await mount();button('重新查询异常项').click();await tick()
  vi.mocked(submitFobCorrections).mockRejectedValue(new Error('网络中断'))
  button('提交1项').click();await tick();button('提交1项').click();await tick()
  const calls=vi.mocked(submitFobCorrections).mock.calls;expect(calls).toHaveLength(2);expect(calls[0]![2]).toBe(calls[1]![2]);expect(calls[0]![1]).toEqual(calls[1]![1])
  props.rate=7;await tick();expect(button('提交0项').disabled).toBe(true);expect(document.body.textContent).toContain('财务参数已变化')
})
it('does not keep a submit-capable stale record after refresh failure',async()=>{
  await mount();button('重新查询异常项').click();await tick();vi.mocked(api.get).mockRejectedValue(new Error('读取失败'))
  button('刷新退回记录').click();await tick();expect(button('提交0项').disabled).toBe(true);expect(document.querySelectorAll('tbody tr')).toHaveLength(0)
})
it('ignores late successful query after unmount and validates new quantities before submission',async()=>{
  await mount();const quantity=document.querySelector<HTMLInputElement>('[aria-label="OK 报价数量"]')!;quantity.value='1';quantity.dispatchEvent(new Event('input',{bubbles:true}));await tick();button('重新查询异常项').click();await tick();expect(button('提交0项').disabled).toBe(true);expect(document.body.textContent).toMatch(/起订|最低|最小/)
  let resolve!:(p:FobQuoteProduct)=>void;vi.mocked(loadFobQuoteProduct).mockImplementation(()=>new Promise(r=>resolve=r))
  document.querySelector<HTMLButtonElement>('tbody button')!.click();await tick();app!.unmount();app=undefined;resolve(product('OK'));await tick();expect(submitFobCorrections).not.toHaveBeenCalled()
})
it('blocks finance initialization and access from another account',async()=>{
  props.financePending=true;await mount();expect(button('重新查询异常项').disabled).toBe(true);expect(loadFobQuoteProduct).not.toHaveBeenCalled()
  app!.unmount();record.salespersonAccount='OTHER';props.financePending=false;await mount();expect(document.body.textContent).toContain('只能处理自己的退回报价');expect(button('提交0项').disabled).toBe(true)
})
