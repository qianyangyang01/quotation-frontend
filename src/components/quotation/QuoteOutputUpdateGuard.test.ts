// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App, type Component } from 'vue'
import CustomerSheet from './CustomerQuoteSheet.vue'
import BatchSheet from './BatchSingleSheet.vue'
import FobSheet from './FobQuoteSheet.vue'
import FobExports from './FobBatchSheetExports.vue'
import { appUpdate } from '@/services/appUpdate'
import { copyQuoteSheetData } from '@/services/customerQuoteSheetClipboard'
import { copyQuoteSheetImage, renderCustomerQuoteSheet, type QuoteSheetImage } from '@/services/customerQuoteSheetRenderer'
import { fobSheetWorkbook, fobSheetPrintHtml } from '@/services/fobBatchExports'
import type { CustomerQuoteSheet } from '@/data/customerQuoteSheet'
import { FOB_SMALL_ORDER_POLICY, type FobQuoteProduct } from '@/services/fobQuotation'

vi.mock('@/services/appUpdate', async () => {
  const { reactive } = await import('vue')
  const state = reactive({ updateRequired: false })
  return { appUpdate: { state, assertCurrent: vi.fn(() => { if (state.updateRequired) throw new Error('系统已更新，请保留输入并刷新后重新报价') }) } }
})
vi.mock('@/services/customerQuoteSheetClipboard', () => ({ copyQuoteSheetData: vi.fn().mockResolvedValue(undefined) }))
vi.mock('@/services/customerQuoteSheetRenderer', () => ({
  preloadQuoteSheetAssets: vi.fn().mockResolvedValue([]),
  renderCustomerQuoteSheet: vi.fn(), copyQuoteSheetImage: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@/services/fobBatchExports', () => ({ fobSheetWorkbook: vi.fn(), fobSheetPrintHtml: vi.fn().mockReturnValue('<p>saved quote</p>') }))

const version = appUpdate.state as { updateRequired: boolean }
const sheet: CustomerQuoteSheet = { agent: 'Saved Agent', date: '23 Oct 2026', quantityLabels: ['1 pc'], issues: [], rows: [{ key: 'saved', number: 1, sku: 'OLD-SKU', country: 'US', provider: '4PX', shippingTime: '7 days', prices: [12.34], sourceDescription: '' }] }
const product: FobQuoteProduct = { sku: 'NEW-SKU', source: 'fob', category: '内裤', weight: '60 g', updatedAt: '', notices: [], parsed: { minOrderQty: 1, orderMultiple: 1, freight: { quantity: 100, totalFreightCny: 0, unitFreightCny: 0, estimated: false, basis: '包邮' }, priceTiers: [{ minQty: 1, maxQty: null, unitPriceCny: 7.2, unit: '件' }] } }
const fobProps = { product, rate: 6.7, quantity: '26', policy: FOB_SMALL_ORDER_POLICY }
const customerProps = { rows: [{ country: 'US', carrier: '4PX', channelKey: 'one', ruleId: 1, rule: 'r', channelCode: 'one', transport: 'route', eta: '7 days', quote1: 10, quote2: 20, quote3: 30, quoteCustom: 40 }], countries: [], salesperson: 'Agent', contextKey: 'quote', customQuantity: 4, bundle: false, sourcePending: false }
const png = (): QuoteSheetImage => ({ blob: new Blob(['png'], { type: 'image/png' }), width: 1536, height: 1024, firstRow: 1, lastRow: 1 })
let app: App
function mount(component: Component, props: Record<string, unknown>) {
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(component, props)
  return app.mount(host)
}
const tick = async () => { for (let i = 0; i < 12; i++) await nextTick() }
function button(text: string) { return [...document.querySelectorAll('button')].find(element => element.textContent === text)! }

beforeEach(() => {
  version.updateRequired = false
  vi.clearAllMocks()
  vi.mocked(renderCustomerQuoteSheet).mockReset().mockResolvedValue([png()])
  vi.mocked(fobSheetWorkbook).mockReset().mockResolvedValue(new Blob(['xlsx']))
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:quote')
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  localStorage.clear()
})
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.restoreAllMocks(); localStorage.clear() })

it('blocks current ordinary quote exports synchronously while leaving entered text available for backup', async () => {
  const exposed = mount(CustomerSheet, { ...customerProps, currentQuote: true }) as unknown as { copyData: () => Promise<unknown>; preview: () => Promise<void> }
  await tick()
  const input = document.querySelector<HTMLInputElement>('[aria-label="报价单署名"]')!
  input.value = 'Keep my input'; input.dispatchEvent(new Event('input', { bubbles: true }))
  version.updateRequired = true; await tick()
  expect(input.value).toBe('Keep my input')
  expect(input.disabled).toBe(false)
  expect(button('复制报价数据').disabled).toBe(true)
  expect(button('预览报价单').disabled).toBe(true)
  await exposed.copyData(); await exposed.preview()
  expect(copyQuoteSheetData).not.toHaveBeenCalled()
  expect(renderCustomerQuoteSheet).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain('刷新后重新查询并报价')
})

it('discards a current ordinary quote preview if an update arrives during rendering', async () => {
  let finish!: (images: QuoteSheetImage[]) => void
  vi.mocked(renderCustomerQuoteSheet).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const exposed = mount(CustomerSheet, { ...customerProps, currentQuote: true }) as unknown as { preview: () => Promise<void> }
  await tick(); const pending = exposed.preview(); await tick()
  version.updateRequired = true
  finish([png()]); await pending; await tick()
  expect(document.querySelector('.sheet-image-scroll img')).toBeNull()
  expect(URL.createObjectURL).not.toHaveBeenCalled()
})

it('keeps ordinary saved-record copying available without changing the saved prices', async () => {
  version.updateRequired = true
  const initialQuote = { quantities: [1], rows: [{ optionId: 'one', prices: [12.34] }] }
  const before = JSON.stringify(initialQuote)
  const exposed = mount(CustomerSheet, { ...customerProps, recordMode: true, initialQuote, initialSystemQuote: initialQuote }) as unknown as { copyData: () => Promise<unknown> }
  await tick()
  const copied = exposed.copyData()
  expect(copyQuoteSheetData).toHaveBeenCalledOnce() // No await precedes the clipboard invocation.
  await copied
  expect(vi.mocked(copyQuoteSheetData).mock.lastCall![0].rows[0]!.prices).toEqual([12.34])
  expect(JSON.stringify(initialQuote)).toBe(before)
  expect(appUpdate.assertCurrent).not.toHaveBeenCalled()
})

it('removes existing current batch image/download previews after an update and blocks another copy', async () => {
  mount(BatchSheet, { sheet, currentQuote: true }); await tick()
  button('预览报价图片').click(); await tick()
  expect(document.querySelector('a[download]')).not.toBeNull()
  version.updateRequired = true; await tick()
  expect(document.querySelector('a[download]')).toBeNull()
  expect(button('复制报价数据').disabled).toBe(true)
  button('复制报价数据').click(); await tick()
  expect(copyQuoteSheetData).not.toHaveBeenCalled()
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:quote')
})

it('keeps historical batch copying and image downloads available', async () => {
  version.updateRequired = true
  const before = JSON.stringify(sheet)
  mount(BatchSheet, { sheet }); await tick()
  button('复制报价数据').click()
  expect(copyQuoteSheetData).toHaveBeenCalledWith(sheet)
  await tick(); button('预览报价图片').click(); await tick()
  expect(document.querySelector('a[download]')).not.toBeNull()
  button('复制报价图片').click(); await tick()
  expect(copyQuoteSheetImage).toHaveBeenCalledOnce()
  expect(JSON.stringify(sheet)).toBe(before)
})

it.each([false, true])('blocks current FOB output including an unsaved batch snapshot (savedSheet=%s)', async snapshot => {
  version.updateRequired = true
  mount(FobSheet, { ...fobProps, currentQuote: true, ...(snapshot ? { savedSheet: sheet, previewOnly: true } : {}) }); await tick()
  expect(button('复制报价数据').disabled).toBe(true)
  expect(renderCustomerQuoteSheet).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain('当前未保存报价')
})

it('keeps historical FOB prices exportable even when present-day calculation inputs are invalid', async () => {
  version.updateRequired = true
  const before = JSON.stringify(sheet)
  mount(FobSheet, { ...fobProps, rate: 999, quantity: 'invalid', product: { ...product, parsed: { ...product.parsed, priceTiers: [] } }, savedSheet: sheet }); await tick()
  expect(renderCustomerQuoteSheet).toHaveBeenCalledWith(sheet, expect.any(Function))
  button('复制报价数据').click()
  expect(copyQuoteSheetData).toHaveBeenCalledWith(sheet)
  expect(JSON.stringify(sheet)).toBe(before)
  expect(appUpdate.assertCurrent).not.toHaveBeenCalled()
})

it('blocks all current FOB file/print export buttons without affecting the source snapshot', async () => {
  version.updateRequired = true
  mount(FobExports, { sheet, currentQuote: true }); await tick()
  for (const label of ['导出Excel', '打印 / 另存PDF', '下载分页图片']) { expect(button(label).disabled).toBe(true); button(label).click() }
  expect(fobSheetWorkbook).not.toHaveBeenCalled()
  expect(fobSheetPrintHtml).not.toHaveBeenCalled()
  expect(renderCustomerQuoteSheet).not.toHaveBeenCalled()
})

it('does not download a current FOB workbook when an update arrives while it is being generated', async () => {
  let finish!: (blob: Blob) => void
  vi.mocked(fobSheetWorkbook).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  mount(FobExports, { sheet, currentQuote: true }); await tick()
  button('导出Excel').click(); await tick()
  version.updateRequired = true; finish(new Blob(['xlsx'])); await tick()
  expect(HTMLAnchorElement.prototype.click).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain('系统已更新')
})

it('keeps saved FOB workbook exports available after an update', async () => {
  version.updateRequired = true
  mount(FobExports, { sheet }); await tick()
  button('导出Excel').click(); await tick()
  expect(fobSheetWorkbook).toHaveBeenCalledWith(sheet)
  expect(HTMLAnchorElement.prototype.click).toHaveBeenCalledOnce()
  expect(appUpdate.assertCurrent).not.toHaveBeenCalled()
})
