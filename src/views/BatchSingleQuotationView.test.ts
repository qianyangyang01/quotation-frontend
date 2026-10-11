// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import View from './BatchSingleQuotationView.vue'
import { loadPurchaseProduct } from '@/data/purchaseStore'
import { setPricingTestPermissions, clearPricingTestPermissions } from '@/test/pricingPermissions'
const { prime, calculate, item } = vi.hoisted(() => {
 const item = (sku: string) => ({ input: { quoteMode:'single',primarySku:sku,customerName:'客户',customerGrade:'S',exchangeRate:6.68,commissionThreshold:1,customerQuote:{quantities:[1],rows:[]} }, conditions:{customerName:'客户'}, sheet:{quantityLabels:['1 pc'],issues:[],rows:[{key:'channel',sku,prices:[5],number:1,country:'US',provider:'4PX'}]} });return { prime:vi.fn(), calculate:vi.fn(async(sku:string)=>item(sku)), item }
})
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }))
vi.mock('@/data/purchaseStore', () => ({ loadPurchaseProduct: vi.fn(), purchaseQuoteBlockingMessage: () => '采购资料不完整' }))
vi.mock('./QuotationSystemView.vue', () => ({ __esModule: true, default: { setup(_: unknown, { expose }: { expose: (value: unknown) => void }) { expose({ batchReady: true, primeBatchProduct: prime, calculateBatchItem: calculate }); return { first:item('SKU0') } }, template: '<div data-engine><button @click="$emit(\'batch-captured\', first)">应用条件并生成批量报价</button></div>' } }))
let app: App
const button = (text: string) => [...document.querySelectorAll('button')].find(b => b.textContent?.trim() === text)!
beforeEach(() => { setPricingTestPermissions(['quote']); vi.clearAllMocks(); vi.mocked(loadPurchaseProduct).mockImplementation(async sku => ({ sku, quoteReady: true }) as never) })
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
