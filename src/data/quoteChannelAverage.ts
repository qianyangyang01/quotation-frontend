import Decimal from 'decimal.js'
import { quoteSheetCountryCode, quoteSheetRowKey, type CustomerQuoteSheet, type CustomerQuoteSheetRow, type QuoteSheetSourceRow } from './customerQuoteSheet'

export type AveragePlan = {
  id: string; mode: 'equal' | 'weighted'; display: 'summary' | 'details'
  provider: string; shippingTime: string; quantities: number[]
  members: Array<{ optionId: string; weight: number; sourcePrices: Array<number | null> }>
  systemPrices: Array<number | null>; prices: Array<number | null>
}
export const cloneAveragePlans = (plans: AveragePlan[] = []): AveragePlan[] => JSON.parse(JSON.stringify(plans))
export function validAveragePlans(plans: unknown): plans is AveragePlan[] {
  if (!Array.isArray(plans) || plans.length > 20) return false
  const ids = new Set<string>()
  return plans.every((p: AveragePlan) => {
    if (!p || typeof p.id !== 'string' || !/^[A-Za-z0-9_-]{1,80}$/.test(p.id) || ids.has(p.id) || !['equal', 'weighted'].includes(p.mode) || !['summary', 'details'].includes(p.display) ||
      typeof p.provider !== 'string' || !p.provider.trim() || p.provider.length > 80 || typeof p.shippingTime !== 'string' || p.shippingTime.length > 80 ||
      !Array.isArray(p.quantities) || !p.quantities.length || p.quantities.length > 10 || p.quantities.some(q => !Number.isSafeInteger(q) || q <= 0) || new Set(p.quantities).size !== p.quantities.length ||
      !Array.isArray(p.members) || p.members.length < 2 || new Set(p.members.map(m => m?.optionId)).size !== p.members.length) return false
    ids.add(p.id)
    const prices = (v: unknown) => Array.isArray(v) && v.length === p.quantities.length && v.every(n => n === null || typeof n === 'number' && Number.isFinite(n) && n >= 0 && n <= 999999999.99 && new Decimal(n).decimalPlaces() <= 2)
    if (!prices(p.systemPrices) || !prices(p.prices) || p.members.some(m => !m || typeof m.optionId !== 'string' || !prices(m.sourcePrices))) return false
    try { return JSON.stringify(averagePrices(p.members, p.mode, p.quantities.length)) === JSON.stringify(p.systemPrices) } catch { return false }
  })
}
export function averageScope(row: QuoteSheetSourceRow) {
  return JSON.stringify([quoteSheetCountryCode(row.country, []) || row.country, row.quoteRegion || '', row.taxFeeMode ?? '', row.taxIncluded ?? null, row.taxConfigured ?? null, row.taxRatePercent ?? null])
}
export function averagePrices(members: AveragePlan['members'], mode: AveragePlan['mode'], count: number) {
  if (members.length < 2) throw new Error('请至少选择两条渠道')
  if (members.some(m => !Number.isFinite(m.weight) || m.weight <= 0 || m.weight > 100 || new Decimal(m.weight).decimalPlaces() > 2)) throw new Error('每条渠道的权重须大于 0，最多两位小数')
  if (mode === 'weighted' && !members.reduce((sum, m) => sum.plus(m.weight), new Decimal(0)).equals(100)) throw new Error('权重合计必须为 100%')
  return Array.from({ length: count }, (_, i) => {
    if (members.some(m => m.sourcePrices[i] == null || !Number.isFinite(m.sourcePrices[i]) || m.sourcePrices[i]! < 0)) return null
    const total = members.reduce((sum, m) => sum.plus(new Decimal(m.sourcePrices[i]!).mul(mode === 'equal' ? 1 : m.weight)), new Decimal(0))
    return total.div(mode === 'equal' ? members.length : 100).toDecimalPlaces(2, Decimal.ROUND_HALF_UP).toNumber()
  })
}
/** Translate UI keys to stable saved option IDs, preserving the source snapshot. */
export function mapAveragePlans(plans: AveragePlan[] | undefined, keyFor: (key: string) => string | undefined) {
  return cloneAveragePlans(plans).map(plan => ({ ...plan, members: plan.members.map(member => {
    const optionId = keyFor(member.optionId)
    if (!optionId) throw new Error('综合报价的来源渠道已变化，请重新生成')
    return { ...member, optionId }
  }) }))
}
export function averagePlanIssues(plan: AveragePlan, sources: QuoteSheetSourceRow[], quantities: number[]) {
  const selected = plan.members.map(m => sources.find(row => quoteSheetRowKey(row) === m.optionId))
  if (selected.some(row => !row || row.available === false)) return ['综合报价来源渠道已移除或不可用，请移除方案后重新生成']
  if (new Set(selected.map(row => averageScope(row!))).size !== 1) return ['只能合并同一国家、报价区域和税费口径的渠道']
  const missing = quantities.filter(q => plan.quantities.indexOf(q) < 0 || plan.systemPrices[plan.quantities.indexOf(q)] == null)
  return missing.length ? [`综合报价缺少 ${missing.join('、')} 数量档位的系统价格，请补齐来源报价后重新生成`] : []
}
export function applyAveragePlans(sheet: CustomerQuoteSheet, plans: AveragePlan[], sources: QuoteSheetSourceRow[], quantities: number[], editor = false) {
  const issues = plans.flatMap(plan => averagePlanIssues(plan, sources, quantities))
  const rows = [...sheet.rows]
  const hidden = new Set<string>()
  for (const plan of plans) {
    const keys = new Set(plan.members.map(m => m.optionId))
    const first = sheet.rows.find(row => keys.has(row.key))
    if (!first) continue
    const summary: CustomerQuoteSheetRow = {
      ...first, key: `average:${plan.id}`, number: 'AVG', averageId: plan.id, provider: plan.provider,
      shippingTime: plan.shippingTime || 'To be confirmed', processingTime: '1-2 workingdays',
      prices: quantities.map(q => plan.prices[plan.quantities.indexOf(q)] ?? null),
      sourceDescription: `${plan.mode === 'equal' ? '普通平均' : '加权平均'} · ${plan.members.length} 条渠道 · 系统报价为计算基准`,
    }
    const last = rows.reduce((found, row, index) => keys.has(row.key) ? index : found, -1)
    rows.splice(last + 1, 0, summary)
    if (!editor && plan.display === 'summary') {
      keys.forEach(key => hidden.add(key))
    }
  }
  return { ...sheet, rows: rows.filter(row => !hidden.has(row.key)), issues: [...sheet.issues, ...issues], tableIssues: [...(sheet.tableIssues ?? []), ...issues], priceIssues: [...(sheet.priceIssues ?? []), ...issues] }
}
