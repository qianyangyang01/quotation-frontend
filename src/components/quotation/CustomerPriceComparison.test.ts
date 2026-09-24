// @vitest-environment happy-dom
import { afterEach,beforeEach,expect,it,vi } from 'vitest'
import { createApp,h,nextTick,reactive,type App } from 'vue'
import Comparison from './CustomerPriceComparison.vue'
import { normalizeQuotationRecord,updateQuotationRecord,type QuotationRecord } from '@/data/quotationRecords'
vi.mock('@/data/quotationRecords',async original=>({...await original<typeof import('@/data/quotationRecords')>(),updateQuotationRecord:vi.fn()}))
const update=vi.mocked(updateQuotationRecord)
let app:App
function record(id='one') {return normalizeQuotationRecord({id,no:`QT-${id}`,_version:2,customQuoteQuantity:4,quoteOptions:[{id:'yanwen',country:'US',carrier:'Yanwen',channel:'A',rule:'',eta:'',quote1Usd:2,quote2Usd:3,quote3Usd:null,quoteCustomUsd:5}],customerQuote:{quantities:[1,2,4],rows:[{optionId:'yanwen',prices:[1.8,2.7,4.6]}]}})!}
function mount(canEdit=true) {
  const state=reactive({record:record(),canEdit});const host=document.createElement('div');document.body.append(host)
  const saved=vi.fn((record:QuotationRecord)=>{state.record=record})
  app=createApp({render:()=>h(Comparison,{...state,onSaved:saved})});app.mount(host);return {state,saved}
}
const button=(label:string)=>Array.from(document.querySelectorAll('button')).find(b=>b.textContent===label)!
async function settle(){for(let i=0;i<12;i++)await nextTick()}
beforeEach(()=>{update.mockReset()})
afterEach(()=>{app?.unmount();document.body.innerHTML=''})
it('saves only customer prices with the expected version and renders the final comparison',async()=>{
  const {state}=mount(), original=JSON.stringify(state.record.quoteOptions)
  button('编辑客户报价').click();await settle()
  const input=document.querySelector<HTMLInputElement>('[aria-label="yanwen 2客户报价"]')!;input.value='2.60';input.dispatchEvent(new Event('input'));await settle()
  update.mockImplementation(async(_id,patch)=>({...record(),customerQuote:patch.customerQuote,_version:3}))
  button('保存客户报价').click();await settle()
  expect(update).toHaveBeenCalledWith('one',{customerQuote:{quantities:[1,2,4],rows:[{optionId:'yanwen',prices:[1.8,2.6,4.6]}]}},2)
  expect(document.body.textContent).toContain('-13.33%');expect(JSON.stringify(state.record.quoteOptions)).toBe(original)
})
it('preserves the quotation contact when editing prices from the comparison panel',async()=>{
  const {state}=mount()
  state.record={...record(),_version:3,customerQuote:{...record().customerQuote!,contact:{agent:'Vivian',whatsapp:'+111'}}};await settle()
  button('编辑客户报价').click();await settle()
  update.mockImplementation(async(_id,patch)=>({...state.record,customerQuote:patch.customerQuote,_version:4}))
  button('保存客户报价').click();await settle()
  expect(update.mock.lastCall![1].customerQuote?.contact).toEqual({agent:'Vivian',whatsapp:'+111'})
})
it('blocks repeat saves and does not apply a late response to another record',async()=>{
  const {state,saved}=mount();let finish!:(value:QuotationRecord)=>void
  update.mockReturnValue(new Promise(resolve=>{finish=resolve}))
  button('编辑客户报价').click();await settle();const save=button('保存客户报价')
  save.click();save.click();await settle();expect(update).toHaveBeenCalledTimes(1)
  state.record=record('two');await settle();finish({...record(),_version:3});await settle()
  expect(saved).not.toHaveBeenCalled();expect(state.record.id).toBe('two')
})
it('keeps edits on version conflict, shows reload action, and rejects malformed amounts before HTTP',async()=>{
  mount();button('编辑客户报价').click();await settle()
  const input=document.querySelector<HTMLInputElement>('[aria-label="yanwen 2客户报价"]')!
  input.value='2.123';input.dispatchEvent(new Event('input'));await settle();button('保存客户报价').click();await settle()
  expect(update).not.toHaveBeenCalled();expect(document.querySelector('[role=alert]')?.textContent).toContain('两位小数')
  input.value='2.60';input.dispatchEvent(new Event('input'));await settle()
  update.mockRejectedValue(Object.assign(new Error('版本冲突'),{status:409}));button('保存客户报价').click();await settle()
  expect(input.value).toBe('2.60');expect(button('重新加载记录（放弃未保存修改）')).toBeTruthy()
})
it('keeps other owners read-only',()=>{mount(false);expect(button('编辑客户报价')).toBeUndefined()})

it('does not publish a stale save after the user leaves the detail tab',async()=>{
  const {saved}=mount();let finish!:(value:QuotationRecord)=>void
  update.mockReturnValue(new Promise(resolve=>{finish=resolve}))
  button('确认报价').click();await settle();app.unmount()
  finish({...record(),_version:3,quoteConfirmed:true});await settle()
  expect(saved).not.toHaveBeenCalled()
})

it('marks a quote won without requiring or changing prices, confirmation, or deal amounts',async()=>{
  const {state}=mount();let finish!:(value:QuotationRecord)=>void
  update.mockReturnValue(new Promise(resolve=>{finish=resolve}))
  const mark=button('标记已成交');mark.click();mark.click();await settle()
  expect(update).toHaveBeenCalledTimes(1);expect(update).toHaveBeenCalledWith('one',{status:'won'},2)
  finish({...record(),status:'won',_version:3});await settle()
  expect(button('已成交').disabled).toBe(true);expect(state.record.customerQuote).toEqual(record().customerQuote)
  expect(state.record.quoteConfirmed).toBe(false)
})

it('confirms the current saved version once and displays server confirmation metadata',async()=>{
  const {state}=mount();let finish!:(value:QuotationRecord)=>void
  update.mockReturnValue(new Promise(resolve=>{finish=resolve}))
  const confirm=button('确认报价');confirm.click();confirm.click();await settle()
  expect(update).toHaveBeenCalledTimes(1);expect(update).toHaveBeenCalledWith('one',{quoteConfirmed:true},2)
  expect(button('编辑客户报价').disabled).toBe(true)
  finish({...record(),_version:3,status:'won',quoteConfirmed:true,quoteConfirmedBy:'业务员',quoteConfirmedAt:'2026-09-12T06:00:00Z'});await settle()
  expect(button('已处理').disabled).toBe(true);expect(document.body.textContent).toContain('已确认报价 · 业务员')
  expect(state.record.status).toBe('won')
})
it('does not confirm unsaved prices and handles a competing edit conflict',async()=>{
  mount();button('编辑客户报价').click();await settle();expect(button('确认报价')).toBeUndefined()
  button('取消').click();await settle();update.mockRejectedValue(Object.assign(new Error('报价已被修改'),{status:409}))
  button('确认报价').click();await settle()
  expect(button('已处理')).toBeUndefined();expect(document.querySelector('[role=alert]')?.textContent).toContain('报价已被修改')
  expect(button('重新加载记录（放弃未保存修改）')).toBeTruthy()
})

it('does not roll the same record back when an older save response arrives after a newer refresh',async()=>{
  const {state,saved}=mount();let finish!:(value:QuotationRecord)=>void
  update.mockReturnValue(new Promise(resolve=>{finish=resolve}))
  button('编辑客户报价').click();await settle();button('保存客户报价').click();await settle()
  state.record={...record(),_version:4,customerQuote:{quantities:[1],rows:[{optionId:'yanwen',prices:[1.5]}]}};await settle()
  finish({...record(),_version:3});await settle()
  expect(saved).not.toHaveBeenCalled();expect(state.record.customerQuote!.rows[0].prices[0]).toBe(1.5)
})
