import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { calculateLogisticsFee, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import type { LogisticsRateRow } from './logisticsWorkbook'

const path = process.env.WANBANG_MEDIUM_BILLING_CASES
it.skipIf(!path)('reconciles both Wanbang medium channels against the original workbook and backend', () => {
  const evidence: {
    channels: Array<{ channelName: string; providerName: string; rows: LogisticsRateRow[] }>
    cases: Array<{ channel: string; weightKg: number; total: number }>
  } = JSON.parse(readFileSync(path!, 'utf8'))
  expect(evidence.channels).toHaveLength(2)
  expect(evidence.cases).toHaveLength(16)
  const rules = new Map(evidence.channels.map((channel, i) => [channel.channelName, {
    id: i + 1, name: channel.channelName, englishName: '', type: '专线', currency: 'CNY', published: 'test', status: '启用', dates: '', users: '',
    relations: [{ carrier: channel.providerName, channel: channel.channelName, channelCode: `WBMP${i}`, discounts: '' }],
    phoneRequired: false, areaCount: 1, priceRowCount: channel.rows.length, billingVerified: true,
    prices: channel.rows.map(normalizeLogisticsPriceRow),
  } as LogisticsRule]))
  for (const sample of evidence.cases) {
    expect(calculateLogisticsFee(rules.get(sample.channel)!, 'US', sample.weightKg), `${sample.channel}/${sample.weightKg}`).toMatchObject({
      total: sample.total, chargeWeightKg: sample.weightKg,
    })
  }
  for (const rule of rules.values()) {
    for (const weight of [10.0005, 30.001]) expect(calculateLogisticsFee(rule, 'US', weight)).toBeNull()
  }
})
