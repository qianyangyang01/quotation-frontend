// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import FobQuoteSheet from './FobQuoteSheet.vue'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
import type { FobQuoteProduct } from '@/services/fobQuotation'
import { loadQuotationBatchRecords } from '@/services/quotationBatchCopy'
import { renderCustomerQuoteSheet } from '@/services/customerQuoteSheetRenderer'
vi.mock('@/services/quotationBatchCopy', async original => ({ ...await original<typeof import('@/services/quotationBatchCopy')>(), loadQuotationBatchRecords: vi.fn() }))
vi.mock('@/services/customerQuoteSheetRenderer', async original => ({ ...await original<typeof import('@/services/customerQuoteSheetRenderer')>(), renderCustomerQuoteSheet: vi.fn().mockResolvedValue([{ blob: new Blob(['png']), width: 100, height: 100, firstRow: 1, lastRow: 2 }]) }))
const product: FobQuoteProduct = { sku: 'A', source: 'fob', category: '', weight: '', notices: [], parsed: { minOrderQty: 1, orderMultiple: 1, priceTiers: [], freight: { quantity: 1, totalFreightCny: 0, unitFreightCny: 0, estimated: false, basis: '' } } }
let app: App
async function settle() { for (let i = 0; i < 12; i++) await nextTick() }
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.restoreAllMocks(); vi.unstubAllGlobals() })
it('offers whole batch copy on a later FOB record and preserves all saved quantity rows', async () => {
  const writeText = vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('isSecureContext', true); vi.stubGlobal('navigator', { clipboard: { writeText } })
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:test'); vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
  const records = ['A', 'B'].map((sku, index) => normalizeQuotationRecord({ id: sku, no: sku, quoteMode: 'fob', quotationBatch: { id: 'batch', no: 'B1', count: 2, index: index + 1 }, fob: {
    schemaVersion: 1, product: { ...product, sku }, rate: 6.7, quantity: 3, displayMode: 'quantity', policy: { scope: 'single-price', calculation: 'before-coefficient' },
    sheet: { agent: 'Saved', date: '2026-10-12', quantityLabels: ['With declaration', 'Without declaration'], showQuantityRange: true, issues: [], rows: [3, 6].map(q => ({ key: `${sku}-${q}`, number: q, sku, quantityRange: `${q} pcs`, country: '', provider: '', shippingTime: '', sourceDescription: '', prices: [index + q, index + q + 1] })) },
  } })!)
  const current = records[1]!, before = JSON.stringify(records)
  vi.mocked(loadQuotationBatchRecords).mockResolvedValue(records)
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(FobQuoteSheet, { product, rate: 1, quantity: '100', policy: null, savedSheet: current.fob!.sheet, batchRecord: current }); app.mount(host); await settle()
  expect(host.querySelectorAll('.scope-arrow')).toHaveLength(2)
  const button = (label: string) => [...host.querySelectorAll<HTMLButtonElement>('button')].find(b => b.textContent === label || b.getAttribute('aria-label') === label)!
  button('复制报价数据').click(); await settle()
  expect(writeText.mock.lastCall![0]).toContain('B'); expect(loadQuotationBatchRecords).not.toHaveBeenCalled()
  for (const kind of ['数据', '图片']) {
    button(`复制报价${kind}范围`).click(); await settle()
    ;[...host.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')].find(b => b.textContent?.includes('复制本批'))!.click(); await settle()
    if (kind === '数据') { expect(writeText.mock.lastCall![0]).toContain('A'); expect(writeText.mock.lastCall![0]).toContain('6 pcs') }
    else expect(vi.mocked(renderCustomerQuoteSheet).mock.lastCall![0].rows).toHaveLength(4)
  }
  expect(JSON.stringify(records)).toBe(before)
})
