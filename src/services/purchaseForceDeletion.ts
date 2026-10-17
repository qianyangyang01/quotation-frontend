import { api } from './http'

export function forceDeletePurchaseProduct(sku: string, expectedVersion: number, confirmationSku: string) {
  return api.post<void>(`/purchase-products/${encodeURIComponent(sku)}/force-delete`, { expectedVersion, confirmationSku })
}

export interface DeletedPurchaseDisplaySnapshot {
  sku: string; category: string; productImage: string; physicalImage: string; createdAt: string; deletedAt: string
}

export async function loadDeletedPurchaseSnapshots(skus: string[]): Promise<DeletedPurchaseDisplaySnapshot[]> {
  const unique = [...new Set(skus.map(sku => sku.trim().toUpperCase()).filter(Boolean))]
  if (!unique.length) return []
  const result: DeletedPurchaseDisplaySnapshot[] = []
  for (let offset = 0; offset < unique.length; offset += 100) {
    result.push(...await api.get<DeletedPurchaseDisplaySnapshot[]>(`/purchase-products/deleted-snapshots?${unique.slice(offset, offset + 100).map(sku => `skus=${encodeURIComponent(sku)}`).join('&')}`))
  }
  return result
}

export function deletedPurchaseSnapshotForQuote(snapshots: DeletedPurchaseDisplaySnapshot[], sku: string, quotedAt: string) {
  const at = Date.parse(quotedAt)
  return snapshots.find(snapshot => snapshot.sku === sku && at >= Date.parse(snapshot.createdAt) && at <= Date.parse(snapshot.deletedAt))
}
