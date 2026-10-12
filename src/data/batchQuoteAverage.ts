import { quoteSheetRowKey, type CustomerQuoteSheet, type QuoteSheetSourceRow } from './customerQuoteSheet'
import { averagePrices, averageScope, type AveragePlan } from './quoteChannelAverage'

// Match routes without SKU-specific option IDs or weight bands.
function routeIdentity(row: QuoteSheetSourceRow) {
  return JSON.stringify([averageScope({ ...row, sku: undefined }), row.carrier, row.channelCode || row.transport,
    row.quoteRegion || '', row.taxFeeMode || '', row.taxIncluded ?? null])
}

/** Validate the entire batch first. Prices always come from each SKU's snapshot. */
export function batchAveragePlans(template: AveragePlan, rows: QuoteSheetSourceRow[], system: CustomerQuoteSheet, existing: AveragePlan[]) {
  const sources = new Map(rows.map(row => [quoteSheetRowKey(row), row]))
  const prices = new Map(system.rows.map(row => [row.key, row.prices]))
  const selected = template.members.map(member => {
    const row = sources.get(member.optionId)
    if (!row) throw new Error('来源渠道已变化，请重新选择渠道')
    return { identity: routeIdentity(row), weight: member.weight }
  })
  const bySku = new Map<string, QuoteSheetSourceRow[]>()
  for (const row of rows) {
    if (!row.sku) throw new Error('批量报价缺少 SKU 标识，请重新生成报价')
    const group = bySku.get(row.sku) ?? []
    group.push(row); bySku.set(row.sku, group)
  }
  return [...bySku].map(([sku, candidates]) => {
    const members = selected.map(member => {
      const matches = candidates.filter(row => routeIdentity(row) === member.identity)
      if (matches.length !== 1 || matches[0]!.available === false) throw new Error(`${sku} 缺少所选渠道、渠道不可用或存在重复渠道，请调整渠道选择`)
      const optionId = quoteSheetRowKey(matches[0]!)
      return { optionId, weight: member.weight, sourcePrices: [...(prices.get(optionId) ?? [])] }
    })
    const systemPrices = averagePrices(members, template.mode, template.quantities.length)
    if (systemPrices.some(price => price == null)) throw new Error(`${sku} 的所选渠道存在缺价档位，请补齐报价或调整渠道选择`)
    // Update the same channel combination on repeat generation, without duplicating it.
    const old = existing.find(plan => plan.members.length === members.length && members.every(member => plan.members.some(previous => previous.optionId === member.optionId)))
    const owned = existing.filter(plan => sources.get(plan.members[0]?.optionId ?? '')?.sku === sku)
    if (!old && owned.length >= 20) throw new Error(`${sku} 最多保留 20 个综合方案，请先移除不用的方案`)
    const unchanged = old?.mode === template.mode && members.every(member => {
      const previous = old.members.find(candidate => candidate.optionId === member.optionId)!
      return (template.mode === 'equal' || previous.weight === member.weight) && template.quantities.every((q, i) => previous.sourcePrices[old.quantities.indexOf(q)] === member.sourcePrices[i])
    })
    return { ...template, id: old?.id ?? crypto.randomUUID(), members, systemPrices,
      prices: unchanged ? template.quantities.map(q => old!.prices[old!.quantities.indexOf(q)] ?? null) : [...systemPrices] }
  })
}
