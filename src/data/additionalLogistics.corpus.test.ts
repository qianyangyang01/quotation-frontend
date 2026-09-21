import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { calculateLogisticsFee, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import type { LogisticsRateRow } from './logisticsWorkbook'

for (const [label, path] of [['通邮标准敏感', process.env.TONGYOU_BILLING_CASES], ['极通定制纯电', process.env.JITONG_BILLING_CASES]]) {
  it.skipIf(!path)(`${label}: source prices and minimum boundaries agree with Java`, () => {
    const { channel, cases }: { channel: { channelName: string; providerName: string; rows: LogisticsRateRow[] }; cases: Array<{ country: string; zoneName?: string; weightKg: number; expected: { total: number; chargeWeightKg: number; minChargeWeightKg: number } }> } = JSON.parse(readFileSync(path!, 'utf8'))
    const rule: LogisticsRule = {
      id: 1, name: channel.channelName, englishName: '', type: '专线', currency: 'CNY', published: 'test', status: '启用', dates: '', users: '',
      relations: [{ carrier: channel.providerName, channel: channel.channelName, channelCode: 'test', discounts: '' }],
      phoneRequired: false, areaCount: 1, priceRowCount: channel.rows.length, billingVerified: true, prices: channel.rows.map(normalizeLogisticsPriceRow),
    }
    expect(cases.length).toBeGreaterThan(290)
    for (const sample of cases) expect(calculateLogisticsFee(rule, sample.country, sample.weightKg, [], undefined, sample.zoneName), `${sample.country}/${sample.zoneName}/${sample.weightKg}`).toMatchObject({
      total: sample.expected.total, chargeWeightKg: sample.expected.chargeWeightKg, minChargeWeightKg: sample.expected.minChargeWeightKg,
    })
  })
}
