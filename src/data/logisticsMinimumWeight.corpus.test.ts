import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { calculateLogisticsFee, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import type { LogisticsRateRow } from './logisticsWorkbook'

const corpusPath = process.env.LOGISTICS_MINIMUM_CASES
it.skipIf(!corpusPath)('agrees with backend minimum billing for original workbook cases', () => {
  const cases: Array<{ provider: string; channel: string; actual: number; price: LogisticsRateRow; expected: { total: number; chargeWeightKg: number } }> = JSON.parse(readFileSync(corpusPath!, 'utf8'))
  expect(cases.length).toBeGreaterThan(0)
  const providers = new Set<string>()
  for (const item of cases) {
    providers.add(item.provider)
    const rule: LogisticsRule = { id: 1, name: item.channel, englishName: '', type: '专线', currency: 'CNY', published: 'audit',
      status: '启用', dates: '', users: '', phoneRequired: false, areaCount: 1, priceRowCount: 1, billingVerified: true,
      relations: [{ carrier: item.provider, channel: item.channel, channelCode: 'AUDIT', discounts: '' }], prices: [normalizeLogisticsPriceRow({ ...item.price, quoteReady: true })] }
    const zone = (item.price.zoneName || '').split(/[/／、,，;；|]/)[0] || ''
    const region = item.price.countryCode === 'CA' && zone ? `${item.provider}｜${item.channel}｜${zone.replace('加拿大', '')}` : zone
    const result = calculateLogisticsFee(rule, item.price.countryCode, item.actual, ['普货'], undefined, region)
    expect(result, `${item.provider}/${item.channel}/${item.price.countryCode}`).toMatchObject({ total: item.expected.total, chargeWeightKg: item.expected.chargeWeightKg })
  }
  expect(providers.size).toBe(11)
})
