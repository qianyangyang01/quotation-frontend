import { api, downloadFile, idempotencyKey, uploadForm, type UploadProgress } from '@/services/http'

export type Dataset = { id: string; name: string; status: 'active' | 'preparing' | 'archived'; revision: number; created_at: string }
export type SourceIssue = { row: number; sourceSheet?: string; field: string; message: string; level: string; code?: string; rowKey?: string; relatedRowKey?: string; suggestedFields?: Partial<Price> }
export type Price = {
  areaName: string; countryCode: string; weightFromKg: number; weightToKg: number
  weightFromInclusive?: boolean; weightToInclusive?: boolean; pricePerKg?: number; registrationFee?: number
  firstWeightPrice?: number; firstWeightKg?: number; nextWeightPrice?: number; nextWeightKg?: number; intervalPrice?: number
  sourceSheet?: string; sourceRow?: number; notes?: string; pendingReason?: string; pricingModel?: string; currency?: string
  zoneName?: string; rowKey?: string
  providerName?: string; channelName?: string; versionId?: string; versionNumber?: number; quoteReady?: boolean
}
export type Provider = { id: string; name: string; code?: string; enabled?: boolean; datasetId?: string; _version?: number }
export type Channel = {
  id: string; providerId: string; name: string; providerName: string; code: string; channelKey: string; currentVersionId: string | null; quoteReady: boolean
  type?: string; logisticsAttribute?: string; enabled?: boolean; archived?: boolean; updatedAt?: string
}
export type DiffKind = 'added' | 'price' | 'rule' | 'range' | 'removed' | 'unchanged'
export type DiffChange = { field: string; kind?: 'price' | 'rule' | 'range'; price?: boolean; before: unknown; after: unknown; delta?: number; percentChange?: number | null }
export type Diff = { key: string; type: DiffKind | string; kinds?: DiffKind[]; row: Price; previous?: Price; changes: DiffChange[] }
export type Version = { id: string; channelId: string; versionNumber: number; status: string; fileName: string; fingerprint: string; pricingReady?: boolean; quoteReady?: boolean; errors: number; rowCount?: number; countryCount?: number; rows?: Price[]; issues?: SourceIssue[]; diffRows?: Diff[]; summary: Record<string, number>; basePublishedVersionId?: string; batchId?: string; sourceFileIndex?: number; importedAt?: string; importedBy?: string; publishedAt?: string; publishedBy?: string; auditNote?: string }
export type Workspace = { dataset: Dataset; providers: Provider[]; channels: Channel[]; versions: Version[] }
export type BatchResult = { channelId?: string; channelName: string; providerName: string; versionId?: string; status: string; message?: string; errors?: number; pricingReady?: boolean; priceRows?: number; pendingReasons?: string[]; basePublishedVersionId?: string; summary?: Record<string, number>; issues?: SourceIssue[] }
export type Batch = { id: string; dataset_id: string; status: string; phase: string; created_at: string; payload: { progress: number; elapsedMs?: number; error?: string; totalFiles?: number; processedFiles?: number; currentFileIndex?: number; currentFileName?: string; totalChannels?: number; processedChannels?: number; currentChannelName?: string; files: Array<{ name: string; size?: number; sha256?: string; lifecycleStatus?: string; deletedAt?: string; deleteError?: string }>; fileReports?: Array<{ fileName: string; status: string; message?: string; retentionUntil?: string; sourceEvidence?: { sha256: string }; sheets?: Array<{ name: string; status: string; priceRows?: number; errors?: number; message?: string }> }>; results: BatchResult[] } }
export type BatchSummary = Pick<Batch, 'id' | 'status' | 'phase' | 'created_at'> & { progress: number; files: Array<{ name: string }> }
export type Mapping = { oldChannelId: string; oldName: string; newChannelId: string; status: string; candidates: Channel[] }
export type Cutover = { previewToken: string; sourceDatasetId: string; targetDatasetId: string; readyChannels: number; requiredReady: boolean; requiredConfirmed: boolean; requiredCount: number; requiredNotReady: Channel[]; unmappedChannels: number; mappings: Mapping[]; pendingChannels: Channel[]; draftsToReprice?: number; bindingChanges?: Array<{ kind: string; id: string; path: string; before: string; after: string; status: string }> }
export type PricePage = { items: Price[]; total: number; page: number; size: number; totalPages: number }
export type RequiredChannels = { revision: number; confirmed: boolean; note?: string; confirmedBy?: string; confirmedAt?: string; channelIds: string[]; channels: Array<Channel & { archived?: boolean; countries: string[]; zones: string[]; priceRows: number; pendingReasons: string[] }> }
export type BillingAcceptance = { versionId: string; fingerprint: string; engineVersion: string; pricePublished: boolean; quoteReady: boolean; unsupportedReasons: string[]; records: Array<{ kind: string; reviewed_by: string; reviewed_at: string; engine_version: string }> }
export type BatchPublishSelection = { channelId: string; versionId: string; removalConfirmed: boolean; reviewConfirmed: boolean }
export type BatchPublishResult = { providerId: string; count: number; published: Array<{ id: string; channelId: string; versionNumber: number; status: string; quoteReady: boolean }> }
export type ReadyPublishResult = { batchId: string; publishedCount: number; skippedCount: number; failedCount: number; published: Array<{ versionId: string; channelId: string; providerName: string; channelName: string; message: string }>; skipped: Array<{ versionId: string; channelName: string; reason: string }>; failed: Array<{ versionId: string; channelName: string; reason: string }> }
export type RowCorrection = { rowKey: string; fields: Partial<Pick<Price, 'weightFromKg' | 'weightToKg' | 'weightFromInclusive' | 'weightToInclusive' | 'pricePerKg' | 'registrationFee' | 'firstWeightKg' | 'firstWeightPrice' | 'nextWeightKg' | 'nextWeightPrice' | 'intervalPrice'>> }
export type LogisticsAdjustmentStatus = 'published' | 'pending'

export function logisticsAdjustmentStatus(channel: Pick<Channel, 'id' | 'currentVersionId'>, versions: Array<Pick<Version, 'channelId' | 'status'>>, hasPendingImport = false): LogisticsAdjustmentStatus {
  const hasDraft = versions.some(version => version.channelId === channel.id && version.status === 'draft')
  return channel.currentVersionId && !hasDraft && !hasPendingImport ? 'published' : 'pending'
}
export const LOGISTICS_MAX_FILES = 30
export const LOGISTICS_MAX_FILE_BYTES = 100 * 1024 * 1024
export const LOGISTICS_MAX_BATCH_BYTES = 500 * 1024 * 1024
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
  upload: (id: string, files: File[], replaceDrafts: boolean, key: string, progress?: (value: UploadProgress) => void) => {
    const form = new FormData(); files.forEach(file => form.append('files', file)); form.append('replaceDrafts', String(replaceDrafts))
    return uploadForm<Batch>(`${root}/datasets/${id}/imports`, form, progress, { 'Idempotency-Key': key })
  },
  retry: (id: string) => api.post<Batch>(`${root}/imports/${id}/retry`),
  version: (id: string) => api.get<Version>(`${root}/versions/${id}`),
  patchRows: (version: Version, changes: RowCorrection[]) => api.patch<Version>(`${root}/versions/${version.id}/rows`, { fingerprint: version.fingerprint, changes }),
  review: (version: Version, note: string, removalConfirmed: boolean, reviewConfirmed: boolean, key: string) => api.post<Version>(`${root}/channels/${version.channelId}/versions/${version.id}/review`, { note, removalConfirmed, reviewConfirmed }, key),
  publishProvider: (providerId: string, selections: BatchPublishSelection[], note: string, key: string) => api.post<BatchPublishResult>(`/logistics/providers/${providerId}/versions/publish-batch`, { selections, note }, key),
  publishReady: (batchId: string, selections: BatchPublishSelection[], note: string, key: string) => api.post<ReadyPublishResult>(`${root}/imports/${batchId}/publish-ready`, { selections, note }, key),
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

export function logisticsUploadError(files: ArrayLike<Pick<File, 'name' | 'size'>>) {
  const selected = Array.from(files)
  if (!selected.length || selected.length > LOGISTICS_MAX_FILES) return `每批请选择1至${LOGISTICS_MAX_FILES}个文件`
  if (selected.some(file => file.size <= 0 || !/\.xlsx?$/i.test(file.name))) return '只支持非空的 .xls / .xlsx 文件'
  if (selected.some(file => file.size > LOGISTICS_MAX_FILE_BYTES)) return '单个物流文件不能超过100MB'
  if (selected.reduce((total, file) => total + file.size, 0) > LOGISTICS_MAX_BATCH_BYTES) return '同一批次文件总大小不能超过500MB'
  return ''
}

export function formatTransferBytes(value: number) {
  if (!Number.isFinite(value) || value <= 0) return '0 B'
  if (value < 1024) return `${Math.round(value)} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`
  return `${(value / 1024 / 1024 / 1024).toFixed(2)} GB`
}

export function weightLabel(price: Price) {
  return `${price.weightFromInclusive ? '[' : '('}${Number((price.weightFromKg * 1000).toFixed(3))}, ${Number((price.weightToKg * 1000).toFixed(3))}${price.weightToInclusive === false ? ')' : ']'} g`
}
export function completedBatchStage(batch: Pick<Batch, 'status' | 'payload'>) {
  if (batch.status !== 'completed') return ''
  if (batch.payload.fileReports?.some(f => ['failed', 'template-pending'].includes(f.status))) return '存在文件解析失败或新模板待适配，请核对'
  if (batch.payload.results.some(r => r.status === 'blocked')) return '存在阻断，请核对'
  if (batch.payload.results.some(r => r.status === 'draft')) return '待审核'
  return '无需审核'
}

export function batchComparisonSummary(result: BatchResult) {
  const summary = result.summary || {}
  const added = Number(summary.added || 0), removed = Number(summary.removed || 0)
  const rows = Number(result.priceRows ?? added)
  if (result.basePublishedVersionId === '') return `初次导入 ${rows} 条价格；当前渠道没有旧价格版本可比较`
  if (added > 0 && added === removed && !Number(summary.price || 0) && !Number(summary.rule || 0) && !Number(summary.range || 0)) {
    return `新表 ${added} 条与旧表 ${removed} 条没有匹配上；请先核对国家、分区和重量档位，不要直接发布`
  }
  return `${rows} 条价格已解析；下方数字表示相对当前正式版本的变化`
}
const diffKindOrder: DiffKind[] = ['range', 'price', 'rule', 'added', 'removed', 'unchanged']
export function diffKinds(diff: Diff): DiffKind[] {
  const supplied = Array.isArray(diff.kinds) ? diff.kinds.filter(kind => diffKindOrder.includes(kind)) : []
  if (supplied.length) return diffKindOrder.filter(kind => supplied.includes(kind))
  return diffKindOrder.includes(diff.type as DiffKind) ? [diff.type as DiffKind] : []
}
export function aggregateChangeSummary(items: Array<{ summary?: Record<string, number> }>) {
  return items.reduce((total, item) => {
    for (const key of ['added', 'price', 'rule', 'range', 'removed'] as const) total[key] += Number(item.summary?.[key] || 0)
    total.coverageReduced += Number(item.summary?.coverageReduced || 0)
    return total
  }, { added: 0, price: 0, rule: 0, range: 0, removed: 0, coverageReduced: 0 })
}
export function rangeImpact(diff: Diff) {
  const before = diff.previous, after = diff.row
  if (!before) return '重量区间边界调整'
  const fromDelta = Number(after.weightFromKg) - Number(before.weightFromKg)
  const toDelta = Number(after.weightToKg) - Number(before.weightToKg)
  if (fromDelta <= 0 && toDelta >= 0 && (fromDelta < 0 || toDelta > 0)) return '覆盖范围扩大'
  if (fromDelta >= 0 && toDelta <= 0 && (fromDelta > 0 || toDelta < 0)) return '覆盖范围缩小'
  if (fromDelta > 0 && toDelta > 0) return '重量区间整体上移'
  if (fromDelta < 0 && toDelta < 0) return '重量区间整体下移'
  return '重量区间边界调整'
}
export function changeImpact(change: DiffChange, currency = 'CNY') {
  if (change.kind !== 'price' && !change.price) return change.kind === 'range' ? '重量边界变化' : '需复核计费规则'
  if (typeof change.delta !== 'number' || !Number.isFinite(change.delta)) return '价格已变化'
  const amount = `${change.delta > 0 ? '+' : ''}${change.delta.toFixed(2)}`
  const percent = typeof change.percentChange === 'number' && Number.isFinite(change.percentChange) ? ` · ${change.percentChange > 0 ? '+' : ''}${change.percentChange.toFixed(2)}%` : ''
  return `${currency} ${amount}${percent}`
}
export function money(value: unknown) { return typeof value === 'number' && Number.isFinite(value) ? value.toFixed(2) : '—' }
export function shown(value: unknown) { return value == null ? '—' : typeof value === 'object' ? JSON.stringify(value) : String(value) }
