import { api } from '@/services/http'

export type CompanyChannel = {
  id: string; providerName: string; channelName: string; logisticsAttribute: string; enabled: boolean
  productCodes: string[]; aliases: string[]; providerAliases: string[]
  sources?: Array<{ file: string; sheet: string; firstRow?: number; lastRow?: number; row?: number }>
}
export type CompanyDirectory = { revision: number; enabled: boolean; entries: CompanyChannel[]; state?: { paused: boolean; rebuild_id?: string; target_dataset_id?: string } }
export type RebuildPreview = { previewToken: string; priceVersions: number; channels: number; quotations: number; imports: number }
export type RebuildJob = { id: string; phase: string; payload: { targetDatasetId: string; removedVersions?: number; historyPreserved?: boolean; priceObjectsPending?: number; readyChannels?: number; blockedChannels?: string[]; backup?: { sha256: string } } }
const root = '/logistics/company-channels'
export const companyChannels = {
  list: () => api.get<CompanyDirectory>(root),
  baseline: () => api.get<CompanyDirectory>(`${root}/baseline`),
  save: (directory: CompanyDirectory) => api.put<CompanyDirectory>(root, directory),
  preview: () => api.get<RebuildPreview>(`${root}/rebuild/preview`),
  begin: (previewToken: string, note: string) => api.post<RebuildJob>(`${root}/rebuild`, { previewToken, note }),
  job: (id: string) => api.get<RebuildJob>(`${root}/rebuild/${id}`),
  step: (id: string, step: 'backup' | 'purge' | 'cleanup' | 'finish' | 'restore', input: Record<string, unknown> = {}) => api.post<RebuildJob>(`${root}/rebuild/${id}/${step}`, input),
}
