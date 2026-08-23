import { api, idempotencyKey } from './http'

export type MigrationEntry = { entryKey?: string; source: string; container: string; key: string; category: string; decision: 'migrate' | 'exclude' | 'review'; reason: string; count: number }
export type MigrationBatch = {
  id: string; sourceOrigin: string; sourceHash: string; sourceType: 'legacy-browser-report' | 'sumao-logistics-zip'; status: string
  counts: Record<string, number>; report: { entries?: MigrationEntry[]; approvedEntryKeys?: string[]; execution?: unknown }
  diff: Record<string, unknown>; errors: Array<{ source?: string; message?: string; level?: string }>; checkpoint: Record<string, unknown>
  requestId?: string; lastError?: string; createdAt: string; updatedAt: string; completedAt?: string
}

export function migrationEntryKey(entry: MigrationEntry) { return entry.entryKey || `${entry.source}/${entry.key}` }
export async function uploadMigrationSource(sourceType: MigrationBatch['sourceType'], file: File) { const form = new FormData(); form.append('sourceType', sourceType); form.append('file', file); return api.post<MigrationBatch>('/migration-jobs/business/sources', form) }
export function loadMigrationBatch(id: string) { return api.get<MigrationBatch>(`/migration-jobs/business/${id}`) }
export function approveMigrationBatch(id: string, approvedEntryKeys: string[], ownerMappings: Record<string, string> = {}, conflictResolutions: Record<string, string> = {}) { return api.post<MigrationBatch>(`/migration-jobs/business/${id}/approve`, { approvedEntryKeys, ownerMappings, conflictResolutions }) }
export function executeMigrationBatch(id: string) { return api.post<MigrationBatch>(`/migration-jobs/business/${id}/execute`, undefined, idempotencyKey('migration-execute')) }
export function rollbackMigrationBatch(id: string) { return api.post<MigrationBatch>(`/migration-jobs/business/${id}/rollback`, undefined, idempotencyKey('migration-rollback')) }
