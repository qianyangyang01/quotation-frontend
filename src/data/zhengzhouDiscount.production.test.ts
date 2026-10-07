import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { calculateLogisticsFee, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import type { LogisticsRateRow } from './logisticsWorkbook'
import { confirmedZhengzhouDiscount, type FreightDiscountSettings } from './freightDiscountSettings'

const path = process.env.ZHENGZHOU_PRODUCTION_CASES
it.skipIf(!path)('matches independent production tariff calculations at every gram in every country and zone', () => {
  const evidence: { channel: { rows: LogisticsRateRow[] }; settings: FreightDiscountSettings; cases: Array<[string, string, number, number]> } = JSON.parse(readFileSync(path!, 'utf8').replace(/^\uFEFF/, ''))
  expect(confirmedZhengzhouDiscount({ channelId: evidence.settings.rules[0]!.channelId, channelCode: '', channelName: '', providerName: '', countries: [] })).toMatchObject(evidence.settings.rules[0]!)
  const rule: LogisticsRule = {
    id: 601, name: '中邮郑州线下E邮宝', englishName: '', type: '专线', currency: 'CNY',
    published: '发布', status: '启用', dates: '', users: '', phoneRequired: false,
    relations: [{ carrier: '燕文', channel: '中邮郑州线下E邮宝', channelCode: 'C-44fc48641d26ef2cab34', discounts: '' }],
    areaCount: 52, priceRowCount: 58, billingVerified: true, prices: evidence.channel.rows.map(normalizeLogisticsPriceRow),
  }
  expect(rule.prices).toHaveLength(58)
  expect(evidence.cases.length).toBeGreaterThan(100000)
  for (const [country, zone, weightKg, expected] of evidence.cases) {
    const region = country === 'CA' ? `燕文｜${rule.name}｜${zone.replace(/^加拿大/, '')}` : zone
    const result = calculateLogisticsFee(rule, country, weightKg, [], undefined, region)
    if (!result || result.total !== expected || result.chargeWeightKg !== Math.max(weightKg, .001)) throw new Error(JSON.stringify({ country, zone, weightKg, expected, actual: result }))
  }
  for (const row of evidence.channel.rows) {
    const region = row.countryCode === 'CA' ? `燕文｜${rule.name}｜${row.zoneName?.replace(/^加拿大/, '')}` : row.zoneName
    expect(calculateLogisticsFee(rule, row.countryCode, row.weightToKg + .0001, [], undefined, region)).toBeNull()
  }
}, 120000)
