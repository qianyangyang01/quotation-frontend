import { logisticsRules as runtimeRules, replaceLogisticsRules, type LogisticsPriceRow, type LogisticsRule } from './logistics'
import type { LogisticsDiffRow, LogisticsDiffSummary, LogisticsImportIssue, LogisticsImportPreview, LogisticsRateRow } from './logisticsWorkbook'

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

const DB_NAME = 'milano-logistics'
const DB_VERSION = 1
const STORES = { providers: 'providers', channels: 'channels', versions: 'versions', audits: 'audits' } as const
export const LOGISTICS_PUBLISHED_EVENT = 'milano:logistics-published'
let databasePromise: Promise<IDBDatabase> | null = null

function openDatabase() {
  if (typeof indexedDB === 'undefined') return Promise.reject(new Error('当前浏览器不支持 IndexedDB'))
  if (databasePromise) return databasePromise
  databasePromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => Object.values(STORES).forEach(name => {
      if (!request.result.objectStoreNames.contains(name)) request.result.createObjectStore(name, { keyPath: 'id' })
    })
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error || new Error('物流数据库打开失败'))
  })
  return databasePromise
}
function requestResult<T>(request: IDBRequest<T>) {
  return new Promise<T>((resolve, reject) => { request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error || new Error('物流数据库操作失败')) })
}
function transactionDone(transaction: IDBTransaction) {
  return new Promise<void>((resolve, reject) => { transaction.oncomplete = () => resolve(); transaction.onerror = () => reject(transaction.error || new Error('物流数据库保存失败')); transaction.onabort = () => reject(transaction.error || new Error('物流数据库操作已取消')) })
}
function id(prefix: string) { return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 9)}` }
function now() { return new Date().toLocaleString('zh-CN', { hour12: false }) }
function providerCode(name: string, index: number) {
  const known: Record<string, string> = { '顺友': 'SHUNYOU', '捷易通达': 'JIEYITONGDA', '容鼎供应链': 'RONGDING', '浙江闪电猴': 'SHANDIANHOU', '万邦速达-专线': 'WANBANG', '云途物流': 'YUNTU', '燕文物流': 'YANWEN', '顺丰国际出口电商': 'SF', '杭州花海供应链': 'HUAHAI', '4PX（新版）': '4PX' }
  return known[name] || `PROVIDER${index + 1}`
}
function rateRowKey(row: LogisticsPriceRow) {
  return [row.countryCode, row.areaName, row.zoneName, '', '', '', '', row.weightFromKg, row.weightToKg].map(value => String(value || '').trim().toLowerCase()).join('|')
}
function legacyRateRow(row: LogisticsPriceRow, sourceRow: number): LogisticsRateRow {
  return {
    sourceRow, areaName: row.areaName, countryCode: row.countryCode, etaMinDays: row.etaMinDays, etaMaxDays: row.etaMaxDays,
    prohibitedMarks: row.prohibitedMarks, allowedMarks: row.allowedMarks, maxPerimeterCm: row.maxPerimeterCm, maxSideCm: row.maxSideCm,
    volumeDivisor: row.volumeDivisor, minLengthCm: 0, maxLengthCm: 0, minWidthCm: 0, maxWidthCm: 0, minSideAreaCm2: 0, maxSideAreaCm2: 0,
    weightFromKg: row.weightFromKg, weightToKg: row.weightToKg, startWeightKg: row.startWeightKg, pricePerKg: row.pricePerKg,
    minChargeWeightKg: row.minChargeWeightKg, firstWeightKg: row.firstWeightKg, firstWeightPrice: row.firstWeightPrice,
    nextWeightKg: row.nextWeightKg, nextWeightPrice: row.nextWeightPrice, intervalPrice: row.intervalPrice, registrationFee: row.registrationFee,
    surcharge: row.surcharge, fuelSurchargeRate: row.fuelSurchargeRate, specialGoodsContent: '', volumetric: row.volumetric,
    prohibitGeneralCargo: row.prohibitGeneralCargo, phoneRequired: row.phoneRequired, zoneName: row.zoneName, zonePostalPrefix: '', zonePostalCode: '',
    zoneCity: '', zoneState: '', zoneExclude: row.zoneExclude, rowKey: rateRowKey(row),
  }
}
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
async function all<T>(storeName: string) {
  const database = await openDatabase()
  return requestResult(database.transaction(storeName, 'readonly').objectStore(storeName).getAll()) as Promise<T[]>
}
async function put<T>(storeName: string, value: T) {
  const database = await openDatabase(); const transaction = database.transaction(storeName, 'readwrite'); transaction.objectStore(storeName).put(value); await transactionDone(transaction)
}
async function seedLegacyRules() {
  if ((await all<LogisticsChannelRecord>(STORES.channels)).length) return
  const createdAt = now()
  const providerNames = [...new Set(runtimeRules.flatMap(rule => rule.relations.map(relation => relation.carrier)).filter(Boolean))]
  const providers = providerNames.map((name, index) => ({ id: `legacy-provider-${index + 1}`, name, code: providerCode(name, index), enabled: true, createdAt, updatedAt: createdAt }))
  const providerByName = new Map(providers.map(provider => [provider.name, provider]))
  const channels: LogisticsChannelRecord[] = []
  const versions: LogisticsChannelVersionRecord[] = []
  runtimeRules.forEach(rule => {
    const relation = rule.relations[0]
    const provider = providerByName.get(relation?.carrier || '') || providers[0]
    if (!provider) return
    const channelId = `legacy-channel-${rule.id}`
    const versionId = `legacy-version-${rule.id}`
    channels.push({ id: channelId, ruleId: rule.id, providerId: provider.id, name: relation?.channel || rule.name, code: relation?.channelCode || `RULE${rule.id}`, type: rule.type, logisticsAttribute: '普货', enabled: rule.status === '启用', currentVersionId: versionId, createdAt, updatedAt: createdAt })
    const rows = rule.prices.map((row, index) => legacyRateRow(row, index + 4))
    versions.push({ id: versionId, channelId, versionNumber: 1, status: 'published', fileName: '系统内置规则', sourceHash: `legacy-${rule.id}`, originalFile: null, rows, issues: [], diffRows: [], summary: { added: rows.length, price: 0, rule: 0, removed: 0, unchanged: 0, highRisk: 0 }, importedAt: createdAt, importedBy: '系统迁移', publishedAt: createdAt, publishedBy: '系统迁移', auditNote: '首次升级保留原有报价规则' })
  })
  const database = await openDatabase(); const transaction = database.transaction([STORES.providers, STORES.channels, STORES.versions], 'readwrite')
  providers.forEach(row => transaction.objectStore(STORES.providers).put(row)); channels.forEach(row => transaction.objectStore(STORES.channels).put(row)); versions.forEach(row => transaction.objectStore(STORES.versions).put(row)); await transactionDone(transaction)
}
export async function loadLogisticsWorkspace(): Promise<LogisticsWorkspaceState> {
  await seedLegacyRules()
  const [providers, channels, versions, audits] = await Promise.all([all<LogisticsProviderRecord>(STORES.providers), all<LogisticsChannelRecord>(STORES.channels), all<LogisticsChannelVersionRecord>(STORES.versions), all<LogisticsAuditRecord>(STORES.audits)])
  return {
    providers: providers.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN')),
    channels: channels.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN')),
    versions: versions.sort((a, b) => b.versionNumber - a.versionNumber || b.importedAt.localeCompare(a.importedAt)),
    audits: audits.sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
  }
}
export async function addLogisticsProvider(name: string, code: string) {
  const state = await loadLogisticsWorkspace(); const normalizedCode = code.trim().toUpperCase()
  if (!name.trim()) throw new Error('请填写物流商名称')
  if (!normalizedCode) throw new Error('请填写物流商编码')
  if (state.providers.some(provider => provider.name === name.trim() || provider.code === normalizedCode)) throw new Error('物流商名称或编码已存在')
  const timestamp = now(); const provider: LogisticsProviderRecord = { id: id('provider'), name: name.trim(), code: normalizedCode, enabled: true, createdAt: timestamp, updatedAt: timestamp }
  await put(STORES.providers, provider); return provider
}
export async function addLogisticsChannel(input: { providerId: string; name: string; code: string; type: string; logisticsAttribute: string }) {
  const state = await loadLogisticsWorkspace(); const code = input.code.trim().toUpperCase()
  if (!input.name.trim() || !code) throw new Error('请填写渠道名称和渠道编码')
  if (state.channels.some(channel => channel.code === code)) throw new Error('渠道编码已存在')
  const timestamp = now(); const channel: LogisticsChannelRecord = { id: id('channel'), ruleId: Math.max(0, ...state.channels.map(item => item.ruleId)) + 1, providerId: input.providerId, name: input.name.trim(), code, type: input.type || '专线', logisticsAttribute: input.logisticsAttribute || '普货', enabled: true, currentVersionId: '', createdAt: timestamp, updatedAt: timestamp }
  await put(STORES.channels, channel); return channel
}
export async function createLogisticsDraft(channelId: string, preview: LogisticsImportPreview, file: File, actor = '物流负责人') {
  const state = await loadLogisticsWorkspace()
  const duplicate = state.versions.find(version => version.channelId === channelId && version.sourceHash === preview.sourceHash)
  if (duplicate && duplicate.status !== 'draft') throw new Error('该渠道已经发布或归档过相同文件，无需重复上传')
  const versionNumber = Math.max(0, ...state.versions.filter(version => version.channelId === channelId).map(version => version.versionNumber)) + 1
  // Diff rows may reference Vue proxies from the active workspace. IndexedDB only accepts plain structured-clone values.
  const plain = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
  const timestamp = now(); const version: LogisticsChannelVersionRecord = { id: duplicate?.id || id('version'), channelId, versionNumber: duplicate?.versionNumber || versionNumber, status: 'draft', fileName: file.name, sourceHash: preview.sourceHash, originalFile: file.slice(0, file.size, file.type), rows: plain(preview.rows), issues: plain(preview.issues), diffRows: plain(preview.diffRows), summary: plain(preview.summary), importedAt: timestamp, importedBy: actor, publishedAt: '', publishedBy: '', auditNote: duplicate?.auditNote || '' }
  const audit: LogisticsAuditRecord = { id: id('audit'), channelId, versionId: version.id, action: 'import', actor, note: `${duplicate ? '重新解析' : '导入'} ${file.name}`, createdAt: timestamp }
  const database = await openDatabase(); const transaction = database.transaction([STORES.versions, STORES.audits], 'readwrite'); transaction.objectStore(STORES.versions).put(version); transaction.objectStore(STORES.audits).put(audit); await transactionDone(transaction); return version
}
export async function publishLogisticsVersion(channelId: string, versionId: string, note: string, actor = '物流负责人') {
  const state = await loadLogisticsWorkspace(); const channel = state.channels.find(item => item.id === channelId); const version = state.versions.find(item => item.id === versionId)
  if (!channel || !version) throw new Error('渠道或版本不存在')
  const timestamp = now(); const database = await openDatabase(); const transaction = database.transaction([STORES.channels, STORES.versions, STORES.audits], 'readwrite')
  state.versions.filter(item => item.channelId === channelId && item.status === 'published').forEach(item => transaction.objectStore(STORES.versions).put({ ...item, status: 'superseded' }))
  transaction.objectStore(STORES.versions).put({ ...version, status: 'published', auditNote: note.trim(), publishedAt: timestamp, publishedBy: actor })
  transaction.objectStore(STORES.channels).put({ ...channel, currentVersionId: version.id, updatedAt: timestamp })
  transaction.objectStore(STORES.audits).put({ id: id('audit'), channelId, versionId, action: 'publish', actor, note: note.trim() || `发布V${version.versionNumber}`, createdAt: timestamp } satisfies LogisticsAuditRecord)
  await transactionDone(transaction); await refreshPublishedLogisticsRules(); window.dispatchEvent(new CustomEvent(LOGISTICS_PUBLISHED_EVENT))
}
export async function rollbackLogisticsVersion(channelId: string, targetVersionId: string, note: string, actor = '物流负责人') {
  const state = await loadLogisticsWorkspace(); const channel = state.channels.find(item => item.id === channelId); const target = state.versions.find(item => item.id === targetVersionId)
  if (!channel || !target) throw new Error('无法找到需要回滚的版本')
  const timestamp = now(); const versionNumber = Math.max(...state.versions.filter(item => item.channelId === channelId).map(item => item.versionNumber)) + 1
  const rollback: LogisticsChannelVersionRecord = { ...target, id: id('version'), versionNumber, status: 'published', fileName: `回滚自V${target.versionNumber} · ${target.fileName}`, sourceHash: `rollback:${target.id}:${Date.now()}`, originalFile: target.originalFile, diffRows: [], summary: { added: 0, price: 0, rule: 0, removed: 0, unchanged: target.rows.length, highRisk: 0 }, importedAt: timestamp, importedBy: actor, publishedAt: timestamp, publishedBy: actor, auditNote: note.trim() || `回滚至V${target.versionNumber}`, rollbackFromVersionId: target.id }
  const database = await openDatabase(); const transaction = database.transaction([STORES.channels, STORES.versions, STORES.audits], 'readwrite')
  state.versions.filter(item => item.channelId === channelId && item.status === 'published').forEach(item => transaction.objectStore(STORES.versions).put({ ...item, status: 'superseded' }))
  transaction.objectStore(STORES.versions).put(rollback); transaction.objectStore(STORES.channels).put({ ...channel, currentVersionId: rollback.id, updatedAt: timestamp }); transaction.objectStore(STORES.audits).put({ id: id('audit'), channelId, versionId: rollback.id, action: 'rollback', actor, note: rollback.auditNote, createdAt: timestamp } satisfies LogisticsAuditRecord)
  await transactionDone(transaction); await refreshPublishedLogisticsRules(); window.dispatchEvent(new CustomEvent(LOGISTICS_PUBLISHED_EVENT)); return rollback
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
  replaceLogisticsRules(rules)
  return rules
}
export async function initializeLogisticsRepository() {
  try { await seedLegacyRules(); await refreshPublishedLogisticsRules() } catch (error) { console.warn('物流版本仓库初始化失败，继续使用内置规则', error) }
}
