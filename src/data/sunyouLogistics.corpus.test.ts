import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { calculateLogisticsFee, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import type { LogisticsRateRow } from './logisticsWorkbook'

it.skipIf(!process.env.SUNYOU_BILLING_CASES)('Sunyou: every source price tier and boundary agrees with independent decimal arithmetic and Java', () => {
  const evidence: Array<{
    channel: { channelName: string; providerName: string; rows: LogisticsRateRow[] }
    cases: Array<{ country: string; zoneName: string; weightKg: number; expected: { total: number; chargeWeightKg: number; minChargeWeightKg: number } }>
  }> = JSON.parse(readFileSync(process.env.SUNYOU_BILLING_CASES!, 'utf8'))
  expect(evidence).toHaveLength(3)
  expect(evidence.reduce((sum, entry) => sum + entry.cases.length, 0)).toBe(1149)
  for (const { channel, cases } of evidence) {
    const rule: LogisticsRule = {
      id: 1, name: channel.channelName, englishName: '', type: '专线', currency: 'CNY', published: 'test', status: '启用', dates: '', users: '',
      relations: [{ carrier: channel.providerName, channel: channel.channelName, channelCode: 'test', discounts: '' }],
      phoneRequired: false, areaCount: 1, priceRowCount: channel.rows.length, billingVerified: true, prices: channel.rows.map(normalizeLogisticsPriceRow),
    }
    for (const sample of cases) expect(calculateLogisticsFee(rule, sample.country, sample.weightKg, [], undefined,
      sample.country === 'CA' && sample.zoneName ? `${channel.providerName}｜${channel.channelName}｜${sample.zoneName}` : sample.zoneName), `${channel.channelName}/${sample.country}/${sample.zoneName}/${sample.weightKg}`).toMatchObject({
      total: sample.expected.total, chargeWeightKg: sample.expected.chargeWeightKg, minChargeWeightKg: 0,
    })
    if (channel.channelName === '顺速宝Plus') expect(calculateLogisticsFee(rule, 'RO', .1, [])).toBeNull()
    if (channel.channelName === '顺速宝(特货)') expect(calculateLogisticsFee(rule, 'US', .1, [])).toBeNull()
  }
})
