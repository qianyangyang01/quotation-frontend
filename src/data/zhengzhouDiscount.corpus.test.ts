import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { calculateLogisticsFee, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import type { LogisticsRateRow } from './logisticsWorkbook'
import { confirmedZhengzhouDiscount, type FreightDiscountSettings } from './freightDiscountSettings'

const path = process.env.ZHENGZHOU_DISCOUNT_CASES
it.skipIf(!path)('matches the confirmed Zhengzhou formula for all countries, zones and weight boundaries', () => {
  const evidence: {
    channel: { rows: LogisticsRateRow[] }
    settings: FreightDiscountSettings
    cases: Array<{ input: { country: string; weightKg: number; zoneName: string }; expected: number }>
  } = JSON.parse(readFileSync(path!, 'utf8'))
  expect(confirmedZhengzhouDiscount({ channelId: evidence.settings.rules[0]!.channelId, channelCode: '', channelName: '', providerName: '', countries: [] })).toMatchObject(evidence.settings.rules[0]!)
  const rule: LogisticsRule = {
    id: 601, name: '中邮郑州线下E邮宝', englishName: '', type: '专线', currency: 'CNY',
    published: '发布', status: '启用', dates: '', users: '', phoneRequired: false,
    relations: [{ carrier: '燕文', channel: '中邮郑州线下E邮宝', channelCode: 'C-44fc48641d26ef2cab34', discounts: '' }],
    areaCount: 52, priceRowCount: 58, billingVerified: true,
    prices: evidence.channel.rows.map(normalizeLogisticsPriceRow),
  }
  expect(rule.prices).toHaveLength(58)
  expect(evidence.cases).toHaveLength(348)
  for (const { input, expected } of evidence.cases) {
    const region = input.country === 'CA' ? `燕文｜${rule.name}｜${input.zoneName.replace(/^加拿大/, '')}` : input.zoneName
    expect(calculateLogisticsFee(rule, input.country, input.weightKg, [], undefined, region), JSON.stringify(input))
      .toMatchObject({ total: expected, chargeWeightKg: Math.max(input.weightKg, 0.001) })
  }
  expect(calculateLogisticsFee(rule, 'US', 1, [])).toBeNull()
  expect(calculateLogisticsFee(rule, 'KR', 2.001, [])).toBeNull()
  expect(calculateLogisticsFee(rule, 'VN', 5.001, [])).toBeNull()
})
