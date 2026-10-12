// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import Dialog from './FobBatchQuoteDialog.vue'
import { FOB_SMALL_ORDER_POLICY, loadFobQuoteProduct, type FobQuoteProduct } from '@/services/fobQuotation'
import { validateFobQuotation } from '@/services/fobQuotationRecords'
import { saveQuotationBatch } from '@/services/quotationBatchSubmission'
import { ApiError } from '@/services/http'
import { setPricingTestPermissions, clearPricingTestPermissions } from '@/test/pricingPermissions'
vi.mock('@/services/fobQuotation', async original => ({ ...await original<typeof import('@/services/fobQuotation')>(), loadFobQuoteProduct: vi.fn() }))
vi.mock('@/services/quotationBatchSubmission', () => ({ saveQuotationBatch: vi.fn() }))
vi.mock('@/services/fobQuotationRecords', () => ({ validateFobQuotation: vi.fn(async (_name, snapshot) => structuredClone(snapshot)) }))
vi.mock('@/services/customerQuoteSheetRenderer', () => ({ renderCustomerQuoteSheet: vi.fn().mockResolvedValue([]), copyQuoteSheetImage: vi.fn() }))
let app: App | undefined
const product = (sku:string):FobQuoteProduct => ({sku,source:'fob',category:'日用品',weight:'50g',updatedAt:'2026-10-11T00:00:00Z',notices:[],parsed:{minOrderQty:3,orderMultiple:1,priceTiers:[{minQty:3,maxQty:null,unitPriceCny:3.9,unit:'件'}],freight:{quantity:100,totalFreightCny:17,unitFreightCny:.17,basis:'100件运费17÷100',estimated:false}}})
const tick=async()=>{for(let i=0;i<12;i++){await Promise.resolve();await nextTick()}}
function mount(){const host=document.createElement('div');document.body.append(host);app=createApp(Dialog,{rate:6.7,policy:FOB_SMALL_ORDER_POLICY});app.mount(host)}
function button(text:string){return [...document.querySelectorAll('button')].find(b=>b.textContent?.includes(text))!}
async function paste(text:string){const el=document.querySelector('textarea')!;el.value=text;el.dispatchEvent(new Event('input',{bubbles:true}));button('查询粘贴清单').click();await tick()}
beforeEach(()=>{setPricingTestPermissions();vi.mocked(loadFobQuoteProduct).mockImplementation(async sku=>product(sku));vi.mocked(validateFobQuotation).mockImplementation(async (_name,snapshot)=>JSON.parse(JSON.stringify(snapshot)))})
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.clearAllMocks();clearPricingTestPermissions()})
it('keeps missing quantity, missing SKU and network failure distinct, repairs quantity and exports only selected valid rows',async()=>{
  vi.mocked(loadFobQuoteProduct).mockImplementation(async sku=>{if(sku==='MISSING')throw new ApiError('missing',404,'NOT_FOUND','x');if(sku==='TIMEOUT')throw new DOMException('timeout','TimeoutError');return product(sku)})
  mount();await paste('OK 3\nNOQUANTITY\nMISSING 4\nTIMEOUT 5')
  expect(document.body.textContent).toContain('请填写报价数量');expect(document.body.textContent).toContain('未找到SKU');expect(document.body.textContent).toContain('不代表SKU不存在')
  expect(button('生成1行').disabled).toBe(false)
  const input=document.querySelector<HTMLInputElement>('[aria-label="第2行数量"]')!;input.value='45';input.dispatchEvent(new Event('input',{bubbles:true}));await tick()
  button('生成2行').click();await tick();expect(validateFobQuotation).toHaveBeenCalledTimes(1)
  const snapshot=vi.mocked(validateFobQuotation).mock.calls[0]![1];expect(snapshot.sheet.rows.map(r=>r.sku)).toEqual(['OK','NOQUANTITY']);expect(snapshot.sheet.rows.map(r=>r.prices)).toEqual([[.93,.95],[.76,.77]])
  expect(document.body.textContent).toContain('条FOB报价记录');expect(saveQuotationBatch).not.toHaveBeenCalled()
})
it('queries twenty items with bounded concurrency and keeps save retry idempotent',async()=>{
  let active=0,max=0;vi.mocked(loadFobQuoteProduct).mockImplementation(async sku=>{active++;max=Math.max(max,active);await Promise.resolve();active--;return product(sku)})
  mount();await paste(Array.from({length:20},(_,i)=>`SKU${i} 3`).join('\n'));await tick();expect(max).toBeLessThanOrEqual(4)
  button('生成20行').click();await tick();expect(vi.mocked(validateFobQuotation).mock.calls[0]![1].items).toHaveLength(20)
  const customer=[...document.querySelectorAll('label')].find(l=>l.textContent?.includes('客户名称'))!.querySelector('input')!;customer.value='客户';customer.dispatchEvent(new Event('input',{bubbles:true}));await tick()
  vi.mocked(saveQuotationBatch).mockRejectedValueOnce(new Error('网络中断')).mockResolvedValueOnce({batchNo:'QT-BATCH',records:Array.from({length:20},(_,i)=>({id:String(i),no:'QT'+i,primarySku:'SKU'+i,quoteMode:'fob'}))} as never)
  button('条FOB报价记录').click();await tick();button('条FOB报价记录').click();await tick()
  expect(saveQuotationBatch).toHaveBeenCalledTimes(2);expect(vi.mocked(saveQuotationBatch).mock.calls[0]![1]).toBe(vi.mocked(saveQuotationBatch).mock.calls[1]![1]);expect(document.body.textContent).toContain('QT-BATCH')
})
it('does not expose costs to employees and blocks stale server previews',async()=>{
  setPricingTestPermissions(['quote','myRecords']);mount();await paste('SKU 3');expect(document.body.textContent).not.toContain('采购成本明细')
  vi.mocked(validateFobQuotation).mockRejectedValueOnce(new Error('SKU：采购资料已更新，请重新查询'))
  button('生成1行').click();await tick();expect(document.body.textContent).toContain('采购资料已更新');expect(document.body.textContent).not.toContain('条FOB报价记录')
})
