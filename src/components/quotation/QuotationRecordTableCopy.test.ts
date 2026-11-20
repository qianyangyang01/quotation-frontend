// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import TableCopy from './QuotationRecordTableCopy.vue'
import { loadRecord } from '@/data/quotationRecordQuery'
import { normalizeQuotationRecord, type QuotationRecord } from '@/data/quotationRecords'
vi.mock('@/data/quotationRecordQuery',()=>({loadRecord:vi.fn()}))
let app:App
const write=vi.fn()
function record(id='one',price=10) {
  return normalizeQuotationRecord({id,no:'QT-'+id,primarySku:'001',salespersonName:'Local QA',_version:1,
    quoteOptions:[{id:'a',country:'US',countryCode:'US',carrier:'顺丰',channel:'顺丰商派',rule:'',eta:'7 days',quote1Usd:1,quote2Usd:2,quote3Usd:3,quoteCustomUsd:null}],
    customerQuote:{quantities:[1,2,3],rows:[{optionId:'a',prices:[price,price*2,price*3]}]}})!
}
function mount() {
  const state=reactive({record:record(),disabled:false})
  const host=document.createElement('div');document.body.append(host)
  app=createApp({render:()=>h(TableCopy,state)});app.mount(host);return state
}
async function settle(){for(let i=0;i<15;i++)await nextTick()}
beforeEach(()=>{
  vi.mocked(loadRecord).mockReset().mockResolvedValue(record('one',42.35))
  write.mockReset().mockResolvedValue(undefined)
  vi.stubGlobal('isSecureContext',true)
  vi.stubGlobal('navigator',{clipboard:{write}})
  vi.stubGlobal('ClipboardItem',class {constructor(public items:Record<string,Blob>){}})
})
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.unstubAllGlobals()})
it('loads the complete saved record lazily and copies its customer prices instead of list summary prices',async()=>{
  const state=mount(),before=JSON.stringify(state.record)
  expect(loadRecord).not.toHaveBeenCalled();expect(document.querySelector('.customer-sheet')).toBeNull()
  document.querySelector<HTMLButtonElement>('button')!.click();await settle()
  expect(loadRecord).toHaveBeenCalledExactlyOnceWith('one')
  expect(write).toHaveBeenCalledTimes(1)
  const html=await write.mock.lastCall![0][0].items['text/html'].text()
  expect(html).toContain('x:num="42.35"');expect(html).not.toContain('x:num="10.00"')
  expect(document.body.textContent).toContain('图文表格已复制')
  expect(document.querySelector('.customer-sheet')).toBeNull()
  expect(JSON.stringify(state.record)).toBe(before)
})
it('reports missing or inaccessible records without writing the clipboard',async()=>{
  vi.mocked(loadRecord).mockResolvedValue(null as unknown as QuotationRecord)
  mount();document.querySelector<HTMLButtonElement>('button')!.click();await settle()
  expect(write).not.toHaveBeenCalled();expect(document.querySelector('[role=alert]')?.textContent).toContain('无权查看')
  expect(document.querySelector<HTMLButtonElement>('button')!.disabled).toBe(false)
})
it('cancels a late detail read when the list row changes',async()=>{
  let finish!:(value:QuotationRecord)=>void
  vi.mocked(loadRecord).mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}))
  const state=mount();document.querySelector<HTMLButtonElement>('button')!.click();await settle()
  state.record=record('two');await settle();finish(record());await settle()
  expect(write).not.toHaveBeenCalled();expect(document.querySelector('[role=status]')).toBeNull()
})
it('cancels stale data before a delayed clipboard lock starts writing',async()=>{
  let unlock!:()=>Promise<void>
  vi.stubGlobal('navigator',{clipboard:{write},locks:{request:(_name:string,_options:unknown,callback:(lock:object)=>Promise<void>)=>new Promise<void>((resolve,reject)=>{unlock=async()=>{try{await callback({});resolve()}catch(error){reject(error)}}})}})
  const state=mount();document.querySelector<HTMLButtonElement>('button')!.click();await settle()
  state.record={...state.record,_version:2};await settle();await unlock();await settle()
  expect(write).not.toHaveBeenCalled();expect(document.querySelector('[role=status]')).toBeNull()
})
it('does not expose the ordinary table copy for FOB records',async()=>{
  const state=mount();state.record={...state.record,quoteMode:'fob'};await settle()
  expect(document.querySelector('button')).toBeNull();expect(loadRecord).not.toHaveBeenCalled()
})
