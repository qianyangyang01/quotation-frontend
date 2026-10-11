import { api } from './http'
import { normalizeQuotationRecord, type QuotationRecord } from '@/data/quotationRecords'
import type { FobQuotationSnapshot } from './fobQuotationRecords'

export interface FobItemReview { index: number; status: 'pending' | 'approved' | 'rejected'; reason: string; reviewedBy?: string; reviewedAt?: string }
export function fobItemReviews(record: QuotationRecord): FobItemReview[] {
  return record.fobItemReviews ?? (record.fob?.items ?? []).map((_, index) => ({ index, status: ['approved','logistics-exempt'].includes(record.financeReviewStatus || '') ? 'approved' : 'pending', reason: '' }))
}
export function correctionChanged(before: FobQuotationSnapshot, after: FobQuotationSnapshot) {
  return before.product.updatedAt !== after.product.updatedAt || before.quantity !== after.quantity || before.rate !== after.rate || before.policy.scope !== after.policy.scope || before.policy.calculation !== after.policy.calculation
}
export async function reviewFobItems(record: QuotationRecord, action: 'return' | 'approve', items: { index: number; reason: string }[], approveOthers: boolean) {
  return normalizeQuotationRecord(await api.post<QuotationRecord>(`/quotations/${record.id}/fob-corrections/review`, { _version: record._version, _reviewVersion: record._reviewVersion ?? 0, action, items, approveOthers }))!
}
export async function submitFobCorrections(record: QuotationRecord, items: { index: number; snapshot: FobQuotationSnapshot }[], key: string) {
  return normalizeQuotationRecord(await api.post<QuotationRecord>(`/quotations/${record.id}/fob-corrections/submit`, { _version: record._version, _reviewVersion: record._reviewVersion ?? 0, items }, key))!
}
export function loadFobRevision(id: string, revision: number) { return api.get<FobQuotationSnapshot>(`/quotations/${id}/fob-corrections/revisions/${revision}`) }
