import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { calculateLogisticsFee, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import type { LogisticsRateRow } from './logisticsWorkbook'

const evidencePath = process.env.WANBANG_CLOTHING_BILLING_CASES
it.skipIf(!evidencePath)('reconciles 44 clothing tariffs and boundaries against original workbook and backend', () => {
  const evidence: {
    channels: Array<{ channelName: string; providerName: string; logisticsAttribute: string; rows: LogisticsRateRow[] }>
    cases: Array<{ country: string; weightKg: number; total: number }>
    rejected: Array<{ country: string; weightKg: number }>
  } = JSON.parse(readFileSync(evidencePath!, 'utf8'))
  expect(evidence.channels).toHaveLength(1)
  expect(evidence.cases).toHaveLength(132)
  expect(evidence.rejected).toHaveLength(44)
  const channel = evidence.channels[0]!
  expect(channel.logisticsAttribute).toBe('服装')
  const rule = {
    id: 1, name: channel.channelName, englishName: '', type: '专线', currency: 'CNY', published: 'test', status: '启用', dates: '', users: '',
    relations: [{ carrier: channel.providerName, channel: channel.channelName, channelCode: 'EUSLPHFZ-P', discounts: '' }],
    phoneRequired: false, areaCount: 14, priceRowCount: 44, billingVerified: true,
    prices: channel.rows.map(normalizeLogisticsPriceRow),
  } as LogisticsRule
  for (const sample of evidence.cases) expect(calculateLogisticsFee(rule, sample.country, sample.weightKg), JSON.stringify(sample)).toMatchObject({ total: sample.total, chargeWeightKg: sample.weightKg })
  for (const sample of evidence.rejected) expect(calculateLogisticsFee(rule, sample.country, sample.weightKg), JSON.stringify(sample)).toBeNull()
})
