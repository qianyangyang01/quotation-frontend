// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import View from './BatchSingleQuotationView.vue'
import { loadPurchaseProduct } from '@/data/purchaseStore'
import { saveBatchSingle } from '@/services/batchSingleQuotation'
import { authState } from '@/data/authStore'
import { ApiError } from '@/services/http'
import { setPricingTestPermissions, clearPricingTestPermissions } from '@/test/pricingPermissions'
const { prime, calculate, item } = vi.hoisted(() => {
 const item = (sku: string) => ({ input: { quoteMode:'single',primarySku:sku,customerName:'客户',customerGrade:'S',exchangeRate:6.68,commissionThreshold:1,quoteOptions:[{id:'option',quoteSheetKey:'channel',country:'US',carrier:'4PX',channel:'TEST',rule:'TEST',eta:'7-12 working days',quote1Usd:5,quote2Usd:10,quote3Usd:15,quoteCustomUsd:null}],systemQuantityQuotes:{quantities:[1],rows:[{optionId:'option',prices:[5]}]},customerQuote:{quantities:[1],rows:[{optionId:'option',prices:[5]}]} }, conditions:{customerName:'客户'}, sheet:{agent:'QA',date:'12 Oct 2026',quantityLabels:['1 pc'],issues:[],rows:[{key:'channel',sku,prices:[5],number:1,country:'US',provider:'4PX'}]} });return { prime:vi.fn(), calculate:vi.fn(async(sku:string)=>item(sku)), item }
})
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), RouterLink: { template: '<a><slot /></a>' } }))
vi.mock('@/services/customerQuoteSheetRenderer', async original => ({ ...await original<typeof import('@/services/customerQuoteSheetRenderer')>(), preloadQuoteSheetAssets: vi.fn().mockResolvedValue([]) }))
vi.mock('@/data/purchaseStore', () => ({ loadPurchaseProduct: vi.fn(), purchaseQuoteBlockingMessage: () => '采购资料不完整' }))
vi.mock('@/services/batchSingleQuotation', async importOriginal => ({ ...await importOriginal<typeof import('@/services/batchSingleQuotation')>(), saveBatchSingle: vi.fn() }))
vi.mock('./QuotationSystemView.vue', () => ({ __esModule: true, default: { setup(_: unknown, { expose }: { expose: (value: unknown) => void }) { expose({ batchReady: true, primeBatchProduct: prime, restoreBatchSample: vi.fn(), calculateBatchItem: calculate }); return { first:item('SKU0') } }, template: '<div data-engine><button @click="$emit(\'batch-captured\', first)">应用条件并生成批量报价</button><button @click="$emit(\'batch-preparing\', true)">模拟校验中</button><button @click="$emit(\'batch-preparing\', false)">模拟校验失败</button></div>' } }))
let app: App
const button = (text: string) => [...document.querySelectorAll('button')].find(b => b.textContent?.trim() === text)!
beforeEach(() => { setPricingTestPermissions(['quote']); vi.clearAllMocks(); calculate.mockImplementation(async sku => item(sku)); vi.mocked(loadPurchaseProduct).mockImplementation(async sku => ({ sku, quoteReady: true }) as never); vi.mocked(saveBatchSingle).mockImplementation(async document => ({ ...document, no: 'BATCH-TEST', createdRecords: document.items.map((item,i)=>({id:String(i),no:'QT'+i,primarySku:item.input.primarySku,quoteMode:'single' as const})) })) })
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.unstubAllGlobals(); clearPricingTestPermissions() })
it('automatically excludes five query failures and quotes the other fifteen with one exception-copy action', async () => {
 const writeText=vi.fn().mockResolvedValue(undefined)
 vi.stubGlobal('navigator',{clipboard:{writeText}})
 vi.mocked(loadPurchaseProduct).mockImplementation(async sku => {
  if(sku==='SKU1') return {sku,quoteReady:false} as never
  if(['SKU2','SKU4','SKU5'].includes(sku)) throw new ApiError('未找到商品',404,'NOT_FOUND','test')
  if(sku==='SKU3') throw new TypeError('网络连接失败')
  return {sku,quoteReady:true} as never
 })
 await mount(20)
 expect(document.body.textContent).toContain('资料不全，待补全')
 expect(document.body.textContent).toContain('未找到商品')
 expect(document.body.textContent).toContain('查询失败，可重试')
 expect(document.body.textContent).toContain('可继续报价 15 个 · 异常 5 个')
 for (let index=1;index<=5;index++) {
  const checkbox=document.querySelector<HTMLInputElement>('[aria-label="选入 SKU'+index+' 第'+(index+1)+'行"]')!
  expect(checkbox.disabled).toBe(true)
  expect(checkbox.checked).toBe(false)
 }
 expect(document.querySelectorAll('.readiness-card button')).toHaveLength(1)
 button('复制异常 SKU').click();await nextTick()
 expect(writeText).toHaveBeenLastCalledWith('SKU1\nSKU2\nSKU3\nSKU4\nSKU5')
 button('应用条件并生成批量报价').click()
 await vi.waitFor(()=>expect(document.body.textContent).toContain('15个成功'))
 expect(calculate.mock.calls.map(call=>call[0]).some(sku=>['SKU1','SKU2','SKU3','SKU4','SKU5'].includes(sku))).toBe(false)
 button('保存 15 条报价记录').click()
 await vi.waitFor(()=>expect(saveBatchSingle).toHaveBeenCalledOnce())
 expect(vi.mocked(saveBatchSingle).mock.calls[0]![0].items).toHaveLength(15)
 await vi.waitFor(()=>expect(document.body.textContent).toContain('已生成 15 条独立报价记录'))
 expect(document.body.textContent).toContain('查看本批报价记录')
 button('复制异常 SKU').click();await nextTick()
 expect(writeText).toHaveBeenLastCalledWith('SKU1\nSKU2\nSKU3\nSKU4\nSKU5')
 vi.unstubAllGlobals()
})
async function mount(count = 21, raw?: string, expectReady = true) { const host = document.createElement('div'); document.body.append(host); app = createApp(View); app.component('RouterLink', { template: '<a><slot /></a>' }); app.mount(host); await nextTick(); const input = document.querySelector('textarea')!; input.value = raw ?? Array.from({ length: count }, (_, i) => `SKU${i}`).join('\n'); input.dispatchEvent(new Event('input')); await nextTick(); button('查询 SKU').click(); await nextTick(); await vi.waitFor(() => expect(expectReady ? prime.mock.calls.length === 1 : !button('处理中…')).toBe(true)) }
it('queries every SKU without requiring customer input and paginates with persistent selection', async () => {
 await mount(); expect(loadPurchaseProduct).toHaveBeenCalledTimes(21); expect(document.querySelectorAll('.processing-scroll tbody tr')).toHaveLength(10); expect(document.querySelector('textarea')).toBeNull(); expect(document.body.textContent).toContain('21 个可报价');
 button('下一页').click(); await nextTick(); const check = document.querySelector<HTMLInputElement>('.processing-scroll input')!; check.click(); await nextTick(); expect(document.body.textContent).toContain('已选 20 个'); button('上一页').click(); await nextTick(); expect(document.querySelector<HTMLInputElement>('.processing-scroll input')!.checked).toBe(true); button('下一页').click(); await nextTick(); expect(document.querySelector<HTMLInputElement>('.processing-scroll input')!.checked).toBe(false); button('下一页').click(); await nextTick(); expect(document.querySelectorAll('.processing-scroll tbody tr')).toHaveLength(1);
 const size = document.querySelector<HTMLSelectElement>('[aria-label="每页处理SKU数"]')!; size.value='50'; size.dispatchEvent(new Event('change')); await nextTick(); expect(document.querySelectorAll('.processing-scroll tbody tr')).toHaveLength(21); expect(document.body.textContent).toContain('已选 20 个');
})
it('isolates query failures and primes the first available SKU, with retry available', async () => {
 vi.mocked(loadPurchaseProduct).mockImplementation(async sku => { if (sku === 'SKU0') throw new Error('商品不存在'); return { sku, quoteReady: true } as never });
 await mount(3); expect(prime).toHaveBeenCalledWith(expect.objectContaining({ sku:'SKU1' })); expect(document.body.textContent).toContain('商品不存在'); expect(document.body.textContent).toContain('2 个可报价，1 个异常'); expect(document.querySelector<HTMLInputElement>('.processing-scroll input')!.checked).toBe(false); expect(button('重新查询 SKU').disabled).toBe(false)
})

it('generates every selected SKU across all pages without silently limiting to visible rows',async()=>{
 await mount();button('下一页').click();await nextTick();document.querySelector<HTMLInputElement>('.processing-scroll input')!.click();await nextTick();button('应用条件并生成批量报价').click();await vi.waitFor(()=>expect(document.body.textContent).toContain('20个成功'));expect(calculate).toHaveBeenCalledTimes(19);expect(calculate.mock.calls.map(call=>call[0])).not.toContain('SKU10');expect(calculate.mock.calls.at(-1)?.[0]).toBe('SKU20');expect(document.body.textContent).toContain('保存 20 条报价记录')
})

function deferred<T>() { let resolve!: (value: T) => void, reject!: (error: Error) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
it('hides but keeps the calculator mounted, stops after the in-flight SKU and resumes without recalculating successes', async () => {
 await mount(4)
 const pending = deferred<ReturnType<typeof item>>()
 calculate.mockImplementationOnce(() => pending.promise)
 button('应用条件并生成批量报价').click()
 await vi.waitFor(() => expect(calculate).toHaveBeenCalledTimes(1))
 expect(document.querySelector<HTMLElement>('.editor')!.style.display).toBe('none')
 expect(document.querySelector('[data-engine]')).not.toBeNull()
 expect(document.querySelector('[role="progressbar"]')!.getAttribute('aria-valuenow')).toBe('1')
 button('停止计算').click(); await nextTick()
 expect(button('正在停止…').disabled).toBe(true)
 pending.resolve(item('SKU1'))
 await vi.waitFor(() => expect(document.body.textContent).toContain('已停止：2个成功'))
 expect(calculate).toHaveBeenCalledTimes(1)
 expect(button('保存 4 条报价记录').disabled).toBe(true)
 expect(saveBatchSingle).not.toHaveBeenCalled()
 button('继续处理未完成项').click()
 await vi.waitFor(() => expect(document.body.textContent).toContain('4个成功'))
 expect(calculate.mock.calls.map(call => call[0])).toEqual(['SKU1','SKU2','SKU3'])
 expect(button('保存 4 条报价记录').disabled).toBe(false)
})

it('shows partial results after failure, retries only the failed SKU and saves all selected rows despite filtering', async () => {
 await mount(3)
 calculate.mockImplementationOnce(async () => { throw new Error('渠道加载超时') })
 button('应用条件并生成批量报价').click()
 await vi.waitFor(() => expect(document.body.textContent).toContain('2个成功，1个待处理'))
 expect(document.querySelectorAll('.result-card .batch-sheet tbody tr, .result-card .sheet-editor-scroll tbody tr')).toHaveLength(2)
 expect(document.querySelector('.pending-panel')!.textContent).toContain('渠道加载超时')
 expect(button('保存 3 条报价记录').disabled).toBe(true)
 button('仅重试失败 / 未完成项').click()
 await vi.waitFor(() => expect(document.body.textContent).toContain('3个成功'))
 expect(calculate.mock.calls.map(call => call[0])).toEqual(['SKU1','SKU2','SKU1'])
 const search = document.querySelector<HTMLInputElement>('[aria-label="搜索报价单 SKU"]')!
 search.value='SKU2'; search.dispatchEvent(new Event('input')); await nextTick()
 expect(document.querySelectorAll('.result-card .batch-sheet tbody tr, .result-card .sheet-editor-scroll tbody tr')).toHaveLength(1)
 button('保存 3 条报价记录').click(); button('保存 3 条报价记录').click()
 await vi.waitFor(() => expect(saveBatchSingle).toHaveBeenCalledTimes(1))
 const documentInput = vi.mocked(saveBatchSingle).mock.calls[0]![0]
 expect(documentInput.items.map(entry => entry.input.primarySku)).toEqual(['SKU0','SKU1','SKU2'])
 expect(documentInput.sheet.rows).toHaveLength(3)
})

it('requires explicit deselection before saving successful items from an incomplete batch', async () => {
 await mount(3)
 calculate.mockImplementationOnce(async () => { throw new Error('缺少渠道') })
 button('应用条件并生成批量报价').click()
 await vi.waitFor(() => expect(document.body.textContent).toContain('2个成功，1个待处理'))
 const check = document.querySelector<HTMLInputElement>('[aria-label="选入 SKU1 第2行"]')!
 check.click(); await nextTick()
 expect(button('保存 2 条报价记录').disabled).toBe(false)
 button('保存 2 条报价记录').click()
 await vi.waitFor(() => expect(saveBatchSingle).toHaveBeenCalledTimes(1))
 expect(vi.mocked(saveBatchSingle).mock.calls[0]![0].items.map(entry => entry.input.primarySku)).toEqual(['SKU0','SKU2'])
})

it('restores the editor after validation fails and never generates or saves', async () => {
 await mount(2)
 button('模拟校验中').click(); await nextTick()
 expect(document.querySelector('.batch-progress')).not.toBeNull()
 expect(document.querySelector<HTMLElement>('.editor')!.style.display).toBe('none')
 expect(button('停止计算').disabled).toBe(true)
 button('模拟校验失败').click(); await nextTick()
 expect(document.querySelector('.batch-progress')).toBeNull()
 expect(document.querySelector<HTMLElement>('.editor')!.style.display).not.toBe('none')
 expect(calculate).not.toHaveBeenCalled(); expect(saveBatchSingle).not.toHaveBeenCalled()
})

it('invalidates earlier results before edited conditions can generate a new batch', async () => {
 await mount(3); button('应用条件并生成批量报价').click()
 await vi.waitFor(() => expect(document.body.textContent).toContain('3个成功'))
 button('修改条件 / 重新生成').click(); await vi.waitFor(() => expect(document.querySelector('.result-card')).toBeNull())
 expect(document.querySelector('.result-card')).toBeNull()
 expect(document.querySelector<HTMLElement>('.editor')!.style.display).not.toBe('none')
 button('应用条件并生成批量报价').click()
 await vi.waitFor(() => expect(calculate).toHaveBeenCalledTimes(4))
 expect(saveBatchSingle).not.toHaveBeenCalled()
})

it('ignores late failures after an account change and stops scheduling the old batch', async () => {
 await mount(3)
 const pending = deferred<ReturnType<typeof item>>()
 calculate.mockImplementationOnce(() => pending.promise)
 button('应用条件并生成批量报价').click()
 await vi.waitFor(() => expect(calculate).toHaveBeenCalledTimes(1))
 authState.current = null; await nextTick()
 pending.reject(new Error('旧账号的错误'))
 await new Promise(resolve => setTimeout(resolve, 10))
 expect(document.body.textContent).not.toContain('旧账号的错误')
 expect(calculate).toHaveBeenCalledTimes(1)
 expect(saveBatchSingle).not.toHaveBeenCalled()
})

it('completes 50 SKUs without dropping the final item', async () => {
 await mount(50); button('应用条件并生成批量报价').click()
 await vi.waitFor(() => expect(document.body.textContent).toContain('50个成功'))
 expect(calculate).toHaveBeenCalledTimes(49)
 expect(calculate.mock.calls.at(-1)![0]).toBe('SKU49')
 expect(document.querySelectorAll('.result-card .batch-sheet tbody tr, .result-card .sheet-editor-scroll tbody tr')).toHaveLength(12)
})

it('keeps all failed queries unselected and allows a fresh query after purchase data is fixed', async () => {
 vi.mocked(loadPurchaseProduct).mockResolvedValue({sku:'SKU0',quoteReady:false} as never)
 await mount(3, undefined, false)
 expect(prime).not.toHaveBeenCalled()
 expect(document.body.textContent).toContain('可继续报价 0 个 · 异常 3 个')
 expect([...document.querySelectorAll<HTMLInputElement>('.processing-scroll input')].every(input=>!input.checked && input.disabled)).toBe(true)
 expect(button('设置条件与渠道 ↓').disabled).toBe(true)
 vi.mocked(loadPurchaseProduct).mockImplementation(async sku=>({sku,quoteReady:true}) as never)
 button('重新查询 SKU').click()
 await vi.waitFor(()=>expect(prime).toHaveBeenCalledOnce())
 expect(document.body.textContent).toContain('可继续报价 3 个 · 异常 0 个')
})
it('rejects an oversized input before any purchase request and keeps malformed/duplicate rows out of calculation', async () => {
 await mount(51, undefined, false)
 expect(loadPurchaseProduct).not.toHaveBeenCalled()
 expect(document.body.textContent).toContain('每批1至50行')
 const input=document.querySelector('textarea')!
 input.value='SKU0\nsku0\nSKU1\t20\nSKU2';input.dispatchEvent(new Event('input'));await nextTick()
 button('查询 SKU').click();await vi.waitFor(()=>expect(prime).toHaveBeenCalledOnce())
 expect(vi.mocked(loadPurchaseProduct).mock.calls.map(call=>call[0])).toEqual(['SKU0','SKU2'])
 button('应用条件并生成批量报价').click();await vi.waitFor(()=>expect(document.body.textContent).toContain('2个成功'))
 expect(calculate.mock.calls.map(call=>call[0])).toEqual(['SKU2'])
})
it('keeps exception rows available when clipboard access fails', async () => {
 vi.stubGlobal('navigator',{clipboard:{writeText:vi.fn().mockRejectedValue(new Error('denied'))}})
 vi.mocked(loadPurchaseProduct).mockImplementation(async sku=>{if(sku==='SKU1')throw new Error('网络中断');return {sku,quoteReady:true} as never})
 await mount(2);button('复制异常 SKU').click()
 await vi.waitFor(()=>expect(document.body.textContent).toContain('复制失败，请重试或从下方清单手动复制'))
 expect(document.body.textContent).toContain('网络中断')
 expect(document.querySelector<HTMLInputElement>('[aria-label="选入 SKU1 第2行"]')!.checked).toBe(false)
})
it('preserves edited prices and reuses the same idempotency key after an uncertain save failure', async () => {
 await mount(2);button('应用条件并生成批量报价').click()
 await vi.waitFor(()=>expect(document.body.textContent).toContain('2个成功'))
 document.querySelector<HTMLButtonElement>('button[aria-label="第 1 行第 1 列美元价格"]')!.click();await nextTick()
 const price=document.querySelector<HTMLInputElement>('input[aria-label="第 1 行第 1 列美元价格"]')!
 price.value='9.95';price.dispatchEvent(new Event('input'));await nextTick()
 price.blur();await nextTick()
 vi.mocked(saveBatchSingle).mockRejectedValueOnce(new Error('保存结果尚未确认'))
 button('保存 2 条报价记录').click()
 await vi.waitFor(()=>expect(document.body.textContent).toContain('保存结果尚未确认'))
 expect(document.querySelector('button[aria-label="第 1 行第 1 列美元价格"]')!.textContent).toBe('9.95')
 button('保存 2 条报价记录').click()
 await vi.waitFor(()=>expect(saveBatchSingle).toHaveBeenCalledTimes(2))
 const [first,retry]=vi.mocked(saveBatchSingle).mock.calls
 expect(retry![0]).toEqual(first![0]);expect(retry![1]).toBe(first![1])
 expect(retry![0].items[0]!.input.customerQuote!.rows[0]!.prices[0]).toBe(9.95)
})
