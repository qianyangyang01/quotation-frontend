import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { calculateLogisticsFee, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import type { LogisticsRateRow } from './logisticsWorkbook'

const path = process.env.QIAOJIE_BILLING_CASES
it.skipIf(!path)('matches Java for every Qiaojie kilogram tier and every country minimum in the real workbook', () => {
  const evidence: { channels: Array<{ channelName: string; providerName: string; rows: LogisticsRateRow[] }>; cases: Array<{ channel: string; country: string; weightKg: number; expected: { total: number; chargeWeightKg: number } }> } = JSON.parse(readFileSync(path!, 'utf8'))
  const rules = new Map(evidence.channels.map((channel, i) => {
    const rule: LogisticsRule = {
      id: i + 1, name: channel.channelName, englishName: '', type: '专线', currency: 'CNY', published: 'test',
      status: '启用', dates: '', users: '', phoneRequired: false, areaCount: 1, priceRowCount: channel.rows.length, billingVerified: true,
      relations: [{ carrier: channel.providerName, channel: channel.channelName, channelCode: `QJ${i}`, discounts: '' }],
      prices: channel.rows.map(normalizeLogisticsPriceRow),
    }
    return [channel.channelName, rule]
  }))
  expect(evidence.cases).toHaveLength(420)
  for (const item of evidence.cases) {
    expect(calculateLogisticsFee(rules.get(item.channel)!, item.country, item.weightKg), `${item.channel}/${item.country}/${item.weightKg}`).toMatchObject({
      total: item.expected.total, chargeWeightKg: item.expected.chargeWeightKg, minChargeWeightKg: item.channel.endsWith('包税B') ? .5 : .05,
    })
  }
})
