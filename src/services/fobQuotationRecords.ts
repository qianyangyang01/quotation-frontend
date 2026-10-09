import type { CustomerQuoteSheet } from '@/data/customerQuoteSheet'
import type { FobQuoteProduct, FobQuoteRow, FobSheetRow, FobSmallOrderPolicy } from './fobQuotation'
import { api } from './http'
import { normalizeQuotationRecord, type QuotationRecord } from '@/data/quotationRecords'

export interface FobQuotationSnapshot {
  schemaVersion: 1
  product: FobQuoteProduct
  rate: number
  quantity: number
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
