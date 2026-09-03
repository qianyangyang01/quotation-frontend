import { api, downloadFile, idempotencyKey } from '@/services/http'

export type Dataset = { id: string; name: string; status: 'active' | 'preparing' | 'archived'; revision: number; created_at: string }
export type SourceIssue = { row: number; field: string; message: string; level: string }
export type Price = {
  areaName: string; countryCode: string; weightFromKg: number; weightToKg: number
  weightFromInclusive?: boolean; weightToInclusive?: boolean; pricePerKg?: number; registrationFee?: number
  firstWeightPrice?: number; firstWeightKg?: number; nextWeightPrice?: number; nextWeightKg?: number; intervalPrice?: number
  sourceSheet?: string; sourceRow?: number; notes?: string; pendingReason?: string; pricingModel?: string; currency?: string
  zoneName?: string
  providerName?: string; channelName?: string; versionId?: string; versionNumber?: number; quoteReady?: boolean
}
export type Channel = { id: string; providerId: string; name: string; providerName: string; code: string; channelKey: string; currentVersionId: string | null; quoteReady: boolean }
export type Diff = { key: string; type: string; row: Price; previous?: Price; changes: Array<{ field: string; before: unknown; after: unknown; delta?: number; percentChange?: number | null }> }
export type Version = { id: string; channelId: string; versionNumber: number; status: string; fileName: string; quoteReady?: boolean; errors: number; rowCount?: number; rows?: Price[]; issues?: SourceIssue[]; diffRows?: Diff[]; summary: Record<string, number>; basePublishedVersionId?: string; batchId?: string; sourceFileIndex?: number; importedAt?: string; publishedAt?: string }
export type Workspace = { dataset: Dataset; providers: Array<{ id: string; name: string }>; channels: Channel[]; versions: Version[] }
export type BatchResult = { channelId?: string; channelName: string; providerName: string; versionId?: string; status: string; message?: string; errors?: number; quoteReady?: boolean; summary?: Record<string, number>; issues?: SourceIssue[] }
export type Batch = { id: string; dataset_id: string; status: string; phase: string; created_at: string; payload: { progress: number; elapsedMs?: number; error?: string; files: Array<{ name: string }>; fileReports?: Array<{ fileName: string; status: string; message?: string; sourceEvidence?: { sha256: string }; sheets?: Array<{ name: string; status: string; priceRows?: number; errors?: number; message?: string }> }>; results: BatchResult[] } }
export type BatchSummary = Pick<Batch, 'id' | 'status' | 'phase' | 'created_at'> & { progress: number; files: Array<{ name: string }> }
export type Mapping = { oldChannelId: string; oldName: string; newChannelId: string; status: string; candidates: Channel[] }
export type Cutover = { previewToken: string; sourceDatasetId: string; targetDatasetId: string; readyChannels: number; requiredReady: boolean; requiredConfirmed: boolean; requiredCount: number; requiredNotReady: Channel[]; unmappedChannels: number; mappings: Mapping[]; pendingChannels: Channel[]; draftsToReprice?: number; bindingChanges?: Array<{ kind: string; id: string; path: string; before: string; after: string; status: string }> }
export type PricePage = { items: Price[]; total: number; page: number; size: number; totalPages: number }
export type RequiredChannels = { revision: number; confirmed: boolean; note?: string; confirmedBy?: string; confirmedAt?: string; channelIds: string[]; channels: Array<Channel & { archived?: boolean; countries: string[]; zones: string[]; priceRows: number; pendingReasons: string[] }> }
export type BillingAcceptance = { versionId: string; fingerprint: string; engineVersion: string; pricePublished: boolean; quoteReady: boolean; unsupportedReasons: string[]; records: Array<{ kind: string; reviewed_by: string; reviewed_at: string; engine_version: string }> }
export type BatchPublishSelection = { channelId: string; versionId: string; removalConfirmed: boolean; reviewConfirmed: boolean }
export type BatchPublishResult = { providerId: string; count: number; published: Array<{ id: string; channelId: string; versionNumber: number; status: string; quoteReady: boolean }> }
const root = '/logistics/rebuild'
export const logisticsRebuild = {
  required: (id: string) => api.get<RequiredChannels>(`${root}/datasets/${id}/required-channels`),
  saveRequired: (id: string, input: { revision: number; confirmed: boolean; channelIds: string[]; note: string }, key: string) => api.put<RequiredChannels>(`${root}/datasets/${id}/required-channels`, input, { 'Idempotency-Key': key }),
  billing: (id: string) => api.get<BillingAcceptance>(`${root}/versions/${id}/billing-acceptance`),
  approveBilling: (id: string, input: unknown, key: string) => api.post<BillingAcceptance>(`${root}/versions/${id}/billing-acceptance`, input, key),
  datasets: () => api.get<Dataset[]>(`${root}/datasets`),
  create: (name: string) => api.post<Dataset>(`${root}/datasets`, { name }, idempotencyKey('dataset')),
  workspace: (id: string) => api.get<Workspace>(`${root}/datasets/${id}/workspace`),
  prices: (id: string, filters: URLSearchParams) => api.get<PricePage>(`${root}/datasets/${id}/prices?${filters}`),
  batches: (id: string) => api.get<BatchSummary[]>(`${root}/datasets/${id}/imports`),
  batch: (id: string) => api.get<Batch>(`${root}/imports/${id}`),
  upload: (id: string, files: File[], replaceDrafts: boolean, key: string) => {
    const form = new FormData(); files.forEach(file => form.append('files', file)); form.append('replaceDrafts', String(replaceDrafts))
    return api.post<Batch>(`${root}/datasets/${id}/imports`, form, key)
  },
  retry: (id: string) => api.post<Batch>(`${root}/imports/${id}/retry`),
  version: (id: string) => api.get<Version>(`${root}/versions/${id}`),
  review: (version: Version, note: string, removalConfirmed: boolean, reviewConfirmed: boolean, key: string) => api.post<Version>(`${root}/channels/${version.channelId}/versions/${version.id}/review`, { note, removalConfirmed, reviewConfirmed }, key),
  publishProvider: (providerId: string, selections: BatchPublishSelection[], note: string, key: string) => api.post<BatchPublishResult>(`/logistics/providers/${providerId}/versions/publish-batch`, { selections, note }, key),
  recompare: (v: Version) => api.post<Version>(`${root}/channels/${v.channelId}/versions/${v.id}/recompare`),
  rollback: (v: Version, note: string) => api.post<Version>(`/logistics/channels/${v.channelId}/versions/${v.id}/rollback`, { note }, idempotencyKey('rollback')),
  backup: (id: string) => api.post<{ sha256: string }>(`${root}/datasets/${id}/backup`),
  preview: (id: string, mappings: Mapping[] = []) => api.post<Cutover>(`${root}/datasets/${id}/preview`, { mappings: mappings.map(({ oldChannelId, newChannelId }) => ({ oldChannelId, newChannelId })) }),
  activate: (id: string, preview: Cutover, note: string, unavailableConfirmed: boolean, key: string) => api.post<Cutover>(`${root}/datasets/${id}/activate`, { previewToken: preview.previewToken, mappings: preview.mappings.map(({ oldChannelId, newChannelId }) => ({ oldChannelId, newChannelId })), note, reviewConfirmed: true, unavailableConfirmed }, key),
  exportPrices: (id: string, filters: URLSearchParams) => downloadFile(new URLSearchParams({ ...Object.fromEntries(filters), kind: 'prices', id })),
  exportDiff: (v: Version) => downloadFile(new URLSearchParams({ kind: 'version-diff', id: v.id })),
  exportBatchDiff: (id: string) => downloadFile(new URLSearchParams({ kind: 'batch-diff', id })),
  original: (id: string, index: number) => downloadFile(new URLSearchParams({ kind: 'source', id, index: String(index) })),
  evidence: (id: string, index: number) => downloadFile(new URLSearchParams({ kind: 'evidence', id, index: String(index) })),
}

export function weightLabel(price: Price) {
  return `${price.weightFromInclusive ? '[' : '('}${Number((price.weightFromKg * 1000).toFixed(3))}, ${Number((price.weightToKg * 1000).toFixed(3))}${price.weightToInclusive === false ? ')' : ']'} g`
}
export function completedBatchStage(batch: Pick<Batch, 'status' | 'payload'>) {
  if (batch.status !== 'completed') return ''
  if (batch.payload.fileReports?.some(f => f.status === 'failed')) return '存在文件解析失败，请核对'
  if (batch.payload.results.some(r => r.status === 'blocked')) return '存在阻断，请核对'
  if (batch.payload.results.some(r => r.status === 'draft')) return '待审核'
  return '无需审核'
}
export function money(value: unknown) { return typeof value === 'number' && Number.isFinite(value) ? value.toFixed(2) : '—' }
export function shown(value: unknown) { return value == null ? '—' : typeof value === 'object' ? JSON.stringify(value) : String(value) }
