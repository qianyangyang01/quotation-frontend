import { expect, it } from 'vitest'
import { calculateLogisticsFee, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'

const rule: LogisticsRule = {
  id: 1, name: '巧捷小包快递特惠包税B', englishName: '', type: '专线', currency: 'CNY', published: 'test', status: '启用', dates: '', users: '',
  relations: [{ carrier: '巧捷', channel: 'B', channelCode: 'QEUFAB', discounts: '' }], phoneRequired: false, areaCount: 1, priceRowCount: 2, billingVerified: true,
  prices: [91, 121].map((amount, i) => normalizeLogisticsPriceRow({ areaName: '美国', countryCode: 'US', pricingModel: 'per-piece-500g',
    weightFromKg: i * .5, weightToKg: (i + 1) * .5, weightFromInclusive: false, weightToInclusive: true,
    minChargeWeightKg: .5, intervalPrice: amount, pricePerKg: 0, registrationFee: 3 })),
}
it('pads and rounds each parcel once then adds its fee once', () => {
  for (const actual of [.000001, .012, .499999, .5, .500001, .501, .999999, 1]) {
    expect(calculateLogisticsFee(rule, 'US', actual)).toMatchObject({ total: actual <= .5 ? 94 : 124, chargeWeightKg: actual <= .5 ? .5 : 1, actualWeightKg: actual })
  }
  for (const actual of [0, -1, 1.000001]) expect(calculateLogisticsFee(rule, 'US', actual)).toBeNull()
})
it('blocks invalid minima and tariffs and conflicting updates', () => {
  for (const change of [{ minChargeWeightKg: .05 }, { weightToKg: .6 }, { weightFromInclusive: true }, { intervalPrice: 0 },
    { intervalPrice: -91 }, { pricePerKg: 91 }, { pricingModel: 'interval' }, { surcharge: 5 }, { surcharge: -5 }]) {
    expect(calculateLogisticsFee({ ...rule, prices: [{ ...rule.prices[0]!, ...change }] }, 'US', .1), JSON.stringify(change)).toBeNull()
  }
  expect(calculateLogisticsFee({ ...rule, prices: [rule.prices[0]!, { ...rule.prices[0]!, intervalPrice: 90 }] }, 'US', .1)).toBeNull()
})
