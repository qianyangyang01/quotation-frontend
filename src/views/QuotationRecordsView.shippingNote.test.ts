// @vitest-environment happy-dom
import { readFileSync } from 'node:fs'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import View from './QuotationRecordsView.vue'
import { normalizeQuotationRecord, type QuotationRecord } from '@/data/quotationRecords'

const query = vi.hoisted(() => ({ loadRecordPage: vi.fn(), loadFilteredRecords: vi.fn(), loadRecord: vi.fn(), recentRecordDates: () => ({ startDate: '2026-10-16', endDate: '2026-10-16' }) }))
vi.mock('@/data/quotationRecordQuery', () => query)
vi.mock('@/data/purchaseStore', () => ({ loadPurchaseProducts: () => Promise.resolve([]) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn().mockResolvedValue(undefined) }), useRoute: () => ({ query: {} }) }))
let app: App | undefined
const shipping = (note = '', id = 'shipping') => normalizeQuotationRecord({ id, no: `QT-${id}`, quoteMode: 'shipping-only', shippingNote: note, productSummary: note ? `仅代发货报价：${note}` : '仅代发货报价', primarySku: '仅代发货报价', customerName: '客户甲', manualPricing: { costCny: 0, weightGrams: 500 } })!
const pageResult = (items: QuotationRecord[]) => ({ items, page: 0, size: 10, total: items.length, totalPages: items.length ? 1 : 0, summary: { pending: items.length, won: 0, lost: 0, total: items.length }, countries: ['美国'] })
const flush = async () => { await nextTick(); await Promise.resolve(); await nextTick() }
async function mount(scope: 'mine' | 'company') {
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(View, { scope }); app.component('RouterLink', { template: '<a><slot /></a>' }); app.mount(host)
  await flush()
}
function input(selector: string, value: string) {
  const field = document.querySelector<HTMLInputElement | HTMLSelectElement>(selector)!
  field.value = value; field.dispatchEvent(new Event(field.tagName === 'SELECT' ? 'change' : 'input'))
}
beforeEach(() => { vi.useFakeTimers() })
afterEach(() => { app?.unmount(); app = undefined; document.body.innerHTML = ''; vi.useRealTimers(); vi.restoreAllMocks(); vi.resetAllMocks() })

it.each(['mine', 'company'] as const)('shows one complete safe shipping title and keeps historical and SKU records readable for %s', async scope => {
  const note = 'TC2600009-1 / <img src=x onerror=alert(1)> / ' + 'LongSKU'.repeat(20)
  const noted = shipping(note)
  const old = normalizeQuotationRecord({ id: 'old', no: 'QT-OLD', quoteMode: 'shipping-only', primarySku: '仅代发货报价' })!
  const single = normalizeQuotationRecord({ id: 'single', no: 'QT-SINGLE', quoteMode: 'single', primarySku: 'SKU-REAL', productSummary: '单品名称', customerName: '单品客户' })!
  query.loadRecordPage.mockResolvedValue(pageResult([noted, old, single]))
  await mount(scope)
  const rows = document.querySelectorAll('.records>article')
  const title = rows[0]!.querySelector('.record-product-title')!
  expect(title.textContent).toBe(`仅代发货报价：${note}`)
  expect(title.querySelectorAll('b')).toHaveLength(1)
  expect(title.querySelector('strong')).toBeNull()
  expect(title.querySelector('img')).toBeNull()
  expect(title.querySelector<HTMLElement>('b')!.title).toBe(`仅代发货报价：${note}`)
  expect(title.classList.contains('shipping-product-title')).toBe(true)
  expect(rows[0]!.querySelector('.record-customer')!.textContent).toContain('客户甲')
  expect(rows[1]!.querySelector('.record-product-title')!.textContent).toBe('仅代发货报价')
  expect(rows[2]!.querySelector('.record-sku')!.textContent).toBe('SKU-REAL')
  expect(rows[2]!.querySelector('.record-product-title strong')!.textContent).toBe('单品名称')
  rows[0]!.querySelector<HTMLButtonElement>('.difference-cell')!.click(); await flush()
  expect(document.querySelector('.shipping-record-drawer .overview-info')!.textContent).toContain(`仅代发货报价：${note}`)
  expect(document.querySelector('.shipping-record-drawer>header')!.textContent).toContain(note)
  const source = readFileSync('src/views/QuotationRecordsView.vue', 'utf8')
  expect(source).toMatch(/\.shipping-product-title \.record-sku\{[^}]*max-width:100%;[^}]*white-space:normal;overflow-wrap:anywhere/)
  expect(source).toMatch(/\.shipping-record-drawer \.overview-info b\{[^}]*white-space:normal;overflow-wrap:anywhere/)
})

it.each(['mine', 'company'] as const)('debounces note search, preserves filters and export, and ignores stale results for %s', async scope => {
  query.loadRecordPage.mockResolvedValue(pageResult([]))
  query.loadFilteredRecords.mockResolvedValue([shipping('包装SKU-B')])
  const download = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  const objectUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:shipping-note-export')
  await mount(scope)
  const search = document.querySelector<HTMLInputElement>('#record-product-filter')!
  expect(search.placeholder).toContain('代发备注')
  input('[aria-label="报价类型筛选"]', 'shipping-only')
  input('[aria-label="客户"]', ' 客户甲 ')
  input('[aria-label="开始日期"]', '2026-10-01')
  input('#record-product-filter', ' 包装SKU-A ')
  await flush(); await vi.advanceTimersByTimeAsync(249)
  expect(query.loadRecordPage).toHaveBeenCalledTimes(1)
  let resolveOld!: (value: ReturnType<typeof pageResult>) => void
  query.loadRecordPage.mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
  await vi.advanceTimersByTimeAsync(1); await flush()
  const oldSignal = query.loadRecordPage.mock.lastCall![4] as AbortSignal
  input('#record-product-filter', ' 包装SKU-B '); await flush()
  expect(oldSignal.aborted).toBe(true)
  query.loadRecordPage.mockResolvedValueOnce(pageResult([shipping('包装SKU-B')]))
  await vi.advanceTimersByTimeAsync(250); await flush()
  const expected = { product: '包装SKU-B', quoteMode: 'shipping-only', customer: '客户甲', startDate: '2026-10-01', reviewStatus: 'pending', lifecycle: 'active' }
  expect(query.loadRecordPage.mock.lastCall).toEqual([scope, expect.objectContaining(expected), 0, 10, expect.any(AbortSignal)])
  resolveOld(pageResult([shipping('包装SKU-A', 'old-result')])); await flush()
  expect(document.querySelector('.records')!.textContent).toContain('包装SKU-B')
  expect(document.querySelector('.records')!.textContent).not.toContain('包装SKU-A')
  Array.from(document.querySelectorAll('button')).find(button => button.textContent === '导出筛选结果')!.click(); await flush()
  expect(query.loadFilteredRecords).toHaveBeenCalledWith(scope, expect.objectContaining(expected))
  const csv = await (objectUrl.mock.calls[0]![0] as Blob).text()
  expect(csv).toContain('代发备注'); expect(csv).toContain('包装SKU-B')
  expect(download).toHaveBeenCalledOnce()
})
