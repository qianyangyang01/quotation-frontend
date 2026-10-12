import type { CustomerQuoteSheet } from '@/data/customerQuoteSheet'
import type { FobQuoteProduct, FobQuoteRow, FobSheetRow, FobSmallOrderPolicy } from './fobQuotation'
import { api } from './http'
import { normalizeQuotationRecord, type QuotationRecord, type QuotationRecordQuoteOption } from '@/data/quotationRecords'

export interface FobQuotationSnapshot {
  schemaVersion: 1 | 2
  items?: FobQuotationSnapshot[]
  product: FobQuoteProduct
  rate: number
  quantity: number
  quantities?: number[]
  policy: FobSmallOrderPolicy
  displayMode: 'tiers' | 'quantity'
  sheet: CustomerQuoteSheet
  tiers?: FobQuoteRow[]
  ranges?: FobSheetRow[]
  current?: FobSheetRow
}
export async function saveFobQuotation(customerName: string, fob: FobQuotationSnapshot, key: string) {
  const record = await api.post<QuotationRecord>('/quotations', { quoteMode: 'fob', customerName, fob }, key)
  return normalizeQuotationRecord(record)!
}
export async function validateFobQuotation(customerName: string, fob: FobQuotationSnapshot) {
  return api.post<FobQuotationSnapshot>('/quotations/fob/preview', { quoteMode: 'fob', customerName: customerName.trim() || '预览客户', fob })
}
// Deal choices are SKU/declaration choices, never persisted as international logistics channels.
export function fobBatchDealOptions(record: QuotationRecord): QuotationRecordQuoteOption[] {
  return (record.fob?.items ?? []).flatMap((item, index) => (record.fobItemReviews && record.fobItemReviews[index]?.status !== 'approved' ? [] : ['declared', 'undeclared']).map(kind => ({
    id: `fob:${index}:${kind}`, country: '', carrier: '', channel: `${item.product.sku} · ${kind === 'declared' ? '报关' : '不报关'}`,
    rule: 'FOB', eta: `单位：${item.product.parsed.priceTiers[0]?.unit || '件'}`, quote1Usd: null, quote2Usd: null, quote3Usd: null, quoteCustomUsd: null,
  })))
}
