// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import PurchaseSalesPanel from './PurchaseSalesPanel.vue'
const load = vi.hoisted(() => vi.fn())
vi.mock('@/services/purchaseSales', async original => ({ ...await original<object>(), loadPurchaseSales: load }))
let app: App
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.resetAllMocks() })
async function flush() { await Promise.resolve(); await nextTick(); await Promise.resolve(); await nextTick() }
const payload = { source: { period: '6—8月', sourceFile: '销量.xlsx', sourceSheet: '汇总', rows: [{ sku: 'A', sourceSku: 'A', sourceRow: 2, months: [1, 2, 3], total: 6, monthlyTotal: 6, salesDifference: 0 }], monthlyOnlySkus: [] }, products: [], matchedAt: '2026-09-28T12:00:00Z' }
async function mount() { const host = document.createElement('div'); document.body.append(host); app = createApp(PurchaseSalesPanel); app.mount(host); await flush() }
it('opens actual unmatched rows from the summary and distinguishes errors from a zero count', async () => {
  load.mockResolvedValue(payload); await mount()
  const button = [...document.querySelectorAll('button')].find(b => b.textContent?.includes('未匹配'))!
  button.click(); await flush()
  expect(document.querySelector('table')?.textContent).toContain('A')
  expect(document.querySelector('dialog')?.open).toBe(true)
  expect(document.querySelector('table')?.textContent).toContain('需核对主 SKU')
  load.mockRejectedValue(new Error('网络中断'))
  ;[...document.querySelectorAll('button')].find(b => b.textContent === '刷新匹配')!.click(); await flush()
  expect(document.querySelector('[role="alert"]')?.textContent).toContain('上次成功匹配结果')
  expect(document.querySelector('table')?.textContent).toContain('A')
})
it('shows retry when the initial read fails, without claiming all data is complete', async () => {
  load.mockRejectedValue(new Error('禁止访问')); await mount()
  expect(document.querySelector('[role="alert"]')?.textContent).toContain('暂未取得匹配结果')
  expect(document.body.textContent).not.toContain('已匹配 0')
})
it('separates missing matched records from unmatched SKUs and reveals monthly columns on demand', async () => {
  load.mockResolvedValue({ ...payload, source: { ...payload.source, rows: [...payload.source.rows, { ...payload.source.rows[0], sku: 'B', sourceSku: 'B' }] }, products: [{ sku: 'B', quotationOwner: '黄', weightG: null, taxPoint: 0, invoiceType: '没票', purchasePriceCny: 10, minOrderQty: 1, singleFreightCny: 0, dataSource: 'legacy_2026', catalogState: 'ready' }] })
  await mount()
  expect(document.querySelector('dialog')?.open).toBe(false)
  const pending = [...document.querySelectorAll('.sales-summary button')].find(b => b.textContent?.includes('待补资料')) as HTMLButtonElement
  expect(pending.textContent).toContain('1')
  pending.click(); await flush()
  expect(document.querySelector('tbody')?.textContent).toContain('B')
  expect(document.querySelector('tbody')?.textContent).not.toContain('需核对主 SKU')
  expect(document.querySelectorAll('thead th')).toHaveLength(7)
  const monthly = document.querySelectorAll<HTMLInputElement>('.view-options input')[1]!
  monthly.click(); await flush()
  expect(document.querySelectorAll('thead th')).toHaveLength(10)
  ;(document.querySelector('[aria-label="关闭销量明细"]') as HTMLButtonElement).click(); await flush()
  expect(document.querySelector('dialog')?.open).toBe(false)
})
