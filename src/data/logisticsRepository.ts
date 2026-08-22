import { logisticsRules as runtimeRules, replaceLogisticsRules, type LogisticsPriceRow, type LogisticsRule } from './logistics'
import type { LogisticsDiffRow, LogisticsDiffSummary, LogisticsImportIssue, LogisticsImportPreview, LogisticsRateRow } from './logisticsWorkbook'
import { api, idempotencyKey } from '@/services/http'

export type LogisticsProviderRecord = { id: string; name: string; code: string; enabled: boolean; createdAt: string; updatedAt: string }
export type LogisticsChannelRecord = {
  id: string; ruleId: number; providerId: string; name: string; code: string; type: string; logisticsAttribute: string
  enabled: boolean; currentVersionId: string; createdAt: string; updatedAt: string
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

function toPriceRow(row: LogisticsRateRow): LogisticsPriceRow {
  return {
    areaName: row.areaName, countryCode: row.countryCode, etaMinDays: row.etaMinDays, etaMaxDays: row.etaMaxDays,
    prohibitedMarks: row.prohibitedMarks, allowedMarks: row.allowedMarks, maxPerimeterCm: row.maxPerimeterCm, maxSideCm: row.maxSideCm,
    volumeDivisor: row.volumeDivisor, weightFromKg: row.weightFromKg, weightToKg: row.weightToKg, startWeightKg: row.startWeightKg,
    pricePerKg: row.pricePerKg, minChargeWeightKg: row.minChargeWeightKg, firstWeightKg: row.firstWeightKg, firstWeightPrice: row.firstWeightPrice,
    nextWeightKg: row.nextWeightKg, nextWeightPrice: row.nextWeightPrice, intervalPrice: row.intervalPrice, registrationFee: row.registrationFee,
    surcharge: row.surcharge, fuelSurchargeRate: row.fuelSurchargeRate, prohibitGeneralCargo: row.prohibitGeneralCargo, volumetric: row.volumetric,
    phoneRequired: row.phoneRequired, zoneName: row.zoneName, zoneExclude: row.zoneExclude,
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
  return api.post<LogisticsProviderRecord>('/logistics/providers', { name: name.trim(), code: code.trim().toUpperCase(), enabled: true })
}

export async function addLogisticsChannel(input: { providerId: string; name: string; code: string; type: string; logisticsAttribute: string }) {
  return api.post<LogisticsChannelRecord>('/logistics/channels', input)
}

export async function createLogisticsDraft(channelId: string, preview: LogisticsImportPreview, file: File, actor = '物流负责人') {
  return api.post<LogisticsChannelVersionRecord>(`/logistics/channels/${channelId}/versions`, {
    fileName: file.name, sourceHash: preview.sourceHash, rows: preview.rows, issues: preview.issues,
    diffRows: preview.diffRows, summary: preview.summary, importedBy: actor, publishedBy: '', auditNote: '',
  }, idempotencyKey('logistics-import'))
}

export async function publishLogisticsVersion(channelId: string, versionId: string, note: string) {
  await api.post(`/logistics/channels/${channelId}/versions/${versionId}/publish`, { note }, idempotencyKey('logistics-publish'))
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
    const prices = version.rows.map(toPriceRow)
    return [{ id: channel.ruleId, name: channel.name, englishName: channel.code.toLowerCase(), type: channel.type, currency: 'CNY', published: '发布', status: '启用', dates: `${channel.createdAt}|${channel.updatedAt}`, users: `${version.importedBy}|${version.publishedBy}`, relations: [{ carrier: provider.name, channel: channel.name, channelCode: channel.code, discounts: '-\n-' }], phoneRequired: prices.some(row => row.phoneRequired), areaCount: new Set(prices.map(row => row.countryCode || row.areaName)).size, priceRowCount: prices.length, prices }]
  })
  if (rules.length) replaceLogisticsRules(rules)
  return rules.length ? rules : runtimeRules
}

export async function initializeLogisticsRepository() {
  try { await refreshPublishedLogisticsRules() } catch (error) { console.warn('报价物流服务初始化失败，暂时保留内置只读规则', error) }
}
