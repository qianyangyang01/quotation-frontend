import { expect, it } from 'vitest'
import { calculateLogisticsFee, validBillingSteps, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'

const starts = [0, .201, .454, .701, 1.001, 2.001]
const ends = [.2, .453, .7, 1, 2, 5]
const rates = [86, 88, 93, 93, 96, 96]
const fees = [23, 21, 21, 18, 15, 13]
function rule(model = 'per-kg-1g-ceil-cny', kiloRates = rates, ticketFees = fees): LogisticsRule {
  return { id: 77, name: '快驴 美国商派-特敏感', englishName: '', type: '专线', currency: 'CNY',
    published: '', status: '启用', dates: '', users: '', relations: [], phoneRequired: false,
    areaCount: 1, priceRowCount: 6, billingVerified: true,
    prices: ends.map((end, i) => normalizeLogisticsPriceRow({ areaName: '美国', countryCode: 'US',
      pricingModel: model, weightFromKg: starts[i]!, weightToKg: end, weightFromInclusive: i > 0,
      weightToInclusive: true, minChargeWeightKg: .001, pricePerKg: kiloRates[i]!, registrationFee: ticketFees[i]!,
      etaMinDays: 10, etaMaxDays: 18, allowedMarks: '保健品' })) }
}
it.each([{ name: '商派', rates, fees }, { name: '专线', rates: [90, 90, 93, 93, 96, 96], fees: [23, 23, 23, 21, 18, 15] }])('rounds $name weight before tier selection and complete freight upwards to CNY across every gram', ({ rates, fees }) => {
  const channel = rule('per-kg-1g-ceil-cny', rates, fees)
  for (let grams = 1; grams <= 5000; grams++) {
    const i = ends.findIndex(end => grams <= Math.round(end * 1000))
    const expected = Math.floor((grams * rates[i]! + 999) / 1000) + fees[i]!
    for (const weight of [grams / 1000, (grams * 10 - 5) / 10000]) {
      expect(calculateLogisticsFee(channel, 'US', weight, ['保健品'])).toMatchObject({ total: expected, chargeWeightKg: grams / 1000 })
    }
  }
  for (const weight of [0, -1, 5.000001]) expect(calculateLogisticsFee(channel, 'US', weight, ['保健品'])).toBeNull()
  expect(calculateLogisticsFee(channel, 'GB', .1, ['保健品'])).toBeNull()
})
it('keeps existing gram tariffs unchanged and rejects incompatible increments', () => {
  expect(calculateLogisticsFee(rule('per-kg-1g'), 'US', .001, ['保健品'])?.total).toBe(23.09)
  expect(calculateLogisticsFee(rule(), 'US', .001, ['保健品'])?.total).toBe(24)
  expect(calculateLogisticsFee(rule(), 'US', .201, ['保健品'])?.total).toBe(39)
  expect(validBillingSteps({ pricingModel: 'per-kg-1g-ceil-cny', billingStepKg: .01 })).toBe(false)
  expect(validBillingSteps({ pricingModel: 'per-kg-1g-ceil-cny', billingStepBands: [{ aboveKg: 0, stepKg: .001 }] })).toBe(false)
})
