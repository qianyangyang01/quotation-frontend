// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import View from './BatchSingleQuotationView.vue'
import { loadPurchaseProduct } from '@/data/purchaseStore'
import { saveBatchSingle } from '@/services/batchSingleQuotation'
import { authState } from '@/data/authStore'
import { setPricingTestPermissions, clearPricingTestPermissions } from '@/test/pricingPermissions'
const { prime, calculate, item } = vi.hoisted(() => {
 const item = (sku: string) => ({ input: { quoteMode:'single',primarySku:sku,customerName:'客户',customerGrade:'S',exchangeRate:6.68,commissionThreshold:1,customerQuote:{quantities:[1],rows:[]} }, conditions:{customerName:'客户'}, sheet:{quantityLabels:['1 pc'],issues:[],rows:[{key:'channel',sku,prices:[5],number:1,country:'US',provider:'4PX'}]} });return { prime:vi.fn(), calculate:vi.fn(async(sku:string)=>item(sku)), item }
})
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }))
vi.mock('@/data/purchaseStore', () => ({ loadPurchaseProduct: vi.fn(), purchaseQuoteBlockingMessage: () => '采购资料不完整' }))
vi.mock('@/services/batchSingleQuotation', async importOriginal => ({ ...await importOriginal<typeof import('@/services/batchSingleQuotation')>(), saveBatchSingle: vi.fn() }))
vi.mock('./QuotationSystemView.vue', () => ({ __esModule: true, default: { setup(_: unknown, { expose }: { expose: (value: unknown) => void }) { expose({ batchReady: true, primeBatchProduct: prime, calculateBatchItem: calculate }); return { first:item('SKU0') } }, template: '<div data-engine><button @click="$emit(\'batch-captured\', first)">应用条件并生成批量报价</button><button @click="$emit(\'batch-preparing\', true)">模拟校验中</button><button @click="$emit(\'batch-preparing\', false)">模拟校验失败</button></div>' } }))
let app: App
const button = (text: string) => [...document.querySelectorAll('button')].find(b => b.textContent?.trim() === text)!
beforeEach(() => { setPricingTestPermissions(['quote']); vi.clearAllMocks(); calculate.mockImplementation(async sku => item(sku)); vi.mocked(loadPurchaseProduct).mockImplementation(async sku => ({ sku, quoteReady: true }) as never); vi.mocked(saveBatchSingle).mockImplementation(async document => ({ ...document, id: 'saved', no: 'BATCH-TEST' })) })
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; clearPricingTestPermissions() })
async function mount(count = 21) { const host = document.createElement('div'); document.body.append(host); app = createApp(View); app.component('RouterLink', { template: '<a><slot /></a>' }); app.mount(host); await nextTick(); const input = document.querySelector('textarea')!; input.value = Array.from({ length: count }, (_, i) => `SKU${i}`).join('\n'); input.dispatchEvent(new Event('input')); await nextTick(); button('查询 SKU').click(); await vi.waitFor(() => expect(prime, document.body.textContent || "").toHaveBeenCalledOnce()) }
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
 await mount();button('下一页').click();await nextTick();document.querySelector<HTMLInputElement>('.processing-scroll input')!.click();await nextTick();button('应用条件并生成批量报价').click();await vi.waitFor(()=>expect(document.body.textContent).toContain('20个成功'));expect(calculate).toHaveBeenCalledTimes(19);expect(calculate.mock.calls.map(call=>call[0])).not.toContain('SKU10');expect(calculate.mock.calls.at(-1)?.[0]).toBe('SKU20');expect(document.body.textContent).toContain('保存 1 张报价单 · 20个 SKU')
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
 expect(button('保存 1 张报价单 · 4个 SKU').disabled).toBe(true)
 expect(saveBatchSingle).not.toHaveBeenCalled()
 button('继续处理未完成项').click()
 await vi.waitFor(() => expect(document.body.textContent).toContain('4个成功'))
 expect(calculate.mock.calls.map(call => call[0])).toEqual(['SKU1','SKU2','SKU3'])
 expect(button('保存 1 张报价单 · 4个 SKU').disabled).toBe(false)
})

it('shows partial results after failure, retries only the failed SKU and saves all selected rows despite filtering', async () => {
 await mount(3)
 calculate.mockImplementationOnce(async () => { throw new Error('渠道加载超时') })
 button('应用条件并生成批量报价').click()
 await vi.waitFor(() => expect(document.body.textContent).toContain('2个成功，1个待处理'))
 expect(document.querySelectorAll('.result-card .batch-sheet tbody tr')).toHaveLength(2)
 expect(document.querySelector('.pending-panel')!.textContent).toContain('渠道加载超时')
 expect(button('保存 1 张报价单 · 3个 SKU').disabled).toBe(true)
 button('仅重试失败 / 未完成项').click()
 await vi.waitFor(() => expect(document.body.textContent).toContain('3个成功'))
 expect(calculate.mock.calls.map(call => call[0])).toEqual(['SKU1','SKU2','SKU1'])
 const search = document.querySelector<HTMLInputElement>('[aria-label="搜索报价单 SKU"]')!
 search.value='SKU2'; search.dispatchEvent(new Event('input')); await nextTick()
 expect(document.querySelectorAll('.result-card .batch-sheet tbody tr')).toHaveLength(1)
 button('保存 1 张报价单 · 3个 SKU').click(); button('保存 1 张报价单 · 3个 SKU').click()
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
 expect(button('保存 1 张报价单 · 2个 SKU').disabled).toBe(false)
 button('保存 1 张报价单 · 2个 SKU').click()
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
 button('修改条件 / 重新生成').click(); await nextTick()
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
 expect(document.querySelectorAll('.result-card .batch-sheet tbody tr')).toHaveLength(12)
})
