import { api } from '@/services/http'
import { normalizePurchaseRecord, type PurchaseProductRecord } from '@/data/purchaseStore'

export interface PurchaseHistoryChange { field: string; label: string; before: string | number | null; after: string | number | null }
export interface PurchaseHistoryEntry {
  id: string; createdAt: string; actorAccount: string; actorName: string; sku: string; operation: string; changes: PurchaseHistoryChange[]
}
export interface PurchaseHistoryPage { items: PurchaseHistoryEntry[]; page: number; size: number; total: number; totalPages: number }
export function loadPurchaseHistory(sku: string, page = 0, size = 10) {
  return api.get<PurchaseHistoryPage>(`/purchase-products/${encodeURIComponent(sku)}/history?page=${page}&size=${size}`)
}
export async function updatePurchaseProduct(originalSku: string, record: PurchaseProductRecord) {
  return normalizePurchaseRecord(await api.post<PurchaseProductRecord>(`/purchase-products/${encodeURIComponent(originalSku)}/maintenance`, record))
}
export function historyValue(field: string, value: string | number | null) {
  if (value == null || value === '') return '暂无数据'
  if (field === 'catalogState') return ({ ready: '正式目录', pending_template: '待补全目录', disabled: '已停用' } as Record<string, string>)[String(value)] || String(value)
  if (field === 'taxPoint' && typeof value === 'number') return `${Number((value * 100).toFixed(6))}%`
  return String(value)
}
export function historyImage(field: string, value: string | number | null) {
  return (field === 'productImage' || field === 'physicalImage') && typeof value === 'string' && /^\/api\/v1\/assets\/[a-f0-9-]+$/i.test(value) ? value : ''
}
