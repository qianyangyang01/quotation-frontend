import { type LogisticsPriceRow, type LogisticsRule } from './logistics'
import type { LogisticsDiffRow, LogisticsDiffSummary, LogisticsImportIssue, LogisticsImportPreview, LogisticsRateRow } from './logisticsWorkbook'
import { api, idempotencyKey, request } from '@/services/http'
import { invalidatePublishedLogisticsCache } from './publishedLogisticsRepository'

export type LogisticsProviderRecord = { id: string; name: string; code: string; enabled: boolean; createdAt: string; updatedAt: string; _version: number }
export type LogisticsChannelRecord = {
  id: string; ruleId: number; providerId: string; name: string; code: string; type: string; logisticsAttribute: string
  enabled: boolean; currentVersionId: string; createdAt: string; updatedAt: string; _version: number
  archived: boolean; archivedAt: string; archivedBy: string; archiveReason: string
}
export type LogisticsVersionStatus = 'draft' | 'published' | 'superseded' | 'rejected'
export type LogisticsChannelVersionRecord = {
  id: string; channelId: string; versionNumber: number; status: LogisticsVersionStatus; fileName: string; sourceHash: string
  originalFile: Blob | null; rows: LogisticsRateRow[]; issues: LogisticsImportIssue[]; diffRows: LogisticsDiffRow[]; summary: LogisticsDiffSummary
  importedAt: string; importedBy: string; publishedAt: string; publishedBy: string; auditNote: string; rollbackFromVersionId?: string
  rowCount: number; issueCount: number; diffCount: number; countryCount: number; errors: number; warnings: number
}
export type LogisticsAuditRecord = { id: string; channelId: string; versionId: string; action: 'import' | 'publish' | 'rollback'; actor: string; note: string; createdAt: string }
export type LogisticsWorkspaceState = { providers: LogisticsProviderRecord[]; channels: LogisticsChannelRecord[]; versions: LogisticsChannelVersionRecord[]; audits: LogisticsAuditRecord[] }
export type LogisticsBatchPreviewItem = LogisticsImportPreview & { fileIndex: number; fileKey: string; action: 'match' | 'create' | 'restore' | 'blocked'; matchType?: 'code' | 'name' | 'new'; channelId: string; channelName: string; channelCode: string; providerId: string; providerName?: string; providerMatchStatus: 'matched' | 'unmatched' | 'ambiguous' | 'disabled'; providerMatchMessage?: string; hasDraft: boolean; archived?: boolean }
export type LogisticsBatchPreview = { providerId?: string; count: number; blocking: number; selectable: number; replaceDrafts: number; items: LogisticsBatchPreviewItem[] }
export type LogisticsBatchImportResult = { providerId: string; count: number; items: Array<{ fileName: string; channelId: string; channelName: string; versionId: string; versionNumber: number; action: 'created' | 'updated' | 'duplicate' }> }
export const LOGISTICS_PUBLISHED_EVENT = 'milano:logistics-published'

type PageResult<T> = { items: T[]; page: number; size: number; total: number; totalPages: number }
const WORKSPACE_CACHE_MS = 5_000
let workspaceCache: { value: LogisticsWorkspaceState; expiresAt: number } | null = null
let workspaceRequest: Promise<LogisticsWorkspaceState> | null = null

function numberOrZero(value: unknown) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function countOrFallback(value: unknown, fallback: number) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? Math.max(0, parsed) : fallback
}

function normalizeLogisticsVersion(version: LogisticsChannelVersionRecord): LogisticsChannelVersionRecord {
  const summary = version.summary || {} as Partial<LogisticsDiffSummary>
  const issues = Array.isArray(version.issues) ? version.issues : []
  return {
    ...version,
    fileName: String(version.fileName || ''),
    sourceHash: String(version.sourceHash || ''),
    originalFile: null,
    rows: Array.isArray(version.rows) ? version.rows : [],
    issues,
    diffRows: Array.isArray(version.diffRows) ? version.diffRows : [],
    summary: {
      added: numberOrZero(summary.added),
      price: numberOrZero(summary.price),
      rule: numberOrZero(summary.rule),
      removed: numberOrZero(summary.removed),
      unchanged: numberOrZero(summary.unchanged),
      highRisk: numberOrZero(summary.highRisk),
    },
    importedAt: String(version.importedAt || ''),
    importedBy: String(version.importedBy || ''),
    publishedAt: String(version.publishedAt || ''),
    publishedBy: String(version.publishedBy || ''),
    auditNote: String(version.auditNote || ''),
    rowCount: numberOrZero(version.rowCount || version.rows?.length),
    issueCount: numberOrZero(version.issueCount || version.issues?.length),
    diffCount: numberOrZero(version.diffCount || version.diffRows?.length),
    countryCount: numberOrZero(version.countryCount),
    errors: countOrFallback(version.errors, issues.filter(issue => issue.level === 'error').length),
    warnings: countOrFallback(version.warnings, issues.filter(issue => issue.level === 'warning').length),
  }
}

export function normalizeLogisticsPriceRow(row: Partial<LogisticsRateRow>): LogisticsPriceRow {
  return {
    pricingModel: row.pricingModel, weightFromInclusive: row.weightFromInclusive, weightToInclusive: row.weightToInclusive, quoteReady: row.quoteReady,
    areaName: String(row.areaName || ''), countryCode: String(row.countryCode || ''), etaMinDays: numberOrZero(row.etaMinDays), etaMaxDays: numberOrZero(row.etaMaxDays),
    prohibitedMarks: String(row.prohibitedMarks || ''), allowedMarks: String(row.allowedMarks || ''), maxPerimeterCm: numberOrZero(row.maxPerimeterCm), maxSideCm: numberOrZero(row.maxSideCm),
    volumeDivisor: numberOrZero(row.volumeDivisor), weightFromKg: numberOrZero(row.weightFromKg), weightToKg: numberOrZero(row.weightToKg), startWeightKg: numberOrZero(row.startWeightKg),
    pricePerKg: numberOrZero(row.pricePerKg), minChargeWeightKg: numberOrZero(row.minChargeWeightKg), firstWeightKg: numberOrZero(row.firstWeightKg), firstWeightPrice: numberOrZero(row.firstWeightPrice),
    nextWeightKg: numberOrZero(row.nextWeightKg), nextWeightPrice: numberOrZero(row.nextWeightPrice), intervalPrice: numberOrZero(row.intervalPrice), registrationFee: numberOrZero(row.registrationFee),
    surcharge: numberOrZero(row.surcharge), fuelSurchargeRate: numberOrZero(row.fuelSurchargeRate), prohibitGeneralCargo: row.prohibitGeneralCargo === true, volumetric: row.volumetric !== false,
    phoneRequired: row.phoneRequired === true, zoneName: String(row.zoneName || ''), zoneExclude: row.zoneExclude === true,
  }
}

export function invalidateLogisticsWorkspaceCache() { workspaceCache = null }

async function loadAllPages<T>(path: string) {
  const items: T[] = []
  let page = 0
  let totalPages = 1
  while (page < totalPages) {
    const separator = path.includes('?') ? '&' : '?'
    const result = await api.get<PageResult<T>>(`${path}${separator}page=${page}&size=200`)
    items.push(...result.items)
    totalPages = result.totalPages
    page += 1
  }
  return items
}

export async function loadLogisticsWorkspace(): Promise<LogisticsWorkspaceState> {
  if (workspaceCache && workspaceCache.expiresAt > Date.now()) return workspaceCache.value
  if (workspaceRequest) return workspaceRequest
  workspaceRequest = Promise.all([
    loadAllPages<LogisticsProviderRecord>('/logistics/providers'),
    Promise.all([loadAllPages<LogisticsChannelRecord>('/logistics/channels?archived=false'), loadAllPages<LogisticsChannelRecord>('/logistics/channels?archived=true')]).then(([active, archived]) => [...active, ...archived]),
    loadAllPages<LogisticsChannelVersionRecord>('/logistics/versions'),
  ]).then(([providers, channels, versions]) => {
    const uniqueChannels = [...new Map(channels.map(channel => [channel.id, channel])).values()]
    const normalized = {
      providers: [...providers].sort((a, b) => a.name.localeCompare(b.name, 'zh-CN')),
      channels: uniqueChannels.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN')),
      versions: [...versions].map(normalizeLogisticsVersion).sort((a, b) => b.versionNumber - a.versionNumber || b.importedAt.localeCompare(a.importedAt)),
      audits: [],
    }
    workspaceCache = { value: normalized, expiresAt: Date.now() + WORKSPACE_CACHE_MS }
    return normalized
  }).finally(() => { workspaceRequest = null })
  return workspaceRequest
}

async function mutation<T>(request: Promise<T>) { const result = await request; invalidateLogisticsWorkspaceCache(); return result }

export async function addLogisticsProvider(name: string, code: string) {
  return mutation(api.post<LogisticsProviderRecord>('/logistics/providers', { name: name.trim(), code: code.trim().toUpperCase(), enabled: true }, idempotencyKey('logistics-provider')))
}

export function initialLogisticsBatchSelection(preview: LogisticsBatchPreview) { return preview.items.filter(item => item.errors === 0).map(item => item.fileKey) }
export function logisticsBatchSelectionSummary(preview: LogisticsBatchPreview, selectedKeys: string[]) {
  const selected = new Set(selectedKeys); const items = preview.items.filter(item => selected.has(item.fileKey) && item.errors === 0)
  return { selectable: items.length, blocked: preview.items.filter(item => item.errors > 0).length, replaceDrafts: items.filter(item => item.hasDraft).length }
}

export function logisticsVersionHasBlockingErrors(version: LogisticsChannelVersionRecord) { return version.errors > 0 }
export function logisticsVersionNeedsRiskReview(version: LogisticsChannelVersionRecord) { return version.summary.highRisk > 0 || version.summary.removed > 0 }
export function initialLogisticsVersionPublishSelection(versions: LogisticsChannelVersionRecord[]) { return versions.filter(version => !logisticsVersionHasBlockingErrors(version)).map(version => version.id) }
export function filterPublishableLogisticsVersionIds(versions: LogisticsChannelVersionRecord[], selectedIds: string[]) {
  const selected = new Set(selectedIds)
  return versions.filter(version => selected.has(version.id) && !logisticsVersionHasBlockingErrors(version)).map(version => version.id)
}

export async function setLogisticsProviderStatus(provider: LogisticsProviderRecord, enabled: boolean) {
  const result = await mutation(api.patch<LogisticsProviderRecord>(`/logistics/providers/${provider.id}/status`, { enabled }, { 'If-Match': String(provider._version) }))
  await invalidatePublishedLogisticsCache()
  return result
}

export async function deleteLogisticsProvider(providerId: string) { return mutation(api.delete<void>(`/logistics/providers/${providerId}`)) }

export async function addLogisticsChannel(input: { providerId: string; name: string; code: string; type: string; logisticsAttribute: string }) {
  return mutation(api.post<LogisticsChannelRecord>('/logistics/channels', input, idempotencyKey('logistics-channel')))
}

export async function updateLogisticsChannel(channel: LogisticsChannelRecord, input: { name: string; code: string; type: string; logisticsAttribute: string; enabled: boolean }) {
  const result = await mutation(api.put<LogisticsChannelRecord>(`/logistics/channels/${channel.id}`, input, { 'If-Match': String(channel._version) }))
  if (channel.currentVersionId) await invalidatePublishedLogisticsCache()
  return result
}

export async function setLogisticsChannelStatus(channel: LogisticsChannelRecord, enabled: boolean) {
  const result = await mutation(api.patch<LogisticsChannelRecord>(`/logistics/channels/${channel.id}/status`, { enabled }, { 'If-Match': String(channel._version) }))
  if (channel.currentVersionId) await invalidatePublishedLogisticsCache()
  return result
}

export async function cloneLogisticsChannel(channel: LogisticsChannelRecord, name: string, code: string) {
  return mutation(api.post<LogisticsChannelRecord>(`/logistics/channels/${channel.id}/clone`, { name, code }, idempotencyKey('logistics-clone')))
}

export async function deleteLogisticsChannel(channelId: string) { return mutation(api.delete<void>(`/logistics/channels/${channelId}`)) }

export async function archiveLogisticsChannel(channel: LogisticsChannelRecord, reason: string) {
  const result = await mutation(request<LogisticsChannelRecord>(`/logistics/channels/${channel.id}/archive`, { method: 'POST', body: JSON.stringify({ reason }), headers: { 'If-Match': String(channel._version), 'Idempotency-Key': idempotencyKey('logistics-channel-archive') } }))
  await invalidatePublishedLogisticsCache(); window.dispatchEvent(new CustomEvent(LOGISTICS_PUBLISHED_EVENT)); return result
}

export async function saveLogisticsManualDraft(channelId: string, rows: LogisticsPriceRow[], note: string) {
  return mutation(api.put<LogisticsChannelVersionRecord>(`/logistics/channels/${channelId}/manual-draft`, { fileName: '手工维护区域规则', rows, note }, { 'Idempotency-Key': idempotencyKey('logistics-manual-draft') }))
}

export async function createLogisticsDraft(channelId: string, preview: LogisticsImportPreview, file: File, actor = '物流负责人', replaceDraft = false) {
  void preview; void actor
  const form = new FormData(); form.append('file', file)
  return mutation(api.post<LogisticsChannelVersionRecord>(`/logistics/channels/${channelId}/imports?replaceDraft=${replaceDraft}`, form, idempotencyKey('logistics-import')))
}

function providerFilesForm(files: File[]) { const form = new FormData(); files.forEach(file => form.append('files', file)); return form }

export async function previewLogisticsProviderImports(providerId: string, files: File[]) {
  return api.post<LogisticsBatchPreview>(`/logistics/providers/${providerId}/imports/preview`, providerFilesForm(files))
}

export async function importLogisticsProviderFiles(providerId: string, files: File[], replaceDrafts = false) {
  return mutation(api.post<LogisticsBatchImportResult>(`/logistics/providers/${providerId}/imports?replaceDrafts=${replaceDrafts}`, providerFilesForm(files), idempotencyKey('logistics-provider-import')))
}

export async function previewLogisticsGlobalImports(files: File[]) { return api.post<LogisticsBatchPreview>('/logistics/imports/preview', providerFilesForm(files)) }
export async function importLogisticsGlobalFiles(files: File[], replaceDrafts = false) { return mutation(api.post<LogisticsBatchImportResult>(`/logistics/imports?replaceDrafts=${replaceDrafts}`, providerFilesForm(files), idempotencyKey('logistics-global-import'))) }

export async function publishLogisticsProviderVersions(providerId: string, selections: Array<{ channelId: string; versionId: string; removalConfirmed: boolean; reviewConfirmed: boolean }>, note: string) {
  const result = await api.post<{ providerId: string; count: number; published: Array<{ id: string; channelId: string; versionNumber: number; status: string; quoteReady: boolean }> }>(`/logistics/providers/${providerId}/versions/publish-batch`, { selections, note }, idempotencyKey('logistics-provider-publish'))
  invalidateLogisticsWorkspaceCache(); await invalidatePublishedLogisticsCache(); window.dispatchEvent(new CustomEvent(LOGISTICS_PUBLISHED_EVENT)); return result
}

export async function rejectLogisticsVersion(channelId: string, versionId: string, note: string) {
  return mutation(api.post<LogisticsChannelVersionRecord>(`/logistics/channels/${channelId}/versions/${versionId}/reject`, { note }, idempotencyKey('logistics-reject')))
}

export async function publishLogisticsVersion(channelId: string, versionId: string, note: string, removalConfirmed = false) {
  await api.post(`/logistics/channels/${channelId}/versions/${versionId}/publish`, { note, removalConfirmed }, idempotencyKey('logistics-publish'))
  invalidateLogisticsWorkspaceCache()
  await invalidatePublishedLogisticsCache(); window.dispatchEvent(new CustomEvent(LOGISTICS_PUBLISHED_EVENT))
}

export async function rollbackLogisticsVersion(channelId: string, targetVersionId: string, note: string) {
  const result = await api.post<LogisticsChannelVersionRecord>(`/logistics/channels/${channelId}/versions/${targetVersionId}/rollback`, { note }, idempotencyKey('logistics-rollback'))
  invalidateLogisticsWorkspaceCache()
  await invalidatePublishedLogisticsCache(); window.dispatchEvent(new CustomEvent(LOGISTICS_PUBLISHED_EVENT)); return result
}

export function versionRows(state: LogisticsWorkspaceState, channel: LogisticsChannelRecord) { return state.versions.find(version => version.id === channel.currentVersionId)?.rows || [] }

async function loadVersionArray<T>(versionId: string, field: 'rows' | 'issues' | 'diff') {
  return loadAllPages<T>(`/logistics/versions/${versionId}/${field}`)
}

export async function loadLogisticsVersionDetail(version: LogisticsChannelVersionRecord) {
  const [rows, issues, diffRows] = await Promise.all([
    loadVersionArray<LogisticsRateRow>(version.id, 'rows'),
    loadVersionArray<LogisticsImportIssue>(version.id, 'issues'),
    loadVersionArray<LogisticsDiffRow>(version.id, 'diff'),
  ])
  return normalizeLogisticsVersion({ ...version, rows, issues, diffRows })
}

export async function loadCurrentVersionRows(state: LogisticsWorkspaceState, channel: LogisticsChannelRecord) {
  const version = state.versions.find(item => item.id === channel.currentVersionId)
  if (!version) return []
  if (version.rows.length || version.rowCount === 0) return version.rows
  const rows = await loadVersionArray<LogisticsRateRow>(version.id, 'rows')
  version.rows = rows
  return rows
}

export function workspaceLogisticsRules(state: LogisticsWorkspaceState): LogisticsRule[] {
  const providers = new Map(state.providers.map(provider => [provider.id, provider]))
  return state.channels.filter(channel => !channel.archived && Boolean(channel.currentVersionId)).map(channel => {
    const provider = providers.get(channel.providerId)
    const version = state.versions.find(item => item.id === channel.currentVersionId && item.status === 'published')
    if (!version) return null
    return {
      id: channel.ruleId, name: channel.name, englishName: '', type: channel.type, currency: 'CNY',
      published: `V${version.versionNumber}`, status: channel.enabled && provider?.enabled ? '启用' : '禁用',
      dates: `${channel.createdAt}|${channel.updatedAt}`, users: `${version?.importedBy || ''}|${version?.publishedBy || ''}`,
      relations: [{ carrier: provider?.name || '', channel: channel.name, channelCode: channel.code, discounts: '-\n-' }],
      phoneRequired: false, areaCount: version?.countryCount || 0, priceRowCount: version?.rowCount || 0,
      prices: version?.rows.map(normalizeLogisticsPriceRow) || [],
    }
  }).filter((rule): rule is LogisticsRule => rule !== null)
}
