// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import PurchaseDataWorkspace from './PurchaseDataWorkspace.vue'
import { normalizePurchaseRecord, type PurchasePage } from '@/data/purchaseStore'

const mocks = vi.hoisted(() => ({ page: vi.fn(), stats: vi.fn() }))
vi.mock('@/data/purchaseStore', async importOriginal => ({ ...await importOriginal<typeof import('@/data/purchaseStore')>(), loadPurchaseProductPage: mocks.page, loadPurchaseStats: mocks.stats }))
let app: App | undefined
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.useRealTimers(); vi.clearAllMocks() })
function page(sku: string): PurchasePage { return { items: [normalizePurchaseRecord({ sku, purchasePriceCny: 10, weightG: 100 })], total: 1, totalPages: 1, page: 0, size: 10 } as PurchasePage }
it('ignores an older purchase response during the new input debounce', async () => {
  vi.useFakeTimers()
  let resolveOld!: (page: PurchasePage) => void
  mocks.page.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve })).mockResolvedValue(page('LATEST-SKU'))
  mocks.stats.mockResolvedValue({ total: 1, quoteReady: 1, generatedSku: 0 })
  const host = document.createElement('div'); document.body.append(host); app = createApp(PurchaseDataWorkspace); app.mount(host)
  const input = document.querySelector('input[placeholder="搜索 SKU、类别、报价人、尺码、颜色或工厂"]') as HTMLInputElement
  input.value = 'LATEST-SKU'; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  resolveOld(page('STALE-SKU')); await Promise.resolve(); await Promise.resolve(); await nextTick()
  expect(document.body.textContent).not.toContain('STALE-SKU')
  await vi.advanceTimersByTimeAsync(250); await nextTick()
  expect(document.body.textContent).toContain('LATEST-SKU')
  expect(mocks.page).toHaveBeenLastCalledWith('LATEST-SKU', 0, 10)
})

it('does not replace a new result when the previous search finishes last', async () => {
  vi.useFakeTimers()
  let resolveOld!: (page: PurchasePage) => void
  mocks.page.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve })).mockResolvedValue(page('LATEST-SKU'))
  mocks.stats.mockResolvedValue({ total: 1, quoteReady: 1, generatedSku: 0 })
  const host = document.createElement('div'); document.body.append(host); app = createApp(PurchaseDataWorkspace); app.mount(host)
  const input = document.querySelector('input[placeholder="搜索 SKU、类别、报价人、尺码、颜色或工厂"]') as HTMLInputElement
  input.value = 'LATEST-SKU'; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  await vi.advanceTimersByTimeAsync(250); await nextTick()
  expect(document.body.textContent).toContain('LATEST-SKU')
  resolveOld(page('STALE-SKU')); await Promise.resolve(); await Promise.resolve(); await nextTick()
  expect(document.body.textContent).toContain('LATEST-SKU')
  expect(document.body.textContent).not.toContain('STALE-SKU')
})
