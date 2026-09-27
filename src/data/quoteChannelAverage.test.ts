import { describe, expect, it } from 'vitest'
import { applyAveragePlans, averagePrices, averagePlanIssues, mapAveragePlans, validAveragePlans, type AveragePlan } from './quoteChannelAverage'
import { buildCustomerQuoteSheet, newQuoteSheetEdits, quoteSheetRowKey, quoteSheetTextTable, type QuoteSheetSourceRow } from './customerQuoteSheet'

const sources: QuoteSheetSourceRow[] = ['SDH', 'SF', '燕文'].map((carrier, i) => ({ country: '美国', quoteRegion: '全国统一', carrier, transport: '普货', channelKey: String(i), rule: '', ruleId: i, channelCode: String(i), eta: '7-12 workingdays', quote1: [5, 5.25, 5.3][i]!, quote2: [5.95, 6.25, 6.45][i]!, quote3: [7, 7, 7.45][i]!, quoteCustom: null }))
const plan = (): AveragePlan => ({ id: 'plan-1', mode: 'weighted', display: 'details', provider: 'Combined Shipping', shippingTime: '7-12 workingdays', quantities: [1, 2, 3], members: sources.map((row, i) => ({ optionId: quoteSheetRowKey(row), weight: [50, 30, 20][i]!, sourcePrices: [row.quote1, row.quote2, row.quote3] })), systemPrices: [5.14, 6.14, 7.09], prices: [5.14, 6.14, 7.09] })
describe('channel average snapshot', () => {
  it('calculates each tier independently with decimal half-up rounding', () => {
    const p = plan()
    expect(averagePrices(p.members, 'weighted', 3)).toEqual([5.14, 6.14, 7.09])
    expect(averagePrices(p.members, 'equal', 3)).toEqual([5.18, 6.22, 7.15])
    expect(averagePrices([{ optionId: 'a', weight: 50, sourcePrices: [1] }, { optionId: 'b', weight: 50, sourcePrices: [1.01] }], 'weighted', 1)).toEqual([1.01])
  })
  it('rejects invalid weights and never substitutes zero for a missing tier', () => {
    const p = plan(); p.members[0]!.weight = 49
    expect(() => averagePrices(p.members, 'weighted', 3)).toThrow('100%')
    p.members[0]!.weight = -1
    expect(() => averagePrices(p.members, 'weighted', 3)).toThrow('权重')
    p.members[0]!.weight = 50; p.members[0]!.sourcePrices[2] = null
    expect(averagePrices(p.members, 'weighted', 3)).toEqual([5.14, 6.14, null])
  })
  it('checks source identity, country, region, tax scope and quantity availability', () => {
    expect(averagePlanIssues(plan(), sources, [3, 1])).toEqual([])
    for (const change of [{ country: '英国' }, { quoteRegion: '偏远' }, { taxIncluded: true }]) {
      expect(averagePlanIssues(plan(), [sources[0]!, sources[1]!, { ...sources[2]!, ...change }], [1])).not.toEqual([])
    }
    expect(averagePlanIssues(plan(), sources, [50])[0]).toContain('50')
    expect(averagePlanIssues(plan(), sources.slice(1), [1])[0]).toContain('来源渠道')
  })
  it('adds the same AVG row to image/text models while retaining source detail in the editor', () => {
    const sheet = buildCustomerQuoteSheet({ rows: sources, countries: [], edits: newQuoteSheetEdits('Demo'), customQuantity: 3, bundle: false, quantities: [3, 1, 2] })
    const p = plan(); p.display = 'summary'
    const output = applyAveragePlans(sheet, [p], sources, [3, 1, 2])
    expect(output.rows).toHaveLength(1)
    expect(output.rows[0]).toMatchObject({ number: 'AVG', prices: [7.09, 5.14, 6.14] })
    expect(quoteSheetTextTable(output)[1]).toContain('AVG')
    expect(applyAveragePlans(sheet, [p], sources, [3, 1, 2], true).rows).toHaveLength(4)
    expect(sheet.rows).toHaveLength(3)
  })
  it('round trips stable saved IDs and rejects tampered totals or duplicate source IDs', () => {
    const p = plan(), saved = mapAveragePlans([p], key => sources.find(row => quoteSheetRowKey(row) === key)?.channelKey)
    expect(validAveragePlans(saved)).toBe(true)
    expect(mapAveragePlans(saved, id => quoteSheetRowKey(sources.find(row => row.channelKey === id)!))).toEqual([p])
    saved[0]!.systemPrices[0] = 1
    expect(validAveragePlans(saved)).toBe(false)
    const duplicate = plan(); duplicate.members[1]!.optionId = duplicate.members[0]!.optionId
    expect(validAveragePlans([duplicate])).toBe(false)
  })
})
