// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import View from './BatchShippingQuotationView.vue'
import { saveBatchShipping } from '@/services/batchSingleQuotation'
import { authState } from '@/data/authStore'
import { setPricingTestPermissions, clearPricingTestPermissions } from '@/test/pricingPermissions'

const { prime, resetChannels, calculate, item } = vi.hoisted(() => {
  const item = (id: string) => ({ entryId: id, input: { quoteMode:'shipping-only', primarySku:'', manualPricing:{costCny:0,weightGrams:200}, shippingNote:'重复备注', customerName:'测试客户',customerGrade:'S',exchangeRate:6.68,commissionThreshold:1,quoteOptions:[{id:'option',quoteSheetKey:'channel',country:'US',carrier:'4PX',channel:'TEST',rule:'TEST',eta:'7-12 working days',quote1Usd:5,quote2Usd:10,quote3Usd:15,quoteCustomUsd:null}],systemQuantityQuotes:{quantities:[1],rows:[{optionId:'option',prices:[5]}]},customerQuote:{quantities:[1],rows:[{optionId:'option',prices:[5]}]} }, conditions:{customerName:'测试客户'}, sheet:{title:'Shipping Quote',agent:'QA',date:'16 Nov 2026',quantityLabels:['1 pc'],issues:[],rows:[{key:'channel',sku:id+' · 200g · 重复备注',prices:[5],number:1,country:'United States',provider:'4PX',shippingTime:'7-12 working days'}]} })
  return { prime: vi.fn(), resetChannels: vi.fn(), calculate: vi.fn(async(id:string)=>item(id)), item }
})
vi.mock('vue-router', () => ({ onBeforeRouteLeave:vi.fn(), RouterLink:{template:'<a><slot /></a>'} }))
vi.mock('@/services/customerQuoteSheetRenderer', async original => ({ ...await original<typeof import('@/services/customerQuoteSheetRenderer')>(),preloadQuoteSheetAssets:vi.fn().mockResolvedValue([]) }))
vi.mock('@/services/batchSingleQuotation', async original => ({ ...await original<typeof import('@/services/batchSingleQuotation')>(),saveBatchShipping:vi.fn() }))
vi.mock('./QuotationSystemView.vue', () => ({ __esModule:true, default:{props:['beforeShippingQuery'],setup(_:unknown,{expose}:{expose:(value:unknown)=>void}) { expose({batchReady:true,primeBatchShipping:prime,calculateBatchItem:calculate,restoreBatchSample:vi.fn(),resetBatchShippingChannels:resetChannels});return {first:item('1')} },template:'<div data-engine><button @click="beforeShippingQuery()">加载渠道</button><button @click="$emit(\'batch-captured\',first)">生成测试批次</button></div>'} }))
let app:App
const button=(label:string)=>[...document.querySelectorAll('button')].find(button=>button.textContent?.trim()===label)!
async function click(label:string) { await vi.waitFor(()=>expect(button(label)).toBeTruthy());button(label).click();await nextTick() }
async function mount(count=20) {
  const host=document.createElement('div');document.body.append(host);app=createApp(View);app.component('RouterLink',{template:'<a><slot /></a>'});app.mount(host);await nextTick()
  await click('从 Excel 粘贴')
  const field=document.querySelector('textarea')!;field.value=Array(count).fill('200\t重复备注').join('\n');field.dispatchEvent(new Event('input'));await nextTick();await click('确认粘贴')
}
async function prepare(count=20) { await mount(count);await click('加载渠道');await vi.waitFor(()=>expect(prime).toHaveBeenCalledOnce()) }
async function generate(count=20) { await prepare(count);await click('生成测试批次');await vi.waitFor(()=>expect(document.body.textContent).toContain(`${count}/${count}条已完成`)) }
function deferred<T>() { let resolve!:(value:T)=>void;const promise=new Promise<T>(yes=>{resolve=yes});return {promise,resolve} }
beforeEach(()=>{setPricingTestPermissions(['quote']);vi.clearAllMocks();calculate.mockImplementation(async id=>item(id));vi.mocked(saveBatchShipping).mockImplementation(async document=>({...document,no:'B-TEST',createdRecords:document.items.map((_item,i)=>({id:String(i),no:'Q'+i,primarySku:'',quoteMode:'shipping-only' as const}))}))})
afterEach(()=>{app?.unmount();document.body.innerHTML='';authState.current=null;vi.unstubAllGlobals();clearPricingTestPermissions()})

it('keeps duplicate weights independent across input pages and saves one record per row with one request',async()=>{
  await generate()
  expect(calculate.mock.calls.map(call=>call[0])).toEqual(Array.from({length:19},(_,i)=>String(i+2)))
  expect(document.querySelector('[data-engine]')).not.toBeNull()
  await click('保存20条报价记录');button('正在保存…')?.click()
  await vi.waitFor(()=>expect(document.body.textContent).toContain('已生成20条独立报价记录'))
  expect(saveBatchShipping).toHaveBeenCalledOnce()
  const documentSaved=vi.mocked(saveBatchShipping).mock.calls[0]![0]
  expect(new Set(documentSaved.items.map(item=>item.entryId)).size).toBe(20)
  expect(documentSaved.items.every(item=>item.input.manualPricing?.weightGrams===200)).toBe(true)
})
it('blocks partial save, retries only failures, and preserves successful rows',async()=>{
  calculate.mockImplementationOnce(async()=>{throw new Error('渠道超时')})
  await prepare(3);await click('生成测试批次')
  await vi.waitFor(()=>expect(document.body.textContent).toContain('渠道超时'))
  expect(button('保存3条报价记录').disabled).toBe(true)
  await click('仅重试失败 / 未完成项');await vi.waitFor(()=>expect(document.body.textContent).toContain('3/3条已完成'))
  expect(calculate.mock.calls.map(call=>call[0])).toEqual(['2','3','2'])
  await click('保存3条报价记录');await vi.waitFor(()=>expect(saveBatchShipping).toHaveBeenCalledOnce())
})
it('explicitly excludes failed entries without renumbering or saving them',async()=>{
  calculate.mockImplementationOnce(async()=>{throw new Error('缺价')})
  await prepare(3);await click('生成测试批次');await vi.waitFor(()=>expect(document.body.textContent).toContain('缺价'))
  await click('取消选入待处理项');await click('保存2条报价记录');await vi.waitFor(()=>expect(saveBatchShipping).toHaveBeenCalledOnce())
  expect(vi.mocked(saveBatchShipping).mock.calls[0]![0].items.map(item=>item.entryId)).toEqual(['1','3'])
})
it('stops after the in-flight entry, retains its result and resumes only unfinished entries',async()=>{
  const pending=deferred<ReturnType<typeof item>>();calculate.mockImplementationOnce(()=>pending.promise)
  await prepare(4);await click('生成测试批次');await vi.waitFor(()=>expect(calculate).toHaveBeenCalledOnce())
  await click('停止生成');pending.resolve(item('2'))
  await vi.waitFor(()=>expect(document.body.textContent).toContain('2条待处理'))
  expect(button('保存4条报价记录').disabled).toBe(true)
  await click('仅重试失败 / 未完成项');await vi.waitFor(()=>expect(document.body.textContent).toContain('4/4条已完成'))
  expect(calculate.mock.calls.map(call=>call[0])).toEqual(['2','3','4'])
})
it('retries ambiguous saves with the same key but deliberately regenerated batches use a new key',async()=>{
  vi.mocked(saveBatchShipping).mockRejectedValueOnce(new Error('响应中断'))
  await generate(2);await click('保存2条报价记录');await vi.waitFor(()=>expect(document.body.textContent).toContain('响应中断'))
  await click('保存2条报价记录');await vi.waitFor(()=>expect(document.body.textContent).toContain('已生成2条独立报价记录'))
  const calls=vi.mocked(saveBatchShipping).mock.calls;expect(calls[0]![1]).toBe(calls[1]![1])
  await click('修改条件 / 重新生成');await click('生成测试批次');await vi.waitFor(()=>expect(button('保存2条报价记录')?.disabled).toBe(false))
  await click('保存2条报价记录');await vi.waitFor(()=>expect(saveBatchShipping).toHaveBeenCalledTimes(3))
  expect(calls[2]![1]).not.toBe(calls[0]![1])
})
it('blocks leaving while computing and ignores results after the account changes',async()=>{
  const pending=deferred<ReturnType<typeof item>>();calculate.mockImplementationOnce(()=>pending.promise)
  await prepare(3);await click('生成测试批次');await vi.waitFor(()=>expect(calculate).toHaveBeenCalledOnce())
  const leave=vi.mocked(onBeforeRouteLeave).mock.calls.at(-1)![0]
  expect(await leave.call(undefined as never,{} as never,{} as never,vi.fn())).toBe(false)
  authState.current={id:'new-account',account:'new-account',name:'new',role:'employee',status:'enabled',mustChangePassword:false,passwordUpdatedAt:''}
  await nextTick();pending.resolve(item('2'));await nextTick();await nextTick()
  expect(document.body.textContent).toContain('批量录入代发数据')
  expect(document.body.textContent).not.toContain('3/3条已完成')
  expect(saveBatchShipping).not.toHaveBeenCalled()
})
it('rejects a 51-row paste atomically and supports all 50 rows at the accepted limit',async()=>{
  await mount(51);expect(document.body.textContent).toContain('50');expect(document.querySelectorAll('.table-scroll tbody tr')).toHaveLength(1)
  const field=document.querySelector('textarea')!;field.value=Array(50).fill('200').join('\n');field.dispatchEvent(new Event('input'));await nextTick();await click('确认粘贴')
  expect(document.body.textContent).toContain('共50条 · 已选50条');expect(button('＋ 添加一行').disabled).toBe(true)
  await click('加载渠道');await vi.waitFor(()=>expect(prime).toHaveBeenCalledOnce());await click('生成测试批次')
  await vi.waitFor(()=>expect(document.body.textContent).toContain('50/50条已完成'));expect(calculate).toHaveBeenCalledTimes(49)
})



it('loads directly from the persistent paginated input list and resets channels before editing',async()=>{
  await prepare(20)
  expect(document.querySelectorAll('.input-card tbody tr')).toHaveLength(10)
  expect(document.querySelector<HTMLInputElement>('[aria-label="第1条克重"]')?.readOnly).toBe(true)
  expect(document.body.textContent).not.toContain('下一步：选择渠道')
  await click('下一页')
  expect(document.querySelector<HTMLInputElement>('[aria-label="第11条克重"]')?.value).toBe('200')
  await click('修改录入清单')
  expect(resetChannels).toHaveBeenCalledOnce()
  expect(document.querySelector<HTMLInputElement>('[aria-label="第11条克重"]')?.readOnly).toBe(false)
  await click('加载渠道')
  await vi.waitFor(()=>expect(prime).toHaveBeenCalledTimes(2))
})
it('validates all input pages before loading any channels',async()=>{
  await mount(20)
  await click('下一页')
  const weight=document.querySelector<HTMLInputElement>('[aria-label="第11条克重"]')!
  weight.value='0';weight.dispatchEvent(new Event('input'));await nextTick()
  await click('上一页');await click('加载渠道')
  expect(prime).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain('请修正标红的重量或备注')
  expect(document.querySelector<HTMLInputElement>('[aria-label="第1条克重"]')?.readOnly).toBe(false)
})
