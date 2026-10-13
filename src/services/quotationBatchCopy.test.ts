import { beforeEach, expect, it, vi } from 'vitest'
import { request } from './http'
import { batchRecordCopyLayout, loadQuotationBatchRecords, mergeSavedBatchSheets } from './quotationBatchCopy'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
import type { CustomerQuoteSheet } from '@/data/customerQuoteSheet'
vi.mock('./http', () => ({ request: vi.fn() }))
function record(index: number) { return normalizeQuotationRecord({ id: `r${index}`, no: `QT-${index}`, quoteMode: 'single', primarySku: `SKU-${index}`, purchaseUnitPriceCny: 789.12, quotationBatch: { id: 'batch-id', no: 'B1', index, count: 14 }, quoteOptions: [{ id: 'a', country: '美国', carrier: '闪电猴', channel: '专线', rule: '', eta: '', quote1Usd: index, quote2Usd: null, quote3Usd: null, quoteCustomUsd: null }] })! }
beforeEach(() => vi.mocked(request).mockReset())
it('loads all 14 members from any record in batch order without page or review filters', async () => {
  const rows = Array.from({ length: 14 }, (_, i) => record(i + 1)).reverse()
  vi.mocked(request).mockResolvedValue(rows)
  const result = await loadQuotationBatchRecords(record(8))
  expect(result.map(row => row.id)).toEqual(Array.from({ length: 14 }, (_, i) => `r${i + 1}`))
  expect(request).toHaveBeenCalledWith('/quotations/r8/batch-records', expect.any(Object))
})
it.each(['missing', 'wrong-batch', 'duplicate', 'trashed', 'wrong-index'])('refuses %s batch without silently copying a partial batch', async reason => {
  const rows = Array.from({ length: 14 }, (_, i) => record(i + 1))
  if (reason === 'missing') rows.pop()
  if (reason === 'wrong-batch') rows[0]!.quotationBatch!.id = 'other'
  if (reason === 'duplicate') rows[0] = rows[1]!
  if (reason === 'trashed') rows[0]!.lifecycleState = 'trashed'
  if (reason === 'wrong-index') rows[0]!.quotationBatch!.index = 20
  vi.mocked(request).mockResolvedValue(rows)
  await expect(loadQuotationBatchRecords(record(8))).rejects.toThrow('未复制任何数据')
})
it.each(['single', 'fob'] as const)('loads withdrawn and recycled %s members for trash scope validation without allowing partial copy', async quoteMode => {
  const rows = Array.from({ length: 14 }, (_, i) => ({ ...record(i + 1), quoteMode }))
  rows[0]!.lifecycleState = 'withdrawn'; rows[1]!.lifecycleState = 'trashed'
  vi.mocked(request).mockResolvedValue(rows)
  expect(await loadQuotationBatchRecords(rows[7]!, 'trash')).toEqual(rows)
  expect(request).toHaveBeenCalledWith('/quotations/r8/batch-records?purpose=trash', expect.any(Object))
  await expect(loadQuotationBatchRecords(rows[7]!)).rejects.toThrow('未复制任何数据')
  vi.mocked(request).mockResolvedValue(rows.slice(1))
  await expect(loadQuotationBatchRecords(rows[7]!, 'trash')).rejects.toThrow('本次未移入任何记录')
})
it('allows trash validation after a member changes quotation mode during re-edit, while copy still checks compatibility', async () => {
  const rows = Array.from({ length: 14 }, (_, i) => record(i + 1))
  rows[0]!.quoteMode = 'bundle'
  vi.mocked(request).mockResolvedValue(rows)
  expect(await loadQuotationBatchRecords(rows[7]!, 'trash')).toEqual(rows)
  await expect(loadQuotationBatchRecords(rows[7]!)).rejects.toThrow('未复制任何数据')
})
it('preserves saved prices and field permissions and generates one valid clipboard document', () => {
  const rows = [record(1), record(2)], before = JSON.stringify(rows)
  const layout = batchRecordCopyLayout(rows, 'full', 'visible', { includeCost: false, includeDetails: false })
  expect(layout.text).toContain('SKU-1'); expect(layout.text).toContain('SKU-2')
  expect(layout.text).not.toContain('789.12')
  expect(layout.html.match(/<html>/g)).toHaveLength(1)
  expect(layout.html.match(/<!--StartFragment-->/g)).toHaveLength(1)
  expect(JSON.stringify(rows)).toBe(before)
})
function sheet(labels = ['1 pc', '2 pcs'], prices = [10, 20]): CustomerQuoteSheet { return { agent: 'A', date: '2026-10-12', quantityLabels: labels, issues: [], rows: [{ key: 'a', number: 1, sku: 'SKU', country: 'US', provider: '4PX', shippingTime: '', sourceDescription: '', prices }] } }
it('merges saved quantity columns by name rather than shifting prices across SKUs', () => {
  const sheets = [sheet(), sheet(['2 pcs', '3 pcs'], [25, 35])], before = JSON.stringify(sheets)
  const combined = mergeSavedBatchSheets(sheets)
  expect(combined.quantityLabels).toEqual(['1 pc', '2 pcs', '3 pcs'])
  expect(combined.rows.map(row => row.prices)).toEqual([[10, 20, null], [null, 25, 35]])
  expect(combined.rows.map(row => row.key)).toEqual(['0:a', '1:a'])
  expect(JSON.stringify(sheets)).toBe(before)
})
it('copies FOB saved multi-quantity rows, including their original order and prices', () => {
  const rows = [record(1), record(2)].map((record, index) => ({ ...record, quoteMode: 'fob' as const, fob: { sheet: sheet(['Declared', 'Undeclared'], [11 + index, 12 + index]) } as NonNullable<typeof record.fob> }))
  const layout = batchRecordCopyLayout(rows, 'full', 'visible', { includeCost: false, includeDetails: false })
  expect(layout.text).toContain('11.00'); expect(layout.text).toContain('13.00')
})
