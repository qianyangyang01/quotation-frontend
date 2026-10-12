import { beforeEach, expect, it, vi } from 'vitest'
import { request } from './http'
import { mergeBatchSingle, saveBatchSingle } from './batchSingleQuotation'
import { saveQuotationBatch } from './quotationBatchSubmission'
import type { BatchSingleItem } from './batchSingleQuotation'
import { normalizeQuotationRecord } from '@/data/quotationRecords'

vi.mock('./http', async original => ({ ...await original<typeof import('./http')>(), request: vi.fn() }))
beforeEach(() => vi.clearAllMocks())
it('submits one atomic request and keeps the edited combined preview while returning independent record IDs', async () => {
  const items = Array.from({ length: 14 }, (_, i) => ({ input: { quoteMode: 'single', primarySku: `SKU${i}`, customerName: '客户', customerGrade: 'S', exchangeRate: 7, commissionThreshold: 1, customerQuote: { quantities: [1] } }, sheet: { title: '已编辑标题', quantityLabels: ['1 pc'], issues: [], rows: [{ key: 'channel', sku: `SKU${i}`, prices: [i + 1] }] } })) as unknown as BatchSingleItem[]
  const document = mergeBatchSingle(items), before = JSON.stringify(document)
  const records = items.map((item, i) => ({ id: `id${i}`, no: `QT${i}`, primarySku: item.input.primarySku, quoteMode: 'single' }))
  vi.mocked(request).mockResolvedValue({ batchId: 'batch', batchNo: 'BQT', records })
  const saved = await saveBatchSingle(document, 'stable-key')
  expect(request).toHaveBeenCalledOnce()
  const [path, options] = vi.mocked(request).mock.calls[0]!
  expect(path).toBe('/quotations/batch'); expect(options?.headers).toEqual({ 'Idempotency-Key': 'stable-key' })
  expect(JSON.parse(String(options?.body))).toEqual({ kind: 'single', document })
  expect(saved.createdRecords).toHaveLength(14); expect(saved.sheet).toEqual(document.sheet); expect(saved.no).toBe('BQT')
  expect(JSON.stringify(document)).toBe(before)
})
it('retains the server-owned batch reference through normalization', () => {
  const quotationBatch = { id: 'batch', no: 'BQT', index: 2, count: 14 }
  const row = normalizeQuotationRecord({ id: 'id', no: 'QT', quoteMode: 'single', quotationBatch })
  expect(row?.quotationBatch).toEqual(quotationBatch)
})
it('sends FOB through the same batch endpoint without submitting an aggregate record', async () => {
  const body = { kind: 'fob', customerName: '客户', fob: { schemaVersion: 2 } } as Parameters<typeof saveQuotationBatch>[0]
  await saveQuotationBatch(body, 'fob-stable-key')
  expect(request).toHaveBeenCalledWith('/quotations/batch', expect.objectContaining({ body: JSON.stringify(body), headers: { 'Idempotency-Key': 'fob-stable-key' } }))
})
