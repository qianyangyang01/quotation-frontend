import { expect, it } from 'vitest'
import { batchAveragePlans } from './batchQuoteAverage'
import { buildCustomerQuoteSheet, newQuoteSheetEdits, quoteSheetRowKey, type QuoteSheetSourceRow } from './customerQuoteSheet'
import { validAveragePlans, type AveragePlan } from './quoteChannelAverage'

function fixture() {
  const rows: QuoteSheetSourceRow[] = ['A', 'B'].flatMap((sku, s) => ['a', 'b'].map((channelCode, i) => ({
    sku, country: s ? '美国' : 'US', carrier: '4PX', transport: channelCode, channelCode,
    channelKey: `${sku}-${channelCode}`, ruleId: s, rule: `weight band ${s}`, eta: '7-12 working days',
    quote1: (s + 1) * (i ? 7 : 5), quote2: (s + 1) * (i ? 13 : 9), quote3: (s + 1) * (i ? 18 : 14), quoteCustom: null,
  })))
  const system = buildCustomerQuoteSheet({ rows, countries: [], edits: newQuoteSheetEdits('QA'), customQuantity: 0, bundle: false, quantities: [1, 2, 3] })
  const plan: AveragePlan = { id: 'template', mode: 'weighted', display: 'details', provider: 'Combined Shipping', shippingTime: '', quantities: [1, 2, 3],
    members: rows.slice(0, 2).map((row, i) => ({ optionId: quoteSheetRowKey(row), weight: i ? 75 : 25, sourcePrices: [] })), prices: [], systemPrices: [] }
  return { rows, system, plan }
}
it('matches country aliases and routes across different SKU IDs and weight bands; weights every quantity independently', () => {
  const { rows, system, plan } = fixture()
  const result = batchAveragePlans(plan, rows, system, [])
  expect(result.map(plan => plan.prices)).toEqual([[6.5, 12, 17], [13, 24, 34]])
  expect(result.every(plan => validAveragePlans([plan]))).toBe(true)
  expect(result[0]!.id).not.toBe(result[1]!.id)
})
it('retains manual average prices on repeat generation but recalculates after weights change', () => {
  const { rows, system, plan } = fixture()
  const saved = batchAveragePlans(plan, rows, system, [])
  saved[1]!.prices[0] = 12.95
  const repeated = batchAveragePlans(plan, rows, system, saved)
  expect(repeated).toEqual(saved)
  plan.members[0]!.weight = 50; plan.members[1]!.weight = 50
  const changed = batchAveragePlans(plan, rows, system, saved)
  expect(changed[1]!.id).toBe(saved[1]!.id)
  expect(changed.map(plan => plan.prices)).toEqual([[6, 11, 16], [12, 22, 32]])
  expect(saved[1]!.prices[0]).toBe(12.95)
})
it('refuses missing price tiers without changing existing plans', () => {
  const { rows, system, plan } = fixture()
  const saved = batchAveragePlans(plan, rows, system, [])
  const before = JSON.stringify(saved)
  system.rows[3]!.prices[2] = null
  expect(() => batchAveragePlans(plan, rows, system, saved)).toThrow('B 的所选渠道存在缺价档位')
  expect(JSON.stringify(saved)).toBe(before)
})
it.each(['region', 'tax', 'unavailable', 'duplicate'] as const)('refuses %s route mismatches instead of using a different service', kind => {
  const { rows, system, plan } = fixture()
  if (kind === 'region') rows[3]!.quoteRegion = 'Zone 2'
  if (kind === 'tax') rows[3]!.taxIncluded = true
  if (kind === 'unavailable') rows[3]!.available = false
  if (kind === 'duplicate') rows.push({ ...rows[3]!, channelKey: 'duplicate' })
  expect(() => batchAveragePlans(plan, rows, system, [])).toThrow('B 缺少所选渠道')
})
it('applies the existing twenty-plan limit to each SKU, not to the combined batch', () => {
  const { rows, system, plan } = fixture()
  const saved = Array.from({ length: 20 }, (_, i) => ({ ...plan, id: String(i), members: [{ optionId: quoteSheetRowKey(rows[2]!), weight: 1, sourcePrices: [1, 2, 3] }] }))
  expect(() => batchAveragePlans(plan, rows, system, saved)).toThrow('B 最多保留 20 个综合方案')
})
