import type { LogisticsPriceRow } from './logistics'
import type { HistoryChannel, PublishedPriceVersion, RealPriceSnapshot } from './logisticsRealPriceHistory'
import type { PriceUpdateGroup } from './logisticsUpdateOverview'
import catalog from './logisticsPriceCountryCatalog.json'

export type ExportPrice = Partial<LogisticsPriceRow> & {
  countryCode: string; weightFromKg: number; weightToKg: number; currency?: string
  originRegion?: string; sourceOriginRegion?: string; sourceRow?: number; notes?: string
  pendingReason?: string; blockingReason?: string
}
export type ExportCategory = 'up' | 'down' | 'same' | 'other'
export interface BatchExportRow {
  country: string; channel: HistoryChannel; region: string; origin: string; weight: string
  before?: ExportPrice; after?: ExportPrice; category: ExportCategory; status: string; note: string
  rateDelta: number | null; ratePercent: number | null; feeDelta: number | null
  version: PublishedPriceVersion; baseline?: PublishedPriceVersion
}
export interface BatchExportData { batch: PriceUpdateGroup; rows: BatchExportRow[]; capturedAt: string; sample?: boolean }
export type VersionRowsLoader = (version: PublishedPriceVersion, signal?: AbortSignal) => Promise<ExportPrice[]>
const published = (v: PublishedPriceVersion) => !!v.publishedAt && ['published', 'superseded'].includes(v.status)
const order = (a: PublishedPriceVersion, b: PublishedPriceVersion) => Date.parse(a.publishedAt) - Date.parse(b.publishedAt) || a.number - b.number

/** Membership comes from confirmed import IDs, never current screen filters or current channel prices. */
export function batchExportVersions(snapshot: RealPriceSnapshot, batch: PriceUpdateGroup) {
  const imports = new Set([...batch.imports.map(i => i.id), ...batch.batches.flatMap(b => b.importBatchId ? [b.importBatchId] : [])])
  const explicit = new Set(batch.batches.filter(b => !b.importBatchId).flatMap(b => b.rows.map(r => r.after.id)))
  if (batch.id.startsWith('version:')) explicit.add(batch.id.slice(8))
  return snapshot.versions.filter(v => published(v) && (explicit.has(v.id) || (!v.rollbackFromVersionId && !!v.importBatchId && imports.has(v.importBatchId)))).sort(order)
}
function baselineFor(snapshot: RealPriceSnapshot, after: PublishedPriceVersion) {
  if (after.baseVersionId === '') return undefined
  if (after.baseVersionId) return snapshot.versions.find(v => v.id === after.baseVersionId && v.channel === after.channel && published(v))
  const history = snapshot.versions.filter(v => v.channel === after.channel && published(v)).sort(order)
  return history[history.findIndex(v => v.id === after.id) - 1]
}
const number = (value: unknown): number | null => typeof value === 'number' && Number.isFinite(value) ? value : null
export const exportRate = (r?: ExportPrice) => r ? number(r.pricePerKg) : null
export const exportFee = (r?: ExportPrice) => r ? number(r.registrationFee ?? 0) : null
export const exportInterval = (r?: ExportPrice) => r ? number(r.intervalPrice ?? 0) : null
const diff = (a: number | null, b: number | null) => a === null || b === null ? null : Math.round((b - a) * 1e8) / 1e8
const models: Record<string, string> = { 'per-kg': '按公斤', 'per-kg-1g': '按克进位', 'per-kg-1g-ceil-cny': '按克进位，金额向上取整', 'per-piece-500g': '500g档按票' }
export const exportModel = (r?: ExportPrice) => r ? models[r.pricingModel || 'per-kg'] || r.pricingModel! : '—'
export function exportRules(r?: ExportPrice) {
  if (!r) return '—'
  const entries: [string, unknown][] = [
    ['计费', exportModel(r)], ['最低计重kg', r.minChargeWeightKg ?? 0], ['起计kg', r.startWeightKg ?? 0],
    ['进位kg', r.billingStepKg ?? 0], ['分段进位', r.billingStepBands], ['首重kg', r.firstWeightKg], ['首重价', r.firstWeightPrice],
    ['续重kg', r.nextWeightKg], ['续重价', r.nextWeightPrice], ['附加费', r.surcharge], ['燃油比例', r.fuelSurchargeRate],
    ['允许属性', r.allowedMarks], ['禁运属性', r.prohibitedMarks], ['排除分区', r.zoneExclude], ['计费可用', r.quoteReady === false ? '否' : undefined],
    ['待核对', r.blockingReason || r.pendingReason],
  ]
  return entries.filter(([, v]) => v !== undefined && v !== '' && v !== false && v !== 0).map(([k, v]) => `${k}：${typeof v === 'object' ? JSON.stringify(v) : v}`).join('；')
}
function ruleSignature(r: ExportPrice) {
  return JSON.stringify([
    r.pricingModel || 'per-kg', r.minChargeWeightKg || 0, r.startWeightKg || 0, r.billingStepKg || 0, r.billingStepBands || [],
    r.firstWeightKg || 0, r.firstWeightPrice || 0, r.nextWeightKg || 0, r.nextWeightPrice || 0,
    r.surcharge || 0, r.fuelSurchargeRate || 0, r.allowedMarks || '', r.prohibitedMarks || '', !!r.zoneExclude,
    r.quoteReady !== false, r.prohibitGeneralCargo || false, r.volumetric || false, r.volumeDivisor || 0,
    r.maxPerimeterCm || 0, r.maxSideCm || 0, r.blockingReason || '', r.pendingReason || '',
  ])
}
function classify(before: ExportPrice | undefined, after: ExportPrice | undefined, missing: boolean, first: boolean) {
  const rateDelta = diff(exportRate(before), exportRate(after)), feeDelta = diff(exportFee(before), exportFee(after))
  const ratePercent = rateDelta !== null && exportRate(before) ? rateDelta / exportRate(before)! : null
  const base = { rateDelta, feeDelta, ratePercent }
  const other = (status: string, note: string) => ({ ...base, category: 'other' as const, status, note })
  if (missing) return other('无可比价', '更新前版本未保留')
  if (first) return other('首次记录', '无更早的发布记录')
  if (!before) return other('新增', '本次新增重量段')
  if (!after) return other('移除', '本次移除重量段')
  if ((before.currency || 'CNY') !== (after.currency || 'CNY')) return { ...other('币种变化', '前后币种不同，不计算差额'), rateDelta: null, ratePercent: null, feeDelta: null }
  if (ruleSignature(before) !== ruleSignature(after)) return other('规则调整', '计费或适用规则有调整，见原／新计费规则')
  if (before.zoneExclude || after.zoneExclude || before.quoteReady === false || after.quoteReady === false || before.blockingReason || after.blockingReason || before.pendingReason || after.pendingReason) return other('无可比价', '该重量段不可直接报价，见计费规则')
  const model = before.pricingModel || 'per-kg'
  if (!['per-kg', 'per-kg-1g', 'per-kg-1g-ceil-cny', 'per-piece-500g'].includes(model)
    || [before, after].some(r => [r.firstWeightPrice, r.nextWeightPrice, r.surcharge, r.fuelSurchargeRate].some(v => Number(v) > 0))) return other('无可比价', '需按完整计费规则核对')
  const priceDelta = model === 'per-piece-500g' ? diff(exportInterval(before), exportInterval(after)) : rateDelta
  if (priceDelta === null || feeDelta === null) return other('无可比价', '缺少有效价格')
  if (priceDelta * feeDelta < 0) return other('混合变化', '单价与固定费涨跌方向不同')
  const category: ExportCategory = priceDelta > 0 || feeDelta > 0 ? 'up' : priceDelta < 0 || feeDelta < 0 ? 'down' : 'same'
  const status = { up: '涨价', down: '降价', same: '不变' }[category]
  const notes = [priceDelta ? `${model === 'per-piece-500g' ? '区间价' : '单价'}${priceDelta > 0 ? '涨' : '降'}${Math.abs(priceDelta)}` : '', feeDelta ? `固定费${feeDelta > 0 ? '涨' : '降'}${Math.abs(feeDelta)}` : ''].filter(Boolean)
  return { ...base, category, status, note: notes.join('，') || '费用及计费规则未变' }
}
type Interval = { from: number; to: number; left: boolean; right: boolean; a: ExportPrice[]; b: ExportPrice[] }
const validRange = (r: ExportPrice) => Number.isFinite(r.weightFromKg) && Number.isFinite(r.weightToKg) && r.weightFromKg >= 0 && r.weightToKg > r.weightFromKg
const contains = (r: ExportPrice, w: number) => (r.weightFromInclusive ? w >= r.weightFromKg : w > r.weightFromKg) && (r.weightToInclusive === false ? w < r.weightToKg : w <= r.weightToKg)
function alignedIntervals(a: ExportPrice[], b: ExportPrice[]) {
  const edges = [...new Set([...a, ...b].flatMap(r => [r.weightFromKg, r.weightToKg]))].sort((x, y) => x - y)
  const segments: Interval[] = []
  const add = (from: number, to: number, left: boolean, right: boolean, w: number) => {
    const old = a.filter(r => contains(r, w)), next = b.filter(r => contains(r, w))
    if (!old.length && !next.length) return
    const last = segments.at(-1)
    if (last && last.to === from && (last.right || left) && last.a.length === old.length && last.b.length === next.length && last.a.every((r, i) => r === old[i]) && last.b.every((r, i) => r === next[i])) { last.to = to; last.right = right }
    else segments.push({ from, to, left, right, a: old, b: next })
  }
  for (let i = 0; i < edges.length; i++) {
    const e = edges[i]!
    if (e > 0) add(e, e, true, true, e)
    const next = edges[i + 1]
    if (next !== undefined) add(e, next, false, false, (e + next) / 2)
  }
  return segments
}
function weightLabel(s: Interval) {
  const from = Math.round(s.from * 1e6) / 1000, to = Math.round(s.to * 1e6) / 1000
  const gram = [...s.a, ...s.b].every(r => ['per-kg-1g', 'per-kg-1g-ceil-cny'].includes(r.pricingModel || ''))
  if (s.from === s.to) return String(from)
  if (gram && Number.isInteger(from) && Number.isInteger(to)) return `${s.left ? from : from + 1}–${s.right ? to : to - 1}`
  return `${s.left ? '[' : '('}${from}, ${to}${s.right ? ']' : ')'}`
}
function hasBillableGram(s: Interval) {
  if (![...s.a, ...s.b].every(r => ['per-kg-1g', 'per-kg-1g-ceil-cny'].includes(r.pricingModel || ''))) return true
  const from = Math.round(s.from * 1e6) / 1000, to = Math.round(s.to * 1e6) / 1000
  const first = s.left ? Math.ceil(from) : Math.floor(from) + 1
  const last = s.right ? Math.floor(to) : Math.ceil(to) - 1
  return first <= last
}
function routes(rows: ExportPrice[]) {
  const result = new Map<string, { country: string; region: string; origin: string; rows: ExportPrice[] }>()
  for (const r of rows) {
    const zones = r.zoneName?.split(/[/／、,，;；|]/).map(z => z.trim()).filter(Boolean) || ['全国统一']
    for (const region of new Set(zones.length ? zones : ['全国统一'])) {
      const origin = r.originRegion || r.sourceOriginRegion || ''
      const key = JSON.stringify([r.countryCode, region, origin]);let route = result.get(key)
      if (!route) { route = { country: r.countryCode, region, origin, rows: [] }; result.set(key, route) }
      route.rows.push(r)
    }
  }
  return result
}

export async function prepareBatchExport(snapshot: RealPriceSnapshot, batch: PriceUpdateGroup, loader: VersionRowsLoader, signal?: AbortSignal, progress?: (done: number, total: number) => void): Promise<BatchExportData> {
  const selected = batchExportVersions(snapshot, batch)
  if (!selected.length) throw new Error('该批次没有已发布记录')
  const needed = new Map<string, PublishedPriceVersion>()
  selected.forEach(v => { needed.set(v.id, v); const b = baselineFor(snapshot, v); if (b) needed.set(b.id, b) })
  const loaded = new Map<string, ExportPrice[]>()
  const pending = [...needed.values()];let done = 0
  // At most three read-only requests at a time; do not retain data across accounts.
  await Promise.all(Array.from({ length: Math.min(3, pending.length) }, async () => {
    for (;;) {
      signal?.throwIfAborted();const v = pending.shift();if (!v) break
      const rows = v.preparedPrices ? await loader(v, signal) : v.rows
      if (!Array.isArray(rows) || rows.length > 50000) throw new Error('版本重量段数据不完整')
      loaded.set(v.id, rows);progress?.(++done, needed.size)
    }
  }))
  signal?.throwIfAborted()
  const result: BatchExportRow[] = []
  for (const version of selected) {
    await new Promise(resolve => setTimeout(resolve, 0))
    signal?.throwIfAborted()
    const channel = snapshot.channels.find(c => c.id === version.channel)
    if (!channel) throw new Error('批次渠道资料缺失')
    const baseline = baselineFor(snapshot, version)
    const oldRoutes = routes(baseline ? loaded.get(baseline.id)! : []), newRoutes = routes(loaded.get(version.id)!)
    for (const key of new Set([...oldRoutes.keys(), ...newRoutes.keys()])) {
      const route = newRoutes.get(key) || oldRoutes.get(key)!, old = oldRoutes.get(key)?.rows || [], next = newRoutes.get(key)?.rows || []
      const country = catalog.countries.find(c => c.code === route.country)?.name || route.country || '未记录国家'
      const common = { country, channel, region: route.region, origin: route.origin, version, baseline }
      for (const segment of alignedIntervals(old.filter(validRange), next.filter(validRange))) {
        if (!hasBillableGram(segment)) continue
        const before = segment.a[0], after = segment.b[0]
        const comparison = classify(before, after, !!version.baseVersionId && !baseline, !baseline && !version.baseVersionId)
        if (segment.a.length > 1 || segment.b.length > 1) {
          // Never choose one tariff from overlapping candidates. Preserve every candidate.
          for (const r of segment.a) result.push({ ...common, ...comparison, before: r, after: undefined, weight: weightLabel(segment), category: 'other', status: '档位重叠', note: '原版本同一重量匹配多行', rateDelta: null, ratePercent: null, feeDelta: null })
          for (const r of segment.b) result.push({ ...common, ...comparison, before: undefined, after: r, weight: weightLabel(segment), category: 'other', status: '档位重叠', note: '新版本同一重量匹配多行', rateDelta: null, ratePercent: null, feeDelta: null })
        } else result.push({ ...common, ...comparison, before, after, weight: weightLabel(segment) })
      }
      for (const [side, rows] of [['before', old], ['after', next]] as const) for (const r of rows.filter(r => !validRange(r))) {
        result.push({ ...common, [side]: r, weight: '重量段待核对', category: 'other', status: '无可比价', note: `原始重量：${r.weightFromKg}–${r.weightToKg} kg`, rateDelta: null, ratePercent: null, feeDelta: null })
      }
    }
  }
  if (!result.length) throw new Error('该批次没有可导出的重量段')
  return { batch, rows: result, capturedAt: snapshot.capturedAt, sample: snapshot.dataset === 'local-demo-only' }
}
