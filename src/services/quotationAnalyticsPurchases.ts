import { normalizePurchaseRecord, type PurchaseProductRecord } from '@/data/purchaseStore'
import type { AnalyticsPurchase } from '@/data/quotationAnalytics'
import { api } from '@/services/http'

export async function loadAnalyticsPurchases(signal?: AbortSignal): Promise<AnalyticsPurchase[]> {
  const response = await api.get<{ items: Partial<PurchaseProductRecord>[]; total: number }>(
    '/purchase-products/analytics-catalog',
    { cache: 'no-store', signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(20_000)]) : AbortSignal.timeout(20_000) },
  )
  if (!Array.isArray(response.items) || !Number.isSafeInteger(response.total) || response.total !== response.items.length) {
    throw new Error('采购目录读取不完整，请重试')
  }
  // Preserve the same legacy price and SKU normalization as full product reads.
  const rows = response.items.map(item => {
    const { sku, category, purchasePriceCny } = normalizePurchaseRecord(item)
    return { sku, category, purchasePriceCny }
  })
  if (rows.some(row => !row.sku) || new Set(rows.map(row => row.sku)).size !== response.total) {
    throw new Error('采购目录读取不完整，请重试')
  }
  return rows
}
