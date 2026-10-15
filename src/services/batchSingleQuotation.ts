import type { PriceException, PriceExceptionType } from '@/data/priceExceptions'
import type { createQuotationRecord } from '@/data/quotationRecords'
import type { CustomerQuoteSheet } from '@/data/customerQuoteSheet'
import type { QuotationDraftPayload } from './quotationDrafts'
import { request } from './http'
import { saveQuotationBatch, type QuotationBatchResult } from './quotationBatchSubmission'

export interface BatchSingleItem {
  input: Parameters<typeof createQuotationRecord>[0]
  conditions: QuotationDraftPayload
  sheet: CustomerQuoteSheet
}
export interface BatchSingleDocument {
  createdRecords?: QuotationBatchResult['records']
  schemaVersion: 1
  customerName: string
  items: Array<Pick<BatchSingleItem, 'input' | 'sheet'>>
  sheet: CustomerQuoteSheet
  id?: string
  no?: string
  createdAt?: string
  salespersonName?: string
  ownerAccount?: string
  _reviewVersion?: number
  review?: BatchSingleReviewState
}
export type BatchReviewResult = 'approved' | 'rejected' | 'channel-exempt' | 'logistics-exempt'
export interface BatchItemReview { priceException?: PriceException; index: number; status: 'pending' | 'approved' | 'rejected'; reviewResult?: BatchReviewResult; reason?: string; note?: string; reviewedBy?: string; reviewedAccount?: string; reviewedAt?: string }
export interface BatchSingleReviewState {
  status: string; revision: number; claimantAccount?: string; claimantName?: string; claimedAt?: string
  items: BatchItemReview[]
  history: Array<{ id: string; action: string; actorName: string; at: string; note: string; revision: number; items: Array<{ index: number; sku: string; before: unknown; after: unknown; previousReview?: BatchItemReview }> }>
}
export const batchReviewLabels: Record<string, string> = { pending: '待审核', reviewing: '审核中', approved: '审核通过', rejected: '价格异常 / 已退回', 'channel-exempt': '同渠道免审', 'logistics-exempt': '物流免审-采购已审' }
export function batchItemReviews(document: BatchSingleDocument): BatchItemReview[] {
  return document.items.map((_, index) => document.review?.items.find(item => item.index === index) ?? { index, status: 'pending' })
}
export function approvedBatchSheet(document: BatchSingleDocument): CustomerQuoteSheet {
  const states = batchItemReviews(document)
  const approved = new Set(document.items.flatMap((item, index) => states[index]?.status === 'approved' ? item.sheet.rows.map(row => `${index}:${row.key}`) : []))
  return { ...document.sheet, rows: document.sheet.rows.filter(row => approved.has(row.key)).map((row, index) => ({ ...row, number: index + 1 })) }
}
export function reviewBatchSingle(document: BatchSingleDocument, action: 'claim' | 'cancel' | 'complete', key: string, options: { result?: BatchReviewResult; note?: string; items?: Array<{ index: number; reason?: string; exceptionType?: PriceExceptionType }> } = {}) {
  return request<BatchSingleDocument>(`/batch-single-quotations/${encodeURIComponent(document.id!)}/review`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ _reviewVersion: document._reviewVersion ?? 0, action, ...options }) })
}
export function correctBatchSingle(document: BatchSingleDocument, index: number, fresh: BatchSingleDocument, key: string) {
  return request<BatchSingleDocument>(`/batch-single-quotations/${encodeURIComponent(document.id!)}/corrections`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ _reviewVersion: document._reviewVersion ?? 0, items: [{ index }], document: fresh }) })
}
export function loadBatchSingleOriginal(id: string) { return request<BatchSingleDocument>(`/batch-single-quotations/${encodeURIComponent(id)}/original`) }
export interface BatchSingleLine { sku: string; selected: boolean; error: string; queried?: boolean; queryState?: import('./batchSingleReadiness').BatchQueryState; deferred?: boolean; item?: BatchSingleItem }
export function parseBatchSingleSkus(text: string): BatchSingleLine[] {
  if (text.length > 20000) throw new Error('粘贴内容过长，每批最多50个 SKU')
  const lines = text.replace(/^\uFEFF/, '').split(/\r?\n/).map(s => s.trim()).filter(Boolean)
  if (!lines.length || lines.length > 50) throw new Error('请每行填写一个 SKU，每批1至50行；未截断数据')
  const seen = new Set<string>()
  return lines.map(raw => {
    const sku = raw.toUpperCase()
    const error = !/^[A-Z0-9][A-Z0-9._/-]{0,95}$/.test(sku) ? '每行只能填写一个有效 SKU' : seen.has(sku) ? '重复 SKU，请取消此行' : ''
    seen.add(sku)
    return { sku, selected: !error, error }
  })
}
export function mergeBatchSingle(items: BatchSingleItem[]): BatchSingleDocument {
  if (!items.length || items.length > 50) throw new Error('请选择1至50个已完成报价的 SKU')
  const first = items[0]!, seen = new Set<string>()
  for (const item of items) {
    if (item.input.quoteMode !== 'single' || seen.has(item.input.primarySku)) throw new Error('批量单品不能混入其他模式或重复 SKU')
    seen.add(item.input.primarySku)
    if (item.input.customerName !== first.input.customerName || item.input.customerGrade !== first.input.customerGrade || item.input.exchangeRate !== first.input.exchangeRate || item.input.commissionThreshold !== first.input.commissionThreshold) throw new Error('客户、等级、汇率或佣金条件不一致，请统一重新生成')
    if (JSON.stringify(item.input.customerQuote?.quantities) !== JSON.stringify(first.input.customerQuote?.quantities)) throw new Error('各 SKU 的数量列必须一致，请重新生成')
    if (item.sheet.issues.length || !item.sheet.rows.length) throw new Error(`${item.input.primarySku}：报价单尚未准备好`)
  }
  const sheet = JSON.parse(JSON.stringify(first.sheet)) as CustomerQuoteSheet
  sheet.rows = items.flatMap((item, index) => item.sheet.rows.map(row => ({ ...row, prices: [...row.prices], key: `${index}:${row.key}`, sku: item.input.primarySku }))).map((row, index) => ({ ...row, number: index + 1 }))
  if (sheet.rows.length > 500) throw new Error('合并报价最多500条渠道明细，请减少 SKU 或渠道')
  return JSON.parse(JSON.stringify({ schemaVersion: 1, customerName: first.input.customerName, items, sheet }))
}
export async function saveBatchSingle(document: BatchSingleDocument, key: string): Promise<BatchSingleDocument> {
  const result = await saveQuotationBatch({ kind: 'single', document }, key)
  return { ...document, no: result.batchNo, createdRecords: result.records }
}
export function loadBatchSingleRecords(page = 0, scope = 'mine') {
  return request<{ items: Array<{ id: string; no: string; customerName: string; skuCount: number; createdAt: string; salespersonName: string; reviewStatus?: string; revision?: number }>; total: number }>('/batch-single-quotations?page=' + page + '&scope=' + scope, { signal: AbortSignal.timeout(20000) })
}
export function loadBatchSingleRecord(id: string) {
  return request<BatchSingleDocument>('/batch-single-quotations/' + encodeURIComponent(id), { signal: AbortSignal.timeout(20000) })
}
