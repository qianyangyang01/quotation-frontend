import { api } from './http'
import { normalizePurchaseRecord, type PurchaseProductRecord } from '@/data/purchaseStore'
import type { FobRecord } from './fobPurchase'

export type FobCatalogRecord = FobRecord & { dataSource: 'fob' }
export type PurchaseCatalogRecord = PurchaseProductRecord | FobCatalogRecord
export interface PurchaseCatalogPage { items: PurchaseCatalogRecord[]; page: number; size: number; total: number; totalPages: number }
export async function loadPurchaseCatalogPage(query = '', page = 0, size = 10, signal?: AbortSignal): Promise<PurchaseCatalogPage> {
  const result = await api.get<PurchaseCatalogPage>(`/purchase-catalog?q=${encodeURIComponent(query)}&page=${page}&size=${size}`, { signal })
  return { ...result, items: result.items.map(row => row.dataSource === 'fob' ? row : normalizePurchaseRecord(row)) }
}
