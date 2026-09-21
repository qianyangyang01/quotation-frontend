import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { calculateLogisticsFee, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import type { LogisticsRateRow } from './logisticsWorkbook'

const path = process.env.YANWEN_WANBANG_BILLING_CASES
it.skipIf(!path)('matches the four Yanwen/Wanbang tariffs and minimum weight boundaries', () => {
  const evidence: { channels: Array<{ channelName: string; providerName: string; rows: LogisticsRateRow[] }>; cases: Array<{ channel: string; country: string; zoneName: string; weightKg: number; expected: { total: number; chargeWeightKg: number; minChargeWeightKg: number } }> } = JSON.parse(readFileSync(path!, 'utf8'))
  const rules = new Map(evidence.channels.map((c, i) => [c.channelName, {
    id: i + 1, name: c.channelName, englishName: '', type: '专线', currency: 'CNY', published: 'test', status: '启用', dates: '', users: '',
    relations: [{ carrier: c.providerName, channel: c.channelName, channelCode: `YW${i}`, discounts: '' }],
    phoneRequired: false, areaCount: 1, priceRowCount: c.rows.length, billingVerified: true, prices: c.rows.map(normalizeLogisticsPriceRow),
  } as LogisticsRule]))
  expect(evidence.channels).toHaveLength(4)
  expect(evidence.cases.length).toBeGreaterThan(400)
  for (const sample of evidence.cases) expect(calculateLogisticsFee(rules.get(sample.channel)!, sample.country, sample.weightKg, [], undefined, sample.zoneName), `${sample.channel}/${sample.country}/${sample.zoneName}/${sample.weightKg}`).toMatchObject({
    total: sample.expected.total, chargeWeightKg: sample.expected.chargeWeightKg, minChargeWeightKg: sample.expected.minChargeWeightKg,
  })
})
