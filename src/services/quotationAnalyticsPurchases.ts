import { loadPurchaseProductPage, type PurchaseProductRecord } from '@/data/purchaseStore'

// Analytics needs the entire purchase category catalog, not only the first 500 products.
export async function loadAnalyticsPurchases(): Promise<PurchaseProductRecord[]> {
  const first = await loadPurchaseProductPage('', 0, 500)
  const rows = [...first.items]
  for (let page = 1; page < first.totalPages; page++) {
    const next = await loadPurchaseProductPage('', page, 500)
    if (next.total !== first.total) throw new Error('采购目录在读取期间发生变化，请刷新后重试')
    rows.push(...next.items)
  }
  const unique = new Map(rows.map(row => [row.sku.toUpperCase(), row]))
  if (unique.size !== first.total) throw new Error('采购目录读取不完整，请刷新后重试')
  return [...unique.values()]
}
