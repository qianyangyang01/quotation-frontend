import type { PurchaseImportPreview } from '@/data/purchaseWorkbook'
import { api, idempotencyKey } from './http'

export type ServerPurchaseImportPreview = PurchaseImportPreview & { jobId: string }
type PreviewResponse = { jobId: string; fileName: string; records: PurchaseImportPreview['records']; issues: PurchaseImportPreview['issues']; summary: Omit<PurchaseImportPreview, 'fileName' | 'records' | 'issues'> }

export async function previewPurchaseWorkbook(file: File): Promise<ServerPurchaseImportPreview> {
  const form = new FormData(); form.append('file', file)
  const response = await api.post<PreviewResponse>('/purchase-imports/preview', form)
  return { jobId: response.jobId, fileName: response.fileName, records: response.records, issues: response.issues, ...response.summary }
}

export async function confirmPurchaseImport(jobId: string) {
  return api.post<{ jobId: string; imported: number; status: string }>(`/purchase-imports/${jobId}/confirm`, undefined, idempotencyKey('purchase-import'))
}
