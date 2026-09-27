import { normalizeCustomerPrices } from './customerQuotePrices'
import type { QuotationRecord } from './quotationRecords'

function parseSnapshot(text: string) {
  try {
    const snapshot = normalizeCustomerPrices(JSON.parse(text))
    if (!snapshot || new Set(snapshot.rows.map(row => row.optionId)).size !== snapshot.rows.length) return undefined
    for (const plan of snapshot.averagePlans ?? []) snapshot.rows.push({ optionId: 'average:' + plan.id, prices: snapshot.quantities.map(q => plan.prices[plan.quantities.indexOf(q)] ?? null) })
    return snapshot
  } catch { return undefined }
}

/** Compare historical customer snapshots by channel identity and quantity, never by column position. */
export function customerPriceRevision(record: QuotationRecord, beforeText: string, afterText: string) {
  const before = parseSnapshot(beforeText), after = parseSnapshot(afterText)
  if (!before || !after) return { groups: [], note: '本次客户报价已修改，但历史价格明细不完整，无法还原修改前后金额。' }
  const quantities = [...new Set([...before.quantities, ...after.quantities])].sort((a, b) => (a || Infinity) - (b || Infinity))
  const ids = [...new Set([...before.rows, ...after.rows].map(row => row.optionId))]
  const money = (price: number | null) => price == null ? '未报价' : `$${price.toFixed(2)}`
  const groups = ids.flatMap(optionId => {
    const oldRow = before.rows.find(row => row.optionId === optionId), newRow = after.rows.find(row => row.optionId === optionId)
    const changes = quantities.flatMap(quantity => {
      const oldIndex = before.quantities.indexOf(quantity), newIndex = after.quantities.indexOf(quantity)
      const hadPrice = !!oldRow && oldIndex >= 0, hasPrice = !!newRow && newIndex >= 0
      if (!hadPrice && !hasPrice) return []
      const oldPrice = oldRow?.prices[oldIndex] ?? null, newPrice = newRow?.prices[newIndex] ?? null
      if (hadPrice === hasPrice && oldPrice === newPrice) return []
      return [{ quantity, label: quantity ? `${quantity}${record.quoteMode === 'bundle' ? '套' : '件'}` : '自定义数量',
        before: hadPrice ? money(oldPrice) : '未设置', after: hasPrice ? money(newPrice) : '已移除' }]
    })
    if (!changes.length) return []
    const option = record.quoteOptions?.find(item => item.id === optionId)
    const plan = [...(after.averagePlans ?? []), ...(before.averagePlans ?? [])].find(p => 'average:' + p.id === optionId)
    const route = plan ? `综合报价 · ${plan.provider}` : option ? [option.country, option.quoteRegion, option.carrier, option.channel].filter(Boolean).join(' · ') : `历史渠道 ${ids.indexOf(optionId) + 1}（当前记录已无渠道名称）`
    return [{ optionId, route, changes }]
  })
  const planSettings = (s: typeof before) => JSON.stringify(s.averagePlans?.map(p => ({ id: p.id, mode: p.mode, display: p.display, provider: p.provider, shippingTime: p.shippingTime, members: p.members.map(m => [m.optionId, m.weight]).sort() })) ?? [])
  return { groups, note: planSettings(before) !== planSettings(after) ? '本次同时新增、移除或调整了综合报价方案、参与渠道、权重或展示设置。' : groups.length ? '' : '本次未改变客户报价金额（仅调整排列或保存格式）。' }
}
