import { expect, it } from 'vitest'
import parsed from './__fixtures__/yanwenJapanFirstNext.json'
import { calculateLogisticsFee, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'

// Parser output from the 2026-11-23 source workbook (SHA-256 9c321eec…2315).
// Expected totals below are independently calculated from its eight original tariffs.
it('quotes all four Japanese routes with the existing browser calculator', () => {
  const first = [[38.5], [43], [34, 34.5, 36.5], [35, 35, 36]]
  const next = [[8.5], [16], [7.5, 9.5, 11.5], [12, 13.5, 14]]
  parsed.forEach((channel, index) => {
    const rule: LogisticsRule = {
      id: index + 1, name: channel.name, englishName: '', type: '专线', currency: 'CNY', published: 'test', status: '启用', dates: '', users: '',
      relations: [{ carrier: '燕文', channel: channel.name, channelCode: String(index), discounts: '' }],
      phoneRequired: false, areaCount: 1, priceRowCount: channel.prices.length, billingVerified: true,
      prices: channel.prices.map(normalizeLogisticsPriceRow),
    }
    const max = index < 2 ? 10 : 20
    for (let tier = 1; tier <= max * 2; tier++) {
      const upper = tier / 2
      for (const weight of [upper - .499999, upper - .001, upper]) {
        const band = index < 2 ? 0 : upper <= 2 ? 0 : upper <= 10 ? 1 : 2
        const expected = first[index]![band]! + (tier - 1) * next[index]![band]!
        expect(calculateLogisticsFee(rule, '日本', weight), `${channel.name} ${weight}`).toMatchObject({ total: expected, chargeWeightKg: upper })
      }
    }
    for (const weight of [0, -1, max + .000001]) expect(calculateLogisticsFee(rule, '日本', weight)).toBeNull()
  })
})
