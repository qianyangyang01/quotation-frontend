import { request } from './http'
import type { PurchasePastePatch } from '@/data/purchasePaste'
import type { PurchaseProductRecord } from '@/data/purchaseStore'

export interface PasteExpected { sku: string; productId: string | null; version: number | null; updatedAt: string | null }
export interface PasteSkipped { sku: string; sourceRow: number }
export interface PastePreviewRow {
  sourceRow: number
  sku: string
  action: 'create' | 'update' | 'unchanged'
  expected: PasteExpected
  changes: { field: string; label: string; before: unknown; after: unknown }[]
  notices: string[]
  effective: Partial<PurchaseProductRecord>
}
export interface PastePreview { rows: PastePreviewRow[]; skipped: PasteSkipped[] }
export interface PasteResult { added: PurchaseProductRecord[]; updated: PurchaseProductRecord[]; unchanged: string[]; skipped: PasteSkipped[] }
export interface PasteSavedCounts { added: number; updated: number; unchanged: number; skipped: number }
export const previewPurchasePaste = (rows: PurchasePastePatch[]) =>
  request<PastePreview>('/purchase-products/paste/preview', { method: 'POST', body: JSON.stringify(rows), signal: AbortSignal.timeout(60_000) })
export const confirmPurchasePaste = (rows: PurchasePastePatch[], expected: PasteExpected[]) =>
  request<PasteResult>('/purchase-products/paste/confirm', { method: 'POST', body: JSON.stringify({ rows, expected }), signal: AbortSignal.timeout(60_000) })
