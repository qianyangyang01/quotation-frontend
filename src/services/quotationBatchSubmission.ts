import { request } from './http'
import type { FobQuotationSnapshot } from './fobQuotationRecords'
import type { BatchSingleDocument } from './batchSingleQuotation'

export interface QuotationBatchResult {
  batchId: string
  batchNo: string
  records: Array<{ id: string; no: string; primarySku: string; quoteMode: 'single' | 'fob' }>
}
export function saveQuotationBatch(body: { kind: 'single'; document: BatchSingleDocument } | { kind: 'fob'; customerName: string; fob: FobQuotationSnapshot }, key: string) {
  return request<QuotationBatchResult>('/quotations/batch', { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal: AbortSignal.timeout(120000) })
}
