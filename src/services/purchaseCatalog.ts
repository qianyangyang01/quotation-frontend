import { api } from './http'
import { normalizePurchaseRecord, type PurchaseProductRecord } from '@/data/purchaseStore'
import type { FobRecord } from './fobPurchase'

export type FobCatalogRecord = FobRecord & { dataSource: 'fob' }
export type PurchaseCatalogRecord = PurchaseProductRecord | FobCatalogRecord
export interface PurchaseCatalogPage { items: PurchaseCatalogRecord[]; page: number; size: number; total: number; totalPages: number }
export interface PurchaseCatalogDates { dateFrom?: string; dateTo?: string }
function dateQuery(dates: PurchaseCatalogDates) {
  return (dates.dateFrom ? `&dateFrom=${encodeURIComponent(dates.dateFrom)}` : '')
    + (dates.dateTo ? `&dateTo=${encodeURIComponent(dates.dateTo)}` : '')
}
export function purchaseCatalogExportUrl(query = '', dates: PurchaseCatalogDates = {}) {
  const base = String(import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '')
  return `${base}/purchase-catalog/export.xlsx?q=${encodeURIComponent(query)}${dateQuery(dates)}`
}
export async function loadPurchaseCatalogPage(query = '', page = 0, size = 10, signal?: AbortSignal, dates: PurchaseCatalogDates = {}): Promise<PurchaseCatalogPage> {
  const result = await api.get<PurchaseCatalogPage>(`/purchase-catalog?q=${encodeURIComponent(query)}&page=${page}&size=${size}${dateQuery(dates)}`, { signal })
  return { ...result, items: result.items.map(row => row.dataSource === 'fob' ? row : normalizePurchaseRecord(row)) }
}
