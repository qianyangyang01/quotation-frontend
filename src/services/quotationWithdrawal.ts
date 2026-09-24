import { api, idempotencyKey } from './http'
import { normalizeQuotationRecord, type QuotationRecord } from '@/data/quotationRecords'
import { normalizeDraftState, type QuotationDraftState } from './quotationDrafts'
import { quotationReissuePayload } from './quotationReissue'

export function withdrawalBlocked(row: QuotationRecord, account: string) {
  if (row.salespersonAccount !== account) return '只能操作自己的报价'
  if (row.lifecycleState && row.lifecycleState !== 'active') return '只能操作当前报价'
  if (row.status === 'won' || row.dealLines?.length || (row.dealQuantity ?? 0) > 0) return '已成交或已有成交明细，不能取消或撤回'
  if (row._version == null) return '请刷新后重试'
  return ''
}
export function cancelQuotation(id: string, version: number, draftVersion?: number) {
  return api.post(`/quotations/${encodeURIComponent(id)}/cancel`, { _version: version, ...(draftVersion == null ? {} : { draftVersion }) }, `cancel:${id}:${version}:${draftVersion ?? 'record'}`)
}
export async function withdrawQuotation(row: QuotationRecord) {
  return normalizeDraftState(await api.post<QuotationDraftState>(`/quotations/${encodeURIComponent(row.id)}/withdraw`,
    { _version: row._version, draft: quotationReissuePayload(row) }, `withdraw:${row.id}:${row._version}`))
}
/** Keep an uncertain submission's key until its exact body changes, including after a network timeout. */
export function withdrawalSubmitter() {
  let previous = '', key = ''
  return async (source: NonNullable<QuotationDraftState['sourceQuote']>, draftVersion: number, quotation: unknown) => {
    const body = { _version: source.version, draftVersion, quotation }
    const signature = JSON.stringify({ id: source.id, body })
    if (signature !== previous) { previous = signature; key = idempotencyKey('resubmit') }
    return normalizeQuotationRecord(await api.post<QuotationRecord>(`/quotations/${encodeURIComponent(source.id)}/resubmit`, body, key))!
  }
}
