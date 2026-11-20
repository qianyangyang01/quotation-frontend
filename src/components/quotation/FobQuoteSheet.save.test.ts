// @vitest-environment happy-dom
import { createApp, h, nextTick, ref, type App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import Sheet from './FobQuoteSheet.vue'
import { saveFobQuotation } from '@/services/fobQuotationRecords'
import { renderCustomerQuoteSheet } from '@/services/customerQuoteSheetRenderer'
import { FOB_SMALL_ORDER_POLICY, type FobQuoteProduct } from '@/services/fobQuotation'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
vi.mock('@/services/fobQuotationRecords',()=>({saveFobQuotation:vi.fn()}))
vi.mock('@/services/customerQuoteSheetRenderer',()=>({renderCustomerQuoteSheet:vi.fn().mockResolvedValue([]),copyQuoteSheetImage:vi.fn()}))
vi.mock('@/services/customerQuoteSheetClipboard',()=>({copyQuoteSheetData:vi.fn()}))
const product:FobQuoteProduct={sku:'FOB-SAVE',source:'fob',category:'内裤',weight:'60 g',updatedAt:'2026-10-09T00:00:00Z',notices:[],parsed:{minOrderQty:1,orderMultiple:1,freight:{quantity:100,totalFreightCny:0,unitFreightCny:0,estimated:false,basis:'包邮'},priceTiers:[{minQty:1,maxQty:null,unitPriceCny:7.2,unit:'件'}]}}
let app:App
const tick=async()=>{await nextTick();await Promise.resolve();await nextTick()}
function mount(extra:Record<string,unknown>={}){const host=document.createElement('div');document.body.append(host);app=createApp(Sheet,{product,rate:6.7,quantity:'26',policy:FOB_SMALL_ORDER_POLICY,...extra});app.mount(host)}
function button(text:string){return Array.from(document.querySelectorAll('button')).find(b=>b.textContent?.includes(text))!}
async function customer(value:string){const input=document.querySelector<HTMLInputElement>('[aria-label="FOB客户名称"]')!;input.value=value;input.dispatchEvent(new Event('input',{bubbles:true}));await tick()}
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.clearAllMocks()})
it('requires a customer and saves exact prices, notes and both column and row order',async()=>{
  vi.mocked(saveFobQuotation).mockResolvedValue(normalizeQuotationRecord({id:'r',no:'FOB-1',quoteMode:'fob'})!)
  mount();await tick();button('保存FOB报价').click();await tick();expect(saveFobQuotation).not.toHaveBeenCalled()
  await customer('客户甲');button('编辑报价单').click();await tick()
  const note=document.querySelector<HTMLTextAreaElement>('[aria-label="FOB第 1 条报价说明"]')!;note.value='Edited notes';note.dispatchEvent(new Event('input',{bubbles:true}));await tick()
  document.querySelector('[aria-label="第2行排序，上下键移动"]')!.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowUp',bubbles:true}));await tick()
  document.querySelector('[aria-label="不报关价格列排序，左右键移动"]')!.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowLeft',bubbles:true}));await tick()
  const target=document.querySelector('[aria-label="prices列排序，左右键移动"]')!;target.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowLeft',bubbles:true}));await tick()
  button('保存FOB报价').click();await tick()
  const [name,snapshot]=vi.mocked(saveFobQuotation).mock.calls[0]!
  expect(name).toBe('客户甲');expect(snapshot.sheet.notes?.[0]).toBe('Edited notes')
  expect(snapshot.sheet.columnOrder).toEqual(['number','prices','sku'])
  expect(snapshot.sheet.quantityLabels).toEqual(['Without declaration','With declaration'])
  expect(snapshot.sheet.rows.map(r=>r.quantityRange)).toEqual(['26+ pcs','1–25 pcs'])
  expect(snapshot.sheet.rows[0]!.prices).toEqual([1.37,1.35]);expect(document.body.textContent).toContain('已保存：FOB-1')
})
it('blocks double clicks and keeps the idempotency key for timeout retries, changes it after edits',async()=>{
  let reject!:(e:Error)=>void;vi.mocked(saveFobQuotation).mockImplementationOnce(()=>new Promise((_,bad)=>{reject=bad})).mockRejectedValue(new Error('超时'))
  mount();await tick();await customer('甲');button('保存FOB报价').click();button('保存FOB报价').click();await tick();expect(saveFobQuotation).toHaveBeenCalledTimes(1)
  reject(new Error('超时'));await tick();button('保存FOB报价').click();await tick()
  expect(vi.mocked(saveFobQuotation).mock.calls[0]![2]).toBe(vi.mocked(saveFobQuotation).mock.calls[1]![2])
  await customer('乙');button('保存FOB报价').click();await tick();expect(vi.mocked(saveFobQuotation).mock.calls[2]![2]).not.toBe(vi.mocked(saveFobQuotation).mock.calls[0]![2])
})
it('renders historical snapshot without recalculation or editable/save actions',async()=>{
  const savedSheet={title:'Saved',agent:'A',date:'9 Oct 2026',quantityLabels:['With declaration','Without declaration'],rows:[{key:'saved',number:1,sku:'OLD',quantityRange:'100+',prices:[3.14,3.2],country:'',provider:'',shippingTime:'',sourceDescription:''}],notes:['Original'],issues:[]}
  mount({savedSheet,discount:.5,rate:999,quantity:'invalid',product:{...product,parsed:{...product.parsed,priceTiers:[]}}});await tick()
  expect(vi.mocked(renderCustomerQuoteSheet).mock.calls.at(-1)![0]).toEqual(savedSheet)
  expect(button('保存FOB报价')).toBeUndefined();expect(button('编辑报价单')).toBeUndefined()
  expect(normalizeQuotationRecord({id:'x',no:'x',quoteMode:'fob',quoteOptions:[],fob:{schemaVersion:1,product,rate:6.7,quantity:26,policy:FOB_SMALL_ORDER_POLICY,displayMode:'tiers',sheet:savedSheet}})?.quoteMode).toBe('fob')
})

it('saves discounted prices and the discount together and keeps historical prices intact',async()=>{
  vi.mocked(saveFobQuotation).mockResolvedValue(normalizeQuotationRecord({id:'r',no:'FOB-DISCOUNT',quoteMode:'fob'})!)
  mount({discount:.95});await tick();await customer('折扣验收');button('保存FOB报价').click();await tick()
  const snapshot=vi.mocked(saveFobQuotation).mock.calls[0]![1]
  expect(snapshot.discount).toBe(.95)
  expect(snapshot.sheet.rows.map(r=>r.prices)).toEqual([[1.44,1.47],[1.28,1.30]])
  expect(vi.mocked(renderCustomerQuoteSheet).mock.calls.at(-1)![0].rows.map(r=>r.prices)).toEqual([[1.44,1.47],[1.28,1.30]])
})
it('blocks save and output on an invalid discount',async()=>{
  mount({discount:0});await tick();await customer('无效折扣')
  expect(document.body.textContent).toContain('折扣须大于0')
  expect(button('保存FOB报价').disabled).toBe(true)
  expect(button('复制报价数据').disabled).toBe(true)
  expect(saveFobQuotation).not.toHaveBeenCalled()
})

it('invalidates the saved marker when discount changes even if rounding keeps the same prices',async()=>{
  const discount=ref(.95), host=document.createElement('div');document.body.append(host)
  app=createApp({render:()=>h(Sheet,{product,rate:6.7,quantity:'26',policy:FOB_SMALL_ORDER_POLICY,discount:discount.value})});app.mount(host)
  vi.mocked(saveFobQuotation).mockResolvedValue(normalizeQuotationRecord({id:'r',no:'FOB-SMALL-CHANGE',quoteMode:'fob'})!)
  await tick();await customer('折扣验收');button('保存FOB报价').click();await tick()
  expect(button('已保存').disabled).toBe(true)
  discount.value=.950001;await tick()
  expect(button('保存FOB报价').disabled).toBe(false)
  button('保存FOB报价').click();await tick()
  expect(vi.mocked(saveFobQuotation).mock.calls[1]![1].discount).toBe(.950001)
  expect(vi.mocked(saveFobQuotation).mock.calls[1]![2]).not.toBe(vi.mocked(saveFobQuotation).mock.calls[0]![2])
})
