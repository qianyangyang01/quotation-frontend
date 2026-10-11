import { api } from './http'
import { normalizeQuotationRecord, type QuotationRecord } from '@/data/quotationRecords'
import type { FobQuotationSnapshot } from './fobQuotationRecords'

export type FobReviewResult = 'approved' | 'logistics-exempt' | 'rejected'
export interface FobItemReview { index: number; status: 'pending' | 'approved' | 'rejected'; reviewResult?: FobReviewResult; note?: string; reason: string; reviewedBy?: string; reviewedAt?: string }
export function fobItemReviews(record: QuotationRecord): FobItemReview[] {
  return (record.fob?.items ?? []).map((_, index) => record.fobItemReviews?.find(item => item.index === index) ?? ({ index, status: ['approved','logistics-exempt'].includes(record.financeReviewStatus || '') ? 'approved' : 'pending', reviewResult: record.financeReviewStatus === 'logistics-exempt' ? 'logistics-exempt' : undefined, reason: '' }))
}
export function fobItemReviewLabel(item: FobItemReview) {
  return item.status === 'pending' ? '待审核' : item.status === 'rejected' ? '价格异常 · 已退回' : item.reviewResult === 'logistics-exempt' ? '物流免审-采购已审' : '审核通过'
}
export function correctionChanged(before: FobQuotationSnapshot, after: FobQuotationSnapshot) {
  return before.product.updatedAt !== after.product.updatedAt || before.quantity !== after.quantity || before.rate !== after.rate || before.policy.scope !== after.policy.scope || before.policy.calculation !== after.policy.calculation
}
export async function reviewFobItems(record: QuotationRecord, action: 'return' | 'approve', items: { index: number; reason: string }[], reviewResult: FobReviewResult = 'approved', note = '') {
  return normalizeQuotationRecord(await api.post<QuotationRecord>(`/quotations/${record.id}/fob-corrections/review`, { _version: record._version, _reviewVersion: record._reviewVersion ?? 0, action, items, approveOthers: false, reviewResult: action === 'return' ? 'rejected' : reviewResult, note }))!
}
export async function submitFobCorrections(record: QuotationRecord, items: { index: number; snapshot: FobQuotationSnapshot; confirmUnchanged?: boolean }[], key: string) {
  return normalizeQuotationRecord(await api.post<QuotationRecord>(`/quotations/${record.id}/fob-corrections/submit`, { _version: record._version, _reviewVersion: record._reviewVersion ?? 0, items }, key))!
}
export function loadFobRevision(id: string, revision: number) { return api.get<FobQuotationSnapshot>(`/quotations/${id}/fob-corrections/revisions/${revision}`) }
