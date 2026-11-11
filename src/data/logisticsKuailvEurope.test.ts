import { expect, it } from 'vitest'
import { calculateLogisticsFee, formatLogisticsEta, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'

const prices = [
  ['DE', 0, 1, .001, 70, 27], ['DE', 1, 30, .001, 68, 30],
  ['AT', 0, 1, .1, 93, 35], ['AT', 1.001, 30, .1, 78, 51],
  ['CZ', .05, 1, .05, 106.5, 27.1], ['CZ', 1.001, 30, .05, 83, 42],
  ['RO', 0, 2, .001, 107, 22], ['RO', 2.001, 30, .001, 73.5, 66],
  ['MT', 0, 20, .001, 227, 56],
] as const
const channel: LogisticsRule = { id: 991, name: '欧洲小包-特货', englishName: '', type: '专线', currency: 'CNY', published: '', status: '启用', dates: '', users: '', relations: [], phoneRequired: false, areaCount: 5, priceRowCount: prices.length, billingVerified: true,
  prices: prices.map(([countryCode, weightFromKg, weightToKg, minChargeWeightKg, pricePerKg, registrationFee]) => normalizeLogisticsPriceRow({ countryCode, areaName: countryCode, weightFromKg, weightToKg, minChargeWeightKg, pricePerKg, registrationFee,
    weightFromInclusive: !(countryCode === 'DE' && weightFromKg === 1), weightToInclusive: true, pricingModel: 'per-kg-1g-ceil-cny', etaMinDays: 0, etaMaxDays: 0, etaStatus: 'missing', allowedMarks: '保健品' })) }

it('uses country-specific minimums, rounded grams, decimal ticket fees and confirmed Malta/Romania rules', () => {
  for (const [country, weight, total, charged] of [
    ['DE', 1, 97, 1], ['DE', 1.000001, 99, 1.001], ['AT', .001, 45, .1],
    ['CZ', .0005, 33, .05], ['CZ', 1.000001, 126, 1.001], ['RO', 3, 287, 3], ['MT', 20, 4596, 20],
  ] as const) expect(calculateLogisticsFee(channel, country, weight, ['保健品'])).toMatchObject({ total, chargeWeightKg: charged })
  for (const country of ['GB', 'NO', 'CH', 'RS', 'US']) expect(calculateLogisticsFee(channel, country, .5, ['保健品'])).toBeNull()
  expect(calculateLogisticsFee(channel, 'MT', 20.000001, ['保健品'])).toBeNull()
  expect(formatLogisticsEta(channel.prices[0]!)).toBe('该物流暂无时效说明')
})
