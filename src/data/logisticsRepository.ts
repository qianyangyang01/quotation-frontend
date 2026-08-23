import { replaceLogisticsRules, type LogisticsPriceRow, type LogisticsRule } from './logistics'
import type { LogisticsDiffRow, LogisticsDiffSummary, LogisticsImportIssue, LogisticsImportPreview, LogisticsRateRow } from './logisticsWorkbook'
import { api, idempotencyKey } from '@/services/http'

export type LogisticsProviderRecord = { id: string; name: string; code: string; enabled: boolean; createdAt: string; updatedAt: string }
export type LogisticsChannelRecord = {
  id: string; ruleId: number; providerId: string; name: string; code: string; type: string; logisticsAttribute: string
  enabled: boolean; currentVersionId: string; createdAt: string; updatedAt: string; _version: number
}
export type LogisticsVersionStatus = 'draft' | 'published' | 'superseded' | 'rejected'
export type LogisticsChannelVersionRecord = {
  id: string; channelId: string; versionNumber: number; status: LogisticsVersionStatus; fileName: string; sourceHash: string
  originalFile: Blob | null; rows: LogisticsRateRow[]; issues: LogisticsImportIssue[]; diffRows: LogisticsDiffRow[]; summary: LogisticsDiffSummary
  importedAt: string; importedBy: string; publishedAt: string; publishedBy: string; auditNote: string; rollbackFromVersionId?: string
}
export type LogisticsAuditRecord = { id: string; channelId: string; versionId: string; action: 'import' | 'publish' | 'rollback'; actor: string; note: string; createdAt: string }
export type LogisticsWorkspaceState = { providers: LogisticsProviderRecord[]; channels: LogisticsChannelRecord[]; versions: LogisticsChannelVersionRecord[]; audits: LogisticsAuditRecord[] }
export const LOGISTICS_PUBLISHED_EVENT = 'milano:logistics-published'

type RemoteWorkspace = Omit<LogisticsWorkspaceState, 'audits'> & { audits?: LogisticsAuditRecord[] }

function numberOrZero(value: unknown) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

export function normalizeLogisticsPriceRow(row: Partial<LogisticsRateRow>): LogisticsPriceRow {
  return {
    areaName: String(row.areaName || ''), countryCode: String(row.countryCode || ''), etaMinDays: numberOrZero(row.etaMinDays), etaMaxDays: numberOrZero(row.etaMaxDays),
    prohibitedMarks: String(row.prohibitedMarks || ''), allowedMarks: String(row.allowedMarks || ''), maxPerimeterCm: numberOrZero(row.maxPerimeterCm), maxSideCm: numberOrZero(row.maxSideCm),
    volumeDivisor: numberOrZero(row.volumeDivisor), weightFromKg: numberOrZero(row.weightFromKg), weightToKg: numberOrZero(row.weightToKg), startWeightKg: numberOrZero(row.startWeightKg),
    pricePerKg: numberOrZero(row.pricePerKg), minChargeWeightKg: numberOrZero(row.minChargeWeightKg), firstWeightKg: numberOrZero(row.firstWeightKg), firstWeightPrice: numberOrZero(row.firstWeightPrice),
    nextWeightKg: numberOrZero(row.nextWeightKg), nextWeightPrice: numberOrZero(row.nextWeightPrice), intervalPrice: numberOrZero(row.intervalPrice), registrationFee: numberOrZero(row.registrationFee),
    surcharge: numberOrZero(row.surcharge), fuelSurchargeRate: numberOrZero(row.fuelSurchargeRate), prohibitGeneralCargo: row.prohibitGeneralCargo === true, volumetric: row.volumetric === true,
    phoneRequired: row.phoneRequired === true, zoneName: String(row.zoneName || ''), zoneExclude: row.zoneExclude === true,
  }
}

export async function loadLogisticsWorkspace(): Promise<LogisticsWorkspaceState> {
  const state = await api.get<RemoteWorkspace>('/logistics/workspace')
  return {
    providers: [...state.providers].sort((a, b) => a.name.localeCompare(b.name, 'zh-CN')),
    channels: [...state.channels].sort((a, b) => a.name.localeCompare(b.name, 'zh-CN')),
    versions: [...state.versions].map(version => ({ ...version, originalFile: null })).sort((a, b) => b.versionNumber - a.versionNumber || b.importedAt.localeCompare(a.importedAt)),
    audits: [...(state.audits || [])].sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
  }
}

export async function addLogisticsProvider(name: string, code: string) {
  return api.post<LogisticsProviderRecord>('/logistics/providers', { name: name.trim(), code: code.trim().toUpperCase(), enabled: true }, idempotencyKey('logistics-provider'))
}

export async function addLogisticsChannel(input: { providerId: string; name: string; code: string; type: string; logisticsAttribute: string }) {
  return api.post<LogisticsChannelRecord>('/logistics/channels', input, idempotencyKey('logistics-channel'))
}

export async function updateLogisticsChannel(channel: LogisticsChannelRecord, input: { name: string; code: string; type: string; logisticsAttribute: string; enabled: boolean }) {
  return api.put<LogisticsChannelRecord>(`/logistics/channels/${channel.id}`, input, { 'If-Match': String(channel._version) })
}

export async function setLogisticsChannelStatus(channel: LogisticsChannelRecord, enabled: boolean) {
  return api.patch<LogisticsChannelRecord>(`/logistics/channels/${channel.id}/status`, { enabled }, { 'If-Match': String(channel._version) })
}

export async function cloneLogisticsChannel(channel: LogisticsChannelRecord, name: string, code: string) {
  return api.post<LogisticsChannelRecord>(`/logistics/channels/${channel.id}/clone`, { name, code }, idempotencyKey('logistics-clone'))
}

export async function deleteLogisticsChannel(channelId: string) { return api.delete<void>(`/logistics/channels/${channelId}`) }

export async function saveLogisticsManualDraft(channelId: string, rows: LogisticsPriceRow[], note: string) {
  return api.put<LogisticsChannelVersionRecord>(`/logistics/channels/${channelId}/manual-draft`, { fileName: '手工维护区域规则', rows, note }, { 'Idempotency-Key': idempotencyKey('logistics-manual-draft') })
}

export async function createLogisticsDraft(channelId: string, preview: LogisticsImportPreview, file: File, actor = '物流负责人') {
  void preview; void actor
  const form = new FormData(); form.append('file', file)
  return api.post<LogisticsChannelVersionRecord>(`/logistics/channels/${channelId}/imports`, form, idempotencyKey('logistics-import'))
}

export async function publishLogisticsVersion(channelId: string, versionId: string, note: string, removalConfirmed = false) {
  await api.post(`/logistics/channels/${channelId}/versions/${versionId}/publish`, { note, removalConfirmed }, idempotencyKey('logistics-publish'))
  await refreshPublishedLogisticsRules(); window.dispatchEvent(new CustomEvent(LOGISTICS_PUBLISHED_EVENT))
}

export async function rollbackLogisticsVersion(channelId: string, targetVersionId: string, note: string) {
  const result = await api.post<LogisticsChannelVersionRecord>(`/logistics/channels/${channelId}/versions/${targetVersionId}/rollback`, { note }, idempotencyKey('logistics-rollback'))
  await refreshPublishedLogisticsRules(); window.dispatchEvent(new CustomEvent(LOGISTICS_PUBLISHED_EVENT)); return result
}

export function versionRows(state: LogisticsWorkspaceState, channel: LogisticsChannelRecord) { return state.versions.find(version => version.id === channel.currentVersionId)?.rows || [] }

export async function refreshPublishedLogisticsRules() {
  const state = await loadLogisticsWorkspace(); const providers = new Map(state.providers.map(provider => [provider.id, provider])); const versions = new Map(state.versions.map(version => [version.id, version]))
  const rules: LogisticsRule[] = state.channels.filter(channel => channel.enabled && channel.currentVersionId).flatMap(channel => {
    const provider = providers.get(channel.providerId); const version = versions.get(channel.currentVersionId)
    if (!provider?.enabled || !version || version.status !== 'published') return []
    const prices = version.rows.map(normalizeLogisticsPriceRow)
    return [{ id: channel.ruleId, name: channel.name, englishName: channel.code.toLowerCase(), type: channel.type, currency: 'CNY', published: '发布', status: '启用', dates: `${channel.createdAt}|${channel.updatedAt}`, users: `${version.importedBy}|${version.publishedBy}`, relations: [{ carrier: provider.name, channel: channel.name, channelCode: channel.code, discounts: '-\n-' }], phoneRequired: prices.some(row => row.phoneRequired), areaCount: new Set(prices.map(row => row.countryCode || row.areaName)).size, priceRowCount: prices.length, prices }]
  })
  replaceLogisticsRules(rules)
  return rules
}

export async function initializeLogisticsRepository() {
  try { await refreshPublishedLogisticsRules() } catch (error) {
    replaceLogisticsRules([])
    console.warn('报价物流服务初始化失败，已停用本地规则并等待正式发布版本', error)
  }
}
