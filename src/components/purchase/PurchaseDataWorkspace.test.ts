// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import PurchaseDataWorkspace from './PurchaseDataWorkspace.vue'
import { normalizePurchaseRecord } from '@/data/purchaseStore'

const mocks=vi.hoisted(()=>({page:vi.fn(),stats:vi.fn(),save:vi.fn()}))
vi.mock('@/data/purchaseStore',async importOriginal=>({...await importOriginal<object>(),loadPurchaseProductPage:mocks.page,loadPurchaseStats:mocks.stats,upsertPurchaseProducts:mocks.save}))
let app:App
const row=(sku:string)=>normalizePurchaseRecord({sku,weightG:100,minOrderQty:1,purchasePriceCny:10,_version:5})
const page=(sku:string)=>({items:[row(sku)],total:1,totalPages:1,page:0,size:10})
async function flush(){await Promise.resolve();await nextTick();await Promise.resolve();await nextTick()}
async function mount(){const host=document.createElement('div');document.body.append(host);app=createApp(PurchaseDataWorkspace);app.mount(host);await flush()}
async function search(text:string){const input=document.querySelector('.toolbar input') as HTMLInputElement;input.value=text;input.dispatchEvent(new Event('input',{bubbles:true}));await nextTick()}
beforeEach(()=>{vi.useFakeTimers();mocks.page.mockResolvedValue(page('INITIAL'));mocks.stats.mockResolvedValue({total:1,ready:1,pending:0,generatedSku:0})})
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.clearAllMocks();vi.useRealTimers()})

it('aborts superseded searches immediately and never applies their late results or repeats statistics',async()=>{
  await mount()
  let resolveOld!:(value:ReturnType<typeof page>)=>void
  mocks.page.mockImplementationOnce(()=>new Promise(resolve=>{resolveOld=resolve}))
  await search('OLD');await vi.advanceTimersByTimeAsync(250)
  const oldSignal=mocks.page.mock.calls.at(-1)![3] as AbortSignal
  await search('NEW');expect(oldSignal.aborted).toBe(true)
  mocks.page.mockResolvedValueOnce(page('NEW'))
  await vi.advanceTimersByTimeAsync(250);await flush()
  resolveOld(page('OLD'));await flush()
  expect(document.querySelector('table')?.textContent).toContain('NEW')
  expect(document.querySelector('table')?.textContent).not.toContain('OLD')
  expect(mocks.stats).toHaveBeenCalledTimes(1)
})

it('renders the page even when the independent statistics request fails',async()=>{
  mocks.stats.mockRejectedValueOnce(new Error('统计不可用'))
  await mount()
  expect(document.querySelector('table')?.textContent).toContain('INITIAL')
  expect(document.body.textContent).toContain('统计不可用')
})

it('reloads the last available page after concurrent deletions shrink the page count',async()=>{
  mocks.page.mockResolvedValueOnce({...page('FIRST'),total:20,totalPages:2})
  await mount()
  mocks.page.mockResolvedValueOnce({...page(''),items:[],total:1,totalPages:1}).mockResolvedValueOnce(page('LAST'))
  Array.from(document.querySelectorAll('button')).find(b=>b.textContent?.trim()==='2')!.click();await flush();await flush()
  expect(mocks.page.mock.calls.at(-1)![1]).toBe(0)
  expect(document.querySelector('table')?.textContent).toContain('LAST')
  expect(mocks.stats).toHaveBeenCalledTimes(1)
})

it('uses authoritative save data on the unfiltered first page without a second list read',async()=>{
  await mount()
  Array.from(document.querySelectorAll('button')).find(b=>b.textContent?.includes('新增采购资料'))!.click();await flush()
  const sku=Array.from(document.querySelectorAll('.form-grid label')).find(l=>l.textContent?.includes('SKU'))!.querySelector('input')!
  sku.value='NEW-SAVED';sku.dispatchEvent(new Event('input',{bubbles:true}));await nextTick()
  mocks.save.mockResolvedValueOnce([row('NEW-SAVED')])
  const save=Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='保存资料')!
  save.click();save.click();await flush()
  expect(mocks.save).toHaveBeenCalledTimes(1)
  expect(mocks.page).toHaveBeenCalledTimes(1)
  expect(document.querySelector('table')?.textContent).toContain('NEW-SAVED')
  expect(mocks.stats).toHaveBeenCalledTimes(2)
})
