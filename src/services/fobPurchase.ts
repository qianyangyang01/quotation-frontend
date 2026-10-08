import { request } from './http'
import type { FobPatch } from '@/data/fobClipboard'
import type { PasteSavedCounts } from './purchasePaste'
export interface FobParsed {
  notices?: string[]
  minOrderQty: number; orderMultiple: number
  priceTiers: { minQty: number; maxQty: number | null; unitPriceCny: number; unit: string }[]
  freight: { quantity: number; totalFreightCny: number; unitFreightCny: number; estimated: boolean; basis: string }
}
export type FobRecord = FobPatch & { dataSource?: 'fob'; verificationStatus?: 'pending'; parsed?: FobParsed; version?: number; updatedAt?: string }
export interface FobExpected { sku: string; version: number | null; updatedAt: string | null }
export interface FobPreview {
  rows: { sourceRow: number; sku: string; action: 'create' | 'update' | 'unchanged'; expected: FobExpected
    changes: { field: string; label: string; before: string; after: string }[]; issues: string[]; notices: string[]; effective: FobRecord }[]
  skipped: { sourceRow: number; sku: string }[]; canSave: boolean; digest: string
}
export interface FobHistory { createdAt: string; actorAccount: string; changes: { label: string; before: string; after: string }[] }
export const previewFobPaste = (rows: FobPatch[]) => request<FobPreview>('/fob-purchase-products/paste/preview', { method: 'POST', body: JSON.stringify(rows), signal: AbortSignal.timeout(60_000) })
export const confirmFobPaste = (rows: FobPatch[], preview: FobPreview) => request<PasteSavedCounts>('/fob-purchase-products/paste/confirm', { method: 'POST', body: JSON.stringify({ rows, expected: preview.rows.map(row => row.expected), digest: preview.digest }), signal: AbortSignal.timeout(60_000) })
export const loadFobRecord = (sku: string, signal?: AbortSignal) => request<FobRecord>(`/fob-purchase-products/${encodeURIComponent(sku)}`, { signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(20_000)]) : AbortSignal.timeout(20_000), cache: 'no-store' })
export const loadFobHistory = (sku: string) => request<FobHistory[]>(`/fob-purchase-products/${encodeURIComponent(sku)}/history`)
