import { api, idempotencyKey } from './http'

export type ImageMigrationJob = {
  id: string; status: 'awaiting-parts' | 'uploading' | 'processing' | 'completed' | 'completed-with-errors' | 'failed'
  sourceName: string; summary: { manifestRows?: number; uploadedParts?: number; completed?: number; failed?: number }
  completed: number; failed: number; pending: number; uploadedParts: number; error?: string; createdAt: string; updatedAt: string
}

export async function createImageMigration(manifest: File) {
  const form = new FormData(); form.append('manifest', manifest)
  return api.post<ImageMigrationJob>('/migration-jobs', form)
}

export async function uploadImageMigrationPart(jobId: string, partNumber: number, file: File) {
  const form = new FormData(); form.append('file', file)
  return api.put<ImageMigrationJob>(`/migration-jobs/${jobId}/parts/${partNumber}`, form)
}

export function getImageMigration(jobId: string) { return api.get<ImageMigrationJob>(`/migration-jobs/${jobId}`) }
export function completeImageMigration(jobId: string) { return api.post<ImageMigrationJob>(`/migration-jobs/${jobId}/complete`, undefined, idempotencyKey('image-migration-complete')) }
