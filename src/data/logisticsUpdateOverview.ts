import { normalizeLogisticsAttribute } from './logisticsAttributes'
import { realChannelHistories, type RealPriceSnapshot, type PublishedPriceVersion, type HistoryPoint, type HistoryChannel, type PriceHistoryImportBatch } from './logisticsRealPriceHistory'

export type UpdateStatus = 'up' | 'down' | 'same' | 'first' | 'added' | 'removed' | 'unavailable'
export interface PriceUpdateRow {
  key: string
  channel: HistoryChannel
  region: string
  before?: HistoryPoint
  after: HistoryPoint
  change: number | null
  percent: number | null
  status: UpdateStatus
  reason: string
  rollback: boolean
  importBatchId: string | null
}
export interface PriceUpdateBatch {
  id: string
  importBatchId: string | null
  startedAt: string
  publishedAt: string
  providers: string[]
  files: string[]
  rows: PriceUpdateRow[]
}
export interface PriceUpdateGroup {
  id: string
  windowStartAt: string | null
  windowEndAt: string | null
  operator: string | null
  startedAt: string
  publishedAt: string
  providers: string[]
  files: string[]
  rows: PriceUpdateRow[]
  batches: PriceUpdateBatch[]
  imports: PriceHistoryImportBatch[]
}
export const UPDATE_WINDOW_MS = 4 * 60 * 60 * 1000
export const updateStatusLabels: Record<UpdateStatus, string> = {
  up: '涨价', down: '降价', same: '不变', first: '首次记录', added: '新增适用价', removed: '已无适用价', unavailable: '暂不可比',
}
export function updateAmount(row: PriceUpdateRow): string {
  return row.change === null ? updateStatusLabels[row.status] : row.change === 0 ? '不变 0.00 元/票' : `${row.change > 0 ? '涨价' : '降价'} ${Math.abs(row.change).toFixed(2)} 元/票`
}
export function updatePercent(row: PriceUpdateRow): string {
  return row.percent === null ? '—' : `${row.percent > 0 ? '+' : ''}${row.percent.toFixed(1)}%`
}
export function updateCounts(rows: PriceUpdateRow[]) {
  return rows.reduce((counts, row) => { counts[row.status]++; return counts }, { up: 0, down: 0, same: 0, first: 0, added: 0, removed: 0, unavailable: 0 })
}
export function matchesUpdateAttribute(channel: HistoryChannel, attribute: string): boolean {
  if (!attribute) return true
  const accepted = attribute === '普货/服装' ? ['普货', '服装'] : [normalizeLogisticsAttribute(attribute)]
  return (channel.attribute || '普货').split(/[、,，;；|]/).some(value => accepted.includes(normalizeLogisticsAttribute(value.trim())))
}

function compareUpdate(channel: HistoryChannel, region: string, after: HistoryPoint, before: HistoryPoint | undefined, version: PublishedPriceVersion): PriceUpdateRow {
  const change = before?.price != null && after.price != null ? Math.round((after.price - before.price) * 100) / 100 : null
  const percent = change !== null && before?.price ? change / before.price * 100 : null
  let status: UpdateStatus
  let reason = ''
  if (change !== null) status = change > 0 ? 'up' : change < 0 ? 'down' : 'same'
  else if (!before) {
    status = version.baseVersionId ? 'unavailable' : 'first'
    reason = version.baseVersionId ? '更新前版本未保留，无法计算差额' : '无更早的已发布记录，不将首次价格计作涨价'
  } else if (before.price === null && after.price !== null && before.reason === '此版本无该国家 / 分区') {
    status = 'added'; reason = '更新前无该国家 / 分区的价格，不计算涨跌'
  } else if (before.price !== null && after.price === null && after.reason === '此版本无该国家 / 分区') {
    status = 'removed'; reason = '更新后无该国家 / 分区的价格，不视作降至零元'
  } else {
    status = 'unavailable'; reason = [before.price === null ? `更新前：${before.reason || '无适用价'}` : '', after.price === null ? `更新后：${after.reason || '无适用价'}` : ''].filter(Boolean).join('；')
  }
  return { key: `${after.id}|${region}`, channel, region, before, after, change, percent, status, reason, rollback: !!version.rollbackFromVersionId, importBatchId: version.rollbackFromVersionId ? null : version.importBatchId || null }
}

/** Only a source-confirmed import ID groups versions. Never guess a batch from dates, filenames or similar channel names. */
export function priceUpdateBatches(snapshot: RealPriceSnapshot, country: string, weight: number, attribute = ''): PriceUpdateBatch[] {
  const versions = new Map(snapshot.versions.map(version => [version.id, version]))
  const channels = snapshot.channels.filter(channel => matchesUpdateAttribute(channel, attribute))
  const histories = realChannelHistories({ ...snapshot, channels }, country, weight, '', true)
  const batches = new Map<string, PriceUpdateBatch>()
  for (const history of histories) {
    const points = new Map(history.history.map(point => [point.id, point]))
    for (const [index, after] of history.history.entries()) {
      const version = versions.get(after.id)!
      // Explicit baseline takes precedence; a missing referenced baseline must never silently become another version.
      const before = version.baseVersionId === '' ? undefined : version.baseVersionId ? points.get(version.baseVersionId) : history.history[index - 1]
      // A region that exists only in later versions was not involved in this update.
      if (after.reason === '此版本无该国家 / 分区' && (!before || before.reason === '此版本无该国家 / 分区')) continue
      const importBatchId = version.rollbackFromVersionId ? null : version.importBatchId || null
      const id = importBatchId ? `import:${importBatchId}` : `version:${version.id}`
      let batch = batches.get(id)
      if (!batch) {
        batch = { id, importBatchId, startedAt: after.timestamp, publishedAt: after.timestamp, providers: [], files: [], rows: [] }
        batches.set(id, batch)
      }
      if (Date.parse(after.timestamp) < Date.parse(batch.startedAt)) batch.startedAt = after.timestamp
      if (Date.parse(after.timestamp) > Date.parse(batch.publishedAt)) batch.publishedAt = after.timestamp
      if (!batch.providers.includes(history.channel.provider)) batch.providers.push(history.channel.provider)
      if (!batch.files.includes(after.file)) batch.files.push(after.file)
      batch.rows.push(compareUpdate(history.channel, history.region, after, before, version))
    }
  }
  for (const batch of batches.values()) batch.rows.sort((a, b) => (b.change ?? -Infinity) - (a.change ?? -Infinity) || a.channel.name.localeCompare(b.channel.name, 'zh-CN') || a.region.localeCompare(b.region, 'zh-CN', { numeric: true }) || b.after.number - a.after.number)
  return [...batches.values()].sort((a, b) => Date.parse(b.publishedAt) - Date.parse(a.publishedAt) || a.id.localeCompare(b.id))
}

/** Fixed four-hour windows per operator, anchored before applying country, weight or publication filters.
 * Unpublished imports still anchor a window. Subsequent uploads never extend it. */
export function priceUpdateGroups(snapshot: RealPriceSnapshot, country: string, weight: number, attribute = ''): PriceUpdateGroup[] {
  type Window = { id: string; start: string; end: string; operator: string; imports: PriceHistoryImportBatch[] }
  const active = new Map<string, Window>()
  const membership = new Map<string, Window>()
  const imports = [...(snapshot.importBatches || [])].sort((a, b) => Date.parse(a.importedAt) - Date.parse(b.importedAt) || a.id.localeCompare(b.id))
  for (const item of imports) {
    if (!item.operator.trim() || !Number.isFinite(Date.parse(item.importedAt))) continue
    let window = active.get(item.operator)
    // [first import, first import + 4 hours): the exact boundary starts the next group.
    if (!window || Date.parse(item.importedAt) >= Date.parse(window.end)) {
      window = { id: `window:${item.id}`, start: item.importedAt, end: new Date(Date.parse(item.importedAt) + UPDATE_WINDOW_MS).toISOString(), operator: item.operator, imports: [] }
      active.set(item.operator, window)
    }
    window.imports.push(item)
    membership.set(item.id, window)
  }
  const groups = new Map<string, PriceUpdateGroup>()
  for (const batch of priceUpdateBatches(snapshot, country, weight, attribute)) {
    const window = batch.importBatchId ? membership.get(batch.importBatchId) : undefined
    const id = window?.id || batch.id
    let group = groups.get(id)
    if (!group) {
      group = { id, windowStartAt: window?.start || null, windowEndAt: window?.end || null, operator: window?.operator || null, startedAt: batch.startedAt, publishedAt: batch.publishedAt, providers: [], files: [], rows: [], batches: [], imports: window?.imports || (batch.importBatchId ? imports.filter(item => item.id === batch.importBatchId) : []) }
      groups.set(id, group)
    }
    group.batches.push(batch)
    group.rows.push(...batch.rows)
    if (Date.parse(batch.startedAt) < Date.parse(group.startedAt)) group.startedAt = batch.startedAt
    if (Date.parse(batch.publishedAt) > Date.parse(group.publishedAt)) group.publishedAt = batch.publishedAt
    for (const provider of batch.providers) if (!group.providers.includes(provider)) group.providers.push(provider)
    for (const file of batch.files) if (!group.files.includes(file)) group.files.push(file)
  }
  for (const group of groups.values()) {
    group.rows.sort((a, b) => (b.change ?? -Infinity) - (a.change ?? -Infinity) || a.channel.name.localeCompare(b.channel.name, 'zh-CN') || a.region.localeCompare(b.region, 'zh-CN', { numeric: true }) || b.after.number - a.after.number)
    group.batches.sort((a, b) => Date.parse(a.startedAt) - Date.parse(b.startedAt) || a.id.localeCompare(b.id))
  }
  return [...groups.values()].sort((a, b) => Date.parse(b.windowStartAt || b.startedAt) - Date.parse(a.windowStartAt || a.startedAt) || a.id.localeCompare(b.id))
}
